-- Programmations d'enregistrement du compte.
--
-- Avant la 1.8.1, une programmation faite sur le téléphone n'était qu'un message temps réel : si Vyzo PC (qui
-- enregistre) était éteint ou réduit dans la zone de notification à ce moment-là, elle était perdue. Chaque
-- programmation est désormais une ligne de cette table, relue par l'appareil qui enregistre à sa prochaine synchro.
--
-- id = « clé de chaîne | début | fin » ; la clé de chaîne ne dépend pas de l'appareil (voir PlaylistKeys).

create table public.recordings (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  id text not null,
  channel_key text not null,
  channel_name text not null default '',
  title text not null default '',
  start_ms bigint not null,
  stop_ms bigint not null,
  status text not null default 'SCHEDULED',
  error text not null default '',
  sender text not null default '',
  updated_at bigint not null,
  deleted boolean not null default false,
  synced_at timestamptz not null default clock_timestamp(),
  primary key (user_id, id)
);

create index recordings_user_synced on public.recordings (user_id, synced_at);

alter table public.recordings enable row level security;

create policy "Lignes du compte" on public.recordings
for all
to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

-- Dernier écrit gagnant sur updated_at, synced_at posé par le serveur (comme les autres tables synchronisées).
create trigger vyzo_sync_guard before insert or update on public.recordings
for each row execute function public.vyzo_sync_guard();

alter publication supabase_realtime add table public.recordings;
