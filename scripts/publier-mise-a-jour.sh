#!/usr/bin/env bash
# Publie une nouvelle version de Vyzo pour la mise à jour intégrée (Android et Vyzo PC).
#
# 1. Augmente vyzo.versionCode et vyzo.versionName dans gradle.properties.
#    (Fichiers déjà construits réutilisables : VYZO_APK_TV=… VYZO_APK_MOBILE=… VYZO_DEB=… VYZO_MSI=…)
# 2. Lance : SUPABASE_SECRET_KEY=sb_secret_… scripts/publier-mise-a-jour.sh "Ce qui change"
#
# Le script compile les APK signés TV et mobile (keystore/ nécessaire) et le paquet Linux (.deb), récupère l'installeur
# Windows (.msi) s'il est disponible, envoie le tout dans le dossier public « releases » du projet Supabase de Vyzo, puis
# met à jour latest-tv.json, latest-mobile.json et latest.json (Vyzo PC). Il y dépose aussi l'archive des sources de la
# version (vyzo-sources-X.Y.Z.tar.gz), que la GPL impose de fournir avec les applis : le dépôt doit donc être propre.
# Les appareils le proposent au prochain lancement (ou tout de suite avec Réglages → Mises à jour → Rechercher).
# Les APK signés sont aussi joints à la release GitHub vX.Y.Z, pour une installation à la main.
#
# Dans latest.json, le seul APK annoncé est la version de transition universelle (vyzo-1.10.5.apk) : l'ancienne appli
# unique (TV et téléphone) lit ce fichier et ne doit recevoir ni l'APK TV sur un téléphone ni l'inverse. Une fois installée,
# la transition lit latest-tv.json ou latest-mobile.json selon l'appareil.
#   VYZO_APK_PONT=chemin/vyzo-1.10.5.apk …   pour l'envoyer ; ensuite le script reprend l'empreinte déjà publiée.
#
# Installeur Windows : il se construit sous Windows (workflow GitHub Actions « Vyzo PC », lancé par le tag vX.Y.Z).
#   VYZO_MSI=chemin/Vyzo-X.Y.Z.msi …   pour un fichier déjà téléchargé ;
#   sinon le script le prend dans la release GitHub vX.Y.Z si la commande gh est connectée ;
#   sinon la version est publiée sans .msi (les PC Windows ne la verront pas).
# La clé secrète se trouve dans Supabase → Project Settings → API Keys. Ne la mets jamais dans le dépôt.
set -euo pipefail
# Une erreur d'envoi dans un $(…) doit aussi tout arrêter (sinon latest.json serait publié sans les fichiers).
shopt -s inherit_errexit
cd "$(dirname "$0")/.."

URL="${VYZO_SUPABASE_URL:-https://jucbmlwigbptgxqoiqih.supabase.co}"
# Dépôt explicite : avec deux remotes, gh refuse d'en choisir un dans un vrai terminal.
GH_REPO_NAME="${VYZO_GITHUB_REPO:-yannickuhrig1/vyzo}"
KEY="${SUPABASE_SECRET_KEY:?Indique la clé secrète Supabase : SUPABASE_SECRET_KEY=sb_secret_… $0 \"notes\"}"
NOTES="${1:-}"

if [ ! -f keystore/keystore.properties ]; then
  echo "keystore/keystore.properties introuvable : sans la clé de signature, la mise à jour ne pourrait pas s'installer." >&2
  exit 1
fi

VC=$(grep -m1 '^vyzo.versionCode=' gradle.properties | cut -d= -f2)
VN=$(grep -m1 '^vyzo.versionName=' gradle.properties | cut -d= -f2)
echo "Version $VN ($VC)"

# L'archive des sources doit correspondre exactement à ce qui est compilé.
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "Des fichiers suivis sont modifiés : valide-les avant de publier (les sources publiées doivent correspondre aux APK)." >&2
  exit 1
fi

