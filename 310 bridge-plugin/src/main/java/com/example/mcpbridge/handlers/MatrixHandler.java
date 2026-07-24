package com.example.mcpbridge.handlers;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.SessionManager;
import com.nomagic.magicdraw.uml.BaseElement;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
// Note: Collection import retained for applyScope method signature

/**
 * Dependency Matrix 설정 핸들러
 *
 * POST /project/{id}/diagrams/{did}/matrix/configure
 *   Body: {
 *     "rowScopeIds":      [str],   // 행 범위 루트 요소 ID
 *     "columnScopeIds":   [str],   // 열 범위 루트 요소 ID
 *     "dependencyCriteria":[str],  // 셀에 표시할 관계 (예: ["Satisfy"])
 *     "direction":        str,     // optional: row->column / column->row / both
 *     "includeSubtypes":  bool,    // optional, default true
 *   }
 *
 * 사용 API (reflection):
 *   new com.nomagic.magicdraw.dependencymatrix.persistence.PersistenceManager(Diagram)
 *   PersistenceManager.getRowSettings()    -> FilterSettings
 *   PersistenceManager.getColumnSettings() -> FilterSettings
 *   PersistenceManager.getMatrixSettings() -> MatrixSettings
 *   FilterSettings.setScope(Collection<Element>)
 *   FilterSettings.setScopeDefined(boolean)
 *   FilterSettings.setTypesIncludeSubtypes(boolean)
 *   MatrixSettings.setDirection(MatrixSettings.Direction)
 *   NOTE: MatrixSettings.setDependencyCriteria(List<String>) is intentionally NOT used.
 *         Plain stereotype names ("Satisfy") are not valid ExpressionSpecification format.
 *         Criteria is managed by the diagram type configurator at runtime.
 */
public class MatrixHandler extends BaseHandler {

