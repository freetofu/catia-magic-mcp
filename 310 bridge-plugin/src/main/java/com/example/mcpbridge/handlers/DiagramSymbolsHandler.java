package com.example.mcpbridge.handlers;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.SessionManager;
import com.nomagic.magicdraw.uml.BaseElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.sun.net.httpserver.HttpExchange;
import java.awt.Point;
import java.awt.Rectangle;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Diagram symbol placement and auto-layout handler
 *
 * GET  /project/{id}/diagrams/{did}/symbols
 *   Returns all symbols (PresentationElements) with their coordinates and sizes.
 *   Response: {
 *     "diagramId": str,
 *     "symbols": [
 *       {"id": str, "elementId": str, "elementName": str,
 *        "x": int, "y": int, "width": int, "height": int}
 *     ]
 *   }
 *
 * POST /project/{id}/diagrams/{did}/symbols
 *   Body: {"elementId": str, "x": int, "y": int}  (x,y optional)
 *   Adds a model element as a symbol in the diagram.
 *   Response: {"id": str, "elementId": str, "x": int, "y": int, "width": int, "height": int}
 *
 * PUT  /project/{id}/diagrams/{did}/symbols/{sid}
 *   Body: {"x": int, "y": int, "width": int, "height": int}
 *   Updates position and/or size of an existing symbol.
 *   Response: {"symbolId": str, "x": int, "y": int, "width": int, "height": int}
 *
 * POST /project/{id}/diagrams/{did}/layout
 *   Body: {"algorithm": str}  (optional, default: "hierarchic")
 *   Applies auto-layout to the diagram.
 *   Supported algorithms: hierarchic, organic, tree, circular, orthogonal, grid, class
 *   Response: {"diagramId": str, "algorithm": str, "status": "ok"}
 *
 * APIs used (via reflection):
 *   AbstractDiagramPresentationElement.getPresentationElements() -> Collection<PresentationElement>
 *   PresentationElement.getBounds()                              -> java.awt.Rectangle
 *   PresentationElement.getElement()                             -> Element
 *   PresentationElementsManager.createShapeElement(Element, PresentationElement, boolean, Point)
 *   PresentationElementsManager.createShapeElement(Element, PresentationElement)
 *   PresentationElement.setBounds(Rectangle)
 *   Layouting.layout(AbstractDiagramPresentationElement)
 *   Layouting.layout(AbstractDiagramPresentationElement, String)
 */
public class DiagramSymbolsHandler extends BaseHandler {

    private static final String LAYOUTING_CLASS =
        "com.nomagic.magicdraw.uml.symbols.layout.Layouting";
    private static final String PEM_CLASS =
        "com.nomagic.magicdraw.openapi.uml.PresentationElementsManager";

