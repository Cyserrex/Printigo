# Membuat ikon peluncur Android dari satu berkas gambar sumber.
#
# Sumber adalah ikon jadi: tile biru full-bleed dengan empat sudut membulat
# berwarna putih. Warna putih sudut itu sama persis dengan kertas di badan
# printer, jadi batas artwork tidak bisa dicari lewat "bukan putih" -- yang
# dipakai di sini adalah "bukan biru latar", dengan wilayah sudut dikecualikan
# secara geometris memakai radius sudut yang diukur dari gambar.
#
# Alur:
#   1. ukur radius sudut membulat,
#   2. cari batas artwork (piksel bukan-biru, di luar wilayah sudut),
#   3. gambar tile terskala di atas isian biru rata, dipotong bentuk membulat
#      supaya sudut putihnya tidak ikut terbawa,
#   4. keluarkan lapisan ikon adaptif plus PNG legacy persegi dan bulat.
#
# Pemakaian:
#   powershell -File tools\make_icons.ps1 -Source gambar.png -ResDir app\src\main\res

param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$ResDir
)

Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = "Stop"

$src = New-Object System.Drawing.Bitmap($Source)
$W = $src.Width; $H = $src.Height
Write-Host ("Sumber : {0}x{1}" -f $W, $H)

# ------------------------------------------------ baca seluruh piksel sekali
# GetPixel per piksel terlalu lambat untuk 1,5 juta piksel.
$rect = New-Object System.Drawing.Rectangle(0, 0, $W, $H)
$data = $src.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
    [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$stride = $data.Stride
$px = New-Object byte[] ($stride * $H)
[System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $px, 0, $px.Length)
$src.UnlockBits($data)

# Format32bppArgb tersimpan B,G,R,A per piksel.
function Test-Blue([int]$x, [int]$y) {
    $i = $y * $stride + $x * 4
    $b = $px[$i]; $g = $px[$i + 1]; $r = $px[$i + 2]
    return ($b -gt 235 -and $r -lt 110 -and $g -gt 145 -and $g -lt 205)
}

# ------------------------------------------------------ radius sudut membulat
# Pada kolom paling kiri, warna baru menjadi biru setelah lengkung sudut habis.
$radius = 0
while ($radius -lt $H -and -not (Test-Blue 0 $radius)) { $radius++ }
Write-Host ("Radius sudut: {0} px" -f $radius)
$margin = [int]($radius * 1.15)   # sedikit lebih lebar dari lengkungnya

# ----------------------------------------------------------- batas artwork
# Semua piksel bukan-biru dihitung sebagai artwork, kecuali di empat wilayah
# sudut tempat warna putih kanvas berada.
$minX = [int]::MaxValue; $minY = [int]::MaxValue; $maxX = -1; $maxY = -1
for ($y = 0; $y -lt $H; $y++) {
    $inCornerBand = ($y -lt $margin -or $y -ge $H - $margin)
    $row = $y * $stride
    for ($x = 0; $x -lt $W; $x++) {
        if ($inCornerBand -and ($x -lt $margin -or $x -ge $W - $margin)) { continue }
        $i = $row + $x * 4
        $b = $px[$i]; $g = $px[$i + 1]; $r = $px[$i + 2]
        if ($b -gt 235 -and $r -lt 110 -and $g -gt 145 -and $g -lt 205) { continue }
        if ($x -lt $minX) { $minX = $x }
        if ($x -gt $maxX) { $maxX = $x }
        if ($y -lt $minY) { $minY = $y }
        if ($y -gt $maxY) { $maxY = $y }
    }
}
$artX = $minX; $artY = $minY
$artW = $maxX - $minX + 1; $artH = $maxY - $minY + 1
Write-Host ("Artwork: x={0} y={1} {2}x{3}" -f $artX, $artY, $artW, $artH)

# ------------------------------------------------------ warna biru rata-rata
# Dirata-rata dari cincin tepi, di luar wilayah sudut.
$sumR = 0.0; $sumG = 0.0; $sumB = 0.0; $n = 0
foreach ($inset in @(6, 14, 24)) {
    for ($x = $margin; $x -lt $W - $margin; $x += 7) {
        foreach ($y in @($inset, ($H - 1 - $inset))) {
            $i = $y * $stride + $x * 4
            $sumB += $px[$i]; $sumG += $px[$i + 1]; $sumR += $px[$i + 2]; $n++
        }
    }
    for ($y = $margin; $y -lt $H - $margin; $y += 7) {
        foreach ($x in @($inset, ($W - 1 - $inset))) {
            $i = $y * $stride + $x * 4
            $sumB += $px[$i]; $sumG += $px[$i + 1]; $sumR += $px[$i + 2]; $n++
        }
    }
}
$avg = [System.Drawing.Color]::FromArgb(255,
    [int][Math]::Round($sumR / $n), [int][Math]::Round($sumG / $n), [int][Math]::Round($sumB / $n))
$avgHex = "#{0:X2}{1:X2}{2:X2}" -f $avg.R, $avg.G, $avg.B
Write-Host ("Biru latar: {0}" -f $avgHex)

# --------------------------------------------------------------- komposit
function New-RoundedPath([double]$x, [double]$y, [double]$w, [double]$h, [double]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2.0
    if ($d -le 0) { $p.AddRectangle((New-Object System.Drawing.RectangleF($x, $y, $w, $h))); return $p }
    $p.AddArc($x, $y, $d, $d, 180, 90)
    $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

# Menggambar tile terskala di tengah kanvas, dengan artwork menempati
# proporsi tertentu dari kanvas. Sisa kanvas diisi biru rata.
function New-Composite {
    param([int]$size, [double]$artFraction)

    $scale = [Math]::Min($artFraction * $size / $artW, $artFraction * $size / $artH)

    $bmp = New-Object System.Drawing.Bitmap($size, $size,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $gr = [System.Drawing.Graphics]::FromImage($bmp)
    $gr.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gr.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $gr.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias

    # Latar diisi biru rata hasil rata-rata cincin tepi tile. Sempat dicoba
    # meregangkan kolom tepi supaya gradasinya menyambung, tapi itu justru
    # meninggalkan garis vertikal di pita atas dan bawah kanvas.
    $brush = New-Object System.Drawing.SolidBrush($avg)
    $gr.FillRectangle($brush, 0, 0, $size, $size)

    $tw = $W * $scale
    $th = $H * $scale
    $ox = $size / 2.0 - ($artX + $artW / 2.0) * $scale
    $oy = $size / 2.0 - ($artY + $artH / 2.0) * $scale

    # Tile dipotong bentuk membulat dan dikecilkan sedikit, supaya sudut putih
    # dan halo lembut di tepinya tidak ikut terbawa.
    $bleed = $tw * 0.015
    $path = New-RoundedPath ($ox + $bleed) ($oy + $bleed) ($tw - 2 * $bleed) `
        ($th - 2 * $bleed) (($radius * $scale) - $bleed)
    $gr.SetClip($path)
    $dest = New-Object System.Drawing.RectangleF($ox, $oy, $tw, $th)
    $srcR = New-Object System.Drawing.RectangleF(0, 0, $W, $H)
    $gr.DrawImage($src, $dest, $srcR, [System.Drawing.GraphicsUnit]::Pixel)
    $gr.ResetClip()

    $brush.Dispose(); $path.Dispose(); $gr.Dispose()
    return $bmp
}

# Ikon legacy digambar full-bleed: gambar sumber mengisi seluruh ikon lalu
# dipotong bentuknya. Karena tidak ada area yang perlu ditambal warna, tidak
# ada sambungan warna sama sekali -- persis ikon aslinya.
function New-LegacyIcon {
    param([int]$size, [bool]$round)

    $bmp = New-Object System.Drawing.Bitmap($size, $size,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $gr = [System.Drawing.Graphics]::FromImage($bmp)
    $gr.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $gr.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gr.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality

    $path = if ($round) {
        $p = New-Object System.Drawing.Drawing2D.GraphicsPath
        $p.AddEllipse(0.0, 0.0, [double]$size, [double]$size)
        $p
    } else {
        # Radius sudut mengikuti proporsi gambar aslinya.
        New-RoundedPath 0.0 0.0 ([double]$size) ([double]$size) ($size * $radius / $W)
    }
    $gr.SetClip($path)

    # Gambar mengisi penuh. Untuk versi bulat, hanya ujung sudut foto kiri-bawah
    # yang tersenggol lingkaran; mengecilkan gambar justru menyisakan celah
    # kosong di keempat titik tengah tepi lingkaran.
    $d = [double]$size
    $off = 0.0
    $gr.DrawImage($src, (New-Object System.Drawing.RectangleF($off, $off, $d, $d)),
        (New-Object System.Drawing.RectangleF(0, 0, $W, $H)),
        [System.Drawing.GraphicsUnit]::Pixel)

    $gr.Dispose(); $path.Dispose()
    return $bmp
}

# --------------------------------------------------------------- keluaran
$densities = [ordered]@{ mdpi = 48; hdpi = 72; xhdpi = 96; xxhdpi = 144; xxxhdpi = 192 }

foreach ($name in $densities.Keys) {
    $size = $densities[$name]
    $dir = Join-Path $ResDir "mipmap-$name"
    New-Item -ItemType Directory -Force $dir | Out-Null

    $square = New-LegacyIcon -size $size -round $false
    $square.Save((Join-Path $dir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $square.Dispose()

    $circle = New-LegacyIcon -size $size -round $true
    $circle.Save((Join-Path $dir "ic_launcher_round.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $circle.Dispose()

    # Lapisan depan adaptif: kanvas 108dp. Artwork dibuat 0,64 dari kanvas
    # (sekitar 69dp) sehingga tetap di dalam zona aman 72dp, sementara tepi
    # tile jatuh sedikit di luar area yang ditampilkan mask -- jadi sambungan
    # warna antara tile dan latar tidak ikut terlihat.
    $fgSize = [int][Math]::Round($size * 108.0 / 48.0)
    $fg = New-Composite -size $fgSize -artFraction 0.64
    $fg.Save((Join-Path $dir "ic_launcher_foreground.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $fg.Dispose()

    Write-Host ("mipmap-{0,-8} ikon {1}px, lapisan adaptif {2}px" -f $name, $size, $fgSize)
}

# Warna latar ikon adaptif, dipakai kalau mask menampilkan area di luar tile.
$colorsDir = Join-Path $ResDir "values"
New-Item -ItemType Directory -Force $colorsDir | Out-Null
@"
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Rata-rata biru latar ikon, diukur dari berkas sumber. -->
    <color name="ic_launcher_background">$avgHex</color>
</resources>
"@ | Set-Content -Encoding UTF8 (Join-Path $colorsDir "ic_launcher_background.xml")

$src.Dispose()
Write-Host "Selesai."
