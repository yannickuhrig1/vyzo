import { append, clear, h, icon } from '../dom'
import {
  EMPTY_RULE, categoryLocked, checkRule, hashParental, isValidPin, minutesLabel, ruleSummary,
  type ContentKind, type Parental, type Profile, type ScreenTimeRule,
} from '../model'
import { act, avatar, confirmDialog, fold, loading, openDialog, plural } from '../ui'
import { backend, refresh, renderView, state } from '../context'
import { errorBanner, KIND_ICON, pageHeader, panel, sectionTitle } from '../shared'
import { vaultGate } from '../vault-ui'

let catalogQuery = ''

export function parentalView() {
  const box = h('section', {}, pageHeader('parental'), errorBanner('parental'))
  if (state.vault === null) return append(box, [loading()]), box
  if (state.vault !== 'open') {
    return append(box, [vaultGate('Réglages chiffrés',
      'Le code parental et les catégories de tes abonnements sont chiffrés avec le mot de passe du compte. Saisis-le pour les gérer.')]), box
  }
  const value = state.parental
  if (value === undefined) return append(box, [state.errors.parental ? null : loading(2)]), box

  const account = state.account!
  const save = (next: Parental | null, done: string, button: HTMLButtonElement | null = null) =>
    act(button, async () => { await backend.saveParental(account, next); state.parental = next; renderView() }, done)

  // Code
  const setCode = h('button', { class: 'btn primary', type: 'button', onclick: () => parentalPinDialog(value) },
    icon('lock', 14), value?.pinHash ? 'Changer le code' : value ? 'Définir un code' : 'Définir un code pour toute la maison')
  const removeCode = value?.pinHash ? h('button', {
    class: 'btn ghost', type: 'button',
    onclick: async (e: Event) => {
      const ok = await confirmDialog({
        title: 'Retirer le code parental ?', body: 'Sur tous les appareils à jour, les catégories verrouillées redeviennent visibles sans code.',
        confirm: 'Retirer le code', danger: true,
      })
      if (ok) await save({ ...value, pinHash: '' }, 'Code parental retiré', e.currentTarget as HTMLButtonElement)
    },
  }, 'Retirer le code') : null
  const stopSharing = value ? h('button', {
    class: 'btn danger-ghost', type: 'button',
    onclick: async (e: Event) => {
      const button = e.currentTarget as HTMLButtonElement
      const ok = await confirmDialog({
        title: 'Ne plus partager le code ?', body: 'Chaque appareil garde le code actuel, puis peut en changer seul. Le site ne le gère plus.',
        confirm: 'Ne plus partager',
      })
      if (ok) await save(null, 'Chaque appareil gère maintenant son code', button)
    },
  }, 'Ne plus partager') : null
  const codeText = !value ? 'Pour l’instant, chaque appareil a son propre code parental (ou aucun).'
    : value.pinHash ? 'Un code commun protège les catégories verrouillées sur tous tes appareils.'
      : 'Contrôle commun actif, mais sans code : les catégories verrouillées restent accessibles.'
  const code = panel('Code parental', codeText, [h('div', { class: 'row' }, setCode, removeCode, h('span', { class: 'spacer' }), stopSharing)],
    [icon('info', 14), value ? 'Appliqué par Vyzo 1.13.0-beta2 et plus, dès que l’appareil se synchronise.'
      : 'Le code choisi ici remplace celui de chaque appareil (Vyzo 1.13.0-beta2 et plus).'])

  // Options
  const option = (id: string, checked: boolean, title: string, text: string, change: (on: boolean) => Parental) => {
    const input = h('input', {
      id, type: 'checkbox', checked, disabled: !value,
      onchange: () => void save(change(input.checked), 'Contrôle parental enregistré'),
    })
    return h('label', { class: 'check', for: id }, input, h('span', {}, h('strong', {}, title), h('small', {}, text)))
  }
  const options = panel('Règles', value ? null : 'Disponibles une fois le code commun défini.', [
    option('pa-adult', value?.autoAdult ?? true, 'Verrouiller les catégories pour adultes',
      'Détectées automatiquement à partir du nom et des indications du fournisseur.', (on) => ({ ...value!, autoAdult: on })),
    option('pa-kid', value?.kidExit ?? true, 'Code pour quitter un profil enfant',
      'Un enfant ne peut pas passer seul sur un profil adulte.', (on) => ({ ...value!, kidExit: on })),
  ])

  append(box, [h('div', { class: 'settings' }, code, options, screenTimePanel(value), categoriesPanel(value?.autoAdult ?? true))])
  return box
}

