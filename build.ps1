param([string[]]$Tasks = @('testDebugUnitTest', 'lintDebug', 'assembleDebug'))
$ErrorActionPreference = 'Stop'
$outputRoot = Join-Path $env:LOCALAPPDATA 'PocketMusic-build'
& "$PSScriptRoot\gradlew.bat" '-p' $PSScriptRoot "-PpocketBuildDir=$outputRoot" '--project-cache-dir' "$outputRoot\gradle-cache" '--console=plain' @Tasks
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$apk = Join-Path $outputRoot 'app\outputs\apk\debug\app-debug.apk'
if (Test-Path -LiteralPath $apk) {
    $artifacts = Join-Path $PSScriptRoot 'artifacts'
    New-Item -ItemType Directory -Force $artifacts | Out-Null
    $version = [regex]::Match([IO.File]::ReadAllText("$PSScriptRoot\app\build.gradle.kts"), 'versionName = "([^"]+)"').Groups[1].Value
    $artifactPath = Join-Path $artifacts "Offline-Plex-Music-$version.apk"
    Copy-Item -LiteralPath $apk -Destination $artifactPath
    Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256
}
