package com.example.mcpbridge.handlers;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.magicdraw.openapi.uml.ReadOnlyElementException;
import com.nomagic.magicdraw.openapi.uml.SessionManager;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * 요소 간 관계(Relation) 핸들러
 *
 * POST   /project/{id}/relation          → handleCreate  (관계 생성)
 * GET    /project/{id}/relations         → handleList    (관계 목록 조회)
 * DELETE /project/{id}/relation/{rid}   → handleDelete  (관계 삭제)
 *
 * 지원하는 관계 타입별 source/target 연결:
 *   - Dependency 계열 (Dependency, Usage, Realization, Abstraction):
 *       client = source,  supplier = target
 *   - Generalization:
 *       specific = source(하위 요소),  general = target(상위 요소)
 *       [Classifier가 Namespace를 상속하므로 리플렉션으로 처리]
 *   - Connector:
 *       ConnectorEnd 2개 생성 후 각각의 role을 source/target Port로 설정 (리플렉션)
 *   - Association 등 기타:
 *       요소 생성만 수행, source/target 자동 연결 미지원
 */
public class RelationsHandler extends BaseHandler {

    // 관계 목록 조회 시 식별할 humanType 집합
    private static final Set<String> RELATION_TYPES = new HashSet<>(Arrays.asList(
        "Dependency", "Usage", "Realization", "Abstraction",
        "Generalization", "InterfaceRealization",
        "Association", "Connector",
        "PackageMerge", "PackageImport",
        "InformationFlow", "Deployment", "Manifestation",
        // SysML Requirement relations (Abstraction + stereotype)
        "DeriveReqt", "Satisfy", "Verify", "Trace", "Copy", "Refine"
    ));

    // SysML 요구사항 관계: Abstraction 기반 + 스테레오타입 적용
    // key = 소문자 타입명, value = [기본타입, 스테레오타입명]
    private static final Map<String, String[]> REQ_RELATION_MAP = new HashMap<>();
    static {
        REQ_RELATION_MAP.put("derivereqt", new String[]{"Abstraction", "DeriveReqt"});
        REQ_RELATION_MAP.put("satisfy",    new String[]{"Abstraction", "Satisfy"});
        REQ_RELATION_MAP.put("verify",     new String[]{"Abstraction", "Verify"});
        REQ_RELATION_MAP.put("trace",      new String[]{"Abstraction", "Trace"});
        REQ_RELATION_MAP.put("copy",       new String[]{"Abstraction", "Copy"});
        REQ_RELATION_MAP.put("refine",     new String[]{"Abstraction", "Refine"});
    }

    // Generalization/Classifier는 Namespace 계층 의존성 문제로 직접 import 회피 → Class.forName 사용
    private static final String GENERALIZATION_CLASS =
        "com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Generalization";
    private static final String CLASSIFIER_CLASS =
        "com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Classifier";