# VYZO_APK_TV / VYZO_APK_MOBILE / VYZO_DEB : fichiers déjà construits ; sinon ils sont compilés ici.
APK_TV="${VYZO_APK_TV:-}"
APK_MOBILE="${VYZO_APK_MOBILE:-}"
DEB="${VYZO_DEB:-}"
if [ -z "$APK_TV" ] || [ -z "$APK_MOBILE" ] || [ -z "$DEB" ]; then
  echo "Compilation des APK et du paquet Linux…"
  ./gradlew -q :app:assembleTvRelease :app:assembleMobileRelease :desktop:packageReleaseDeb
  [ -n "$APK_TV" ] || APK_TV=app/build/outputs/apk/tv/release/app-tv-release.apk
  [ -n "$APK_MOBILE" ] || APK_MOBILE=app/build/outputs/apk/mobile/release/app-mobile-release.apk
  if [ -z "$DEB" ]; then
    DEB=$(ls desktop/build/compose/binaries/main-release/deb/*.deb | head -1)
    desktop/packaging/deb-depends.sh "$DEB" > /dev/null
  fi
fi

# Le dossier « releases » est public : on vérifie qu'aucun jeton GitHub n'y part.
scripts/verifier-sans-jeton.sh "$APK_TV" "$APK_MOBILE" "$DEB"

MSI="${VYZO_MSI:-}"
if [ -z "$MSI" ] && command -v gh > /dev/null; then
  TMP_MSI=$(mktemp -d)
  # 170 Mo : une coupure réseau en cours de route arrive. Un fichier incomplet n'est jamais retenu.
  for attempt in 1 2 3; do
    if gh release download "v$VN" -R "$GH_REPO_NAME" -p '*.msi' -D "$TMP_MSI" --clobber; then
      MSI=$(ls "$TMP_MSI"/*.msi | head -1)
      break
    fi
    echo "Téléchargement du .msi interrompu (essai $attempt sur 3)." >&2
  done
fi

upload() { # upload <fichier local> <nom distant> <type>
  curl -fsS -X POST "$URL/storage/v1/object/releases/$2" \
    -H "apikey: $KEY" -H "x-upsert: true" -H "cache-control: max-age=60" -H "Content-Type: $3" \
    --data-binary @"$1" > /dev/null
}
sha() { sha256sum "$1" | cut -d' ' -f1; }
# Le dossier « releases » doit accepter le type application/octet-stream (Supabase → Storage → releases → Edit).
# Offre gratuite de Supabase : 50 Mo au plus par fichier. Les paquets PC sont envoyés en morceaux de 45 Mo
# (« nom.000 », « nom.001 »…) que Vyzo PC remet bout à bout. Affiche le nombre de morceaux.
upload_parts() { # upload_parts <fichier local> <nom distant> <type>
  local dir n=0
  dir=$(mktemp -d)
  split -b 45M -d -a 3 "$1" "$dir/part."
  for f in "$dir"/part.*; do
    upload "$f" "$2.${f##*.}" "$3"
    n=$((n + 1))
  done
  rm -rf "$dir"
  echo "$n"
}

publish_apk() { # publish_apk <variante> <fichier local> : envoie l'APK, puis le fichier de version qui l'annonce
  local name="vyzo-$1-$VN.apk" manifest
  echo "Envoi de $name…"
  upload "$2" "$name" "application/vnd.android.package-archive"
  manifest=$(mktemp)
  python3 - "$VC" "$VN" "$name" "$(sha "$2")" "$NOTES" > "$manifest" <<'PY'
import json, sys
vc, vn, apk, apk_sha, notes = sys.argv[1:6]
print(json.dumps({
    "versionCode": int(vc), "versionName": vn, "notes": notes,
    "apk": apk, "sha256": apk_sha, "minSdk": 23,
}, ensure_ascii=False, indent=2))
PY
  upload "$manifest" "latest-$1.json" "application/json"
  rm -f "$manifest"
}

# Les sources partent avant les APK : aucune version n'est annoncée sans elles.
SRC_NAME="vyzo-sources-$VN.tar.gz"
SRC=$(mktemp)
git archive --format=tar.gz --prefix="vyzo-$VN/" -o "$SRC" HEAD
echo "Envoi de $SRC_NAME…"
upload "$SRC" "$SRC_NAME" "application/octet-stream"
rm -f "$SRC"

# Version de transition proposée aux anciennes applis, dans latest.json.
PONT_NAME="vyzo-1.10.5.apk"
if [ -n "${VYZO_APK_PONT:-}" ]; then
  echo "Envoi de $PONT_NAME…"
  upload "$VYZO_APK_PONT" "$PONT_NAME" "application/vnd.android.package-archive"
  PONT_SHA=$(sha "$VYZO_APK_PONT")
else
  PONT_SHA=$(curl -fsS "$URL/storage/v1/object/public/releases/latest.json" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print(d["sha256"] if d.get("apk") == sys.argv[1] else "")' "$PONT_NAME")
  if [ -z "$PONT_SHA" ]; then
    echo "$PONT_NAME n'est pas encore publiée : indique VYZO_APK_PONT=chemin/$PONT_NAME" >&2
    exit 1
  fi
fi

publish_apk tv "$APK_TV"
publish_apk mobile "$APK_MOBILE"

DEB_NAME="vyzo_${VN}_amd64.deb"
echo "Envoi de $DEB_NAME…"
DEB_PARTS=$(upload_parts "$DEB" "$DEB_NAME" "application/octet-stream")
MSI_NAME=""
MSI_SHA=""
MSI_PARTS=0
if [ -n "$MSI" ]; then
  MSI_NAME="Vyzo-$VN.msi"
  MSI_SHA=$(sha "$MSI")
  echo "Envoi de $MSI_NAME…"
  MSI_PARTS=$(upload_parts "$MSI" "$MSI_NAME" "application/octet-stream")
else
  echo "Pas d'installeur Windows pour cette version (voir l'en-tête du script)."
fi

LATEST=$(mktemp)
python3 - "$VC" "$VN" "$DEB_NAME" "$(sha "$DEB")" "$DEB_PARTS" "$MSI_NAME" "$MSI_SHA" "$MSI_PARTS" "$NOTES" "$PONT_NAME" "$PONT_SHA" > "$LATEST" <<'PY'
import json, sys
vc, vn, deb, deb_sha, deb_parts, msi, msi_sha, msi_parts, notes, pont, pont_sha = sys.argv[1:12]
print(json.dumps({
    "versionCode": int(vc), "versionName": vn, "notes": notes,
    "apk": pont, "sha256": pont_sha, "minSdk": 23,
    "deb": deb, "debSha256": deb_sha, "debParts": int(deb_parts),
    "msi": msi, "msiSha256": msi_sha, "msiParts": int(msi_parts),
}, ensure_ascii=False, indent=2))
PY
upload "$LATEST" "latest.json" "application/json"
rm -f "$LATEST"

# La mise à jour intégrée passe par Supabase : la release GitHub ne doit pas pouvoir faire échouer la publication.
attach_apks() {
  local dir
  dir=$(mktemp -d)
  # Noms lisibles sur la page GitHub ; « -TV » et « -mobile » restent, l'appli (canal bêta) cherche son APK par ces mots.
  cp "$APK_TV" "$dir/Vyzo-$VN-Android-TV.apk"
  cp "$APK_MOBILE" "$dir/Vyzo-$VN-Android-mobile.apk"
  if command -v gh > /dev/null && gh release upload "v$VN" -R "$GH_REPO_NAME" --clobber "$dir"/*.apk; then
    echo "APK joints à la release GitHub v$VN."
  else
    echo "APK non joints à la release GitHub v$VN (release absente ou gh non connecté)." >&2
  fi
  rm -rf "$dir"
}
attach_apks

echo "Publié : $URL/storage/v1/object/public/releases/latest.json"
