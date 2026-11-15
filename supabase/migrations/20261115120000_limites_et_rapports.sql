-- Limites contre le remplissage de la base (audit de sécurité du 15/11/2026).
--
-- crash_reports : l'envoi reste ouvert sans compte (une appli qui plante n'est pas toujours connectée), mais la clé
-- publique est dans chaque APK et dans le site : sans limite, n'importe qui pouvait remplir la base (~80 Ko par ligne)
-- jusqu'à la faire passer en lecture seule, ce qui arrête la synchro de tous les comptes. Au-delà des plafonds, la ligne
-- est ignorée sans erreur : l'appli reçoit 201 et efface son rapport au lieu de le renvoyer en boucle.
--
-- Tables synchronisées : bornes de taille larges (plusieurs fois les plus grandes valeurs envoyées par les applis), pour
-- qu'un compte ne puisse pas stocker des gigaoctets. account_events : 2000 lignes au plus par compte.

-- ------------------------------------------------------------------ Rapports de plantage

create index if not exists crash_reports_created_at on public.crash_reports (created_at);

create function public.vyzo_crash_guard()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  new.created_at := now();
  -- Rapports de plus de 60 jours : retirés au passage.
  delete from public.crash_reports where created_at < now() - interval '60 days';
  if (select count(*) from public.crash_reports) >= 1000
     or (select count(*) from public.crash_reports where created_at > now() - interval '1 hour') >= 60
     or (new.user_id is not null and (select count(*) from public.crash_reports
           where user_id = new.user_id and created_at > now() - interval '1 hour') >= 10) then
    return null;
  end if;
  return new;
end;
$$;

revoke all on function public.vyzo_crash_guard() from public, anon, authenticated;

create trigger vyzo_crash_guard before insert on public.crash_reports
for each row execute function public.vyzo_crash_guard();

-- ------------------------------------------------------------------ Tailles des données synchronisées

alter table public.sealed_items
  add constraint sealed_items_kind_len check (length(kind) <= 40),
  add constraint sealed_items_id_len check (length(id) <= 512),
  add constraint sealed_items_payload_len check (length(payload) <= 2000000);

alter table public.profiles
  add constraint profiles_name_len check (length(name) <= 200),
  add constraint profiles_pin_hash_len check (length(pin_hash) <= 200);

alter table public.user_lists
  add constraint user_lists_id_len check (length(id) <= 512),
  add constraint user_lists_name_len check (length(name) <= 500);

alter table public.list_items
  add constraint list_items_list_id_len check (length(list_id) <= 512),
  add constraint list_items_content_key_len check (length(content_key) <= 512),
  add constraint list_items_kind_len check (length(kind) <= 40),
  add constraint list_items_meta_len check (length(meta) <= 8000);

alter table public.watch_progress
  add constraint watch_progress_content_key_len check (length(content_key) <= 512),
  add constraint watch_progress_kind_len check (length(kind) <= 40),
  add constraint watch_progress_meta_len check (length(meta) <= 8000),
  add constraint watch_progress_parent_key_len check (length(parent_key) <= 512);

alter table public.recordings
  add constraint recordings_id_len check (length(id) <= 1024),
  add constraint recordings_channel_key_len check (length(channel_key) <= 512),
  add constraint recordings_channel_name_len check (length(channel_name) <= 500),
  add constraint recordings_title_len check (length(title) <= 1000),
  add constraint recordings_status_len check (length(status) <= 40),
  add constraint recordings_error_len check (length(error) <= 2000),
  add constraint recordings_sender_len check (length(sender) <= 128);

-- ------------------------------------------------------------------ Journal du compte

-- Comme avant (six mois), plus un plafond de 2000 lignes par compte : le site peut y écrire lui-même.
create or replace function public.vyzo_events_trim()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  new.at := clock_timestamp();
  delete from public.account_events where user_id = new.user_id and at < clock_timestamp() - interval '180 days';
  delete from public.account_events where id in (
    select id from public.account_events where user_id = new.user_id order by at desc, id desc offset 1999
  );
  return new;
end;
$$;

revoke all on function public.vyzo_events_trim() from public, anon, authenticated;
