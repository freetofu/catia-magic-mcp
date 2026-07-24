"""
bridge_client.py
CATIA Magic Bridge Plugin HTTP API 클라이언트

MagicDraw 내부에서 실행 중인 Bridge Plugin의
localhost:18080 HTTP 서버와 통신합니다.

두 가지 클라이언트를 제공합니다:
  - BridgeClient      : 동기(Sync) — 스크립트/테스트용
  - AsyncBridgeClient : 비동기(Async) — FastMCP server.py용

Bridge Plugin REST API 엔드포인트 (확인된 팩트):
  GET    /status
  GET    /projects
  GET    /project/{id}/elements
  GET    /project/{id}/elements?query=<str>
  GET    /project/{id}/elements/{eid}
  POST   /project/{id}/element
  PUT    /project/{id}/elements/{eid}
  DELETE /project/{id}/elements/{eid}
  GET    /project/{id}/elements/{eid}/props
  PUT    /project/{id}/elements/{eid}/props
  GET    /project/{id}/diagrams
  GET    /project/{id}/diagrams/{did}
  POST   /project/{id}/diagram
  GET    /project/{id}/diagrams/{did}/symbols
  POST   /project/{id}/diagrams/{did}/symbols
  PUT    /project/{id}/diagrams/{did}/symbols/{sid}
  POST   /project/{id}/diagrams/{did}/layout
  POST   /project/{id}/relation
  GET    /project/{id}/relations
  DELETE /project/{id}/relation/{rid}
"""

import httpx
from typing import Any, Optional

BRIDGE_BASE_URL = "http://localhost:18080"
TIMEOUT_SECONDS = 10.0


class BridgeConnectionError(Exception):
    """Bridge Plugin에 연결할 수 없을 때 발생"""
    pass


# ─────────────────────────────────────────────────────────────────────────────
# 동기(Sync) 클라이언트
# ─────────────────────────────────────────────────────────────────────────────

