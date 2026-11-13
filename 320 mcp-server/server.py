"""
server.py
CATIA Magic MCP Server

MCP 2025-11-25 스펙 준수. FastMCP 라이브러리 기반.
Bridge Plugin REST API와 1:1 매핑된 MCP Tools를 제공합니다.

아키텍처:
  Claude (MCP Client)
      ↕ MCP Protocol (stdio, 2025-11-25)
  Python MCP Server  ← 이 파일
      ↕ HTTP REST (localhost:18080)
  Java Bridge Plugin (MagicDraw 내부 실행)
      ↕ MagicDraw Open Java API
  CATIA Magic (MagicDraw 2026x)

구현된 MCP Tools (Bridge Plugin REST API와 1:1 매핑):
  [Status]
    catia_magic_get_status                  GET    /status
  [Projects]
    catia_magic_list_projects               GET    /projects
  [Elements]
    catia_magic_list_elements               GET    /project/{id}/elements[?query=]
    catia_magic_get_element                 GET    /project/{id}/elements/{eid}
    catia_magic_create_element              POST   /project/{id}/element
    catia_magic_update_element              PUT    /project/{id}/elements/{eid}
    catia_magic_delete_element              DELETE /project/{id}/elements/{eid}
  [Stereotype Properties]
    catia_magic_get_stereotype_properties   GET    /project/{id}/elements/{eid}/props
    catia_magic_set_stereotype_property     PUT    /project/{id}/elements/{eid}/props
  [Diagrams]
    catia_magic_list_diagrams               GET    /project/{id}/diagrams
    catia_magic_get_diagram                 GET    /project/{id}/diagrams/{did}
    catia_magic_create_diagram              POST   /project/{id}/diagram
  [Diagram Symbols & Layout]
    catia_magic_get_diagram_symbols         GET    /project/{id}/diagrams/{did}/symbols
    catia_magic_get_diagram_connectors      GET    /project/{id}/diagrams/{did}/connectors
    catia_magic_add_element_to_diagram      POST   /project/{id}/diagrams/{did}/symbols
    catia_magic_set_symbol_bounds           PUT    /project/{id}/diagrams/{did}/symbols/{sid}
    catia_magic_layout_diagram              POST   /project/{id}/diagrams/{did}/layout
  [Relations]
    catia_magic_create_relation             POST   /project/{id}/relation
    catia_magic_list_relations              GET    /project/{id}/relations
    catia_magic_delete_relation             DELETE /project/{id}/relation/{rid}

미구현 (Bridge Plugin에 해당 엔드포인트 없음):
  - catia_magic_get_project   : GET /projects/{id} 엔드포인트 없음
"""

from __future__ import annotations

import json
from contextlib import asynccontextmanager
from typing import Annotated, Any, AsyncIterator, NoReturn, Optional

import httpx
from mcp.server.fastmcp import FastMCP
from mcp.server.fastmcp.exceptions import ToolError
from pydantic import Field

from bridge_client import (
    AsyncBridgeClient,
    BridgeConnectionError,
    BridgeHttpError,
    BridgeTimeoutError,
    BRIDGE_BASE_URL,
    TIMEOUT_SECONDS,
)

# ─────────────────────────────────────────────────────────────────────────────
# Lifespan: httpx.AsyncClient 싱글턴 관리
# ─────────────────────────────────────────────────────────────────────────────

_bridge: Optional[AsyncBridgeClient] = None


@asynccontextmanager
async def lifespan(server: FastMCP) -> AsyncIterator[None]:
    """FastMCP lifespan — MCP 서버 시작/종료 시 httpx.AsyncClient 관리.

    httpx.AsyncClient를 싱글턴으로 관리하여 연결 풀을 재사용합니다.
    """
    global _bridge
    async with httpx.AsyncClient(
        base_url=BRIDGE_BASE_URL,
        timeout=httpx.Timeout(TIMEOUT_SECONDS),
        headers={"Accept": "application/json"},
    ) as http_client:
        _bridge = AsyncBridgeClient(http_client)
        yield
    _bridge = None


def _get_bridge() -> AsyncBridgeClient:
    """현재 활성 AsyncBridgeClient 반환. 초기화 전 호출 시 RuntimeError."""
    if _bridge is None:
        raise RuntimeError(
            "BridgeClient가 초기화되지 않았습니다. "
            "MCP 서버가 lifespan 컨텍스트 내에서 실행 중인지 확인하세요."
        )
    return _bridge


# ─────────────────────────────────────────────────────────────────────────────
# FastMCP 인스턴스
# ─────────────────────────────────────────────────────────────────────────────

mcp = FastMCP(
    name="catia_magic_mcp",
    instructions=(
        "CATIA Magic (MagicDraw 2026x) 모델을 조회하고 편집하는 MCP 서버입니다. "
        "모든 tool을 사용하려면 MagicDraw가 실행 중이고 "
        "MCP Bridge Plugin(localhost:18080)이 활성화되어 있어야 합니다. "
        "project_id로 'active'를 사용하면 현재 활성 프로젝트를 대상으로 합니다.\n\n"
        "## 매트릭스(Dependency Matrix) 설정 워크플로우\n"
        "Satisfy/Verify/Derive/Refine Requirement Matrix의 행/열 범위 및 요소 타입을 설정할 때는 "
        "반드시 catia_magic_configure_matrix를 사용하세요. "
        "catia_magic_set_stereotype_property나 catia_magic_add_element_to_diagram으로 "
        "매트릭스를 설정하려 하지 마세요 — 이 도구들은 매트릭스에 작동하지 않습니다.\n\n"
        "표준 절차:\n"
        "1. catia_magic_list_diagrams → 매트릭스 다이어그램 ID 확인\n"
        "2. catia_magic_list_elements (query로 패키지/블록 이름 검색) → 행/열 범위 요소 ID 확인\n"
        "3. catia_magic_configure_matrix → row_scope_ids, column_scope_ids, "
        "row_element_types, column_element_types 설정\n"
        "   주의: dependency_criteria는 Satisfy/Verify 등 매트릭스 타입이 자동으로 관리하므로 지정하지 마세요.\n\n"
        "## 관계(Relation) 생성 워크플로우\n"
        "Satisfy/Verify 등 SysML 요구사항 관계를 생성할 때:\n"
        "1. catia_magic_list_elements로 source/target 요소 ID 확인\n"
        "2. catia_magic_create_relation(relation_type='Satisfy', source_id=설계요소ID, target_id=요구사항ID)\n"
        "   Satisfy: 설계 요소(Block/Part 등)가 client(source), 요구사항이 supplier(target)"
    ),
    lifespan=lifespan,
)


# ─────────────────────────────────────────────────────────────────────────────
# 공통 에러 응답 헬퍼
# ─────────────────────────────────────────────────────────────────────────────

def _fail(error: Exception) -> NoReturn:
    """예외를 MCP 프로토콜 수준의 '실패'로 올린다.

    이전에는 에러 dict를 return했다. 그러면 FastMCP가 정상 반환값으로 보고
    isError=false로 직렬화하기 때문에, MagicDraw가 꺼져 있어도 호출한 쪽에서는
    성공으로 보였다(본문에 error 문자열이 들어있을 뿐). 실패는 실패로 올린다.
    """
    if isinstance(error, BridgeTimeoutError):
        # 연결은 됐다. MagicDraw 설치/실행 상태를 뒤지게 만들면 안 된다.
        raise ToolError(f"작업이 제한시간을 넘겼습니다(연결 자체는 정상). {error}") from error
    if isinstance(error, BridgeConnectionError):
        raise ToolError(f"Bridge Plugin 연결 실패: {error}") from error
    if isinstance(error, BridgeHttpError):
        raise ToolError(f"Bridge Plugin이 오류를 반환했습니다: {error}") from error
    raise ToolError(f"{type(error).__name__}: {error}") from error


# ─────────────────────────────────────────────────────────────────────────────
# Status Tools
# ─────────────────────────────────────────────────────────────────────────────

@mcp.tool(
    name="catia_magic_get_status",
    annotations={
        "title": "CATIA Magic 연결 상태 확인",
        "readOnlyHint": True,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False,
    },
)
async def catia_magic_get_status() -> dict[str, Any]:
    """CATIA Magic Bridge Plugin 연결 상태와 열린 프로젝트 수를 확인합니다.

    Bridge Plugin이 정상 실행 중이면 status="ok"와 현재 열린 프로젝트 수를 반환합니다.
    연결 실패 시 error 필드가 포함된 응답을 반환합니다.

    Args:
        없음

    Returns:
        성공: {"status": "ok", "openProjects": int}
        실패: {"error": str, "message": str}

    Examples:
        >>> catia_magic_get_status()
        {"status": "ok", "openProjects": 1}
    """
    try:
        return await _get_bridge().get_status()
    except Exception as e:
        _fail(e)


