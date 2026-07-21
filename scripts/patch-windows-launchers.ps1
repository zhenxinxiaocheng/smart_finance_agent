$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$startFile = Join-Path $Root "$([char]0x542F)$([char]0x52A8).exe"
$stopFile = Join-Path $Root "$([char]0x5173)$([char]0x95ED).exe"

$startScript = @'
param([int]$BackendPort=8088,[int]$FrontendPort=3000,[int]$AnalysisPort=8090,[int]$StartupTimeoutSeconds=240,[switch]$Help)
if($Help){Write-Host "Starts frontend, Java backend, and Python analysis service.";exit 0}
$ErrorActionPreference="Stop"
$Root=(Get-Location).ProviderPath
$StartScript=Join-Path $Root "start-dev.ps1"
if(-not(Test-Path $StartScript)){throw "start-dev.ps1 not found: $StartScript"}
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $StartScript -BackendPort $BackendPort -FrontendPort $FrontendPort -AnalysisPort $AnalysisPort -StartupTimeoutSeconds $StartupTimeoutSeconds
if($LASTEXITCODE -ne 0){throw "start-dev.ps1 failed with exit code $LASTEXITCODE"}
$Url="http://127.0.0.1:$FrontendPort/stocks"
Start-Process $Url
'@

$stopScript = @'
param([int]$BackendPort=8088,[int]$FrontendPort=3000,[int]$AnalysisPort=8090)
$ErrorActionPreference="Continue"
$Root=(Get-Location).ProviderPath
$PidFile=Join-Path (Join-Path $Root ".run-logs") "dev-processes.json"
function Stop-Tree([int]$Id){
 if($Id -le 0){return}
 $p=Get-Process -Id $Id -ErrorAction SilentlyContinue
 if($null -eq $p){return}
 $children=Get-CimInstance Win32_Process -Filter "ParentProcessId=$Id" -ErrorAction SilentlyContinue
 foreach($child in $children){Stop-Tree ([int]$child.ProcessId)}
 Stop-Process -Id $Id -Force -ErrorAction SilentlyContinue
}
function Get-ListenerPids([int]$Port){
 $ids=@()
 foreach($line in (& "$env:SystemRoot\System32\netstat.exe" -ano -p tcp)){
  if($line -match "^\s*TCP\s+\S+:$Port\s+\S+\s+\S+\s+(\d+)\s*$"){$ids+=[int]$matches[1]}
 }
 return @($ids|Where-Object{$_ -ne 0}|Select-Object -Unique)
}
function Stop-Port([int]$Port,[string]$Name){
 foreach($id in (Get-ListenerPids $Port)){Stop-Tree ([int]$id)}
}
Write-Host "Stopping frontend, Java backend, and Python analysis service..."
if(Test-Path $PidFile){
 try{
  $info=Get-Content $PidFile -Raw|ConvertFrom-Json
  if($info.analysis.pid){Stop-Tree ([int]$info.analysis.pid)}
  if($info.frontend.pid){Stop-Tree ([int]$info.frontend.pid)}
  if($info.backend.pid){Stop-Tree ([int]$info.backend.pid)}
 }catch{Write-Host "PID file could not be read; using port fallback."}
}
Stop-Port $AnalysisPort "analysis"
Stop-Port $FrontendPort "frontend"
Stop-Port $BackendPort "backend"
Start-Sleep -Milliseconds 700
$left=@(Get-ListenerPids $AnalysisPort;Get-ListenerPids $FrontendPort;Get-ListenerPids $BackendPort)
if($left.Count -eq 0){
 if(Test-Path $PidFile){Remove-Item $PidFile -Force -ErrorAction SilentlyContinue}
 Write-Host "Done. Ports $AnalysisPort, $FrontendPort, and $BackendPort are closed."
}else{Write-Host "Warning: listener PIDs remain: $($left -join ', ')"}
Start-Sleep -Seconds 2
'@

function Set-EmbeddedScript {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Script
    )

    $bytes = [IO.File]::ReadAllBytes($Path)
    $ascii = [Text.Encoding]::ASCII.GetString($bytes)
    $match = [regex]::Match($ascii, '-EncodedCommand\s+([A-Za-z0-9+/=]+)')
    if (-not $match.Success) {
        throw "EncodedCommand was not found in $Path"
    }

    $oldPayload = $match.Groups[1].Value
    $capacityBytes = [Convert]::FromBase64String($oldPayload).Length
    if (($capacityBytes % 2) -ne 0) {
        throw "Embedded payload capacity is not valid UTF-16LE: $capacityBytes"
    }

    $capacityChars = [int]($capacityBytes / 2)
    if ($Script.Length -gt $capacityChars) {
        throw "New script is too large for $Path ($($Script.Length) > $capacityChars chars)"
    }

    $paddedScript = $Script.PadRight($capacityChars, ' ')
    $newPayload = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($paddedScript))
    if ($newPayload.Length -ne $oldPayload.Length) {
        throw "Encoded payload length changed for $Path"
    }

    $payloadBytes = [Text.Encoding]::ASCII.GetBytes($newPayload)
    [Array]::Copy($payloadBytes, 0, $bytes, $match.Groups[1].Index, $payloadBytes.Length)
    [IO.File]::WriteAllBytes($Path, $bytes)
}

Set-EmbeddedScript -Path $startFile -Script $startScript
Set-EmbeddedScript -Path $stopFile -Script $stopScript

Write-Host 'Patched both Windows launchers.'
