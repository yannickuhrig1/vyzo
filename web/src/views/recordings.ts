import { append, h, icon } from '../dom'
import { recordingGroup, sortRecordings, statusLabel, upcomingRecordingAlert, type Recording, type RecordingGroup } from '../model'
import { act, confirmDialog, dayLabel, emptyCard, fullFmt, loading, plural, timeFmt } from '../ui'
import { backend, isHidden, refresh, softDelete, state } from '../context'
import { errorBanner, pageHeader, sectionTitle } from '../shared'
import { unlockDialog } from '../vault-ui'
import { guideDialog } from '../cast'

const GROUPS: { id: RecordingGroup; title: string; icon: string }[] = [
  { id: 'upcoming', title: 'À venir et en cours', icon: 'calendar' },
  { id: 'done', title: 'Terminés', icon: 'check' },
  { id: 'other', title: 'Annulés, en échec ou manqués', icon: 'x' },
]

export function recordingsView() {
  const recordings = state.recordings?.filter((r) => !isHidden(`r:${r.id}`)) ?? null
  const now = Date.now()
  const cleanable = (recordings ?? []).filter((r) => recordingGroup(r, now) !== 'upcoming')
  const cleanup: HTMLButtonElement | null = cleanable.length > 0 ? h('button', {
    class: 'btn ghost', type: 'button',
    onclick: async () => {
      const ok = await confirmDialog({
        title: 'Nettoyer l’historique des enregistrements ?',
        body: `${plural(cleanable.length, 'enregistrement terminé, annulé ou manqué sera retiré', 'enregistrements terminés, annulés ou manqués seront retirés')} de cette liste (les programmations à venir restent).`,
        confirm: 'Nettoyer', danger: true,
      })
      if (ok && cleanup && await act(cleanup, () => backend.removeRecordings(cleanable),
        `${plural(cleanable.length, 'enregistrement retiré', 'enregistrements retirés')}`)) {
        await refresh(['recordings'])
      }
    },
  }, icon('trash', 15), `Nettoyer (${cleanable.length})`) : null
  const schedule = state.vault === 'open'
    ? h('button', { class: 'btn primary', type: 'button', onclick: () => guideDialog() }, icon('plus', 16), 'Programmer')
    : null
  const box = h('section', {}, pageHeader('enregistrements', cleanup, schedule), errorBanner('recordings'))

  if (state.vault === 'locked') {
    append(box, [h('div', { class: 'banner info' }, icon('lock'),
      h('span', {}, 'Déverrouille les données chiffrées pour programmer un enregistrement depuis le guide TV.'),
      h('button', { class: 'btn ghost small', type: 'button', onclick: () => unlockDialog() }, icon('unlock', 15), 'Déverrouiller'))])
  }

  if (!recordings) return append(box, [state.errors.recordings ? null : loading()]), box

  const alert = state.devices ? upcomingRecordingAlert(recordings, state.devices, now) : null
  if (alert) {
    append(box, [h('div', { class: 'banner info' }, icon('info', 16),
      h('span', {}, `Aucun Vyzo PC n’est actuellement en ligne pour « ${alert.title || alert.channelName} » (${dayLabel(alert.startMs).toLowerCase()} à ${timeFmt.format(alert.startMs)}).`))])
  }

  if (recordings.length === 0) {
    append(box, [emptyCard('record', 'Aucun enregistrement',
      'Cherche un programme avec « Programmer », ou choisis-le dans le guide TV de Vyzo puis « Enregistrer ». Il apparaîtra ici.',
      schedule ? h('button', { class: 'btn primary', type: 'button', onclick: () => guideDialog() }, icon('search', 15), 'Chercher dans le guide') : null)])
    return box
  }
  let first = true
  for (const g of GROUPS) {
    const items = sortRecordings(recordings.filter((r) => recordingGroup(r, now) === g.id), g.id)
    if (items.length === 0) continue
    const title = sectionTitle(g.title, g.icon, items.length)
    if (first) title.classList.add('first')
    first = false
    append(box, [title, h('ul', { class: 'rows' }, items.map((r) => recordingRow(r, now)))])
  }
  return box
}

function recordingRow(r: Recording, now: number) {
  const status = statusLabel(r, now)
  const statusClass = r.status === 'RECORDING' ? 'live' : r.status === 'DONE' ? 'ok' : r.status === 'FAILED' ? 'bad'
    : r.status === 'SCHEDULED' && r.stopMs > now ? 'planned' : 'muted'
  const name = r.title || 'Programme sans titre'
  let action: HTMLButtonElement
  if (r.status === 'SCHEDULED' && r.stopMs > now) {
    action = h('button', {
      class: 'btn ghost small', type: 'button',
      onclick: async () => {
        const ok = await confirmDialog({
          title: 'Annuler cet enregistrement ?', body: `« ${name} » sur ${r.channelName}, ${dayLabel(r.startMs).toLowerCase()} à ${timeFmt.format(r.startMs)}.`,
          confirm: 'Annuler l’enregistrement', cancel: 'Garder', danger: true,
        })
        if (ok && await act(action, () => backend.setRecordingStatus(r, 'CANCELLED'), 'Enregistrement annulé')) await refresh(['recordings'])
      },
    }, icon('x', 14), 'Annuler')
  } else if (r.status === 'RECORDING') {
    action = h('button', {
      class: 'btn ghost small', type: 'button',
      onclick: async () => {
        const ok = await confirmDialog({
          title: 'Arrêter l’enregistrement ?', body: `Vyzo PC arrête d’enregistrer « ${name} ». Ce qui est déjà enregistré est gardé.`,
          confirm: 'Arrêter', cancel: 'Continuer', danger: true,
        })
        if (ok && await act(action, () => backend.setRecordingStatus(r, 'CANCELLED'), 'Arrêt demandé à Vyzo PC')) await refresh(['recordings'])
      },
    }, icon('stop', 14), 'Arrêter')
  } else {
    action = h('button', {
      class: 'icon-btn', type: 'button', title: 'Retirer de la liste', 'aria-label': `Retirer ${name} de la liste`,
      onclick: () => softDelete(`r:${r.id}`, `« ${name} » retiré de la liste`, () => backend.removeRecording(r), ['recordings']),
    }, icon('trash', 15))
  }

  return h('li', { class: 'row-card rec' },
    h('div', { class: 'when', title: fullFmt.format(r.startMs) },
      h('span', { class: 'when-day' }, dayLabel(r.startMs)),
      h('span', { class: 'when-time' }, `${timeFmt.format(r.startMs)} – ${timeFmt.format(r.stopMs)}`)),
    h('div', { class: 'row-main' },
      h('div', { class: 'row-title' }, name),
      h('div', { class: 'row-sub' }, icon('tv', 13), r.channelName || 'Chaîne inconnue',
        r.error ? h('span', { class: 'rec-error' }, ` · ${r.error}`) : null)),
    h('span', { class: `pill ${statusClass}` }, status),
    action,
  )
}
