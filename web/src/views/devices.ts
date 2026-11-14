import { type PlaybackAction } from '../backend'
import { append, h, icon } from '../dom'
import {
  MAX_DEVICE_LABEL, MAX_MESSAGE, deviceName, isOnline, livePosition, needsUpdate, supportsRemote, updateInProgress, updatePending,
  type Device, type LatestVersion,
} from '../model'
import { act, barFill, emptyCard, loading, openDialog, plural, relative, toast } from '../ui'
import { backend, expectAck, isHidden, refresh, softDelete, state } from '../context'
import { clock, DEVICE_ICON, errorBanner, pageHeader } from '../shared'

export function devicesView() {
  const devices = state.devices?.filter((d) => !isHidden(`d:${d.id}`)) ?? null
  const latest = state.latest
  const outdated = (devices ?? []).filter((d) => needsUpdate(d, latest) && !updateInProgress(d))
  const all = outdated.length > 1 ? h('button', {
    class: 'btn primary', type: 'button',
    onclick: async (e: Event) => {
      const b = e.currentTarget as HTMLButtonElement
      if (await act(b, async () => { for (const d of outdated) await backend.requestUpdate(d) },
        `Mise à jour demandée à ${plural(outdated.length, 'appareil', 'appareils')}`)) await refresh(['devices'])
    },
  }, icon('refresh', 16), `Tout mettre à jour (${outdated.length})`) : null

  const box = h('section', {}, pageHeader('appareils', all), errorBanner('devices'))
  if (!devices) return append(box, [state.errors.devices ? null : loading()]), box

  if (latest) {
    append(box, [h('p', { class: 'latest' }, icon('check', 14), 'Dernière version publiée ', h('strong', {}, latest.versionName || String(latest.versionCode)))])
  }
  if (devices.length === 0) {
    append(box, [emptyCard('devices', 'Aucun appareil pour l’instant',
      'Chaque appareil apparaît ici après sa première synchronisation avec le compte.')])
    return box
  }
  const now = Date.now()
  append(box, [h('ul', { class: 'rows' }, devices.map((d) => deviceRow(d, latest, now)))])
  append(box, [h('p', { class: 'note' }, icon('info', 15), h('span', {},
    'Un appareil reçoit la demande de mise à jour quand Vyzo y est ouvert, ou à sa prochaine ouverture dans les 24 h. ' +
    'Sur Android, l’installation peut demander une confirmation à l’écran.'))])
  return box
}

function deviceRow(d: Device, latest: LatestVersion | null, now: number) {
  const online = isOnline(d, now)
  const outdated = needsUpdate(d, latest)
  const waiting = updateInProgress(d, now)
  const label = deviceName(d)

  const versionPill = !d.appVersion ? null
    : outdated ? h('span', { class: 'pill planned mono', title: `Version installée ${d.appVersion}` }, `${d.appVersion} → ${latest!.versionName}`)
      : latest && d.versionCode >= latest.versionCode ? h('span', { class: 'pill ok mono' }, d.appVersion)
        : h('span', { class: 'pill muted mono' }, d.appVersion)

  let status: HTMLElement | null = null
  if (updatePending(d)) {
    status = h('div', { class: 'device-status waiting' }, h('span', { class: 'spinner', 'aria-hidden': 'true' }),
      online ? 'Demande envoyée, en attente de l’appareil…' : 'Demande envoyée : elle sera faite à la prochaine ouverture de Vyzo (24 h).')
  } else if (waiting) {
    status = h('div', { class: 'device-status waiting' }, h('span', { class: 'spinner', 'aria-hidden': 'true' }), d.updateStatus)
  } else if (d.updateStatus && d.updateStatusAt > 0) {
    const failed = /^Échec|n’autorise|n'autorise/.test(d.updateStatus)
    status = h('div', { class: `device-status ${failed ? 'bad' : ''}` }, icon(failed ? 'info' : 'refresh', 13),
      h('span', {}, d.updateStatus), h('span', { class: 'faint' }, ` · ${relative(d.updateStatusAt)}`))
  }

  const update: HTMLButtonElement = h('button', {
    class: `btn ${outdated ? 'primary' : 'ghost'} small`, type: 'button', disabled: waiting,
    title: outdated ? '' : 'L’appareil vérifie s’il existe une version plus récente et l’installe',
    onclick: async () => {
      if (await act(update, () => backend.requestUpdate(d), `Mise à jour demandée à « ${label} »`)) await refresh(['devices'])
    },
  }, icon('refresh', 14), outdated ? 'Mettre à jour' : 'Vérifier')

  const remove = h('button', {
    class: 'icon-btn', type: 'button', title: 'Retirer de la liste', 'aria-label': `Retirer ${label} de la liste`,
    onclick: () => softDelete(`d:${d.id}`, `« ${label} » retiré de la liste`, () => backend.removeDevice(d), ['devices']),
  }, icon('trash', 15))
  const remote = supportsRemote(d)
  const later = 'Avec Vyzo 1.13.0-beta3 et plus sur cet appareil'
  const rename = h('button', {
    class: 'icon-btn', type: 'button', title: 'Renommer', 'aria-label': `Renommer ${label}`, onclick: () => renameDialog(d),
  }, icon('edit', 15))
  const message = h('button', {
    class: 'icon-btn', type: 'button', disabled: !remote || !online, 'aria-label': `Envoyer un message à ${label}`,
    title: !remote ? later : online ? 'Afficher un message à l’écran' : 'Vyzo doit être ouvert sur l’appareil',
    onclick: () => messageDialog(d),
  }, icon('message', 15))
  const check: HTMLButtonElement = h('button', {
    class: 'icon-btn', type: 'button', disabled: !remote || !online, 'aria-label': `Vérifier les abonnements depuis ${label}`,
    title: !remote ? later : online ? 'Vérifier maintenant l’état des abonnements' : 'Vyzo doit être ouvert sur l’appareil',
    onclick: () => void checkNow(d, check),
  }, icon('pulse', 15))

  const details = [d.model && d.model !== d.name ? d.model : null, d.system || null].filter(Boolean).join(' · ')
  return h('li', { class: 'row-card device' },
    h('span', { class: 'kind-badge big' }, icon(DEVICE_ICON[d.kind] ?? 'devices', 20),
      h('span', { class: `presence ${online ? 'on' : ''}`, title: online ? 'En ligne' : 'Hors ligne' })),
    h('div', { class: 'row-main' },
      h('div', { class: 'title-line' }, h('span', { class: 'row-title' }, label), versionPill),
      h('div', { class: 'row-sub wrap' },
        h('span', { class: online ? 'online' : '' }, online ? 'En ligne' : `Vu ${relative(d.lastSeenAt)}`),
        details ? h('span', { class: 'faint' }, `· ${details}`) : null),
      status,
      online ? nowPlaying(d) : null),
    h('div', { class: 'row-actions' }, update, rename, message, check, remove),
  )
}