# ─────────────────────────────────────────────────────────────────────────────
# Project Tools
# ─────────────────────────────────────────────────────────────────────────────

@mcp.tool(
    name="catia_magic_list_projects",
    annotations={
        "title": "CATIA Magic 프로젝트 목록",
        "readOnlyHint": True,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False,
    },
)
async def catia_magic_list_projects() -> dict[str, Any]:
    """현재 MagicDraw에서 열려 있는 모든 프로젝트 목록을 반환합니다.

    isActive=true인 프로젝트가 현재 활성(포커스된) 프로젝트입니다.
    project_id로 "active"를 사용하면 항상 활성 프로젝트를 대상으로 합니다.

    Args:
        없음

    Returns:
        성공: {"projects": [{"id": str, "name": str, "isActive": bool}, ...]}
        실패: {"error": str, "message": str}

    Examples:
        >>> catia_magic_list_projects()
        {"projects": [{"id": "_p1", "name": "MyModel.mdzip", "isActive": true}]}
    """
    try:
        return await _get_bridge().list_projects()
    except Exception as e:
        _fail(e)


# ─────────────────────────────────────────────────────────────────────────────
# Element Tools
# ─────────────────────────────────────────────────────────────────────────────

@mcp.tool(
    name="catia_magic_list_elements",
    annotations={
        "title": "CATIA Magic 요소 목록 조회 / 검색",
        "readOnlyHint": True,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False,
    },
)
async def catia_magic_list_elements(
    project_id: Annotated[
        str,
        Field(
            description=(
                "대상 프로젝트 ID. catia_magic_list_projects로 확인하거나 "
                "'active'를 사용하면 현재 활성 프로젝트를 대상으로 합니다."
            )
        ),
    ],
    query: Annotated[
        Optional[str],
        Field(
            default=None,
            description=(
                "검색어 (선택 사항). 지정하면 요소 이름 또는 타입에 해당 문자열이 "
                "포함된 요소를 재귀적으로 검색합니다 (대소문자 무시, 최대 깊이 5). "
                "생략하면 루트부터 깊이 2까지의 요소 목록을 반환합니다."
            ),
        ),
    ] = None,
) -> dict[str, Any]:
    """프로젝트 내 요소 목록을 반환하거나 이름/타입으로 검색합니다.

    query 미지정 시: 프로젝트 루트(primaryModel)에서 깊이 2까지 요소 목록 반환.
    query 지정 시: 이름 또는 humanType에 query 문자열이 포함된 요소 재귀 검색 (깊이 5).

    Args:
        project_id: 대상 프로젝트 ID (또는 "active")
        query: 검색어 (선택 사항)

    Returns:
        query 미지정: {"elements": [{"id": str, "name": str, "type": str}, ...]}
        query 지정:   {"elements": [...], "query": str}
        실패:         {"error": str, "message": str}

    Examples:
        >>> catia_magic_list_elements(project_id="active")
        {"elements": [{"id": "_e1", "name": "System", "type": "Package"}, ...]}

        >>> catia_magic_list_elements(project_id="active", query="Block")
        {"elements": [...], "query": "Block"}
    """
    try:
        bridge = _get_bridge()
        if query is not None:
            return await bridge.search_elements(project_id, query)
        return await bridge.list_elements(project_id)
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_get_element",
    annotations={
        "title": "CATIA Magic 요소 상세 조회",
        "readOnlyHint": True,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False,
    },
)
async def catia_magic_get_element(
    project_id: Annotated[
        str,
        Field(
            description="대상 프로젝트 ID (또는 'active')"
        ),
    ],
    element_id: Annotated[
        str,
        Field(
            description="조회할 요소의 ID (localID). catia_magic_list_elements로 확인하세요."
        ),
    ],
) -> dict[str, Any]:
    """특정 요소의 상세 정보(id, name, type, ownerId, childCount)를 반환합니다.

    Args:
        project_id: 대상 프로젝트 ID (또는 "active")
        element_id: 요소 ID (localID)

    Returns:
        성공: {"id": str, "name": str, "type": str, "ownerId": str, "childCount": int}
        미존재: {"error": str, "message": str}  (HTTP 404)
        실패:   {"error": str, "message": str}

    Examples:
        >>> catia_magic_get_element(project_id="active", element_id="_e1")
        {"id": "_e1", "name": "System", "type": "Package", "ownerId": "_root", "childCount": 3}
    """
    try:
        return await _get_bridge().get_element(project_id, element_id)
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_create_element",
    annotations={
        "title": "CATIA Magic 요소 생성",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": False,
        "openWorldHint": False,
    },
)
async def catia_magic_create_element(
    project_id: Annotated[
        str,
        Field(
            description="대상 프로젝트 ID (또는 'active')"
        ),
    ],
    element_type: Annotated[
        str,
        Field(
            description=(
                "생성할 요소 타입 (MagicDraw ElementsFactory 메서드명에서 "
                "create/Instance를 제외한 부분). 예: 'Class', 'Package', "
                "'Actor', 'Component', 'Interface', 'UseCase'"
            )
        ),
    ],
    name: Annotated[
        str,
        Field(description="생성할 요소의 이름")
    ],
    owner_id: Annotated[
        Optional[str],
        Field(
            default=None,
            description=(
                "소유자 요소 ID (선택 사항). "
                "생략 시 프로젝트 루트(primaryModel)에 생성됩니다."
            ),
        ),
    ] = None,
    stereotype: Annotated[
        Optional[str],
        Field(
            default=None,
            description=(
                "적용할 스테레오타입 이름 (선택 사항). "
                "예: 'Block', 'Requirement', 'ValueType', 'Interface'. "
                "SysML 프로파일 또는 UML Standard Profile에서 탐색합니다. "
                "Port는 기본값으로 'ProxyPort'(SysML)가 적용됩니다. "
                "순수 UML Port가 필요하면 stereotype='none'을 전달하세요."
            ),
        ),
    ] = None,
    type_id: Annotated[
        Optional[str],
        Field(
            default=None,
            description=(
                "요소의 타입으로 설정할 모델 요소 ID (선택 사항). "
                "Property/Port에서 사용. "
                "예: Part Property의 type을 서브시스템 Block으로 지정할 때 서브시스템 Block ID 전달."
            ),
        ),
    ] = None,
    aggregation: Annotated[
        Optional[str],
        Field(
            default=None,
            description=(
                "Property의 집합(aggregation) 종류 (선택 사항). "
                "'none' | 'shared' | 'composite'. "
                "SysML IBD의 Part Property는 'composite'로 설정해야 합니다."
            ),
        ),
    ] = None,
    metaclasses: Annotated[
        Optional[list[str]],
        Field(
            default=None,
            description=(
                "Stereotype 전용 — 이 스테레오타입을 붙일 수 있는 메타클래스 이름 목록. "
                "예: ['Class'], ['Property', 'Port']. "
                "프로파일 정의: element_type='Profile' → 'Stereotype'(owner=Profile, metaclasses 지정) → "
                "태그는 element_type='Property'(owner=Stereotype, type_id=타입 ID)로 생성."
            ),
        ),
    ] = None,
) -> dict[str, Any]:
    """프로젝트 내에 새 UML/SysML 요소를 생성합니다.

    MagicDraw ElementsFactory의 create{type}Instance() 메서드를 통해 요소를 생성합니다.
    stereotype을 지정하면 생성 후 SysML/UML 프로파일에서 해당 스테레오타입을 자동으로 적용합니다.
    type_id로 Property/Port의 타입을 지정할 수 있습니다 (Part Property에 필수).
    aggregation='composite'로 Part Property를 정의합니다.

    Args:
        project_id:   대상 프로젝트 ID (또는 "active")
        element_type: 요소 타입명 (예: "Class", "Package", "Actor", "Property", "Port")
        name:         요소 이름
        owner_id:     소유자 요소 ID (생략 시 루트에 생성)
        stereotype:   적용할 스테레오타입 이름 (예: "Block", "Requirement")
        type_id:      Property/Port의 타입 요소 ID (예: 서브시스템 Block ID)
        aggregation:  집합 종류 — 'composite'이면 Part Property

    Returns:
        성공 (HTTP 201): {"id": str, "name": str, "type": str, "stereotype": str}
        실패:            {"error": str, "message": str}

    Examples:
        >>> catia_magic_create_element(
        ...     project_id="active",
        ...     element_type="Property",
        ...     name="comm_part",
        ...     owner_id="_system_block",
        ...     type_id="_comm_block",
        ...     aggregation="composite",
        ... )
        {"id": "_e42", "name": "comm_part", "type": "Property", "stereotype": ""}
    """
    try:
        return await _get_bridge().create_element(
            project_id, element_type, name, owner_id, stereotype, type_id, aggregation, metaclasses
        )
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_update_element",
    annotations={
        "title": "CATIA Magic 요소 수정",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False,
    },
)
async def catia_magic_update_element(
    project_id: Annotated[
        str,
        Field(description="대상 프로젝트 ID (또는 'active')")
    ],
    element_id: Annotated[
        str,
        Field(description="수정할 요소의 ID (localID)")
    ],
    name: Annotated[
        Optional[str],
        Field(
            default=None,
            description="변경할 새 이름 (선택 사항; 생략 시 현재 이름 유지)"
        ),
    ] = None,
    documentation: Annotated[
        Optional[str],
        Field(
            default=None,
            description=(
                "변경할 문서/설명 텍스트 (선택 사항). "
                "MagicDraw의 Comment.body에 저장됩니다."
            ),
        ),
    ] = None,
    stereotype: Annotated[
        Optional[str],
        Field(
            default=None,
            description=(
                "적용할 스테레오타입 이름 (선택 사항). "
                "예: 'Block', 'Requirement'. "
                "이미 적용된 스테레오타입에 추가로 적용됩니다."
            ),
        ),
    ] = None,
    type_id: Annotated[
        Optional[str],
        Field(
            default=None,
            description="Property/Port의 타입으로 설정할 요소 ID (선택 사항).",
        ),
    ] = None,
    aggregation: Annotated[
        Optional[str],
        Field(
            default=None,
            description="Property의 집합 종류 (선택 사항). 'none' | 'shared' | 'composite'.",
        ),
    ] = None,
    metaclasses: Annotated[
        Optional[list[str]],
        Field(
            default=None,
            description=(
                "Stereotype 전용 — 붙일 수 있는 메타클래스 이름 목록 (기존 목록을 교체). "
                "예: ['Class', 'Port']"
            ),
        ),
    ] = None,
) -> dict[str, Any]:
    """기존 요소의 이름, documentation, stereotype, type, aggregation을 수정합니다.

    변경 후 요소의 전체 정보를 반환합니다.

    Args:
        project_id:    대상 프로젝트 ID (또는 "active")
        element_id:    수정할 요소 ID (localID)
        name:          새 이름 (선택 사항)
        documentation: 새 문서 텍스트 (선택 사항)
        stereotype:    적용할 스테레오타입 이름 (선택 사항)
        type_id:       Property/Port 타입 요소 ID (선택 사항)
        aggregation:   집합 종류 (선택 사항)

    Returns:
        성공: {"id": str, "name": str, "type": str, "ownerId": str, "childCount": int}
        미존재: {"error": str, "message": str}  (HTTP 404)
        실패:   {"error": str, "message": str}
    """
    try:
        return await _get_bridge().update_element(
            project_id, element_id, name, documentation, stereotype, type_id, aggregation, metaclasses
        )
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_delete_element",
    annotations={
        "title": "CATIA Magic 요소 삭제",
        "readOnlyHint": False,
        "destructiveHint": True,
        "idempotentHint": False,
        "openWorldHint": False,
    },
)
async def catia_magic_delete_element(
    project_id: Annotated[
        str,
        Field(description="대상 프로젝트 ID (또는 'active')")
    ],
    element_id: Annotated[
        str,
        Field(description="삭제할 요소의 ID (localID)")
    ],
) -> dict[str, Any]:
    """프로젝트에서 요소를 영구적으로 삭제합니다.

    이 작업은 되돌릴 수 없습니다.
    읽기 전용 요소는 삭제할 수 없습니다 (HTTP 403 반환).

    Args:
        project_id: 대상 프로젝트 ID (또는 "active")
        element_id: 삭제할 요소 ID (localID)

    Returns:
        성공:   {"deleted": str}  (삭제된 element_id)
        미존재: {"error": str, "message": str}  (HTTP 404)
        읽기전용: {"error": str, "message": str}  (HTTP 403)
        실패:   {"error": str, "message": str}

    Examples:
        >>> catia_magic_delete_element(project_id="active", element_id="_e42")
        {"deleted": "_e42"}
    """
    try:
        return await _get_bridge().delete_element(project_id, element_id)
    except Exception as e:
        _fail(e)


