$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & mvn clean package
    if ($LASTEXITCODE -ne 0) {
        throw "mvn clean package failed"
    }

    $inputDir = Join-Path $projectRoot "target\jpackage-input"
    $jarPath = Join-Path $projectRoot "target\calendar-organizer-1.0.0.jar"
    if (-not (Test-Path $jarPath)) {
        throw "Expected JAR not found at $jarPath"
    }
    Copy-Item -Path $jarPath -Destination $inputDir -Force

    $distDir = Join-Path $projectRoot "target\dist"
    if (Test-Path $distDir) {
        Remove-Item -Recurse -Force $distDir
    }
    New-Item -ItemType Directory -Force -Path $distDir | Out-Null

    & jpackage `
        --type app-image `
        --name "CalendarOrganizer" `
        --input $inputDir `
        --main-jar "calendar-organizer-1.0.0.jar" `
        --main-class "com.calendarorganizer.Launcher" `
        --app-version "1.0.0" `
        --java-options "-Xms32m" `
        --java-options "-Xmx256m" `
        --java-options "-XX:+UseG1GC" `
        --java-options "-XX:MaxGCPauseMillis=20" `
        --java-options "-XX:MinHeapFreeRatio=15" `
        --java-options "-XX:MaxHeapFreeRatio=30" `
        --dest $distDir

    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed"
    }

    Write-Output "Packaged app image at: $(Join-Path $distDir 'CalendarOrganizer\CalendarOrganizer.exe')"
} finally {
    Pop-Location
}
