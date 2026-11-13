# CATIA Magic MCP

An MCP (Model Context Protocol) server and bridge plugin for reading and editing
CATIA Magic (MagicDraw 2026x / Cyber Systems Engineer / Systems of Systems Architect) models.

Through this server, an MCP client such as Claude can work with projects, diagrams,
elements, relations, stereotype properties and dependency matrices in a running CATIA Magic instance.

## Architecture

```
MCP client (Claude) --stdio--> 320 mcp-server (Python, FastMCP)
                    --HTTP-->  310 bridge-plugin (Java, inside CATIA Magic, localhost:18080)
                    -->        MagicDraw Open API
```

| Folder | Description |
|---|---|
| `310 bridge-plugin/` | Java plugin running inside CATIA Magic. Exposes a local HTTP bridge on `localhost:18080` and reads/writes the model via the MagicDraw Open API. |
| `320 mcp-server/` | Python MCP server. `server.py` defines the MCP tools; `bridge_client.py` talks to the bridge plugin over HTTP. |

## Requirements

- CATIA Magic 2026x (tested with Cyber Systems Engineer 2026x Refresh1)
- JDK 11+ and Maven (to build the plugin)
- Python 3.10+

## 1. Build and install the bridge plugin

1. In `310 bridge-plugin/pom.xml`, set:
   - `magicdraw.install.dir` — your CATIA Magic install folder
   - `magicdraw.jar.suffix` — the version suffix of the jars in `<install>/lib`
     (look at the name of `core-*.jar`, e.g. `2026.1.0-42-b71a9669` for 2026x Refresh1)
2. Build:
   ```bash
   cd "310 bridge-plugin"
   mvn clean package
   ```
3. Copy `plugin.xml`, `target/mcp-bridge-plugin-1.0.0.jar` and `target/json-simple-1.1.1.jar`
   into `<install>/plugins/com.example.mcpbridge/`.
4. Restart CATIA Magic. The bridge listens on `http://localhost:18080`.

`deploy-restart.ps1` automates save → close → deploy → restart:

```powershell
powershell -ExecutionPolicy Bypass -File deploy-restart.ps1 -Deploy -Project "C:\path\to\model.mdzip" -InstallDir "C:\path\to\CATIA Magic"
```

## 2. Install the MCP server

```bash
cd "320 mcp-server"
pip install -r requirements.txt
```

Register it with your MCP client. For Claude Desktop, see
`320 mcp-server/claude_desktop_config_example.json`.

## MCP tools

Use `active` as `project_id` to target the currently open project.

| Area | Tools |
|---|---|
| Status / projects | `get_status`, `list_projects` |
| Elements | `list_elements`, `get_element`, `create_element`, `update_element`, `delete_element`, `reorder_attributes` |
| Stereotypes / profiles | `get_stereotype_properties`, `set_stereotype_property`, `get_profile` |
| Relations | `create_relation`, `list_relations`, `delete_relation` |
| Diagrams | `list_diagrams`, `get_diagram`, `create_diagram`, `export_diagram` (PNG) |
| Diagram symbols | `get_diagram_symbols`, `add_element_to_diagram`, `set_symbol_bounds`, `move_symbols`, `layout_diagram` |
| Paths / connectors | `add_relation_to_diagram`, `display_all_paths`, `get_diagram_connectors`, `reroute_connectors` |
| Matrices | `configure_matrix` (Satisfy / Verify / Derive / Refine matrices) |

All tool names are prefixed with `catia_magic_`.

### Notes

- New elements are revealed (expanded and selected) in the containment tree.
- `add_element_to_diagram` can auto-display ports for blocks and part properties.
- `display_all_paths` removes duplicate paths; containment links are off by default
  (`display_containment=true` to show them, e.g. for requirement hierarchies).
- `export_diagram` writes a PNG to the temp folder and returns its path, so the client can look at the diagram.
- `reorder_attributes` changes the order of a block's properties/ports (listed IDs go first, the rest keep their order).
- `layout_diagram`: `smart`/`compact` currently fall back to the standard quick layout (hierarchic).

## License

[MIT](LICENSE)

CATIA Magic / MagicDraw is a product of Dassault Systèmes. This project is not affiliated with
Dassault Systèmes, and no MagicDraw libraries are included in this repository.
