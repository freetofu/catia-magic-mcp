package com.example.mcpbridge.handlers;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Comment;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.magicdraw.uml.BaseElement;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;

public abstract class BaseHandler implements HttpHandler {

    protected void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    protected void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        sendJson(exchange, statusCode, "{\"error\": \"" + escapeJson(message) + "\"}");
    }

    protected String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    protected String[] splitPath(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        return path.replaceAll("^/+|/+$", "").split("/");
    }

    protected String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    // 나머지 제어문자(U+0000~U+001F)를 그대로 흘리면 깨진 JSON이 나가
                    // 클라이언트 파서가 죽는다. 이 코드베이스는 문자열 연결로 JSON을
                    // 만들기 때문에 여기가 단일 실패점이다.
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    // ── 공통 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * MagicDraw 태그값 조회 API의 반환값을 문자열로 만든다. 여러 값이면 ", "로 잇는다
     * (set_stereotype_property 의 쉼표 구분 입력과 같은 형식).
     *
     * getStereotypePropertyValueAsString / getStereotypePropertyValue 는
     * 값이 하나여도 List 를 돌려준다. 그대로 toString() 하면 "[REQ-001]" 처럼
     * 대괄호가 붙고, 비었을 때 "" 대신 "[]" 가 나간다.
     */
    protected String valuesOf(Object raw) {
        if (raw == null) return "";
        if (raw instanceof java.util.Collection) {
            StringJoiner sj = new StringJoiner(", ");
            for (Object v : (java.util.Collection<?>) raw) {
                if (v != null) sj.add(v.toString());
            }
            return sj.toString();
        }
        return raw.toString();
    }

    /** BaseElement → Element 캐스팅 헬퍼 (2026x: getElementByID returns BaseElement) */
    protected Element resolveElement(Project project, String id) {
        BaseElement base = project.getElementByID(id);
        return (base instanceof Element) ? (Element) base : null;
    }

    /**
     * Containment Tree(좌측 모델 탐색기)에서 해당 요소까지 경로를 펼치고 선택·스크롤한다.
     * 새 요소/다이어그램 생성 직후 호출하면 트리에서 바로 보인다.
     * UI 작업이므로 EDT(Swing 이벤트 스레드)에서 비동기 실행하여 HTTP 응답을 막지 않는다.
     * 모든 예외는 무시한다(시각화 실패가 생성 자체를 실패시키면 안 됨).
     *
     * Tree.openNode(BaseElement, select, appendSelection, requestFocus, scrollToVisible)
     */
    protected void revealInContainmentTree(Project project, Element el) {
        if (project == null || el == null) return;
        try {
            Object browser = project.getClass().getMethod("getBrowser").invoke(project);
            if (browser == null) return;
            Object tree = browser.getClass().getMethod("getContainmentTree").invoke(browser);
            if (tree == null) return;
            Class<?> baseElClass = Class.forName("com.nomagic.magicdraw.uml.BaseElement");
            final Method openNode = tree.getClass().getMethod("openNode",
                baseElClass, boolean.class, boolean.class, boolean.class, boolean.class);
            final Object fTree = tree;
            javax.swing.SwingUtilities.invokeLater(() -> {
                // select=true, appendSelection=false, requestFocus=false, scrollToVisible=true
                try { openNode.invoke(fTree, el, true, false, false, true); } catch (Exception ignore) {}
            });
        } catch (Exception ignore) {}
    }

    /**
     * 스테레오타입 적용 공통 구현.
     * StereotypesHelper API를 4단계 순서로 시도한다.
     *   1) addStereotypeByString(Element, String)
     *   2) addStereotypeByString(Element, String, profileName) — SysML/UML Standard Profile
     *   3) getStereotype(Project, String) + addStereotype(Element, Stereotype)
     *   4) getStereotype(Project, String, profileName) + addStereotype
     */
    protected void applyStereotype(Project project, Element el, String stereotypeName) {
        try {
            Class<?> shClass = Class.forName("com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper");
            Class<?> stClass = Class.forName("com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype");
            StringBuilder log = new StringBuilder();
            String[] profiles = {"SysML", "UML Standard Profile", "StandardProfile"};

            // 1) addStereotypeByString(Element, String)
            try {
                Method m = shClass.getMethod("addStereotypeByString", Element.class, String.class);
                m.invoke(null, el, stereotypeName);
                System.err.println("[Stereotype] OK via addStereotypeByString(E, \"" + stereotypeName + "\")");
                return;
            } catch (NoSuchMethodException e) {
                log.append("addStereotypeByString(E,S) not found; ");
            } catch (java.lang.reflect.InvocationTargetException e) {
                log.append("addStereotypeByString(E,S) threw: ").append(e.getCause()).append("; ");
            }

            // 2) addStereotypeByString(Element, String, profileName)
            for (String profile : profiles) {
                try {
                    Method m = shClass.getMethod("addStereotypeByString", Element.class, String.class, String.class);
                    m.invoke(null, el, stereotypeName, profile);
                    System.err.println("[Stereotype] OK via addStereotypeByString(E, \"" + stereotypeName + "\", \"" + profile + "\")");
                    return;
                } catch (NoSuchMethodException e) { log.append("addStereotypeByString(E,S,S) not found; "); break; }
                  catch (java.lang.reflect.InvocationTargetException e) {
                    log.append("addStereotypeByString(\"").append(profile).append("\") threw: ").append(e.getCause()).append("; ");
                }
            }

            // 3) getStereotype(Project, String) + addStereotype(Element, Stereotype)
            try {
                Method getS = shClass.getMethod("getStereotype", Project.class, String.class);
                Object st = getS.invoke(null, project, stereotypeName);
                if (st != null) {
                    shClass.getMethod("addStereotype", Element.class, stClass).invoke(null, el, st);
                    System.err.println("[Stereotype] OK via getStereotype+addStereotype for \"" + stereotypeName + "\"");
                    return;
                }
                log.append("getStereotype returned null; ");
            } catch (NoSuchMethodException e) {
                log.append("addStereotype(E,St) not found; ");
            } catch (java.lang.reflect.InvocationTargetException e) {
                log.append("addStereotype threw: ").append(e.getCause()).append("; ");
            }

            // 4) getStereotype(Project, String, profileName) + addStereotype
            for (String profile : profiles) {
                try {
                    Method getS = shClass.getMethod("getStereotype", Project.class, String.class, String.class);
                    Object st = getS.invoke(null, project, stereotypeName, profile);
                    if (st != null) {
                        shClass.getMethod("addStereotype", Element.class, stClass).invoke(null, el, st);
                        System.err.println("[Stereotype] OK via getStereotype(P,S,\"" + profile + "\")+addStereotype");
                        return;
                    }
                } catch (NoSuchMethodException e) { break; }
                  catch (java.lang.reflect.InvocationTargetException ignore) {}
            }

            System.err.println("[Stereotype] FAILED for \"" + stereotypeName + "\": " + log);
        } catch (ClassNotFoundException e) {
            System.err.println("[Stereotype] StereotypesHelper class not found: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[Stereotype] Unexpected error: " + e.getMessage());
        }
    }

    /**
     * 관계 요소에서 source/target 요소 ID를 추출.
     * - Dependency 계열 (Dependency, Usage, Realization, Abstraction, Satisfy, Verify …):
     *     client[0] → sourceId,  supplier[0] → targetId
     * - Generalization:
     *     specific → sourceId,  general → targetId
     * - Connector:
     *     end[0].role → sourceId,  end[1].role → targetId
     * 해당 메서드가 없거나 값이 없으면 빈 문자열.
     * @return String[2] — {sourceId, targetId}
     */
    protected String[] getRelationEndpointIds(Element el) {
        String sourceId = "", targetId = "";

        // Dependency 계열: getClient() / getSupplier()
        try {
            Object clients = el.getClass().getMethod("getClient").invoke(el);
            if (clients instanceof java.util.Collection) {
                for (Object c : (java.util.Collection<?>) clients) {
                    if (c instanceof Element) { sourceId = ((Element) c).getLocalID(); break; }
                }
            }
        } catch (Exception ignore) {}
        try {
            Object suppliers = el.getClass().getMethod("getSupplier").invoke(el);
            if (suppliers instanceof java.util.Collection) {
                for (Object s : (java.util.Collection<?>) suppliers) {
                    if (s instanceof Element) { targetId = ((Element) s).getLocalID(); break; }
                }
            }
        } catch (Exception ignore) {}

        // Generalization: getSpecific() / getGeneral()
        if (sourceId.isEmpty()) {
            try {
                Object specific = el.getClass().getMethod("getSpecific").invoke(el);
                if (specific instanceof Element) sourceId = ((Element) specific).getLocalID();
            } catch (Exception ignore) {}
        }
        if (targetId.isEmpty()) {
            try {
                Object general = el.getClass().getMethod("getGeneral").invoke(el);
                if (general instanceof Element) targetId = ((Element) general).getLocalID();
            } catch (Exception ignore) {}
        }

        // Connector: getEnd() → List<ConnectorEnd>, each has getRole()
        if (sourceId.isEmpty()) {
            try {
                Object ends = el.getClass().getMethod("getEnd").invoke(el);
                if (ends instanceof java.util.List) {
                    java.util.List<?> endList = (java.util.List<?>) ends;
                    if (endList.size() >= 1) {
                        Object role = endList.get(0).getClass().getMethod("getRole").invoke(endList.get(0));
                        if (role instanceof Element) sourceId = ((Element) role).getLocalID();
                    }
                    if (endList.size() >= 2) {
                        Object role = endList.get(1).getClass().getMethod("getRole").invoke(endList.get(1));
                        if (role instanceof Element) targetId = ((Element) role).getLocalID();
                    }
                }
            } catch (Exception ignore) {}
        }

        return new String[]{sourceId, targetId};
    }

    /**
     * Element의 documentation 반환 (ownedComment.body).
     * Comment가 없으면 빈 문자열.
     */
    protected String readDocumentation(Element el) {
        java.util.Collection<Comment> comments = el.getOwnedComment();
        if (comments != null && !comments.isEmpty()) {
            String body = comments.iterator().next().getBody();
            return body != null ? body : "";
        }
        return "";
    }

    /**
     * 요소에 적용된 모든 스테레오타입과 그 속성값(tagged value)을 JSON 배열로 반환.
     * 반환 형식: [{"name":"StName","properties":[{"name":"propName","value":"val"}, ...]}, ...]
     * 실패 시 빈 배열 "[]" 반환.
     */
    protected String readStereotypePropertiesJson(Project project, Element el) {
        try {
            Class<?> shClass = Class.forName("com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper");
            Method getStereotypes = shClass.getMethod("getStereotypes", Element.class);
            List<?> stereotypes = (List<?>) getStereotypes.invoke(null, el);

            StringJoiner stereoArray = new StringJoiner(",", "[", "]");
            for (Object st : stereotypes) {
                if (st == null) continue;
                String stName = "";
                try {
                    Object nameObj = st.getClass().getMethod("getName").invoke(st);
                    if (nameObj != null) stName = nameObj.toString();
                } catch (Exception e) {
                    stName = st.toString();
                }

                StringJoiner propsArray = new StringJoiner(",", "[", "]");
                try {
                    // 스테레오타입의 속성(태그) 목록.
                    // 2026x에서 getPropertiesFromStereotype 가 제거됨.
                    // 1순위: TagsHelper.getPropertiesWithDerived(Stereotype) — 상위 스테레오타입
                    //        (예: SysML «Requirement» ← «AbstractRequirement» 의 Id/Text)에서
                    //        상속·파생된 속성까지 포함해 반환한다.
                    // 2순위(폴백): 일반화(getGeneral) 계층을 직접 따라가며 owned attribute 수집.
                    Class<?> stClass = Class.forName("com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype");
                    Class<?> thClass = Class.forName("com.nomagic.uml2.ext.jmi.helpers.TagsHelper");
                    java.util.List<Object> ownedAttrs = new java.util.ArrayList<>();
                    try {
                        Object pl = thClass.getMethod("getPropertiesWithDerived", stClass).invoke(null, st);
                        if (pl instanceof java.util.Collection)
                            for (Object a : (java.util.Collection<?>) pl) if (a != null) ownedAttrs.add(a);
                    } catch (Exception ignore) {}
                    if (ownedAttrs.isEmpty()) {
                        collectStereotypeAttributes(st, ownedAttrs,
                            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
                    }
                    {
                        for (Object prop : ownedAttrs) {
                            if (prop == null) continue;
                            String propName = "";
                            try {
                                Object pNameObj = prop.getClass().getMethod("getName").invoke(prop);
                                if (pNameObj != null) propName = pNameObj.toString();
                            } catch (Exception ignore) {}
                            // 확장 끝(base_Xxx)은 사용자 tagged value가 아니므로 제외
                            if (propName.isEmpty() || propName.startsWith("base_")) continue;

                            String propValue = "";
                            try {
                                // 현행 시그니처: getStereotypePropertyValueAsString(Element, Stereotype, String)
                                //   → 반환형은 List<String> 이다(javap로 2026.1.0 확인).
                                //   toString() 하면 "[REQ-001]" 처럼 대괄호가 붙고, 값이 없으면 "[]" 가 된다.
                                Method getVal = thClass.getMethod("getStereotypePropertyValueAsString",
                                    Element.class, stClass, String.class);
                                Object valObj = getVal.invoke(null, el, st, propName);
                                propValue = valuesOf(valObj);
                            } catch (Exception e) {
                                // fallback: getStereotypePropertyValue(Element, Stereotype, String) -> Object/List
                                try {
                                    Method getValRaw = thClass.getMethod("getStereotypePropertyValue",
                                        Element.class, stClass, String.class);
                                    Object raw = getValRaw.invoke(null, el, st, propName);
                                    propValue = valuesOf(raw);
                                } catch (Exception ignore) {}
                            }

                            propsArray.add(String.format("{\"name\":\"%s\",\"value\":\"%s\"}",
                                escapeJson(propName), escapeJson(propValue)));
                        }
                    }
                } catch (Exception ignore) {}

                stereoArray.add(String.format("{\"name\":\"%s\",\"properties\":%s}",
                    escapeJson(stName), propsArray));
            }
            return stereoArray.toString();
        } catch (Exception e) {
            System.err.println("[readStereotypeProps] failed: " + e.getMessage());
            return "[]";
        }
    }

    /**
     * 스테레오타입과 그 상위 스테레오타입(getGeneral)들이 정의한 모든 owned attribute를 수집한다.
     * SysML «Requirement»의 Id/Text 등은 상위 «AbstractRequirement»에서 상속되므로,
     * 본인의 owned attribute만 보면 누락된다 → 일반화 계층을 재귀적으로 따라 올라간다.
     * visited 는 순환(상호 일반화) 방지용 식별자 집합.
     */
    private void collectStereotypeAttributes(Object classifier, List<Object> out, java.util.Set<Object> visited) {
        if (classifier == null || !visited.add(classifier)) return;
        try {
            Object owned = classifier.getClass().getMethod("getOwnedAttribute").invoke(classifier);
            if (owned instanceof java.util.Collection) {
                for (Object a : (java.util.Collection<?>) owned) if (a != null) out.add(a);
            }
        } catch (Exception ignore) {}
        try {
            Object generals = classifier.getClass().getMethod("getGeneral").invoke(classifier);
            if (generals instanceof java.util.Collection) {
                for (Object g : (java.util.Collection<?>) generals)
                    collectStereotypeAttributes(g, out, visited);
            }
        } catch (Exception ignore) {}
    }
}
