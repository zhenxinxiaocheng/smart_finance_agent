param(
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 3000,
    [switch]$Help
)

if ($Help) {
    Write-Host "Usage: .\start-dev.ps1 [-BackendPort 8080] [-FrontendPort 3000]"
    Write-Host ""
    Write-Host "Starts:"
    Write-Host "  backend : cd backend  ; mvn spring-boot:run"
    Write-Host "  frontend: cd frontend ; npm run dev"
    Write-Host ""
    Write-Host "Logs:"
    Write-Host "  .run-logs\backend.out.log"
    Write-Host "  .run-logs\backend.err.log"
    Write-Host "  .run-logs\frontend.out.log"
    Write-Host "  .run-logs\frontend.err.log"
    exit 0
}

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$BackendDir = Join-Path $Root "backend"
$FrontendDir = Join-Path $Root "frontend"
$LogDir = Join-Path $Root ".run-logs"

function Test-PortInUse {
    param([int]$Port)
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    return $null -ne $conn
}

function Find-FreePort {
    param([int]$StartPort)
    for ($port = $StartPort; $port -lt ($StartPort + 20); $port++) {
        if (-not (Test-PortInUse -Port $port)) {
            return $port
        }
    }
    throw "No free port found from $StartPort to $($StartPort + 19)."
}

if (-not (Test-Path $BackendDir)) {
    throw "Backend directory not found: $BackendDir"
}

if (-not (Test-Path $FrontendDir)) {
    throw "Frontend directory not found: $FrontendDir"
}

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

$ActualBackendPort = Find-FreePort -StartPort $BackendPort
$ActualFrontendPort = Find-FreePort -StartPort $FrontendPort
$ApiTarget = "http://localhost:$ActualBackendPort"

$RunId = Get-Date -Format "yyyyMMdd-HHmmss"
$BackendOut = Join-Path $LogDir "backend.$RunId.out.log"
$BackendErr = Join-Path $LogDir "backend.$RunId.err.log"
$FrontendOut = Join-Path $LogDir "frontend.$RunId.out.log"
$FrontendErr = Join-Path $LogDir "frontend.$RunId.err.log"
$PidFile = Join-Path $LogDir "dev-processes.json"

Write-Host "Starting backend on port $ActualBackendPort..."
$BackendProcess = Start-Process `
    -FilePath "mvn" `
    -ArgumentList @("spring-boot:run", "-Dspring-boot.run.arguments=--server.port=$ActualBackendPort") `
    -WorkingDirectory $BackendDir `
    -WindowStyle Hidden `
    -RedirectStandardOutput $BackendOut `
    -RedirectStandardError $BackendErr `
    -PassThru

Write-Host "Starting frontend on port $ActualFrontendPort..."
$FrontendCommand = "`$env:VITE_API_TARGET='$ApiTarget'; npm run dev -- --host 127.0.0.1 --port $ActualFrontendPort"
$FrontendProcess = Start-Process `
    -FilePath "powershell" `
    -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $FrontendCommand) `
    -WorkingDirectory $FrontendDir `
    -WindowStyle Hidden `
    -RedirectStandardOutput $FrontendOut `
    -RedirectStandardError $FrontendErr `
    -PassThru

$ProcessInfo = [ordered]@{
    backend = [ordered]@{
        pid = $BackendProcess.Id
        port = $ActualBackendPort
        url = $ApiTarget
        stdout = $BackendOut
        stderr = $BackendErr
    }
    frontend = [ordered]@{
        pid = $FrontendProcess.Id
        port = $ActualFrontendPort
        url = "http://127.0.0.1:$ActualFrontendPort"
        stdout = $FrontendOut
        stderr = $FrontendErr
        apiTarget = $ApiTarget
    }
}

$ProcessInfo | ConvertTo-Json -Depth 4 | Set-Content -Path $PidFile -Encoding UTF8

Write-Host ""
Write-Host "Started."
Write-Host "  Backend : $ApiTarget        PID $($BackendProcess.Id)"
Write-Host "  Frontend: http://127.0.0.1:$ActualFrontendPort  PID $($FrontendProcess.Id)"
Write-Host "  PID file: $PidFile"
Write-Host ""
Write-Host "Tail logs:"
Write-Host "  Get-Content `"$BackendOut`" -Tail 80 -Wait"
Write-Host "  Get-Content `"$FrontendOut`" -Tail 80 -Wait"
