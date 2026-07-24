# 테스트 케이스: 스테레오타입 속성 읽기/쓰기 & 다이어그램 심볼 배치/레이아웃

## 사전 조건
- CATIA Magic 2026x 실행 중
- MCP Bridge Plugin 활성화 (localhost:18080 응답 확인)
- build.bat 빌드 후 플러그인 배포 완료
- Claude Desktop 재시작 완료 (새 MCP Tool 스키마 반영)

---

## 테스트 그룹 A — 스테레오타입 속성 읽기 (GET props)

### A-1. «block» 스테레오타입 속성 조회

**목적**: Block 스테레오타입이 적용된 요소에서 속성 목록과 값을 읽는다.

**준비**:
1. «block» 스테레오타입을 가진 Class 요소 "TestBlock" 생성
   ```
   catia_magic_create_element(project_id="active", element_type="Class",
                               name="TestBlock", stereotype="Block")
   ```
2. 반환된 element_id 기록

**테스트**:
```
catia_magic_get_stereotype_properties(project_id="active", element_id="<위 id>")
```

**기대 결과**:
```json
{
  "elementId": "<id>",
  "elementName": "TestBlock",
  "stereotypes": [
    {
      "name": "Block",
      "properties": [
        {"name": "isEncapsulated", "value": "false"}
      ]
    }
  ]
}
```

**검증 포인트**:
- `stereotypes` 배열에 "Block" 항목 존재
- `properties` 배열에 `isEncapsulated` 속성 존재
- 오류 없이 응답 반환

---

### A-2. «Requirement» 스테레오타입 속성 조회

**목적**: Requirement 스테레오타입의 Id, Text 속성을 읽는다.

**준비**:
```
catia_magic_create_element(project_id="active", element_type="Class",
                            name="TestReq", stereotype="Requirement")
```

**테스트**:
```
catia_magic_get_stereotype_properties(project_id="active", element_id="<id>")
```

**기대 결과**:
- `stereotypes[0].name` = "Requirement"
- `properties` 에 "Id", "Text" 항목 포함

---

## 테스트 그룹 B — 스테레오타입 속성 쓰기 (PUT props)

### B-1. Requirement Id 속성 설정

**목적**: Requirement 스테레오타입의 Id 속성에 값을 쓴다.

**준비**: A-2 테스트의 TestReq 요소 사용 (element_id 보유)

**테스트**:
```
catia_magic_set_stereotype_property(
    project_id="active",
    element_id="<TestReq id>",
    stereotype="Requirement",
    property_name="Id",
    value="REQ-001"
)
```

**기대 결과**:
```json
{
  "elementId": "<id>",
  "stereotype": "Requirement",
  "property": "Id",
  "value": "REQ-001"
}
```

**검증 포인트**:
- 오류 없이 200 응답
- CATIA Magic UI에서 TestReq 요소 속성 패널 → Id 값이 "REQ-001"로 표시

---

### B-2. 속성 쓰기 후 읽기로 확인

**목적**: 쓴 값이 실제로 반영되는지 읽기로 재확인.

**테스트**:
```
catia_magic_get_stereotype_properties(project_id="active", element_id="<TestReq id>")
```

**기대 결과**:
- `Id` 속성의 `value` = "REQ-001"

---

## 테스트 그룹 C — 다이어그램 심볼 목록 조회 (GET symbols)

### C-1. BDD 다이어그램 심볼 조회

**목적**: 다이어그램에 배치된 심볼의 좌표와 크기를 읽는다.

**준비**:
1. BDD 다이어그램 생성: `catia_magic_create_diagram(type="bdd", name="TestBDD")`
2. diagram_id 기록
3. CATIA Magic UI에서 TestBDD를 열고 요소를 몇 개 수동으로 배치

**테스트**:
```
catia_magic_get_diagram_symbols(project_id="active", diagram_id="<did>")
```

**기대 결과**:
```json
{
  "diagramId": "<did>",
  "symbols": [
    {"id": "<sid>", "elementId": "<eid>", "elementName": "...",
     "x": 100, "y": 200, "width": 120, "height": 60}
  ]
}
```

**검증 포인트**:
- `symbols` 배열에 UI에서 배치한 요소 수만큼 항목 존재
- x, y, width, height 가 0이 아닌 실제 좌표값

