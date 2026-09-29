[CmdletBinding()]
param(
    [switch]$SkipTests = $true
)

$ErrorActionPreference = "Stop"

$root       = Split-Path -Parent $MyInvocation.MyCommand.Path
$serverDir  = Join-Path $root "yyba_cloud_server"
$launcher   = Join-Path $serverDir "server-launcher"
$mvnw       = Join-Path $serverDir "mvnw.cmd"
$icon       = Join-Path $serverDir "packaging\windows\app.ico"
$version    = "1.0.0"
$jarName    = "server-launcher-1.0.0-SNAPSHOT.jar"

Write-Host "`n=== 1/3 ?? fat jar ===" -ForegroundColor Cyan
Push-Location $serverDir
try {
    $mvnArgs = @("clean","package","-pl","server-launcher","-am","-Dmaven.test.skip=true")
    & $mvnw @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven ???? (exit=$LASTEXITCODE)" }
} finally {
    Pop-Location
}

$jarPath = Join-Path $launcher "target\$jarName"
if (-not (Test-Path $jarPath)) { throw "???????: $jarPath" }
Write-Host "fat jar: $jarPath" -ForegroundColor Green

$inputDir = Join-Path $launcher "target\jpackage-input"
if (Test-Path $inputDir) { Remove-Item $inputDir -Recurse -Force }
New-Item -ItemType Directory -Path $inputDir | Out-Null
Copy-Item $jarPath $inputDir

Write-Host "`n=== 2/3 ?? jpackage ===" -ForegroundColor Cyan
$jpackage = $null
$cmd = Get-Command jpackage -ErrorAction SilentlyContinue
if ($cmd) { $jpackage = $cmd.Source }
elseif ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\jpackage.exe")) {
    $jpackage = "$env:JAVA_HOME\bin\jpackage.exe"
}
if (-not $jpackage) { throw "??? jpackage" }
Write-Host "jpackage: $jpackage" -ForegroundColor Green

Write-Host "`n=== 3/3 jpackage ?? app-image ===" -ForegroundColor Cyan
$dest = Join-Path $serverDir "target\dist"
if (-not (Test-Path $dest)) { New-Item -ItemType Directory -Path $dest -Force | Out-Null }

$jpArgs = @(
    "--type", "app-image",
    "--name", "YybaCloudServer",
    "--app-version", $version,
    "--vendor", "yyba",
    "--input", $inputDir,
    "--main-jar", $jarName,
    "--dest", $dest,
    "--icon", $icon,
    "--win-console",
    "--java-options", "-Djava.awt.headless=false",
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", "-Dsun.jnu.encoding=UTF-8"
)

& $jpackage @jpArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage ???? (exit=$LASTEXITCODE)" }

$appDir = Join-Path $dest "YybaCloudServer"
$exe    = Join-Path $appDir "YybaCloudServer.exe"

Write-Host "`n========================================" -ForegroundColor Green
Write-Host "? ????????"
Write-Host "   ????: $appDir"
Write-Host "   ?? EXE: $exe"
Write-Host "========================================`n"
