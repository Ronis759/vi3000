Add-Type -AssemblyName System.Drawing

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$logoPath = Join-Path $root "brand\logo.png"
$outDir = Join-Path $root "pwa\icons"

if (-not (Test-Path $logoPath)) {
    throw "Logo not found: $logoPath"
}

$src = [System.Drawing.Bitmap]::FromFile($logoPath)

# Find real logo bounds (skip transparent padding)
$minX = $src.Width; $minY = $src.Height; $maxX = -1; $maxY = -1
for ($y = 0; $y -lt $src.Height; $y += 2) {
    for ($x = 0; $x -lt $src.Width; $x += 2) {
        if ($src.GetPixel($x, $y).A -gt 8) {
            if ($x -lt $minX) { $minX = $x }
            if ($x -gt $maxX) { $maxX = $x }
            if ($y -lt $minY) { $minY = $y }
            if ($y -gt $maxY) { $maxY = $y }
        }
    }
}
if ($maxX -lt 0) { throw "Logo is empty" }

$bbox = New-Object System.Drawing.Rectangle(
    [int]$minX, [int]$minY, [int]($maxX - $minX + 1), [int]($maxY - $minY + 1)
)

function Draw-Logo($g, [int]$size, [double]$scale) {
    $targetW = $size * $scale
    $targetH = $targetW * $bbox.Height / $bbox.Width
    $dest = New-Object System.Drawing.RectangleF(
        [single](($size - $targetW) / 2.0),
        [single](($size - $targetH) / 2.0),
        [single]$targetW,
        [single]$targetH
    )
    $srcRect = New-Object System.Drawing.RectangleF(
        [single]$bbox.X, [single]$bbox.Y, [single]$bbox.Width, [single]$bbox.Height
    )
    $g.DrawImage($src, $dest, $srcRect, [System.Drawing.GraphicsUnit]::Pixel)
}

function Save-Icon([int]$size, [string]$name, [double]$scale, [bool]$maskable) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality

    if ($maskable) {
        $brush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 11, 11, 14))
        $g.FillRectangle($brush, 0, 0, $size, $size)
        $brush.Dispose()
    } else {
        $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
            (New-Object System.Drawing.Point(0, 0)),
            (New-Object System.Drawing.Point($size, $size)),
            [System.Drawing.Color]::FromArgb(255, 23, 23, 28),
            [System.Drawing.Color]::FromArgb(255, 5, 5, 7)
        )
        $g.FillRectangle($brush, 0, 0, $size, $size)
        $brush.Dispose()
    }

    Draw-Logo $g $size $scale

    $path = Join-Path $outDir $name
    $dir = Split-Path -Parent $path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
    Write-Host "Done: $path"
}

Save-Icon 192 "icon-192.png" 0.86 $false
Save-Icon 512 "icon-512.png" 0.86 $false
Save-Icon 512 "icon-maskable-512.png" 0.62 $true

$src.Dispose()
