Add-Type -AssemblyName System.Drawing

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$logoPath = Join-Path $root "brand\logo.png"
$res = Join-Path $root "app\src\main\res"

if (-not (Test-Path $logoPath)) {
    throw "Не найден логотип: $logoPath"
}

$src = [System.Drawing.Bitmap]::FromFile($logoPath)

# Ищем реальные границы логотипа (без прозрачных полей)
$minX = $src.Width
$minY = $src.Height
$maxX = -1
$maxY = -1
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
if ($maxX -lt 0) { throw "Логотип пустой" }

$bbox = New-Object System.Drawing.Rectangle(
    [int]$minX, [int]$minY, [int]($maxX - $minX + 1), [int]($maxY - $minY + 1)
)

function New-Canvas([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    return @($bmp, $g)
}

function Draw-Logo($g, [int]$size, [double]$scale, [double]$centerY) {
    $targetW = $size * $scale
    $targetH = $targetW * $bbox.Height / $bbox.Width
    $x = ($size - $targetW) / 2.0
    $y = ($size * $centerY) - ($targetH / 2.0)
    $dest = New-Object System.Drawing.RectangleF(
        [single]$x, [single]$y, [single]$targetW, [single]$targetH
    )
    $srcRect = New-Object System.Drawing.RectangleF(
        [single]$bbox.X, [single]$bbox.Y, [single]$bbox.Width, [single]$bbox.Height
    )
    $g.DrawImage(
        $src,
        [System.Drawing.RectangleF]$dest,
        [System.Drawing.RectangleF]$srcRect,
        [System.Drawing.GraphicsUnit]::Pixel
    )
}

function Fill-RoundRect($g, [int]$size, [double]$radiusFactor) {
    $r = $size * $radiusFactor
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $path.AddArc(0, 0, $d, $d, 180, 90)
    $path.AddArc($size - $d, 0, $d, $d, 270, 90)
    $path.AddArc($size - $d, $size - $d, $d, $d, 0, 90)
    $path.AddArc(0, $size - $d, $d, $d, 90, 90)
    $path.CloseFigure()

    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.Point(0, 0)),
        (New-Object System.Drawing.Point($size, $size)),
        [System.Drawing.Color]::FromArgb(255, 23, 23, 28),
        [System.Drawing.Color]::FromArgb(255, 5, 5, 7)
    )
    $g.FillPath($brush, $path)
    $brush.Dispose()
    $path.Dispose()
}

function Save-Png($bmp, [string]$path) {
    $dir = Split-Path -Parent $path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
}

# --- Обычные иконки (квадрат) ---
$sizes = @{ "mdpi" = 48; "hdpi" = 72; "xhdpi" = 96; "xxhdpi" = 144; "xxxhdpi" = 192 }
foreach ($key in $sizes.Keys) {
    $size = $sizes[$key]
    $canvas = New-Canvas $size
    $bmp = $canvas[0]; $g = $canvas[1]
    Fill-RoundRect $g $size 0.22
    Draw-Logo $g $size 0.86 0.5
    Save-Png $bmp (Join-Path $res "mipmap-$key\ic_launcher.png")
    $g.Dispose(); $bmp.Dispose()
}

# --- Круглые иконки ---
foreach ($key in $sizes.Keys) {
    $size = $sizes[$key]
    $canvas = New-Canvas $size
    $bmp = $canvas[0]; $g = $canvas[1]
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddEllipse(0, 0, $size, $size)
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.Point(0, 0)),
        (New-Object System.Drawing.Point($size, $size)),
        [System.Drawing.Color]::FromArgb(255, 23, 23, 28),
        [System.Drawing.Color]::FromArgb(255, 5, 5, 7)
    )
    $g.FillPath($brush, $path)
    $brush.Dispose(); $path.Dispose()
    Draw-Logo $g $size 0.76 0.5
    Save-Png $bmp (Join-Path $res "mipmap-$key\ic_launcher_round.png")
    $g.Dispose(); $bmp.Dispose()
}

# --- Foreground для адаптивной иконки (108dp, безопасная зона 66dp) ---
$fgSizes = @{ "mdpi" = 108; "hdpi" = 162; "xhdpi" = 216; "xxhdpi" = 324; "xxxhdpi" = 432 }
foreach ($key in $fgSizes.Keys) {
    $size = $fgSizes[$key]
    $canvas = New-Canvas $size
    $bmp = $canvas[0]; $g = $canvas[1]
    $g.Clear([System.Drawing.Color]::Transparent)
    Draw-Logo $g $size 0.58 0.5
    Save-Png $bmp (Join-Path $res "mipmap-$key\ic_launcher_foreground.png")
    $g.Dispose(); $bmp.Dispose()
}

# --- Логотип для заставки (xxhdpi ≈ 170dp) ---
$splashSize = 512
$canvas = New-Canvas $splashSize
$bmp = $canvas[0]; $g = $canvas[1]
$g.Clear([System.Drawing.Color]::Transparent)
Draw-Logo $g $splashSize 0.78 0.5
Save-Png $bmp (Join-Path $res "drawable-xxhdpi\splash_logo.png")
$g.Dispose(); $bmp.Dispose()

# --- ТВ-баннер для Android TV: 320x180 dp, кладём в xhdpi (640x360 px) ---
$bannerW = 640
$bannerH = 360
$banner = New-Object System.Drawing.Bitmap(
    $bannerW, $bannerH, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
)
$bg = [System.Drawing.Graphics]::FromImage($banner)
$bg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
$bg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$bg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$bg.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality

$bannerBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Point(0, 0)),
    (New-Object System.Drawing.Point($bannerW, $bannerH)),
    [System.Drawing.Color]::FromArgb(255, 22, 22, 27),
    [System.Drawing.Color]::FromArgb(255, 6, 6, 8)
)
$bg.FillRectangle($bannerBrush, 0, 0, $bannerW, $bannerH)
$bannerBrush.Dispose()

$targetH = $bannerH * 0.62
$targetW = $targetH * $bbox.Width / $bbox.Height
$logoRect = New-Object System.Drawing.RectangleF(
    [single](($bannerW - $targetW) / 2.0),
    [single](($bannerH - $targetH) / 2.0),
    [single]$targetW,
    [single]$targetH
)
$srcRect = New-Object System.Drawing.RectangleF(
    [single]$bbox.X, [single]$bbox.Y, [single]$bbox.Width, [single]$bbox.Height
)
$bg.DrawImage(
    $src,
    [System.Drawing.RectangleF]$logoRect,
    [System.Drawing.RectangleF]$srcRect,
    [System.Drawing.GraphicsUnit]::Pixel
)
Save-Png $banner (Join-Path $res "drawable-xhdpi\tv_banner.png")
$bg.Dispose(); $banner.Dispose()

$src.Dispose()

Write-Host "Иконки готовы. BBox: $($bbox.X),$($bbox.Y) $($bbox.Width)x$($bbox.Height)"
