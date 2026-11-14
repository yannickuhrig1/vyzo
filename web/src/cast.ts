import { friendlyError, UserError, type CastItem } from './backend'
import { append, clear, h, icon } from './dom'
import { castReadiness, deviceName, isOnline, recordingId, type GuideResult } from './model'
import { act, dayLabel, openDialog, relative, timeFmt } from './ui'
import { backend, isHidden, refresh, state } from './context'
import { DEVICE_ICON } from './shared'
import { unlockDialog } from './vault-ui'

export function castButton(item: CastItem, label: string) {
  return h('button', {
    class: 'icon-btn', type: 'button', title: 'Lancer sur un appareil', 'aria-label': `Lancer ${label} sur un appareil`,
    onclick: () => castDialog(item, label),
  }, icon('cast', 15))
}

export function castDialog(item: CastItem, label: string) {
  const now = Date.now()
  const devices = (state.devices ?? [])
    .filter((d) => !isHidden(`d:${d.id}`))
    .sort((a, b) => Number(castReadiness(b, now) === 'ready') - Number(castReadiness(a, now) === 'ready') || b.lastSeenAt - a.lastSeenAt)
  const account = state.account!
  const list = h('ul', { class: 'rows' }, devices.map((d) => {
    const ready = castReadiness(d, now)
    const name = deviceName(d)
    const pb = state.playback.get(d.id)
    const busyPlaying = pb && pb.state !== 'idle' && pb.title ? `Lit : ${pb.title}` : null
    const send: HTMLButtonElement = h('button', {
      class: `btn ${ready === 'ready' ? 'primary' : 'ghost'} small`, type: 'button', disabled: ready !== 'ready',
      onclick: async () => {
        if (await act(send, () => backend.castTo(account, d, { ...item, title: item.title || label }), `Envoyé à « ${name} »`)) dialog.close()
      },
    }, icon('play', 13), 'Lancer')
    return h('li', { class: 'row-card compact' },
      h('span', { class: 'kind-badge' }, icon(DEVICE_ICON[d.kind] ?? 'devices', 16), h('span', { class: `presence ${isOnline(d, now) ? 'on' : ''}` })),
      h('div', { class: 'row-main' },
        h('div', { class: 'row-title' }, name),
        h('div', { class: 'row-sub' }, ready === 'ready'
          ? busyPlaying
            ? h('span', { class: 'playing-line' }, icon('play', 11), busyPlaying)
            : h('span', { class: 'online' }, 'Vyzo ouvert')
          : ready === 'offline' ? `Vyzo fermé · vu ${relative(d.lastSeenAt)}` : `Version ${d.appVersion || 'ancienne'} : mise à jour nécessaire`)),
      send)
  }))
  const dialog = openDialog(h('div', {},
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('cast', 20)),
      h('div', {}, h('h2', {}, 'Lancer sur…'), h('p', { class: 'dialog-sub' }, label))),
    devices.length ? list : h('p', { class: 'empty' }, 'Aucun appareil sur ce compte.'),
    h('p', { class: 'fine' }, 'Vyzo doit être ouvert sur l’appareil, avec l’abonnement de ce titre. Le code parental est demandé si le titre est verrouillé.'),
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Fermer'))))
}

/** Recherche dans le guide des appareils connectés, puis programmation (table recordings, comme depuis l'appli). */
export function guideDialog() {
  if (state.vault !== 'open') return unlockDialog()
  const account = state.account!
  const input = h('input', { id: 'guide-q', type: 'search', placeholder: 'Titre ou chaîne : journal, Ligue 1, Arte…', autocomplete: 'off', minlength: 2, maxlength: 80 })
  const go_: HTMLButtonElement = h('button', { class: 'btn primary', type: 'submit' }, icon('search', 15), 'Chercher')
  const results = h('div', { class: 'guide-results' })
  const hasPc = (state.devices ?? []).some((d) => d.kind === 'pc')
  const programmed = () => new Set((state.recordings ?? []).filter((r) => r.status === 'SCHEDULED' || r.status === 'RECORDING').map((r) => r.id))

  const show = (items: GuideResult[]) => {
    clear(results)
    if (items.length === 0) {
      append(results, [h('p', { class: 'empty' }, 'Aucun programme à venir ne correspond.')])
      return
    }
    const already = programmed()
    let day = ''
    const list = h('ul', { class: 'rows' })
    for (const g of items) {
      const label = dayLabel(g.start)
      if (label !== day) {
        day = label
        list.appendChild(h('li', { class: 'guide-day' }, label))
      }
      const id = recordingId(g)
      const live = g.start <= Date.now()
      const add: HTMLButtonElement = h('button', {
        class: `btn ${already.has(id) ? 'ghost' : 'primary'} small`, type: 'button', disabled: already.has(id),
        onclick: async () => {
          if (await act(add, () => backend.scheduleRecording(g), `Programmé : ${g.title}`)) {
            add.disabled = true
            add.className = 'btn ghost small'
            clear(add)
            append(add, [icon('check', 13), 'Programmé'])
            void refresh(['recordings'])
          }
        },
      }, already.has(id) ? icon('check', 13) : icon('record', 13), already.has(id) ? 'Programmé' : 'Enregistrer')
      list.appendChild(h('li', { class: 'row-card compact' },
        h('div', { class: 'guide-time mono' }, timeFmt.format(g.start), h('small', {}, timeFmt.format(g.stop))),
        h('div', { class: 'row-main' },
          h('div', { class: 'row-title' }, g.title || 'Programme sans titre', live ? h('span', { class: 'pill live' }, 'En cours') : null),
          h('div', { class: 'row-sub' }, g.channel || 'Chaîne', g.desc ? h('span', { class: 'faint' }, ` · ${g.desc}`) : null)),
        add))
    }
    append(results, [list])
  }

  const form = h('form', {
    class: 'guide-search',
    onsubmit: async (e: Event) => {
      e.preventDefault()
      const q = input.value.trim()
      if (q.length < 2) return input.focus()
      clear(results)
      append(results, [h('p', { class: 'searching' }, h('span', { class: 'spinner', 'aria-hidden': 'true' }), 'Recherche dans le guide de tes appareils…')])
      go_.disabled = true
      try {
        show(await backend.searchGuide(account, q))
      } catch (err) {
        clear(results)
        append(results, [h('p', { class: 'form-error' }, err instanceof UserError ? err.message : friendlyError(err))])
      } finally {
        go_.disabled = false
      }
    },
  }, input, go_)

  const dialog = openDialog(h('div', {},
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('record', 20)),
      h('div', {}, h('h2', {}, 'Programmer un enregistrement'),
        h('p', { class: 'dialog-sub' }, 'Le guide est lu sur un appareil où Vyzo est ouvert.'))),
    form, results,
    h('p', { class: 'fine' }, icon('info', 13), hasPc
      ? 'Vyzo PC enregistre, même s’il est éteint maintenant : il le fera à sa prochaine ouverture si le programme n’est pas passé.'
      : 'Aucun Vyzo PC sur ce compte : c’est lui qui enregistre. Installe-le sur un ordinateur pour que l’enregistrement se fasse.'),
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Fermer'))), 'wide')
  input.focus()
}
