package com.example.mcpbridge;

import com.nomagic.magicdraw.plugins.Plugin;
import com.nomagic.magicdraw.core.Application;

/**
 * CATIA Magic (MagicDraw) MCP Bridge Plugin
 *
 * MagicDraw 내부에서 실행되며, localhost HTTP 서버를 열어
 * Python MCP 서버가 CATIA Magic을 조작할 수 있도록 합니다.
 *
 * 설치 경로: <MagicDraw 설치폴더>/plugins/com.example.mcpbridge/
 */
public class MCPBridgePlugin extends Plugin {

    private BridgeHttpServer httpServer;

    @Override
    public void init() {
        Application.getInstance().getGUILog().log("[MCP Bridge] 플러그인 초기화 중...");
        try {
            httpServer = new BridgeHttpServer(18080);
            httpServer.start();
            Application.getInstance().getGUILog().log(
                "[MCP Bridge] HTTP 서버 시작 완료 - http://localhost:18080"
            );
        } catch (Exception e) {
            Application.getInstance().getGUILog().log(
                "[MCP Bridge] HTTP 서버 시작 실패: " + e.getMessage()
            );
            e.printStackTrace();
        }
    }

    @Override
    public boolean close() {
        Application.getInstance().getGUILog().log("[MCP Bridge] 플러그인 종료 중...");
        if (httpServer != null) {
            httpServer.stop();
        }
        return true;
    }

    @Override
    public boolean isSupported() {
        return true;
    }
}
