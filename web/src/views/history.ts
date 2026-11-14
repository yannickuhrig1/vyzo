import { append, clear, h, icon } from '../dom'
import { castItemType, progressRatio, type Profile, type ProgressItem } from '../model'
import { act, barFill, confirmDialog, duration, emptyCard, fold, fullFmt, loading, plural, relative } from '../ui'
import { backend, isHidden, refresh, renderView, softDelete, state } from '../context'
import { contentFilterBar, contentTitle, errorBanner, kindBadge, pageHeader, profilePicker, type KindFilter } from '../shared'
import { titlesNotice } from '../vault-ui'
import { castButton } from '../cast'

let historyQuery = ''
let historyKind: KindFilter = 'ALL'

export function historyView() {
  const profile = state.profiles?.find((p) => p.id === state.selected) ?? null
  const progress = state.progress?.profileId === state.selected
    ? state.progress.items.filter((x) => !isHidden(`p:${x.profileId}:${x.contentKey}`)) : null
  const finished = (progress ?? []).filter((x) => x.durationMs > 0 && progressRatio(x) >= 0.95)
  const removeFinished: HTMLButtonElement | null = profile && finished.length > 0 ? h('button', {
    class: 'btn ghost', type: 'button',
    onclick: async () => {
      const ok = await confirmDialog({
        title: `Retirer les titres terminés de ${profile.name} ?`,
        body: `${plural(finished.length, 'titre terminé disparaît', 'titres terminés disparaissent')} de l’historique, sur tous les appareils.`,
        confirm: 'Retirer les terminés', danger: true,
      })
      if (ok && removeFinished && await act(removeFinished,
        () => backend.removeProgress(state.account!, profile.id, finished.map((x) => x.contentKey)),
        `${plural(finished.length, 'titre retiré', 'titres retirés')}`)) {
        await refresh(['progress', 'activity'])
      }
    },
  }, icon('check', 15), `Retirer les terminés (${finished.length})`) : null

  const clearAll: HTMLButtonElement | null = profile ? h('button', {
    class: 'btn danger-ghost', type: 'button', disabled: !progress || progress.length === 0,
    onclick: async () => {
      const n = progress?.length ?? 0
      const ok = await confirmDialog({
        title: `Effacer l’historique de ${profile.name} ?`,
        body: `${plural(n, 'contenu disparaît', 'contenus disparaissent')} de « Reprendre » et de l’historique, sur tous les appareils. Les listes ne sont pas touchées.`,
        confirm: 'Tout effacer', danger: true,
      })
      if (ok && await act(clearAll, () => backend.clearHistory(state.account!, profile.id), `Historique de ${profile.name} effacé`)) {
        await refresh(['progress', 'activity'])
      }
    },
  }, icon('trash', 15), 'Tout effacer') : null

  const box = h('section', {},
    pageHeader('historique', removeFinished, clearAll),
    profilePicker((p) => state.activity?.find((a) => a.profileId === p.id)?.inProgress ?? null),
    errorBanner('progress'))
  if (!state.profiles) return append(box, [loading()]), box
  if (!profile) return append(box, [h('p', { class: 'empty' }, 'Aucun profil sur ce compte.')]), box
  if (!progress) return append(box, [state.errors.progress ? null : loading()]), box
  if (progress.length === 0) {
    append(box, [emptyCard('history', 'Rien en cours', `${profile.name} n’a rien commencé pour l’instant, ou l’historique vient d’être effacé.`)])
    return box
  }

  const results = h('div', {})
  const renderResults = () => {
    clear(results)
    const tokens = fold(historyQuery).split(/\s+/).filter(Boolean)
    const shown = progress.filter((x) => {
      if (historyKind !== 'ALL' && x.kind !== historyKind) return false
      if (tokens.length === 0) return true
      const hay = fold(`${x.title} ${x.subtitle}`)
      return tokens.every((t) => hay.includes(t))
    })
    if (shown.length === 0) {
      append(results, [h('div', { class: 'filter-empty' },
        h('p', { class: 'empty small' }, 'Aucun titre ne correspond à ce filtre.'),
        h('button', {
          class: 'btn ghost small', type: 'button',
          onclick: () => { historyQuery = ''; historyKind = 'ALL'; renderView() },
        }, 'Tout afficher'))])
      return
    }
    append(results, [h('ul', { class: 'rows' }, shown.map((x) => progressRow(x, profile)))])
  }

  append(box, [
    titlesNotice(progress),
    contentFilterBar({
      id: 'hist-search',
      placeholder: `Chercher parmi ${progress.length} titres…`,
      query: historyQuery,
      kind: historyKind,
      onQuery: (q) => { historyQuery = q; renderResults() },
      onKind: (k) => { historyKind = k; renderResults() },
    }),
    results,
  ])
  renderResults()
  return box
}

function progressRow(x: ProgressItem, profile: Profile) {
  const t = contentTitle(x)
  const ratio = progressRatio(x)
  const account = state.account!
  const remove = h('button', {
    class: 'icon-btn', type: 'button', title: 'Retirer de l’historique', 'aria-label': `Retirer ${t.text} de l’historique`,
    onclick: () => softDelete(`p:${x.profileId}:${x.contentKey}`, t.known ? `« ${t.text} » retiré de l’historique` : 'Retiré de l’historique',
      () => backend.removeProgress(account, profile.id, [x.contentKey]), ['progress', 'activity']),
  }, icon('x', 15))

  const when = x.kind === 'LIVE' ? `Regardée ${relative(x.updatedAt)}` : relative(x.updatedAt)
  const watched = x.durationMs > 0
    ? (ratio >= 0.95 ? 'Terminé' : `${Math.round(ratio * 100)} % · reste ${duration(x.durationMs - x.positionMs)}`)
    : null
  return h('li', { class: 'row-card' },
    kindBadge(x.kind),
    h('div', { class: 'row-main' },
      h('div', { class: `row-title ${t.known ? '' : 'unknown'}` }, t.text),
      h('div', { class: 'row-sub wrap' },
        x.subtitle ? h('span', { class: 'ellipsis' }, x.subtitle) : null,
        x.subtitle ? h('span', { class: 'faint' }, '·') : null,
        h('span', { class: 'faint', title: fullFmt.format(x.updatedAt) }, when)),
      watched ? h('div', { class: 'progress-line' },
        h('span', { class: 'bar' }, barFill(ratio)),
        h('span', { class: 'bar-label' }, watched)) : null),
    h('div', { class: 'row-actions' },
      castButton({ contentKey: x.contentKey, itemType: castItemType(x.kind, true), title: x.title, positionMs: ratio >= 0.95 ? 0 : x.positionMs }, t.text),
      remove),
  )
}
