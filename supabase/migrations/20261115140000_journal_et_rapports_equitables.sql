-- Correctif de limites_et_rapports (relecture de sécurité du 15/11/2026).
--
-- Journal : le plafond de 2000 lignes par compte effaçait les plus anciennes, quel que soit leur auteur. Une session
-- volée pouvait donc pousser dehors, avec 2000 « site_signin », les lignes écrites par le serveur (appareil ajouté,
-- abonnement retiré, clé remplacée…) et effacer ses traces. Désormais, seules les lignes que le site peut écrire
-- lui-même sont limitées, et par refus : 50 par jour et par compte au plus. Rien n'est plus effacé avant six mois.
--
-- Rapports de plantage : les plafonds globaux laissaient un envoyeur anonyme bloquer les rapports de tout le monde, et
-- les rapports refusés étaient perdus (réponse 201). Les anonymes ont maintenant leur propre budget, les comptes
-- connectés le leur (par compte), et un refus renvoie une erreur : l'appli garde son rapport et réessaie au lancement
-- suivant.

-- ------------------------------------------------------------------ Journal

create or replace function public.vyzo_events_trim()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  new.at := clock_timestamp();
  delete from public.account_events where user_id = new.user_id and at < clock_timestamp() - interval '180 days';
  -- Types que le site peut écrire (règle « Ajout depuis le site ») : jamais écrits par le serveur.
  if new.kind in ('site_signin', 'password_changed', 'signout_others', 'backup_export', 'backup_restore', 'message_sent')
     and (select count(*) from public.account_events
          where user_id = new.user_id
            and kind in ('site_signin', 'password_changed', 'signout_others', 'backup_export', 'backup_restore', 'message_sent')
            and at > clock_timestamp() - interval '1 day') >= 50 then
    return null;
  end if;
  return new;
end;
$$;

revoke all on function public.vyzo_events_trim() from public, anon, authenticated;

-- ------------------------------------------------------------------ Rapports de plantage

create index if not exists crash_reports_user_created on public.crash_reports (user_id, created_at);

create or replace function public.vyzo_crash_guard()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  new.created_at := now();
  delete from public.crash_reports where created_at < now() - interval '60 days';
  -- Anonymes : gardés 14 jours, 30 par heure et 300 en tout pour l'ensemble des envois sans compte.
  delete from public.crash_reports where user_id is null and created_at < now() - interval '14 days';
  if new.user_id is null then
    if (select count(*) from public.crash_reports where user_id is null and created_at > now() - interval '1 hour') >= 30
       or (select count(*) from public.crash_reports where user_id is null) >= 300 then
      raise exception 'Trop de rapports de plantage, réessaie plus tard' using errcode = 'P0001';
    end if;
  -- Comptes : 10 par heure et 50 en tout par compte, 2000 en tout pour l'ensemble des comptes.
  elsif (select count(*) from public.crash_reports where user_id = new.user_id and created_at > now() - interval '1 hour') >= 10
     or (select count(*) from public.crash_reports where user_id = new.user_id) >= 50
     or (select count(*) from public.crash_reports where user_id is not null) >= 2000 then
    raise exception 'Trop de rapports de plantage, réessaie plus tard' using errcode = 'P0001';
  end if;
  return new;
end;
$$;

revoke all on function public.vyzo_crash_guard() from public, anon, authenticated;
