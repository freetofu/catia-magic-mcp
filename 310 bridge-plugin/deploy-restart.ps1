# Save all open projects -> close MagicDraw -> (optional) deploy plugin -> start -> wait for bridge.
# Usage: powershell -ExecutionPolicy Bypass -File deploy-restart.ps1 [-Deploy] [-Project <mdzip>] [-InstallDir <dir>]
# No Read-Host: safe to run unattended.
param(
    [switch]$Deploy,
    [Parameter(Mandatory = $true)][string]$Project,
    [string]$InstallDir = "D:\Program Files\Magic Cyber Systems Engineer 2026x Refresh1",
    [string]$Exe = "mcse.exe",
    [int]$TimeoutSec = 300
)
$ErrorActionPreference = "Stop"
$bridge = "http://localhost:18080"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

function Get-Projects {
    try { (Invoke-RestMethod "$bridge/projects" -TimeoutSec 5).projects } catch { $null }
}

# 1) save every open project; abort if any save fails (never kill unsaved work)
$projects = Get-Projects
if ($projects) {
    foreach ($p in $projects) {
        $r = Invoke-RestMethod -Method Post "$bridge/project/$($p.id)/save" -TimeoutSec 120
        if ($r.status -ne "saved") { throw "SAVE FAILED: $($p.name) -> abort, MagicDraw left running" }
        "saved: $($p.name)"
    }
}

# 2) close MagicDraw (owner of port 18080, else javaw under InstallDir)
$procId = (Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue).OwningProcess | Select-Object -First 1
if (-not $procId) {
    $procId = (Get-Process javaw -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$InstallDir*" } | Select-Object -First 1).Id
}
if ($procId) {
    $proc = Get-Process -Id $procId
    $null = $proc.CloseMainWindow()
    if (-not $proc.WaitForExit(60000)) { Stop-Process -Id $procId -Force; "force-stopped pid $procId" } else { "closed pid $procId" }
    Start-Sleep -Seconds 3
}

# 3) deploy plugin files (backup old jar once per day)
if ($Deploy) {
    $dst = Join-Path $InstallDir "plugins\com.example.mcpbridge"
    $jar = Join-Path $dst "mcp-bridge-plugin-1.0.0.jar"
    $bak = "$jar.bak-" + (Get-Date -Format "yyyyMMdd")
    if ((Test-Path $jar) -and -not (Test-Path $bak)) { Copy-Item $jar $bak }
    Copy-Item "$here\plugin.xml", "$here\target\mcp-bridge-plugin-1.0.0.jar", "$here\target\json-simple-1.1.1.jar" $dst -Force
    "deployed to $dst"
}

# 4) start with project and wait until the bridge lists it
Start-Process -FilePath (Join-Path $InstallDir "bin\$Exe") -ArgumentList "`"$Project`"" -WorkingDirectory (Join-Path $InstallDir "bin")
$name = [IO.Path]::GetFileNameWithoutExtension($Project)
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$bridgeUp = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    try { $open = (Invoke-RestMethod "$bridge/projects" -TimeoutSec 5).projects; $bridgeUp = $true } catch { continue }
    # ponytail: project is listed before loading finishes (stereotype data came back empty); fixed wait, poll a real model check if 15s is not enough
    if ($open | Where-Object { $_.name -eq $name }) { Start-Sleep -Seconds 15; "READY: $name"; exit 0 }
}
if ($bridgeUp) { "BRIDGE UP but project '$name' not open"; exit 2 }
"TIMEOUT: bridge not up after $TimeoutSec s"; exit 1
