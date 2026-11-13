"""
bridge_client.py
CATIA Magic Bridge Plugin HTTP API 클라이언트

MagicDraw 내부에서 실행 중인 Bridge Plugin의
localhost:18080 HTTP 서버와 통신합니다.

AsyncBridgeClient 하나만 제공합니다(FastMCP server.py 전용).
동기 사본은 아무도 쓰지 않은 채 async와 따로 낡아가고 있어 2026-07-27에 제거했습니다.

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
  POST   /project/{id}/diagrams/{did}/symbols/move
  POST   /project/{id}/diagrams/{did}/layout
  POST   /project/{id}/diagrams/{did}/export
  GET    /project/{id}/diagrams/{did}/connectors
  POST   /project/{id}/diagrams/{did}/connectors/reroute
  POST   /project/{id}/diagrams/{did}/relations
  POST   /project/{id}/diagrams/{did}/paths/display-all
  POST   /project/{id}/diagrams/{did}/matrix/configure
  POST   /project/{id}/relation
  GET    /project/{id}/relations
  DELETE /project/{id}/relation/{rid}
"""

import httpx
from typing import Any, Optional

BRIDGE_BASE_URL = "http://localhost:18080"
TIMEOUT_SECONDS = 10.0
# 다이어그램 렌더링(PNG), 자동 레이아웃, 매트릭스 재계산, path 전체 표시는
# 큰 모델에서 10초를 쉽게 넘긴다. 이런 호출에만 따로 적용한다.
SLOW_TIMEOUT_SECONDS = 180.0


class BridgeConnectionError(Exception):
    """Bridge Plugin에 연결할 수 없을 때 발생"""
    pass


class BridgeTimeoutError(BridgeConnectionError):
    """제한시간 안에 응답이 없을 때 발생.

    연결 자체는 성공했으므로 MagicDraw는 실행 중이다.
    '연결 실패'와 구분해야 사용자가 엉뚱한 곳(플러그인 설치 상태)을 뒤지지 않는다.
    """
    pass


class BridgeHttpError(RuntimeError):
    """Bridge Plugin이 4xx/5xx를 반환. status로 원인을 구분할 수 있다."""

    def __init__(self, status: int, detail: str):
        self.status = status
        self.detail = detail
        super().__init__(f"HTTP {status}: {detail}")


