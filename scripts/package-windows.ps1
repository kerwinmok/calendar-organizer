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
    $launcherSource = Join-Path $projectRoot "packaging\windows\SingleFileLauncher.cs"
    if (-not (Test-Path $launcherSource)) {
        throw "Expected launcher source not found at $launcherSource"
    }

    $stageDir = Join-Path $projectRoot "target\jpackage-stage"
    if (Test-Path $stageDir) {
        Remove-Item -Recurse -Force $stageDir
    }
    New-Item -ItemType Directory -Force -Path $stageDir | Out-Null

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
        --java-options "-Xmx128m" `
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
        --dest $stageDir

    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed"
    }

    $stageAppDir = Join-Path $stageDir "CalendarOrganizer"
    $payloadZip = Join-Path $stageDir "payload.zip"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        $stageAppDir,
        $payloadZip,
        [System.IO.Compression.CompressionLevel]::Optimal,
        $false
    )

    $hashHex = (Get-FileHash -Path $payloadZip -Algorithm SHA256).Hash.ToLowerInvariant()
    $stamp = $hashHex.Substring(0, 16)

    $cscExe = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
    if (-not (Test-Path $cscExe)) {
        throw ".NET Framework csc.exe not found at $cscExe"
    }

    $stubExe = Join-Path $stageDir "launcher-stub.exe"
    & $cscExe `
        /nologo `
        /target:winexe `
        /platform:x64 `
        /optimize+ `
        "/win32icon:$iconPath" `
        /r:System.IO.Compression.dll `
        /r:System.IO.Compression.FileSystem.dll `
        /r:System.Windows.Forms.dll `
        /r:System.Drawing.dll `
        "/out:$stubExe" `
        $launcherSource

    if ($LASTEXITCODE -ne 0) {
        throw "csc launcher compilation failed"
    }

    $distDir = Join-Path $projectRoot "target\dist"
    if (Test-Path $distDir) {
        Remove-Item -Recurse -Force $distDir
    }
    New-Item -ItemType Directory -Force -Path $distDir | Out-Null

    $finalExe = Join-Path $distDir "CalendarOrganizer.exe"
    $stubLength = (Get-Item $stubExe).Length
    $payloadLength = (Get-Item $payloadZip).Length

    $outStream = [System.IO.File]::Create($finalExe)
    try {
        $stubStream = [System.IO.File]::OpenRead($stubExe)
        try {
            $stubStream.CopyTo($outStream)
        } finally {
            $stubStream.Dispose()
        }

        $zipStream = [System.IO.File]::OpenRead($payloadZip)
        try {
            $zipStream.CopyTo($outStream)
        } finally {
            $zipStream.Dispose()
        }

        $writer = New-Object System.IO.BinaryWriter($outStream, [System.Text.Encoding]::ASCII, $true)
        try {
            $writer.Write([System.Text.Encoding]::ASCII.GetBytes($stamp))
            $writer.Write([Int64]$stubLength)
            $writer.Write([Int64]$payloadLength)
            $writer.Write([System.Text.Encoding]::ASCII.GetBytes("CALORG01"))
        } finally {
            $writer.Dispose()
        }
    } finally {
        $outStream.Dispose()
    }

    $sizeMb = [Math]::Round(((Get-Item $finalExe).Length / 1MB), 1)
    Write-Output "Packaged single-file executable at: $finalExe ($sizeMb MB)"
} finally {
    Pop-Location
}