function screenTimePanel(parental: Parental | null) {
  const profiles = [...(state.profiles ?? [])].sort((a, b) => Number(b.isKid) - Number(a.isKid) || a.position - b.position)
  const rules = state.screentime
  let body: (Node | null)[]
  if (!rules) body = [state.errors.screentime ? h('p', { class: 'form-error' }, state.errors.screentime) : loading(2)]
  else if (profiles.length === 0) body = [h('p', { class: 'empty small' }, 'Aucun profil sur ce compte.')]
  else {
    body = [h('ul', { class: 'rows' }, profiles.map((p) => {
      const r = rules.get(p.id)
      const active = !!r && r.enabled && ruleSummary(r) !== 'Aucune limite'
      return h('li', { class: 'row-card compact' },
        avatar(p, 'sm'),
        h('div', { class: 'row-main' },
          h('div', { class: 'row-title' }, p.name, p.isKid ? h('span', { class: 'pill muted' }, 'Enfant') : null),
          h('div', { class: 'row-sub' }, active ? h('span', {}, icon('clock', 12), ' ', ruleSummary(r)) : 'Aucune limite')),
        h('button', { class: `btn ${active ? 'ghost' : 'primary'} small`, type: 'button', onclick: () => screenTimeDialog(p, r ?? null) },
          active ? 'Modifier' : 'Limiter'))
    }))]
  }
  return panel('Temps d’écran', 'Une plage horaire et une durée par jour, comptée sur tous les appareils.', body,
    [icon('info', 14), parental?.pinHash
      ? 'Une fois la limite atteinte, la lecture s’arrête ; le code parental donne une heure de plus. Vyzo 1.13.0-beta3 et plus.'
      : 'Une fois la limite atteinte, la lecture s’arrête. Définis un code parental pour pouvoir prolonger. Vyzo 1.13.0-beta3 et plus.'])
}

