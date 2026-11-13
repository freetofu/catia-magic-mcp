package com.example.mcpbridge.handlers;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.magicdraw.openapi.uml.ReadOnlyElementException;
import com.nomagic.magicdraw.openapi.uml.SessionManager;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Comment;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.StringJoiner;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * 요소(Element) CRUD 핸들러
 * - handleCollection: GET(목록/검색), POST(생성)
 * - handleSingle:     GET(단건), PUT(수정), DELETE(삭제)
 */
public class ElementsHandler extends BaseHandler {

    /** HttpHandler 인터페이스 구현 - ProjectDispatcherHandler를 통해 호출됨 */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        sendError(exchange, 400, "ProjectDispatcherHandler를 통해 호출하세요: /project/{id}/elements");
    }

    public void handleCollection(HttpExchange exchange, Project project) throws IOException {
        String method = exchange.getRequestMethod();
        String query  = getQueryParam(exchange, "query");
        if      ("GET".equals(method) && query != null) searchElements(exchange, project, query);
        else if ("GET".equals(method))                  listElements(exchange, project);
        else if ("POST".equals(method))                 createElement(exchange, project);
        else sendError(exchange, 405, "Method Not Allowed");
    }

    public void handleSingle(HttpExchange exchange, Project project, String elementId) throws IOException {
        switch (exchange.getRequestMethod()) {
            case "GET":    getElement(exchange, project, elementId);    break;
            case "PUT":    updateElement(exchange, project, elementId); break;
            case "DELETE": deleteElement(exchange, project, elementId); break;
            default:       sendError(exchange, 405, "Method Not Allowed");
        }
    }

    /**
     * POST /project/{id}/elements/{eid}/reorder  body: {"order": ["propId", ...]}
     * 전용 API가 없어서 getOwnedAttribute()(순서 있는 List)에서 빼고 다시 끼워 순서를 바꾼다.
     * 나열된 속성이 앞에 그 순서대로 오고, 나열 안 된 속성은 기존 상대 순서대로 뒤에 붙는다.
     */
    @SuppressWarnings("unchecked")
    public void reorderAttributes(HttpExchange exchange, Project project, String elementId) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { sendError(exchange, 405, "Method Not Allowed"); return; }
        Element owner = resolveElement(project, elementId);
        if (owner == null) { sendError(exchange, 404, "Element not found: " + elementId); return; }

        java.util.List<Object> ids;
        try { ids = (java.util.List<Object>) ((JSONObject) new JSONParser().parse(readRequestBody(exchange))).get("order"); }
        catch (Exception e) { sendError(exchange, 400, "Invalid JSON: " + e.getMessage()); return; }
        if (ids == null || ids.isEmpty()) { sendError(exchange, 400, "order (property ID list) is required."); return; }

        java.util.List<Element> attrs;
        try { attrs = (java.util.List<Element>) owner.getClass().getMethod("getOwnedAttribute").invoke(owner); }
        catch (Exception e) { sendError(exchange, 400, "Element has no ordered attributes (Block/Class/DataType/Interface only)."); return; }

        java.util.List<Element> wanted = new java.util.ArrayList<>();
        for (Object id : ids) {
            Element p = resolveElement(project, String.valueOf(id));
            if (p == null || !attrs.contains(p)) { sendError(exchange, 400, "Not an attribute of this element: " + id); return; }
            if (!wanted.contains(p)) wanted.add(p);
        }

        final String[] err = {null};
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                SessionManager sm = SessionManager.getInstance();
                sm.createSession(project, "MCP: reorder attributes");
                try {
                    for (int i = 0; i < wanted.size(); i++) {
                        attrs.remove(wanted.get(i));
                        attrs.add(i, wanted.get(i));
                    }
                    sm.closeSession(project);
                } catch (Exception e) { sm.cancelSession(project); err[0] = String.valueOf(e.getMessage()); }
            });
        } catch (Exception e) { err[0] = String.valueOf(e.getMessage()); }
        if (err[0] != null) { sendError(exchange, 500, "Reorder failed: " + err[0]); return; }

        StringJoiner out = new StringJoiner(",", "[", "]");
        for (Element p : attrs) out.add(String.format("{\"id\":\"%s\",\"name\":\"%s\"}",
            escapeJson(p.getID()), p instanceof NamedElement ? escapeJson(((NamedElement) p).getName()) : ""));
        sendJson(exchange, 200, "{\"elementId\":\"" + escapeJson(elementId) + "\",\"attributes\":" + out + "}");
    }

    private void listElements(HttpExchange exchange, Project project) throws IOException {
        try {
            Package root = project.getPrimaryModel();
            StringJoiner items = new StringJoiner(",", "[", "]");
            collectElements(root, items, 0, 2);
            sendJson(exchange, 200, "{\"elements\": " + items + "}");
        } catch (Exception e) { sendError(exchange, 500, "요소 목록 조회 실패: " + e.getMessage()); }
    }

    private void collectElements(Element parent, StringJoiner joiner, int depth, int maxDepth) {
        for (Element child : parent.getOwnedElement()) {
            joiner.add(elementToJson(child));
            // Package 뿐만 아니라 자식을 가질 수 있는 모든 요소(Block, Component 등)로 재귀
            // (기존: instanceof Package → SysML Block 등 Package가 아닌 Namespace 하위 요소 누락)
            if (depth < maxDepth && !child.getOwnedElement().isEmpty())
                collectElements(child, joiner, depth + 1, maxDepth);
        }
    }

    private void searchElements(HttpExchange exchange, Project project, String query) throws IOException {
        try {
            StringJoiner items = new StringJoiner(",", "[", "]");
            searchRecursive(project.getPrimaryModel(), query.toLowerCase(), items, 0, 5);
            sendJson(exchange, 200, "{\"elements\": " + items + ", \"query\": \"" + escapeJson(query) + "\"}");
        } catch (Exception e) { sendError(exchange, 500, "요소 검색 실패: " + e.getMessage()); }
    }

    private void searchRecursive(Element parent, String query, StringJoiner joiner, int depth, int maxDepth) {
        for (Element child : parent.getOwnedElement()) {
            if (child instanceof NamedElement) {
                String name = ((NamedElement) child).getName();
                String type = child.getHumanType();
                if ((name != null && name.toLowerCase().contains(query))
                        || (type != null && type.toLowerCase().contains(query)))
                    joiner.add(elementToJson(child));
            }
            if (depth < maxDepth) searchRecursive(child, query, joiner, depth + 1, maxDepth);
        }
    }

    private void getElement(HttpExchange exchange, Project project, String elementId) throws IOException {
        try {
            Element el = resolveElement(project, elementId);
            if (el == null) { sendError(exchange, 404, "요소를 찾을 수 없습니다: " + elementId); return; }
            sendJson(exchange, 200, elementToJsonDetailed(project, el));
        } catch (Exception e) { sendError(exchange, 500, "요소 조회 실패: " + e.getMessage()); }
    }

    @SuppressWarnings("unchecked")
    private void createElement(HttpExchange exchange, Project project) throws IOException {
        String body = readRequestBody(exchange);
        JSONObject json;
        try { json = (JSONObject) new JSONParser().parse(body); }
        catch (Exception e) { sendError(exchange, 400, "잘못된 JSON: " + e.getMessage()); return; }

        String typeName   = (String) json.get("type");
        String name       = (String) json.get("name");
        String ownerId    = (String) json.get("ownerId");
        if (typeName == null || name == null) { sendError(exchange, 400, "type과 name은 필수입니다."); return; }

        String stereotype  = (String) json.get("stereotype");
        String typeId      = (String) json.get("typeId");      // Property/Port 타입 요소 ID
        String aggregation = (String) json.get("aggregation"); // "none"|"shared"|"composite"

        // Port는 기본적으로 SysML ProxyPort로 생성 (명시적 stereotype 미지정 시)
        // UML Port가 필요하면 stereotype="none" 전달하여 skip 가능
        if ("Port".equalsIgnoreCase(typeName) && stereotype == null) {
            stereotype = "ProxyPort";
        } else if ("none".equalsIgnoreCase(stereotype)) {
            stereotype = null;
        }

        SessionManager sm = SessionManager.getInstance();
        sm.createSession(project, "MCP: 요소 생성 - " + name);
        try {
            Element owner = ownerId != null ? resolveElement(project, ownerId) : project.getPrimaryModel();
            if (owner == null) { sendError(exchange, 404, "owner 요소를 찾을 수 없습니다: " + ownerId); sm.cancelSession(project); return; }
            // 2026x: createChildElement() 제거됨 → ElementsFactory 리플렉션 + addElement() 사용
            Element newEl = createElementByType(project, typeName);
            if (newEl instanceof NamedElement) ((NamedElement) newEl).setName(name);
            ModelElementsManager.getInstance().addElement(newEl, owner);
            // 스테레오타입 적용 (선택적)
            if (stereotype != null && !stereotype.isEmpty()) applyStereotype(project, newEl, stereotype);
            // typeId / aggregation 적용 (선택적 — Part Property, Port 타입 설정 등)
            applyTypedProperties(project, newEl, typeId, aggregation);
            applyMetaclasses(project, newEl, json.get("metaclasses"));
            sm.closeSession(project);
            revealInContainmentTree(project, newEl);  // 생성 직후 모델 탐색기에서 바로 보이도록 트리 펼치기
            sendJson(exchange, 201, "{\"id\":\"" + escapeJson(newEl.getLocalID()) + "\",\"name\":\"" + escapeJson(name) + "\",\"type\":\"" + escapeJson(newEl.getHumanType()) + "\",\"stereotype\":\"" + escapeJson(stereotype != null ? stereotype : "") + "\"}");
        } catch (ReadOnlyElementException e) { sm.cancelSession(project); sendError(exchange, 403, "읽기 전용: " + e.getMessage());
        } catch (Exception e) { sm.cancelSession(project); sendError(exchange, 500, "요소 생성 실패: " + e.getMessage()); }
    }

    @SuppressWarnings("unchecked")
    private void updateElement(HttpExchange exchange, Project project, String elementId) throws IOException {
        Element el = resolveElement(project, elementId);
        if (el == null) { sendError(exchange, 404, "요소를 찾을 수 없습니다: " + elementId); return; }
        JSONObject json;
        try { json = (JSONObject) new JSONParser().parse(readRequestBody(exchange)); }
        catch (Exception e) { sendError(exchange, 400, "잘못된 JSON: " + e.getMessage()); return; }

        SessionManager sm = SessionManager.getInstance();
        sm.createSession(project, "MCP: 요소 수정 - " + elementId);
        try {
            String newName = (String) json.get("name");
            if (newName != null && el instanceof NamedElement) ((NamedElement) el).setName(newName);
            String doc = (String) json.get("documentation");
            if (doc != null) setDocumentation(project, el, doc);
            // 스테레오타입 적용 (선택적)
            String stereotype = (String) json.get("stereotype");
            if (stereotype != null && !stereotype.isEmpty()) applyStereotype(project, el, stereotype);
            // typeId / aggregation 적용 (선택적)
            String typeId      = (String) json.get("typeId");
            String aggregation = (String) json.get("aggregation");
            applyTypedProperties(project, el, typeId, aggregation);
            applyMetaclasses(project, el, json.get("metaclasses"));
            sm.closeSession(project);
            sendJson(exchange, 200, elementToJsonDetailed(project, el));
        } catch (Exception e) { sm.cancelSession(project); sendError(exchange, 500, "요소 수정 실패: " + e.getMessage()); }
    }

    private void deleteElement(HttpExchange exchange, Project project, String elementId) throws IOException {
        Element el = resolveElement(project, elementId);
        if (el == null) { sendError(exchange, 404, "요소를 찾을 수 없습니다: " + elementId); return; }
        SessionManager sm = SessionManager.getInstance();
        sm.createSession(project, "MCP: 요소 삭제 - " + elementId);
        try {
            ModelElementsManager.getInstance().removeElement(el);
            sm.closeSession(project);
            sendJson(exchange, 200, "{\"deleted\":\"" + escapeJson(elementId) + "\"}");
        } catch (ReadOnlyElementException e) { sm.cancelSession(project); sendError(exchange, 403, "읽기 전용: " + e.getMessage());
        } catch (Exception e) { sm.cancelSession(project); sendError(exchange, 500, "요소 삭제 실패: " + e.getMessage()); }
    }

    /**
     * TypedElement.setType() 및 Property.setAggregation() 적용.
     * 모두 선택적 — null이면 해당 설정 건너뜀.
     *
     * typeId      : 요소의 type으로 설정할 모델 요소 ID (Property, Port 등에 사용)
     * aggregation : "none" | "shared" | "composite" (Property.setAggregation)
     *              "composite" → Part Property (SysML IBD 부품 표현에 필수)
     */
    private void applyTypedProperties(Project project, Element el, String typeId, String aggregation) {
        // setType (TypedElement)
        if (typeId != null && !typeId.isEmpty()) {
            try {
                Class<?> typedElClass = Class.forName(
                    "com.nomagic.uml2.ext.magicdraw.classes.mdkernel.TypedElement");
                Class<?> typeClass = Class.forName(
                    "com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Type");
                if (typedElClass.isInstance(el)) {
                    Element typeEl = resolveElement(project, typeId);
                    if (typeEl != null && typeClass.isInstance(typeEl)) {
                        typedElClass.getMethod("setType", typeClass).invoke(el, typeEl);
                    } else {
                        System.err.println("[setType] typeId 요소를 찾을 수 없거나 Type이 아님: " + typeId);
                    }
                }
            } catch (Exception e) {
                System.err.println("[setType] 실패: " + e.getMessage());
            }
        }
        // setAggregation (Property)
        if (aggregation != null && !aggregation.isEmpty()) {
            try {
                Class<?> propClass = Class.forName(
                    "com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property");
                Class<?> aggKindClass = Class.forName(
                    "com.nomagic.uml2.ext.magicdraw.classes.mdkernel.AggregationKind");
                Class<?> aggEnumClass = Class.forName(
                    "com.nomagic.uml2.ext.magicdraw.classes.mdkernel.AggregationKindEnum");
                if (propClass.isInstance(el)) {
                    java.lang.reflect.Field field = aggEnumClass.getField(aggregation.toUpperCase());
                    Object aggValue = field.get(null);
                    propClass.getMethod("setAggregation", aggKindClass).invoke(el, aggValue);
                }
            } catch (Exception e) {
                System.err.println("[setAggregation] 실패: " + e.getMessage());
            }
        }
    }

    /**
     * 2026x: createChildElement(Element, String) 제거됨.
     * 대신 ElementsFactory.createXxxInstance() 리플렉션 호출 + addElement() 패턴 사용.
     * 타입명 예) "Class" → factory.createClassInstance()
     */
    /** Stereotype이 붙을 수 있는 메타클래스 지정 (예: ["Class","Port"]). 기존 목록을 교체한다. */
    @SuppressWarnings("unchecked")
    private void applyMetaclasses(Project project, Element el, Object raw) throws Exception {
        if (raw == null) return;
        if (!(el instanceof Stereotype)) throw new Exception("metaclasses는 Stereotype에만 지정할 수 있습니다.");
        java.util.List<String> names = (java.util.List<String>) raw;
        for (String n : names) {
            if (StereotypesHelper.getMetaClassByName(project, n) == null)
                throw new Exception("메타클래스를 찾을 수 없습니다: " + n);
        }
        StereotypesHelper.setBaseClassesByName((Stereotype) el, names);
    }

    private Element createElementByType(Project project, String typeName) throws Exception {
        Object factory = project.getElementsFactory();
        String methodName = "create" + typeName + "Instance";
        try {
            Method m = factory.getClass().getMethod(methodName);
            Object result = m.invoke(factory);
            if (result instanceof Element) return (Element) result;
            throw new Exception("생성된 객체가 Element 타입이 아닙니다: " + typeName);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new Exception("요소 생성 중 오류: " + (cause != null ? cause.getMessage() : e.getMessage()));
        } catch (NoSuchMethodException e) {
            throw new Exception("지원하지 않는 요소 타입 (팩토리 메서드 없음): " + typeName + " (필요 메서드: " + methodName + ")");
        }
    }

    /** 요소에 documentation(Comment.body) 설정 */
    private void setDocumentation(Project project, Element el, String doc) {
        java.util.Collection<Comment> comments = el.getOwnedComment();
        if (!comments.isEmpty()) {
            comments.iterator().next().setBody(doc);
        } else {
            Comment comment = project.getElementsFactory().createCommentInstance();
            comment.setBody(doc);
            comment.getAnnotatedElement().add(el);
            comment.setOwner(el);
        }
    }

    private String elementToJson(Element el) {
        return String.format("{\"id\":\"%s\",\"name\":\"%s\",\"type\":\"%s\"}",
            escapeJson(el.getLocalID()),
            el instanceof NamedElement ? escapeJson(((NamedElement)el).getName()) : "",
            escapeJson(el.getHumanType()));
    }

    /**
     * 요소 상세 JSON 직렬화.
     *
     * 공통 속성:
     *   id, name, type, ownerId, childCount
     *   documentation  — ownedComment.body
     *   isAbstract     — Classifier 계열 (Block, Activity, Class, Interface …)
     *   visibility     — NamedElement (public / private / protected / package)
     *
     * TypedElement (Property, Port, Parameter …):
     *   typeId, typeName — 설정된 타입 요소의 id/name
     *
     * Property:
     *   aggregation    — none / shared / composite
     *
     * MultiplicityElement (Property, Port, Parameter …):
     *   lower, upper   — 다중도 하한/상한 ("*" = unlimited)
     *
     * 관계 요소 (Dependency, Generalization, Connector …):
     *   sourceId, targetId — 관계의 양 끝 요소 id
     *
     * 스테레오타입/태그값 (Requirement.Text, Requirement.Id 등 모든 타입):
     *   stereotypes    — [{"name":"...", "properties":[{"name":"...", "value":"..."}]}]
     */
    private String elementToJsonDetailed(Project project, Element el) {
        // documentation
        String doc = readDocumentation(el);

        // isAbstract
        boolean isAbstract = false;
        try {
            Object v = el.getClass().getMethod("isAbstract").invoke(el);
            if (v instanceof Boolean) isAbstract = (Boolean) v;
        } catch (Exception ignore) {}

        // visibility (NamedElement)
        String visibility = "";
        if (el instanceof NamedElement) {
            try {
                Object v = el.getClass().getMethod("getVisibility").invoke(el);
                if (v != null) visibility = v.toString().toLowerCase();
            } catch (Exception ignore) {}
        }

        // typeId + typeName (TypedElement: Property, Port, Parameter, Signal …)
        String typeId = "", typeName = "";
        try {
            Object type = el.getClass().getMethod("getType").invoke(el);
            if (type instanceof Element) {
                typeId = ((Element) type).getLocalID();
                if (typeId == null) typeId = "";
                typeName = (type instanceof NamedElement)
                    ? ((NamedElement) type).getName() : "";
                if (typeName == null) typeName = "";
            }
        } catch (Exception ignore) {}

        // aggregation (Property 계열: none / shared / composite)
        String aggregation = "";
        try {
            Object agg = el.getClass().getMethod("getAggregation").invoke(el);
            if (agg != null) aggregation = agg.toString().toLowerCase();
        } catch (Exception ignore) {}

        // multiplicity (MultiplicityElement: lower/upper)
        String lower = "", upper = "";
        try {
            Object lo = el.getClass().getMethod("getLower").invoke(el);
            if (lo != null) lower = lo.toString();
        } catch (Exception ignore) {}
        try {
            Object up = el.getClass().getMethod("getUpper").invoke(el);
            if (up != null) {
                String upStr = up.toString();
                upper = "-1".equals(upStr) ? "*" : upStr;
            }
        } catch (Exception ignore) {}

        // relation endpoints (Dependency/Generalization/Connector)
        String[] endpoints = getRelationEndpointIds(el);
        String sourceId = endpoints[0];
        String targetId = endpoints[1];

        // stereotype properties (tagged values)
        String stereos = readStereotypePropertiesJson(project, el);

        return String.format(
            "{\"id\":\"%s\",\"name\":\"%s\",\"type\":\"%s\",\"ownerId\":\"%s\"," +
            "\"childCount\":%d,\"documentation\":\"%s\",\"isAbstract\":%b," +
            "\"visibility\":\"%s\",\"typeId\":\"%s\",\"typeName\":\"%s\"," +
            "\"aggregation\":\"%s\",\"lower\":\"%s\",\"upper\":\"%s\"," +
            "\"sourceId\":\"%s\",\"targetId\":\"%s\",\"stereotypes\":%s}",
            escapeJson(el.getLocalID()),
            el instanceof NamedElement ? escapeJson(((NamedElement) el).getName()) : "",
            escapeJson(el.getHumanType()),
            el.getOwner() != null ? escapeJson(el.getOwner().getLocalID()) : "",
            el.getOwnedElement().size(),
            escapeJson(doc),
            isAbstract,
            escapeJson(visibility),
            escapeJson(typeId),
            escapeJson(typeName),
            escapeJson(aggregation),
            escapeJson(lower),
            escapeJson(upper),
            escapeJson(sourceId),
            escapeJson(targetId),
            stereos);
    }

    private String getQueryParam(HttpExchange exchange, String key) {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery == null) return null;
        for (String param : rawQuery.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && key.equals(kv[0])) {
                try { return java.net.URLDecoder.decode(kv[1], "UTF-8"); } catch (Exception e) { return kv[1]; }
            }
        }
        return null;
    }
}