    /** Map user-facing algorithm name to Layouting constant field name */
    private static String resolveAlgorithm(String algo) {
        if (algo == null) return "HIERARCHIC_DIAGRAM_LAYOUTER";
        switch (algo.toLowerCase()) {
            case "hierarchic":  return "HIERARCHIC_DIAGRAM_LAYOUTER";
            case "organic":     return "ORGANIC_DIAGRAM_LAYOUTER";
            case "tree":        return "TREE_DIAGRAM_LAYOUTER";
            case "circular":    return "CIRCULAR_DIAGRAM_LAYOUTER";
            case "orthogonal":  return "ORTHOGONAL_DIAGRAM_LAYOUTER";
            case "grid":        return "GRID_DIAGRAM_LAYOUTER";
            case "class":       return "CLASS_DIAGRAM_LAYOUTER";
            default:            return "HIERARCHIC_DIAGRAM_LAYOUTER";
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        sendError(exchange, 400, "Use ProjectDispatcherHandler: /project/{id}/diagrams/{did}/symbols");
    }

    public void handleSymbols(HttpExchange exchange, Project project, String diagramId) throws IOException {
        String method = exchange.getRequestMethod();
        if      ("GET".equals(method))  getSymbols(exchange, project, diagramId);
        else if ("POST".equals(method)) addSymbol(exchange, project, diagramId);
        else sendError(exchange, 405, "Method Not Allowed");
    }

    public void handleSymbolSingle(HttpExchange exchange, Project project,
                                   String diagramId, String symbolId) throws IOException {
        if (!"PUT".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed"); return;
        }
        updateSymbol(exchange, project, diagramId, symbolId);
    }

    public void handleLayout(HttpExchange exchange, Project project, String diagramId) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed"); return;
        }
        applyLayout(exchange, project, diagramId);
    }

    // -------------------------------------------------------------------------
    public void handleExport(HttpExchange exchange, Project project, String diagramId) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed"); return;
        }
        exportDiagram(exchange, project, diagramId);
    }

    public void handleRelations(HttpExchange exchange, Project project, String diagramId) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed"); return;
        }
        addRelationToDiagram(exchange, project, diagramId);
    }

    public void handleSymbolsMove(HttpExchange exchange, Project project, String diagramId) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed"); return;
        }
        moveSymbols(exchange, project, diagramId);
    }

    public void handleDisplayAllPaths(HttpExchange exchange, Project project, String diagramId) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed"); return;
        }
        displayAllPaths(exchange, project, diagramId);
    }

    public void handleConnectors(HttpExchange exchange, Project project, String diagramId) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed"); return;
        }
        getConnectors(exchange, project, diagramId);
    }

    public void handleReroute(HttpExchange exchange, Project project, String diagramId) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed"); return;
        }
        rerouteConnectors(exchange, project, diagramId);
    }

    public void handleWaypoints(HttpExchange exchange, Project project, String diagramId) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed"); return;
        }
        setConnectorWaypoints(exchange, project, diagramId);
    }

    // -------------------------------------------------------------------------
    // GET /diagrams/{did}/symbols
    // -------------------------------------------------------------------------
    private void getSymbols(HttpExchange exchange, Project project, String diagramId) throws IOException {
        try {
            Object diagram = findDiagram(project, diagramId);
            if (diagram == null) {
                sendError(exchange, 404, "Diagram not found: " + diagramId); return;
            }

            // 재귀 수집: 최상위 블록뿐 아니라 중첩 파트·포트까지 포함한다.
            // (이전엔 최상위 PE만 훑어 포트/내부 파트가 누락됐다 — 레이아웃 엔진 입력에 필수)
            // path(커넥터)와 보조 라벨은 제외하고, 각 심볼에 parentSymbolId·kind를 부여한다.
            StringJoiner symbols = new StringJoiner(",", "[", "]");
            java.util.Set<Object> visited =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            collectSymbolJsonRec(diagram, null, symbols, visited);

            sendJson(exchange, 200, String.format(
                "{\"diagramId\":\"%s\",\"symbols\":%s}",
                escapeJson(diagramId), symbols));

        } catch (Exception e) {
            sendError(exchange, 500, "Failed to get symbols: " + e.getMessage());
        }
    }

    /**
     * 다이어그램 심볼을 재귀 수집해 JSON으로 누적한다.
     * - path(getClient() 보유) 와 보조 라벨(Label/Text/Name/Compartment)은 심볼에서 제외.
     * - shape/port 심볼만 parentSymbolId·kind 와 함께 출력.
     * - 부모 추적: 현재 PE가 출력 대상 shape이면 그 localID가 자식의 parentSymbolId가 된다.
     */
    private void collectSymbolJsonRec(Object parent, String parentSymbolId,
                                      StringJoiner out, java.util.Set<Object> visited) {
        try {
            Collection<?> pes = getPresentationElements(parent);
            for (Object pe : pes) {
                if (!visited.add(pe)) continue;

                boolean isPath = false;
                try { pe.getClass().getMethod("getClient"); isPath = true; }
                catch (NoSuchMethodException ignore) {}

                String clsName = pe.getClass().getSimpleName();
                boolean isAux = clsName.endsWith("Label") || clsName.endsWith("Text")
                    || clsName.endsWith("Name") || clsName.contains("Compartment");

                String sid = getLocalId(pe);
                String childParentId = parentSymbolId;

                if (!isPath && !isAux) {
                    String kind = classifyKind(pe);
                    String json = presentationToJsonEx(pe, parentSymbolId, kind);
                    if (json != null) out.add(json);
                    if (!sid.isEmpty()) childParentId = sid;  // 자식의 부모는 이 심볼
                }

                // path 내부로는 내려가지 않는다(자식이 라벨뿐). shape/port는 재귀.
                if (!isPath) collectSymbolJsonRec(pe, childParentId, out, visited);
            }
        } catch (Exception ignore) {}
    }

    /** 심볼이 표현하는 모델 요소가 Port면 "port", 아니면 "shape" */
    private String classifyKind(Object pe) {
        try {
            Object el = pe.getClass().getMethod("getElement").invoke(pe);
            Class<?> portClass = Class.forName(
                "com.nomagic.uml2.ext.magicdraw.compositestructures.mdports.Port");
            if (portClass.isInstance(el)) return "port";
        } catch (Exception ignore) {}
        if (pe.getClass().getSimpleName().toLowerCase().contains("port")) return "port";
        return "shape";
    }

    /** presentationToJson 확장판 — parentSymbolId·kind·elementType 필드 추가. */
    private String presentationToJsonEx(Object pe, String parentSymbolId, String kind) {
        try {
            String peId = getLocalId(pe);
            String elementId = "";
            String elementName = "";
            String elementType = "";
            try {
                Object el = pe.getClass().getMethod("getElement").invoke(pe);
                if (el instanceof Element) {
                    elementId = ((Element) el).getLocalID();
                    if (el instanceof NamedElement) {
                        String n = ((NamedElement) el).getName();
                        if (n != null) elementName = n;
                    }
                    // Part Property 등은 자기 이름이 비고 실제 블록명은 type(Block)에 있음 → 폴백
                    if (elementName.isEmpty()) {
                        try {
                            Object type = el.getClass().getMethod("getType").invoke(el);
                            if (type instanceof NamedElement) {
                                String tn = ((NamedElement) type).getName();
                                if (tn != null) elementName = tn;
                            }
                        } catch (Exception ignore) {}
                    }
                    try {
                        Object ht = el.getClass().getMethod("getHumanType").invoke(el);
                        if (ht != null) elementType = ht.toString();
                    } catch (Exception ignore) {}
                }
            } catch (Exception ignore) {}

            int x = 0, y = 0, w = 0, h = 0;
            Rectangle r = getSymbolBounds(pe);
            if (r != null) { x = r.x; y = r.y; w = r.width; h = r.height; }

            String parentField = (parentSymbolId == null)
                ? "null" : ("\"" + escapeJson(parentSymbolId) + "\"");
            return String.format(
                "{\"id\":\"%s\",\"elementId\":\"%s\",\"elementName\":\"%s\",\"elementType\":\"%s\"," +
                "\"kind\":\"%s\",\"parentSymbolId\":%s,\"x\":%d,\"y\":%d,\"width\":%d,\"height\":%d}",
                escapeJson(peId), escapeJson(elementId), escapeJson(elementName), escapeJson(elementType),
                escapeJson(kind), parentField, x, y, w, h);
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // GET /diagrams/{did}/connectors
    //   다이어그램에 그려진 모든 커넥터(PathElement)를 재귀 수집해 반환.
    //   각 커넥터: id / relationId / relationName / relationType /
    //             sourceSymbolId / targetSymbolId / waypoints[]
    //   sourceSymbolId·targetSymbolId 는 getSymbols 의 심볼 id(포트일 수 있음)와 매칭된다.
    // -------------------------------------------------------------------------
    private void getConnectors(HttpExchange exchange, Project project, String diagramId) throws IOException {
        try {
            Object diagram = findDiagram(project, diagramId);
            if (diagram == null) {
                sendError(exchange, 404, "Diagram not found: " + diagramId); return;
            }

            java.util.List<Object> paths = new java.util.ArrayList<>();
            java.util.Set<Object> visited =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            collectPathSymbols(diagram, paths, visited);

            StringJoiner arr = new StringJoiner(",", "[", "]");
            for (Object pe : paths) {
                String json = pathToJson(pe);
                if (json != null) arr.add(json);
            }
            sendJson(exchange, 200, String.format(
                "{\"diagramId\":\"%s\",\"connectors\":%s}",
                escapeJson(diagramId), arr));

        } catch (Exception e) {
            sendError(exchange, 500, "Failed to get connectors: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // POST /diagrams/{did}/connectors/reroute
    //   모든 커넥터(PathElement)의 기존 경유점(break points)을 제거해, MagicDraw가
    //   현재 심볼 위치 기준으로 최단 경로로 자동 재라우팅하게 한다.
    //   블록을 옮긴 뒤 옛 waypoint가 남아 크게 우회하는 선을 정리하는 용도.
    // -------------------------------------------------------------------------
    private void rerouteConnectors(HttpExchange exchange, Project project, String diagramId) throws IOException {
        try {
            Object diagram = findDiagram(project, diagramId);
            if (diagram == null) { sendError(exchange, 404, "Diagram not found: " + diagramId); return; }
            openDpe(diagram);

            java.util.List<Object> paths = new java.util.ArrayList<>();
            java.util.Set<Object> visited =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            collectPathSymbols(diagram, paths, visited);

            SessionManager sm = SessionManager.getInstance();
            sm.createSession(project, "MCP: reroute connectors - " + diagramId);
            int done = 0, failed = 0;
            try {
                Object pem = Class.forName(PEM_CLASS).getMethod("getInstance").invoke(null);
                for (Object pe : paths) {
                    if (rerouteOne(pem, pe)) done++; else failed++;
                }
                sm.closeSession(project);
            } catch (Exception e) {
                sm.cancelSession(project);
                sendError(exchange, 500, "Failed to reroute (session): " + e.getMessage()); return;
            }
            sendJson(exchange, 200, String.format(
                "{\"diagramId\":\"%s\",\"total\":%d,\"rerouted\":%d,\"failed\":%d}",
                escapeJson(diagramId), paths.size(), done, failed));
        } catch (Exception e) {
            sendError(exchange, 500, "Failed to reroute connectors: " + e.getMessage());
        }
    }

    /** 커넥터 1개 재라우팅: PEM의 reroute 계열(1-인자) 우선, 실패 시 경유점 제거로 폴백. */
    private boolean rerouteOne(Object pem, Object pe) {
        // 1) PresentationElementsManager 의 reroute 계열 메서드(1-인자) 시도
        String[] pemMethods = { "reroutePathElement", "reroute", "resetRouting", "autoRoute" };
        for (String name : pemMethods) {
            for (Method mm : pem.getClass().getMethods()) {
                if (mm.getName().equals(name) && mm.getParameterCount() == 1) {
                    try { mm.invoke(pem, pe); return true; } catch (Exception ignore) {}
                }
            }
        }
        // 2) 폴백: PathElement 의 경유점(break points)을 빈 리스트로 → 자동 라우팅
        String[] setters = { "setBreakPoints", "setIntermediatePointList", "setIntermediatePoints", "setPoints" };
        for (String name : setters) {
            try {
                pe.getClass().getMethod(name, java.util.List.class)
                  .invoke(pe, new java.util.ArrayList<>());
                return true;
            } catch (Exception ignore) {}
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // POST /diagrams/{did}/connectors/waypoints
    //   레이아웃 엔진(ELK)이 계산한 선 경로를 그대로 심는다.
    //   Magic의 자동 라우팅은 블록을 피해 가지 않는다. 그래서 지금까지는 블록 간격을
    //   넓혀서 선이 지나갈 자리를 비워 두는 식으로 관통을 막았고, 그만큼 프레임이
    //   옆으로 퍼졌다. 경로를 직접 지정하면 간격을 좁혀도 관통이 생기지 않는다.
    //
    //   Body: {"routes":[{"id":"<path 심볼 id>","points":[{"x":..,"y":..}, ...]}],
    //          "dropEndpoints": false}
    //
    //   dropEndpoints=true  → changePathBreakPoints(선, 경유점).
    //     첫 점과 끝 점을 버리고 가운데만 심는다. 끝점은 Magic이 포트 기준으로 잡는다.
    //     2026-07-28 실측 결과 이 방식은 손실이 크다. 경유점 52개를 심으면 29개만 남고,
    //     Magic이 포트에 붙는 양 끝 구간을 다시 계산하면서 그 근처 경유점을 버린다.
    //     그래서 엔진 기준 관통 0인 경로가 화면에서는 6건 관통으로 나왔다.
    //
    //   dropEndpoints=false → changePathPoints(선, 시작점, 끝점, 경유점).  기본값.
    //     끝점까지 명시해 Magic이 양 끝을 다시 계산할 이유를 없앤다.
    //
    //   되돌리기: /connectors/reroute 를 한 번 부르면 경유점이 전부 지워진다.
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private void setConnectorWaypoints(HttpExchange exchange, Project project, String diagramId) throws IOException {
        JSONObject json;
        try { json = (JSONObject) new JSONParser().parse(readRequestBody(exchange)); }
        catch (Exception e) { sendError(exchange, 400, "Invalid JSON: " + e.getMessage()); return; }

        JSONArray routesJson = (JSONArray) json.get("routes");
        if (routesJson == null || routesJson.isEmpty()) {
            sendError(exchange, 400, "routes must be a non-empty array"); return;
        }
        boolean dropEndpoints = Boolean.TRUE.equals(json.get("dropEndpoints"));

        Object diagram = findDiagram(project, diagramId);
        if (diagram == null) { sendError(exchange, 404, "DIAGRAM_NOT_FOUND: " + diagramId); return; }
        openDpe(diagram);

        // path 심볼 id → PathElement. getConnectors 가 돌려주는 id 와 같은 체계다.
        Map<String, Object> pathById = new HashMap<>();
        java.util.List<Object> paths = new ArrayList<>();
        collectPathSymbols(diagram, paths,
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        for (Object pe : paths) {
            String pid = getLocalId(pe);
            if (!pid.isEmpty()) pathById.put(pid, pe);
        }

        // JSON 파싱은 EDT 밖에서 끝낸다. EDT 안에서는 심볼 변경만 한다.
        java.util.List<String> ids = new ArrayList<>();
        java.util.List<java.util.List<Point>> pointLists = new ArrayList<>();
        StringJoiner skippedJson = new StringJoiner(",", "[", "]");
        for (Object ro : routesJson) {
            JSONObject r = (JSONObject) ro;
            String rid = (r.get("id") == null) ? null : r.get("id").toString();
            JSONArray ptsJson = (JSONArray) r.get("points");
            if (rid == null || ptsJson == null) {
                skippedJson.add(String.format("{\"id\":\"%s\",\"reason\":\"id and points are required\"}",
                    escapeJson(rid == null ? "" : rid)));
                continue;
            }
            if (!pathById.containsKey(rid)) {
                skippedJson.add(String.format("{\"id\":\"%s\",\"reason\":\"path symbol not found\"}",
                    escapeJson(rid)));
                continue;
            }
            java.util.List<Point> pts = new ArrayList<>();
            for (Object po : ptsJson) {
                JSONObject p = (JSONObject) po;
                Number px = (Number) p.get("x");
                Number py = (Number) p.get("y");
                if (px == null || py == null) continue;
                pts.add(new Point(px.intValue(), py.intValue()));
            }
            // 여기서는 자르지 않는다. 끝점을 쓸지 말지는 아래에서 방식에 따라 정한다.
            ids.add(rid);
            pointLists.add(pts);
        }

        if (ids.isEmpty()) {
            sendJson(exchange, 200, String.format(
                "{\"diagramId\":\"%s\",\"total\":%d,\"applied\":0,\"failed\":0,\"skipped\":%s}",
                escapeJson(diagramId), routesJson.size(), skippedJson));
            return;
        }

        // 심볼 변경은 EDT에서. moveSymbols 와 같은 이유 — 화면을 그리는 도중에 좌표가
        // 바뀌면 예외가 나거나 화면이 깨진다.
        final StringJoiner appliedJson = new StringJoiner(",", "[", "]");
        final int[] counts = {0, 0};   // [0]=applied, [1]=failed
        final Throwable[] err = {null};
        Runnable task = () -> {
            SessionManager sm = SessionManager.getInstance();
            sm.createSession(project, "MCP: set connector waypoints - " + diagramId);
            try {
                Class<?> pemClass = Class.forName(PEM_CLASS);
                Object pem = pemClass.getMethod("getInstance").invoke(null);
                Class<?> pathClass = Class.forName("com.nomagic.magicdraw.uml.symbols.paths.PathElement");
                Method changeBreakPoints = pemClass.getMethod(
                    "changePathBreakPoints", pathClass, java.util.List.class);
                Method changePathPoints = pemClass.getMethod(
                    "changePathPoints", pathClass, Point.class, Point.class, java.util.List.class);

                for (int i = 0; i < ids.size(); i++) {
                    String rid = ids.get(i);
                    java.util.List<Point> pts = pointLists.get(i);
                    Object pe = pathById.get(rid);
                    // 가운데(경유점)와 양 끝을 나눈다. 점이 2개 이하면 경유점은 없다.
                    java.util.List<Point> bends = (pts.size() > 2)
                        ? new ArrayList<>(pts.subList(1, pts.size() - 1))
                        : new ArrayList<Point>();
                    try {
                        if (dropEndpoints || pts.size() < 2) {
                            changeBreakPoints.invoke(pem, pe, bends);
                        } else {
                            changePathPoints.invoke(pem, pe,
                                pts.get(0), pts.get(pts.size() - 1), bends);
                        }
                        counts[0]++;
                        appliedJson.add(String.format("{\"id\":\"%s\",\"waypoints\":%d}",
                            escapeJson(rid), bends.size()));
                    } catch (Exception one) {
                        counts[1]++;
                        Throwable c = (one instanceof java.lang.reflect.InvocationTargetException
                            && one.getCause() != null) ? one.getCause() : one;
                        System.err.println("[Waypoints] " + rid + " failed: " + c.getMessage());
                    }
                }
                sm.closeSession(project);
            } catch (Throwable t) {
                try { sm.cancelSession(project); } catch (Exception ignore) {}
                err[0] = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                    ? t.getCause() : t;
            }
        };

        try {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) task.run();
            else javax.swing.SwingUtilities.invokeAndWait(task);
        } catch (Exception e) {
            sendError(exchange, 500, "setConnectorWaypoints failed (EDT): " + e.getMessage()); return;
        }
        if (err[0] != null) {
            sendError(exchange, 500, "setConnectorWaypoints failed: " + err[0].getMessage()); return;
        }
        sendJson(exchange, 200, String.format(
            "{\"diagramId\":\"%s\",\"total\":%d,\"applied\":%d,\"failed\":%d,\"routes\":%s,\"skipped\":%s}",
            escapeJson(diagramId), routesJson.size(), counts[0], counts[1], appliedJson, skippedJson));
    }

    /** PathElement(커넥터)만 재귀 수집. getClient() 보유 여부로 path 판별. */
    private void collectPathSymbols(Object parent, java.util.List<Object> out, java.util.Set<Object> visited) {
        try {
            for (Object pe : getPresentationElements(parent)) {
                if (!visited.add(pe)) continue;
                boolean isPath = false;
                try { pe.getClass().getMethod("getClient"); isPath = true; }
                catch (NoSuchMethodException ignore) {}
                if (isPath) out.add(pe);
                collectPathSymbols(pe, out, visited);
            }
        } catch (Exception ignore) {}
    }

    /** 커넥터 PathElement 1개를 JSON으로. 출발/도착 심볼 id + 경유점. */
    private String pathToJson(Object pe) {
        try {
            String pid = getLocalId(pe);
            String relId = "", relName = "", relType = "";
            try {
                Object el = pe.getClass().getMethod("getElement").invoke(pe);
                if (el instanceof Element) {
                    relId = ((Element) el).getLocalID();
                    if (el instanceof NamedElement) {
                        String n = ((NamedElement) el).getName();
                        if (n != null) relName = n;
                    }
                    try {
                        Object ht = el.getClass().getMethod("getHumanType").invoke(el);
                        if (ht != null) relType = ht.toString();
                    } catch (Exception ignore) {}
                }
            } catch (Exception ignore) {}

            // getClient()/getSupplier() → 출발/도착 PresentationElement(심볼). 그 localID가 심볼 id.
            String srcSym = "", tgtSym = "";
            try {
                Object client = pe.getClass().getMethod("getClient").invoke(pe);
                if (client != null) srcSym = getLocalId(client);
            } catch (Exception ignore) {}
            try {
                Object supplier = pe.getClass().getMethod("getSupplier").invoke(pe);
                if (supplier != null) tgtSym = getLocalId(supplier);
            } catch (Exception ignore) {}

            // 경유점(waypoints): API 버전별 메서드명이 달라 여러 후보를 시도한다.
            StringJoiner pts = new StringJoiner(",", "[", "]");
            try {
                java.util.List<?> points = null;
                String[] candidates = {
                    "getIntermediatePointList", "getIntermediatePoints",
                    "getBreakPoints", "getPoints"
                };
                for (String m : candidates) {
                    try {
                        Object res = pe.getClass().getMethod(m).invoke(pe);
                        if (res instanceof java.util.List) { points = (java.util.List<?>) res; break; }
                    } catch (NoSuchMethodException ignore) {}
                }
                if (points != null) {
                    for (Object p : points) {
                        if (p instanceof Point) {
                            Point pt = (Point) p;
                            pts.add(String.format("{\"x\":%d,\"y\":%d}", pt.x, pt.y));
                        }
                    }
                }
            } catch (Exception ignore) {}

            return String.format(
                "{\"id\":\"%s\",\"relationId\":\"%s\",\"relationName\":\"%s\",\"relationType\":\"%s\"," +
                "\"sourceSymbolId\":\"%s\",\"targetSymbolId\":\"%s\",\"waypoints\":%s}",
                escapeJson(pid), escapeJson(relId), escapeJson(relName), escapeJson(relType),
                escapeJson(srcSym), escapeJson(tgtSym), pts);
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // POST /diagrams/{did}/symbols
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private void addSymbol(HttpExchange exchange, Project project, String diagramId) throws IOException {
        JSONObject json;
        try { json = (JSONObject) new JSONParser().parse(readRequestBody(exchange)); }
        catch (Exception e) { sendError(exchange, 400, "Invalid JSON: " + e.getMessage()); return; }

        String elementId = (String) json.get("elementId");
        if (elementId == null) { sendError(exchange, 400, "elementId is required."); return; }

        Number xNum = (Number) json.get("x");
        Number yNum = (Number) json.get("y");
        // autoDisplayPorts: Part Property 추가 시 Block의 Port를 자동 표시할지 여부.
        // 기본값 false — 명시적으로 true를 전달할 때만 Port 자동 표시.
        boolean autoDisplayPorts = Boolean.TRUE.equals(json.get("autoDisplayPorts"));

        Element el = resolveElement(project, elementId);
        if (el == null) { sendError(exchange, 404, "Element not found: " + elementId); return; }

        Object diagram = findDiagram(project, diagramId);
        if (diagram == null) { sendError(exchange, 404, "Diagram not found: " + diagramId); return; }

        // 쓰기 작업 전 EDT에서 명시적으로 다이어그램 open (심볼 추가에 필요)
        final Object diagramFinal = diagram;
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> openDpe(diagramFinal));
        } catch (Exception edtEx) {
            System.err.println("[DiagramSymbols] EDT open() failed: " + edtEx.getMessage());
            // Continue anyway — diagram may already be open
        }

        SessionManager sm = SessionManager.getInstance();
        sm.createSession(project, "MCP: add symbol - " + elementId);
        try {
            Class<?> pemClass = Class.forName(PEM_CLASS);
            Object pem = pemClass.getMethod("getInstance").invoke(null);
            Object created;

            Class<?> peClass = findPresentationElementClass(diagram.getClass());
            System.err.println("[DiagramSymbols] diagram class: " + diagram.getClass().getName()
                + ", resolved peClass: " + peClass.getName());

            if (xNum != null && yNum != null) {
                // createShapeElement(Element, PresentationElement, boolean, Point)
                java.awt.Point point = new java.awt.Point(xNum.intValue(), yNum.intValue());
                Method createShape = pemClass.getMethod(
                    "createShapeElement", Element.class, peClass, boolean.class, java.awt.Point.class);
                System.err.println("[DiagramSymbols] calling createShapeElement(E, PE, bool, Point)");
                created = createShape.invoke(pem, el, diagram, true, point);
            } else {
                // createShapeElement(Element, PresentationElement)
                Method createShape = pemClass.getMethod("createShapeElement", Element.class, peClass);
                System.err.println("[DiagramSymbols] calling createShapeElement(E, PE)");
                created = createShape.invoke(pem, el, diagram);
            }

            // Part Property (Property with Class/Block type having Ports)
            // → autoDisplayPorts=true 일 때만 Port 심볼 자동 표시 (기본 false — opt-in)
            if (created != null && autoDisplayPorts) {
                try {
                    Class<?> propClass = Class.forName(
                        "com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property");
                    // 포트를 가진 분류자(Classifier) 결정:
                    //  - Part Property를 추가했으면 그 type(Block)  → IBD
                    //  - Block(Class) 자체를 추가했으면 el 자신     → BDD (블럭 포트 표시)
                    Object type = propClass.isInstance(el)
                        ? el.getClass().getMethod("getType").invoke(el)
                        : el;
                    if (type != null) {
                        java.util.List<Object> ports = new java.util.ArrayList<>();
                        Class<?> portClass = Class.forName(
                            "com.nomagic.uml2.ext.magicdraw.compositestructures.mdports.Port");
                        try {
                            Object ownedPorts = type.getClass().getMethod("getOwnedPort").invoke(type);
                            if (ownedPorts instanceof java.util.Collection) {
                                for (Object p : (java.util.Collection<?>) ownedPorts) ports.add(p);
                            }
                        } catch (NoSuchMethodException nsme) {
                            Object attrs = type.getClass().getMethod("getOwnedAttribute").invoke(type);
                            if (attrs instanceof java.util.Collection) {
                                for (Object a : (java.util.Collection<?>) attrs) {
                                    if (portClass.isInstance(a)) ports.add(a);
                                }
                            }
                        }
                        if (!ports.isEmpty()) {
                            Class<?> dpClass = Class.forName(
                                "com.nomagic.magicdraw.uml.symbols.DisplayPorts");
                            Class<?> peArgClass = Class.forName(
                                "com.nomagic.magicdraw.uml.symbols.PresentationElement");
                            Method displayPorts = dpClass.getMethod("displayPorts",
                                peArgClass, java.util.List.class,
                                boolean.class, boolean.class, boolean.class);
                            displayPorts.invoke(null, created, ports, false, false, false);
                            System.err.println("[DisplayPorts] displayed " + ports.size() + " ports on symbol");
                        }
                    }
                } catch (Exception dpEx) {
                    System.err.println("[DisplayPorts] skip: " + dpEx.getClass().getName() + ": " + dpEx.getMessage());
                }
            }

            sm.closeSession(project);
            System.err.println("[DiagramSymbols] symbol created: " + (created != null));

            if (created != null) {
                String symbolJson = presentationToJson(created);
                sendJson(exchange, 201, symbolJson != null ? symbolJson :
                    "{\"status\":\"created\",\"elementId\":\"" + escapeJson(elementId) + "\"}");
            } else {
                sendJson(exchange, 201,
                    "{\"status\":\"created\",\"elementId\":\"" + escapeJson(elementId) + "\"}");
            }

        } catch (java.lang.reflect.InvocationTargetException e) {
            sm.cancelSession(project);
            Throwable cause = e.getCause();
            String msg = cause != null ? cause.getClass().getName() + ": " + cause.getMessage() : e.getMessage();
            System.err.println("[DiagramSymbols] createShapeElement InvocationTargetException: " + msg);
            if (cause != null) cause.printStackTrace(System.err);
            sendError(exchange, 500, "Failed to add symbol: " + msg);
        } catch (Exception e) {
            sm.cancelSession(project);
            System.err.println("[DiagramSymbols] createShapeElement Exception: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            sendError(exchange, 500, "Failed to add symbol: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // PUT /diagrams/{did}/symbols/{sid}
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private void updateSymbol(HttpExchange exchange, Project project,
                              String diagramId, String symbolId) throws IOException {
        JSONObject json;
        try { json = (JSONObject) new JSONParser().parse(readRequestBody(exchange)); }
        catch (Exception e) { sendError(exchange, 400, "Invalid JSON: " + e.getMessage()); return; }

        Object diagram = findDiagram(project, diagramId);
        if (diagram == null) { sendError(exchange, 404, "Diagram not found: " + diagramId); return; }

        Object targetPe = findSymbolById(diagram, symbolId);
        if (targetPe == null) { sendError(exchange, 404, "Symbol not found: " + symbolId); return; }

        Number xNum = (Number) json.get("x");
        Number yNum = (Number) json.get("y");
        Number wNum = (Number) json.get("width");
        Number hNum = (Number) json.get("height");

        SessionManager sm = SessionManager.getInstance();
        sm.createSession(project, "MCP: update symbol bounds - " + symbolId);
        try {
            Method getBounds = targetPe.getClass().getMethod("getBounds");
            Rectangle bounds = (Rectangle) getBounds.invoke(targetPe);

            int nx = (xNum != null) ? xNum.intValue() : bounds.x;
            int ny = (yNum != null) ? yNum.intValue() : bounds.y;
            int nw = (wNum != null) ? wNum.intValue() : bounds.width;
            int nh = (hNum != null) ? hNum.intValue() : bounds.height;

            Rectangle newBounds = new Rectangle(nx, ny, nw, nh);
            Method setBounds = targetPe.getClass().getMethod("setBounds", Rectangle.class);
            setBounds.invoke(targetPe, newBounds);

            sm.closeSession(project);
            sendJson(exchange, 200, String.format(
                "{\"symbolId\":\"%s\",\"x\":%d,\"y\":%d,\"width\":%d,\"height\":%d}",
                escapeJson(symbolId), nx, ny, nw, nh));

        } catch (java.lang.reflect.InvocationTargetException e) {
            sm.cancelSession(project);
            Throwable cause = e.getCause();
            sendError(exchange, 500, "Failed to update symbol: " + (cause != null ? cause.getMessage() : e.getMessage()));
        } catch (Exception e) {
            sm.cancelSession(project);
            sendError(exchange, 500, "Failed to update symbol: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // POST /diagrams/{did}/layout
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private void applyLayout(HttpExchange exchange, Project project, String diagramId) throws IOException {
        JSONObject json = null;
        try {
            String body = readRequestBody(exchange);
            if (body != null && !body.trim().isEmpty())
                json = (JSONObject) new JSONParser().parse(body);
        } catch (Exception ignore) {}

        String algo = (json != null) ? (String) json.get("algorithm") : null;
        // [보류] smart/compact는 일반 quick layout으로 대체 (자동 grid 배치 보류 중).
        if (algo != null && (algo.equalsIgnoreCase("smart") || algo.equalsIgnoreCase("compact"))) {
            algo = "hierarchic";
        }
        // IBD / PAR: algorithm 미지정 시 hierarchic 명시 적용
        if (algo == null) {
            BaseElement base = project.getElementByID(diagramId);
            if (base != null) {
                String ht = base.getHumanType();
                if (ht.contains("Internal Block") || ht.contains("Parametric")) {
                    algo = "hierarchic";
                }
            }
        }
        String layouterConst = resolveAlgorithm(algo);
        String algoLabel = (algo != null) ? algo : "hierarchic";

        Object diagram = findDiagram(project, diagramId);
        if (diagram == null) { sendError(exchange, 404, "Diagram not found: " + diagramId); return; }

        // 레이아웃 적용을 위해 다이어그램 open
        final Object diagForLayout = diagram;
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> openDpe(diagForLayout));
        } catch (Exception edtEx) {
            System.err.println("[Layout] EDT open() failed: " + edtEx.getMessage());
        }

        // [보류] smart/compact 자동 grid 재배치는 연결관계를 반영하지 못해 선이 꼬여 보류.
        // 현재는 일반 quick layout(hierarchic)으로 대체한다.
        // 부활하려면 아래 if 블록의 주석을 해제하면 smartGridLayout()이 다시 호출된다.
        // if (algo != null && (algo.equalsIgnoreCase("smart") || algo.equalsIgnoreCase("compact"))) {
        //     smartGridLayout(exchange, project, diagram, diagramId, algo);
        //     return;
        // }
        if (false) {
            smartGridLayout(exchange, project, diagram, diagramId, algo);
            return;
        }

        SessionManager sm = SessionManager.getInstance();
        sm.createSession(project, "MCP: layout diagram - " + diagramId);
        try {
            Class<?> layoutClass = Class.forName(LAYOUTING_CLASS);

            // Read layouter constant value from static field
            String layouterValue = null;
            try {
                java.lang.reflect.Field f = layoutClass.getField(layouterConst);
                layouterValue = (String) f.get(null);
            } catch (NoSuchFieldException e) {
                System.err.println("[Layout] Constant " + layouterConst + " not found, using default layout.");
            }

            // Layouting.layout 메서드는 AbstractDiagramPresentationElement 파라미터를 요구함
            // (DiagramPresentationElement는 서브클래스이지만 getMethod()는 정확한 선언 타입을 요구)
            Class<?> diagPeClass = Class.forName(
                "com.nomagic.magicdraw.uml.symbols.AbstractDiagramPresentationElement");

            if (layouterValue != null) {
                Method layoutMethod = layoutClass.getMethod("layout", diagPeClass, String.class);
                layoutMethod.invoke(null, diagram, layouterValue);
            } else {
                Method layoutMethod = layoutClass.getMethod("layout", diagPeClass);
                layoutMethod.invoke(null, diagram);
            }

            sm.closeSession(project);
            sendJson(exchange, 200, String.format(
                "{\"diagramId\":\"%s\",\"algorithm\":\"%s\",\"status\":\"ok\"}",
                escapeJson(diagramId), escapeJson(algoLabel)));

        } catch (java.lang.reflect.InvocationTargetException e) {
            sm.cancelSession(project);
            Throwable cause = e.getCause();
            sendError(exchange, 500, "Failed to apply layout: " + (cause != null ? cause.getMessage() : e.getMessage()));
        } catch (Exception e) {
            sm.cancelSession(project);
            sendError(exchange, 500, "Failed to apply layout: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // [보류 - 현재 미사용] smart/compact layout — Claude 자체 grid 재배치.
    //   가로 sprawl은 막지만 연결관계(커넥터)를 반영하지 못해 선이 꼬여 보류함.
    //   applyLayout()의 분기가 막혀 있어 현재 호출되지 않는다(quick layout으로 대체).
    //   부활 조건: 연결 근접성(허브 중앙 + 인접 배치) 로직을 추가한 뒤 applyLayout 분기 복원.
    //   상위 shape 심볼(블럭/파트)을 정사각형에 가까운 grid로 배치해 폭을 제한.
    // -------------------------------------------------------------------------
    @SuppressWarnings("unused")
    private void smartGridLayout(HttpExchange exchange, Project project,
                                 Object diagram, String diagramId, String algoLabel) throws IOException {
        final int[] placed = {0};
        final Throwable[] err = {null};
        final Object diag = diagram;
        Runnable task = () -> {
            SessionManager sm = SessionManager.getInstance();
            sm.createSession(project, "MCP: smart layout - " + diagramId);
            try {
                openDpe(diag);
                // ShapeElement(블럭/파트 박스)만 재배치 대상. Connector(선)는 PathElement라 제외.
                // (이전엔 클래스명에 "path"가 없는 커넥터가 grid에 끼어들어 거대 박스를 그렸음)
                Class<?> shapeElClass = Class.forName("com.nomagic.magicdraw.uml.symbols.shapes.ShapeElement");
                java.util.List<Object> shapes = new java.util.ArrayList<>();
                Collection<?> pes = getPresentationElements(diag);
                for (Object pe : pes) {
                    if (!shapeElClass.isInstance(pe)) continue;   // 선/라벨 등 제외
                    Rectangle b = getSymbolBounds(pe);
                    if (b == null || b.width <= 0 || b.height <= 0) continue;
                    shapes.add(pe);
                }
                int n = shapes.size();
                if (n > 0) {
                    int cols = (int) Math.ceil(Math.sqrt((double) n));
                    if (cols > 6) cols = 6;   // 폭 상한 — 가로 sprawl 방지
                    if (cols < 1) cols = 1;
                    int maxW = 0, maxH = 0;
                    for (Object pe : shapes) {
                        Rectangle b = getSymbolBounds(pe);
                        if (b.width  > maxW) maxW = b.width;
                        if (b.height > maxH) maxH = b.height;
                    }
                    int cellW = maxW + 35, cellH = maxH + 35, startX = 40, startY = 40;
                    for (int i = 0; i < n; i++) {
                        Object pe = shapes.get(i);
                        Rectangle b = getSymbolBounds(pe);
                        int col = i % cols, row = i / cols;
                        int nx = startX + col * cellW;
                        int ny = startY + row * cellH;
                        Method setBounds = pe.getClass().getMethod("setBounds", Rectangle.class);
                        setBounds.invoke(pe, new Rectangle(nx, ny, b.width, b.height));
                        placed[0]++;
                    }
                }
                // 참고: 커넥터 직각 정렬(Route Paths Rectilinearly)은 공개 API에
                // 직접 setter가 없어(자동화 불가) 사용자가 다이어그램 메뉴에서 수동 적용한다.
                sm.closeSession(project);
            } catch (Throwable t) {
                try { sm.cancelSession(project); } catch (Exception ignore) {}
                err[0] = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                    ? t.getCause() : t;
            }
        };
        try {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) task.run();
            else javax.swing.SwingUtilities.invokeAndWait(task);
        } catch (Exception e) {
            sendError(exchange, 500, "Failed smart layout (EDT): " + e.getMessage()); return;
        }
        if (err[0] != null) {
            System.err.println("[SmartLayout] error: " + err[0].getClass().getName() + ": " + err[0].getMessage());
            sendError(exchange, 500, "Failed smart layout: " + err[0].getMessage()); return;
        }
        sendJson(exchange, 200, String.format(
            "{\"diagramId\":\"%s\",\"algorithm\":\"%s\",\"placed\":%d,\"status\":\"ok\"}",
            escapeJson(diagramId), escapeJson(algoLabel), placed[0]));
    }

    // -------------------------------------------------------------------------
    // POST /diagrams/{did}/export  — export diagram as PNG, return file path
    //   ImageExporter.export(AbstractDiagramPresentationElement, ImageExporter.PNG, File)
    //   렌더링이므로 EDT에서 실행. 임시폴더에 mcp_diagram_<id>.png 로 저장(덮어쓰기).
    // -------------------------------------------------------------------------
    private void exportDiagram(HttpExchange exchange, Project project, String diagramId) throws IOException {
        Object diagram = findDiagram(project, diagramId);
        if (diagram == null) { sendError(exchange, 404, "DIAGRAM_NOT_FOUND: " + diagramId); return; }

        String safeId = diagramId.replaceAll("[^A-Za-z0-9_.-]", "_");
        final java.io.File outFile = new java.io.File(
            System.getProperty("java.io.tmpdir"), "mcp_diagram_" + safeId + ".png");
        final Object diag = diagram;
        final Throwable[] err = {null};

        Runnable task = () -> {
            try {
                openDpe(diag);
                Class<?> exporterClass = Class.forName("com.nomagic.magicdraw.export.image.ImageExporter");
                Class<?> adpeClass = Class.forName(
                    "com.nomagic.magicdraw.uml.symbols.AbstractDiagramPresentationElement");
                int pngFormat = exporterClass.getField("PNG").getInt(null);
                Method export = exporterClass.getMethod("export", adpeClass, int.class, java.io.File.class);
                export.invoke(null, diag, pngFormat, outFile);
            } catch (Throwable t) {
                err[0] = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                    ? t.getCause() : t;
            }
        };
        try {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) task.run();
            else javax.swing.SwingUtilities.invokeAndWait(task);
        } catch (Exception e) {
            sendError(exchange, 500, "Failed to export diagram (EDT): " + e.getMessage()); return;
        }
        if (err[0] != null) {
            System.err.println("[Export] error: " + err[0].getClass().getName() + ": " + err[0].getMessage());
            sendError(exchange, 500, "Failed to export diagram: " + err[0].getMessage()); return;
        }
        if (!outFile.exists()) {
            sendError(exchange, 500, "Export reported success but file not found: " + outFile.getAbsolutePath()); return;
        }
        sendJson(exchange, 200, String.format(
            "{\"diagramId\":\"%s\",\"format\":\"png\",\"path\":\"%s\",\"bytes\":%d}",
            escapeJson(diagramId), escapeJson(outFile.getAbsolutePath()), outFile.length()));
    }

    // -------------------------------------------------------------------------
    // POST /diagrams/{did}/relations  — add relation as path in diagram
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private void addRelationToDiagram(HttpExchange exchange, Project project, String diagramId) throws IOException {
        JSONObject json;
        try { json = (JSONObject) new JSONParser().parse(readRequestBody(exchange)); }
        catch (Exception e) { sendError(exchange, 400, "Invalid JSON: " + e.getMessage()); return; }

        String relationId      = (String) json.get("relationId");
        String clientSymbolId  = (String) json.get("clientSymbolId");
        String supplierSymbolId= (String) json.get("supplierSymbolId");
        Boolean allowDuplicate = json.containsKey("allowDuplicate") ? (Boolean) json.get("allowDuplicate") : Boolean.FALSE;

        if (relationId == null) { sendError(exchange, 400, "relationId is required"); return; }

        Element relation = resolveElement(project, relationId);
        if (relation == null) { sendError(exchange, 404, "RELATION_NOT_FOUND: " + relationId); return; }

        Object diagram = findDiagram(project, diagramId);
        if (diagram == null) { sendError(exchange, 404, "DIAGRAM_NOT_FOUND: " + diagramId); return; }

        // 관계 path 생성을 위해 다이어그램 open
        final Object diagForRel = diagram;
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> openDpe(diagForRel));
        } catch (Exception edtEx) {
            System.err.println("[AddRelation] EDT open() failed: " + edtEx.getMessage());
        }

        // --- source/target element 추출 (Dependency계열 / Generalization) ---
        Element sourceEl = null, targetEl = null;
        try {
            // Dependency: client → source, supplier → target
            Method getClient = relation.getClass().getMethod("getClient");
            Method getSupplier = relation.getClass().getMethod("getSupplier");
            Collection<?> clients = (Collection<?>) getClient.invoke(relation);
            Collection<?> suppliers = (Collection<?>) getSupplier.invoke(relation);
            if (!clients.isEmpty()) sourceEl = (Element) clients.iterator().next();
            if (!suppliers.isEmpty()) targetEl = (Element) suppliers.iterator().next();
        } catch (Exception ignore) {}

        if (sourceEl == null || targetEl == null) {
            try {
                // Generalization: specific → source, general → target
                Method getSpecific = relation.getClass().getMethod("getSpecific");
                Method getGeneral  = relation.getClass().getMethod("getGeneral");
                sourceEl = (Element) getSpecific.invoke(relation);
                targetEl = (Element) getGeneral.invoke(relation);
            } catch (Exception ignore) {}
        }

        if (sourceEl == null || targetEl == null) {
            try {
                // Connector: getEnd() → List<ConnectorEnd>, ConnectorEnd.getRole() → ConnectableElement(Port)
                Method getEnd = relation.getClass().getMethod("getEnd");
                java.util.List<?> ends = (java.util.List<?>) getEnd.invoke(relation);
                if (ends.size() >= 2) {
                    Method getRole = ends.get(0).getClass().getMethod("getRole");
                    sourceEl = (Element) getRole.invoke(ends.get(0));
                    targetEl = (Element) getRole.invoke(ends.get(1));
                }
            } catch (Exception ignore) {}
        }

        if (sourceEl == null || targetEl == null) {
            sendError(exchange, 409, "Cannot resolve source/target elements from relation: " + relationId); return;
        }

        // --- 다이어그램 내 심볼 수집 (중첩 포함) ---
        Map<String, List<Object>> symbolsByElementId = new HashMap<>();
        collectSymbolsByElementId(diagram, symbolsByElementId);

        // --- 중복 시각화 검사 ---
        if (!Boolean.TRUE.equals(allowDuplicate)) {
            Object existingPath = findExistingPath(diagram, relationId);
            if (existingPath != null) {
                String pathId = getLocalId(existingPath);
                sendError(exchange, 409, "ALREADY_VISUALIZED: relation is already shown in diagram. existingPathId=" + pathId
                    + ". Set allowDuplicate=true to force.");
                return;
            }
        }

        // --- client 심볼 결정 ---
        Object clientSymbol  = resolveSymbol(clientSymbolId,  sourceEl.getLocalID(), symbolsByElementId, exchange, "source"); if (clientSymbol == null) return;
        Object supplierSymbol= resolveSymbol(supplierSymbolId, targetEl.getLocalID(), symbolsByElementId, exchange, "target"); if (supplierSymbol == null) return;

        // --- Path 생성 ---
        SessionManager sm = SessionManager.getInstance();
        sm.createSession(project, "MCP: add relation to diagram - " + relationId);
        try {
            Class<?> pemClass = Class.forName(PEM_CLASS);
            Object pem = pemClass.getMethod("getInstance").invoke(null);
            Class<?> peClass = Class.forName("com.nomagic.magicdraw.uml.symbols.PresentationElement");
            Method createPath = pemClass.getMethod("createPathElement", Element.class, peClass, peClass);
            Object path = createPath.invoke(pem, relation, clientSymbol, supplierSymbol);
            sm.closeSession(project);

            String pathId = getLocalId(path);
            sendJson(exchange, 201, String.format(
                "{\"id\":\"%s\",\"relationId\":\"%s\",\"clientSymbolId\":\"%s\",\"supplierSymbolId\":\"%s\"}",
                escapeJson(pathId), escapeJson(relationId),
                escapeJson(getLocalId(clientSymbol)), escapeJson(getLocalId(supplierSymbol))));
        } catch (java.lang.reflect.InvocationTargetException e) {
            sm.cancelSession(project);
            Throwable cause = e.getCause();
            sendError(exchange, 500, "Failed to create path: " + (cause != null ? cause.getMessage() : e.getMessage()));
        } catch (Exception e) {
            sm.cancelSession(project);
            sendError(exchange, 500, "Failed to create path: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // POST /diagrams/{did}/paths/display-all
    // — 모든 심볼을 선택한 상태에서 DisplayPathElements.displayPathElements 호출
    //   결과: 선택 심볼들 사이의 모든 모델 관계가 path로 자동 시각화됨
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private void displayAllPaths(HttpExchange exchange, Project project, String diagramId) throws IOException {
        Object diagram = findDiagram(project, diagramId);
        if (diagram == null) { sendError(exchange, 404, "DIAGRAM_NOT_FOUND: " + diagramId); return; }

        // 옵션: displayContainment (기본 false). 요구사항 계층 등에서 containment 표시가 필요할 때 true.
        boolean displayContainment = false;
        try {
            String body = readRequestBody(exchange);
            if (body != null && !body.trim().isEmpty()) {
                JSONObject j = (JSONObject) new JSONParser().parse(body);
                displayContainment = Boolean.TRUE.equals(j.get("displayContainment"));
            }
        } catch (Exception ignore) {}
        final boolean fDisplayContainment = displayContainment;

        // 결과/오류를 EDT 밖으로 전달하기 위한 홀더.
        final java.util.List<Object> keptPaths = new java.util.ArrayList<>();
        final int[] selectedCount = {0};
        final Throwable[] err = {null};
        final Object diagramFinal = diagram;

        // 그리기(open/collect/displayPaths/중복삭제)는 반드시 EDT에서 수행한다.
        // 별도 스레드에서 PresentationElement를 변경하면 paint 중 ConcurrentModificationException 발생.
        Runnable task = () -> {
            SessionManager sm = SessionManager.getInstance();
            sm.createSession(project, "MCP: display all paths - " + diagramId);
            try {
                openDpe(diagramFinal);

                java.util.Set<Object> visited =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                java.util.List<Object> shapeSymbols = new java.util.ArrayList<>();
                collectShapeSymbols(diagramFinal, shapeSymbols, visited);
                selectedCount[0] = shapeSymbols.size();
                if (shapeSymbols.isEmpty()) { sm.cancelSession(project); return; }

                Class<?> dpeClass = Class.forName("com.nomagic.magicdraw.uml.symbols.DisplayPathElements");
                Class<?> optClass = Class.forName(
                    "com.nomagic.magicdraw.uml.symbols.DisplayPathElements$DisplayPathOptions");

                // 관계는 항상 그리고, containment(소유) 링크는 호출자 옵션에 따른다.
                // (기본 false → BDD/IBD 깔끔. 요구사항 계층 등은 true로 호출.)
                Object opts = optClass.getConstructor().newInstance();
                optClass.getMethod("setDisplayRelationships", boolean.class).invoke(opts, true);
                optClass.getMethod("setDisplayContainmentLinks", boolean.class).invoke(opts, fDisplayContainment);
                try { optClass.getMethod("setDisplayNoteAnchors", boolean.class).invoke(opts, false); } catch (Exception ignore) {}
                try { optClass.getMethod("setDisplayPathsToSelf", boolean.class).invoke(opts, false); } catch (Exception ignore) {}

                Method displayPaths = dpeClass.getMethod("displayPathElements", Collection.class, optClass);
                Object resultObj = displayPaths.invoke(null, shapeSymbols, opts);

                // 같은 모델 관계가 여러 심볼 쌍에 중복으로 그려질 수 있어 관계 ID 기준 1개만 남긴다.
                java.util.List<Object> dupPaths = new java.util.ArrayList<>();
                if (resultObj instanceof Collection) {
                    java.util.Set<String> seenRel = new java.util.HashSet<>();
                    for (Object pe : (Collection<?>) resultObj) {
                        String relId = relationIdOfPath(pe);
                        if (!relId.isEmpty() && !seenRel.add(relId)) dupPaths.add(pe);
                        else keptPaths.add(pe);
                    }
                }
                if (!dupPaths.isEmpty()) {
                    Class<?> pemClass = Class.forName("com.nomagic.magicdraw.openapi.uml.PresentationElementsManager");
                    Class<?> peClass  = Class.forName("com.nomagic.magicdraw.uml.symbols.PresentationElement");
                    Object pem = pemClass.getMethod("getInstance").invoke(null);
                    Method deletePE = pemClass.getMethod("deletePresentationElement", peClass);
                    for (Object pe : dupPaths) { try { deletePE.invoke(pem, pe); } catch (Exception ignore) {} }
                }

                sm.closeSession(project);
            } catch (Throwable t) {
                try { sm.cancelSession(project); } catch (Exception ignore) {}
                err[0] = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                    ? t.getCause() : t;
            }
        };

        try {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) task.run();
            else javax.swing.SwingUtilities.invokeAndWait(task);
        } catch (Exception e) {
            sendError(exchange, 500, "Failed to display paths (EDT): " + e.getMessage());
            return;
        }

        if (err[0] != null) {
            System.err.println("[DisplayAllPaths] error: " + err[0].getClass().getName() + ": " + err[0].getMessage());
            err[0].printStackTrace(System.err);
            sendError(exchange, 500, "Failed to display paths: " + err[0].getMessage());
            return;
        }

        StringJoiner pathsJson = new StringJoiner(",", "[", "]");
        int count = 0;
        for (Object pe : keptPaths) {
            count++;
            String pid = getLocalId(pe);
            String relId = "";
            String relName = "";
            try {
                Object el = pe.getClass().getMethod("getElement").invoke(pe);
                if (el instanceof Element) {
                    relId = ((Element) el).getLocalID();
                    if (el instanceof NamedElement) {
                        String n = ((NamedElement) el).getName();
                        if (n != null) relName = n;
                    }
                }
            } catch (Exception ignore) {}
            pathsJson.add(String.format(
                "{\"id\":\"%s\",\"relationId\":\"%s\",\"relationName\":\"%s\"}",
                escapeJson(pid), escapeJson(relId), escapeJson(relName)));
        }

        sendJson(exchange, 200, String.format(
            "{\"diagramId\":\"%s\",\"selectedSymbolCount\":%d,\"count\":%d,\"paths\":%s}",
            escapeJson(diagramId), selectedCount[0], count, pathsJson));
    }

    /** path PE가 표현하는 모델 관계의 localID 반환. 없으면 "". */
    private String relationIdOfPath(Object pe) {
        try {
            Object el = pe.getClass().getMethod("getElement").invoke(pe);
            if (el instanceof Element) return ((Element) el).getLocalID();
        } catch (Exception ignore) {}
        return "";
    }

    /** shape 계열 PE만 재귀 수집 — Path / Label / Text / Name / Compartment 제외 */
    private void collectShapeSymbols(Object parent, java.util.List<Object> out, java.util.Set<Object> visited) {
        try {
            Collection<?> pes = getPresentationElements(parent);
            for (Object pe : pes) {
                if (!visited.add(pe)) continue;
                String clsName = pe.getClass().getSimpleName();
                boolean isPath = false;
                try {
                    pe.getClass().getMethod("getClient");
                    isPath = true;
                } catch (NoSuchMethodException ignore) {}
                boolean isAuxiliary = clsName.endsWith("Label") || clsName.endsWith("Text")
                    || clsName.endsWith("Name") || clsName.contains("Compartment");
                if (!isPath && !isAuxiliary) {
                    out.add(pe);
                }
                collectShapeSymbols(pe, out, visited);
            }
        } catch (Exception ignore) {}
    }

    // -------------------------------------------------------------------------
    // POST /diagrams/{did}/symbols/move  — batch move symbols
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private void moveSymbols(HttpExchange exchange, Project project, String diagramId) throws IOException {
        JSONObject json;
        try { json = (JSONObject) new JSONParser().parse(readRequestBody(exchange)); }
        catch (Exception e) { sendError(exchange, 400, "Invalid JSON: " + e.getMessage()); return; }

        JSONArray symbolIdsJson = (JSONArray) json.get("symbolIds");
        Number dxNum = (Number) json.get("dx");
        Number dyNum = (Number) json.get("dy");
        // pushAdjacent: 부모 심볼 확장 시 인접 심볼을 자동으로 밀어낼지 여부.
        // 기본값 false — 명시적으로 true를 전달할 때만 인접 심볼 이동.
        boolean pushAdjacent = Boolean.TRUE.equals(json.get("pushAdjacent"));
        // expandParent: 자식이 부모 밖으로 나가면 부모를 늘릴지 여부. 기본값 true(기존 동작).
        // 포트를 옮길 때는 false로 줘야 한다. 포트는 원래 테두리에 걸쳐 밖으로 나가 있는
        // 것이 정상인데, 그걸 '삐져나왔다'고 보고 블록을 늘리면 블록이 커진다. 그러면
        // 레이아웃 엔진이 선이 지나가라고 비워 둔 자리를 블록이 먹어 선이 관통한다.
        boolean expandParent = !Boolean.FALSE.equals(json.get("expandParent"));
        if (symbolIdsJson == null || symbolIdsJson.isEmpty()) { sendError(exchange, 400, "symbolIds must be non-empty array"); return; }
        if (dxNum == null || dyNum == null) { sendError(exchange, 400, "dx and dy are required"); return; }

        int dx = dxNum.intValue();
        int dy = dyNum.intValue();

        Object diagram = findDiagram(project, diagramId);
        if (diagram == null) { sendError(exchange, 404, "DIAGRAM_NOT_FOUND: " + diagramId); return; }

        // 심볼 ID 목록 수집 및 유효성 검사
        List<String> requestedIds = new ArrayList<>();
        for (Object o : symbolIdsJson) requestedIds.add(o.toString());

        // 다이어그램 내 모든 심볼 맵 구성 (id → symbol 객체)
        Map<String, Object> allSymbols = new HashMap<>();
        collectAllSymbols(diagram, allSymbols);

        StringJoiner movedJson   = new StringJoiner(",", "[", "]");
        StringJoiner skippedJson = new StringJoiner(",", "[", "]");
        StringJoiner resizedJson = new StringJoiner(",", "[", "]");

        // --- 조상이 함께 요청된 심볼은 제외 ---
        // 부모가 움직이면 자식은 따라 움직인다. 자식까지 또 옮기면 두 배로 밀린다.
        // 직계 부모만 보면 A(블록)→B(파트)→C(포트)에서 A와 C만 요청했을 때 C가 안 걸러진다.
        // IBD의 중첩 파트 구조에서는 이 형태가 일반적이라 조상 체인 전체를 본다.
        Set<String> requestedIdSet = new HashSet<>(requestedIds);
        Set<String> effectiveIds = new LinkedHashSet<>();
        for (String sid : requestedIds) {
            Object sym = allSymbols.get(sid);
            if (sym == null) { skippedJson.add(String.format("{\"symbolId\":\"%s\",\"reason\":\"not found\"}", escapeJson(sid))); continue; }
            String ancestorId = findRequestedAncestor(sym, diagram, allSymbols, requestedIdSet);
            if (ancestorId != null) {
                skippedJson.add(String.format(
                    "{\"symbolId\":\"%s\",\"reason\":\"auto-excluded: ancestor %s also in move list\"}",
                    escapeJson(sid), escapeJson(ancestorId)));
            } else {
                effectiveIds.add(sid);
            }
        }

        // 심볼 변경은 EDT(화면 갱신 스레드)에서 해야 한다. HTTP 워커 스레드에서 바로 고치면
        // MagicDraw가 화면을 그리는 도중에 좌표가 바뀌어 예외가 나거나 화면이 깨진다.
        // (이 파일의 displayAllPaths 주석에 같은 이유가 이미 적혀 있는데, 정작 최종 출고
        //  경로인 여기서는 안 지키고 있었다.)
        final Object diagramF = diagram;
        final int dxF = dx, dyF = dy;
        final boolean pushAdjacentF = pushAdjacent;
        final boolean expandParentF = expandParent;
        final Throwable[] err = {null};
        Runnable task = () -> {
        SessionManager sm = SessionManager.getInstance();
        sm.createSession(project, "MCP: move symbols - " + diagramId);
        try {
            Class<?> pemClass = Class.forName(PEM_CLASS);
            Object pem = pemClass.getMethod("getInstance").invoke(null);
            Class<?> peClass = Class.forName("com.nomagic.magicdraw.uml.symbols.PresentationElement");
            Method movePE = pemClass.getMethod("movePresentationElement", peClass, Point.class);
            Method reshapeMethod = pemClass.getMethod("reshapeShapeElement",
                Class.forName("com.nomagic.magicdraw.uml.symbols.shapes.ShapeElement"), Rectangle.class);

            final int PADDING = 20;

            // 부모 심볼 확장 필요 여부 추적
            Map<String, Rectangle> parentNewBounds = new HashMap<>();

            for (String sid : effectiveIds) {
                Object sym = allSymbols.get(sid);
                Rectangle bounds = getSymbolBounds(sym);
                if (bounds == null) { skippedJson.add(String.format("{\"symbolId\":\"%s\",\"reason\":\"cannot get bounds\"}", escapeJson(sid))); continue; }

                int newX = bounds.x + dxF;
                int newY = bounds.y + dyF;

                movePE.invoke(pem, sym, new Point(newX, newY));

                // 요청 좌표가 아니라 실제 반영된 좌표를 돌려준다.
                // MagicDraw가 부모 경계로 보정하는 경우가 있어, 요청값을 그대로 응답하면
                // 호출한 쪽이 실제와 다른 좌표를 기준으로 다음 계산을 하게 된다.
                Rectangle after = getSymbolBounds(sym);
                int actualX = (after != null) ? after.x : newX;
                int actualY = (after != null) ? after.y : newY;
                movedJson.add(String.format("{\"symbolId\":\"%s\",\"x\":%d,\"y\":%d}", escapeJson(sid), actualX, actualY));

                // 부모가 있으면 부모 확장 필요 여부 계산
                Object parentSym = expandParentF ? getParentSymbol(sym, diagramF, allSymbols) : null;
                if (parentSym != null) {
                    String parentId = getLocalId(parentSym);
                    Rectangle parentBounds = parentNewBounds.getOrDefault(parentId, getSymbolBounds(parentSym));
                    if (parentBounds != null) {
                        int requiredRight  = actualX + bounds.width  + PADDING;
                        int requiredBottom = actualY + bounds.height + PADDING;
                        int newW = Math.max(parentBounds.width,  requiredRight  - parentBounds.x);
                        int newH = Math.max(parentBounds.height, requiredBottom - parentBounds.y);
                        if (newW != parentBounds.width || newH != parentBounds.height) {
                            Rectangle newParentBounds = new Rectangle(parentBounds.x, parentBounds.y, newW, newH);
                            parentNewBounds.put(parentId, newParentBounds);
                        }
                    }
                }
            }

            // 부모 심볼 확장 적용
            for (Map.Entry<String, Rectangle> entry : parentNewBounds.entrySet()) {
                Object parentSym = allSymbols.get(entry.getKey());
                if (parentSym == null) continue;
                Rectangle nb = entry.getValue();
                try {
                    reshapeMethod.invoke(pem, parentSym, nb);
                    resizedJson.add(String.format("{\"symbolId\":\"%s\",\"width\":%d,\"height\":%d}",
                        escapeJson(entry.getKey()), nb.width, nb.height));

                    // pushAdjacent=true 일 때만 인접 심볼 밀어내기
                    if (pushAdjacentF) {
                        pushAdjacentSymbols(pem, movePE, peClass, diagramF, parentSym, nb, allSymbols, effectiveIds, PADDING, movedJson);
                    }
                } catch (Exception e) {
                    System.err.println("[MoveSymbols] reshape parent failed: " + e.getMessage());
                }
            }

            sm.closeSession(project);
        } catch (Throwable t) {
            try { sm.cancelSession(project); } catch (Exception ignore) {}
            err[0] = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                ? t.getCause() : t;
        }
        };

        try {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) task.run();
            else javax.swing.SwingUtilities.invokeAndWait(task);
        } catch (Exception e) {
            sendError(exchange, 500, "moveSymbols failed (EDT): " + e.getMessage()); return;
        }
        if (err[0] != null) {
            sendError(exchange, 500, "moveSymbols failed: " + err[0].getMessage()); return;
        }
        // 응답은 세션이 닫힌 뒤에 보낸다. 같은 try 안에 두면 클라이언트가 끊겼을 때
        // 이미 닫힌 세션을 취소하려다 다른 예외가 겹친다.
        sendJson(exchange, 200, String.format(
            "{\"moved\":%s,\"skipped\":%s,\"resized\":%s}",
            movedJson, skippedJson, resizedJson));
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /**
     * Find the DiagramPresentationElement for the given diagram ID and open it.
     *
     * API reference (openapi examples/sequencecreation, symbolcreation):
     *   Diagram diagram = ...;
     *   DiagramPresentationElement dpe = project.getDiagram(diagram);
     *   dpe.open();  // must be called before adding symbols
     *
     * project.getDiagram() takes a Diagram (subtype of Element), not Element.
     * We resolve the Diagram class via Class.forName to use the correct method.
     */
    private Object findDiagram(Project project, String diagramId) {
        try {
            BaseElement base = project.getElementByID(diagramId);
            if (base == null) return null;
            if (!(base instanceof Element)) return null;
            Element diagEl = (Element) base;

            // Try 1: project.getDiagram(Diagram) — official API
            // Diagram class: com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Diagram
            // NOTE: openDpe()는 findDiagram()에서 호출하지 않는다.
            //       쓰기 작업(addSymbol, addRelation 등)에서 필요 시 명시적으로 open.
            try {
                Class<?> diagramClass = Class.forName(
                    "com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Diagram");
                if (diagramClass.isInstance(diagEl)) {
                    Method getDiagram = project.getClass().getMethod("getDiagram", diagramClass);
                    Object dpe = getDiagram.invoke(project, diagEl);
                    if (dpe != null) {
                        System.err.println("[DiagramSymbols] Found via getDiagram(Diagram): " + diagramId);
                        return dpe;
                    }
                }
            } catch (NoSuchMethodException ignore) {}
              catch (Exception e) {
                System.err.println("[DiagramSymbols] getDiagram(Diagram) failed: " + e.getMessage());
            }

            // Try 2: project.getDiagrams() — scan all diagrams (opened and closed)
            try {
                Method getDiagrams = project.getClass().getMethod("getDiagrams");
                Collection<?> all = (Collection<?>) getDiagrams.invoke(project);
                for (Object dpe : all) {
                    try {
                        Method getDiagram = dpe.getClass().getMethod("getDiagram");
                        Object diagramElement = getDiagram.invoke(dpe);
                        if (diagramElement instanceof Element) {
                            if (diagramId.equals(((Element) diagramElement).getLocalID())) {
                                System.err.println("[DiagramSymbols] Found via getDiagrams(): " + diagramId);
                                return dpe;
                            }
                        }
                    } catch (Exception ignore) {}
                }
            } catch (Exception e) {
                System.err.println("[DiagramSymbols] getDiagrams() failed: " + e.getMessage());
            }

            // Try 3: getOpenedDiagrams() — only already-opened diagrams (last resort)
            try {
                Method getOpenedDiagrams = project.getClass().getMethod("getOpenedDiagrams");
                Collection<?> opened = (Collection<?>) getOpenedDiagrams.invoke(project);
                for (Object dpe : opened) {
                    try {
                        Method getDiagram = dpe.getClass().getMethod("getDiagram");
                        Object diagramElement = getDiagram.invoke(dpe);
                        if (diagramElement instanceof Element) {
                            if (diagramId.equals(((Element) diagramElement).getLocalID())) {
                                System.err.println("[DiagramSymbols] Found via getOpenedDiagrams(): " + diagramId);
                                return dpe;
                            }
                        }
                    } catch (Exception ignore) {}
                }
            } catch (Exception ignore) {}

            System.err.println("[DiagramSymbols] Diagram not found: " + diagramId);
            return null;
        } catch (Exception e) {
            System.err.println("[DiagramSymbols] findDiagram error: " + e.getMessage());
            return null;
        }
    }

    /** Call dpe.open() so the diagram is active and symbols can be added */
    private void openDpe(Object dpe) {
        try {
            dpe.getClass().getMethod("open").invoke(dpe);
        } catch (Exception e) {
            System.err.println("[DiagramSymbols] open() failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Collection<?> getPresentationElements(Object diagram) throws Exception {
        try {
            Method m = diagram.getClass().getMethod("getPresentationElements");
            return (Collection<?>) m.invoke(diagram);
        } catch (NoSuchMethodException e) {
            Method m = diagram.getClass().getMethod("getPresentations");
            return (Collection<?>) m.invoke(diagram);
        }
    }

    private String presentationToJson(Object pe) {
        try {
            // 심볼(PresentationElement)에는 getLocalID()가 없다 — getID()만 있다.
            // 직접 리플렉션으로 getLocalID를 부르면 매번 실패해 id가 빈 문자열로 나갔다.
            String peId = getLocalId(pe);

            String elementId = "";
            String elementName = "";
            try {
                Method getEl = pe.getClass().getMethod("getElement");
                Object el = getEl.invoke(pe);
                if (el instanceof Element) {
                    elementId = ((Element) el).getLocalID();
                    if (el instanceof NamedElement)
                        elementName = ((NamedElement) el).getName();
                    if (elementName == null) elementName = "";
                }
            } catch (Exception ignore) {}

            int x = 0, y = 0, w = 0, h = 0;
            try {
                Method getBounds = pe.getClass().getMethod("getBounds");
                Object boundsObj = getBounds.invoke(pe);
                if (boundsObj instanceof Rectangle) {
                    Rectangle r = (Rectangle) boundsObj;
                    x = r.x; y = r.y; w = r.width; h = r.height;
                }
            } catch (Exception ignore) {}

            return String.format(
                "{\"id\":\"%s\",\"elementId\":\"%s\",\"elementName\":\"%s\"," +
                "\"x\":%d,\"y\":%d,\"width\":%d,\"height\":%d}",
                escapeJson(peId), escapeJson(elementId), escapeJson(elementName),
                x, y, w, h);

        } catch (Exception e) {
            return null;
        }
    }

    private Object findSymbolById(Object diagram, String symbolId) {
        // getSymbols 와 동일한 재귀 수집(collectAllSymbols, getLocalId 헬퍼 기반)을 재사용해
        // 읽기/쓰기의 ID 체계를 일치시키고, 중첩된 포트·파트 심볼까지 도달한다.
        try {
            Map<String, Object> all = new HashMap<>();
            collectAllSymbols(diagram, all);
            return all.get(symbolId);
        } catch (Exception e) {
            System.err.println("[DiagramSymbols] findSymbolById error: " + e.getMessage());
            return null;
        }
    }

    private Class<?> findAbstractDiagramClass(Class<?> clazz) {
        String[] candidates = {
            "com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement",
            "com.nomagic.magicdraw.uml.symbols.AbstractDiagramPresentationElement",
            "com.nomagic.magicdraw.uml.symbols.PresentationElement"
        };
        for (String c : candidates) {
            try {
                Class<?> candidate = Class.forName(c);
                if (candidate.isAssignableFrom(clazz)) return candidate;
            } catch (ClassNotFoundException ignore) {}
        }
        return clazz;
    }

    /**
     * Returns the declared parameter type used in PresentationElementsManager.createShapeElement().
     * API: createShapeElement(Element, PresentationElement, ...)
     * getMethod() requires exact declared type — subtype won't work.
     * So we try PresentationElement first (most abstract), then narrower types.
     */
    private Class<?> findPresentationElementClass(Class<?> diagClass) {
        String[] candidates = {
            "com.nomagic.magicdraw.uml.symbols.PresentationElement",
            "com.nomagic.magicdraw.uml.symbols.AbstractDiagramPresentationElement",
            "com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement"
        };
        for (String c : candidates) {
            try {
                Class<?> candidate = Class.forName(c);
                if (candidate.isAssignableFrom(diagClass)) return candidate;
            } catch (ClassNotFoundException ignore) {}
        }
        return diagClass;
    }

    // ── 관계 시각화 헬퍼 ────────────────────────────────────────────────────────

    /** 다이어그램 내 심볼을 elementId 기준으로 수집 (중첩 재귀 탐색 + PE 중복 제거)
     *  MagicDraw API에 따라 getPresentationElements가 이미 중첩 포함인 경우가 있어
     *  IdentityHashMap 기반으로 동일 PE 객체를 한 번만 처리한다. */
    private void collectSymbolsByElementId(Object parent, Map<String, List<Object>> map) {
        java.util.Set<Object> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        collectSymbolsByElementIdRec(parent, map, visited);
    }

    private void collectSymbolsByElementIdRec(Object parent, Map<String, List<Object>> map, java.util.Set<Object> visited) {
        try {
            Collection<?> pes = getPresentationElements(parent);
            for (Object pe : pes) {
                if (!visited.add(pe)) continue; // 이미 처리된 PE 스킵
                // Label/Text/Name PE는 shape가 아니므로 제외 (Port 등에서 shape + label 중복 방지)
                String clsName = pe.getClass().getSimpleName();
                boolean isShape = !(clsName.endsWith("Label") || clsName.endsWith("Text")
                    || clsName.endsWith("Name") || clsName.contains("Compartment"));
                if (isShape) {
                    try {
                        Method getEl = pe.getClass().getMethod("getElement");
                        Object el = getEl.invoke(pe);
                        if (el instanceof Element) {
                            String eid = ((Element) el).getLocalID();
                            map.computeIfAbsent(eid, k -> new ArrayList<>()).add(pe);
                        }
                    } catch (Exception ignore) {}
                }
                collectSymbolsByElementIdRec(pe, map, visited);
            }
        } catch (Exception ignore) {}
    }

    /** 다이어그램에 해당 relation이 이미 PathElement로 표시되어 있는지 확인 */
    private Object findExistingPath(Object diagram, String relationId) {
        try {
            Collection<?> pes = getPresentationElements(diagram);
            for (Object pe : pes) {
                try {
                    Method getEl = pe.getClass().getMethod("getElement");
                    Object el = getEl.invoke(pe);
                    // PathElement 여부: getClient()/getSupplier() 메서드 존재 확인
                    pe.getClass().getMethod("getClient");
                    if (el instanceof Element && relationId.equals(((Element) el).getLocalID())) return pe;
                } catch (NoSuchMethodException ignore) {
                    // ShapeElement (not PathElement) — skip
                } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
        return null;
    }

    /**
     * symbol ID 또는 element ID로 심볼을 결정.
     * 후보가 복수이면 409 MULTIPLE_SYMBOLS 반환.
     */
    private Object resolveSymbol(String explicitSymbolId, String elementId,
                                 Map<String, List<Object>> symbolsByElementId,
                                 HttpExchange exchange, String role) throws IOException {
        if (explicitSymbolId != null && !explicitSymbolId.isEmpty()) {
            // 명시적으로 지정된 경우: symbolsByElementId 전체에서 탐색
            for (List<Object> syms : symbolsByElementId.values()) {
                for (Object sym : syms) {
                    if (explicitSymbolId.equals(getLocalId(sym))) return sym;
                }
            }
            sendError(exchange, 409, "SYMBOL_NOT_FOUND: specified " + role + " symbolId not in diagram: " + explicitSymbolId);
            return null;
        }
        List<Object> candidates = symbolsByElementId.get(elementId);
        if (candidates == null || candidates.isEmpty()) {
            sendError(exchange, 409, "SYMBOL_NOT_FOUND: " + role + " element has no symbol in diagram. elementId=" + elementId);
            return null;
        }
        if (candidates.size() == 1) return candidates.get(0);
        // 복수 심볼 — 후보 목록 반환
        StringJoiner arr = new StringJoiner(",", "[", "]");
        for (Object sym : candidates) {
            String sid = getLocalId(sym);
            String name = "";
            try {
                Method getEl = sym.getClass().getMethod("getElement");
                Object el = getEl.invoke(sym);
                if (el instanceof NamedElement) name = ((NamedElement) el).getName();
            } catch (Exception ignore) {}
            arr.add(String.format("{\"symbolId\":\"%s\",\"elementName\":\"%s\"}", escapeJson(sid), escapeJson(name)));
        }
        sendError(exchange, 409, "MULTIPLE_SYMBOLS: " + role + " element has " + candidates.size()
            + " symbols. Specify " + role + "SymbolId. candidates=" + arr);
        return null;
    }

    private String getLocalId(Object obj) {
        // 심볼(PresentationElement)의 ID는 getID(), 모델 Element의 ID는 getLocalID().
        // 이 헬퍼는 심볼/path에 쓰이므로 getID()를 우선 시도하고, 모델 Element가
        // 흘러들어올 경우를 대비해 getLocalID()로 폴백한다.
        try {
            Object r = obj.getClass().getMethod("getID").invoke(obj);
            if (r != null && !r.toString().isEmpty()) return r.toString();
        } catch (Exception ignore) {}
        try {
            Object r = obj.getClass().getMethod("getLocalID").invoke(obj);
            if (r != null) return r.toString();
        } catch (Exception ignore) {}
        return "";
    }

    // ── 배치 이동 헬퍼 ────────────────────────────────────────────────────────

    /** 다이어그램 내 모든 심볼을 id → 객체 맵으로 수집 (중첩 재귀) */
    private void collectAllSymbols(Object parent, Map<String, Object> map) {
        // visited 가드 없이 돌면 심볼 트리에 순환이 있을 때 StackOverflowError로 죽는다.
        // 이 함수는 moveSymbols(최종 출고 경로)와 findSymbolById가 쓴다.
        collectAllSymbolsRec(parent, map, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    private void collectAllSymbolsRec(Object parent, Map<String, Object> map, java.util.Set<Object> visited) {
        if (parent == null || !visited.add(parent)) return;
        try {
            for (Object pe : getPresentationElements(parent)) {
                String sid = getLocalId(pe);
                if (!sid.isEmpty()) map.put(sid, pe);
                collectAllSymbolsRec(pe, map, visited);
            }
        } catch (Exception ignore) {}
    }

    /** 심볼의 부모 심볼 ID 반환 (다이어그램 루트가 부모면 null) */
    private String getParentSymbolId(Object sym, Object diagram) {
        try {
            Method getParent = sym.getClass().getMethod("getParent");
            Object parent = getParent.invoke(sym);
            if (parent == null || parent == diagram) return null;
            String pid = getLocalId(parent);
            return pid.isEmpty() ? null : pid;
        } catch (Exception e) { return null; }
    }

    /** 심볼의 부모 심볼 객체 반환 */
    private Object getParentSymbol(Object sym, Object diagram, Map<String, Object> allSymbols) {
        String pid = getParentSymbolId(sym, diagram);
        return pid != null ? allSymbols.get(pid) : null;
    }

    /**
     * 이 심볼의 조상 중에 이동 요청 목록에 든 것이 있으면 그 ID를 반환한다(없으면 null).
     *
     * 직계 부모만 보면 손자가 걸러지지 않는다. IBD는 블록→파트→포트처럼
     * 2단계 이상 중첩이 기본이라 조상 체인을 끝까지 따라간다.
     */
    private String findRequestedAncestor(Object sym, Object diagram,
                                         Map<String, Object> allSymbols, Set<String> requestedIds) {
        java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Object cur = getParentSymbol(sym, diagram, allSymbols);
        while (cur != null && seen.add(cur)) {
            String id = getLocalId(cur);
            if (!id.isEmpty() && requestedIds.contains(id)) return id;
            cur = getParentSymbol(cur, diagram, allSymbols);
        }
        return null;
    }

    /** 심볼의 bounds 반환 */
    private Rectangle getSymbolBounds(Object sym) {
        try {
            Method getBounds = sym.getClass().getMethod("getBounds");
            Object b = getBounds.invoke(sym);
            return (b instanceof Rectangle) ? (Rectangle) b : null;
        } catch (Exception e) { return null; }
    }

    /**
     * 부모 심볼이 확장된 후 인접 최상위 심볼이 겹치면 밀어냄 (Gap 5).
     * x/y 각 축에서 겹치는 방향으로만 밀어냄.
     */
    @SuppressWarnings("unchecked")
    private void pushAdjacentSymbols(Object pem, Method movePE, Class<?> peClass,
                                     Object diagram, Object parentSym, Rectangle newParentBounds,
                                     Map<String, Object> allSymbols, Set<String> movedIds,
                                     int padding, StringJoiner movedJson) {
        try {
            Collection<?> topLevel = getPresentationElements(diagram);
            for (Object sibling : topLevel) {
                if (sibling == parentSym) continue;
                Rectangle sb = getSymbolBounds(sibling);
                if (sb == null) continue;
                if (!newParentBounds.intersects(sb)) continue;

                int pushX = sb.x, pushY = sb.y;
                boolean pushed = false;

                // x축 겹침: 부모 오른쪽으로 밀어냄
                if (sb.x < newParentBounds.x + newParentBounds.width &&
                    sb.x + sb.width > newParentBounds.x) {
                    pushX = newParentBounds.x + newParentBounds.width + padding;
                    pushed = true;
                }
                // y축 겹침: 부모 아래쪽으로 밀어냄
                if (sb.y < newParentBounds.y + newParentBounds.height &&
                    sb.y + sb.height > newParentBounds.y) {
                    pushY = newParentBounds.y + newParentBounds.height + padding;
                    pushed = true;
                }

                if (pushed) {
                    try {
                        movePE.invoke(pem, sibling, new Point(pushX, pushY));
                        String sid = getLocalId(sibling);
                        movedJson.add(String.format(
                            "{\"symbolId\":\"%s\",\"x\":%d,\"y\":%d,\"reason\":\"pushed by parent resize\"}",
                            escapeJson(sid), pushX, pushY));
                    } catch (Exception e) {
                        System.err.println("[MoveSymbols] push adjacent failed: " + e.getMessage());
                    }
                }
            }
        } catch (Exception ignore) {}
    }

}
