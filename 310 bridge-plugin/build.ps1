# UTF-8 encoding: Maven output + clipboard (Korean Windows CP949 -> force UTF-8)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
$OutputEncoding           = [System.Text.Encoding]::UTF8
$env:JAVA_TOOL_OPTIONS    = "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8"

$MAGICDRAW_DIR = "D:\Program Files\Magic Systems of Systems Architect 26x HF1"
$PLUGIN_DIR = "$MAGICDRAW_DIR\plugins\com.example.mcpbridge"
$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $SCRIPT_DIR

$log = @()
$log += "============================================================"
$log += " CATIA Magic MCP Bridge Plugin - Build + Deploy"
$log += "============================================================"
$log += ""

Write-Host "============================================================"
Write-Host " CATIA Magic MCP Bridge Plugin - Build + Deploy"
Write-Host "============================================================"
Write-Host ""

# [1/3] Build
Write-Host "[1/3] Building..."
$log += "[1/3] Building..."

$mvnResult = & mvn clean package -e 2>&1
$mvnOutput = $mvnResult | Out-String

$success = $mvnOutput -match "BUILD SUCCESS"
$buildTime = ($mvnOutput -split "`n" | Where-Object { $_ -match "Total time|Finished at" }) -join "`n"

if (-not $success) {
    $errors = ($mvnOutput -split "`n" | Where-Object { $_ -match "\[ERROR\]" }) -join "`n"
    $log += "[FAILED] Build failed!"
    $log += $errors
    $result = $log -join "`n"
    $result | Set-Clipboard
    Write-Host "[FAILED] Build failed!" -ForegroundColor Red
    Write-Host $errors
    Write-Host ""
    Write-Host "Result copied to clipboard."
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "[SUCCESS] Build OK" -ForegroundColor Green
$log += "[SUCCESS] Build OK"
$log += $buildTime
$log += ""

# [2/3] Deploy
Write-Host ""
Write-Host "[2/3] Deploying..."
Write-Host "  To: $PLUGIN_DIR"
$log += "[2/3] Deploying..."
$log += "  To: $PLUGIN_DIR"

if (-not (Test-Path $PLUGIN_DIR)) {
    Write-Host "  Creating plugin directory..."
    $log += "  Creating plugin directory..."
    try {
        New-Item -ItemType Directory -Path $PLUGIN_DIR -Force | Out-Null
    } catch {
        $log += "[ERROR] Cannot create plugin dir: $PLUGIN_DIR"
        $log += "Run as Administrator."
        ($log -join "`n") | Set-Clipboard
        Write-Host "[ERROR] Cannot create plugin dir. Run as Administrator." -ForegroundColor Red
        Read-Host "Press Enter to exit"
        exit 1
    }
}

$files = @(
    @{ src = "plugin.xml"; dst = "$PLUGIN_DIR\plugin.xml" },
    @{ src = "target\mcp-bridge-plugin-1.0.0.jar"; dst = "$PLUGIN_DIR\mcp-bridge-plugin-1.0.0.jar" },
    @{ src = "target\json-simple-1.1.1.jar"; dst = "$PLUGIN_DIR\json-simple-1.1.1.jar" }
)

foreach ($f in $files) {
    try {
        Copy-Item $f.src $f.dst -Force
        Write-Host "  $($f.src) ... OK" -ForegroundColor Green
        $log += "  $($f.src) ... OK"
    } catch {
        $msg = "[ERROR] Failed to copy: $($f.src)"
        Write-Host $msg -ForegroundColor Red
        $log += $msg
        ($log -join "`n") | Set-Clipboard
        Read-Host "Press Enter to exit"
        exit 1
    }
}

# [3/3] Verify
Write-Host ""
Write-Host "[3/3] Deployed files:"
$log += ""
$log += "[3/3] Deployed files:"

$deployed = Get-ChildItem $PLUGIN_DIR | Select-Object -ExpandProperty Name
foreach ($f in $deployed) {
    Write-Host "  $f"
    $log += "  $f"
}

$log += ""
$log += "============================================================"
$log += " [DONE] Build + Deploy complete!"
$log += " Restart MagicDraw to apply changes."
$log += "============================================================"

Write-Host ""
Write-Host "============================================================"
Write-Host " [DONE] Build + Deploy complete!" -ForegroundColor Green
Write-Host " Restart MagicDraw to apply changes."
Write-Host "============================================================"
Write-Host ""

$result = $log -join "`n"
$result | Set-Clipboard
Write-Host "Result copied to clipboard. Paste into Claude chat (Ctrl+V)."
Write-Host ""
Read-Host "Press Enter to exit"
