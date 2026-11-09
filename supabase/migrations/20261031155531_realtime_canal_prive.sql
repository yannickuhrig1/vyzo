-- Canal temps réel privé par compte.
--
-- Avant la 1.7.1, tous les appareils diffusaient sur le canal public « vyzo-sync » : n'importe quel détenteur de la
-- clé publique pouvait lire les commandes Cast, les programmations et les purges d'historique de tous les comptes,
-- ou en envoyer. Désormais chaque appareil rejoint le canal privé « user:<uid> » de son compte, et ces règles
-- n'autorisent la lecture et la diffusion qu'avec un jeton de ce compte.

create policy "vyzo: lire le canal de son compte"
on realtime.messages
for select
to authenticated
using (
  realtime.topic() = 'user:' || (select auth.uid())::text
  and realtime.messages.extension = 'broadcast'
);
-- Les notifications de modification des tables (postgres_changes) restent filtrées par les règles de chaque table.

create policy "vyzo: diffuser sur le canal de son compte"
on realtime.messages
for insert
to authenticated
with check (
  realtime.topic() = 'user:' || (select auth.uid())::text
  and realtime.messages.extension = 'broadcast'
);
