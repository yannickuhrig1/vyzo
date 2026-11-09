# Vyzo — lecteur IPTV pour Android TV, téléphone et PC (Linux, Windows)

Application IPTV en Kotlin + Compose (Jetpack Compose sur Android, Compose Multiplatform sur PC). Elle reprend les fonctionnalités d'un lecteur comme TiviMate,
**sans réutiliser son code, ses ressources ni son nom**. L'interface TV s'appuie en partie sur le projet libre IPVIO Stream (GPL-3.0, voir [Licence](#licence)).
Vyzo ne fournit aucun contenu : il lit les sources que l'utilisateur ajoute.

Identifiant Android : `fr.yannickuhrig.vyzo` (l'appli s'appelait Zapette jusqu'à la 1.1.0 ; ses QR codes,
liens `zapette://` et sauvegardes restent reconnus).

## Fonctionnalités

| Domaine | Ce qui est fait |
|---|---|
| Sources | M3U/M3U8 (URL ou fichier, gz accepté), Xtream Codes (API `player_api.php`), portail Stalker/Ministra (MAC). Plusieurs playlists : une active, ou toutes affichées ensemble. Un lien `get.php?username=…` est détecté et converti en Xtream. |
| Abonnement | Xtream et Stalker : date d'expiration, connexions en cours / autorisées, état du compte, affichés dans les réglages. Alerte quelques jours avant l'expiration. Quand le serveur refuse un flux, le lecteur explique pourquoi (limite de connexions atteinte, abonnement expiré). |
| Profils | Un profil par personne du foyer : progression des films et séries, chaînes récentes, favoris et groupes propres à chacun. Écran « Qui regarde ? » au lancement, couleur, code PIN de profil, profil enfant. Un **profil local** reste sur son appareil : jamais envoyé au compte, à une TV ni dans un fichier exporté, avec sa propre sauvegarde sur place. |
| Compte Vyzo | Compte par adresse e-mail (Supabase) : profils, progression, favoris et groupes synchronisés entre la TV et le téléphone, ainsi que les playlists, le guide, les réglages communs et les chaînes, chiffrés sur l'appareil. La TV peut se connecter en scannant un QR code avec le téléphone. Voir [Compte et synchro](#compte-et-synchro). |
| Contrôle parental | Code parental, catégories verrouillées (au choix, ou détection automatique des catégories « adultes » par leur nom et le marquage `is_adult` du fournisseur), chaînes verrouillées. Le contenu verrouillé disparaît partout (direct, guide, films, séries, recherche, favoris, récents) tant que le code n'a pas été saisi ; invisible sur les profils enfant. |
| TV en direct | Groupes de la playlist, Favoris, Récentes, groupes personnalisés. Aperçu vidéo sur TV (OK = aperçu, OK encore = plein écran). Personnalisation : renommer, renuméroter, masquer, verrouiller une chaîne ; renommer, masquer, réordonner, verrouiller une catégorie. Conservé entre deux mises à jour de la playlist. |
| Guide TV (EPG) | Grille horaire façon TiviMate (télécommande et tactile). XMLTV (.xml / .xml.gz) détecté depuis la playlist (`url-tvg`, `xmltv.php`) ou ajouté à la main. Association par `tvg-id` puis par nom normalisé, ou choisie à la main pour une chaîne (conservée). Décalage horaire et taille de la grille (compacte, normale, grande) réglables. |
| Replay (catch-up) | Xtream (`/timeshift/…`), M3U (`catchup`, `catchup-source`, types default / append / shift / flussonic / xc), Stalker (`tv_archive`). |
| Films et séries | Catégories, « Reprendre », Favoris, « Ajoutés récemment », « Les mieux notés », **genres automatiques** (Action, Comédie, Anime… reconnus dans le genre du titre et le nom des catégories du fournisseur, en plusieurs langues), fiches détaillées, saisons et épisodes, « Aller à l'épisode » (n° ou S2E5) pour les longues séries, reprise de lecture, épisode suivant automatique, langue audio et sous-titres retenus par série. Stalker : chargement à la demande, page par page. |
| Lecteur | Media3 ExoPlayer + décodeur FFmpeg (AC3, E-AC3, DTS, MP2…), HLS, DASH, MPEG-TS, DRM ClearKey/Widevine (`#KODIPROP`). Pistes audio et sous-titres, qualité, format d'image, zapping ▲▼, saisie du numéro, chaîne précédente, liste des chaînes, mini-guide (programmes de la chaîne et des autres sans zapper), reconnexion automatique. Incrustation (PiP) en quittant l'appli. « Ouvrir dans un autre lecteur » (VLC, MX Player, Kodi… ; VLC ou mpv sur PC) depuis une chaîne, un film ou une série. |
| Pause du direct | Sur une chaîne avec replay : reprise via le replay du fournisseur à l'heure de la pause. Sinon : mémoire tampon sur l'appareil (jusqu'à 2 h, réglable), lue pendant qu'elle se remplit. Retour au direct à tout moment. |
| Rappels | Alarme exacte, notification et ouverture de l'appli (si l'autorisation de superposition est donnée). |
| Enregistrements | Programmés depuis le guide (marges avant/après) ou immédiats, ou **toute une série** (chaque nouvelle diffusion du même titre sur la chaîne). Service au premier plan, flux TS continus et HLS (y compris AES-128). Dossier au choix (clé USB, carte SD, dossier partagé, via le sélecteur Android), sinon `Android/data/fr.yannickuhrig.vyzo/files/Movies/Enregistrements`. Les enregistrements qui dépassent le nombre de connexions de l'abonnement sont signalés. |
| Téléphone ↔ TV | La TV affiche un QR code : scanné avec l'appareil photo du téléphone, il ouvre une page servie par la TV pour saisir la playlist (Xtream, M3U, Stalker) sans télécommande. Vyzo sur téléphone peut aussi scanner ce QR code puis **cocher ce qu'il envoie** (chaque playlist, compte Vyzo, profils et favoris, guides, chaînes et catégories, réglages, enregistrements programmés) ; la TV demande confirmation et garde tout ce qui n'est pas coché. Sur téléphone, l'assistant peut aussi remplir le formulaire en scannant un QR code de fournisseur (lien M3U, lien Xtream `get.php`, portail + MAC). |
| Divers | Recherche (chaînes, programmes, films, séries) tolérante aux fautes de frappe (« harry poter », « spiderman ») avec recherche vocale, mise à jour automatique (WorkManager), sauvegarde/restauration JSON (profils compris), redémarrage des rappels et enregistrements après reboot. |
| Maintenance | Mises à jour de l'appli sans Play Store (voir [Publier une mise à jour](#publier-une-mise-à-jour)), rapports de plantage (désactivables), journal de diagnostic exportable (échanges Stalker/Xtream, erreurs de lecture, identifiants et adresse MAC masqués), profil de référence (Baseline Profile) pour un démarrage fluide. |

## Interface : TV et téléphone

Sur Android, deux applis distinctes sont construites depuis `app/` : **Vyzo TV** (`fr.yannickuhrig.vyzo`, interface
télécommande) et **Vyzo mobile** (`fr.yannickuhrig.vyzo.mobile`, interface tactile). L'interface dépend de l'appli installée.

- **TV** : rail de navigation à gauche, tout se pilote à la croix directionnelle. Appui long sur OK ou touche Menu = menu
  contextuel (favoris, groupes, enregistrer…).
- **Téléphone** : barre d'onglets en bas, appui long = menu contextuel, lecteur en paysage avec gestes (glisser
  verticalement pour zapper, double-tap pour avancer/reculer).

Touches du lecteur en direct : ▲▼ zapper · OK liste des chaînes · ◀ liste · ▶ infos, ▶ encore mini-guide (touche Guide
aussi) · 0-9 numéro · 0 seul ou « dernière chaîne » : chaîne précédente · ▶❚❚ pause du direct · ■ retour au direct ·
Menu options. En VOD : OK lecture/pause · ◀▶ reculer/avancer (maintenir pour accélérer) · ▲ options.

## Vyzo PC (Linux et Windows)

Même code que l'appli Android (module `shared`), avec l'interface TV adaptée au clavier et à la souris.

| | Linux (Mint, Ubuntu, Debian) | Windows 10/11 |
|---|---|---|
| Installeur | `vyzo_x.y.z_amd64.deb` (double-clic) : installe aussi VLC (`libvlc5`) | `Vyzo-x.y.z.msi`, VLC inclus |
| Données | `~/.local/share/vyzo` | `%APPDATA%\Vyzo` |
| Enregistrements | dossier Vidéos → `Vyzo` | dossier Vidéos → `Vyzo` |
| Lecteur | libVLC du système (décodage matériel VA-API) | libVLC fourni (Direct3D 11) |

**Clavier :** ↑↓ ou Page↑/Page↓ zapper · Entrée liste des chaînes · Échap retour · F ou F11 plein écran ·
Espace pause · M couper le son · 0-9 numéro de chaîne · Ctrl+Q quitter.
**Souris :** survol du rail pour l'ouvrir, clic droit = menu contextuel, double-clic = plein écran, molette dans le guide,
bouton « précédent » = retour.

- **Zone de notification :** fermer la fenêtre laisse Vyzo tourner (rappels, enregistrements, synchro) ; « Quitter » dans
  le menu de l'icône ou Ctrl+Q pour l'arrêter. Option « Lancer Vyzo à l'ouverture de session » dans les réglages.
- **Mini-lecteur :** petite fenêtre toujours au premier plan (bouton en haut du lecteur).
- **Téléphone → PC :** Réglages → « Recevoir depuis un téléphone » affiche le même QR code que la TV (port 8642).
  Sous Windows, autoriser Vyzo sur les réseaux privés à la première ouverture.
- **Premier lancement sous Windows :** l'installeur n'est pas signé, SmartScreen affiche un avertissement →
  « Informations complémentaires » → « Exécuter quand même ».

## Architecture

```
shared/                Code commun Android + PC (Kotlin Multiplatform)
├── commonMain/        Sources, guide, base Room, réglages, synchro, enregistreur, réception téléphone, écrans Compose
├── androidMain/       Spécificités Android de l'interface (sélecteurs de fichiers, autorisations…)
├── desktopMain/       Spécificités PC de l'interface (Échap, sélecteurs de fichiers, curseur…)
└── sharedTest/        Tests JVM exécutés par app et desktop
app/                   Android : MainActivity, ExoPlayer, WorkManager, alarmes, service d'enregistrement
desktop/               PC : fenêtre, lecteur libVLC (vlcj), zone de notification, mini-lecteur, minuteries, installeurs
```

Détail du code commun (`shared/src/commonMain/kotlin/fr/yannickuhrig/vyzo/`) :

```
├── AppContainer.kt    Conteneur d'injection manuel (une implémentation de Platform par système)
├── data/
│   ├── db/            Room : playlists, catégories, chaînes, films, séries, épisodes, programmes, groupes, progression, rappels, enregistrements
│   ├── source/        Clients M3U (parseur en flux), Xtream, Stalker
│   ├── epg/           Parseur XMLTV en flux (XmlPullParser)
│   ├── catchup/       Construction des URL de replay M3U
│   ├── sync/          SyncManager (synchro playlists + guides) et SyncWorker (périodique)
│   ├── repo/          Repository : requêtes pour l'UI, résolution des flux, favoris, recherche
│   ├── prefs/         Réglages (DataStore, JSON sérialisé)
│   ├── profile/       Profils (ProfileManager) et contrôle parental (ParentalControl)
│   ├── cloud/         Compte Vyzo : client Supabase (CloudApi) et synchro (CloudSync)
│   └── backup/        Export / import de la configuration
├── data/remote/       Serveur local de réception (jeton par session), page web pour le téléphone, envoi téléphone → TV, analyse des QR codes
├── player/            Interface du lecteur (ExoPlayer sur Android, libVLC sur PC), pause du direct (Timeshift), incrustation (Pip)
├── schedule/          Rappels, enregistrements (enregistreur TS/HLS avec garde-fous : disque plein, flux qui boucle ; séries, dossier choisi)
├── diag/              Rapports de plantage, journal de diagnostic
├── update/            Mises à jour intégrées (latest.json ; APK sur Android, .deb ou .msi sur PC)
└── ui/                Écrans Compose (profils, direct, guide, films/séries, lecteur, recherche, enregistrements, réglages, compte)
```

Les grosses playlists (dizaines de milliers d'entrées) sont lues en flux et insérées par lots ; seuls les programmes EPG
des chaînes présentes et de la fenêtre de jours choisie sont conservés.

## Compiler

Prérequis : JDK 17+, SDK Android (plateforme 35). Les versions sont figées dans `app/build.gradle.kts`
(AGP 8.11, Kotlin 2.2, Compose BOM 2025.08, Media3 1.8).

```bash
./gradlew :app:assembleTvRelease         # APK TV signé : app/build/outputs/apk/tv/release/app-tv-release.apk
./gradlew :app:assembleMobileRelease     # APK mobile signé : app/build/outputs/apk/mobile/release/app-mobile-release.apk
./gradlew :app:testTvDebugUnitTest       # tests Android (parseurs, synchro de bout en bout, captures d'écran)

./gradlew :desktop:run                   # lancer Vyzo PC depuis les sources (VLC requis)
./gradlew :desktop:test                  # tests PC
./gradlew :desktop:packageReleaseDeb     # Linux : desktop/build/compose/binaries/main-release/deb/
desktop/packaging/deb-depends.sh <fichier.deb>   # ajoute VLC aux dépendances du paquet
```

Sous Windows (JDK 17+ et WiX 3 dans le PATH) :

```powershell
powershell -ExecutionPolicy Bypass -File desktop\packaging\prepare-vlc-windows.ps1   # copie VLC dans les ressources
.\gradlew :desktop:packageReleaseMsi     # desktop\build\compose\binaries\main-release\msi\
```

Le workflow GitHub Actions `.github/workflows/vyzo-pc.yml` fait les deux : lancé à la main (onglet Actions) ou à chaque
tag `v*`, il construit le `.deb` et le `.msi` et les joint à la release.

La signature utilise `keystore/zapette.jks` (nom historique, à garder tel quel) et `keystore/keystore.properties`. **Garde ces deux fichiers** : sans eux,
tu ne pourras pas installer une mise à jour par-dessus l'appli existante. Ne les publie pas sur un dépôt public.
Aucun jeton GitHub n'est intégré aux paquets : `scripts/verifier-sans-jeton.sh` le vérifie avant chaque publication
(workflow et `publier-mise-a-jour.sh`).

## Tests

- `ParsersTest` : M3U (attributs, virgules entre guillemets, en-têtes Kodi), XMLTV gzip, modèles de replay, URL Xtream,
  JSON en flux, normalisation des noms.
- `SyncIntegrationTest` (Robolectric + faux serveur) : synchro M3U et Xtream complètes, association EPG, replay, films,
  séries, favoris, sauvegarde aller-retour, mauvais mot de passe.
- `RemoteConfigTest` : analyse des QR codes, serveur de réception (jeton, formulaire, playlist, configuration, statut) testé avec le client du téléphone.
- `StalkerPortalTest` (faux portail) : poignée de main, chaînes, genres, guide, liens de lecture et de replay, VOD page par
  page, reconnexion quand le jeton expire, fin d'abonnement, masquage du journal de diagnostic.
- `CloudSyncTest` (faux Supabase) : deux appareils sur le même compte (profils, progression, favoris, groupes,
  fusion des profils de même nom, la progression la plus récente l'emporte, message d'erreur en français).
  `LiveSupabaseTest` refait l'essai contre le vrai projet, seulement si
  `VYZO_LIVE_EMAIL` et `VYZO_LIVE_PASSWORD` sont définis.
- `RealtimeClientTest` (faux serveur WebSocket) : canal privé du compte, abonnement aux tables, notifications,
  battements de cœur, jeton renouvelé, reconnexion et rattrapage après une coupure.
- `MigrationTest` : une base de la version 1.2 passe en version 2 sans rien perdre (progression, favoris, groupes).
- `TvScreensTest` / `PhoneScreensTest` : rendu des écrans avec de fausses données et captures dans `app/screenshots/`.
- `RecorderTest` (commun) : un serveur qui renvoie une vidéo courte en boucle, ou un disque presque plein, arrêtent
  l'enregistrement au lieu de remplir le disque.
- `DesktopSyncTest` : synchro M3U et Xtream, guide et sauvegarde avec la base et les réglages de Vyzo PC.

## Identité visuelle

Logo « Le Rebond » : un V en deux rubans (cyan → violet, magenta → jaune) avec un triangle lecture au point de rebond.
Sources dans `branding/` (`vyzo-icon.svg`, `vyzo-banner.svg`). Dans l'appli :

- icône adaptative : fond blanc, avant-plan PNG (`mipmap-*/ic_launcher_foreground.png`), couche monochrome vectorielle
  pour les icônes à thème d'Android 13+ ; PNG complets pour Android 6–7 ;
- bannière Android TV `drawable-xhdpi/banner.png` (320 × 180) et `drawable-xxxhdpi` (640 × 360) ;
- `ui/common/Brand.kt` : le V redessiné en Compose (`VyzoMark`, `VyzoWordmark`) et les couleurs du logo (`VyzoBrand`).

## Configuration depuis le téléphone

1. Sur la TV : premier lancement → « Saisir depuis mon téléphone », ou Réglages → « Recevoir depuis un téléphone ».
2. Avec l'appareil photo du téléphone, scanner le QR code : une page s'ouvre, saisir la playlist, « Envoyer à la TV ».
3. Ou, avec Vyzo sur le téléphone : Réglages → « Envoyer vers une TV », scanner le QR code, cocher ce qu'il faut envoyer, puis accepter sur la TV.

Le serveur n'écoute que pendant que l'écran de réception est ouvert, sur le port 8642 du réseau local, et refuse toute requête sans le jeton du QR code.

## Compte et synchro

Le compte Vyzo est un compte du foyer : on s'y connecte une fois par appareil (Réglages → Profils et compte), et tous
les profils qu'il contient suivent. Sur la TV, « Se connecter depuis le téléphone » affiche un QR code : la page qui
s'ouvre sur le téléphone envoie l'adresse et le mot de passe à la TV (même réseau Wi-Fi), qui se connecte elle-même.

- Synchronisés : profils (nom, couleur, enfant, code PIN), progression des films et épisodes, favoris et groupes de
  chaque profil.
- Synchronisés et chiffrés sur l'appareil : playlists avec leurs identifiants, sources de guide ajoutées à la main,
  personnalisations de chaînes et de catégories, réglages communs (langues, guide, zapping, marges d'enregistrement,
  clés TMDB et OpenSubtitles). Le serveur n'en garde que des blocs illisibles (table `sealed_items`). Restent sur chaque
  appareil : mode d'affichage, décodeur, code parental, dossiers, mises à jour, et les playlists en fichier local.
- Clé de ces données : une clé commune aux appareils du compte, gardée sur le serveur chiffrée par une clé tirée du mot
  de passe (PBKDF2, AES-256-GCM). Le mot de passe sert une fois sur chaque appareil. Après une réinitialisation, un
  appareil qui a encore la clé la reprotège à sa prochaine connexion ; s'il n'y en a plus, « Repartir de cet appareil »
  remplace ce qui est illisible. Supabase Auth reçoit toujours le mot de passe à la connexion, comme pour tout compte :
  ce chiffrement protège ce qui est stocké, il ne vaut pas contre un serveur d'authentification compromis.
- Une playlist supprimée sur un appareil l'est partout. À la première connexion rien n'est supprimé : la version du
  compte l'emporte, et ce qui n'existe que sur l'appareil rejoint le compte.
- Une progression retrouve son film sur un autre appareil quand les deux ont la même source : même compte Xtream,
  même portail et même MAC, ou même adresse M3U. L'adresse du serveur Xtream peut changer sans rien perdre.
- Quand un appareil se connecte pour la première fois, ses profils sont fusionnés avec ceux du compte qui portent le
  même nom (le profil « Principal » créé à l'installation rejoint le premier profil du compte s'il n'a encore rien
  enregistré).
- En cas de modification des deux côtés, la plus récente l'emporte. Envoi 8 secondes après un changement et en
  quittant l'appli. Tant que l'appli est ouverte, une connexion permanente (Supabase Realtime) prévient l'appareil dès
  qu'un autre modifie le compte : il récupère aussitôt la nouvelle progression ou le nouveau favori. La connexion est
  fermée en arrière-plan ; au retour dans l'appli, elle se rouvre et rattrape ce qui a changé.

Vyzo PC utilise le même compte : profils, progression, favoris et groupes passent du PC à la TV et au téléphone.

Côté serveur : projet Supabase « Vyzo » (Paris). Tables `profiles`, `watch_progress`, `user_lists`, `list_items`, `sealed_items`
protégées par des règles d'accès par compte et publiées pour le temps réel (chaque appareil ne reçoit que les
notifications de son compte), `crash_reports` (écriture seule), dossier public `releases` pour les mises à jour.
Les commandes entre appareils (Envoyer vers, programmations, purges) passent par le canal privé `user:<uid>` du compte,
réservé à ses jetons par les règles de `supabase/migrations/20261031155531_realtime_canal_prive.sql` ; elles ne
contiennent jamais l'URL d'un flux. Pour pointer l'appli vers un autre projet : `./gradlew assembleRelease -Pvyzo.supabaseUrl=… -Pvyzo.supabaseKey=…` (valeurs par défaut dans `gradle.properties`).

## Publier une mise à jour

1. Augmenter `vyzo.versionCode` et `vyzo.versionName` dans `gradle.properties` (version commune à Android et au PC).
2. Pousser le tag `vX.Y.Z` : le workflow GitHub Actions « Vyzo PC » construit le `.deb` et le `.msi` et les joint à la
   release GitHub.
3. `SUPABASE_SECRET_KEY=sb_secret_… scripts/publier-mise-a-jour.sh "Ce qui change"` (clé secrète : Supabase → Project
   Settings → API Keys ; ne jamais la mettre dans le dépôt).

Le script compile les APK signés TV et mobile et le `.deb`, récupère le `.msi` de la release GitHub (commande `gh`, ou
`VYZO_MSI=…`), puis envoie le tout avec `latest-tv.json`, `latest-mobile.json` et `latest.json` pour le PC (version,
empreintes SHA-256, notes) dans le dossier `releases`. L'offre
gratuite de Supabase limite chaque fichier à 50 Mo : les paquets PC partent en morceaux de 45 Mo que Vyzo PC remet
bout à bout. Les appareils proposent la mise à jour au lancement (une fois par jour) ou via Réglages → Mises à jour :

- Android : installateur d'Android (autorisation « Installer des applis inconnues » à donner une fois) ; l'APK doit
  être signé avec la même clé que l'appli installée.
- Linux : le `.deb` s'ouvre dans l'installeur de paquets (mot de passe demandé).
- Windows : l'installeur Windows remplace la version installée ; Vyzo se ferme pour le laisser faire.

## Limites connues

- Le portail Stalker est testé contre un faux portail conforme au protocole MAG, pas encore contre un vrai.
  Le journal de diagnostic (Réglages → Diagnostic) permet de récupérer les échanges d'un portail réel.
- Les enregistrements partagent la connexion de l'abonnement : si le fournisseur limite à 1 connexion, on ne peut pas
  regarder une autre chaîne pendant un enregistrement (l'appli prévient).
- La pause du direct sans replay du fournisseur garde le flux sur l'appareil : elle demande de la place libre
  (environ 1 Go par demi-heure en HD).
- Le multi-écran utilise une connexion de l'abonnement par écran : Vyzo prévient quand il n'en reste plus de libre.
- PC : les chaînes protégées par DRM (`#KODIPROP`, Widevine/ClearKey) ne sont pas lisibles (libVLC ne les gère pas).
  Les rappels et enregistrements ne fonctionnent que si Vyzo est ouvert ou dans la zone de notification, PC allumé :
  Vyzo empêche la mise en veille pendant un enregistrement mais ne peut pas réveiller le PC.

## Licence

Copyright (C) 2026 Les contributeurs de Vyzo

Vyzo est un logiciel libre, distribué selon les termes de la **GNU General Public License version 3** (voir [LICENSE](LICENSE)).
Bibliothèques, code repris et services utilisés : [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