function screenTimeDialog(p: Profile, current: ScreenTimeRule | null) {
  const account = state.account!
  const r = current ?? EMPTY_RULE
  const field = (id: string, value: string) => h('input', { id, type: 'time', value, step: 300 })
  const minutes = h('select', { id: 'st-daily' },
    [0, 30, 45, 60, 90, 120, 150, 180, 240, 300].concat(r.dailyMinutes && ![0, 30, 45, 60, 90, 120, 150, 180, 240, 300].includes(r.dailyMinutes) ? [r.dailyMinutes] : [])
      .sort((a, b) => a - b)
      .map((m) => h('option', { value: String(m), selected: m === r.dailyMinutes }, m === 0 ? 'Pas de limite' : minutesLabel(m))))
  const from = field('st-from', r.from)
  const to = field('st-to', r.to)
  const wFrom = field('st-wfrom', r.weekendFrom)
  const wTo = field('st-wto', r.weekendTo)
  const error = h('p', { class: 'form-error', role: 'alert' })
  const save: HTMLButtonElement = h('button', { class: 'btn primary', type: 'submit' }, 'Enregistrer')
  const write = async (rule: ScreenTimeRule | null, done: string, button: HTMLButtonElement) => {
    if (await act(button, () => backend.saveScreenTime(account, p.id, rule), done)) {
      dialog.close()
      await refresh(['screentime'])
    }
  }
  const removeBtn: HTMLButtonElement | null = current ? h('button', {
    class: 'btn danger-ghost', type: 'button', onclick: () => void write(null, `Plus de limite pour ${p.name}`, removeBtn!),
  }, 'Retirer les limites') : null
  const form = h('form', {
    onsubmit: (e: Event) => {
      e.preventDefault()
      const rule: ScreenTimeRule = {
        enabled: true, dailyMinutes: Number(minutes.value), from: from.value, to: to.value, weekendFrom: wFrom.value, weekendTo: wTo.value,
      }
      const problem = checkRule(rule) ?? ((!rule.from) !== (!rule.to) ? 'Semaine : indique l’heure de début et l’heure de fin' : null)
        ?? ((!rule.weekendFrom) !== (!rule.weekendTo) ? 'Week-end : indique l’heure de début et l’heure de fin' : null)
      if (problem) { error.textContent = problem; return }
      void write(rule, `Temps d’écran de ${p.name} enregistré`, save)
    },
  },
    h('div', { class: 'dialog-head' }, avatar(p, 'sm'), h('div', {}, h('h2', {}, 'Temps d’écran'), h('p', { class: 'dialog-sub' }, p.name))),
    h('label', { for: 'st-daily' }, 'Durée maximale par jour'), minutes,
    h('p', { class: 'fine' }, 'Tous appareils confondus : TV, téléphone et PC.'),
    h('fieldset', { class: 'time-range' }, h('legend', {}, 'Du lundi au vendredi'),
      h('div', { class: 'two' }, h('div', {}, h('label', { for: 'st-from' }, 'À partir de'), from), h('div', {}, h('label', { for: 'st-to' }, 'Jusqu’à'), to))),
    h('fieldset', { class: 'time-range' }, h('legend', {}, 'Samedi et dimanche'),
      h('div', { class: 'two' }, h('div', {}, h('label', { for: 'st-wfrom' }, 'À partir de'), wFrom), h('div', {}, h('label', { for: 'st-wto' }, 'Jusqu’à'), wTo))),
    h('p', { class: 'fine' }, 'Laisse vide pour ne pas limiter les heures. Sans horaire du week-end, celui de la semaine s’applique.'),
    error,
    h('div', { class: 'dialog-actions' }, removeBtn, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler'), save))
  const dialog = openDialog(form)
  minutes.focus()
}

function categoriesPanel(autoAdult: boolean) {
  const playlists = state.playlists ?? []
  const body: (Node | null)[] = []
  if (playlists.length === 0) {
    body.push(h('p', { class: 'empty small' }, 'Aucun abonnement sur le compte.'))
    return panel('Catégories verrouillées', null, body)
  }
  if (playlists.length > 1) {
    body.push(h('div', { class: 'picker' }, playlists.map((p) => h('button', {
      type: 'button', class: 'chip', 'aria-selected': String(p.id === state.catalogFor),
      onclick: () => {
        if (state.catalogFor === p.id) return
        state.catalogFor = p.id
        state.catalog = null
        renderView()
        void refresh(['catalog'])
      },
    }, icon('key', 13), h('span', {}, p.name)))))
  }
  const data = state.catalog?.playlistId === state.catalogFor ? state.catalog : null
  if (!data) {
    body.push(state.errors.catalog ? errorBanner('catalog') : loading(2))
    return panel('Catégories verrouillées', 'Elles sont cachées tant que le code n’est pas saisi, et toujours sur un profil enfant.', body)
  }
  if (data.entries.length === 0) {
    body.push(h('p', { class: 'empty small' },
      'Aucun appareil n’a encore envoyé la liste des catégories de cet abonnement. Ouvre Vyzo 1.13.0-beta2 ou plus sur un appareil qui l’utilise.'))
    return panel('Catégories verrouillées', null, body)
  }

  const prefs = new Map(data.prefs.categories.map((c) => [c.rest, c]))
  const rows = data.entries.map((e) => ({ e, pref: prefs.get(e.rest), locked: categoryLocked(e, prefs.get(e.rest), autoAdult) }))
  const search = h('input', {
    id: 'cat-search', type: 'search', placeholder: `Chercher parmi ${data.entries.length} catégories…`, value: catalogQuery,
    autocomplete: 'off', spellcheck: 'false', 'aria-label': 'Chercher une catégorie',
  })
  const results = h('div', {})
  const KIND_TITLE: Record<ContentKind, string> = { LIVE: 'Chaînes', MOVIE: 'Films', SERIES: 'Séries' }
  const renderRows = () => {
    clear(results)
    const tokens = fold(catalogQuery).split(/\s+/).filter(Boolean)
    const shown = tokens.length
      ? rows.filter((r) => tokens.every((t) => fold(r.pref?.name || r.e.name).includes(t))).slice(0, 80)
      : rows.filter((r) => r.locked)
    if (shown.length === 0) {
      results.appendChild(h('p', { class: 'empty small' }, tokens.length ? 'Aucune catégorie ne correspond.' : 'Aucune catégorie verrouillée. Cherche une catégorie pour la verrouiller.'))
      return
    }
    for (const kind of ['LIVE', 'MOVIE', 'SERIES'] as const) {
      const group = shown.filter((r) => r.e.kind === kind)
      if (group.length === 0) continue
      append(results, [sectionTitle(KIND_TITLE[kind], KIND_ICON[kind], group.length), h('ul', { class: 'rows' }, group.map((r) => {
        const toggle: HTMLButtonElement = h('button', {
          class: `btn ${r.locked ? 'ghost' : 'outline'} small`, type: 'button',
          onclick: async () => {
            const next = !r.locked
            if (await act(toggle, () => backend.setCategoryLock(state.account!, data.playlistId, r.e.rest, next),
              next ? `« ${r.pref?.name || r.e.name} » verrouillée` : `« ${r.pref?.name || r.e.name} » déverrouillée`)) await refresh(['catalog'])
          },
        }, icon(r.locked ? 'unlock' : 'lock', 13), r.locked ? 'Déverrouiller' : 'Verrouiller')
        const auto = r.locked && r.pref?.locked == null
        return h('li', { class: 'row-card compact' },
          h('span', { class: `kind-badge ${r.locked ? 'k-locked' : ''}` }, icon(r.locked ? 'lock' : KIND_ICON[kind], 15)),
          h('div', { class: 'row-main' },
            h('div', { class: 'row-title' }, r.pref?.name || r.e.name),
            auto ? h('div', { class: 'row-sub' }, 'Détectée pour adultes') : r.pref?.name && r.pref.name !== r.e.name ? h('div', { class: 'row-sub' }, r.e.name) : null),
          toggle)
      }))])
    }
  }
  search.addEventListener('input', () => { catalogQuery = search.value; renderRows() })
  renderRows()
  const lockedCount = rows.filter((r) => r.locked).length
  body.push(h('div', { class: 'catalog-search' }, icon('search', 15), search), results)
  return panel('Catégories verrouillées', `${plural(lockedCount, 'catégorie verrouillée', 'catégories verrouillées')}. Elles sont cachées tant que le code n’est pas saisi, et toujours sur un profil enfant.`, body)
}

function parentalPinDialog(current: Parental | null | undefined) {
  const pin1 = h('input', { id: 'pp-1', type: 'password', inputmode: 'numeric', maxlength: 4, autocomplete: 'new-password', placeholder: '••••' })
  const pin2 = h('input', { id: 'pp-2', type: 'password', inputmode: 'numeric', maxlength: 4, autocomplete: 'new-password', placeholder: '••••' })
  const error = h('p', { class: 'form-error', role: 'alert' })
  const save = h('button', { class: 'btn primary', type: 'submit' }, 'Enregistrer le code')
  const form = h('form', {
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      error.textContent = ''
      if (!isValidPin(pin1.value)) { error.textContent = 'Le code doit faire 4 chiffres.'; pin1.focus(); return }
      if (pin1.value !== pin2.value) { error.textContent = 'Les deux codes ne correspondent pas.'; pin2.focus(); return }
      const next: Parental = { pinHash: await hashParental(pin1.value), autoAdult: current?.autoAdult ?? true, kidExit: current?.kidExit ?? true }
      if (await act(save, () => backend.saveParental(state.account!, next), current ? 'Code parental changé sur tous les appareils' : 'Code parental commun défini')) {
        state.parental = next
        dialog.close()
        renderView()
      }
    },
  },
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('lock', 20)), h('h2', {}, 'Code parental')),
    h('p', { class: 'dialog-sub' }, 'Demandé sur la TV, le téléphone et le PC pour ouvrir une catégorie verrouillée.'),
    h('div', { class: 'pin-fields' }, h('label', { for: 'pp-1' }, 'Nouveau code', pin1), h('label', { for: 'pp-2' }, 'Confirmer', pin2)),
    error,
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler'), save))
  const dialog = openDialog(form, 'small')
  pin1.focus()
}
