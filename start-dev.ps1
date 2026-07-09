param(
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 3000,
    [switch]$Help
)

if ($Help) {
    Write-Host "Usage: .\start-dev.ps1 [-BackendPort 8080] [-FrontendPort 3000]"
    Write-Host ""
    Write-Host "Starts:"
    Write-Host "  backend : closes BackendPort first, then runs mvn spring-boot:run"
    Write-Host "  frontend: closes FrontendPort first, then runs npm run dev -- --strictPort"
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

function Get-PortListenerProcessIds {
    param([int]$Port)

    $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($null -eq $connections) {
        return @()
    }

    return @(
        $connections |
            Where-Object { $_.OwningProcess -and $_.OwningProcess -ne 0 } |
            Select-Object -ExpandProperty OwningProcess -Unique
    )
}

function Wait-PortReleased {
    param(
        [int]$Port,
        [int]$TimeoutSeconds = 10
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (-not (Test-PortInUse -Port $Port)) {
            return $true
        }
        Start-Sleep -Milliseconds 300
    }

    return -not (Test-PortInUse -Port $Port)
}

function Stop-PortListeners {
    param(
        [int]$Port,
        [string]$Name
    )

    $listenerIds = Get-PortListenerProcessIds -Port $Port
    if ($listenerIds.Count -eq 0) {
        Write-Host "$Name port $Port is free."
        return
    }

    Write-Host "Closing $Name port ${Port}: PID $($listenerIds -join ', ')"
    foreach ($processId in $listenerIds) {
        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if ($null -ne $process) {
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        }
    }

    if (-not (Wait-PortReleased -Port $Port)) {
        throw "$Name port $Port is still in use after stopping listener process(es)."
    }
}

if (-not (Test-Path $BackendDir)) {
    throw "Backend directory not found: $BackendDir"
}

if (-not (Test-Path $FrontendDir)) {
    throw "Frontend directory not found: $FrontendDir"
}

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

Stop-PortListeners -Port $BackendPort -Name "backend"
Stop-PortListeners -Port $FrontendPort -Name "frontend"

$ActualBackendPort = $BackendPort
$ActualFrontendPort = $FrontendPort
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
$FrontendShell = if (Get-Command "pwsh" -ErrorAction SilentlyContinue) { "pwsh" } else { "powershell" }
$FrontendCommand = "`$env:VITE_API_TARGET='$ApiTarget'; npm run dev -- --host 127.0.0.1 --port $ActualFrontendPort --strictPort"
$FrontendProcess = Start-Process `
    -FilePath $FrontendShell `
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