/** « ▶ Dune · Yannick » : ce que l'appareil lit, pour les listes courtes. */
export function playingLine(d: Device): HTMLElement | null {
  const p = state.playback.get(d.id)
  if (!p || p.state === 'idle' || !p.title) return null
  return h('span', { class: 'playing-line' }, icon(p.state === 'playing' ? 'play' : 'pause', 11),
    h('span', {}, p.title), p.profile ? h('span', { class: 'faint' }, ` · ${p.profile}`) : null)
}

export function playbackControlButtons(d: Device, compact = false) {
  const p = state.playback.get(d.id)
  if (!p || p.state === 'idle' || !p.title) return []
  const account = state.account!
  const control = (action: PlaybackAction, iconName: string, text: string) => {
    const b: HTMLButtonElement = h('button', {
      class: compact ? 'icon-btn' : 'btn ghost small', type: 'button',
      title: text, 'aria-label': `${text} sur ${deviceName(d)}`,
      onclick: () => void act(b, () => backend.controlPlayback(account, d, action)),
    }, icon(iconName, compact ? 14 : 13), compact ? null : text)
    return b
  }
  return [
    p.state === 'playing' ? control('pause', 'pause', 'Pause') : control('resume', 'play', 'Reprendre'),
    control('stop', 'stop', 'Arrêter'),
  ]
}

function nowPlaying(d: Device): HTMLElement | null {
  const p = state.playback.get(d.id)
  if (!p || p.state === 'idle' || !p.title) return null
  const pos = livePosition(p)
  const timing = p.durationMs > 0
    ? h('span', { class: 'mono faint' }, `${clock(pos)} / ${clock(p.durationMs)}`)
    : h('span', { class: 'pill live' }, p.kind === 'live' ? 'Direct' : 'En cours')
  return h('div', { class: 'now-playing' },
    h('div', { class: 'np-head' },
      h('span', { class: `np-dot ${p.state}` }),
      h('div', { class: 'np-text' },
        h('div', { class: 'np-title' }, p.title),
        h('div', { class: 'np-sub' }, [p.detail, p.profile ? `Profil ${p.profile}` : '', p.state === 'paused' ? 'En pause' : ''].filter(Boolean).join(' · '))),
      timing),
    p.durationMs > 0 ? h('div', { class: 'bar np-bar' }, barFill(pos / p.durationMs)) : null,
    h('div', { class: 'np-actions' }, playbackControlButtons(d)))
}