# ─────────────────────────────────────────────────────────────────────────────
# Diagram Tools
# ─────────────────────────────────────────────────────────────────────────────

@mcp.tool(
    name="catia_magic_list_diagrams",
    annotations={
        "title": "CATIA Magic 다이어그램 목록",
        "readOnlyHint": True,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False,
    },
)
async def catia_magic_list_diagrams(
    project_id: Annotated[
        str,
        Field(description="대상 프로젝트 ID (또는 'active')")
    ],
) -> dict[str, Any]:
    """프로젝트 내 모든 다이어그램 목록을 반환합니다.

    모델 트리를 재귀 탐색(최대 깊이 10)하여 Diagram 타입 요소를 수집합니다.
    humanType에 "Diagram"이 포함된 요소를 다이어그램으로 식별합니다.

    Args:
        project_id: 대상 프로젝트 ID (또는 "active")

    Returns:
        성공: {"diagrams": [{"id": str, "name": str, "type": str}, ...]}
        실패: {"error": str, "message": str}

    Examples:
        >>> catia_magic_list_diagrams(project_id="active")
        {"diagrams": [
            {"id": "_d1", "name": "System BDD", "type": "SysML Block Definition Diagram"},
            {"id": "_d2", "name": "Main IBD", "type": "SysML Internal Block Diagram"}
        ]}
    """
    try:
        return await _get_bridge().list_diagrams(project_id)
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_get_diagram",
    annotations={
        "title": "CATIA Magic 다이어그램 단건 조회",
        "readOnlyHint": True,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False,
    },
)
async def catia_magic_get_diagram(
    project_id: Annotated[
        str,
        Field(description="대상 프로젝트 ID (또는 'active')")
    ],
    diagram_id: Annotated[
        str,
        Field(
            description="조회할 다이어그램의 ID (localID). catia_magic_list_diagrams로 확인하세요."
        ),
    ],
) -> dict[str, Any]:
    """특정 다이어그램의 정보(id, name, type)를 반환합니다.

    Args:
        project_id: 대상 프로젝트 ID (또는 "active")
        diagram_id: 다이어그램 ID (localID)

    Returns:
        성공:   {"id": str, "name": str, "type": str}
        미존재: {"error": str, "message": str}  (HTTP 404)
        실패:   {"error": str, "message": str}

    Examples:
        >>> catia_magic_get_diagram(project_id="active", diagram_id="_d1")
        {"id": "_d1", "name": "System BDD", "type": "SysML Block Definition Diagram"}
    """
    try:
        return await _get_bridge().get_diagram(project_id, diagram_id)
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_create_diagram",
    annotations={
        "title": "CATIA Magic 다이어그램 생성",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": False,
        "openWorldHint": False,
    },
)
async def catia_magic_create_diagram(
    project_id: Annotated[
        str,
        Field(description="대상 프로젝트 ID (또는 'active')")
    ],
    diagram_type: Annotated[
        str,
        Field(
            description=(
                "생성할 다이어그램 타입. Bridge Plugin이 지원하는 단축명 또는 정식명을 사용합니다.\n"
                "일반 다이어그램 단축명: bdd, ibd, usecase, sequence, activity, statemachine, "
                "req (requirement), parametric\n"
                "요구사항 추적 매트릭스 단축명: satisfy (Satisfy Requirement Matrix), "
                "verify (Verify Requirement Matrix), derive (DeriveReqt Requirement Matrix), "
                "refine (Refine Requirement Matrix)\n"
                "주의: 매트릭스 타입 다이어그램은 생성 후 catia_magic_add_element_to_diagram으로 "
                "심볼을 추가하지 말 것. 매트릭스는 catia_magic_configure_matrix로 scope/element type을 "
                "설정하면 자동으로 행/열이 채워짐."
            )
        ),
    ],
    name: Annotated[
        str,
        Field(description="생성할 다이어그램 이름")
    ],
    owner_id: Annotated[
        Optional[str],
        Field(
            default=None,
            description=(
                "소유자 Namespace 요소 ID (선택 사항). "
                "생략 시 프로젝트 루트(primaryModel)에 생성됩니다. "
                "owner는 반드시 Namespace 타입이어야 합니다."
            ),
        ),
    ] = None,
) -> dict[str, Any]:
    """프로젝트 내에 새 다이어그램을 생성합니다.

    ModelElementsManager.createDiagram()을 사용하여 다이어그램을 생성합니다.
    owner는 반드시 Namespace 타입 요소여야 합니다.

    Args:
        project_id:   대상 프로젝트 ID (또는 "active")
        diagram_type: 다이어그램 타입 (단축명 또는 정식명)
        name:         다이어그램 이름
        owner_id:     소유자 Namespace 요소 ID (생략 시 루트에 생성)

    Returns:
        성공 (HTTP 201): {"id": str, "name": str, "type": str}
        실패:            {"error": str, "message": str}

    Examples:
        >>> catia_magic_create_diagram(
        ...     project_id="active",
        ...     diagram_type="bdd",
        ...     name="Vehicle System BDD",
        ...     owner_id="_pkg_system",
        ... )
        {"id": "_d5", "name": "Vehicle System BDD", "type": "SysML Block Definition Diagram"}
    """
    try:
        return await _get_bridge().create_diagram(
            project_id, diagram_type, name, owner_id
        )
    except Exception as e:
        _fail(e)


