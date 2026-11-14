import { clear, h, icon } from './dom'
import { deviceName, isOnline, supportsRemote } from './model'
import { THEMES } from './theme'
import { fold, openDialog, relative } from './ui'
import { canInstallApp, go, installApp, isHidden, PAGES, setTheme, signOut, state } from './context'
import { DEVICE_ICON, hostOf, KIND_ICON, KIND_LABEL, TYPE_LABEL } from './shared'
import { backupDialog, lockNow, unlockDialog } from './vault-ui'
import { guideDialog } from './cast'
import { announceDialog } from './views/devices'
import { editPlaylist, editEpgDialog, setPlaylistsTab } from './views/playlists'
import { editProfile } from './views/profiles'

interface Command { group: string; label: string; hint?: string; icon: string; terms?: string; run: () => void }

function commands(): Command[] {
  const list: Command[] = PAGES.map((p) => ({ group: 'Pages', label: p.label, icon: p.icon, terms: p.lead ?? '', run: () => go(p.id) }))
  const open = state.vault === 'open'
  list.push(
    ...(open ? [
      { group: 'Actions', label: 'Ajouter un abonnement', icon: 'plus', terms: 'iptv xtream m3u stalker', run: () => { setPlaylistsTab('playlists'); go('abonnements'); editPlaylist(null) } },
      { group: 'Actions', label: 'Sources de guide TV (EPG)', icon: 'globe', terms: 'xmltv epg programmes guide', run: () => { setPlaylistsTab('epg'); go('abonnements') } },
      { group: 'Actions', label: 'Ajouter une source EPG', icon: 'plus', terms: 'xmltv guide tv url', run: () => { setPlaylistsTab('epg'); go('abonnements'); editEpgDialog(null) } },
      { group: 'Actions', label: 'Clés API et réglages partagés', icon: 'sliders', terms: 'tmdb omdb opensubtitles trakt github epg lecture', run: () => go('reglages') },
    ] : []),
    { group: 'Actions', label: 'Nouveau profil', icon: 'plus', run: () => { go('profils'); editProfile(null) } },
    ...(open
      ? [{ group: 'Actions', label: 'Verrouiller les données chiffrées', icon: 'lock', run: () => void lockNow() }]
      : state.vault === 'locked' ? [{ group: 'Actions', label: 'Déverrouiller les données chiffrées', icon: 'unlock', run: () => unlockDialog() }] : []),
    { group: 'Actions', label: 'Changer le mot de passe', icon: 'key', run: () => { go('securite'); setTimeout(() => document.getElementById('pw-current')?.focus(), 50) } },
    ...THEMES.map((t) => ({ group: 'Actions', label: `Thème ${t.label}`, hint: t.hint, icon: 'sparkle', terms: 'apparence couleur', run: () => setTheme(t.id) })),
    { group: 'Actions', label: 'Se déconnecter', icon: 'logout', run: () => void signOut() },
  )
  const onlineRemote = (state.devices ?? []).filter((d) => !isHidden(`d:${d.id}`) && isOnline(d) && supportsRemote(d))
  list.push(
    ...(onlineRemote.length > 0 ? [{ group: 'Actions', label: 'Envoyer une annonce maison', icon: 'message', terms: 'message tv ecran tous', run: () => announceDialog(onlineRemote) }] : []),
    { group: 'Actions', label: 'Programmer un enregistrement', icon: 'record', terms: 'guide tv programme enregistrer', run: () => { go('enregistrements'); guideDialog() } },
    ...(open ? [{ group: 'Actions', label: 'Télécharger une sauvegarde du compte', icon: 'download', terms: 'export backup', run: () => { go('securite'); backupDialog() } }] : []),
    ...(canInstallApp() ? [{ group: 'Actions', label: 'Installer le site', icon: 'install', terms: 'application bureau pwa', run: () => void installApp() }] : []),
  )
  for (const d of state.devices ?? []) {
    list.push({ group: 'Appareils', label: deviceName(d), hint: isOnline(d) ? 'En ligne' : `Vu ${relative(d.lastSeenAt)}`,
      icon: DEVICE_ICON[d.kind] ?? 'devices', terms: `${d.model} ${d.system} ${d.appVersion}`, run: () => go('appareils') })
  }
  for (const p of state.playlists ?? []) {
    list.push({ group: 'Abonnements', label: p.name, hint: TYPE_LABEL[p.type], icon: 'key', terms: hostOf(p.url),
      run: () => { go('abonnements'); editPlaylist(p) } })
  }
  for (const p of state.profiles ?? []) {
    list.push({ group: 'Profils', label: p.name, hint: p.isKid ? 'Enfant' : undefined, icon: p.isKid ? 'child' : 'users',
      run: () => { go('profils'); editProfile(p) } })
  }
  const owner = state.profiles?.find((p) => p.id === state.selected)?.name ?? ''
  const seen = new Set<string>()
  for (const x of [...(state.progress?.items ?? []), ...(state.lists?.items ?? [])]) {
    if (!x.title || seen.has(x.contentKey)) continue
    seen.add(x.contentKey)
    const inHistory = 'positionMs' in x
    list.push({ group: 'Titres', label: x.title, hint: `${KIND_LABEL[x.kind]}${owner ? ` · ${owner}` : ''}`, icon: KIND_ICON[x.kind],
      terms: x.subtitle, run: () => go(inHistory ? 'historique' : 'listes') })
  }
  return list
}

