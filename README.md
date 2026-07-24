# CATIA Magic MCP

CATIA Magic(MagicDraw / Magic Systems of Systems Architect 2026x) 모델을
조회·편집하기 위한 MCP(Model Context Protocol) 서버와 브릿지 플러그인입니다.

Claude 같은 MCP 클라이언트가 이 서버를 통해 실행 중인 CATIA Magic의
프로젝트, 다이어그램, 요소, 관계, 매트릭스 등을 다룰 수 있습니다.

## 구성

```
310 bridge-plugin/   MagicDraw 안에서 도는 Java 플러그인 (localhost:18080 HTTP 브릿지)
320 mcp-server/      MCP 서버 (Python) — MCP 클라이언트와 브릿지 사이 중계
```

- **브릿지 플러그인**: MagicDraw Open API로 모델을 직접 읽고 씁니다. Maven으로 빌드해
  MagicDraw의 `plugins` 폴더에 배포합니다.
- **MCP 서버**: `server.py`가 MCP 도구들을 노출하고, `bridge_client.py`가
  브릿지 플러그인(HTTP)과 통신합니다.

## 동작 전제

- CATIA Magic(MagicDraw 2026x)이 실행 중일 것
- 브릿지 플러그인이 설치되어 `localhost:18080`에서 대기 중일 것

## MCP 서버 설치

```bash
cd "320 mcp-server"
pip install -r requirements.txt
```

MCP 클라이언트(예: Claude Desktop) 설정은
`320 mcp-server/claude_desktop_config_example.json`를 참고하세요.

## 브릿지 플러그인 빌드

```bash
cd "310 bridge-plugin"
mvn clean package
```

빌드 산출물(`plugin.xml`, `target/*.jar`)을 MagicDraw의
`plugins/com.example.mcpbridge` 폴더에 복사한 뒤 MagicDraw를 재시작합니다.
