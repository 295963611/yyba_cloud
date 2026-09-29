Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
$ErrorActionPreference = "Continue"
$logFile = "c:\work\yyba_cloud\apk-build.log"
Set-Location "c:\work\yyba_cloud\yyba_cloud_android"
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17.0.11"
$env:ANDROID_HOME = "C:\Users\Administrator\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
"=== Build started: $(Get-Date) ===" | Out-File $logFile -Encoding UTF8
& ".\gradlew.bat" assembleDebug 2>&1 | Tee-Object -FilePath $logFile -Append | Out-Null
"=== Build finished: $(Get-Date) EXIT=$LASTEXITCODE ===" | Out-File $logFile -Append -Encoding UTF8
"=== APK files ===" | Out-File $logFile -Append -Encoding UTF8
Get-ChildItem "app\build\outputs\apk\debug\" -ErrorAction SilentlyContinue | Select-Object Name, Length, LastWriteTime | Format-Table -AutoSize | Out-File $logFile -Append -Encoding UTF8
