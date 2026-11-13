# Vyzo Web — gestion du compte depuis un navigateur

Site statique (Vite + TypeScript, sans framework) qui lit et modifie le compte Vyzo directement dans Supabase, avec
les mêmes règles que les applis : sécurité ligne par ligne (RLS), dernier écrit gagnant sur `updated_at`,
canal temps réel privé `user:<uid>`. L'adresse et la clé publique Supabase viennent de `../gradle.properties`.

## Sections

- **Profils** : créer, renommer, couleur, profil enfant, code à 4 chiffres (même empreinte que `ProfileManager.hashPin`),
  ordre, suppression (le déclencheur `vyzo_profile_deleted` efface aussi progression et listes côté serveur).
- **Appareils** : table `devices`, remplie par chaque appli à la synchro (`CloudSync.reportDevice`, au plus toutes les
  5 min). « Mettre à jour » écrit `update_requested_at` ; l'appareil le reçoit en temps réel (ou à sa prochaine synchro,
  pendant 24 h), lance `AppUpdater` et renvoie chaque étape dans `update_status`. Version de référence : `latest.json`
  du stockage `releases`.
- **Abonnements** : lignes `sealed_items` de type `playlist`, chiffrées en AES-256-GCM avec la clé du compte
  (`src/vault.ts`, même format que `VaultCrypto`/`SealedSync`). La clé est déballée avec le mot de passe du compte
  (PBKDF2) et reste en mémoire, sauf si « Rester déverrouillé sur cet ordinateur » est coché : elle est alors gardée
  dans IndexedDB, chiffrée par une clé AES non extractible propre au navigateur (`src/keystore.ts`), et oubliée par
  « Verrouiller » ou la déconnexion. L'identifiant d'un abonnement est `PlaylistKeys.of` : en changer revient à créer
  un autre abonnement (l'ancien et ses réglages de chaînes sont retirés).
- **Historique** : `watch_progress` du profil choisi, avec titres chiffrés dans la colonne `meta` (écrite par l'appli,
  voir `ContentTitles.kt`). Retrait d'un titre : suppression des lignes + message `history_remove` ; tout effacer :
  `history_clear`, comme `CloudSync.deleteCloudHistory`.
- **Listes** : favoris (`fav`) et listes `user_lists` du profil ; créer, renommer, supprimer une liste, retirer un élément.
- **Enregistrements** : table `recordings`, annulation ou arrêt (statut `CANCELLED`), retrait de la liste.

Concordance avec l'appli : `src/vault.test.ts` et `desktop/src/test/…/WebCompatTest.kt` vérifient les mêmes valeurs
(identifiants d'abonnement, dérivation de clé, blocs chiffrés dans les deux sens).

Serveur : la migration `supabase/migrations/20261113120000_appareils_et_titres.sql` (table `devices`, colonnes `meta`)
doit être appliquée **avant** de publier une appli qui s'en sert.

## Commandes

```bash
npm install
npm run dev        # http://localhost:5173, démo sans compte : http://localhost:5173/?demo (mot de passe « demo »)
npm test           # règles communes avec l'appli (Vitest)
npm run build      # site dans dist/, avec une politique de sécurité de contenu stricte
```

En ligne : **https://yannickuhrig1.github.io/vyzo/**, publié par `.github/workflows/site.yml` (tests, build, GitHub
Pages) à chaque changement de `web/` sur `main`. `dist/` peut aussi se publier tel quel sur tout hébergement statique
en HTTPS. Pour d'éventuels liens envoyés par e-mail (confirmation, mot de passe oublié), l'adresse du site doit figurer
dans Supabase › Authentication › URL Configuration.

## Règles

- Jamais d'`innerHTML` : tout passe par `h()` (`src/dom.ts`). Le site affiche des identifiants d'abonnement.
- Jamais d'attribut `style` : la CSP le refuse, passer par `el.style` (CSSOM).
- Aucune ressource externe (polices, scripts, statistiques) : la CSP du build l'interdit de toute façon.
- Le site refuse de s'afficher dans le cadre d'une autre page (GitHub Pages ne peut pas envoyer `frame-ancestors`).
