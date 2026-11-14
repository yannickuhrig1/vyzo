import { type ListsData } from '../backend'
import { append, clear, h, icon } from '../dom'
import { FAVORITES_LIST_ID, castItemType, moveUserList, sortUserLists, type ListItem, type Profile, type UserList } from '../model'
import { act, fold, loading, openDialog, relative } from '../ui'
import { backend, isHidden, refresh, renderView, softDelete, state } from '../context'
import {
  contentFilterBar, contentTitle, errorBanner, KIND_LABEL, kindBadge, pageHeader, profilePicker, sectionTitle, type KindFilter,
} from '../shared'
import { titlesNotice } from '../vault-ui'
import { castButton } from '../cast'

let listsQuery = ''
let listsKind: KindFilter = 'ALL'

export function listsView() {
  const profile = state.profiles?.find((p) => p.id === state.selected) ?? null
  const data = state.lists?.profileId === state.selected ? state.lists : null
  const add = profile && data ? h('button', { class: 'btn primary', type: 'button', onclick: () => editList(profile, null, data) },
    icon('plus', 16), 'Nouvelle liste') : null

  const box = h('section', {},
    pageHeader('listes', add),
    profilePicker((p) => state.activity?.find((a) => a.profileId === p.id)?.inLists ?? null),
    errorBanner('lists'))
  if (!state.profiles) return append(box, [loading()]), box
  if (!profile) return append(box, [h('p', { class: 'empty' }, 'Aucun profil sur ce compte.')]), box
  if (!data) return append(box, [state.errors.lists ? null : loading()]), box

  const visibleItems = data.items.filter((x) => !isHidden(`i:${x.profileId}:${x.listId}:${x.contentKey}`))
  const favorites: UserList = { profileId: profile.id, id: FAVORITES_LIST_ID, name: 'Favoris', position: -1, updatedAt: 0 }
  const customLists = sortUserLists(data.lists.filter((l) => !isHidden(`l:${l.profileId}:${l.id}`)))
  const lists = [favorites, ...customLists]
  const results = h('div', {})

  const renderResults = () => {
    clear(results)
    const tokens = fold(listsQuery).split(/\s+/).filter(Boolean)
    const filtering = tokens.length > 0 || listsKind !== 'ALL'
    let matchedTotal = 0
    for (const list of lists) {
      const listItems = visibleItems
        .filter((x) => x.listId === list.id)
        .filter((x) => {
          if (listsKind !== 'ALL' && x.kind !== listsKind) return false
          if (tokens.length === 0) return true
          return tokens.every((t) => fold(`${x.title} ${x.subtitle}`).includes(t))
        })
        .sort((a, b) => a.position - b.position || b.addedAt - a.addedAt)
      if (filtering && listItems.length === 0) continue
      matchedTotal += listItems.length
      const isFav = list.id === FAVORITES_LIST_ID
      const customIdx = customLists.findIndex((l) => l.id === list.id)
      const up = isFav ? null : h('button', {
        class: 'icon-btn', type: 'button', title: 'Monter la liste', 'aria-label': `Monter ${list.name}`, disabled: customIdx === 0,
        onclick: (e: Event) => void reorderList(e.currentTarget as HTMLButtonElement, customLists, list.id, -1),
      }, icon('up', 14))
      const down = isFav ? null : h('button', {
        class: 'icon-btn', type: 'button', title: 'Descendre la liste', 'aria-label': `Descendre ${list.name}`, disabled: customIdx === customLists.length - 1,
        onclick: (e: Event) => void reorderList(e.currentTarget as HTMLButtonElement, customLists, list.id, 1),
      }, icon('down', 14))
      const del = isFav ? null : h('button', {
        class: 'icon-btn', type: 'button', title: 'Supprimer la liste', 'aria-label': `Supprimer la liste ${list.name}`,
        onclick: () => softDelete(`l:${list.profileId}:${list.id}`, `Liste « ${list.name} » supprimée`,
          () => backend.deleteList(list), ['lists', 'activity']),
      }, icon('trash', 14))
      append(results, [
        h('div', { class: 'list-head' },
          sectionTitle(list.name, isFav ? 'heart' : 'list', listItems.length),
          isFav ? null : h('span', { class: 'order' },
            up, down,
            h('button', { class: 'icon-btn', type: 'button', title: 'Renommer', 'aria-label': `Renommer ${list.name}`,
              onclick: () => editList(profile, list, data) }, icon('edit', 14)),
            del)),
        listItems.length === 0
          ? h('p', { class: 'empty small' }, isFav ? 'Aucun favori.' : 'Liste vide. Ajoute des films ou des séries depuis l’appli.')
          : h('ul', { class: 'rows' }, listItems.map((x) => listItemRow(x, list, lists, visibleItems))),
      ])
    }
    if (filtering && matchedTotal === 0) {
      append(results, [h('div', { class: 'filter-empty' },
        h('p', { class: 'empty small' }, 'Aucun élément ne correspond à ce filtre.'),
        h('button', {
          class: 'btn ghost small', type: 'button',
          onclick: () => { listsQuery = ''; listsKind = 'ALL'; renderView() },
        }, 'Tout afficher'))])
    }
  }

  append(box, [
    titlesNotice(visibleItems),
    visibleItems.length > 0 ? contentFilterBar({
      id: 'lists-search',
      placeholder: 'Chercher dans les listes…',
      query: listsQuery,
      kind: listsKind,
      onQuery: (q) => { listsQuery = q; renderResults() },
      onKind: (k) => { listsKind = k; renderResults() },
    }) : null,
    results,
  ])
  renderResults()
  return box
}