# ─────────────────────────────────────────────────────────────────────────────
# Stereotype Properties Tools
# ─────────────────────────────────────────────────────────────────────────────

@mcp.tool(
    name="catia_magic_get_stereotype_properties",
    annotations={
        "title": "CATIA Magic 스테레오타입 속성 조회",
        "readOnlyHint": True,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False,
    },
)
async def catia_magic_get_stereotype_properties(
    project_id: Annotated[
        str,
        Field(description="대상 프로젝트 ID (또는 'active')")
    ],
    element_id: Annotated[
        str,
        Field(description="조회할 요소의 ID (localID)")
    ],
) -> dict[str, Any]:
    """요소에 적용된 스테레오타입과 각 속성값을 조회합니다.

    해당 요소에 적용된 모든 스테레오타입과, 각 스테레오타입이 가진
    속성(태그된 값, Tagged Value) 목록 및 현재 설정값을 반환합니다.

    예: «block» 스테레오타입의 isEncapsulated 속성값 조회,
        «Requirement» 의 Id, Text 속성값 조회

    Args:
        project_id: 대상 프로젝트 ID (또는 "active")
        element_id: 요소 ID (localID)

    Returns:
        성공: {
            "elementId": str,
            "elementName": str,
            "stereotypes": [
                {
                    "name": str,
                    "properties": [{"name": str, "value": str}, ...]
                }
            ]
        }
        실패: {"error": str, "message": str}

    Examples:
        >>> catia_magic_get_stereotype_properties(
        ...     project_id="active", element_id="_e42"
        ... )
        {
            "elementId": "_e42",
            "elementName": "PowerSubsystem",
            "stereotypes": [
                {
                    "name": "Block",
                    "properties": [{"name": "isEncapsulated", "value": "false"}]
                }
            ]
        }
    """
    try:
        return await _get_bridge().get_stereotype_properties(project_id, element_id)
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_set_stereotype_property",
    annotations={
        "title": "CATIA Magic 스테레오타입 속성값 변경",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False,
    },
)
async def catia_magic_set_stereotype_property(
    project_id: Annotated[
        str,
        Field(description="대상 프로젝트 ID (또는 'active')")
    ],
    element_id: Annotated[
        str,
        Field(description="수정할 요소의 ID (localID)")
    ],
    stereotype: Annotated[
        str,
        Field(description="속성이 속한 스테레오타입 이름. 예: 'Block', 'Requirement'")
    ],
    property_name: Annotated[
        str,
        Field(description="변경할 속성 이름. 예: 'isEncapsulated', 'Id', 'Text'")
    ],
    value: Annotated[
        str,
        Field(description=(
            "설정할 값 (문자열). 숫자·불리언도 문자열로 전달합니다. "
            "요소를 가리키는 태그(예: Customization의 customizationTarget)는 요소 ID를, "
            "여러 개면 쉼표로 구분한 ID 목록을 전달합니다."
        ))
    ],
) -> dict[str, Any]:
    """요소의 스테레오타입 속성값을 변경합니다.

    스테레오타입이 해당 요소에 이미 적용되어 있어야 합니다.
    스테레오타입 적용은 catia_magic_create_element 또는 catia_magic_update_element의
    stereotype 파라미터를 사용하세요.

    Args:
        project_id:    대상 프로젝트 ID (또는 "active")
        element_id:    수정할 요소 ID (localID)
        stereotype:    속성이 속한 스테레오타입 이름
        property_name: 변경할 속성 이름
        value:         설정할 값 (문자열)

    Returns:
        성공: {"elementId": str, "stereotype": str, "property": str, "value": str}
        실패: {"error": str, "message": str}

    Examples:
        >>> catia_magic_set_stereotype_property(
        ...     project_id="active",
        ...     element_id="_e42",
        ...     stereotype="Requirement",
        ...     property_name="Id",
        ...     value="REQ-001",
        ... )
        {"elementId": "_e42", "stereotype": "Requirement", "property": "Id", "value": "REQ-001"}
    """
    try:
        return await _get_bridge().set_stereotype_property(
            project_id, element_id, stereotype, property_name, value
        )
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_get_profile",
    annotations={
        "title": "CATIA Magic 프로파일 조회",
        "readOnlyHint": True,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False,
    },
)
async def catia_magic_get_profile(
    project_id: Annotated[
        str,
        Field(description="대상 프로젝트 ID (또는 'active')")
    ],
    profile_id: Annotated[
        Optional[str],
        Field(
            default=None,
            description=(
                "조회할 Profile ID (선택 사항). 지정하면 그 프로파일의 스테레오타입 상세"
                "(메타클래스, 상위 스테레오타입, 태그 정의)를 반환합니다. "
                "생략하면 프로젝트에 로드된 프로파일 목록을 반환합니다."
            ),
        ),
    ] = None,
    query: Annotated[
        Optional[str],
        Field(
            default=None,
            description="profile_id 생략 시 프로파일 이름 필터 (대소문자 무시, 부분 일치).",
        ),
    ] = None,
) -> dict[str, Any]:
    """프로파일 목록 또는 프로파일 내 스테레오타입 정의를 조회합니다.

    Returns:
        목록: {"profiles": [{"id", "name", "stereotypeCount"}]}
        상세: {"id", "name", "stereotypes": [{"id", "name", "isAbstract",
               "metaclasses": [str], "parents": [str],
               "tags": [{"id", "name", "type", "lower", "upper"}]}]}
    """
    try:
        if profile_id:
            return await _get_bridge().get_profile(project_id, profile_id)
        return await _get_bridge().list_profiles(project_id, query)
    except Exception as e:
        _fail(e)


# ─────────────────────────────────────────────────────────────────────────────
# Diagram Symbols & Layout Tools
# ─────────────────────────────────────────────────────────────────────────────

