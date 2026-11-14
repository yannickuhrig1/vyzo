-- Nom choisi pour un appareil, et journal du compte (site de gestion du compte, Vyzo 1.13.0-beta3).
--
-- devices.label : nom donné depuis le site. L'appli écrit « name » (nom du système) à chaque signalement sans toucher
-- à cette colonne : le nom choisi reste.
--
-- account_events : ce qui s'est passé sur le compte (appareil ajouté, abonnement ajouté ou retiré, profil créé…).
-- Écrit par le serveur lui-même (déclencheurs ci-dessous), et par le site pour quelques actions qu'il est seul à voir
-- (connexion, sauvegarde, mot de passe changé). Le compte lit ses lignes et ne peut ni les modifier ni les effacer.
-- Rien de chiffré n'y figure : pour un abonnement, seulement « ajouté » ou « retiré ».

alter table public.devices add column label text not null default '' check (length(label) <= 60);

create table public.account_events (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  at timestamptz not null default clock_timestamp(),
  kind text not null check (length(kind) <= 40),
  detail text not null default '' check (length(detail) <= 200)
);

create index account_events_user_at on public.account_events (user_id, at desc);

alter table public.account_events enable row level security;

create policy "Lecture du journal" on public.account_events
for select
to authenticated
using ((select auth.uid()) = user_id);

create policy "Ajout depuis le site" on public.account_events
for insert
to authenticated
with check (
  (select auth.uid()) = user_id
  and kind in ('site_signin', 'password_changed', 'signout_others', 'backup_export', 'backup_restore', 'message_sent')
);

-- Journal limité à six mois : les plus anciennes lignes du compte partent à chaque ajout.
create function public.vyzo_events_trim()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  new.at := clock_timestamp();
  delete from public.account_events where user_id = new.user_id and at < clock_timestamp() - interval '180 days';
  return new;
end;
$$;

create trigger vyzo_events_trim before insert on public.account_events
for each row execute function public.vyzo_events_trim();

create function public.vyzo_log(p_user uuid, p_kind text, p_detail text)
returns void
language sql
security definer
set search_path = ''
as $$
  -- Rien quand le compte est en cours de suppression (appareils effacés en cascade).
  insert into public.account_events (user_id, kind, detail)
  select p_user, p_kind, left(coalesce(p_detail, ''), 200)
  where exists (select 1 from auth.users where id = p_user);
$$;

revoke all on function public.vyzo_log(uuid, text, text) from public, anon, authenticated;

create function public.vyzo_log_devices()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' then
    perform public.vyzo_log(new.user_id, 'device_added', new.name);
  elsif tg_op = 'DELETE' then
    perform public.vyzo_log(old.user_id, 'device_removed', coalesce(nullif(old.label, ''), old.name));
  else
    if new.update_requested_at > old.update_requested_at then
      perform public.vyzo_log(new.user_id, 'update_requested', coalesce(nullif(new.label, ''), new.name));
    end if;
    if new.label is distinct from old.label then
      perform public.vyzo_log(new.user_id, 'device_renamed', coalesce(nullif(new.label, ''), new.name));
    end if;
    if new.app_version is distinct from old.app_version and old.app_version <> '' then
      perform public.vyzo_log(new.user_id, 'device_updated', coalesce(nullif(new.label, ''), new.name) || ' · ' || new.app_version);
    end if;
  end if;
  return null;
end;
$$;

create trigger vyzo_log_devices after insert or update or delete on public.devices
for each row execute function public.vyzo_log_devices();

create function public.vyzo_log_profiles()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' then
    if not new.deleted then
      perform public.vyzo_log(new.user_id, 'profile_added', new.name);
    end if;
  elsif new.deleted and not old.deleted then
    perform public.vyzo_log(new.user_id, 'profile_removed', old.name);
  elsif not new.deleted then
    if old.deleted then
      perform public.vyzo_log(new.user_id, 'profile_added', new.name);
    elsif new.name is distinct from old.name then
      perform public.vyzo_log(new.user_id, 'profile_renamed', old.name || ' → ' || new.name);
    end if;
    if not old.deleted and new.pin_hash is distinct from old.pin_hash then
      perform public.vyzo_log(new.user_id, case when new.pin_hash = '' then 'profile_pin_removed' else 'profile_pin_set' end, new.name);
    end if;
    if not old.deleted and new.is_kid is distinct from old.is_kid then
      perform public.vyzo_log(new.user_id, case when new.is_kid then 'profile_kid_on' else 'profile_kid_off' end, new.name);
    end if;
  end if;
  return null;
end;
$$;

create trigger vyzo_log_profiles after insert or update on public.profiles
for each row execute function public.vyzo_log_profiles();

create function public.vyzo_log_sealed()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  was_live boolean := tg_op = 'UPDATE' and not old.deleted;
begin
  if new.kind = 'playlist' then
    if not new.deleted and not was_live then
      perform public.vyzo_log(new.user_id, 'playlist_added', '');
    elsif new.deleted and was_live then
      perform public.vyzo_log(new.user_id, 'playlist_removed', '');
    end if;
  elsif new.kind = 'vault' and tg_op = 'UPDATE' then
    if (new.payload::jsonb ->> 'keyId') is distinct from (old.payload::jsonb ->> 'keyId') then
      perform public.vyzo_log(new.user_id, 'vault_reset', '');
    end if;
  elsif new.kind in ('parental', 'screentime') then
    if tg_op = 'INSERT' or new.payload is distinct from old.payload or new.deleted is distinct from old.deleted then
      perform public.vyzo_log(new.user_id, new.kind || '_changed', '');
    end if;
  end if;
  return null;
exception when others then
  -- Le journal ne doit jamais faire échouer un envoi (bloc de clé illisible, par exemple).
  return null;
end;
$$;

create trigger vyzo_log_sealed after insert or update on public.sealed_items
for each row execute function public.vyzo_log_sealed();

create function public.vyzo_log_recordings()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' and not new.deleted and new.status = 'SCHEDULED' then
    perform public.vyzo_log(new.user_id, 'recording_added', new.title || case when new.channel_name <> '' then ' · ' || new.channel_name else '' end);
  end if;
  return null;
end;
$$;

create trigger vyzo_log_recordings after insert on public.recordings
for each row execute function public.vyzo_log_recordings();

revoke all on function public.vyzo_events_trim() from public, anon, authenticated;
revoke all on function public.vyzo_log_devices() from public, anon, authenticated;
revoke all on function public.vyzo_log_profiles() from public, anon, authenticated;
revoke all on function public.vyzo_log_sealed() from public, anon, authenticated;
revoke all on function public.vyzo_log_recordings() from public, anon, authenticated;
