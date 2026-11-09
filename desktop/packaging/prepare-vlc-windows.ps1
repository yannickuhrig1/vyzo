# Télécharge VLC 3 (64 bits) et copie libVLC dans les ressources de Vyzo PC pour l'installeur Windows.
#   powershell -ExecutionPolicy Bypass -File desktop\packaging\prepare-vlc-windows.ps1
param([string]$Version = "3.0.21")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$dest = Join-Path $root "desktop\resources\windows-x64\vlc"
$tmp = Join-Path $env:TEMP "vyzo-vlc"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$zip = Join-Path $tmp "vlc-$Version-win64.zip"
if (-not (Test-Path $zip)) {
    $url = "https://download.videolan.org/pub/videolan/vlc/$Version/win64/vlc-$Version-win64.zip"
    Write-Host "Téléchargement de $url"
    Invoke-WebRequest -Uri $url -OutFile $zip
}
Expand-Archive -Path $zip -DestinationPath $tmp -Force
$src = Join-Path $tmp "vlc-$Version"
if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Copy-Item (Join-Path $src "libvlc.dll"), (Join-Path $src "libvlccore.dll") $dest
Copy-Item -Recurse (Join-Path $src "plugins") (Join-Path $dest "plugins")
# Modules inutiles pour Vyzo (interfaces graphiques de VLC, scripts Lua, visualisations) : installeur plus léger.
foreach ($d in @("gui", "lua", "visualization")) {
    $p = Join-Path $dest "plugins\$d"
    if (Test-Path $p) { Remove-Item -Recurse -Force $p }
}
# Cache des modules régénéré après le tri (démarrage plus rapide de VLC).
$cacheGen = Join-Path $src "vlc-cache-gen.exe"
Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $dest "plugins\plugins.dat")
if (Test-Path $cacheGen) { & $cacheGen (Join-Path $dest "plugins") }
Copy-Item (Join-Path $src "COPYING.txt") (Join-Path $dest "COPYING-VLC.txt") -ErrorAction SilentlyContinue
$size = (Get-ChildItem -Recurse $dest | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host ("VLC {0} prêt dans {1} ({2:N0} Mo)" -f $Version, $dest, $size)
