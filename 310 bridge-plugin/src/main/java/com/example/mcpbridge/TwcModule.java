package com.example.mcpbridge;

/**
 * TWC(Teamwork Cloud) 연동 모듈 인터페이스
 *
 * 현재는 Stub(빈 구현)입니다.
 * 나중에 TWC 기능을 추가할 때 이 인터페이스를 구현하세요.
 */
public interface TwcModule {
    boolean isConnected();
    String getServerUrl();
}

class TwcModuleStub implements TwcModule {
    @Override public boolean isConnected() { return false; }
    @Override public String getServerUrl() { return null; }
}
