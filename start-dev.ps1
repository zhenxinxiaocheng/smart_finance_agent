param(
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 3000,
    [int]$AnalysisPort = 8090,
    [int]$StartupTimeoutSeconds = 240,
    [switch]$Help
)

if ($Help) {
    Write-Host "Usage: .\start-dev.ps1 [-BackendPort 8080] [-FrontendPort 3000] [-AnalysisPort 8090] [-StartupTimeoutSeconds 240]"
    Write-Host ""
    Write-Host "Starts:"
    Write-Host "  backend : closes BackendPort first, then runs mvn spring-boot:run"
    Write-Host "  frontend: closes FrontendPort first, then runs npm run dev -- --strictPort"
    Write-Host "  analysis: closes AnalysisPort first, then runs FastAPI on 127.0.0.1"
    Write-Host ""
    Write-Host "Logs:"
    Write-Host "  .run-logs\backend.out.log"
    Write-Host "  .run-logs\backend.err.log"
    Write-Host "  .run-logs\frontend.out.log"
    Write-Host "  .run-logs\frontend.err.log"
    Write-Host "  .run-logs\analysis.out.log"
    Write-Host "  .run-logs\analysis.err.log"
    exit 0
}

$ErrorActionPreference = "Stop"

if ($StartupTimeoutSeconds -lt 1) {
    throw "StartupTimeoutSeconds must be a positive integer."
}

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$BackendDir = Join-Path $Root "backend"
$FrontendDir = Join-Path $Root "frontend"
$AnalysisDir = Join-Path $Root "analysis-service"
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

function Wait-HttpReady {
    param([string]$Uri, [int]$TimeoutSeconds = 30)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-RestMethod -Uri $Uri -TimeoutSec 2
            if ($response.status -eq "UP") { return $true }
        } catch {}
        Start-Sleep -Milliseconds 400
    }
    return $false
}

function Stop-ProcessTree {
    param([int]$Id)

    if ($Id -le 0 -or $null -eq (Get-Process -Id $Id -ErrorAction SilentlyContinue)) {
        return
    }

    & "$env:SystemRoot\System32\taskkill.exe" /PID $Id /T /F 2>$null | Out-Null
    if ($null -ne (Get-Process -Id $Id -ErrorAction SilentlyContinue)) {
        Stop-Process -Id $Id -Force -ErrorAction SilentlyContinue
    }
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

if (-not (Test-Path $AnalysisDir)) {
    throw "Analysis service directory not found: $AnalysisDir"
}

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

Stop-PortListeners -Port $BackendPort -Name "backend"
Stop-PortListeners -Port $FrontendPort -Name "frontend"
Stop-PortListeners -Port $AnalysisPort -Name "analysis"

$ActualBackendPort = $BackendPort
$ActualFrontendPort = $FrontendPort
$ActualAnalysisPort = $AnalysisPort
$ApiTarget = "http://localhost:$ActualBackendPort"
$AnalysisUrl = "http://127.0.0.1:$ActualAnalysisPort"
$AnalysisToken = [Guid]::NewGuid().ToString("N")

$RunId = Get-Date -Format "yyyyMMdd-HHmmss"
$BackendOut = Join-Path $LogDir "backend.$RunId.out.log"
$BackendErr = Join-Path $LogDir "backend.$RunId.err.log"
$FrontendOut = Join-Path $LogDir "frontend.$RunId.out.log"
$FrontendErr = Join-Path $LogDir "frontend.$RunId.err.log"
$AnalysisOut = Join-Path $LogDir "analysis.$RunId.out.log"
$AnalysisErr = Join-Path $LogDir "analysis.$RunId.err.log"
$PidFile = Join-Path $LogDir "dev-processes.json"
Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue

$VenvPython = Join-Path $AnalysisDir ".venv\Scripts\python.exe"
$PythonCommand = if (Test-Path $VenvPython) { $VenvPython } else {
    $Python = Get-Command "python" -ErrorAction SilentlyContinue
    if ($null -eq $Python) { throw "Python not found. Create analysis-service\.venv and install requirements.txt first." }
    $Python.Source
}

Write-Host "Starting analysis service on port $ActualAnalysisPort..."
$AnalysisShell = if (Get-Command "pwsh" -ErrorAction SilentlyContinue) { "pwsh" } else { "powershell" }
$AnalysisCommand = "`$env:ANALYSIS_INTERNAL_TOKEN='$AnalysisToken'; & '$PythonCommand' -m uvicorn app.main:app --host 127.0.0.1 --port $ActualAnalysisPort"
$AnalysisProcess = Start-Process `
    -FilePath $AnalysisShell `
    -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $AnalysisCommand) `
    -WorkingDirectory $AnalysisDir `
    -WindowStyle Hidden `
    -RedirectStandardOutput $AnalysisOut `
    -RedirectStandardError $AnalysisErr `
    -PassThru

if (-not (Wait-HttpReady -Uri "$AnalysisUrl/health")) {
    Stop-ProcessTree -Id $AnalysisProcess.Id
    $details = if (Test-Path $AnalysisErr) { (Get-Content $AnalysisErr -Tail 20) -join [Environment]::NewLine } else { "No error log" }
    throw "Analysis service health check failed.`n$details"
}

Write-Host "Starting backend on port $ActualBackendPort..."
$env:ANALYSIS_SERVICE_URL = $AnalysisUrl
$env:ANALYSIS_INTERNAL_TOKEN = $AnalysisToken
$BackendProcess = Start-Process `
    -FilePath "mvn" `
    -ArgumentList @("spring-boot:run", "-Dspring-boot.run.profiles=mysql", "-Dspring-boot.run.arguments=--server.port=$ActualBackendPort") `
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

$startupDeadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
while ((Get-Date) -lt $startupDeadline -and
       (-not (Test-PortInUse -Port $ActualBackendPort) -or -not (Test-PortInUse -Port $ActualFrontendPort))) {
    if ($BackendProcess.HasExited -or $FrontendProcess.HasExited) { break }
    Start-Sleep -Milliseconds 500
}
if (-not (Test-PortInUse -Port $ActualBackendPort) -or -not (Test-PortInUse -Port $ActualFrontendPort)) {
    foreach ($process in @($AnalysisProcess, $BackendProcess, $FrontendProcess)) {
        Stop-ProcessTree -Id $process.Id
    }
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
    throw "Backend or frontend startup check failed. Inspect logs in $LogDir"
}

$ProcessInfo = [ordered]@{
    analysis = [ordered]@{
        pid = $AnalysisProcess.Id
        port = $ActualAnalysisPort
        url = $AnalysisUrl
        stdout = $AnalysisOut
        stderr = $AnalysisErr
    }
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
Write-Host "  Analysis: $AnalysisUrl  PID $($AnalysisProcess.Id)"
Write-Host "  PID file: $PidFile"
Write-Host ""
Write-Host "Tail logs:"
Write-Host "  Get-Content `"$BackendOut`" -Tail 80 -Wait"
Write-Host "  Get-Content `"$FrontendOut`" -Tail 80 -Wait"
Write-Host "  Get-Content `"$AnalysisOut`" -Tail 80 -Wait"