@mcp.tool(
    name="catia_magic_get_diagram_symbols",
    annotations={
        "title": "CATIA Magic 다이어그램 심볼 목록 조회",
        "readOnlyHint": True,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False,
    },
)
async def catia_magic_get_diagram_symbols(
    project_id: Annotated[
        str,
        Field(description="대상 프로젝트 ID (또는 'active')")
    ],
    diagram_id: Annotated[
        str,
        Field(description="조회할 다이어그램의 ID (localID). catia_magic_list_diagrams로 확인하세요.")
    ],
) -> dict[str, Any]:
    """다이어그램에 배치된 모든 심볼의 좌표와 크기를 반환합니다.

    심볼은 다이어그램 내에서 모델 요소가 시각적으로 표현된 객체입니다.
    최상위 블록뿐 아니라 중첩된 파트·포트까지 재귀적으로 포함합니다.
    반환된 x, y, width, height 값을 바탕으로 레이아웃을 분석하거나
    catia_magic_set_symbol_bounds로 위치를 조정할 수 있습니다.
    커넥터(연결선)는 catia_magic_get_diagram_connectors로 따로 조회하세요.

    각 심볼 필드:
        kind:           "shape"(블록/파트) 또는 "port"
        parentSymbolId: 소속 부모 심볼 ID. 최상위면 null. (포트→소속 블록 역추적용)
        elementType:    모델 요소의 humanType (예: "Part Property", "Proxy Port")

    Args:
        project_id: 대상 프로젝트 ID (또는 "active")
        diagram_id: 다이어그램 ID (localID)

    Returns:
        성공: {
            "diagramId": str,
            "symbols": [
                {
                    "id": str,
                    "elementId": str,
                    "elementName": str,
                    "elementType": str,
                    "kind": "shape" | "port",
                    "parentSymbolId": str | None,
                    "x": int, "y": int, "width": int, "height": int
                }, ...
            ]
        }
        실패: {"error": str, "message": str}

    Examples:
        >>> catia_magic_get_diagram_symbols(project_id="active", diagram_id="_d1")
        {
            "diagramId": "_d1",
            "symbols": [
                {"id": "_s1", "elementId": "_e1", "elementName": "Engine",
                 "elementType": "Part Property", "kind": "shape", "parentSymbolId": None,
                 "x": 100, "y": 200, "width": 120, "height": 60},
                {"id": "_s2", "elementId": "_p1", "elementName": "p1",
                 "elementType": "Proxy Port", "kind": "port", "parentSymbolId": "_s1",
                 "x": 215, "y": 230, "width": 10, "height": 10}
            ]
        }
    """
    try:
        return await _get_bridge().get_diagram_symbols(project_id, diagram_id)
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_get_diagram_connectors",
    annotations={
        "title": "CATIA Magic 다이어그램 커넥터(연결선) 조회",
        "readOnlyHint": True,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False,
    },
)
async def catia_magic_get_diagram_connectors(
    project_id: Annotated[
        str,
        Field(description="대상 프로젝트 ID (또는 'active')")
    ],
    diagram_id: Annotated[
        str,
        Field(description="조회할 다이어그램의 ID (localID). catia_magic_list_diagrams로 확인하세요.")
    ],
) -> dict[str, Any]:
    """다이어그램에 그려진 모든 커넥터(연결선/path)를 반환합니다.

    각 커넥터의 출발/도착 심볼 ID와 경유점(waypoints)을 제공합니다.
    catia_magic_get_diagram_symbols(심볼 좌표)와 결합하면 선 교차 분석·자동
    레이아웃 계산에 필요한 그래프 구조(노드+엣지)를 완성할 수 있습니다.
    sourceSymbolId/targetSymbolId는 포트 심볼을 가리킬 수 있으며, 해당 포트의
    parentSymbolId(get_diagram_symbols)로 소속 블록을 역추적합니다.

    Args:
        project_id: 대상 프로젝트 ID (또는 "active")
        diagram_id: 다이어그램 ID (localID)

    Returns:
        성공: {
            "diagramId": str,
            "connectors": [
                {
                    "id": str,                # path 심볼 ID
                    "relationId": str,        # 모델 관계(Connector 등) ID
                    "relationName": str,
                    "relationType": str,      # humanType (예: "Connector")
                    "sourceSymbolId": str,    # 출발 심볼(포트일 수 있음)
                    "targetSymbolId": str,    # 도착 심볼(포트일 수 있음)
                    "waypoints": [{"x": int, "y": int}, ...]
                }, ...
            ]
        }
        실패: {"error": str, "message": str}

    Examples:
        >>> catia_magic_get_diagram_connectors(project_id="active", diagram_id="_d1")
        {
            "diagramId": "_d1",
            "connectors": [
                {"id": "_pa1", "relationId": "_c1", "relationName": "",
                 "relationType": "Connector",
                 "sourceSymbolId": "_s2", "targetSymbolId": "_s5",
                 "waypoints": [{"x": 225, "y": 235}, {"x": 400, "y": 235}]}
            ]
        }
    """
    try:
        return await _get_bridge().get_diagram_connectors(project_id, diagram_id)
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_reroute_connectors",
    annotations={
        "title": "CATIA Magic 커넥터 재라우팅(경로 정리)",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False,
    },
)
async def catia_magic_reroute_connectors(
    project_id: Annotated[
        str,
        Field(description="대상 프로젝트 ID (또는 'active')")
    ],
    diagram_id: Annotated[
        str,
        Field(description="대상 다이어그램 ID (localID). catia_magic_list_diagrams로 확인하세요.")
    ],
) -> dict[str, Any]:
    """다이어그램의 모든 커넥터 경유점(waypoint)을 제거해 자동 재라우팅합니다.

    블록을 move_symbols로 옮긴 뒤 옛 waypoint가 남아 크게 우회하는 선을,
    현재 심볼 위치 기준 최단 경로로 MagicDraw가 다시 그리게 합니다.
    거대하게 도는 커넥터가 다이어그램 프레임을 늘려 auto-resize를 방해할 때
    호출하면 프레임도 함께 정상화됩니다.

    Args:
        project_id: 대상 프로젝트 ID (또는 "active")
        diagram_id: 대상 다이어그램 ID (localID)

    Returns:
        성공: {"diagramId": str, "total": int, "rerouted": int, "failed": int}
        실패: {"error": str, "message": str}
    """
    try:
        return await _get_bridge().reroute_connectors(project_id, diagram_id)
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_add_element_to_diagram",
    annotations={
        "title": "CATIA Magic 다이어그램에 요소 심볼 추가",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": False,
        "openWorldHint": False,
    },
)
async def catia_magic_add_element_to_diagram(
    project_id: Annotated[
        str,
        Field(description="대상 프로젝트 ID (또는 'active')")
    ],
    diagram_id: Annotated[
        str,
        Field(
            description=(
                "심볼을 추가할 다이어그램 ID (localID). "
                "주의: Satisfy/Verify/Derive/Refine Requirement Matrix 등 매트릭스 타입 다이어그램에는 "
                "사용하지 말 것. 매트릭스는 catia_magic_configure_matrix로 설정."
            )
        )
    ],
    element_id: Annotated[
        str,
        Field(description="다이어그램에 배치할 모델 요소 ID (localID)")
    ],
    x: Annotated[
        Optional[int],
        Field(default=None, description="심볼 X 좌표 (픽셀). 생략 시 MagicDraw가 자동 배치합니다.")
    ] = None,
    y: Annotated[
        Optional[int],
        Field(default=None, description="심볼 Y 좌표 (픽셀). 생략 시 MagicDraw가 자동 배치합니다.")
    ] = None,
    auto_display_ports: Annotated[
        Optional[bool],
        Field(
            default=False,
            description=(
                "Part Property 추가 시 해당 Block의 Port를 자동으로 다이어그램에 표시할지 여부. "
                "기본값 False. True로 설정하면 Block에 정의된 모든 Port 심볼이 함께 생성됩니다. "
                "IBD에서 Part를 추가하고 Port는 수동으로 배치하려면 False(기본값)를 사용하세요."
            ),
        ),
    ] = False,
) -> dict[str, Any]:
    """모델 요소를 다이어그램에 심볼로 추가합니다.

    x, y를 지정하면 해당 좌표에 배치되고, 생략하면 MagicDraw가 자동으로
    위치를 결정합니다. 요소가 다이어그램 소유자(owner)의 하위 요소여야 합니다.

    Args:
        project_id: 대상 프로젝트 ID (또는 "active")
        diagram_id: 대상 다이어그램 ID
        element_id: 배치할 요소 ID
        x:          배치 X 좌표 (선택 사항)
        y:          배치 Y 좌표 (선택 사항)

    Returns:
        성공 (HTTP 201): {"id": str, "elementId": str, "elementName": str,
                          "x": int, "y": int, "width": int, "height": int}
        실패: {"error": str, "message": str}

    Examples:
        >>> catia_magic_add_element_to_diagram(
        ...     project_id="active",
        ...     diagram_id="_d1",
        ...     element_id="_e_engine",
        ...     x=100, y=200,
        ... )
        {"id": "_s5", "elementId": "_e_engine", "elementName": "Engine",
         "x": 100, "y": 200, "width": 120, "height": 60}
    """
    try:
        return await _get_bridge().add_element_to_diagram(
            project_id, diagram_id, element_id, x, y, auto_display_ports
        )
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_set_symbol_bounds",
    annotations={
        "title": "CATIA Magic 심볼 위치·크기 변경",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False,
    },
)
async def catia_magic_set_symbol_bounds(
    project_id: Annotated[
        str,
        Field(description="대상 프로젝트 ID (또는 'active')")
    ],
    diagram_id: Annotated[
        str,
        Field(description="심볼이 속한 다이어그램 ID (localID)")
    ],
    symbol_id: Annotated[
        str,
        Field(description="변경할 심볼 ID. catia_magic_get_diagram_symbols로 확인하세요.")
    ],
    x: Annotated[
        Optional[int],
        Field(default=None, description="새 X 좌표 (픽셀). 생략 시 현재 값 유지.")
    ] = None,
    y: Annotated[
        Optional[int],
        Field(default=None, description="새 Y 좌표 (픽셀). 생략 시 현재 값 유지.")
    ] = None,
    width: Annotated[
        Optional[int],
        Field(default=None, description="새 너비 (픽셀). 생략 시 현재 값 유지.")
    ] = None,
    height: Annotated[
        Optional[int],
        Field(default=None, description="새 높이 (픽셀). 생략 시 현재 값 유지.")
    ] = None,
) -> dict[str, Any]:
    """다이어그램 내 특정 심볼의 위치와 크기를 변경합니다.

    x, y, width, height 중 변경하려는 값만 지정하면 됩니다.
    나머지는 현재 값을 유지합니다.
    catia_magic_layout_diagram으로 자동 레이아웃을 적용한 후
    개별 심볼을 미세 조정할 때 유용합니다.

    Args:
        project_id: 대상 프로젝트 ID (또는 "active")
        diagram_id: 다이어그램 ID
        symbol_id:  변경할 심볼 ID
        x:          새 X 좌표 (선택 사항)
        y:          새 Y 좌표 (선택 사항)
        width:      새 너비 (선택 사항)
        height:     새 높이 (선택 사항)

    Returns:
        성공: {"symbolId": str, "x": int, "y": int, "width": int, "height": int}
        실패: {"error": str, "message": str}

    Examples:
        >>> catia_magic_set_symbol_bounds(
        ...     project_id="active",
        ...     diagram_id="_d1",
        ...     symbol_id="_s1",
        ...     x=300, y=150,
        ... )
        {"symbolId": "_s1", "x": 300, "y": 150, "width": 120, "height": 60}
    """
    try:
        return await _get_bridge().set_symbol_bounds(
            project_id, diagram_id, symbol_id, x, y, width, height
        )
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_layout_diagram",
    annotations={
        "title": "CATIA Magic 다이어그램 자동 레이아웃",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": False,
        "openWorldHint": False,
    },
)
async def catia_magic_layout_diagram(
    project_id: Annotated[
        str,
        Field(description="대상 프로젝트 ID (또는 'active')")
    ],
    diagram_id: Annotated[
        str,
        Field(description="레이아웃을 적용할 다이어그램 ID (localID)")
    ],
    algorithm: Annotated[
        Optional[str],
        Field(
            default=None,
            description=(
                "사용할 레이아웃 알고리즘 (선택 사항). 생략 시 'hierarchic' 적용.\n"
                "IBD / PAR 다이어그램은 algorithm 미지정 시 자동으로 hierarchic 적용.\n"
                "선택값:\n"
                "  hierarchic  - 계층형 (상하 방향 흐름). IBD/PAR 기본값.\n"
                "  organic     - 유기적 (자유로운 배치, 중첩 최소화)\n"
                "  tree        - 트리형 (부모-자식 계층)\n"
                "  circular    - 원형 배치\n"
                "  orthogonal  - 직각 그리드 기반\n"
                "  grid        - 격자형\n"
                "  class       - 클래스 다이어그램 최적화"
            ),
        ),
    ] = None,
) -> dict[str, Any]:
    """다이어그램에 자동 레이아웃 알고리즘을 적용합니다.

    MagicDraw Layouting API를 사용하여 다이어그램 내 모든 심볼의 위치를
    자동으로 재배치합니다.

    IBD / PAR 다이어그램은 algorithm 미지정 시 hierarchic이 자동 적용됩니다.
    BDD 등 다른 다이어그램 타입도 미지정 시 hierarchic이 기본값입니다.

    Args:
        project_id: 대상 프로젝트 ID (또는 "active")
        diagram_id: 다이어그램 ID
        algorithm:  레이아웃 알고리즘 (선택 사항, 기본값: hierarchic)

    Returns:
        성공: {"diagramId": str, "algorithm": str, "status": "ok"}
        실패: {"error": str, "message": str}

    Examples:
        >>> catia_magic_layout_diagram(
        ...     project_id="active",
        ...     diagram_id="_d1",
        ...     algorithm="organic",
        ... )
        {"diagramId": "_d1", "algorithm": "organic", "status": "ok"}
    """
    try:
        return await _get_bridge().layout_diagram(project_id, diagram_id, algorithm)
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_export_diagram",
    annotations={
        "title": "CATIA Magic 다이어그램 PNG 내보내기",
        # 모델은 안 바꾸지만 다이어그램을 UI에서 열고 PNG 파일을 덮어쓴다.
        # readOnly로 두면 호출하는 쪽이 부작용 없는 조회로 오해한다.
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": False,
        "openWorldHint": False,
    },
)
async def catia_magic_export_diagram(
    project_id: Annotated[
        str,
        Field(description="대상 프로젝트 ID (또는 'active')"),
    ],
    diagram_id: Annotated[
        str,
        Field(description="내보낼 다이어그램 ID (localID)"),
    ],
) -> dict[str, Any]:
    """다이어그램을 PNG 이미지 파일로 내보내고 저장 경로를 반환합니다.

    좌표(get_diagram_symbols)만으로는 커넥터 경로·선 교차를 알 수 없으므로,
    선 교차나 전반적 배치를 눈으로 확인해야 할 때 이 도구로 PNG를 만든 뒤
    반환된 path를 Read로 읽어 실제 그림을 본다.
    임시폴더에 mcp_diagram_<id>.png 로 저장된다(매 호출 덮어쓰기).

    Args:
        project_id: 대상 프로젝트 ID (또는 "active")
        diagram_id: 다이어그램 ID

    Returns:
        성공: {"diagramId": str, "format": "png", "path": str, "bytes": int}
        실패: {"error": str, "message": str}
    """
    try:
        return await _get_bridge().export_diagram(project_id, diagram_id)
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_reorder_attributes",
    annotations={
        "title": "CATIA Magic 속성(Property/Port) 순서 변경",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False,
    },
)
async def catia_magic_reorder_attributes(
    project_id: Annotated[str, Field(description="대상 프로젝트 ID (또는 'active')")],
    element_id: Annotated[str, Field(description="속성을 가진 요소 ID (Block/Class/DataType/Interface)")],
    order: Annotated[
        list[str],
        Field(description="원하는 순서의 속성 ID 목록. 나열한 속성이 앞에 이 순서로 오고, 빠진 속성은 기존 순서대로 뒤에 붙는다."),
    ],
) -> dict[str, Any]:
    """요소가 가진 속성(part/value/reference property, port 등)의 순서를 바꿉니다.

    Returns:
        성공: {"elementId": str, "attributes": [{"id": str, "name": str}, ...]}  (변경 후 전체 순서)
        실패: {"error": str, "message": str}
    """
    try:
        return await _get_bridge().reorder_attributes(project_id, element_id, order)
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_configure_matrix",
    annotations={
        "title": "CATIA Magic Dependency Matrix 행/열/관계 설정",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False,
    },
)
async def catia_magic_configure_matrix(
    project_id: Annotated[str, Field(description="대상 프로젝트 ID (또는 'active')")],
    diagram_id: Annotated[str, Field(description="대상 매트릭스 다이어그램 ID")],
    row_scope_ids: Annotated[
        list[str],
        Field(description="행 범위 루트 요소 ID 목록 (예: 요구사항 패키지 ID들)"),
    ],
    column_scope_ids: Annotated[
        list[str],
        Field(description="열 범위 루트 요소 ID 목록 (예: 설계 패키지/Block ID들)"),
    ],
    dependency_criteria: Annotated[
        Optional[list[str]],
        Field(
            default=None,
            description=(
                "[현재 미지원 — 지정해도 무시됨] "
                "셀에 표시할 관계 기준은 매트릭스 타입(Satisfy/Verify/Derive/Refine)이 "
                "자동으로 관리합니다. 이 파라미터를 지정하면 내부적으로 무시되며 "
                "ExpressionException을 유발할 수 있으므로 생략하세요."
            ),
        ),
    ] = None,
    row_element_types: Annotated[
        Optional[list[str]],
        Field(
            default=None,
            description=(
                "행에 표시할 요소 타입 목록. "
                "지원값: 'Block', 'PartProperty'(또는 'Property'), 'ProxyPort'(또는 'Port'), "
                "'Requirement', 'Interface', 'ValueType'. "
                "예: ['Block', 'PartProperty', 'ProxyPort']"
            ),
        ),
    ] = None,
    column_element_types: Annotated[
        Optional[list[str]],
        Field(
            default=None,
            description=(
                "열에 표시할 요소 타입 목록. "
                "지원값: 'Block', 'PartProperty'(또는 'Property'), 'ProxyPort'(또는 'Port'), "
                "'Requirement', 'Interface', 'ValueType'. "
                "예: ['Block', 'PartProperty', 'ProxyPort']"
            ),
        ),
    ] = None,
    direction: Annotated[
        Optional[str],
        Field(
            default=None,
            description=(
                "관계 방향. 예: 'row_to_column' / 'column_to_row' / 'both'. "
                "MatrixSettings.Direction enum과 매칭."
            ),
        ),
    ] = None,
    include_subtypes: Annotated[
        Optional[bool],
        Field(
            default=None,
            description="행/열 요소 타입의 서브타입 포함 여부 (기본: 변경 없음)",
        ),
    ] = None,
) -> dict[str, Any]:
    """Dependency Matrix의 행/열 범위와 요소 타입을 설정하는 전용 도구입니다.

    매트릭스(Satisfy/Verify/Derive/Refine Requirement Matrix)의 행/열 설정은
    반드시 이 도구를 사용하세요. catia_magic_set_stereotype_property나
    catia_magic_add_element_to_diagram으로는 매트릭스를 설정할 수 없습니다.

    dependency_criteria(Satisfy 등)는 매트릭스 타입 configurator가 자동으로 관리하므로
    이 파라미터를 지정하지 마세요.

    사용 전 필수 준비:
    - catia_magic_list_diagrams로 매트릭스 다이어그램 ID 확인
    - catia_magic_list_elements(query=패키지명)로 행/열 범위 루트 요소 ID 확인

    Args:
        project_id:           대상 프로젝트 ID (또는 "active")
        diagram_id:           대상 매트릭스 다이어그램 ID
        row_scope_ids:        행 범위 루트 요소 ID 목록 (예: 요구사항 패키지 ID)
        column_scope_ids:     열 범위 루트 요소 ID 목록 (예: 설계 패키지 ID)
        row_element_types:    행에 표시할 요소 타입 (예: ['Block','PartProperty','ProxyPort'])
        column_element_types: 열에 표시할 요소 타입 (예: ['Requirement'])
        dependency_criteria:  [미지원, 생략 권장] 매트릭스 타입이 자동 관리
        direction:            관계 방향 (예: 'row_to_column')
        include_subtypes:     서브타입 포함 여부

    Returns:
        성공: {"diagramId": str, "rowScopeCount": int, "columnScopeCount": int,
               "criteriaNote": "managed-by-diagram-type-configurator", "status": "ok"}
        실패: {"error": str, "message": str}

    Examples:
        # Satisfy Matrix: 행=설계요소(Block/Part/Port), 열=요구사항
        >>> catia_magic_configure_matrix(
        ...     project_id="active",
        ...     diagram_id="_matrix_id",
        ...     row_scope_ids=["_structure_pkg_id"],
        ...     column_scope_ids=["_requirement_pkg_id"],
        ...     row_element_types=["Block", "PartProperty", "ProxyPort"],
        ...     column_element_types=["Requirement"],
        ...     direction="row_to_column",
        ... )
        {"diagramId": "_matrix_id", "rowScopeCount": 3, "columnScopeCount": 1,
         "criteriaNote": "managed-by-diagram-type-configurator", "status": "ok"}
    """
    try:
        return await _get_bridge().configure_matrix(
            project_id, diagram_id,
            row_scope_ids, column_scope_ids,
            dependency_criteria, direction, include_subtypes,
            row_element_types, column_element_types,
        )
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_display_all_paths",
    annotations={
        "title": "CATIA Magic 모든 관계 경로 자동 시각화",
        "readOnlyHint": False,
        "destructiveHint": False,
        # 다이어그램에 path 요소를 새로 만든다. 반복 호출이 같은 결과를 주지 않는다.
        "idempotentHint": False,
        "openWorldHint": False,
    },
)
async def catia_magic_display_all_paths(
    project_id: Annotated[
        str,
        Field(description="대상 프로젝트 ID (또는 'active')"),
    ],
    diagram_id: Annotated[
        str,
        Field(description="대상 다이어그램 ID (localID)"),
    ],
    display_containment: Annotated[
        bool,
        Field(description=(
            "containment(소유) 링크 표시 여부. 기본 False — 관계만 표시(BDD/IBD 권장). "
            "요구사항 다이어그램에서 상위/하위 요구사항 계층을 containment로 표시하려면 True."
        )),
    ] = False,
) -> dict[str, Any]:
    """다이어그램에 이미 배치된 모든 심볼 사이의 관계를 path로 자동 시각화합니다.

    MagicDraw의 DisplayPathElements.displayPathElements API를 사용해
    다이어그램에 표시된 모든 shape 심볼(Part, Block, Port 등)을 선택한 상태로
    "Display All Paths" 동작을 수행합니다. 심볼 사이에 모델상 존재하는 모든
    Connector/Dependency/Generalization 등이 자동으로 path로 표현됩니다.

    display_containment=False(기본)이면 containment 링크는 그리지 않고 관계만 표시합니다.
    요구사항 계층(상위/하위 요구사항)처럼 containment 표시가 필요하면 True로 호출하세요.

    IBD에서 Part Property 배치 + Connector 모델을 만든 뒤 이 도구를 호출하면
    개별 add_relation_to_diagram 호출 없이 한 번에 모든 path가 그려집니다.

    Args:
        project_id: 대상 프로젝트 ID (또는 "active")
        diagram_id: 다이어그램 ID
        display_containment: containment 링크 표시 여부 (기본 False)

    Returns:
        성공: {
            "diagramId": str,
            "selectedSymbolCount": int,
            "count": int,
            "paths": [{"id": str, "relationId": str, "relationName": str}, ...]
        }
        실패: {"error": str, "message": str}

    Examples:
        >>> catia_magic_display_all_paths(project_id="active", diagram_id="_d1")
        {"diagramId": "_d1", "selectedSymbolCount": 9, "count": 3,
         "paths": [{"id": "_p1", "relationId": "_r1", "relationName": "mountLink"}, ...]}
    """
    try:
        return await _get_bridge().display_all_paths(
            project_id, diagram_id, display_containment
        )
    except Exception as e:
        _fail(e)


