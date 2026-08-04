$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot

function Read-EmbeddedScript {
    param([Parameter(Mandatory)][string]$Path)

    $bytes = [IO.File]::ReadAllBytes($Path)
    $ascii = [Text.Encoding]::ASCII.GetString($bytes)
    $match = [regex]::Match($ascii, '-EncodedCommand\s+([A-Za-z0-9+/=]+)')
    if (-not $match.Success) {
        throw "EncodedCommand was not found in $Path"
    }
    return [Text.Encoding]::Unicode.GetString([Convert]::FromBase64String($match.Groups[1].Value))
}

function Assert-Contains {
    param([string]$Text, [string]$Expected, [string]$FileName)
    if (-not $Text.Contains($Expected)) {
        throw "$FileName is missing: $Expected"
    }
}

function Assert-NotContains {
    param([string]$Text, [string]$Unexpected, [string]$FileName)
    if ($Text.Contains($Unexpected)) {
        throw "$FileName must not contain: $Unexpected"
    }
}

$startFile = "$([char]0x542F)$([char]0x52A8).exe"
$stopFile = "$([char]0x5173)$([char]0x95ED).exe"
$start = Read-EmbeddedScript (Join-Path $Root $startFile)
$stop = Read-EmbeddedScript (Join-Path $Root $stopFile)
$startDev = Get-Content (Join-Path $Root 'start-dev.ps1') -Raw

Assert-Contains $start 'BackendPort=8088' $startFile
Assert-Contains $start 'FrontendPort=3000' $startFile
Assert-Contains $start 'AnalysisPort=8090' $startFile
Assert-Contains $start 'StartupTimeoutSeconds=240' $startFile
Assert-Contains $start 'start-dev.ps1' $startFile
Assert-Contains $start '-AnalysisPort $AnalysisPort' $startFile
Assert-Contains $start '-StartupTimeoutSeconds $StartupTimeoutSeconds' $startFile
Assert-Contains $startDev '[int]$StartupTimeoutSeconds = 240' 'start-dev.ps1'
Assert-Contains $startDev '.AddSeconds($StartupTimeoutSeconds)' 'start-dev.ps1'
Assert-Contains $startDev 'Stop-ProcessTree -Id $process.Id' 'start-dev.ps1'
Assert-Contains $startDev 'Stop-ProcessTree -Id $processId' 'start-dev.ps1'
Assert-Contains $startDev '$BackendProcess.Refresh()' 'start-dev.ps1'
Assert-Contains $startDev '$FrontendProcess.Refresh()' 'start-dev.ps1'

Assert-Contains $stop 'BackendPort=8088' $stopFile
Assert-Contains $stop 'FrontendPort=3000' $stopFile
Assert-Contains $stop 'AnalysisPort=8090' $stopFile
Assert-Contains $stop '$info.analysis.pid' $stopFile
Assert-Contains $stop 'Stop-Port $AnalysisPort "analysis"' $stopFile
Assert-Contains $stop 'netstat.exe" -ano -p tcp' $stopFile
Assert-NotContains $stop 'Get-NetTCPConnection' $stopFile

Assert-NotContains $start 'BackendPort=8080' $startFile
Assert-NotContains $stop 'BackendPort=8080' $stopFile

$sandbox = Join-Path $env:TEMP ("launcher-stop-test-" + [Guid]::NewGuid().ToString('N'))
$sandboxLogs = Join-Path $sandbox '.run-logs'
$sandboxPidFile = Join-Path $sandboxLogs 'dev-processes.json'
New-Item -ItemType Directory -Force -Path $sandboxLogs | Out-Null
'{"analysis":{"pid":0},"backend":{"pid":0},"frontend":{"pid":0}}' |
    Set-Content -Path $sandboxPidFile -Encoding UTF8
Push-Location $sandbox
try {
    & ([scriptblock]::Create($stop)) -BackendPort 48088 -FrontendPort 43000 -AnalysisPort 48090
    if (Test-Path $sandboxPidFile) {
        throw 'stop launcher did not remove its PID file after all ports were free'
    }
} finally {
    Pop-Location
    Remove-Item -LiteralPath $sandbox -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host 'PASS: both launchers contain the 3000/8088/8090 three-process lifecycle.'
