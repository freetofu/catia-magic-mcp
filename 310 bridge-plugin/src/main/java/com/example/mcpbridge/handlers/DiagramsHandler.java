package com.example.mcpbridge.handlers;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.magicdraw.openapi.uml.SessionManager;
import com.nomagic.magicdraw.uml.DiagramTypeConstants;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.StringJoiner;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * 다이어그램 목록 조회 / 생성 핸들러
 *
 * 2026x 변경사항:
 *  - getDiagramFactory() 제거됨 → ModelElementsManager.createDiagram() 사용
 *  - Diagram/Namespace 타입은 컴파일 시 슈퍼클래스(MDObject, RefObject) JAR 필요
 *    → JAR 의존 없이 리플렉션 + getHumanType() 으로 대체
 */
public class DiagramsHandler extends BaseHandler {

    // NOTE: Namespace, Diagram 클래스를 직접 import하지 않음.
    //       이 두 클래스의 슈퍼클래스(MDObject, RefObject)가 클래스패스에 없으면
    //       컴파일 에러가 발생하기 때문. 리플렉션으로 우회.
    private static final String NAMESPACE_CLASS = "com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Namespace";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        sendError(exchange, 400, "ProjectDispatcherHandler를 통해 호출하세요: /project/{id}/diagrams");
    }

    public void handleCollection(HttpExchange exchange, Project project) throws IOException {
        if      ("GET".equals(exchange.getRequestMethod()))  listDiagrams(exchange, project);
        else if ("POST".equals(exchange.getRequestMethod())) createDiagram(exchange, project);
        else sendError(exchange, 405, "Method Not Allowed");
    }

    public void handleSingle(HttpExchange exchange, Project project, String diagramId) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { sendError(exchange, 405, "Method Not Allowed"); return; }
        getDiagramById(exchange, project, diagramId);
    }

    // ── 다이어그램 목록 ──────────────────────────────────────────────────────────
    private void listDiagrams(HttpExchange exchange, Project project) throws IOException {
        try {
            StringJoiner items = new StringJoiner(",", "[", "]");
            collectDiagrams(project.getPrimaryModel(), items, 0, 10);
            sendJson(exchange, 200, "{\"diagrams\": " + items + "}");
        } catch (Exception e) { sendError(exchange, 500, "다이어그램 목록 조회 실패: " + e.getMessage()); }
    }

    /**
     * 모델 요소를 재귀 탐색하여 Diagram 요소를 수집.
     * instanceof Diagram 대신 getHumanType() 로 식별 (Diagram 타입 직접 참조 금지).
     */
    private void collectDiagrams(Element parent, StringJoiner joiner, int depth, int maxDepth) {
        for (Element child : parent.getOwnedElement()) {
            if (isDiagramElement(child)) {
                joiner.add(elementToDiagramJson(child));
            } else if (depth < maxDepth) {
                collectDiagrams(child, joiner, depth + 1, maxDepth);
            }
        }
    }

    // ── 다이어그램 단건 조회 ──────────────────────────────────────────────────────
    private void getDiagramById(HttpExchange exchange, Project project, String diagramId) throws IOException {
        try {
            Element found = findDiagramById(project.getPrimaryModel(), diagramId, 0, 10);
            if (found == null) { sendError(exchange, 404, "다이어그램을 찾을 수 없습니다: " + diagramId); return; }
            sendJson(exchange, 200, elementToDiagramJson(found));
        } catch (Exception e) { sendError(exchange, 500, "다이어그램 조회 실패: " + e.getMessage()); }
    }

    private Element findDiagramById(Element parent, String id, int depth, int maxDepth) {
        for (Element child : parent.getOwnedElement()) {
            if (isDiagramElement(child) && id.equals(child.getLocalID())) return child;
            if (depth < maxDepth) {
                Element found = findDiagramById(child, id, depth + 1, maxDepth);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ── 다이어그램 생성 ──────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private void createDiagram(HttpExchange exchange, Project project) throws IOException {
        JSONObject json;
        try { json = (JSONObject) new JSONParser().parse(readRequestBody(exchange)); }
        catch (Exception e) { sendError(exchange, 400, "잘못된 JSON: " + e.getMessage()); return; }

        String typeName = (String) json.get("type");
        String name     = (String) json.get("name");
        String ownerId  = (String) json.get("ownerId");
        if (typeName == null || name == null) { sendError(exchange, 400, "type과 name은 필수입니다."); return; }

        SessionManager sm = SessionManager.getInstance();
        sm.createSession(project, "MCP: 다이어그램 생성 - " + name);
        try {
            Element owner = ownerId != null ? resolveElement(project, ownerId) : project.getPrimaryModel();
            if (owner == null) { sendError(exchange, 404, "owner를 찾을 수 없습니다: " + ownerId); sm.cancelSession(project); return; }

            // Namespace 타입 직접 참조 금지 → Class.forName() + 리플렉션으로 createDiagram 호출
            Class<?> nsClass = Class.forName(NAMESPACE_CLASS);
            if (!nsClass.isInstance(owner)) {
                sendError(exchange, 400, "owner가 Namespace 타입이 아닙니다.");
                sm.cancelSession(project);
                return;
            }
            Method createDiagram = ModelElementsManager.class.getMethod(
                "createDiagram", String.class, nsClass);
            Object created = createDiagram.invoke(
                ModelElementsManager.getInstance(), resolveDiagramType(typeName), owner);

            // 이름 설정 - NamedElement는 직접 참조 가능 (슈퍼클래스 의존성 없음)
            if (created instanceof NamedElement) ((NamedElement) created).setName(name);

            sm.closeSession(project);

            if (created instanceof Element) {
                revealInContainmentTree(project, (Element) created);  // 생성 직후 모델 탐색기에서 바로 보이도록 펼치기
                sendJson(exchange, 201, elementToDiagramJson((Element) created));
            } else {
                sendJson(exchange, 201, String.format(
                    "{\"name\":\"%s\",\"type\":\"%s\"}", escapeJson(name), escapeJson(typeName)));
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            sm.cancelSession(project);
            Throwable cause = e.getCause();
            sendError(exchange, 500, "다이어그램 생성 실패: " + (cause != null ? cause.getMessage() : e.getMessage()));
        } catch (Exception e) {
            sm.cancelSession(project);
            sendError(exchange, 500, "다이어그램 생성 실패: " + e.getMessage());
        }
    }

    // ── 유틸리티 ─────────────────────────────────────────────────────────────────

    /** Diagram 요소 여부 확인 (Diagram 타입 직접 참조 없이 humanType 로 판별) */
    private boolean isDiagramElement(Element el) {
        String ht = el.getHumanType();
        return ht != null && ht.contains("Diagram");
    }

    /**
     * 다이어그램 타입 별칭 → MagicDraw 정식 타입명 변환.
     *
     * 버그 수정: bdd → UML_CLASS_DIAGRAM (일반 UML 클래스 다이어그램) 오매핑
     *           → "SysML Block Definition Diagram" 으로 수정
     * 추가: pkg/package, component, deployment, composite, communication alias
     */
    private String resolveDiagramType(String t) {
        switch (t.toLowerCase().replace(" ", "")) {
            // SysML 다이어그램 (별칭 → 정식 타입명)
            case "bdd":
            case "blockdefinitiondiagram":
            case "sysmlblockdefinitiondiagram":  return "SysML Block Definition Diagram";
            case "ibd":
            case "internalblockdiagram":
            case "sysmlinternalblockdiagram":    return "SysML Internal Block Diagram";
            case "req":
            case "requirement":
            case "requirementdiagram":           return "Requirement Diagram";
            case "par":
            case "parametric":
            case "parametricdiagram":            return "SysML Parametric Diagram";
            // UML 표준 다이어그램
            case "pkg":
            case "package":
            case "packagediagram":               return DiagramTypeConstants.UML_CLASS_DIAGRAM;
            case "class":
            case "classdiagram":                 return DiagramTypeConstants.UML_CLASS_DIAGRAM;
            case "usecase":
            case "uc":
            case "usecasediagram":               return DiagramTypeConstants.UML_USECASE_DIAGRAM;
            case "sequence":
            case "seq":
            case "sequencediagram":              return DiagramTypeConstants.UML_SEQUENCE_DIAGRAM;
            case "activity":
            case "act":
            case "activitydiagram":              return DiagramTypeConstants.UML_ACTIVITY_DIAGRAM;
            case "statemachine":
            case "state":
            case "statediagram":
            case "statechartdiagram":            return DiagramTypeConstants.UML_STATECHART_DIAGRAM;
            case "component":
            case "componentdiagram":             return DiagramTypeConstants.UML_COMPONENT_DIAGRAM;
            case "deployment":
            case "deploymentdiagram":            return DiagramTypeConstants.UML_DEPLOYMENT_DIAGRAM;
            case "communication":
            case "collaborationdiagram":         return "Collaboration";  // MagicDraw 2026x에서는 상수 없음, 형식 타입명 사용
            // 매트릭스/테이블 (추적성 확인용)
            case "matrix":
            case "dependencymatrix":
            case "dependencymatrixdiagram":      return DiagramTypeConstants.DEPENDENCY_MATRIX;
            case "satisfy":
            case "satisfymatrix":
            case "satisfyreq":
            case "satisfyrequirementmatrix":     return "Satisfy Requirement Matrix";
            case "verify":
            case "verifymatrix":
            case "verifyreq":
            case "verifyrequirementmatrix":      return "Verify Requirement Matrix";
            case "derive":
            case "derivereqt":
            case "derivematrix":
            case "derivereqtmatrix":
            case "deriverequirementmatrix":      return "DeriveReqt Requirement Matrix";
            case "refine":
            case "refinematrix":
            case "refinereq":
            case "refinerequirementmatrix":      return "Refine Requirement Matrix";
            case "generictable":
            case "table":                        return DiagramTypeConstants.GENERIC_TABLE;
            case "instancetable":                return DiagramTypeConstants.INSTANCE_TABLE;
            default:                             return t;  // 정식 타입명 그대로 사용
        }
    }

    private String elementToDiagramJson(Element el) {
        return String.format("{\"id\":\"%s\",\"name\":\"%s\",\"type\":\"%s\"}",
            escapeJson(el.getLocalID()),
            el instanceof NamedElement ? escapeJson(((NamedElement) el).getName()) : "",
            escapeJson(el.getHumanType()));
    }
}
