# Mentions tierces

Vyzo est distribué sous la licence GNU GPL version 3 (voir `LICENSE`).

## Code repris d'un autre projet

Une partie de l'interface de Vyzo TV (`app/src/tv/java/fr/yannickuhrig/vyzo/tv/`) est dérivée d'**IPVIO Stream** :

- Projet d'origine : <https://github.com/Emrodev/IPVIO-Stream>
- Copyright (C) 2026 IPVIO Stream contributors, licence GNU GPL version 3
- Chaque fichier repris l'indique dans son en-tête, avec la date de sa modification pour Vyzo.

Le nom et le logo IPVIO identifient le projet d'origine : Vyzo ne les utilise pas.

## Bibliothèques

| Composant | Licence | Où |
|---|---|---|
| AndroidX (Compose, Room, Work, DataStore, CameraX, Lifecycle, Navigation) | Apache 2.0 | Android, PC |
| AndroidX Media3 (ExoPlayer, HLS, DASH, UI, Session) | Apache 2.0 | Android |
| Décodeur FFmpeg Media3 de Jellyfin (`org.jellyfin.media3:media3-ffmpeg-decoder`) | GNU GPL 3 (contient FFmpeg) | Android |
| Compose Multiplatform (JetBrains) | Apache 2.0 | Android, PC |
| OkHttp (Square) | Apache 2.0 | Android, PC |
| Coil | Apache 2.0 | Android, PC |
| ZXing | Apache 2.0 | Android, PC |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | Apache 2.0 | Android, PC |
| vlcj (Caprica) | GNU GPL 3 | PC |
| libVLC (VideoLAN) | GNU LGPL 2.1 ou ultérieure | PC |

Les bibliothèques sont téléchargées par Gradle à la compilation ; leurs textes de licence sont disponibles auprès de chaque projet.

## Services en ligne

| Service | Usage | Note |
|---|---|---|
| The Movie Database (TMDB) | Informations sur les films et les séries, images | *This product uses the TMDB API but is not endorsed or certified by TMDB.* |
| TheIntroDB | Repères de récap, d'intro et de générique | theintrodb.org |
| OMDb | Notes IMDb, Rotten Tomatoes et Metacritic, avec la clé personnelle de l'utilisateur | omdbapi.com |
| TVmaze | Séries retrouvées par leur titre | tvmaze.com, licence CC BY-SA |

Android TV, Google TV, Fire TV et TMDB sont des marques de leurs propriétaires respectifs. Vyzo n'est ni affilié à ces sociétés ni approuvé par elles.
