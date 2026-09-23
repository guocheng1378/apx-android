# 由手机端矢量图标生成 Windows 图标（pc/host/res/apx.ico）。
#
# 为什么用脚本而不是直接提交 .ico：图标要和手机端保持一致（同一套几何 + 同一配色），
# 二进制 .ico 无法 review，脚本可复现 —— 手机端 ic_launcher_app.xml 一改，重跑即同步。
#
# 几何与配色取自 android/app/src/main/res/drawable/ic_launcher_app.xml（48x48 视口）：
#   圆角矩形 (6,6)-(34,42) r=4  #6750A4                —— 手机
#   白色竖条 (16,12) 8x24                              —— 机身
#   白色横条 (36,14) 4x20 / (40,20) 4x8 / (44,26) 4x4  —— USB 插头
#
# 编码格式：<=128 用经典 BMP/DIB 条目（兼容一切工具），256 用 PNG 条目
# （Vista+ 标准做法，体积小）。只用 PNG 的话 System.Drawing 等老 API 解不出来。
#
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts/make_icon.ps1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Drawing

$repoRoot = Split-Path -Parent $PSScriptRoot
$outPath = Join-Path $repoRoot 'pc\host\res\apx.ico'

# 覆盖任务栏 / 资源管理器 / Alt+Tab / 高 DPI 常用档位
$sizes = @(16, 24, 32, 48, 64, 128, 256)

function New-IconBitmap([int]$s) {
    $bmp = New-Object System.Drawing.Bitmap($s, $s, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    try {
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $g.Clear([System.Drawing.Color]::Transparent)
        $k = $s / 48.0

        # 手机：紫色圆角矩形
        $x = 6 * $k; $y = 6 * $k; $w = 28 * $k; $h = 36 * $k; $d = 8 * $k
        $path = New-Object System.Drawing.Drawing2D.GraphicsPath
        $path.AddArc($x, $y, $d, $d, 180, 90)
        $path.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
        $path.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
        $path.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
        $path.CloseFigure()
        $purple = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 0x67, 0x50, 0xA4))
        $g.FillPath($purple, $path)

        # 白色机身与 USB 插头
        $white = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
        $bars = @(@(16, 12, 8, 24), @(36, 14, 4, 20), @(40, 20, 4, 8), @(44, 26, 4, 4))
        foreach ($b in $bars) {
            $g.FillRectangle($white, $b[0] * $k, $b[1] * $k, $b[2] * $k, $b[3] * $k)
        }
        $purple.Dispose(); $white.Dispose(); $path.Dispose()
    } finally {
        $g.Dispose()
    }
    return $bmp
}

# 32bpp BGRA 的经典 ICO 图像数据（BITMAPINFOHEADER + 自底向上像素 + AND 掩码）
function ConvertTo-IcoDib([System.Drawing.Bitmap]$bmp) {
    $w = $bmp.Width
    $h = $bmp.Height
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    try {
        $bw.Write([UInt32]40)              # biSize
        $bw.Write([Int32]$w)               # biWidth
        $bw.Write([Int32]($h * 2))         # biHeight = XOR + AND
        $bw.Write([UInt16]1)               # biPlanes
        $bw.Write([UInt16]32)              # biBitCount
        $bw.Write([UInt32]0)               # biCompression = BI_RGB
        $bw.Write([UInt32]($w * $h * 4))   # biSizeImage
        $bw.Write([Int32]0); $bw.Write([Int32]0)
        $bw.Write([UInt32]0); $bw.Write([UInt32]0)

        for ($y = $h - 1; $y -ge 0; $y--) {          # DIB 自底向上
            for ($x = 0; $x -lt $w; $x++) {
                $c = $bmp.GetPixel($x, $y)
                $bw.Write([Byte]$c.B); $bw.Write([Byte]$c.G)
                $bw.Write([Byte]$c.R); $bw.Write([Byte]$c.A)
            }
        }
        # AND 掩码：32bpp 下由 alpha 决定透明，这里全 0 即可；每行按 4 字节对齐
        $rowBytes = [Math]::Floor(($w + 31) / 32) * 4
        $bw.Write((New-Object byte[] ($rowBytes * $h)))
        $bw.Flush()
        return , $ms.ToArray()
    } finally {
        $bw.Dispose()
        $ms.Dispose()
    }
}

function ConvertTo-IcoPng([System.Drawing.Bitmap]$bmp) {
    $ms = New-Object System.IO.MemoryStream
    try {
        $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
        return , $ms.ToArray()
    } finally {
        $ms.Dispose()
    }
}

$images = @()
foreach ($s in $sizes) {
    $bmp = New-IconBitmap $s
    try {
        if ($s -ge 256) { $images += , (ConvertTo-IcoPng $bmp) }
        else            { $images += , (ConvertTo-IcoDib $bmp) }
    } finally {
        $bmp.Dispose()
    }
}

# 组装 ICO 容器：ICONDIR(6B) + ICONDIRENTRY(16B * N) + 各图数据
$outDir = Split-Path -Parent $outPath
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

$fs = [System.IO.File]::Create($outPath)
$bw = New-Object System.IO.BinaryWriter($fs)
try {
    $bw.Write([UInt16]0)                 # reserved
    $bw.Write([UInt16]1)                 # type = icon
    $bw.Write([UInt16]$sizes.Count)

    $offset = 6 + 16 * $sizes.Count
    for ($i = 0; $i -lt $sizes.Count; $i++) {
        $s = $sizes[$i]
        $dim = if ($s -ge 256) { 0 } else { $s }   # 0 表示 256
        $bw.Write([Byte]$dim)
        $bw.Write([Byte]$dim)
        $bw.Write([Byte]0)               # 调色板色数
        $bw.Write([Byte]0)               # reserved
        $bw.Write([UInt16]1)             # planes
        $bw.Write([UInt16]32)            # bpp
        $bw.Write([UInt32]$images[$i].Length)
        $bw.Write([UInt32]$offset)
        $offset += $images[$i].Length
    }
    foreach ($d in $images) { $bw.Write($d) }
} finally {
    $bw.Dispose()
    $fs.Dispose()
}

Write-Output ("已生成 " + $outPath + "（" + $sizes.Count + " 档：" + ($sizes -join ', ') + "）")
