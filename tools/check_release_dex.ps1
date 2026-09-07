# Memeriksa APK rilis yang sudah dikecilkan R8.
#
# R8 membuang apa yang tampaknya tidak dipakai. Yang dicari lewat refleksi --
# terutama konstruktor PrintViewModel(Application) yang dipanggil
# AndroidViewModelFactory -- tidak terlihat olehnya. Kalau aturan keep salah
# tulis, hilangnya baru ketahuan saat aplikasi dibuka di HP, bukan saat
# dibangun. Skrip ini membuka DEX-nya dan memastikan hal itu memang ada.
#
# Pemakaian:
#   powershell -File tools\check_release_dex.ps1
#   powershell -File tools\check_release_dex.ps1 -Apk path\ke\lain.apk

param(
    [string]$Apk = "app\build\outputs\apk\release\Printigo-v2.1-release.apk",
    [string]$Sdk = "C:\Claude\android-sdk"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $Apk)) { throw "APK tidak ditemukan: $Apk" }

$dexdump = Get-ChildItem "$Sdk\build-tools\*\dexdump.exe" |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $dexdump) { throw "dexdump tidak ada di $Sdk\build-tools" }

# APK diekstrak ke folder sementara: dexdump membaca berkas .dex, bukan zip.
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("dexcheck-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $work | Out-Null
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $Apk))
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -match '^classes\d*\.dex$') {
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile(
                    $entry, (Join-Path $work $entry.FullName), $true)
            }
        }
    } finally { $zip.Dispose() }

    $dexFiles = Get-ChildItem "$work\*.dex"
    if (-not $dexFiles) { throw "Tidak ada classes.dex di dalam APK" }
    Write-Host ("DEX ditemukan: " + $dexFiles.Count)

    $dump = & $dexdump.FullName -d $dexFiles.FullName 2>$null | Out-String

    # Konstruktor diperiksa DI DALAM blok kelasnya sendiri. Mencari
    # "(Landroid/app/Application;)V" di seluruh dump tidak membuktikan apa-apa:
    # kelas lain mana pun bisa punya tanda tangan yang sama.
    $failed = 0
    $blocks = $dump -split "Class descriptor\s*:\s*"
    $vm = $blocks | Where-Object { $_ -like "'Lcom/escpr/usbprint/ui/PrintViewModel;'*" }

    if (-not $vm) {
        Write-Host "  HILANG kelas PrintViewModel" -ForegroundColor Red
        $failed++
    } else {
        Write-Host "  OK    kelas PrintViewModel"
        if ($vm -match "\(Landroid/app/Application;") {
            Write-Host "  OK    konstruktor PrintViewModel(Application)"
        } else {
            Write-Host "  HILANG konstruktor PrintViewModel(Application)" -ForegroundColor Red
            $failed++
        }
    }

    if ($blocks | Where-Object { $_ -like "'Lcom/escpr/usbprint/ui/MainActivity;'*" }) {
        Write-Host "  OK    MainActivity"
    } else {
        Write-Host "  HILANG MainActivity" -ForegroundColor Red
        $failed++
    }

    # Nama enum inilah yang tersimpan di SharedPreferences. Kalau teksnya tidak
    # ada lagi di DEX, pengaturan lama tidak akan pernah cocok kembali.
    foreach ($name in @("A4", "LETTER", "PHOTO_4R")) {
        if ($dump.Contains('"' + $name + '"')) {
            Write-Host ("  OK    nama enum " + $name)
        } else {
            Write-Host ("  HILANG nama enum " + $name) -ForegroundColor Red
            $failed++
        }
    }

    if ($blocks | Where-Object { $_ -like "'Lcom/escpr/usbprint/escpr/PaperSize;'*" }) {
        Write-Host "  OK    kelas PaperSize tidak berganti nama"
    } else {
        Write-Host "  HILANG kelas PaperSize tidak berganti nama" -ForegroundColor Red
        $failed++
    }

    if ($failed -gt 0) {
        throw "$failed hal yang seharusnya dijaga tidak ada di DEX rilis"
    }
    Write-Host "Semua yang dijaga ada di dalam APK rilis." -ForegroundColor Green
} finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
