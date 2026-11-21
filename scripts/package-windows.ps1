param(
    [switch]$Install,
    [switch]$DesktopShortcut
)

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

    $iconPath = Join-Path $projectRoot "packaging\windows\app-icon.ico"
    if (-not (Test-Path $iconPath)) {
        throw "Expected icon not found at $iconPath"
    }

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
        --icon $iconPath `
        --add-modules "java.base,java.sql,java.net.http,java.desktop,java.logging,java.xml,jdk.crypto.ec,jdk.unsupported" `
        --java-options "-Xms16m" `
        --java-options "-Xmx192m" `
        --java-options "-XX:+UseSerialGC" `
        --java-options "-XX:TieredStopAtLevel=1" `
        --java-options "-XX:ReservedCodeCacheSize=20m" `
        --java-options "-Xss384k" `
        --java-options "-XX:MinHeapFreeRatio=10" `
        --java-options "-XX:MaxHeapFreeRatio=20" `
        --java-options "-XX:SoftRefLRUPolicyMSPerMB=50" `
        --java-options "-Djava.awt.headless=true" `
        --java-options "-Dprism.order=sw" `
        --java-options "-Dprism.maxvram=32m" `
        --java-options "-Dprism.targetvram=16m" `
        --java-options "--enable-native-access=ALL-UNNAMED" `
        --dest $distDir

    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed"
    }

    $appDir = Join-Path $distDir "CalendarOrganizer"
    $zipPath = Join-Path $distDir "CalendarOrganizer-windows-x64.zip"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        $appDir,
        $zipPath,
        [System.IO.Compression.CompressionLevel]::Optimal,
        $true
    )

    $zipSizeMb = [Math]::Round(((Get-Item $zipPath).Length / 1MB), 1)
    Write-Output "Packaged standalone app-image at: $appDir"
    Write-Output "Packaged portable archive at: $zipPath ($zipSizeMb MB)"

    if ($Install) {
        Stop-Process -Name CalendarOrganizer -Force -ErrorAction SilentlyContinue

        $installDir = Join-Path $env:LOCALAPPDATA "Programs\CalendarOrganizer"
        if (Test-Path $installDir) {
            Remove-Item -Recurse -Force $installDir
        }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $installDir) | Out-Null
        Copy-Item -Path $appDir -Destination $installDir -Recurse -Force

        $installedExe = Join-Path $installDir "CalendarOrganizer.exe"
        Write-Output "Installed application to: $installedExe"

        if ($DesktopShortcut) {
            $desktopDir = [Environment]::GetFolderPath("Desktop")
            $lnkPath = Join-Path $desktopDir "Calendar Organizer.lnk"
            $wsh = New-Object -ComObject WScript.Shell
            $shortcut = $wsh.CreateShortcut($lnkPath)
            $shortcut.TargetPath = $installedExe
            $shortcut.WorkingDirectory = $installDir
            $shortcut.IconLocation = "$installedExe,0"
            $shortcut.Description = "Calendar Organizer"
            $shortcut.Save()
            Write-Output "Updated Desktop shortcut: $lnkPath"
        }
    }
} finally {
    Pop-Location
}