---

## 테스트 그룹 D — 다이어그램에 심볼 추가 (POST symbols)

### D-1. 요소를 다이어그램에 좌표 지정 배치

**목적**: 모델 요소를 특정 좌표에 다이어그램 심볼로 추가한다.

**준비**:
1. 요소 생성: `catia_magic_create_element(type="Class", name="Engine", stereotype="Block")`
2. BDD 다이어그램 생성: `catia_magic_create_diagram(type="bdd", name="LayoutTest")`

**테스트**:
```
catia_magic_add_element_to_diagram(
    project_id="active",
    diagram_id="<did>",
    element_id="<Engine id>",
    x=100, y=200
)
```

**기대 결과**:
- 201 응답
- `elementName` = "Engine"
- CATIA Magic UI에서 다이어그램 열면 (100, 200) 근처에 Engine 블록 표시

---

### D-2. 좌표 없이 자동 배치

**테스트**:
```
catia_magic_add_element_to_diagram(
    project_id="active",
    diagram_id="<did>",
    element_id="<Wheel id>"
)
```

**기대 결과**:
- 201 응답 + MagicDraw가 자동으로 배치한 x, y 반환

---

## 테스트 그룹 E — 심볼 위치·크기 변경 (PUT symbols/{sid})

### E-1. 심볼 좌표 이동

**목적**: 배치된 심볼을 새 좌표로 이동한다.

**준비**: D-1 테스트에서 추가된 심볼 id 확인 (GET symbols 로 sid 조회)

**테스트**:
```
catia_magic_set_symbol_bounds(
    project_id="active",
    diagram_id="<did>",
    symbol_id="<sid>",
    x=400, y=300
)
```

**기대 결과**:
```json
{"symbolId": "<sid>", "x": 400, "y": 300, "width": 120, "height": 60}
```

**검증 포인트**:
- CATIA Magic UI에서 해당 심볼이 (400, 300)으로 이동 확인

---

## 테스트 그룹 F — 자동 레이아웃 (POST layout)

### F-1. Hierarchic 레이아웃 적용

**목적**: 심볼이 여러 개 있는 다이어그램에 계층형 레이아웃을 적용한다.

**준비**: D 테스트에서 요소 3~4개를 배치한 다이어그램 사용

**테스트**:
```
catia_magic_layout_diagram(
    project_id="active",
    diagram_id="<did>",
    algorithm="hierarchic"
)
```

**기대 결과**:
```json
{"diagramId": "<did>", "algorithm": "hierarchic", "status": "ok"}
```

**검증 포인트**:
- CATIA Magic UI에서 심볼이 자동 재배치되어 겹치지 않음
- 오류 없이 응답

---

### F-2. Organic 레이아웃 적용 (IBD 커넥터 교차 최소화)

**목적**: 커넥터가 있는 다이어그램에 유기적 레이아웃을 적용하여 교차를 줄인다.

**테스트**:
```
catia_magic_layout_diagram(
    project_id="active",
    diagram_id="<did>",
    algorithm="organic"
)
```

**기대 결과**:
- `status` = "ok"
- 다이어그램에서 커넥터 교차 감소 확인

---

### F-3. 알고리즘 생략 (기본값 hierarchic)

**테스트**:
```
catia_magic_layout_diagram(project_id="active", diagram_id="<did>")
```

**기대 결과**:
```json
{"diagramId": "<did>", "algorithm": "hierarchic", "status": "ok"}
```

---

## 오류 케이스 테스트

| 케이스 | 입력 | 기대 응답 |
|--------|------|-----------|
| 없는 element_id로 props 조회 | `element_id="INVALID"` | `{"error": ...}` + 404 |
| 없는 stereotype으로 속성 쓰기 | `stereotype="없는스테레오타입"` | `{"error": ...}` + 404 |
| 없는 diagram_id로 symbols 조회 | `diagram_id="INVALID"` | `{"error": ...}` + 404 |
| 없는 symbol_id로 bounds 변경 | `symbol_id="INVALID"` | `{"error": ...}` + 404 |
| 잘못된 algorithm 값 | `algorithm="invalid"` | hierarchic으로 fallback 적용 |