let paletteOpen = false

export function openPalette() {
  if (!state.account || paletteOpen) return
  paletteOpen = true
  const all = commands()
  const input = h('input', {
    type: 'text', placeholder: 'Rechercher une page, un appareil, un titre…', 'aria-label': 'Rechercher',
    role: 'combobox', 'aria-expanded': 'true', 'aria-controls': 'palette-list', autocomplete: 'off', spellcheck: 'false',
  })
  const listEl = h('ul', { class: 'palette-list', id: 'palette-list', role: 'listbox' })
  let shown: Command[] = []
  let index = 0

  const select = (i: number) => {
    index = i
    for (const li of listEl.querySelectorAll<HTMLElement>('.palette-item')) {
      const on = Number(li.dataset.i) === i
      li.setAttribute('aria-selected', String(on))
      if (on) {
        input.setAttribute('aria-activedescendant', li.id)
        li.scrollIntoView({ block: 'nearest' })
      }
    }
  }
  const run = (c: Command | undefined) => {
    if (!c) return
    dialog.close()
    c.run()
  }
  const render = () => {
    const tokens = fold(input.value).split(/\s+/).filter(Boolean)
    shown = tokens.length === 0
      ? all.filter((c) => c.group === 'Pages' || c.group === 'Actions')
      : all.filter((c) => {
        const hay = fold(`${c.label} ${c.hint ?? ''} ${c.terms ?? ''} ${c.group}`)
        return tokens.every((t) => hay.includes(t))
      }).slice(0, 50)
    clear(listEl)
    if (shown.length === 0) {
      listEl.appendChild(h('li', { class: 'palette-empty', role: 'presentation' }, 'Aucun résultat'))
      input.removeAttribute('aria-activedescendant')
      return
    }
    let group = ''
    shown.forEach((c, i) => {
      if (c.group !== group) listEl.appendChild(h('li', { class: 'palette-group', role: 'presentation' }, c.group))
      group = c.group
      listEl.appendChild(h('li', {
        class: 'palette-item', role: 'option', id: `palette-${i}`, 'data-i': i, 'aria-selected': 'false',
        onmousemove: () => { if (index !== i) select(i) },
        onclick: () => run(c),
      }, icon(c.icon, 16), h('span', { class: 'ellipsis' }, c.label), c.hint ? h('span', { class: 'hint' }, c.hint) : null))
    })
    select(0)
  }
  input.addEventListener('input', render)
  input.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); if (shown.length) select((index + 1) % shown.length) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); if (shown.length) select((index - 1 + shown.length) % shown.length) }
    else if (e.key === 'Enter') { e.preventDefault(); run(shown[index]) }
  })
  const dialog = openDialog(h('div', {},
    h('div', { class: 'palette-input' }, icon('search', 17), input),
    listEl,
    h('div', { class: 'palette-foot' },
      h('span', {}, h('kbd', {}, '↑'), h('kbd', {}, '↓'), 'naviguer'),
      h('span', {}, h('kbd', {}, '↵'), 'ouvrir'),
      h('span', {}, h('kbd', {}, 'Échap'), 'fermer'))), 'palette')
  dialog.addEventListener('close', () => { paletteOpen = false })
  const close = dialog.close.bind(dialog)
  dialog.close = (v?: string) => { paletteOpen = false; close(v) }
  render()
  input.focus()
}

export function installPaletteShortcut() {
  document.addEventListener('keydown', (e) => {
    if (!state.account) return
    const typing = e.target instanceof HTMLElement && (e.target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName))
    if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 'k') {
      e.preventDefault()
      openPalette()
    } else if (e.key === '/' && !typing && !document.querySelector('dialog[open]')) {
      e.preventDefault()
      openPalette()
    }
  })
}
