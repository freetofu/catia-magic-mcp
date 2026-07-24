package com.example.mcpbridge.handlers;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.SessionManager;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.lang.reflect.Method;
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
            Class<?> shClass = Class.forName(STEREOTYPES_HELPER);

            // getStereotype(Project, String) -> Stereotype
            Method getStereotype = shClass.getMethod("getStereotype", Project.class, String.class);
            Object stereotype = getStereotype.invoke(null, project, stereotypeName);

            if (stereotype == null) {
                sm.cancelSession(project);
                sendError(exchange, 404, "Stereotype not found: " + stereotypeName);
                return;
            }

            Class<?> stClass = Class.forName("com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype");

            // Try setStereotypePropertyValue with Project, Element, Stereotype, String, Object
            try {
                Method setVal = shClass.getMethod(
                    "setStereotypePropertyValue",
                    Project.class, Element.class, stClass, String.class, Object.class);
                setVal.invoke(null, project, el, stereotype, propertyName, value);
            } catch (NoSuchMethodException e1) {
                // Fallback: try setStereotypePropertyValue with Element, Stereotype, String, Object
                try {
                    Method setVal = shClass.getMethod(
                        "setStereotypePropertyValue",
                        Element.class, stClass, String.class, Object.class);
                    setVal.invoke(null, el, stereotype, propertyName, value);
                } catch (NoSuchMethodException e2) {
                    // Final fallback: try alternate method name or signature
                    throw new Exception("setStereotypePropertyValue method not found. Tried signatures: " +
                        "(Project, Element, Stereotype, String, Object) and (Element, Stereotype, String, Object). " +
                        "Verify MagicDraw StereotypesHelper API for correct method.");
                }
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

}
