package com.example.mcpbridge.handlers;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * /project/{projectId}/... 경로 디스패처
 *
 * GET    /project/{id}/elements                       → ElementsHandler.list
 * GET    /project/{id}/elements?query=..              → ElementsHandler.search
 * GET    /project/{id}/element/{eid}                  → ElementsHandler.get
 * POST   /project/{id}/element                        → ElementsHandler.create
 * PUT    /project/{id}/element/{eid}                  → ElementsHandler.update
 * DELETE /project/{id}/element/{eid}                  → ElementsHandler.delete
 * GET    /project/{id}/elements/{eid}/props           → StereotypePropertiesHandler.getProps
 * PUT    /project/{id}/elements/{eid}/props           → StereotypePropertiesHandler.setProps
 * GET    /project/{id}/diagrams                       → DiagramsHandler.list
 * GET    /project/{id}/diagrams/{did}                 → DiagramsHandler.get
 * POST   /project/{id}/diagram                        → DiagramsHandler.create
 * GET    /project/{id}/diagrams/{did}/symbols         → DiagramSymbolsHandler.getSymbols
 * POST   /project/{id}/diagrams/{did}/symbols         → DiagramSymbolsHandler.addSymbol
 * PUT    /project/{id}/diagrams/{did}/symbols/{sid}   → DiagramSymbolsHandler.updateSymbol
 * POST   /project/{id}/diagrams/{did}/symbols/move    → DiagramSymbolsHandler.moveSymbols
 * POST   /project/{id}/diagrams/{did}/layout          → DiagramSymbolsHandler.applyLayout
 * POST   /project/{id}/diagrams/{did}/relations       → DiagramSymbolsHandler.addRelationToDiagram
 * POST   /project/{id}/diagrams/{did}/paths/display-all → DiagramSymbolsHandler.displayAllPaths
 * GET    /project/{id}/diagrams/{did}/connectors        → DiagramSymbolsHandler.getConnectors
 * POST   /project/{id}/relation                       → RelationsHandler.handleCreate
 * GET    /project/{id}/relations                      → RelationsHandler.handleList
 * DELETE /project/{id}/relation/{rid}                → RelationsHandler.handleDelete
 */
public class ProjectDispatcherHandler extends BaseHandler {

    private final ElementsHandler              elementsHandler    = new ElementsHandler();
    private final DiagramsHandler              diagramsHandler    = new DiagramsHandler();
    private final RelationsHandler             relationsHandler   = new RelationsHandler();
    private final StereotypePropertiesHandler  stereotypeHandler  = new StereotypePropertiesHandler();
    private final DiagramSymbolsHandler        symbolsHandler     = new DiagramSymbolsHandler();
    private final MatrixHandler                matrixHandler      = new MatrixHandler();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String[] parts = splitPath(exchange);
        if (parts.length < 3) {
            sendError(exchange, 400, "잘못된 URL. /project/{id}/elements 형태로 요청하세요."); return;
        }

        String projectId = parts[1];
        String resource  = parts[2];
        String subId     = parts.length >= 4 ? parts[3] : null;
        // 5-segment 경로: /project/{id}/{resource}/{subId}/{subResource}
        // 예) /project/active/elements/{eid}/props
        //     /project/active/diagrams/{did}/symbols
        //     /project/active/diagrams/{did}/layout
        //     /project/active/diagrams/{did}/symbols/{sid}
        String subResource  = parts.length >= 5 ? parts[4] : null;
        String subSubId     = parts.length >= 6 ? parts[5] : null;
        // 7-segment: /project/{id}/diagrams/{did}/symbols/move
        //            parts[4] = "symbols", parts[5] = "move"
        String subSubResource = parts.length >= 7 ? parts[6] : null;

        Project project = findProject(projectId);
        if (project == null) {
            sendError(exchange, 404, "프로젝트를 찾을 수 없습니다: " + projectId); return;
        }

        switch (resource) {
            case "elements": case "element":
                // /elements/{eid}/props  → StereotypePropertiesHandler
                if (subId != null && "props".equals(subResource)) {
                    stereotypeHandler.handleProps(exchange, project, subId);
                } else if (subId != null) {
                    elementsHandler.handleSingle(exchange, project, subId);
                } else {
                    elementsHandler.handleCollection(exchange, project);
                }
                break;
            case "diagrams": case "diagram":
                if (subId != null && "symbols".equals(subResource)) {
                    if ("move".equals(subSubId)) {
                        // /diagrams/{did}/symbols/move  → POST 배치 이동
                        symbolsHandler.handleSymbolsMove(exchange, project, subId);
                    } else if (subSubId != null) {
                        // /diagrams/{did}/symbols/{sid}  → PUT 심볼 위치 변경
                        symbolsHandler.handleSymbolSingle(exchange, project, subId, subSubId);
                    } else {
                        // /diagrams/{did}/symbols  → GET 목록 / POST 추가
                        symbolsHandler.handleSymbols(exchange, project, subId);
                    }
                } else if (subId != null && "layout".equals(subResource)) {
                    // /diagrams/{did}/layout  → POST 자동 레이아웃
                    symbolsHandler.handleLayout(exchange, project, subId);
                } else if (subId != null && "export".equals(subResource)) {
                    // /diagrams/{did}/export  → POST PNG 내보내기 (파일 경로 반환)
                    symbolsHandler.handleExport(exchange, project, subId);
                } else if (subId != null && "relations".equals(subResource)) {
                    // /diagrams/{did}/relations  → POST 관계 시각화
                    symbolsHandler.handleRelations(exchange, project, subId);
                } else if (subId != null && "paths".equals(subResource)
                        && "display-all".equals(subSubId)) {
                    // /diagrams/{did}/paths/display-all → POST 모든 path 자동 시각화
                    symbolsHandler.handleDisplayAllPaths(exchange, project, subId);
                } else if (subId != null && "connectors".equals(subResource)) {
                    if ("reroute".equals(subSubId)) {
                        // /diagrams/{did}/connectors/reroute → POST 커넥터 경유점 초기화 재라우팅
                        symbolsHandler.handleReroute(exchange, project, subId);
                    } else {
                        // /diagrams/{did}/connectors → GET 커넥터(연결선) 조회
                        symbolsHandler.handleConnectors(exchange, project, subId);
                    }
                } else if (subId != null && "matrix".equals(subResource)
                        && "configure".equals(subSubId)) {
                    // /diagrams/{did}/matrix/configure → POST 매트릭스 행/열/관계 설정
                    matrixHandler.handleConfigure(exchange, project, subId);
                } else if (subId != null) {
                    diagramsHandler.handleSingle(exchange, project, subId);
                } else {
                    diagramsHandler.handleCollection(exchange, project);
                }
                break;
            case "relation": case "relations":
                String relMethod = exchange.getRequestMethod();
                if ("GET".equals(relMethod)) {
                    relationsHandler.handleList(exchange, project);
                } else if ("DELETE".equals(relMethod) && subId != null) {
                    relationsHandler.handleDelete(exchange, project, subId);
                } else {
                    relationsHandler.handleCreate(exchange, project);
                }
                break;
            default:
                sendError(exchange, 404, "알 수 없는 리소스: " + resource);
        }
    }

    private Project findProject(String projectId) {
        for (Project p : Application.getInstance().getProjectsManager().getProjects()) {
            if (projectId.equals(p.getID())) return p;
        }
        if ("active".equals(projectId)) return Application.getInstance().getProject();
        return null;
    }
}