class BridgeClient:
    """동기 HTTP 클라이언트.

    스크립트, 단위 테스트, REPL 환경에서 사용합니다.
    FastMCP server.py에서는 AsyncBridgeClient를 사용하세요.
    """

    def __init__(self, base_url: str = BRIDGE_BASE_URL):
        self.base_url = base_url

    # ── 내부 HTTP 헬퍼 ─────────────────────────────────────────────────────

    def _get(self, path: str, params: Optional[dict] = None) -> dict:
        try:
            resp = httpx.get(
                f"{self.base_url}{path}", params=params, timeout=TIMEOUT_SECONDS
            )
            resp.raise_for_status()
            return resp.json()
        except httpx.ConnectError:
            raise BridgeConnectionError(
                "CATIA Magic Bridge Plugin에 연결할 수 없습니다. "
                "MagicDraw가 실행 중이고 MCP Bridge Plugin이 활성화되어 있는지 확인하세요."
            )
        except httpx.HTTPStatusError as e:
            try:
                raise RuntimeError(e.response.json().get("error", str(e)))
            except (KeyError, ValueError):
                raise RuntimeError(str(e))

    def _post(self, path: str, body: dict) -> dict:
        try:
            resp = httpx.post(
                f"{self.base_url}{path}", json=body, timeout=TIMEOUT_SECONDS
            )
            resp.raise_for_status()
            return resp.json()
        except httpx.ConnectError:
            raise BridgeConnectionError(
                "CATIA Magic Bridge Plugin에 연결할 수 없습니다."
            )
        except httpx.HTTPStatusError as e:
            try:
                raise RuntimeError(e.response.json().get("error", str(e)))
            except (KeyError, ValueError):
                raise RuntimeError(str(e))

    def _put(self, path: str, body: dict) -> dict:
        try:
            resp = httpx.put(
                f"{self.base_url}{path}", json=body, timeout=TIMEOUT_SECONDS
            )
            resp.raise_for_status()
            return resp.json()
        except httpx.ConnectError:
            raise BridgeConnectionError(
                "CATIA Magic Bridge Plugin에 연결할 수 없습니다."
            )
        except httpx.HTTPStatusError as e:
            try:
                raise RuntimeError(e.response.json().get("error", str(e)))
            except (KeyError, ValueError):
                raise RuntimeError(str(e))

    def _delete(self, path: str) -> dict:
        try:
            resp = httpx.delete(f"{self.base_url}{path}", timeout=TIMEOUT_SECONDS)
            resp.raise_for_status()
            return resp.json()
        except httpx.ConnectError:
            raise BridgeConnectionError(
                "CATIA Magic Bridge Plugin에 연결할 수 없습니다."
            )
        except httpx.HTTPStatusError as e:
            try:
                raise RuntimeError(e.response.json().get("error", str(e)))
            except (KeyError, ValueError):
                raise RuntimeError(str(e))

    # ── 상태 & 프로젝트 ──────────────────────────────────────────────────────

    def get_status(self) -> dict:
        """GET /status → {"status": "ok", "openProjects": int}"""
        return self._get("/status")

    def list_projects(self) -> dict:
        """GET /projects → {"projects": [{"id": str, "name": str, "isActive": bool}]}"""
        return self._get("/projects")

    # ── 요소(Element) ────────────────────────────────────────────────────────

    def list_elements(self, project_id: str) -> dict:
        """GET /project/{id}/elements → {"elements": [{"id", "name", "type"}]}"""
        return self._get(f"/project/{project_id}/elements")

    def search_elements(self, project_id: str, query: str) -> dict:
        """GET /project/{id}/elements?query=str → {"elements": [...], "query": str}"""
        return self._get(f"/project/{project_id}/elements", params={"query": query})

    def get_element(self, project_id: str, element_id: str) -> dict:
        """GET /project/{id}/elements/{eid} → {"id", "name", "type", "ownerId", "childCount"}"""
        return self._get(f"/project/{project_id}/elements/{element_id}")

    def create_element(
        self,
        project_id: str,
        element_type: str,
        name: str,
        owner_id: Optional[str] = None,
        stereotype: Optional[str] = None,
        type_id: Optional[str] = None,
        aggregation: Optional[str] = None,
    ) -> dict:
        """POST /project/{id}/element → 201: {"id", "name", "type", "stereotype"}"""
        body: dict[str, Any] = {"type": element_type, "name": name}
        if owner_id:
            body["ownerId"] = owner_id
        if stereotype:
            body["stereotype"] = stereotype
        if type_id:
            body["typeId"] = type_id
        if aggregation:
            body["aggregation"] = aggregation
        return self._post(f"/project/{project_id}/element", body)

    def update_element(
        self,
        project_id: str,
        element_id: str,
        name: Optional[str] = None,
        documentation: Optional[str] = None,
        stereotype: Optional[str] = None,
        type_id: Optional[str] = None,
        aggregation: Optional[str] = None,
    ) -> dict:
        """PUT /project/{id}/elements/{eid} → {"id", "name", "type", "ownerId", "childCount"}"""
        body: dict[str, Any] = {}
        if name is not None:
            body["name"] = name
        if documentation is not None:
            body["documentation"] = documentation
        if stereotype is not None:
            body["stereotype"] = stereotype
        if type_id is not None:
            body["typeId"] = type_id
        if aggregation is not None:
            body["aggregation"] = aggregation
        return self._put(f"/project/{project_id}/elements/{element_id}", body)

    def delete_element(self, project_id: str, element_id: str) -> dict:
        """DELETE /project/{id}/elements/{eid} → {"deleted": str}"""
        return self._delete(f"/project/{project_id}/elements/{element_id}")

    # ── 다이어그램(Diagram) ──────────────────────────────────────────────────

    def list_diagrams(self, project_id: str) -> dict:
        """GET /project/{id}/diagrams → {"diagrams": [{"id", "name", "type"}]}"""
        return self._get(f"/project/{project_id}/diagrams")

    def get_diagram(self, project_id: str, diagram_id: str) -> dict:
        """GET /project/{id}/diagrams/{did} → {"id", "name", "type"}"""
        return self._get(f"/project/{project_id}/diagrams/{diagram_id}")

    def create_diagram(
        self,
        project_id: str,
        diagram_type: str,
        name: str,
        owner_id: Optional[str] = None,
    ) -> dict:
        """POST /project/{id}/diagram → 201: {"id", "name", "type"}"""
        body: dict[str, Any] = {"type": diagram_type, "name": name}
        if owner_id:
            body["ownerId"] = owner_id
        return self._post(f"/project/{project_id}/diagram", body)

    # ── 스테레오타입 속성(Stereotype Properties) ─────────────────────────────

    def get_stereotype_properties(self, project_id: str, element_id: str) -> dict:
        """GET /project/{id}/elements/{eid}/props
        → {"elementId", "elementName", "stereotypes": [{"name", "properties": [{"name","value"}]}]}
        """
        return self._get(f"/project/{project_id}/elements/{element_id}/props")

    def set_stereotype_property(
        self,
        project_id: str,
        element_id: str,
        stereotype: str,
        property_name: str,
        value: str,
    ) -> dict:
        """PUT /project/{id}/elements/{eid}/props
        → {"elementId", "stereotype", "property", "value"}
        """
        body: dict[str, Any] = {
            "stereotype": stereotype,
            "property": property_name,
            "value": value,
        }
        return self._put(f"/project/{project_id}/elements/{element_id}/props", body)

    # ── 다이어그램 심볼(Diagram Symbols) ─────────────────────────────────────

    def get_diagram_symbols(self, project_id: str, diagram_id: str) -> dict:
        """GET /project/{id}/diagrams/{did}/symbols
        → {"diagramId", "symbols": [{"id","elementId","elementName","elementType",
           "kind","parentSymbolId","x","y","width","height"}]}
        중첩 파트·포트까지 재귀 포함. kind: "shape"|"port". parentSymbolId: 소속 심볼(없으면 null).
        """
        return self._get(f"/project/{project_id}/diagrams/{diagram_id}/symbols")

    def get_diagram_connectors(self, project_id: str, diagram_id: str) -> dict:
        """GET /project/{id}/diagrams/{did}/connectors
        → {"diagramId", "connectors": [{"id","relationId","relationName","relationType",
           "sourceSymbolId","targetSymbolId","waypoints":[{"x","y"}]}]}
        sourceSymbolId/targetSymbolId 는 get_diagram_symbols 의 심볼 id(포트일 수 있음)와 매칭.
        """
        return self._get(f"/project/{project_id}/diagrams/{diagram_id}/connectors")

    def add_element_to_diagram(
        self,
        project_id: str,
        diagram_id: str,
        element_id: str,
        x: Optional[int] = None,
        y: Optional[int] = None,
    ) -> dict:
        """POST /project/{id}/diagrams/{did}/symbols
        → 201: {"id","elementId","elementName","x","y","width","height"}
        """
        body: dict[str, Any] = {"elementId": element_id}
        if x is not None:
            body["x"] = x
        if y is not None:
            body["y"] = y
        return self._post(f"/project/{project_id}/diagrams/{diagram_id}/symbols", body)

    def set_symbol_bounds(
        self,
        project_id: str,
        diagram_id: str,
        symbol_id: str,
        x: Optional[int] = None,
        y: Optional[int] = None,
        width: Optional[int] = None,
        height: Optional[int] = None,
    ) -> dict:
        """PUT /project/{id}/diagrams/{did}/symbols/{sid}
        → {"symbolId","x","y","width","height"}
        """
        body: dict[str, Any] = {}
        if x is not None:
            body["x"] = x
        if y is not None:
            body["y"] = y
        if width is not None:
            body["width"] = width
        if height is not None:
            body["height"] = height
        return self._put(
            f"/project/{project_id}/diagrams/{diagram_id}/symbols/{symbol_id}", body
        )

    def layout_diagram(
        self,
        project_id: str,
        diagram_id: str,
        algorithm: Optional[str] = None,
    ) -> dict:
        """POST /project/{id}/diagrams/{did}/layout
        → {"diagramId","algorithm","status":"ok"}
        algorithm 선택값: hierarchic, organic, tree, circular, orthogonal, grid, class
        """
        body: dict[str, Any] = {}
        if algorithm:
            body["algorithm"] = algorithm
        return self._post(f"/project/{project_id}/diagrams/{diagram_id}/layout", body)

    # ── 관계(Relation) ───────────────────────────────────────────────────────

    def create_relation(
        self,
        project_id: str,
        relation_type: str,
        source_id: str,
        target_id: str,
        name: Optional[str] = None,
        owner_id: Optional[str] = None,
    ) -> dict:
        """POST /project/{id}/relation → 201: {"id", "name", "type", "sourceId", "targetId"}"""
        body: dict[str, Any] = {
            "type": relation_type,
            "sourceId": source_id,
            "targetId": target_id,
        }
        if name:
            body["name"] = name
        if owner_id:
            body["ownerId"] = owner_id
        return self._post(f"/project/{project_id}/relation", body)

    def list_relations(self, project_id: str) -> dict:
        """GET /project/{id}/relations → {"relations": [{"id", "name", "type"}]}"""
        return self._get(f"/project/{project_id}/relations")

    def delete_relation(self, project_id: str, relation_id: str) -> dict:
        """DELETE /project/{id}/relation/{rid} → {"deleted": str}"""
        return self._delete(f"/project/{project_id}/relation/{relation_id}")