    private static final String PERSISTENCE_MANAGER_CLASS =
        "com.nomagic.magicdraw.dependencymatrix.persistence.PersistenceManager";
    private static final String MATRIX_SETTINGS_CLASS =
        "com.nomagic.magicdraw.dependencymatrix.persistence.MatrixSettings";
    private static final String DIAGRAM_CLASS =
        "com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Diagram";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        sendError(exchange, 400, "Use ProjectDispatcherHandler: /project/{id}/diagrams/{did}/matrix/configure");
    }

    public void handleConfigure(HttpExchange exchange, Project project, String diagramId) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed"); return;
        }
        configureMatrix(exchange, project, diagramId);
    }

    @SuppressWarnings("unchecked")
    private void configureMatrix(HttpExchange exchange, Project project, String diagramId) throws IOException {
        JSONObject json;
        try { json = (JSONObject) new JSONParser().parse(readRequestBody(exchange)); }
        catch (Exception e) { sendError(exchange, 400, "Invalid JSON: " + e.getMessage()); return; }

        JSONArray rowScopeJson      = (JSONArray) json.get("rowScopeIds");
        JSONArray colScopeJson      = (JSONArray) json.get("columnScopeIds");
        JSONArray criteriaJson      = (JSONArray) json.get("dependencyCriteria");
        JSONArray rowTypesJson      = (JSONArray) json.get("rowElementTypes");
        JSONArray colTypesJson      = (JSONArray) json.get("columnElementTypes");
        String direction            = (String)    json.get("direction");
        Boolean includeSubtypes     = (Boolean)   json.get("includeSubtypes");

        if (rowScopeJson == null || colScopeJson == null) {
            sendError(exchange, 400, "rowScopeIds and columnScopeIds are required"); return;
        }

        // 다이어그램 요소(Diagram) 탐색
        BaseElement base = project.getElementByID(diagramId);
        if (!(base instanceof Element)) {
            sendError(exchange, 404, "DIAGRAM_NOT_FOUND: " + diagramId); return;
        }
        Element diagramEl = (Element) base;

        // 행/열 scope 요소 수집
        List<Element> rowScope = new ArrayList<>();
        for (Object o : rowScopeJson) {
            Element e = resolveElement(project, o.toString());
            if (e != null) rowScope.add(e);
        }
        List<Element> colScope = new ArrayList<>();
        for (Object o : colScopeJson) {
            Element e = resolveElement(project, o.toString());
            if (e != null) colScope.add(e);
        }
        if (rowScope.isEmpty() || colScope.isEmpty()) {
            sendError(exchange, 404, "rowScope or columnScope contains no resolvable elements"); return;
        }

        List<String> criteriaList = new ArrayList<>();
        if (criteriaJson != null) {
            for (Object o : criteriaJson) criteriaList.add(o.toString());
        }

        List<String> rowTypeNames = new ArrayList<>();
        if (rowTypesJson != null) {
            for (Object o : rowTypesJson) rowTypeNames.add(o.toString());
        }
        List<String> colTypeNames = new ArrayList<>();
        if (colTypesJson != null) {
            for (Object o : colTypesJson) colTypeNames.add(o.toString());
        }

        SessionManager sm = SessionManager.getInstance();
        sm.createSession(project, "MCP: configure matrix - " + diagramId);
        try {
            // PersistenceManager 인스턴스 생성 (Diagram 받는 public 생성자)
            Class<?> pmClass = Class.forName(PERSISTENCE_MANAGER_CLASS);
            Class<?> diagramClass = Class.forName(DIAGRAM_CLASS);
            Object pm = pmClass.getConstructor(diagramClass).newInstance(diagramEl);

            // Row settings
            Object rowSettings = pmClass.getMethod("getRowSettings").invoke(pm);
            applyScope(project, rowSettings, rowScope, includeSubtypes, rowTypeNames);

            // Column settings
            Object colSettings = pmClass.getMethod("getColumnSettings").invoke(pm);
            applyScope(project, colSettings, colScope, includeSubtypes, colTypeNames);

            // Matrix-level settings
            Object matrixSettings = pmClass.getMethod("getMatrixSettings").invoke(pm);

            // Dependency criteria: NEVER write via MatrixSettings.setDependencyCriteria(List<String>)
            // or DependencyMatrixProfile.setDependencyCriteria(Element, Collection).
            // Both APIs store the value as a tagged value string.
            // "Satisfy" (plain name) is NOT a valid ExpressionSpecification format,
            // causing ExpressionException: Can not load expression: Satisfy at runtime.
            //
            // Correct behavior: keep the tagged value EMPTY.
            // The diagram type configurator (e.g. Satisfy Requirement Matrix configurator)
            // provides proper Expression objects at runtime when no tagged value is present.
            //
            // Action: clear any previously-written corrupt tagged value, ignore criteria param.
            String dbgCriteriaAction = clearCorruptCriteriaTaggedValue(diagramEl);
            if (!criteriaList.isEmpty()) {
                System.err.println("[Matrix] WARN: dependency_criteria param cannot be set via API. " +
                    "Requested " + criteriaList + " ignored. Diagram type configurator manages criteria automatically.");
            }
            System.err.println("[Matrix] criteriaAction=" + dbgCriteriaAction + ", criteriaParam=" + criteriaList);

            if (direction != null && !direction.isEmpty()) {
                applyDirection(matrixSettings, direction);
            }

            sm.closeSession(project);

            sendJson(exchange, 200, String.format(
                "{\"diagramId\":\"%s\",\"rowScopeCount\":%d,\"columnScopeCount\":%d," +
                "\"criteriaNote\":\"managed-by-diagram-type-configurator\"," +
                "\"dbgCriteriaAction\":\"%s\",\"status\":\"ok\"}",
                escapeJson(diagramId), rowScope.size(), colScope.size(),
                escapeJson(dbgCriteriaAction)));

        } catch (java.lang.reflect.InvocationTargetException e) {
            sm.cancelSession(project);
            Throwable cause = e.getCause();
            String msg = cause != null ? cause.getClass().getName() + ": " + cause.getMessage() : e.getMessage();
            System.err.println("[Matrix] InvocationTargetException: " + msg);
            if (cause != null) cause.printStackTrace(System.err);
            sendError(exchange, 500, "Failed to configure matrix: " + msg);
        } catch (Exception e) {
            sm.cancelSession(project);
            System.err.println("[Matrix] error: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            sendError(exchange, 500, "Failed to configure matrix: " + e.getMessage());
        }
    }

    private void applyScope(Project project, Object filterSettings, List<Element> scope, Boolean includeSubtypes,
                            List<String> elementTypeNames) throws Exception {
        Method setScope = filterSettings.getClass().getMethod("setScope", Collection.class);
        setScope.invoke(filterSettings, scope);

        try {
            Method setScopeDefined = filterSettings.getClass().getMethod("setScopeDefined", boolean.class);
            setScopeDefined.invoke(filterSettings, true);
        } catch (NoSuchMethodException ignore) {}

        if (includeSubtypes != null) {
            try {
                Method setSubtypes = filterSettings.getClass().getMethod("setTypesIncludeSubtypes", boolean.class);
                setSubtypes.invoke(filterSettings, includeSubtypes.booleanValue());
            } catch (NoSuchMethodException ignore) {}
        }

        if (elementTypeNames != null && !elementTypeNames.isEmpty()) {
            List<Element> stereotypeElements = resolveStereotypeElements(project, elementTypeNames);
            if (!stereotypeElements.isEmpty()) {
                try {
                    Method setElementTypes = filterSettings.getClass().getMethod("setElementTypes", List.class);
                    setElementTypes.invoke(filterSettings, stereotypeElements);
                    System.err.println("[Matrix] setElementTypes success: " + elementTypeNames);
                } catch (NoSuchMethodException e) {
                    System.err.println("[Matrix] setElementTypes not found: " + e.getMessage());
                }
            }
        }
    }

    /**
     * DependencyMatrix 프로파일의 dependencyCriteria tagged value에서
     * corrupt(plain string) 값만 제거한다. 유효한 XML 표현식은 보존한다.
     *
     * 배경:
     * - 유효한 값: XML 직렬화 형식으로 시작 ("<callExpressionSpecification..." 등)
     *   → 다이어그램 타입 configurator 또는 UI가 작성한 올바른 ExpressionSpecification
     *   → 보존해야 함
     * - Corrupt 값: plain string ("Satisfy", "Verify" 등)
     *   → 과거 버그(MatrixSettings.setDependencyCriteria(List<String>))가 쓴 값
     *   → ExpressionException 유발 → 제거해야 함
     *
     * @return debug string describing what was done
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private String clearCorruptCriteriaTaggedValue(Element diagramEl) {
        try {
            Class<?> dmProfileClass = Class.forName(
                "com.nomagic.magicdraw.dependencymatrix.persistence.DependencyMatrixProfile");
            Method getInstance = dmProfileClass.getMethod("getInstance",
                Class.forName("com.nomagic.magicdraw.uml.BaseElement"));
            Object profile = getInstance.invoke(null, diagramEl);
            Method dependencyMatrix = dmProfileClass.getMethod("dependencyMatrix");
            Object dmSt = dependencyMatrix.invoke(profile);

            Method getCriteria = dmSt.getClass().getMethod("getDependencyCriteria", Element.class);
            Object existing = getCriteria.invoke(dmSt, diagramEl);

            if (!(existing instanceof java.util.Collection) || ((java.util.Collection) existing).isEmpty()) {
                System.err.println("[Matrix] criteria tagged value is empty - configurator provides defaults");
                return "empty-no-action";
            }

            // Check each value: valid XML starts with '<', corrupt plain string does not
            boolean hasCorrupt = false;
            boolean hasValid = false;
            for (Object val : (java.util.Collection) existing) {
                if (val instanceof String) {
                    String s = ((String) val).trim();
                    if (s.startsWith("<")) {
                        hasValid = true;
                    } else {
                        hasCorrupt = true;
                        System.err.println("[Matrix] CORRUPT criteria value (plain string, not XML): " + s);
                    }
                }
            }

            if (hasCorrupt && !hasValid) {
                // All values are corrupt plain strings -> clear to restore configurator defaults
                Method setCriteria = dmSt.getClass().getMethod(
                    "setDependencyCriteria", Element.class, Collection.class);
                setCriteria.invoke(dmSt, diagramEl, new ArrayList<>());
                System.err.println("[Matrix] clearCorruptCriteriaTaggedValue: cleared all corrupt plain strings");
                return "cleared-corrupt-plain-strings";
            } else if (hasValid) {
                // Valid XML expressions present -> preserve them (do NOT clear)
                System.err.println("[Matrix] clearCorruptCriteriaTaggedValue: valid XML expressions found, preserving");
                return "preserved-valid-xml(" + ((java.util.Collection) existing).size() + " entries)";
            } else {
                System.err.println("[Matrix] clearCorruptCriteriaTaggedValue: no action needed");
                return "no-action";
            }
        } catch (Exception e) {
            System.err.println("[Matrix] clearCorruptCriteriaTaggedValue failed (non-fatal): "
                + e.getClass().getName() + ": " + e.getMessage());
            return "check-failed:" + e.getMessage();
        }
    }

    private List<Element> resolveStereotypeElements(Project project, List<String> typeNames) {
        List<Element> result = new ArrayList<>();
        for (String name : typeNames) {
            String key = name.toLowerCase().replaceAll("[\\s_\\-]", "");
            String stereotypeName;
            switch (key) {
                case "block":            stereotypeName = "Block"; break;
                case "partproperty":
                case "property":
                case "part":             stereotypeName = "PartProperty"; break;
                case "proxyport":
                case "port":             stereotypeName = "ProxyPort"; break;
                case "requirement":      stereotypeName = "Requirement"; break;
                case "interface":        stereotypeName = "InterfaceBlock"; break;
                case "valuetype":        stereotypeName = "ValueType"; break;
                default:
                    System.err.println("[Matrix] unknown element type: " + name);
                    continue;
            }
            Stereotype st = StereotypesHelper.getStereotype(project, stereotypeName);
            if (st != null) {
                result.add(st);
                System.err.println("[Matrix] resolved stereotype: " + name + " -> " + st.getQualifiedName());
            } else {
                System.err.println("[Matrix] stereotype not found: " + stereotypeName);
            }
        }
        return result;
    }

    private void applyDirection(Object matrixSettings, String direction) {
        try {
            Class<?> dirEnum = Class.forName(MATRIX_SETTINGS_CLASS + "$Direction");
            Object[] enumConstants = dirEnum.getEnumConstants();
            String norm = direction.toUpperCase().replace("-", "_").replace(" ", "_");
            Object match = null;
            for (Object c : enumConstants) {
                if (c.toString().equalsIgnoreCase(norm)
                    || c.toString().toUpperCase().contains(norm)) {
                    match = c; break;
                }
            }
            if (match != null) {
                Method setDir = matrixSettings.getClass().getMethod("setDirection", dirEnum);
                setDir.invoke(matrixSettings, match);
                System.err.println("[Matrix] direction=" + match);
            } else {
                System.err.println("[Matrix] direction '" + direction + "' not matched. options="
                    + java.util.Arrays.toString(enumConstants));
            }
        } catch (Exception e) {
            System.err.println("[Matrix] applyDirection failed: " + e.getMessage());
        }
    }

}
