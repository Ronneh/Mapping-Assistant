[CmdletBinding()]
param(
    [string]$AppVersion = '5.3.0',
    [string]$ReleaseName = "Mapping Assistant v$($AppVersion.Split('.')[0])",
    [string]$OutputDirectory = 'target\windows-release',
    [string]$ContentPackDirectory = 'help-content-pack',
    [string]$JpackagePath
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$targetDirectory = Join-Path $projectRoot 'target'
$jarName = 'mapping-assistant-0.1.0.jar'
$jarPath = Join-Path $targetDirectory $jarName
$iconPng = Join-Path $projectRoot 'app-icon.png'
$iconIco = Join-Path $targetDirectory 'app-icon.ico'
$packageInput = Join-Path $targetDirectory 'jpackage-input'
$resolvedOutput = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory))
$contentPack = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $ContentPackDirectory))

function Write-MultiResolutionIcon {
    param([string]$Source, [string]$Destination)

    Add-Type -AssemblyName System.Drawing
    $sizes = @(16, 24, 32, 48, 64, 128, 256)
    $sourceImage = [System.Drawing.Image]::FromFile($Source)
    $images = [System.Collections.Generic.List[byte[]]]::new()
    try {
        foreach ($size in $sizes) {
            $bitmap = [System.Drawing.Bitmap]::new($size, $size,
                [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
            try {
                $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
                try {
                    $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
                    $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
                    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
                    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                    $graphics.DrawImage($sourceImage, 0, 0, $size, $size)
                } finally {
                    $graphics.Dispose()
                }
                $stream = [System.IO.MemoryStream]::new()
                try {
                    $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
                    $images.Add($stream.ToArray())
                } finally {
                    $stream.Dispose()
                }
            } finally {
                $bitmap.Dispose()
            }
        }
    } finally {
        $sourceImage.Dispose()
    }

    $file = [System.IO.File]::Create($Destination)
    $writer = [System.IO.BinaryWriter]::new($file)
    try {
        $writer.Write([UInt16]0)
        $writer.Write([UInt16]1)
        $writer.Write([UInt16]$sizes.Count)
        $offset = 6 + (16 * $sizes.Count)
        for ($index = 0; $index -lt $sizes.Count; $index++) {
            $sizeByte = if ($sizes[$index] -eq 256) { 0 } else { $sizes[$index] }
            $writer.Write([Byte]$sizeByte)
            $writer.Write([Byte]$sizeByte)
            $writer.Write([Byte]0)
            $writer.Write([Byte]0)
            $writer.Write([UInt16]1)
            $writer.Write([UInt16]32)
            $writer.Write([UInt32]$images[$index].Length)
            $writer.Write([UInt32]$offset)
            $offset += $images[$index].Length
        }
        foreach ($image in $images) {
            $writer.Write($image)
        }
    } finally {
        $writer.Dispose()
    }
}

if (-not $JpackagePath) {
    $candidates = @()
    if ($env:JAVA_HOME) {
        $candidates += Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
    }
    $command = Get-Command jpackage.exe -ErrorAction SilentlyContinue
    if ($command) {
        $candidates += $command.Source
    }
    $JpackagePath = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (-not $JpackagePath -or -not (Test-Path -LiteralPath $JpackagePath)) {
    throw 'jpackage.exe was not found. Set JAVA_HOME to a JDK or pass -JpackagePath.'
}
if (-not (Test-Path -LiteralPath (Join-Path $contentPack 'manifest.json') -PathType Leaf)) {
    throw "Editor Guide content pack is missing or invalid: $contentPack"
}

Push-Location $projectRoot
try {
    & mvn --batch-mode clean package
    if ($LASTEXITCODE -ne 0) { throw "Maven failed with exit code $LASTEXITCODE." }

    $forbidden = & jar tf $jarPath |
        Select-String '(^|/)TutorialEditorExtension\.class$|META-INF/services/EditorHelpAuthoringExtension'
    if ($forbidden) {
        throw "The normal release JAR contains tutorial-editor artifacts: $forbidden"
    }

    Write-MultiResolutionIcon -Source $iconPng -Destination $iconIco

    New-Item -ItemType Directory -Path $packageInput -Force | Out-Null
    Copy-Item -LiteralPath $jarPath -Destination (Join-Path $packageInput $jarName) -Force
    if (Test-Path -LiteralPath $resolvedOutput) {
        throw "Output already exists: $resolvedOutput"
    }

    & $JpackagePath --type app-image --name $ReleaseName --dest $resolvedOutput `
        --input $packageInput --main-jar $jarName --main-class MappingAssistant `
        --app-version $AppVersion --icon $iconIco
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE." }

    $launcher = Join-Path (Join-Path $resolvedOutput $ReleaseName) "$ReleaseName.exe"
    Copy-Item -LiteralPath $contentPack `
        -Destination (Join-Path (Split-Path -Parent $launcher) 'help-content') -Recurse
    Add-Type -AssemblyName System.Drawing
    $embeddedIcon = [System.Drawing.Icon]::ExtractAssociatedIcon($launcher)
    try {
        if (-not $embeddedIcon -or $embeddedIcon.Width -lt 16 -or $embeddedIcon.Height -lt 16) {
            throw "The packaged launcher does not contain a usable icon: $launcher"
        }
        $bitmap = $embeddedIcon.ToBitmap()
        try {
            $canonicalColorPixels = 0
            for ($y = 0; $y -lt $bitmap.Height; $y++) {
                for ($x = 0; $x -lt $bitmap.Width; $x++) {
                    $pixel = $bitmap.GetPixel($x, $y)
                    if ($pixel.A -gt 0 -and $pixel.R -lt 100 -and
                        $pixel.G -gt 150 -and $pixel.B -gt 180) {
                        $canonicalColorPixels++
                    }
                }
            }
            if ($canonicalColorPixels -lt 20) {
                throw "The packaged launcher does not contain the canonical cyan application icon: $launcher"
            }
        } finally {
            $bitmap.Dispose()
        }
    } finally {
        if ($embeddedIcon) { $embeddedIcon.Dispose() }
    }

    Write-Output "Windows app image created at: $(Split-Path -Parent $launcher)"
    Write-Output 'Verified: normal JAR excludes the tutorial editor, launcher contains the canonical icon, and Editor Guide content is included.'
} finally {
    Pop-Location
}