def _error_detail(resp: httpx.Response) -> str:
    """에러 응답 본문에서 읽을 만한 메시지를 뽑는다.

    본문이 JSON 객체가 아니어도(문자열·배열·HTML·빈 본문) 절대 죽지 않아야 한다.
    여기서 예외가 나면 진짜 원인이 그 예외에 가려진다.
    """
    try:
        payload = resp.json()
    except ValueError:
        payload = None
    if isinstance(payload, dict):
        detail = payload.get("error") or payload.get("message")
        if detail:
            return str(detail)
    text = (resp.text or "").strip()
    if text:
        return text[:500]
    return resp.reason_phrase or "(응답 본문 없음)"


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

    async def _request(
        self,
        method: str,
        path: str,
        *,
        params: Optional[dict] = None,
        body: Optional[dict] = None,
        timeout: Optional[float] = None,
    ) -> dict:
        """모든 HTTP 호출이 지나가는 단일 통로. 에러 변환도 여기서만 한다.

        timeout을 주면 이 호출에만 적용된다(렌더링·레이아웃처럼 오래 걸리는 작업용).
        생략하면 AsyncClient에 설정된 기본값을 쓴다.
        """
        kwargs: dict[str, Any] = {}
        if params is not None:
            kwargs["params"] = params
        if body is not None:
            kwargs["json"] = body
        if timeout is not None:
            kwargs["timeout"] = timeout
        try:
            resp = await self._client.request(method, path, **kwargs)
            resp.raise_for_status()
            return resp.json()
        except httpx.ConnectError:
            raise BridgeConnectionError(
                "CATIA Magic Bridge Plugin에 연결할 수 없습니다. "
                "MagicDraw가 실행 중이고 MCP Bridge Plugin이 활성화되어 있는지 확인하세요."
            )
        except httpx.TimeoutException:
            limit = timeout if timeout is not None else TIMEOUT_SECONDS
            raise BridgeTimeoutError(
                f"Bridge Plugin이 {limit:g}초 안에 응답하지 않았습니다 ({method} {path}). "
                "연결은 됐으므로 MagicDraw는 실행 중이며, 작업이 오래 걸리는 상태입니다."
            )
        except httpx.HTTPStatusError as e:
            raise BridgeHttpError(e.response.status_code, _error_detail(e.response))

    async def _get(
        self, path: str, params: Optional[dict] = None, *, timeout: Optional[float] = None
    ) -> dict:
        return await self._request("GET", path, params=params, timeout=timeout)

    async def _post(
        self, path: str, body: dict, *, timeout: Optional[float] = None
    ) -> dict:
        return await self._request("POST", path, body=body, timeout=timeout)

    async def _put(
        self, path: str, body: dict, *, timeout: Optional[float] = None
    ) -> dict:
        return await self._request("PUT", path, body=body, timeout=timeout)

    async def _delete(self, path: str, *, timeout: Optional[float] = None) -> dict:
        return await self._request("DELETE", path, timeout=timeout)

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
        metaclasses: Optional[list[str]] = None,
    ) -> dict:
        """POST /project/{id}/element → 201: {"id", "name", "type", "stereotype"}"""
        # update_element과 같은 규칙(is not None)을 쓴다. 빈 문자열을 조용히 버리면
        # create와 update가 같은 인자에 다르게 반응해 원인을 찾기 어려워진다.
        body: dict[str, Any] = {"type": element_type, "name": name}
        if owner_id is not None:
            body["ownerId"] = owner_id
        if stereotype is not None:
            body["stereotype"] = stereotype
        if type_id is not None:
            body["typeId"] = type_id
        if aggregation is not None:
            body["aggregation"] = aggregation
        if metaclasses is not None:
            body["metaclasses"] = metaclasses
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
        metaclasses: Optional[list[str]] = None,
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
        if metaclasses is not None:
            body["metaclasses"] = metaclasses
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

    # ── 프로파일(Profile) ────────────────────────────────────────────────────

    async def list_profiles(self, project_id: str, query: Optional[str] = None) -> dict:
        """GET /project/{id}/profiles → {"profiles": [{"id", "name", "stereotypeCount"}]}"""
        params = {"query": query} if query else None
        return await self._get(f"/project/{project_id}/profiles", params=params)

    async def get_profile(self, project_id: str, profile_id: str) -> dict:
        """GET /project/{id}/profiles/{pid} → {"id", "name", "stereotypes": [...]}"""
        return await self._get(f"/project/{project_id}/profiles/{profile_id}")

    # ── 다이어그램 심볼(Diagram Symbols) ─────────────────────────────────────

    async def get_diagram_symbols(self, project_id: str, diagram_id: str) -> dict:
        """GET /project/{id}/diagrams/{did}/symbols
        → 중첩 파트·포트 포함. 각 심볼에 elementType/kind/parentSymbolId 추가.
        """
        return await self._get(
            f"/project/{project_id}/diagrams/{diagram_id}/symbols",
            timeout=SLOW_TIMEOUT_SECONDS,
        )

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

    async def move_symbols(
        self,
        project_id: str,
        diagram_id: str,
        symbol_ids: list[str],
        dx: int,
        dy: int,
        push_adjacent: bool = False,
    ) -> dict:
        """POST /project/{id}/diagrams/{did}/symbols/move
        → {"moved":[...], "skipped":[...], "resized":[...]}
        dx/dy는 상대 이동량이며 0도 유효한 값이므로 항상 본문에 넣는다.
        """
        body: dict[str, Any] = {
            "symbolIds": symbol_ids,
            "dx": dx,
            "dy": dy,
            "pushAdjacent": push_adjacent,
        }
        return await self._post(
            f"/project/{project_id}/diagrams/{diagram_id}/symbols/move", body
        )

    async def add_relation_to_diagram(
        self,
        project_id: str,
        diagram_id: str,
        relation_id: str,
        client_symbol_id: Optional[str] = None,
        supplier_symbol_id: Optional[str] = None,
        allow_duplicate: bool = False,
    ) -> dict:
        """POST /project/{id}/diagrams/{did}/relations
        → {"id", "relationId", "clientSymbolId", "supplierSymbolId"}
        이미 배치된 두 심볼 사이에 기존 모델 관계를 path로 표시한다.
        """
        body: dict[str, Any] = {
            "relationId": relation_id,
            "allowDuplicate": allow_duplicate,
        }
        if client_symbol_id is not None:
            body["clientSymbolId"] = client_symbol_id
        if supplier_symbol_id is not None:
            body["supplierSymbolId"] = supplier_symbol_id
        return await self._post(
            f"/project/{project_id}/diagrams/{diagram_id}/relations", body
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
            f"/project/{project_id}/diagrams/{diagram_id}/layout",
            body,
            timeout=SLOW_TIMEOUT_SECONDS,
        )

    async def export_diagram(self, project_id: str, diagram_id: str) -> dict:
        """POST /project/{id}/diagrams/{did}/export
        → {"diagramId", "format":"png", "path": str, "bytes": int}
        다이어그램을 PNG로 내보내고 저장된 파일 경로를 반환한다.
        """
        return await self._post(
            f"/project/{project_id}/diagrams/{diagram_id}/export",
            {},
            timeout=SLOW_TIMEOUT_SECONDS,
        )

    async def reorder_attributes(self, project_id: str, element_id: str, order: list) -> dict:
        """POST /project/{id}/elements/{eid}/reorder  → {"elementId", "attributes": [{id,name}]}"""
        return await self._post(
            f"/project/{project_id}/elements/{element_id}/reorder",
            {"order": order},
        )

    async def reroute_connectors(self, project_id: str, diagram_id: str) -> dict:
        """POST /project/{id}/diagrams/{did}/connectors/reroute
        → {"diagramId", "total", "rerouted", "failed"}
        모든 커넥터의 경유점(waypoint)을 제거해 현재 심볼 위치 기준으로 자동 재라우팅한다.
        """
        return await self._post(
            f"/project/{project_id}/diagrams/{diagram_id}/connectors/reroute",
            {},
            timeout=SLOW_TIMEOUT_SECONDS,
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
            f"/project/{project_id}/diagrams/{diagram_id}/matrix/configure",
            body,
            timeout=SLOW_TIMEOUT_SECONDS,
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
            timeout=SLOW_TIMEOUT_SECONDS,
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
        if name is not None:
            body["name"] = name
        if owner_id is not None:
            body["ownerId"] = owner_id
        if source_part_id is not None:
            body["sourcePartId"] = source_part_id
        if target_part_id is not None:
            body["targetPartId"] = target_part_id
        return await self._post(f"/project/{project_id}/relation", body)

    async def list_relations(self, project_id: str) -> dict:
        """GET /project/{id}/relations → {"relations": [{"id", "name", "type"}]}"""
        return await self._get(f"/project/{project_id}/relations")

    async def delete_relation(self, project_id: str, relation_id: str) -> dict:
        """DELETE /project/{id}/relation/{rid} → {"deleted": str}"""
        return await self._delete(f"/project/{project_id}/relation/{relation_id}")
