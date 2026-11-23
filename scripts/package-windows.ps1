param(
    [switch]$Install,
    [switch]$DesktopShortcut,
    [switch]$Installer,
    [string]$AppVersion = "1.0.0"
)

$ErrorActionPreference = "Stop"

$cleanVersion = if ([string]::IsNullOrWhiteSpace($AppVersion)) { "1.0.0" } else { $AppVersion.Trim().TrimStart('v', 'V') }
if ([string]::IsNullOrWhiteSpace($cleanVersion)) {
    $cleanVersion = "1.0.0"
}

$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    $distDir = Join-Path $projectRoot "target\dist"
    if (Test-Path $distDir) {
        Get-ChildItem -Path $distDir -Recurse -Force -ErrorAction SilentlyContinue | ForEach-Object {
            if (-not $_.PSIsContainer) {
                $_.IsReadOnly = $false
            }
        }
        Remove-Item -Recurse -Force $distDir
    }

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
        --app-version $cleanVersion `
        --icon $iconPath `
        --add-modules "java.base,java.sql,java.net.http,java.desktop,java.logging,java.xml,jdk.crypto.ec,jdk.unsupported" `
        --java-options "-Xms16m" `
        --java-options "-Xmx192m" `
        --java-options "-XX:+UseSerialGC" `
        --java-options "-XX:ReservedCodeCacheSize=32m" `
        --java-options "-Xss384k" `
        --java-options "-XX:MinHeapFreeRatio=10" `
        --java-options "-XX:MaxHeapFreeRatio=20" `
        --java-options "-XX:SoftRefLRUPolicyMSPerMB=50" `
        --java-options "-Djava.awt.headless=true" `
        --java-options "-Dprism.order=d3d,sw" `
        --java-options "-Dprism.maxvram=64m" `
        --java-options "-Dprism.targetvram=32m" `
        --java-options "--enable-native-access=ALL-UNNAMED" `
        --dest $distDir

    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed"
    }

    $appDir = Join-Path $distDir "CalendarOrganizer"
    $builtExe = Join-Path $appDir "CalendarOrganizer.exe"
    if (Test-Path $builtExe) {
        (Get-Item -Force $builtExe).IsReadOnly = $false
    }
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

    if ($Installer) {
        if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
            $wixCandidates = @()
            if (-not [string]::IsNullOrWhiteSpace($env:WIX)) {
                $wixCandidates += (Join-Path $env:WIX "bin")
            }
            $wixCandidates += @(Get-ChildItem -Path "C:\Program Files (x86)\WiX Toolset*\bin" -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
            foreach ($dir in $wixCandidates) {
                if (Test-Path (Join-Path $dir "candle.exe")) {
                    $env:PATH = "$dir;$env:PATH"
                    break
                }
            }
        }
        if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue) -or -not (Get-Command light.exe -ErrorAction SilentlyContinue)) {
            throw "WiX Toolset v3 (candle.exe / light.exe) is required on PATH to build the Windows .exe installer."
        }

        & jpackage `
            --type exe `
            --name "CalendarOrganizer" `
            --app-version $cleanVersion `
            --vendor "kerwinmok" `
            --app-image $appDir `
            --win-per-user-install `
            --win-shortcut `
            --win-menu `
            --win-menu-group "Calendar Organizer" `
            --win-dir-chooser `
            --win-upgrade-uuid "6e4a8c31-9c9a-4f1b-9a8d-7d5f94c0b211" `
            --dest $distDir

        if ($LASTEXITCODE -ne 0) {
            throw "jpackage installer build failed"
        }

        $installerPath = Join-Path $distDir "CalendarOrganizer-$cleanVersion.exe"
        if (-not (Test-Path $installerPath)) {
            throw "Expected installer not found at $installerPath"
        }
        $installerSizeMb = [Math]::Round(((Get-Item $installerPath).Length / 1MB), 1)
        Write-Output "Packaged Windows installer at: $installerPath ($installerSizeMb MB)"
    }

    if ($Install) {
        Stop-Process -Name CalendarOrganizer -Force -ErrorAction SilentlyContinue
        Wait-Process -Name CalendarOrganizer -Timeout 5 -ErrorAction SilentlyContinue

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
