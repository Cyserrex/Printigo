# Menyiapkan isi secret KEYSTORE_BASE64 untuk GitHub Actions.
#
# GitHub Actions tidak bisa membaca berkas dari komputer Anda, jadi kunci
# penandatanganan harus dititipkan sebagai secret. Secret hanya menerima teks,
# sedangkan .jks adalah berkas biner -- karena itu diubah dulu ke base64.
#
# Hasilnya ditulis ke berkas, **tidak ditampilkan di layar**. Isinya adalah
# kunci penandatanganan aplikasi Anda: siapa pun yang memilikinya bisa membuat
# "pembaruan Printigo" palsu yang dipasang mulus di HP Anda. Jangan pernah
# menempelkannya ke chat, isu, atau pesan.
#
# Pemakaian:
#   powershell -File tools\keystore_base64.ps1

param(
    [string]$Keystore = "",
    [string]$Out = "keystore\KEYSTORE_BASE64.txt"
)

$ErrorActionPreference = "Stop"

# Kalau tidak disebut, dibaca dari keystore.properties supaya tidak ada nama
# berkas yang perlu diingat.
if (-not $Keystore) {
    if (-not (Test-Path "keystore.properties")) {
        throw "keystore.properties tidak ada. Sebutkan -Keystore path\ke\kunci.jks"
    }
    $line = Select-String -Path "keystore.properties" -Pattern '^\s*storeFile\s*=' |
        Select-Object -First 1
    if (-not $line) { throw "storeFile tidak ada di keystore.properties" }
    $Keystore = ($line.Line -split '=', 2)[1].Trim()
}

if (-not (Test-Path $Keystore)) { throw "Kunci tidak ditemukan: $Keystore" }

$dir = Split-Path $Out -Parent
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }

$bytes = [System.IO.File]::ReadAllBytes((Resolve-Path $Keystore))
# Satu baris tanpa pemisah: base64 -d di runner membacanya apa adanya.
[System.IO.File]::WriteAllText(
    $Out,
    [System.Convert]::ToBase64String($bytes),
    (New-Object System.Text.UTF8Encoding($false))
)

Write-Host "Ditulis ke: $Out"
Write-Host ("Ukuran: " + (Get-Item $Out).Length + " karakter")
Write-Host ""
Write-Host "Buka berkas itu, salin seluruh isinya, tempelkan sebagai secret"
Write-Host "KEYSTORE_BASE64 di GitHub. Setelah itu berkasnya boleh dihapus."
Write-Host ""
Write-Host "Jangan tampilkan isinya di layar bersama orang lain, dan jangan"
Write-Host "menempelkannya ke mana pun selain kolom secret di GitHub." -ForegroundColor Yellow
