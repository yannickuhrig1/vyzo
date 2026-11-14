import { append, h, icon } from '../dom'
import {
  compareUsagePeriods, dayUsageBreakdown, deviceName, summarizeUsage, usageByProfile, usageToCsv,
} from '../model'
import { avatar, barFill, dayLabel, duration, loading, longDayFmt, plural, emptyCard } from '../ui'
import { renderView, state } from '../context'
import { DEVICE_ICON, errorBanner, pageHeader, sectionTitle } from '../shared'
import { vaultGate } from '../vault-ui'

let statsProfile: string | null = null
let statsDays: 7 | 14 | 30 = 7
let selectedDay: string | null = null

export function statsView() {
  const profiles = state.profiles ?? []
  const devices = state.devices ?? []
  const rows = state.usage
  const open = state.vault === 'open'

  const exportBtn: HTMLButtonElement | null = open && rows && rows.length > 0 ? h('button', {
    class: 'btn ghost', type: 'button',
    onclick: () => {
      const targetRows = statsProfile ? rows.filter((r) => r.profile === statsProfile) : rows
      const csv = usageToCsv(targetRows, profiles, devices)
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
      const a = h('a', { href: url, download: `vyzo-statistiques-${new Date().toISOString().slice(0, 10)}.csv` })
      document.body.appendChild(a)
      a.click()
      a.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 30_000)
    },
  }, icon('download', 15), 'Exporter CSV') : null

  const box = h('section', {}, pageHeader('statistiques', exportBtn), errorBanner('usage'))
  if (state.vault === null) return append(box, [loading()]), box
  if (!open) {
    return append(box, [vaultGate('Statistiques chiffrées', 'Le temps passé sur chaque titre est chiffré avec le mot de passe du compte. Saisis-le pour le voir.')]), box
  }
  if (!rows) return append(box, [state.errors.usage ? null : loading(3)]), box
  if (statsProfile && !profiles.some((p) => p.id === statsProfile)) statsProfile = null

  const chip = (label: string, active: boolean, run: () => void, lead: Node | null = null) =>
    h('button', { class: 'chip', type: 'button', 'aria-pressed': String(active), onclick: () => { run(); renderView() } }, lead, label)
  const filters = h('div', { class: 'stats-filters' },
    h('div', { class: 'chips' },
      chip('Tous', statsProfile === null, () => { statsProfile = null }),
      profiles.map((p) => chip(p.name, statsProfile === p.id, () => { statsProfile = p.id }, avatar(p, 'xs')))),
    h('div', { class: 'segmented small', role: 'radiogroup', 'aria-label': 'Période' },
      ([7, 14, 30] as const).map((n) => h('button', {
        type: 'button', role: 'radio', 'aria-checked': String(statsDays === n), onclick: () => { statsDays = n; renderView() },
      }, `${n} jours`))))
  append(box, [filters])

  if (rows.length === 0) {
    append(box, [emptyCard('chart', 'Pas encore de statistiques',
      'Le temps passé est compté par Vyzo 1.13.0-beta3 et plus, puis envoyé chiffré au compte toutes les quelques minutes.')])
    return box
  }
  const sum = summarizeUsage(rows, statsProfile, statsDays)
  const cmp = compareUsagePeriods(rows, statsProfile, statsDays)
  const watchedDays = sum.perDay.filter((d) => d.ms > 0).length
  const best = sum.perDay.reduce((a, b) => (b.ms > a.ms ? b : a), sum.perDay[0])

  if (!selectedDay || !sum.perDay.some((d) => d.day === selectedDay)) {
    selectedDay = [...sum.perDay].reverse().find((d) => d.ms > 0)?.day ?? sum.perDay.at(-1)?.day ?? null
  }

  let trendNote: Node | string = `sur ${statsDays} jours`
  if (statsDays !== 30 && cmp.previousMs > 0 && cmp.deltaPct !== null) {
    const sign = cmp.deltaMs > 0 ? '+' : cmp.deltaMs < 0 ? '−' : ''
    const diffStr = cmp.deltaMs === 0 ? 'identique' : `${sign}${cmp.deltaPct === 0 ? duration(Math.abs(cmp.deltaMs)) : `${Math.abs(cmp.deltaPct)} %`}`
    trendNote = h('span', { class: `kpi-trend ${cmp.deltaMs > 0 ? 'up' : cmp.deltaMs < 0 ? 'down' : ''}` },
      `${diffStr} vs ${statsDays} j précédents`)
  }

  const kpi = (label: string, value: string, note: Node | string) =>
    h('div', { class: 'kpi static' }, h('span', { class: 'kpi-label' }, label), h('span', { class: 'kpi-value' }, value), h('span', { class: 'kpi-note' }, note))
  append(box, [h('div', { class: 'kpis' },
    kpi('Temps total', sum.totalMs ? duration(sum.totalMs) : '0 min', trendNote),
    kpi('Par jour', sum.totalMs ? duration(sum.totalMs / statsDays) : '0 min', `en moyenne · ${plural(watchedDays, 'jour actif', 'jours actifs')}`),
    kpi('Jour le plus chargé', best && best.ms ? duration(best.ms) : '—', best && best.ms ? dayLabel(new Date(`${best.day}T12:00`).getTime()) : 'Aucun'),
    kpi('Le plus regardé', sum.top[0] ? duration(sum.top[0].ms) : '—', sum.top[0]?.title ?? 'Rien pour l’instant'))])

  // Barres empilées et cliquables par jour
  const max = Math.max(1, ...sum.perDay.map((d) => d.ms))
  const chart = h('div', { class: `day-chart ${statsDays >= 14 ? 'dense' : ''}`, role: 'group',
    'aria-label': `Temps par jour sur ${statsDays} jours` },
  sum.perDay.map((d) => {
    const date = new Date(`${d.day}T12:00`)
    const dk = sum.perDayKinds.get(d.day)
    const stackWrap = h('span', { class: 'day-stack-wrap' })
    stackWrap.style.height = `${Math.round((d.ms / max) * 100)}%`
    if (d.ms > 0 && dk) {
      const segs: [string, number][] = [['live', dk.live], ['movie', dk.movie], ['episode', dk.episode], ['other', dk.other]]
      const totalDay = Math.max(d.ms, dk.live + dk.movie + dk.episode + dk.other)
      let added = false
      for (const [k, part] of segs) {
        if (part <= 0) continue
        const s = h('span', { class: `day-seg k-${k}` })
        s.style.height = `${(part / totalDay) * 100}%`
        stackWrap.appendChild(s)
        added = true
      }
      if (!added) {
        const fallback = h('span', { class: 'day-bar' })
        fallback.style.height = '100%'
        stackWrap.appendChild(fallback)
      }
    }
    const weekday = date.toLocaleDateString('fr-FR', { weekday: 'short' }).replace('.', '')
    const active = selectedDay === d.day
    return h('button', {
      type: 'button', class: 'day-col', 'aria-pressed': String(active),
      title: `${dayLabel(date.getTime())} : ${d.ms ? duration(d.ms) : 'rien'}`,
      onclick: () => { selectedDay = d.day; renderView() },
    },
      h('span', { class: 'day-value' }, d.ms ? duration(d.ms) : ''),
      h('span', { class: 'day-track stacked' }, stackWrap),
      h('span', { class: 'day-label' }, statsDays === 7 ? weekday : String(date.getDate())))
  }))
  const kinds = [
    ['Direct et replay', sum.byKind.live, 'live'], ['Films', sum.byKind.movie, 'movie'], ['Séries', sum.byKind.episode, 'episode'],
    ['Enregistrements', sum.byKind.other, 'other'],
  ] as const
  const total = Math.max(1, sum.totalMs)
  const stack = h('div', { class: 'kind-stack', role: 'img', 'aria-label': 'Répartition par type' },
    kinds.filter(([, ms]) => ms > 0).map(([label, ms, k]) => {
      const seg = h('span', { class: `seg k-${k}`, title: `${label} : ${duration(ms)}` })
      seg.style.width = `${(ms / total) * 100}%`
      return seg
    }))
  const legend = h('ul', { class: 'kind-legend' }, kinds.filter(([, ms]) => ms > 0).map(([label, ms, k]) =>
    h('li', {}, h('span', { class: `legend-dot k-${k}` }), h('span', {}, label), h('span', { class: 'mono faint' }, `${Math.round((ms / total) * 100)} %`))))

  // Détail du jour sélectionné
  let dayDetail: HTMLElement | null = null
  if (selectedDay) {
    const info = dayUsageBreakdown(rows, selectedDay, statsProfile, profiles)
    const label = longDayFmt.format(new Date(`${selectedDay}T12:00`))
    dayDetail = h('div', { class: 'day-detail' },
      h('div', { class: 'day-detail-head' },
        h('strong', {}, label),
        h('span', { class: 'mono' }, info.totalMs ? duration(info.totalMs) : 'Aucune lecture')),
      info.byProfile.length > 1 && !statsProfile
        ? h('div', { class: 'day-detail-profiles' }, info.byProfile.map((p) =>
          h('span', { class: 'pill muted' }, `${p.profile.name} · ${duration(p.ms)}`)))
        : null,
      info.top.length > 0
        ? h('ul', { class: 'day-detail-titles' }, info.top.map((t) =>
          h('li', {}, h('span', { class: 'ellipsis' }, t.title), h('span', { class: 'mono faint' }, duration(t.ms)))))
        : null)
  }

  // Répartition par profil (en mode « Tous » si au moins 2 profils actifs)
  const profileSlices = statsProfile === null ? usageByProfile(rows, statsDays, profiles) : []
  const profileBlock = profileSlices.length > 1
    ? h('div', {},
      sectionTitle('Par profil', 'users', null),
      h('ul', { class: 'rows' }, profileSlices.map((s) => h('li', { class: 'row-card compact' },
        avatar(s.profile, 'sm'),
        h('div', { class: 'row-main' },
          h('div', { class: 'top-line' },
            h('span', { class: 'row-title' }, s.profile.name),
            h('span', { class: 'mono faint' }, `${duration(s.totalMs)} · ${Math.round(s.share * 100)} %`)),
          h('div', { class: 'bar wide-bar' }, barFill(s.share))),
        h('button', {
          class: 'btn ghost small', type: 'button',
          onclick: () => { statsProfile = s.profile.id; renderView() },
        }, 'Filtrer')))))
    : null

  const topMax = Math.max(1, ...sum.top.map((t) => t.ms))
  const top = sum.top.length ? h('ol', { class: 'top-list' }, sum.top.map((t) => h('li', {},
    h('div', { class: 'top-line' }, h('span', { class: 'top-title' }, t.title), h('span', { class: 'mono faint' }, duration(t.ms))),
    h('div', { class: 'bar' }, barFill(t.ms / topMax))))) : h('p', { class: 'empty small' }, 'Rien sur cette période.')

  const byDevice = [...sum.byDevice.entries()].sort((a, b) => b[1] - a[1])
  const deviceList = byDevice.length ? h('ul', { class: 'rows' }, byDevice.map(([id, ms]) => {
    const d = devices.find((x) => x.id === id)
    return h('li', { class: 'row-card compact' },
      h('span', { class: 'kind-badge' }, icon(DEVICE_ICON[d?.kind ?? ''] ?? 'devices', 16)),
      h('div', { class: 'row-main' }, h('div', { class: 'row-title' }, d ? deviceName(d) : 'Appareil retiré')),
      h('span', { class: 'mono faint' }, duration(ms)))
  })) : h('p', { class: 'empty small' }, 'Rien sur cette période.')

  append(box, [h('div', { class: 'dash-grid' },
    h('div', {}, sectionTitle('Jour par jour', 'calendar', null), h('div', { class: 'panel chart-panel' }, chart, stack, legend, dayDetail), profileBlock),
    h('div', {}, sectionTitle('Les plus regardés', 'play', null), top, sectionTitle('Par appareil', 'devices', null), deviceList))])
  append(box, [h('p', { class: 'note' }, icon('info', 15), h('span', {},
    'Compté sur les appareils en Vyzo 1.13.0-beta3 et plus, lecture en cours seulement (pas les menus). Les 35 derniers jours sont gardés, chiffrés.'))])
  return box
}
