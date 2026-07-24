package com.example.mcpbridge.handlers;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Collection;

/** GET /status — MagicDraw 연결 상태 및 기본 정보 반환 */
public class StatusHandler extends BaseHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed"); return;
        }
        try {
            Application app = Application.getInstance();
            Collection<Project> projects = app.getProjectsManager().getProjects();
            String json = String.format(
                "{\"status\": \"ok\", \"openProjects\": %d}",
                projects.size()
            );
            sendJson(exchange, 200, json);
        } catch (Exception e) {
            sendError(exchange, 500, "상태 조회 실패: " + e.getMessage());
        }
    }
}
