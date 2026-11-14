import { append, h, icon } from '../dom'
import { SENSITIVE_EVENTS, eventText } from '../model'
import { dayLabel, emptyCard, fullFmt, loading, timeFmt } from '../ui'
import { renderView, state } from '../context'
import { errorBanner, pageHeader } from '../shared'

let journalFilter: 'all' | 'security' = 'all'

export function journalView() {
  const box = h('section', {}, pageHeader('journal',
    h('div', { class: 'segmented small', role: 'radiogroup', 'aria-label': 'Filtre' },
      ([['all', 'Tout'], ['security', 'Sécurité']] as const).map(([id, label]) => h('button', {
        type: 'button', role: 'radio', 'aria-checked': String(journalFilter === id), onclick: () => { journalFilter = id; renderView() },
      }, label)))), errorBanner('events'))
  const events = state.events
  if (!events) return append(box, [state.errors.events ? null : loading(4)]), box
  const shown = journalFilter === 'security' ? events.filter((e) => SENSITIVE_EVENTS.has(e.kind)) : events
  if (shown.length === 0) {
    append(box, [emptyCard('journal', journalFilter === 'security' ? 'Rien à signaler' : 'Journal vide',
      'Les connexions d’appareils, changements de mot de passe, abonnements et profils apparaîtront ici.')])
    return box
  }
  let day = ''
  const list = h('ol', { class: 'timeline' })
  for (const e of shown) {
    const label = dayLabel(e.at)
    if (label !== day) {
      day = label
      list.appendChild(h('li', { class: 'tl-day' }, label))
    }
    const t = eventText(e)
    list.appendChild(h('li', { class: `tl-item ${SENSITIVE_EVENTS.has(e.kind) ? 'sensitive' : ''}` },
      h('span', { class: 'tl-icon' }, icon(t.icon, 14)),
      h('div', { class: 'tl-main' }, h('div', { class: 'tl-title' }, t.label), e.detail ? h('div', { class: 'tl-detail' }, e.detail) : null),
      h('time', { class: 'mono faint', datetime: new Date(e.at).toISOString(), title: fullFmt.format(e.at) }, timeFmt.format(e.at))))
  }
  append(box, [list, h('p', { class: 'note' }, icon('info', 15), h('span', {},
    'Écrit par le serveur du compte : personne ne peut le modifier ni l’effacer. Un appareil inconnu ? Change le mot de passe, puis « Déconnecter les autres appareils » dans Sécurité.'))])
  return box
}
