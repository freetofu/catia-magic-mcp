package com.example.mcpbridge;

import com.example.mcpbridge.handlers.*;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
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

    public BridgeHttpServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", port), 0);

        server.createContext("/status",   new StatusHandler());
        server.createContext("/projects", new ProjectsHandler());
        server.createContext("/project/", new ProjectDispatcherHandler());

        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
        }
    }
}