    /** HttpHandler 인터페이스 구현 — ProjectDispatcherHandler를 통해 호출됨 */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        sendError(exchange, 400, "ProjectDispatcherHandler를 통해 호출하세요: /project/{id}/relation");
    }

    // ── POST /project/{id}/relation  (관계 생성) ────────────────────────────

    @SuppressWarnings("unchecked")
    public void handleCreate(HttpExchange exchange, Project project) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "POST만 지원합니다."); return;
        }

        JSONObject json;
        try { json = (JSONObject) new JSONParser().parse(readRequestBody(exchange)); }
        catch (Exception e) { sendError(exchange, 400, "잘못된 JSON: " + e.getMessage()); return; }

        String relationType  = (String) json.get("type");
        String sourceId      = (String) json.get("sourceId");
        String targetId      = (String) json.get("targetId");
        String name          = (String) json.get("name");
        String ownerId       = (String) json.get("ownerId");        // optional: explicit owner
        String sourcePartId  = (String) json.get("sourcePartId");   // Connector: source Port의 Part Property ID
        String targetPartId  = (String) json.get("targetPartId");   // Connector: target Port의 Part Property ID
        if (relationType == null || sourceId == null || targetId == null) {
            sendError(exchange, 400, "type, sourceId, targetId는 필수입니다."); return;
        }

        Element source = resolveElement(project, sourceId);
        Element target = resolveElement(project, targetId);
        if (source == null) { sendError(exchange, 404, "source 요소를 찾을 수 없습니다: " + sourceId); return; }
        if (target == null) { sendError(exchange, 404, "target 요소를 찾을 수 없습니다: " + targetId); return; }

        SessionManager sm = SessionManager.getInstance();
        sm.createSession(project, "MCP: 관계 생성 - " + relationType);
        try {
            // owner 결정: ownerId 명시 → 명시된 요소 사용
            // Generalization/InterfaceRealization → source 자체
            // 그 외 → source의 owner (패키지/블록)
            Element owner;
            if (ownerId != null && !ownerId.isEmpty()) {
                owner = resolveElement(project, ownerId);
                if (owner == null) {
                    sm.cancelSession(project);
                    sendError(exchange, 404, "owner 요소를 찾을 수 없습니다: " + ownerId); return;
                }
            } else if ("Generalization".equals(relationType) || "InterfaceRealization".equals(relationType)) {
                owner = source;
            } else {
                owner = source.getOwner() != null ? source.getOwner() : project.getPrimaryModel();
            }
            String[] reqMapping = REQ_RELATION_MAP.get(relationType.toLowerCase());
            Element relation;
            if (reqMapping != null) {
                relation = createRelationByType(project, reqMapping[0]);
                applyStereotype(project, relation, reqMapping[1]);
            } else {
                relation = createRelationByType(project, relationType);
            }
            ModelElementsManager.getInstance().addElement(relation, owner);

            // 관계 타입별 source/target 엔드포인트 연결
            connectEndpoints(project, relation, source, target, sourcePartId, targetPartId);

            if (name != null && !name.isEmpty() && relation instanceof NamedElement)
                ((NamedElement) relation).setName(name);

            sm.closeSession(project);
            String relId   = escapeJson(relation.getLocalID());
            String relName = relation instanceof NamedElement
                ? escapeJson(((NamedElement) relation).getName()) : "";
            sendJson(exchange, 201, String.format(
                "{\"id\":\"%s\",\"name\":\"%s\",\"type\":\"%s\",\"sourceId\":\"%s\",\"targetId\":\"%s\"}",
                relId, relName, escapeJson(relationType),
                escapeJson(sourceId), escapeJson(targetId)));
        } catch (ReadOnlyElementException e) {
            sm.cancelSession(project);
            sendError(exchange, 403, "읽기 전용: " + e.getMessage());
        } catch (Exception e) {
            sm.cancelSession(project);
            sendError(exchange, 500, "관계 생성 실패: " + e.getMessage());
        }
    }

    // ── GET /project/{id}/relations  (관계 목록 조회) ───────────────────────

    public void handleList(HttpExchange exchange, Project project) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "GET만 지원합니다."); return;
        }
        try {
            StringJoiner items = new StringJoiner(",", "[", "]");
            collectRelations(project.getPrimaryModel(), items, 0, 10);
            sendJson(exchange, 200, "{\"relations\": " + items + "}");
        } catch (Exception e) {
            sendError(exchange, 500, "관계 목록 조회 실패: " + e.getMessage());
        }
    }

    // ── DELETE /project/{id}/relation/{rid}  (관계 삭제) ────────────────────

    public void handleDelete(HttpExchange exchange, Project project, String relationId) throws IOException {
        if (!"DELETE".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "DELETE만 지원합니다."); return;
        }
        Element el = resolveElement(project, relationId);
        if (el == null) { sendError(exchange, 404, "관계를 찾을 수 없습니다: " + relationId); return; }

        SessionManager sm = SessionManager.getInstance();
        sm.createSession(project, "MCP: 관계 삭제 - " + relationId);
        try {
            ModelElementsManager.getInstance().removeElement(el);
            sm.closeSession(project);
            sendJson(exchange, 200, "{\"deleted\":\"" + escapeJson(relationId) + "\"}");
        } catch (ReadOnlyElementException e) {
            sm.cancelSession(project);
            sendError(exchange, 403, "읽기 전용: " + e.getMessage());
        } catch (Exception e) {
            sm.cancelSession(project);
            sendError(exchange, 500, "관계 삭제 실패: " + e.getMessage());
        }
    }

    // ── 내부 유틸리티 ────────────────────────────────────────────────────────

    /**
     * 관계 타입별 source/target 엔드포인트 연결.
     *
     * Dependency 계열: client(source), supplier(target)
     * Generalization : specific(source/하위), general(target/상위)  — 리플렉션 사용
     * Connector      : ConnectorEnd 2개 생성 후 role=Port 설정   — 리플렉션 사용
     * 기타           : 자동 연결 미지원 (요소 생성만 수행)
     */
    private void connectEndpoints(Project project, Element relation, Element source, Element target,
                                  String sourcePartId, String targetPartId) {
        // Dependency 계열: Usage, Realization, Abstraction도 Dependency를 상속
        if (relation instanceof com.nomagic.uml2.ext.magicdraw.classes.mddependencies.Dependency) {
            com.nomagic.uml2.ext.magicdraw.classes.mddependencies.Dependency dep =
                (com.nomagic.uml2.ext.magicdraw.classes.mddependencies.Dependency) relation;
            if (source instanceof NamedElement) dep.getClient().add((NamedElement) source);
            if (target instanceof NamedElement) dep.getSupplier().add((NamedElement) target);
            return;
        }

        // Generalization: Classifier를 직접 import하면 Namespace 슈퍼클래스 JAR 필요
        // → Class.forName 리플렉션으로 우회 (DiagramsHandler의 Namespace 처리와 동일한 패턴)
        try {
            Class<?> genClass = Class.forName(GENERALIZATION_CLASS);
            if (genClass.isInstance(relation)) {
                Class<?> clsClass = Class.forName(CLASSIFIER_CLASS);
                if (clsClass.isInstance(source) && clsClass.isInstance(target)) {
                    genClass.getMethod("setSpecific", clsClass).invoke(relation, source);
                    genClass.getMethod("setGeneral", clsClass).invoke(relation, target);
                }
                return;
            }
        } catch (Exception ignored) {
            // Classifier 타입 불일치 또는 리플렉션 실패 시 무시
            // → 관계 요소는 생성되지만 specific/general 미설정 상태
        }

        // Connector: ConnectorEnd 2개를 생성하여 source/target Port에 role로 연결
        // uml2 JAR의 compositestructures 패키지 — 리플렉션으로 안전하게 처리
        try {
            Class<?> connClass = Class.forName(
                "com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.Connector");
            if (connClass.isInstance(relation)) {
                Class<?> ceClass = Class.forName(
                    "com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.ConnectableElement");
                // Connector 생성 시 기본 end가 자동 생성되는 경우가 있음 → 기존 end 재사용
                @SuppressWarnings("unchecked")
                java.util.List<Object> ends =
                    (java.util.List<Object>) connClass.getMethod("getEnd").invoke(relation);
                // 부족한 개수만큼만 factory로 추가 생성
                Method createEnd = project.getElementsFactory()
                    .getClass().getMethod("createConnectorEndInstance");
                while (ends.size() < 2) {
                    ends.add(createEnd.invoke(project.getElementsFactory()));
                }
                Object srcEnd = ends.get(0);
                Object tgtEnd = ends.get(1);
                // ConnectorEnd.setRole(ConnectableElement)로 Port 지정
                Method setRole = srcEnd.getClass().getMethod("setRole", ceClass);
                if (ceClass.isInstance(source)) setRole.invoke(srcEnd, source);
                if (ceClass.isInstance(target)) setRole.invoke(tgtEnd, target);
                // ConnectorEnd.setPartWithPort(Property) — UML 레벨 Part 인스턴스 컨텍스트
                // + SysML «NestedConnectorEnd» 스테레오타입의 propertyPath 태그값 설정
                // (MagicDraw SysML 커넥터 해석에 필수)
                try {
                    Class<?> propClass = Class.forName(
                        "com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property");
                    Element srcPart = sourcePartId != null ? resolveElement(project, sourcePartId) : null;
                    Element tgtPart = targetPartId != null ? resolveElement(project, targetPartId) : null;
                    // 자동 해결: sourcePartId/targetPartId 미지정 시 relation의 owner(상위 블록) 아래에서
                    // Port를 소유한 Part Property 를 탐색
                    if (srcPart == null) srcPart = findPartPropertyForPort(relation.getOwner(), source, propClass);
                    if (tgtPart == null) tgtPart = findPartPropertyForPort(relation.getOwner(), target, propClass);

                    if (srcPart != null && propClass.isInstance(srcPart)) {
                        srcEnd.getClass().getMethod("setPartWithPort", propClass)
                            .invoke(srcEnd, srcPart);
                        applyNestedConnectorEnd(project, (Element) srcEnd, srcPart);
                    } else {
                        System.err.println("[ConnectorEnd] source partWithPort not resolved");
                    }
                    if (tgtPart != null && propClass.isInstance(tgtPart)) {
                        tgtEnd.getClass().getMethod("setPartWithPort", propClass)
                            .invoke(tgtEnd, tgtPart);
                        applyNestedConnectorEnd(project, (Element) tgtEnd, tgtPart);
                    } else {
                        System.err.println("[ConnectorEnd] target partWithPort not resolved");
                    }
                } catch (Exception e) {
                    System.err.println("[ConnectorEnd part/propertyPath] 실패: " + e.getMessage());
                }
                return;
            }
        } catch (Exception e) {
            System.err.println("[Connector] ConnectorEnd 연결 실패: " + e.getMessage());
            // 실패 시 Connector 요소는 생성됐으나 연결 미완성 상태로 유지
        }

        // Association 등 기타: 자동 연결 미지원 (요소 생성만 수행)
    }

    /** 모델 트리를 재귀 탐색하여 관계 요소 수집 */
    private void collectRelations(Element parent, StringJoiner joiner, int depth, int maxDepth) {
        for (Element child : parent.getOwnedElement()) {
            if (isRelationElement(child)) {
                joiner.add(relationToJson(child));
            }
            // 관계 요소 자체는 재귀 탐색 불필요 (하위 요소 없음)
            if (depth < maxDepth && !isRelationElement(child)
                    && !child.getOwnedElement().isEmpty()) {
                collectRelations(child, joiner, depth + 1, maxDepth);
            }
        }
    }

    /** humanType 기반 관계 요소 판별 */
    private boolean isRelationElement(Element el) {
        String ht = el.getHumanType();
        return ht != null && RELATION_TYPES.contains(ht);
    }

    private String relationToJson(Element el) {
        String[] endpoints = getRelationEndpointIds(el);
        return String.format(
            "{\"id\":\"%s\",\"name\":\"%s\",\"type\":\"%s\",\"sourceId\":\"%s\",\"targetId\":\"%s\"}",
            escapeJson(el.getLocalID()),
            el instanceof NamedElement ? escapeJson(((NamedElement) el).getName()) : "",
            escapeJson(el.getHumanType()),
            escapeJson(endpoints[0]),
            escapeJson(endpoints[1]));
    }

    /**
     * ConnectorEnd에 SysML «NestedConnectorEnd» 스테레오타입 적용 + propertyPath 태그 설정.
     * propertyPath = [partProperty] (커넥터 end가 가리키는 part property 경로).
     * MagicDraw SysML IBD에서 커넥터 라인이 올바르게 표시되기 위한 필수 설정.
     */
    private void applyNestedConnectorEnd(Project project, Element end, Element partProperty) {
        try {
            Class<?> shClass = Class.forName("com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper");
            Class<?> stClass = Class.forName("com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype");

            // 1. Stereotype 객체 탐색 — 여러 경로 시도 + 자세한 로그
            Object stereotype = findNestedConnectorEndStereotype(project, shClass);
            if (stereotype == null) {
                System.err.println("[NestedConnectorEnd] Stereotype not found in any profile. Skipping.");
                return;
            }

            // 2. addStereotype(Element, Stereotype) 직접 호출
            boolean applied = false;
            try {
                Method addS = shClass.getMethod("addStereotype", Element.class, stClass);
                addS.invoke(null, end, stereotype);
                applied = true;
                System.err.println("[NestedConnectorEnd] addStereotype OK on end=" + end.getLocalID());
            } catch (java.lang.reflect.InvocationTargetException ite) {
                Throwable c = ite.getCause();
                System.err.println("[NestedConnectorEnd] addStereotype failed: "
                    + (c != null ? c.getClass().getName() + ": " + c.getMessage() : ite.getMessage()));
            } catch (Exception e) {
                System.err.println("[NestedConnectorEnd] addStereotype error: "
                    + e.getClass().getName() + ": " + e.getMessage());
            }

            // 3. 적용 확인 — 실패 시 isStereotypeApplied 재확인
            if (!applied) {
                try {
                    Method isAppl = shClass.getMethod("isStereotypeApplied", Element.class, stClass);
                    Object r = isAppl.invoke(null, end, stereotype);
                    if (Boolean.TRUE.equals(r)) {
                        applied = true;
                        System.err.println("[NestedConnectorEnd] already applied on end=" + end.getLocalID());
                    }
                } catch (Exception ignore) {}
            }
            if (!applied) {
                System.err.println("[NestedConnectorEnd] Stereotype application failed — propertyPath not settable");
                return;
            }

            // 4. propertyPath 태그 설정
            java.util.List<Element> path = new java.util.ArrayList<>();
            path.add(partProperty);
            try {
                Method setVal = shClass.getMethod("setStereotypePropertyValue",
                    Project.class, Element.class, stClass, String.class, Object.class);
                setVal.invoke(null, project, end, stereotype, "propertyPath", path);
                System.err.println("[NestedConnectorEnd] propertyPath set to ["
                    + (partProperty instanceof NamedElement
                        ? ((NamedElement) partProperty).getName() : partProperty.getLocalID()) + "]");
            } catch (NoSuchMethodException e1) {
                Method setVal = shClass.getMethod("setStereotypePropertyValue",
                    Element.class, stClass, String.class, Object.class);
                setVal.invoke(null, end, stereotype, "propertyPath", path);
                System.err.println("[NestedConnectorEnd] propertyPath set (4-arg)");
            } catch (java.lang.reflect.InvocationTargetException ite) {
                Throwable c = ite.getCause();
                System.err.println("[NestedConnectorEnd] setStereotypePropertyValue failed: "
                    + (c != null ? c.getClass().getName() + ": " + c.getMessage() : ite.getMessage()));
            }
        } catch (Exception e) {
            System.err.println("[NestedConnectorEnd] unexpected: "
                + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    /**
     * 주어진 port 를 소유한 Block 을 type 으로 하는 Part Property 를
     * ownerBlock(Connector 소유자) 아래에서 탐색.
     * 일치하는 Property 가 없으면 null.
     */
    private Element findPartPropertyForPort(Element ownerBlock, Element port, Class<?> propClass) {
        if (ownerBlock == null || port == null) return null;
        Element portOwner = port.getOwner();   // Block that owns the port
        if (portOwner == null) return null;
        for (Element child : ownerBlock.getOwnedElement()) {
            if (!propClass.isInstance(child)) continue;
            try {
                Object type = child.getClass().getMethod("getType").invoke(child);
                if (type != null && type.equals(portOwner)) return child;
            } catch (Exception ignore) {}
        }
        return null;
    }

    /**
     * SysML «NestedConnectorEnd» Stereotype 객체 탐색.
     * 시도 순서: (1) getStereotype(P,name) (2) 프로파일명 지정 (3) URI 기반 탐색
     */
    private Object findNestedConnectorEndStereotype(Project project, Class<?> shClass) {
        String name = "NestedConnectorEnd";
        // 1) getStereotype(Project, String)
        try {
            Method m = shClass.getMethod("getStereotype", Project.class, String.class);
            Object s = m.invoke(null, project, name);
            if (s != null) { System.err.println("[NestedConnectorEnd] found via getStereotype(P,name)"); return s; }
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable c = ite.getCause();
            System.err.println("[NestedConnectorEnd] getStereotype(P,name) threw: "
                + (c != null ? c.getMessage() : ite.getMessage()));
        } catch (Exception e) {
            System.err.println("[NestedConnectorEnd] getStereotype(P,name) err: " + e.getMessage());
        }
        // 2) 프로파일명 지정
        for (String profile : new String[]{"SysML", "MD Customization for SysML", "SysML Profile"}) {
            try {
                Method m = shClass.getMethod("getStereotype", Project.class, String.class, String.class);
                Object s = m.invoke(null, project, name, profile);
                if (s != null) { System.err.println("[NestedConnectorEnd] found via profile=" + profile); return s; }
            } catch (Exception ignore) {}
        }
        // 3) 프로젝트 내 모든 스테레오타입 스캔 (getAllStereotypes 또는 정적 함수)
        try {
            Method m = shClass.getMethod("getAllStereotypes", Project.class);
            Object list = m.invoke(null, project);
            if (list instanceof java.util.Collection) {
                for (Object s : (java.util.Collection<?>) list) {
                    try {
                        Object n = s.getClass().getMethod("getName").invoke(s);
                        if (name.equals(n)) {
                            System.err.println("[NestedConnectorEnd] found via getAllStereotypes scan");
                            return s;
                        }
                    } catch (Exception ignore) {}
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    /**
     * ElementsFactory 리플렉션을 통한 관계 요소 생성.
     * 예) "Dependency" → factory.createDependencyInstance()
     */
    private Element createRelationByType(Project project, String typeName) throws Exception {
        Object factory = project.getElementsFactory();
        String methodName = "create" + typeName + "Instance";
        try {
            Method m = factory.getClass().getMethod(methodName);
            Object result = m.invoke(factory);
            if (result instanceof Element) return (Element) result;
            throw new Exception("생성된 객체가 Element 타입이 아닙니다: " + typeName);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new Exception("관계 생성 중 오류: " + (cause != null ? cause.getMessage() : e.getMessage()));
        } catch (NoSuchMethodException e) {
            throw new Exception(
                "지원하지 않는 관계 타입 (팩토리 메서드 없음): " + typeName
                + " (필요 메서드: " + methodName + ")");
        }
    }
}
