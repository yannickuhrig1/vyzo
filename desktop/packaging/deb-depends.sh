#!/usr/bin/env bash
# Ajoute VLC aux dépendances du .deb produit par jpackage, pour qu'apt (ou le double-clic sous Mint)
# installe automatiquement libVLC et ses modules avec Vyzo.
#   desktop/packaging/deb-depends.sh desktop/build/compose/binaries/main-release/deb/vyzo_1.3.0_amd64.deb
set -euo pipefail
deb="$(realpath "$1")"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
dpkg-deb -R "$deb" "$work/pkg"
control="$work/pkg/DEBIAN/control"
# jpackage termine le fichier par une ligne vide : on la retire, sinon les champs ajoutés forment un second paquet.
sed -i -e '/^[[:space:]]*$/d' -e 's/[[:space:]]*$//' "$control"
extra="libvlc5, vlc-plugin-base"
if grep -q '^Depends:' "$control"; then
  grep -q 'libvlc5' "$control" || sed -i "s/^Depends: \(.*\)$/Depends: \1, $extra/" "$control"
else
  echo "Depends: $extra" >> "$control"
fi
# Notifications avec boutons et sélecteur de fichiers GTK : présents sur Mint, recommandés ailleurs.
grep -q '^Recommends:' "$control" || echo "Recommends: libnotify-bin, zenity, xdg-utils" >> "$control"
sed -i 's/^Section: .*/Section: video/' "$control"
# Raccourci du menu : icône de la fenêtre associée au lanceur (barre des tâches de Cinnamon), mots-clés de recherche.
for d in "$work"/pkg/opt/vyzo/lib/*.desktop; do
  sed -i 's/^Categories=\(.*[^;]\)$/Categories=\1;/' "$d"
  grep -q '^StartupWMClass=' "$d" || echo "StartupWMClass=fr-yannickuhrig-vyzo-desktop-MainKt" >> "$d"
  grep -q '^Keywords=' "$d" || echo "Keywords=IPTV;TV;M3U;Xtream;EPG;" >> "$d"
  sed -i '/^MimeType=$/d' "$d"
done
# Sans menu XDG (système minimal), xdg-desktop-menu échoue et bloquait l'installation : on copie le raccourci à la main.
sed -i 's|^xdg-desktop-menu install \(.*\)$|xdg-desktop-menu install \1 \|\| install -Dm644 \1 /usr/share/applications/vyzo-Vyzo.desktop|' "$work/pkg/DEBIAN/postinst"
sed -i 's|^xdg-desktop-menu uninstall \(.*\)$|xdg-desktop-menu uninstall \1 \|\| rm -f /usr/share/applications/vyzo-Vyzo.desktop|' "$work/pkg/DEBIAN/prerm"
fakeroot dpkg-deb --build "$work/pkg" "$deb" >/dev/null
echo "Dépendances du paquet :"
dpkg-deb -f "$deb" Depends Recommends
