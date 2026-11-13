-- Appareils du compte, et titres chiffrés de la progression et des listes (site de gestion du compte, Vyzo 1.13).
--
-- devices : chaque appli se signale à sa synchro (nom, type, version, dernière activité). Le site y pose une demande de
-- mise à jour (update_requested_at) ; l'appareil la voit en temps réel ou à sa synchro suivante, lance sa mise à jour et
-- écrit où il en est (update_status). Chaque côté n'écrit que ses colonnes : pas de « dernier écrit gagnant » ici.
--
-- meta : titre du contenu chiffré sur l'appareil avec la clé du compte (comme sealed_items), lisible seulement par les
-- appareils et le site une fois le mot de passe saisi. Vide pour les lignes envoyées par une version plus ancienne.

create table public.devices (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  id text not null check (length(id) <= 64),
  name text not null default '' check (length(name) <= 120),
  kind text not null default '' check (kind in ('', 'tv', 'mobile', 'pc')),
  model text not null default '' check (length(model) <= 120),
  system text not null default '' check (length(system) <= 120),
  app_version text not null default '' check (length(app_version) <= 40),
  version_code integer not null default 0,
  last_seen_at bigint not null default 0,
  update_requested_at bigint not null default 0,
  update_status text not null default '' check (length(update_status) <= 300),
  update_status_at bigint not null default 0,
  synced_at timestamptz not null default clock_timestamp(),
  primary key (user_id, id)
);

alter table public.devices enable row level security;

create policy "Lignes du compte" on public.devices
for all
to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

create function public.vyzo_touch_synced()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE' then
    new.user_id := old.user_id;
  end if;
  new.synced_at := clock_timestamp();
  return new;
end;
$$;

create trigger vyzo_touch_synced before insert or update on public.devices
for each row execute function public.vyzo_touch_synced();

alter publication supabase_realtime add table public.devices;

alter table public.watch_progress add column meta text not null default '';
alter table public.list_items add column meta text not null default '';
