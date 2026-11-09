#!/usr/bin/env bash
# Refuse un paquet qui contient un jeton GitHub (ghp_…, github_pat_…) : les .apk, .deb et .jar sont dépaquetés,
# y compris les .jar qu'ils contiennent. Seul le nom du fichier fautif est affiché, jamais le jeton.
#
#   scripts/verifier-sans-jeton.sh dist/Vyzo-1.7.1.apk desktop/build/…/vyzo_1.7.1_amd64.deb
set -euo pipefail

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
found=0
for f in "$@"; do
  d="$work/$(basename "$f")"
  mkdir -p "$d"
  case "$f" in
    *.deb) dpkg-deb -x "$f" "$d" ;;
    *.apk | *.jar | *.zip) unzip -qo "$f" -d "$d" ;;
    *) cp "$f" "$d/" ;;
  esac
  find "$d" -name '*.jar' -print0 | while IFS= read -r -d '' j; do
    unzip -qo "$j" -d "$j.x" 2> /dev/null || true
  done
  if grep -rlaE 'gh[pousr]_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{22,}' "$d" > "$work/hits"; then
    echo "::error::$f contient un jeton GitHub :" >&2
    sed "s|^$d/||" "$work/hits" >&2
    found=1
  else
    echo "$f : aucun jeton GitHub"
  fi
done
exit "$found"