# ─────────────────────────────────────────────────────────────────────────────
# Relation Tools
# ─────────────────────────────────────────────────────────────────────────────

@mcp.tool(
    name="catia_magic_create_relation",
    annotations={
        "title": "CATIA Magic 관계 생성",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": False,
        "openWorldHint": False,
    },
)
async def catia_magic_create_relation(
    project_id: Annotated[
        str,
        Field(description="대상 프로젝트 ID (또는 'active')")
    ],
    relation_type: Annotated[
        str,
        Field(
            description=(
                "생성할 관계 타입 (MagicDraw ElementsFactory 메서드명 기준). "
                "UML 관계: 'Dependency', 'Association', 'Generalization', "
                "'Realization', 'Usage', 'Abstraction'. "
                "SysML 요구사항 관계: 'DeriveReqt' (파생 요구사항), "
                "'Satisfy' (설계 요소가 요구사항 충족), "
                "'Verify' (테스트가 요구사항 검증), "
                "'Trace' (추적), 'Copy' (복사), 'Refine' (세분화). "
                "SysML 관계는 Abstraction에 스테레오타입이 적용됩니다."
            )
        ),
    ],
    source_id: Annotated[
        str,
        Field(
            description="관계의 출발(client/source) 요소 ID (localID). NamedElement여야 합니다."
        ),
    ],
    target_id: Annotated[
        str,
        Field(
            description="관계의 도착(supplier/target) 요소 ID (localID). NamedElement여야 합니다."
        ),
    ],
    name: Annotated[
        Optional[str],
        Field(
            default=None,
            description="관계 이름 (선택 사항)"
        ),
    ] = None,
    owner_id: Annotated[
        Optional[str],
        Field(
            default=None,
            description=(
                "관계를 소유할 요소 ID (선택 사항). "
                "Connector는 일반적으로 IBD의 owner Block(상위 시스템)이 소유해야 합니다. "
                "생략 시 source 요소의 owner가 자동으로 사용됩니다."
            ),
        ),
    ] = None,
    source_part_id: Annotated[
        Optional[str],
        Field(
            default=None,
            description=(
                "Connector 전용 — source Port가 속한 Part Property ID. "
                "생략 시 자동 탐색: source Port의 owner Block이 Connector owner의 "
                "Part Property의 type과 일치하면 해당 Part Property를 자동 선택하고 "
                "ConnectorEnd에 «NestedConnectorEnd» 스테레오타입을 적용합니다. "
                "자동 탐색 결과가 의도와 다를 경우 명시적으로 지정하세요."
            ),
        ),
    ] = None,
    target_part_id: Annotated[
        Optional[str],
        Field(
            default=None,
            description=(
                "Connector 전용 — target Port가 속한 Part Property ID. "
                "생략 시 source_part_id와 동일한 조건으로 자동 탐색."
            ),
        ),
    ] = None,
) -> dict[str, Any]:
    """두 요소 사이에 관계(Relation)를 생성합니다.

    Dependency 계열 관계(Dependency, Usage, Realization, Abstraction)의 경우
    source가 client, target이 supplier로 설정됩니다.
    source와 target 요소는 모두 NamedElement 타입이어야 합니다.

    Connector는 SysML IBD에서 Port 간 연결에 사용됩니다.
    owner_id에 IBD와 동일한 Block(상위 시스템)을 지정해야 올바른 모델이 생성됩니다.

    Args:
        project_id:    대상 프로젝트 ID (또는 "active")
        relation_type: 관계 타입 (예: "Dependency", "Association", "Connector")
        source_id:     출발 요소 ID
        target_id:     도착 요소 ID
        name:          관계 이름 (선택 사항)
        owner_id:      관계 소유자 요소 ID (선택 사항, Connector에 권장)

    Returns:
        성공 (HTTP 201): {
            "id": str, "name": str, "type": str,
            "sourceId": str, "targetId": str
        }
        미존재: {"error": str, "message": str}  (source/target HTTP 404)
        읽기전용: {"error": str, "message": str}  (HTTP 403)
        실패:   {"error": str, "message": str}

    Examples:
        >>> catia_magic_create_relation(
        ...     project_id="active",
        ...     relation_type="Connector",
        ...     source_id="_port_a",
        ...     target_id="_port_b",
        ...     name="power_link",
        ...     owner_id="_system_block",
        ... )
        {
            "id": "_r1", "name": "power_link", "type": "Connector",
            "sourceId": "_port_a", "targetId": "_port_b"
        }
    """
    try:
        return await _get_bridge().create_relation(
            project_id, relation_type, source_id, target_id, name, owner_id,
            source_part_id, target_part_id
        )
    except Exception as e:
        _fail(e)


