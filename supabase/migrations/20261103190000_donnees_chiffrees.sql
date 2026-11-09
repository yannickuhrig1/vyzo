-- Playlists, sources de guide, réglages et personnalisations de chaînes du compte, chiffrés sur l'appareil.
--
-- Ces données contiennent les identifiants des abonnements IPTV : le serveur n'en garde que des blocs illisibles
-- (colonne payload). La ligne kind = 'vault', id = 'key' contient la clé commune aux appareils du compte, elle-même
-- chiffrée par une clé tirée du mot de passe du compte, qui ne quitte jamais l'appareil.

create table public.sealed_items (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  kind text not null,
  id text not null,
  payload text not null default '',
  updated_at bigint not null,
  deleted boolean not null default false,
  synced_at timestamptz not null default clock_timestamp(),
  primary key (user_id, kind, id)
);

create index sealed_items_user_synced on public.sealed_items (user_id, synced_at);

alter table public.sealed_items enable row level security;

create policy "Lignes du compte" on public.sealed_items
for all
to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

-- Dernier écrit gagnant sur updated_at, synced_at posé par le serveur (comme les autres tables synchronisées).
create trigger vyzo_sync_guard before insert or update on public.sealed_items
for each row execute function public.vyzo_sync_guard();

alter publication supabase_realtime add table public.sealed_items;