async function reorderList(button: HTMLButtonElement, lists: UserList[], id: string, delta: -1 | 1) {
  const changed = moveUserList(lists, id, delta)
  if (changed.length && await act(button, () => backend.saveLists(changed))) await refresh(['lists'])
}

function listItemRow(x: ListItem, list: UserList, allLists: UserList[], allItems: ListItem[]) {
  const t = contentTitle(x)
  const move = allLists.length > 1 ? h('button', {
    class: 'icon-btn', type: 'button', title: 'Déplacer vers une autre liste', 'aria-label': `Déplacer ${t.text} vers une autre liste`,
    onclick: () => moveItemDialog(x, t.text, allLists, allItems),
  }, icon('arrow', 14)) : null
  const remove = h('button', {
    class: 'icon-btn', type: 'button', title: `Retirer de « ${list.name} »`, 'aria-label': `Retirer ${t.text} de ${list.name}`,
    onclick: () => softDelete(`i:${x.profileId}:${x.listId}:${x.contentKey}`, `Retiré de « ${list.name} »`,
      () => backend.removeListItem(x), ['lists', 'activity']),
  }, icon('x', 15))
  return h('li', { class: 'row-card compact' },
    kindBadge(x.kind),
    h('div', { class: 'row-main' },
      h('div', { class: `row-title ${t.known ? '' : 'unknown'}` }, t.text),
      h('div', { class: 'row-sub' }, h('span', { class: 'ellipsis' },
        [t.known ? KIND_LABEL[x.kind] : null, x.subtitle || null, x.addedAt > 0 ? `ajouté ${relative(x.addedAt)}` : null].filter(Boolean).join(' · ')))),
    h('div', { class: 'row-actions' },
      castButton({ contentKey: x.contentKey, itemType: castItemType(x.kind, false), title: x.title, positionMs: 0 }, t.text),
      move,
      remove),
  )
}

function moveItemDialog(x: ListItem, title: string, lists: UserList[], allItems: ListItem[]) {
  const targets = lists.filter((l) => l.id !== x.listId)
  const rows = h('ul', { class: 'rows' }, targets.map((target) => {
    const targetItems = allItems.filter((i) => i.listId === target.id)
    const nextPos = targetItems.reduce((m, i) => Math.max(m, i.position), -1) + 1
    const isFav = target.id === FAVORITES_LIST_ID
    const btn: HTMLButtonElement = h('button', {
      class: 'btn ghost small', type: 'button',
      onclick: async () => {
        if (await act(btn, () => backend.moveListItem(x, target.id, nextPos), `Déplacé dans « ${target.name} »`)) {
          dialog.close()
          await refresh(['lists', 'activity'])
        }
      },
    }, 'Choisir')
    return h('li', { class: 'row-card compact' },
      h('span', { class: 'kind-badge' }, icon(isFav ? 'heart' : 'list', 15)),
      h('div', { class: 'row-main' },
        h('div', { class: 'row-title' }, target.name),
        h('div', { class: 'row-sub' }, `${targetItems.length} élément${targetItems.length > 1 ? 's' : ''}`)),
      btn)
  }))
  const dialog = openDialog(h('div', {},
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('arrow', 18)),
      h('div', {}, h('h2', {}, 'Déplacer vers…'), h('p', { class: 'dialog-sub' }, title))),
    rows,
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler'))), 'small')
}

function editList(profile: Profile, existing: UserList | null, data: ListsData) {
  const name = h('input', { id: 'list-name', value: existing?.name ?? '', maxlength: 40, required: true, autocomplete: 'off' })
  const error = h('p', { class: 'form-error', role: 'alert' })
  const save = h('button', { class: 'btn primary', type: 'submit' }, existing ? 'Renommer' : 'Créer la liste')
  const cancel = h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler')
  const form = h('form', {
    method: 'dialog',
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      const clean = name.value.trim().replace(/\s+/g, ' ')
      if (!clean) { error.textContent = 'Donne un nom à la liste.'; return }
      const list: UserList = existing ? { ...existing, name: clean } : {
        profileId: profile.id, id: crypto.randomUUID(), name: clean, updatedAt: 0,
        position: data.lists.reduce((m, l) => Math.max(m, l.position), 0) + 1,
      }
      if (await act(save, () => backend.saveList(list), existing ? 'Liste renommée' : `Liste « ${clean} » créée pour ${profile.name}`)) {
        dialog.close()
        await refresh(['lists'])
      }
    },
  },
    h('h2', {}, existing ? 'Renommer la liste' : `Nouvelle liste pour ${profile.name}`),
    h('label', { for: 'list-name' }, 'Nom'), name,
    error,
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }), cancel, save))
  const dialog = openDialog(form, 'small')
  name.focus()
}