# ─────────────────────────────────────────────────────────────────────────────
# 비동기(Async) 클라이언트
# ─────────────────────────────────────────────────────────────────────────────

class AsyncBridgeClient:
    """비동기 HTTP 클라이언트.

    FastMCP server.py의 lifespan 패턴과 함께 사용합니다.
    httpx.AsyncClient 인스턴스를 외부에서 주입받아 싱글턴으로 관리합니다.

    사용 예::

        async with httpx.AsyncClient(base_url=BRIDGE_BASE_URL, ...) as http:
            client = AsyncBridgeClient(http)
            result = await client.get_status()
    """

    def __init__(self, http_client: httpx.AsyncClient):
        self._client = http_client

    # ── 내부 HTTP 헬퍼 ─────────────────────────────────────────────────────

    async def _get(self, path: str, params: Optional[dict] = None) -> dict:
        try:
            resp = await self._client.get(path, params=params)
            resp.raise_for_status()
            return resp.json()
        except httpx.ConnectError:
            raise BridgeConnectionError(
                "CATIA Magic Bridge Plugin에 연결할 수 없습니다. "
                "MagicDraw가 실행 중이고 MCP Bridge Plugin이 활성화되어 있는지 확인하세요."
            )
        except httpx.TimeoutException:
            raise BridgeConnectionError(
                f"Bridge Plugin 응답 타임아웃 ({TIMEOUT_SECONDS}초). "
                "MagicDraw가 바쁜 상태일 수 있습니다."
            )
        except httpx.HTTPStatusError as e:
            try:
                raise RuntimeError(e.response.json().get("error", str(e)))
            except (KeyError, ValueError):
                raise RuntimeError(str(e))

    async def _post(self, path: str, body: dict) -> dict:
        try:
            resp = await self._client.post(path, json=body)
            resp.raise_for_status()
            return resp.json()
        except httpx.ConnectError:
            raise BridgeConnectionError(
                "CATIA Magic Bridge Plugin에 연결할 수 없습니다."
            )
        except httpx.TimeoutException:
            raise BridgeConnectionError(
                f"Bridge Plugin 응답 타임아웃 ({TIMEOUT_SECONDS}초)."
            )
        except httpx.HTTPStatusError as e:
            try:
                raise RuntimeError(e.response.json().get("error", str(e)))
            except (KeyError, ValueError):
                raise RuntimeError(str(e))

    async def _put(self, path: str, body: dict) -> dict:
        try:
            resp = await self._client.put(path, json=body)
            resp.raise_for_status()
            return resp.json()
        except httpx.ConnectError:
            raise BridgeConnectionError(
                "CATIA Magic Bridge Plugin에 연결할 수 없습니다."
            )
        except httpx.TimeoutException:
            raise BridgeConnectionError(
                f"Bridge Plugin 응답 타임아웃 ({TIMEOUT_SECONDS}초)."
            )
        except httpx.HTTPStatusError as e:
            try:
                raise RuntimeError(e.response.json().get("error", str(e)))
            except (KeyError, ValueError):
                raise RuntimeError(str(e))

    async def _delete(self, path: str) -> dict:
        try:
            resp = await self._client.delete(path)
            resp.raise_for_status()
            return resp.json()
        except httpx.ConnectError:
            raise BridgeConnectionError(
                "CATIA Magic Bridge Plugin에 연결할 수 없습니다."
            )
        except httpx.TimeoutException:
            raise BridgeConnectionError(
                f"Bridge Plugin 응답 타임아웃 ({TIMEOUT_SECONDS}초)."
            )
        except httpx.HTTPStatusError as e:
            try:
                raise RuntimeError(e.response.json().get("error", str(e)))
            except (KeyError, ValueError):
                raise RuntimeError(str(e))

    # ── 상태 & 프로젝트 ──────────────────────────────────────────────────────

    async def get_status(self) -> dict:
        """GET /status → {"status": "ok", "openProjects": int}"""
        return await self._get("/status")

    async def list_projects(self) -> dict:
        """GET /projects → {"projects": [{"id": str, "name": str, "isActive": bool}]}"""
        return await self._get("/projects")

    # ── 요소(Element) ────────────────────────────────────────────────────────

    async def list_elements(self, project_id: str) -> dict:
        """GET /project/{id}/elements → {"elements": [{"id", "name", "type"}]}"""
        return await self._get(f"/project/{project_id}/elements")

    async def search_elements(self, project_id: str, query: str) -> dict:
        """GET /project/{id}/elements?query=str → {"elements": [...], "query": str}"""
        return await self._get(
            f"/project/{project_id}/elements", params={"query": query}
        )

    async def get_element(self, project_id: str, element_id: str) -> dict:
        """GET /project/{id}/elements/{eid} → {"id", "name", "type", "ownerId", "childCount"}"""
        return await self._get(f"/project/{project_id}/elements/{element_id}")

    async def create_element(
        self,
        project_id: str,
        element_type: str,
        name: str,
        owner_id: Optional[str] = None,
        stereotype: Optional[str] = None,
        type_id: Optional[str] = None,
        aggregation: Optional[str] = None,
    ) -> dict:
        """POST /project/{id}/element → 201: {"id", "name", "type", "stereotype"}"""
        body: dict[str, Any] = {"type": element_type, "name": name}
        if owner_id:
            body["ownerId"] = owner_id
        if stereotype:
            body["stereotype"] = stereotype
        if type_id:
            body["typeId"] = type_id
        if aggregation:
            body["aggregation"] = aggregation
        return await self._post(f"/project/{project_id}/element", body)

    async def update_element(
        self,
        project_id: str,
        element_id: str,
        name: Optional[str] = None,
        documentation: Optional[str] = None,
        stereotype: Optional[str] = None,
        type_id: Optional[str] = None,
        aggregation: Optional[str] = None,
    ) -> dict:
        """PUT /project/{id}/elements/{eid} → {"id", "name", "type", "ownerId", "childCount"}"""
        body: dict[str, Any] = {}
        if name is not None:
            body["name"] = name
        if documentation is not None:
            body["documentation"] = documentation
        if stereotype is not None:
            body["stereotype"] = stereotype
        if type_id is not None:
            body["typeId"] = type_id
        if aggregation is not None:
            body["aggregation"] = aggregation
        return await self._put(f"/project/{project_id}/elements/{element_id}", body)

    async def delete_element(self, project_id: str, element_id: str) -> dict:
        """DELETE /project/{id}/elements/{eid} → {"deleted": str}"""
        return await self._delete(f"/project/{project_id}/elements/{element_id}")

    # ── 다이어그램(Diagram) ──────────────────────────────────────────────────

    async def list_diagrams(self, project_id: str) -> dict:
        """GET /project/{id}/diagrams → {"diagrams": [{"id", "name", "type"}]}"""
        return await self._get(f"/project/{project_id}/diagrams")

    async def get_diagram(self, project_id: str, diagram_id: str) -> dict:
        """GET /project/{id}/diagrams/{did} → {"id", "name", "type"}"""
        return await self._get(f"/project/{project_id}/diagrams/{diagram_id}")

    async def create_diagram(
        self,
        project_id: str,
        diagram_type: str,
        name: str,
        owner_id: Optional[str] = None,
    ) -> dict:
        """POST /project/{id}/diagram → 201: {"id", "name", "type"}"""
        body: dict[str, Any] = {"type": diagram_type, "name": name}
        if owner_id:
            body["ownerId"] = owner_id
        return await self._post(f"/project/{project_id}/diagram", body)

    # ── 스테레오타입 속성(Stereotype Properties) ─────────────────────────────

    async def get_stereotype_properties(self, project_id: str, element_id: str) -> dict:
        """GET /project/{id}/elements/{eid}/props"""
        return await self._get(f"/project/{project_id}/elements/{element_id}/props")

    async def set_stereotype_property(
        self,
        project_id: str,
        element_id: str,
        stereotype: str,
        property_name: str,
        value: str,
    ) -> dict:
        """PUT /project/{id}/elements/{eid}/props"""
        body: dict[str, Any] = {
            "stereotype": stereotype,
            "property": property_name,
            "value": value,
        }
        return await self._put(f"/project/{project_id}/elements/{element_id}/props", body)

    # ── 다이어그램 심볼(Diagram Symbols) ─────────────────────────────────────

    async def get_diagram_symbols(self, project_id: str, diagram_id: str) -> dict:
        """GET /project/{id}/diagrams/{did}/symbols
        → 중첩 파트·포트 포함. 각 심볼에 elementType/kind/parentSymbolId 추가.
        """
        return await self._get(f"/project/{project_id}/diagrams/{diagram_id}/symbols")

    async def get_diagram_connectors(self, project_id: str, diagram_id: str) -> dict:
        """GET /project/{id}/diagrams/{did}/connectors
        → 커넥터별 sourceSymbolId/targetSymbolId/waypoints. 레이아웃 엔진 그래프 입력.
        """
        return await self._get(f"/project/{project_id}/diagrams/{diagram_id}/connectors")

    async def add_element_to_diagram(
        self,
        project_id: str,
        diagram_id: str,
        element_id: str,
        x: Optional[int] = None,
        y: Optional[int] = None,
        auto_display_ports: bool = False,
    ) -> dict:
        """POST /project/{id}/diagrams/{did}/symbols"""
        body: dict[str, Any] = {"elementId": element_id, "autoDisplayPorts": auto_display_ports}
        if x is not None:
            body["x"] = x
        if y is not None:
            body["y"] = y
        return await self._post(
            f"/project/{project_id}/diagrams/{diagram_id}/symbols", body
        )

    async def set_symbol_bounds(
        self,
        project_id: str,
        diagram_id: str,
        symbol_id: str,
        x: Optional[int] = None,
        y: Optional[int] = None,
        width: Optional[int] = None,
        height: Optional[int] = None,
    ) -> dict:
        """PUT /project/{id}/diagrams/{did}/symbols/{sid}"""
        body: dict[str, Any] = {}
        if x is not None:
            body["x"] = x
        if y is not None:
            body["y"] = y
        if width is not None:
            body["width"] = width
        if height is not None:
            body["height"] = height
        return await self._put(
            f"/project/{project_id}/diagrams/{diagram_id}/symbols/{symbol_id}", body
        )

    async def layout_diagram(
        self,
        project_id: str,
        diagram_id: str,
        algorithm: Optional[str] = None,
    ) -> dict:
        """POST /project/{id}/diagrams/{did}/layout"""
        body: dict[str, Any] = {}
        if algorithm:
            body["algorithm"] = algorithm
        return await self._post(
            f"/project/{project_id}/diagrams/{diagram_id}/layout", body
        )

    async def export_diagram(self, project_id: str, diagram_id: str) -> dict:
        """POST /project/{id}/diagrams/{did}/export
        → {"diagramId", "format":"png", "path": str, "bytes": int}
        다이어그램을 PNG로 내보내고 저장된 파일 경로를 반환한다.
        """
        return await self._post(
            f"/project/{project_id}/diagrams/{diagram_id}/export", {}
        )

    async def reroute_connectors(self, project_id: str, diagram_id: str) -> dict:
        """POST /project/{id}/diagrams/{did}/connectors/reroute
        → {"diagramId", "total", "rerouted", "failed"}
        모든 커넥터의 경유점(waypoint)을 제거해 현재 심볼 위치 기준으로 자동 재라우팅한다.
        """
        return await self._post(
            f"/project/{project_id}/diagrams/{diagram_id}/connectors/reroute", {}
        )

    async def configure_matrix(
        self,
        project_id: str,
        diagram_id: str,
        row_scope_ids: list[str],
        column_scope_ids: list[str],
        dependency_criteria: Optional[list[str]] = None,
        direction: Optional[str] = None,
        include_subtypes: Optional[bool] = None,
        row_element_types: Optional[list[str]] = None,
        column_element_types: Optional[list[str]] = None,
    ) -> dict:
        """POST /project/{id}/diagrams/{did}/matrix/configure
        Dependency Matrix(또는 Satisfy/Verify/Derive/Refine Requirement Matrix)의
        행/열 범위와 셀 표시 관계를 설정한다.
        """
        body: dict[str, Any] = {
            "rowScopeIds": row_scope_ids,
            "columnScopeIds": column_scope_ids,
        }
        if dependency_criteria is not None:
            body["dependencyCriteria"] = dependency_criteria
        if direction is not None:
            body["direction"] = direction
        if include_subtypes is not None:
            body["includeSubtypes"] = include_subtypes
        if row_element_types is not None:
            body["rowElementTypes"] = row_element_types
        if column_element_types is not None:
            body["columnElementTypes"] = column_element_types
        return await self._post(
            f"/project/{project_id}/diagrams/{diagram_id}/matrix/configure", body
        )

    async def display_all_paths(
        self,
        project_id: str,
        diagram_id: str,
        display_containment: bool = False,
    ) -> dict:
        """POST /project/{id}/diagrams/{did}/paths/display-all
        → {"diagramId", "selectedSymbolCount", "count", "paths":[{id,relationId,relationName}]}
        다이어그램에 이미 배치된 모든 심볼을 선택한 상태로
        DisplayPathElements.displayPathElements()를 호출하여
        심볼 사이의 모든 모델 관계를 path로 자동 시각화한다.
        display_containment=True이면 containment(소유) 링크도 함께 표시한다(기본 False).
        """
        return await self._post(
            f"/project/{project_id}/diagrams/{diagram_id}/paths/display-all",
            {"displayContainment": display_containment},
        )

    # ── 관계(Relation) ───────────────────────────────────────────────────────

    async def create_relation(
        self,
        project_id: str,
        relation_type: str,
        source_id: str,
        target_id: str,
        name: Optional[str] = None,
        owner_id: Optional[str] = None,
        source_part_id: Optional[str] = None,
        target_part_id: Optional[str] = None,
    ) -> dict:
        """POST /project/{id}/relation → 201: {"id", "name", "type", "sourceId", "targetId"}"""
        body: dict[str, Any] = {
            "type": relation_type,
            "sourceId": source_id,
            "targetId": target_id,
        }
        if name:
            body["name"] = name
        if owner_id:
            body["ownerId"] = owner_id
        if source_part_id:
            body["sourcePartId"] = source_part_id
        if target_part_id:
            body["targetPartId"] = target_part_id
        return await self._post(f"/project/{project_id}/relation", body)

    async def list_relations(self, project_id: str) -> dict:
        """GET /project/{id}/relations → {"relations": [{"id", "name", "type"}]}"""
        return await self._get(f"/project/{project_id}/relations")

    async def delete_relation(self, project_id: str, relation_id: str) -> dict:
        """DELETE /project/{id}/relation/{rid} → {"deleted": str}"""
        return await self._delete(f"/project/{project_id}/relation/{relation_id}")


# ─────────────────────────────────────────────────────────────────────────────
# 싱글턴 인스턴스 (하위 호환성 유지)
# ─────────────────────────────────────────────────────────────────────────────

# 동기 싱글턴 — 스크립트/테스트 환경에서 직접 임포트해 사용
bridge = BridgeClient()
