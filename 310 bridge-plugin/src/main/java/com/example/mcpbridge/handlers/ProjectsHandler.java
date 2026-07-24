package com.example.mcpbridge.handlers;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Collection;
import java.util.StringJoiner;

/** GET /projects — 현재 열린 프로젝트 목록 반환 */
public class ProjectsHandler extends BaseHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed"); return;
        }
        try {
            Collection<Project> projects = Application.getInstance().getProjectsManager().getProjects();
            StringJoiner items = new StringJoiner(",", "[", "]");
            for (Project p : projects) {
                items.add(String.format(
                    "{\"id\": \"%s\", \"name\": \"%s\", \"isActive\": %b}",
                    escapeJson(p.getID()), escapeJson(p.getName()),
                    p == Application.getInstance().getProject()
                ));
            }
            sendJson(exchange, 200, "{\"projects\": " + items + "}");
        } catch (Exception e) {
            sendError(exchange, 500, "프로젝트 목록 조회 실패: " + e.getMessage());
        }
    }
}
