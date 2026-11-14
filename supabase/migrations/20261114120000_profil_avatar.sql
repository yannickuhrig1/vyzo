-- Avatar choisi pour un profil (clé courte partagée entre le site web et les applis).
-- Rétrocompatible : chaîne vide = monogramme sur fond coloré.

alter table public.profiles add column avatar text not null default '' check (length(avatar) <= 40);