# ─────────────────────────────────────────────────────────────────────────────
# Relation Tools (추가)
# ─────────────────────────────────────────────────────────────────────────────

@mcp.tool(
    name="catia_magic_list_relations",
    annotations={
        "title": "CATIA Magic 관계 목록 조회",
        "readOnlyHint": True,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False,
    },
)
async def catia_magic_list_relations(
    project_id: Annotated[
        str,
        Field(
            description=(
                "대상 프로젝트 ID. catia_magic_list_projects로 확인하거나 "
                "'active'를 사용하면 현재 활성 프로젝트를 대상으로 합니다."
            )
        ),
    ],
) -> dict[str, Any]:
    """프로젝트 내 모든 관계(Relation) 목록을 반환합니다.

    모델 트리를 재귀 탐색(최대 깊이 10)하여 관계 타입 요소를 수집합니다.
    지원 타입: Dependency, Usage, Realization, Abstraction, Generalization,
              InterfaceRealization, Association, Connector,
              PackageMerge, PackageImport, InformationFlow, Deployment, Manifestation

    Args:
        project_id: 대상 프로젝트 ID (또는 "active")

    Returns:
        성공: {"relations": [{"id": str, "name": str, "type": str}, ...]}
        실패: {"error": str, "message": str}

    Examples:
        >>> catia_magic_list_relations(project_id="active")
        {"relations": [
            {"id": "_r1", "name": "uses", "type": "Dependency"},
            {"id": "_r2", "name": "", "type": "Generalization"}
        ]}
    """
    try:
        return await _get_bridge().list_relations(project_id)
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_delete_relation",
    annotations={
        "title": "CATIA Magic 관계 삭제",
        "readOnlyHint": False,
        "destructiveHint": True,
        "idempotentHint": False,
        "openWorldHint": False,
    },
)
async def catia_magic_delete_relation(
    project_id: Annotated[
        str,
        Field(description="대상 프로젝트 ID (또는 'active')")
    ],
    relation_id: Annotated[
        str,
        Field(
            description=(
                "삭제할 관계의 ID (localID). "
                "catia_magic_list_relations로 확인하세요."
            )
        ),
    ],
) -> dict[str, Any]:
    """프로젝트에서 관계(Relation)를 영구적으로 삭제합니다.

    이 작업은 되돌릴 수 없습니다.
    읽기 전용 관계는 삭제할 수 없습니다 (HTTP 403 반환).

    Args:
        project_id:  대상 프로젝트 ID (또는 "active")
        relation_id: 삭제할 관계 ID (localID)

    Returns:
        성공:     {"deleted": str}  (삭제된 relation_id)
        미존재:   {"error": str, "message": str}  (HTTP 404)
        읽기전용: {"error": str, "message": str}  (HTTP 403)
        실패:     {"error": str, "message": str}

    Examples:
        >>> catia_magic_delete_relation(project_id="active", relation_id="_r1")
        {"deleted": "_r1"}
    """
    try:
        return await _get_bridge().delete_relation(project_id, relation_id)
    except Exception as e:
        _fail(e)


