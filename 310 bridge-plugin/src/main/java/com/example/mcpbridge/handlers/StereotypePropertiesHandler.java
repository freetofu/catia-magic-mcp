package com.example.mcpbridge.handlers;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.SessionManager;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Stereotype property read/write handler
 *
 * GET  /project/{id}/elements/{eid}/props
 *   Returns all stereotypes applied to the element and their property values.
 *   Response: {
 *     "elementId": str,
 *     "elementName": str,
 *     "stereotypes": [
 *       { "name": str, "properties": [{"name": str, "value": str}, ...] }
 *     ]
 *   }
 *
 * PUT  /project/{id}/elements/{eid}/props
 *   Body: {"stereotype": str, "property": str, "value": str}
 *   Response: {"elementId": str, "stereotype": str, "property": str, "value": str}
 *
 * APIs used (via reflection - StereotypesHelper):
 *   getStereotypes(Element)                                     -> List<Stereotype>
 *   getPropertiesFromStereotype(Stereotype)                     -> List<Property>
 *   getStereotypePropertyValueAsString(Element, String, String) -> String
 *   getStereotypePropertyValue(Element, String, String)         -> List<Object>
 *   setStereotypePropertyValue(Project, Element, Stereotype, String, Object) -> void
 *   getStereotype(Project, String)                              -> Stereotype
 */
public class StereotypePropertiesHandler extends BaseHandler {

    private static final String STEREOTYPES_HELPER = "com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        sendError(exchange, 400, "Use ProjectDispatcherHandler: /project/{id}/elements/{eid}/props");
    }

    public void handleProps(HttpExchange exchange, Project project, String elementId) throws IOException {
        switch (exchange.getRequestMethod()) {
            case "GET": getProps(exchange, project, elementId); break;
            case "PUT": setProps(exchange, project, elementId); break;
            default:    sendError(exchange, 405, "Method Not Allowed");
        }
    }

    // -------------------------------------------------------------------------
    // GET: read all stereotype properties
    // -------------------------------------------------------------------------
    private void getProps(HttpExchange exchange, Project project, String elementId) throws IOException {
        try {
            Element el = resolveElement(project, elementId);
            if (el == null) {
                sendError(exchange, 404, "Element not found: " + elementId); return;
            }

            // 속성(태그) 목록 + 값 추출은 BaseHandler.readStereotypePropertiesJson 에 일원화한다.
            // (상속/파생 속성까지 포함: TagsHelper.getPropertiesWithDerived)
            // 과거 이 핸들러는 제거된 getPropertiesFromStereotype 를 직접 호출해 항상 빈 목록을 반환했다.
            String stereoArray = readStereotypePropertiesJson(project, el);

            String elementName = el instanceof NamedElement
                ? escapeJson(((NamedElement) el).getName()) : "";

            sendJson(exchange, 200, String.format(
                "{\"elementId\":\"%s\",\"elementName\":\"%s\",\"stereotypes\":%s}",
                escapeJson(elementId), elementName, stereoArray));

        } catch (Exception e) {
            sendError(exchange, 500, "Failed to get stereotype properties: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // PUT: set stereotype property value
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private void setProps(HttpExchange exchange, Project project, String elementId) throws IOException {
        Element el = resolveElement(project, elementId);
        if (el == null) {
            sendError(exchange, 404, "Element not found: " + elementId); return;
        }

        JSONObject json;
        try { json = (JSONObject) new JSONParser().parse(readRequestBody(exchange)); }
        catch (Exception e) { sendError(exchange, 400, "Invalid JSON: " + e.getMessage()); return; }

        String stereotypeName = (String) json.get("stereotype");
        String propertyName   = (String) json.get("property");
        String value          = (String) json.get("value");

        if (stereotypeName == null || propertyName == null || value == null) {
            sendError(exchange, 400, "stereotype, property, and value are all required."); return;
        }

        SessionManager sm = SessionManager.getInstance();
        sm.createSession(project, "MCP: set stereotype property - " + elementId);
        try {
            // 같은 이름 스테레오타입이 여러 프로파일에 있을 수 있다(예: Subsystem ×3).
            // 프로젝트 전체에서 이름으로 찾으면 엉뚱한 것을 잡고 조용히 아무것도 안 바뀐다
            // → 요소에 실제 적용된 스테레오타입 중에서 먼저 찾는다.
            Object stereotype = null;
            for (Stereotype applied : StereotypesHelper.getStereotypes(el)) {
                if (stereotypeName.equals(applied.getName())) { stereotype = applied; break; }
            }
            if (stereotype == null) {
                Class<?> shClass = Class.forName(STEREOTYPES_HELPER);
                Method getStereotype = shClass.getMethod("getStereotype", Project.class, String.class);
                stereotype = getStereotype.invoke(null, project, stereotypeName);
            }

            if (stereotype == null) {
                sm.cancelSession(project);
                sendError(exchange, 404, "Stereotype not found: " + stereotypeName);
                return;
            }

            Stereotype st = (Stereotype) stereotype;
            List<Element> refs = resolveElementRefs(project, value);
            if (refs == null) {
                StereotypesHelper.setStereotypePropertyValue(el, st, propertyName, value);
            } else {
                // 요소를 가리키는 태그(예: customizationTarget): 첫 값은 교체, 나머지는 추가
                for (int i = 0; i < refs.size(); i++)
                    StereotypesHelper.setStereotypePropertyValue(el, st, propertyName, refs.get(i), i > 0);
            }

            sm.closeSession(project);
            sendJson(exchange, 200, String.format(
                "{\"elementId\":\"%s\",\"stereotype\":\"%s\",\"property\":\"%s\",\"value\":\"%s\"}",
                escapeJson(elementId), escapeJson(stereotypeName),
                escapeJson(propertyName), escapeJson(value)));

        } catch (java.lang.reflect.InvocationTargetException e) {
            sm.cancelSession(project);
            Throwable cause = e.getCause();
            sendError(exchange, 500, "Failed to set property: " + (cause != null ? cause.getMessage() : e.getMessage()));
        } catch (ClassNotFoundException e) {
            sm.cancelSession(project);
            sendError(exchange, 500, "StereotypesHelper class not found: " + e.getMessage());
        } catch (Exception e) {
            sm.cancelSession(project);
            sendError(exchange, 500, "Failed to set stereotype property: " + e.getMessage());
        }
    }

    /**
     * 값이 요소 ID(쉼표 구분 가능)이고 전부 실제 요소로 찾아지면 그 요소 목록, 아니면 null(일반 글자 값).
     * ponytail: 글자 태그에 우연히 요소 ID와 같은 값을 넣으면 요소로 해석된다. 실제로 문제 되면 태그 타입을 확인하도록 바꿀 것.
     */
    private List<Element> resolveElementRefs(Project project, String value) {
        List<Element> out = new ArrayList<>();
        for (String token : value.split(",")) {
            Element ref = resolveElement(project, token.trim());
            if (ref == null) return null;
            out.add(ref);
        }
        return out;
    }

}
