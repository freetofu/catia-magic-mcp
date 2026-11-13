package com.example.mcpbridge.handlers;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Classifier;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Profile;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.StringJoiner;

/**
 * 프로파일 조회 핸들러
 *
 * GET /project/{id}/profiles[?query=..]  → {"profiles": [{"id","name","stereotypeCount"}]}
 * GET /project/{id}/profiles/{pid}       → {"id","name","stereotypes":[{"id","name","isAbstract",
 *                                            "metaclasses":[..],"parents":[..],"tags":[..]}]}
 */
public class ProfilesHandler extends BaseHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        sendError(exchange, 400, "ProjectDispatcherHandler를 통해 호출하세요: /project/{id}/profiles");
    }

    public void handleProfiles(HttpExchange exchange, Project project, String profileId) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { sendError(exchange, 405, "GET만 지원합니다."); return; }
        try {
            if (profileId == null) listProfiles(exchange, project);
            else getProfile(exchange, project, profileId);
        } catch (Exception e) {
            sendError(exchange, 500, "프로파일 조회 실패: " + e.getMessage());
        }
    }

    private void listProfiles(HttpExchange exchange, Project project) throws IOException {
        String query = null;
        String q = exchange.getRequestURI().getRawQuery();
        if (q != null && q.startsWith("query=")) query = java.net.URLDecoder.decode(q.substring(6), "UTF-8").toLowerCase();

        StringJoiner arr = new StringJoiner(",", "[", "]");
        for (Profile p : StereotypesHelper.getAllProfiles(project)) {
            String name = p.getName() != null ? p.getName() : "";
            if (query != null && !name.toLowerCase().contains(query)) continue;
            arr.add(String.format("{\"id\":\"%s\",\"name\":\"%s\",\"stereotypeCount\":%d}",
                escapeJson(p.getLocalID()), escapeJson(name), StereotypesHelper.getStereotypesByProfile(p).size()));
        }
        sendJson(exchange, 200, "{\"profiles\":" + arr + "}");
    }

    private void getProfile(HttpExchange exchange, Project project, String profileId) throws IOException {
        Element el = resolveElement(project, profileId);
        if (!(el instanceof Profile)) { sendError(exchange, 404, "Profile을 찾을 수 없습니다: " + profileId); return; }
        Profile p = (Profile) el;

        StringJoiner sts = new StringJoiner(",", "[", "]");
        for (Stereotype st : StereotypesHelper.getStereotypesByProfile(p)) {
            StringJoiner metas = new StringJoiner(",", "[", "]");
            for (com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class mc : StereotypesHelper.getBaseClasses(st, false))
                metas.add("\"" + escapeJson(mc.getName()) + "\"");

            StringJoiner parents = new StringJoiner(",", "[", "]");
            for (Classifier g : st.getGeneral())
                parents.add("\"" + escapeJson(g.getName()) + "\"");

            StringJoiner tags = new StringJoiner(",", "[", "]");
            for (Property prop : st.getOwnedAttribute()) {
                String n = prop.getName() != null ? prop.getName() : "";
                if (n.startsWith("base_")) continue;  // 메타클래스 연결 끝이지 태그가 아님
                tags.add(String.format("{\"id\":\"%s\",\"name\":\"%s\",\"type\":\"%s\",\"lower\":%d,\"upper\":%d}",
                    escapeJson(prop.getLocalID()), escapeJson(n),
                    escapeJson(prop.getType() != null ? prop.getType().getName() : ""),
                    prop.getLower(), prop.getUpper()));
            }

            sts.add(String.format("{\"id\":\"%s\",\"name\":\"%s\",\"isAbstract\":%s,\"metaclasses\":%s,\"parents\":%s,\"tags\":%s}",
                escapeJson(st.getLocalID()), escapeJson(st.getName()), st.isAbstract(), metas, parents, tags));
        }
        sendJson(exchange, 200, String.format("{\"id\":\"%s\",\"name\":\"%s\",\"stereotypes\":%s}",
            escapeJson(p.getLocalID()), escapeJson(p.getName()), sts));
    }
}