# ─────────────────────────────────────────────────────────────────────────────
# Diagram Relation / Symbol Move Tools
# ─────────────────────────────────────────────────────────────────────────────

@mcp.tool(
    name="catia_magic_add_relation_to_diagram",
    annotations={
        "title": "CATIA Magic 관계 다이어그램 시각화",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": False,
        "openWorldHint": False,
    },
)
async def catia_magic_add_relation_to_diagram(
    project_id: Annotated[str, Field(description="대상 프로젝트 ID (또는 'active')")],
    diagram_id: Annotated[str, Field(description="관계를 표시할 다이어그램 ID")],
    relation_id: Annotated[str, Field(description="시각화할 관계 요소 ID (localID)")],
    client_symbol_id: Annotated[
        Optional[str],
        Field(
            default=None,
            description=(
                "source(client) 심볼 ID. "
                "생략 시 다이어그램에서 자동 탐색. "
                "동일 요소의 심볼이 여러 개면 후보 목록과 함께 오류 반환 — "
                "그 중 하나를 지정하여 재호출하세요."
            ),
        ),
    ] = None,
    supplier_symbol_id: Annotated[
        Optional[str],
        Field(
            default=None,
            description=(
                "target(supplier) 심볼 ID. "
                "생략 시 다이어그램에서 자동 탐색. "
                "동일 요소의 심볼이 여러 개면 후보 목록과 함께 오류 반환."
            ),
        ),
    ] = None,
    allow_duplicate: Annotated[
        bool,
        Field(
            default=False,
            description="True이면 이미 시각화된 관계라도 중복 생성 허용. 기본값 False.",
        ),
    ] = False,
) -> dict[str, Any]:
    """모델 관계(Relation)를 다이어그램에 선(PathElement)으로 표시합니다.

    source/target 심볼을 다이어그램에서 자동 탐색하여 PresentationElementsManager.createPathElement()로 연결합니다.
    탐색은 중첩 심볼(부모 심볼 내부)까지 재귀적으로 수행합니다.

    오류 케이스:
    - RELATION_NOT_FOUND: relation_id 요소 없음
    - DIAGRAM_NOT_FOUND: diagram_id 다이어그램 없음
    - SYMBOL_NOT_FOUND: source 또는 target 심볼이 다이어그램에 없음
    - ALREADY_VISUALIZED: 이미 선이 존재함 (allow_duplicate=True로 우회)
    - MULTIPLE_SYMBOLS: 동일 요소의 심볼이 복수 — candidates 목록 확인 후 심볼 ID 직접 지정

    Returns:
        성공 (HTTP 201): {"id": str, "relationId": str, "clientSymbolId": str, "supplierSymbolId": str}
        실패:            {"error": str, "message": str}

    Examples:
        >>> catia_magic_add_relation_to_diagram(
        ...     project_id="active",
        ...     diagram_id="_d1",
        ...     relation_id="_r1",
        ... )
        {"id": "_p1", "relationId": "_r1", "clientSymbolId": "_s1", "supplierSymbolId": "_s2"}
    """
    try:
        return await _get_bridge().add_relation_to_diagram(
            project_id,
            diagram_id,
            relation_id,
            client_symbol_id,
            supplier_symbol_id,
            allow_duplicate,
        )
    except Exception as e:
        _fail(e)


@mcp.tool(
    name="catia_magic_move_symbols",
    annotations={
        "title": "CATIA Magic 심볼 배치 이동",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": False,
        "openWorldHint": False,
    },
)
async def catia_magic_move_symbols(
    project_id: Annotated[str, Field(description="대상 프로젝트 ID (또는 'active')")],
    diagram_id: Annotated[str, Field(description="대상 다이어그램 ID")],
    symbol_ids: Annotated[
        list[str],
        Field(description="이동할 심볼 ID 목록. 부모 심볼과 자식 심볼이 함께 포함된 경우 자식은 자동 제외됩니다."),
    ],
    dx: Annotated[int, Field(description="x축 이동량 (픽셀, 양수=오른쪽, 음수=왼쪽)")],
    dy: Annotated[int, Field(description="y축 이동량 (픽셀, 양수=아래, 음수=위)")],
    push_adjacent: Annotated[
        Optional[bool],
        Field(
            default=False,
            description=(
                "부모 심볼이 확장될 때 인접한 최상위 심볼을 자동으로 밀어낼지 여부. "
                "기본값 False. True로 설정하면 겹침 방지를 위해 인접 심볼이 자동 이동됩니다. "
                "응답의 moved 배열에 밀려난 심볼도 포함됩니다."
            ),
        ),
    ] = False,
) -> dict[str, Any]:
    """여러 심볼을 (dx, dy)만큼 상대 이동합니다.

    동작 규칙:
    - symbol_ids에 부모와 자식이 함께 있으면 자식은 자동 제외 (부모 이동 시 자식이 따라가므로 이중 이동 방지)
    - 자식 심볼이 부모 경계를 벗어나면 부모를 자동 확장 (padding=20px)
    - 부모 확장으로 인접 최상위 심볼과 겹치면 인접 심볼을 밀어냄

    Returns:
        성공 (HTTP 200): {
            "moved":   [{"symbolId": str, "x": int, "y": int}],
            "skipped": [{"symbolId": str, "reason": str}],
            "resized": [{"symbolId": str, "width": int, "height": int}]
        }
        실패: {"error": str, "message": str}

    Examples:
        >>> catia_magic_move_symbols(
        ...     project_id="active",
        ...     diagram_id="_d1",
        ...     symbol_ids=["_s1", "_s2", "_s3"],
        ...     dx=100,
        ...     dy=50,
        ... )
        {"moved": [...], "skipped": [], "resized": []}
    """
    try:
        return await _get_bridge().move_symbols(
            project_id, diagram_id, symbol_ids, dx, dy, push_adjacent
        )
    except Exception as e:
        _fail(e)


# ─────────────────────────────────────────────────────────────────────────────
# 서버 진입점
# ─────────────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    mcp.run()