function renameDialog(d: Device) {
  const input = h('input', { id: 'dev-label', maxlength: MAX_DEVICE_LABEL, value: d.label, placeholder: d.name || 'Nom de l’appareil', autocomplete: 'off' })
  const save: HTMLButtonElement = h('button', { class: 'btn primary', type: 'submit' }, 'Enregistrer')
  const form = h('form', {
    onsubmit: async (e: Event) => {
      e.preventDefault()
      if (await act(save, () => backend.renameDevice(d, input.value), input.value.trim() ? 'Appareil renommé' : 'Nom d’origine rétabli')) {
        dialog.close()
        await refresh(['devices'])
      }
    },
  },
    h('h2', {}, 'Renommer l’appareil'),
    h('label', { for: 'dev-label' }, 'Nom affiché sur le site'), input,
    h('p', { class: 'fine' }, `Nom donné par le système : ${d.name || 'inconnu'}. Laisse vide pour le reprendre.`),
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler'), save))
  const dialog = openDialog(form, 'small')
  input.focus()
  input.select()
}

export const SIGNATURE_KEY = 'vyzo-message-from'
const QUICK_MESSAGES = ['À table !', 'Extinction dans 10 min', 'Bonne nuit !']

function messageDialogBase(
  title: string,
  subtitle: string,
  note: string,
  sendLabel: string,
  onSend: (text: string, from: string) => Promise<void>,
) {
  let saved = ''
  try { saved = localStorage.getItem(SIGNATURE_KEY) ?? '' } catch { /* stockage indisponible */ }
  const text = h('textarea', { id: 'msg-text', maxlength: MAX_MESSAGE, rows: 4, required: true, placeholder: 'À table dans 10 minutes !' })
  const from = h('input', { id: 'msg-from', maxlength: 60, value: saved, placeholder: 'Papa', autocomplete: 'off' })
  const count = h('span', { class: 'fine count' }, `0 / ${MAX_MESSAGE}`)
  const updateCount = () => { count.textContent = `${text.value.length} / ${MAX_MESSAGE}` }
  text.addEventListener('input', updateCount)
  const presets = h('div', { class: 'chips' },
    QUICK_MESSAGES.map((m) => h('button', {
      type: 'button', class: 'chip',
      onclick: () => { text.value = m; updateCount(); text.focus() },
    }, m)))
  const send: HTMLButtonElement = h('button', { class: 'btn primary', type: 'submit' }, icon('message', 14), sendLabel)
  const form = h('form', {
    onsubmit: async (e: Event) => {
      e.preventDefault()
      if (!text.value.trim()) return
      try { localStorage.setItem(SIGNATURE_KEY, from.value.trim()) } catch { /* stockage indisponible */ }
      if (await act(send, () => onSend(text.value, from.value))) dialog.close()
    },
  },
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('message', 20)),
      h('div', {}, h('h2', {}, title), h('p', { class: 'dialog-sub' }, subtitle))),
    h('label', { for: 'msg-text' }, 'Message'), presets, text, count,
    h('label', { for: 'msg-from' }, 'Signé (facultatif)'), from,
    h('p', { class: 'fine' }, note),
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler'), send))
  const dialog = openDialog(form, 'small')
  text.focus()
}

export function messageDialog(d: Device) {
  const account = state.account!
  const name = deviceName(d)
  messageDialogBase(
    'Message à l’écran',
    name,
    'Il s’affiche par-dessus ce qui est à l’écran, jusqu’à ce qu’on appuie sur OK. Chiffré de bout en bout.',
    'Envoyer',
    async (text, from) => {
      const req = await backend.sendMessage(account, d, text, from)
      expectAck(req, d, () => toast(`Message affiché sur « ${name} »`), `« ${name} » n’a pas répondu : Vyzo doit être ouvert à l’écran`)
    },
  )
}

export function announceDialog(devices: Device[]) {
  const account = state.account!
  const targets = devices.filter((d) => isOnline(d) && supportsRemote(d))
  if (targets.length === 0) return
  messageDialogBase(
    'Annonce maison',
    plural(targets.length, 'appareil connecté recevra le message', 'appareils connectés recevront le message'),
    'Affiché en même temps sur tous les écrans actuellement allumés, chiffré de bout en bout.',
    `Envoyer (${targets.length})`,
    async (text, from) => {
      const sent = await Promise.allSettled(targets.map((d) => backend.sendMessage(account, d, text, from)))
      const ok = sent.filter((r) => r.status === 'fulfilled').length
      if (ok === 0) throw new Error('Aucun appareil n’a pu recevoir l’annonce')
      toast(`Annonce envoyée à ${plural(ok, 'appareil', 'appareils')}`)
    },
  )
}

export async function checkNow(d: Device, button: HTMLButtonElement) {
  const name = deviceName(d)
  let req = ''
  if (!await act(button, async () => { req = await backend.checkNow(state.account!, d) })) return
  button.disabled = true
  expectAck(req, d, (action) => {
    if (action === 'check') toast(`« ${name} » interroge les fournisseurs…`)
    else {
      button.disabled = false
      toast('Vérification terminée : l’état des abonnements arrive dans quelques secondes')
      // L'appareil renvoie l'état chiffré après un court délai (envoi groupé) : la page se met à jour d'elle-même.
      window.setTimeout(() => void refresh(['playlists']), 12_000)
    }
  }, `« ${name} » n’a pas répondu : Vyzo doit être ouvert à l’écran`)
  window.setTimeout(() => { button.disabled = false }, 8000)
}
