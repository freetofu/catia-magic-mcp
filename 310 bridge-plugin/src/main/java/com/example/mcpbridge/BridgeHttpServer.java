package com.example.mcpbridge;

import com.example.mcpbridge.handlers.*;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MCP Bridge HTTP 서버
 *
 * JDK 내장 com.sun.net.httpserver 사용 (외부 라이브러리 불필요).
 * 각 URL 경로를 담당 핸들러에 라우팅합니다.
 */
public class BridgeHttpServer {

    private final int port;
    private HttpServer server;
    private ExecutorService executor;

    public BridgeHttpServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", port), 0);

        server.createContext("/status",   new StatusHandler());
        server.createContext("/projects", new ProjectsHandler());
        server.createContext("/project/", new ProjectDispatcherHandler());

        // 모델 변경은 MagicDraw 세션(한 번에 하나만 열리는 편집 단위) 위에서 일어난다.
        // 워커를 여러 개 두면 서로의 세션을 밟아 한쪽의 미완성 변경이 커밋될 수 있다.
        // 요청을 한 줄로 세워 그 경합을 없앤다. 어차피 실제 작업은 EDT에서 직렬화된다.
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mcp-bridge-http");
            t.setDaemon(true);   // 플러그인 언로드 후 MagicDraw 종료를 막지 않도록
            return t;
        });
        server.setExecutor(executor);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
        }
        // HttpServer.stop()은 직접 지정한 executor를 종료하지 않는다.
        // 안 내리면 플러그인을 다시 로드할 때마다 스레드가 쌓인다.
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
