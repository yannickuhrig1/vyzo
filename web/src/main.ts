import '@fontsource-variable/instrument-sans'
import '@fontsource/instrument-serif/400.css'
import '@fontsource-variable/jetbrains-mono'
import './style.css'
import { append, clear, h, icon } from './dom'
import { needsUpdate, parseEmailLink, subscriptionState, updateInProgress } from './model'
import { friendlyError } from './backend'
import { applyTheme, savedTheme } from './theme'
import { openDialog, plural, toast } from './ui'
import {
  backend, ensureRouteData, enter, go, page, PAGES, realtimeStatus, registerShellHandlers, route, signOut, state,
  type Route,
} from './context'
import { appRoot, isMac, themeSwitch } from './shared'
import { lockNow, unlockDialog } from './vault-ui'
import { openEmailLink, renderLogin } from './auth'
import { installPaletteShortcut, openPalette } from './palette'
import { homeView } from './views/home'
import { devicesView } from './views/devices'
import { playlistsView } from './views/playlists'
import { recordingsView } from './views/recordings'
import { profilesView } from './views/profiles'
import { historyView } from './views/history'
import { listsView } from './views/lists'
import { statsView } from './views/stats'
import { parentalView } from './views/parental'
import { settingsView } from './views/settings'
import { securityView } from './views/security'
import { journalView } from './views/journal'

// ------------------------------------------------------------------ Cadre

/**
 * Refus d'être affiché dans le cadre d'une autre page : sinon un site piégé pourrait faire cliquer, à travers un
 * calque, « Supprimer » ou « Mettre à jour ». L'hébergement (GitHub Pages) ne permet pas d'envoyer frame-ancestors.
 */
if (window.top !== window.self) {
  document.body.textContent = 'Ouvre le site du compte Vyzo directement, pas dans une autre page.'
  throw new Error('Affichage dans un cadre refusé')
}

applyTheme(savedTheme())
installPaletteShortcut()

// ------------------------------------------------------------------ Coquille

const MOBILE_PRIMARY: Route[] = ['accueil', 'appareils', 'abonnements', 'profils']

let viewEl: HTMLElement | null = null
let navEl: HTMLElement | null = null
let mobileNavEl: HTMLElement | null = null
let whoEl: HTMLElement | null = null
let mobileVaultEl: HTMLElement | null = null

function navBadges() {
  const outdated = (state.devices ?? []).filter((d) => needsUpdate(d, state.latest) && !updateInProgress(d)).length
  const expiring = state.vault === 'open'
    ? (state.playlists ?? []).filter((p) => {
      const st = subscriptionState(p.status)
      return !!st && st.tone !== 'ok'
    }).length
    : 0
  return { outdated, expiring }
}

function badgeFor(r: Route, { outdated, expiring }: { outdated: number; expiring: number }): HTMLElement | null {
  if (r === 'appareils' && outdated > 0) {
    return h('span', { class: 'nav-badge', title: `${plural(outdated, 'appareil', 'appareils')} à mettre à jour` }, String(outdated))
  }
  if (r === 'abonnements' && expiring > 0) {
    return h('span', { class: 'nav-badge warn', title: `${plural(expiring, 'abonnement', 'abonnements')} bientôt expiré ou expiré` }, '!')
  }
  return null
}

function renderShell() {
  const root = appRoot()
  clear(root)
  navEl = h('nav', { class: 'nav', 'aria-label': 'Sections' })
  mobileNavEl = h('nav', { class: 'mobile-bar', 'aria-label': 'Navigation rapide' })
  whoEl = h('div', { class: 'who' })
  mobileVaultEl = h('div', { class: 'mobile-status' })
  viewEl = h('main', { class: 'view', id: 'view', tabindex: '-1' })
  append(root, [
    h('div', { class: 'shell' },
      h('aside', { class: 'side' },
        h('a', { class: 'logo', href: '#/accueil' },
          h('img', { src: 'icon.svg', alt: '', width: 24, height: 24 }),
          h('span', { class: 'logo-name' }, 'Vyzo'), h('span', { class: 'logo-sub' }, 'Compte'),
          backend.demo ? h('span', { class: 'demo-tag' }, 'démo') : null),
        mobileVaultEl,
        h('button', { class: 'search-trigger', type: 'button', 'aria-label': 'Rechercher', onclick: () => openPalette() },
          icon('search', 15), h('span', {}, 'Rechercher…'), h('kbd', {}, isMac ? '⌘K' : 'Ctrl K')),
        navEl,
        h('div', { class: 'side-foot' },
          whoEl,
          themeSwitch(),
          h('button', { class: 'btn ghost small', type: 'button', onclick: () => void signOut() }, icon('logout', 15), 'Déconnexion')),
      ),
      viewEl,
      mobileNavEl,
    ),
  ])
  renderView(true)
}

function renderNav(current: Route) {
  const badges = navBadges()
  if (navEl) {
    clear(navEl)
    let group: string | null = null
    for (const p of PAGES) {
      if (p.group && p.group !== group) navEl.appendChild(h('div', { class: 'nav-group' }, p.group))
      group = p.group
      navEl.appendChild(h('a', { href: `#/${p.id}`, class: 'nav-item', 'aria-current': p.id === current ? 'page' : null },
        icon(p.icon, 16), h('span', {}, p.label), badgeFor(p.id, badges)))
    }
  }
  if (mobileNavEl) {
    clear(mobileNavEl)
    const inMore = !MOBILE_PRIMARY.includes(current)
    for (const id of MOBILE_PRIMARY) {
      const p = page(id)
      mobileNavEl.appendChild(h('a', { href: `#/${p.id}`, class: 'mobile-tab', 'aria-current': current === p.id ? 'page' : null },
        h('span', { class: 'mobile-tab-icon' }, icon(p.icon, 18), badgeFor(p.id, badges)),
        h('span', {}, p.id === 'abonnements' ? 'Abo' : p.label)))
    }
    mobileNavEl.appendChild(h('button', {
      type: 'button', class: 'mobile-tab', 'aria-pressed': String(inMore), onclick: () => openMoreSheet(current),
    }, h('span', { class: 'mobile-tab-icon' }, icon('menu', 18)), h('span', {}, 'Plus')))
  }
}

function openMoreSheet(current: Route) {
  const email = state.account?.email ?? ''
  const secondary = PAGES.filter((p) => !MOBILE_PRIMARY.includes(p.id))
  const list = h('div', { class: 'sheet-groups' })
  let group: string | null = null
  let ul: HTMLElement | null = null
  for (const p of secondary) {
    if (p.group && p.group !== group) {
      group = p.group
      list.appendChild(h('div', { class: 'nav-group' }, p.group))
      ul = h('div', { class: 'sheet-grid' })
      list.appendChild(ul)
    }
    const item = h('button', {
      type: 'button', class: 'sheet-item', 'aria-current': p.id === current ? 'page' : null,
      onclick: () => { dialog.close(); go(p.id) },
    }, icon(p.icon, 17), h('span', {}, p.label))
    ;(ul ?? list).appendChild(item)
  }
  const rtLabel = realtimeStatus === 'connected' ? 'Synchro temps réel active'
    : realtimeStatus === 'connecting' ? 'Connexion temps réel…' : 'Synchro hors ligne'
  const dialog = openDialog(h('div', { class: 'more-sheet' },
    h('div', { class: 'sheet-head' },
      h('div', {}, h('h2', {}, 'Navigation'), email ? h('p', { class: 'dialog-sub' }, email) : null),
      h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Fermer', onclick: () => dialog.close() }, icon('x', 16))),
    list,
    h('div', { class: 'sheet-foot' },
      h('div', { class: 'sheet-theme' },
        h('span', { class: 'field-label' }, 'Apparence'),
        themeSwitch()),
      h('div', { class: 'sheet-meta' },
        h('span', { class: `rt-pill ${realtimeStatus}` }, h('span', { class: 'rt-dot', 'aria-hidden': 'true' }), rtLabel),
        h('button', { class: 'btn danger-ghost small', type: 'button', onclick: () => { dialog.close(); void signOut() } }, icon('logout', 14), 'Déconnexion'))),
  ), 'sheet')
}

function vaultActionButton(compact: boolean) {
  const open = state.vault === 'open'
  const locked = state.vault === 'locked'
  const label = open ? (state.remembered ? 'Déverrouillé, gardé ici' : 'Déverrouillé')
    : locked ? 'Verrouillé' : state.vault === 'missing' ? 'Rien de chiffré' : '…'
  const short = open ? 'Coffre ouvert' : locked ? 'Déverrouiller' : state.vault === 'missing' ? 'Sans coffre' : '…'
  const cls = `vault-pill ${open ? 'open' : locked ? 'locked' : ''}`
  const onClick = open ? () => void lockNow() : locked ? () => unlockDialog() : () => go('abonnements')
  const title = open ? 'Cliquer pour verrouiller les données chiffrées'
    : locked ? 'Cliquer pour déverrouiller avec ton mot de passe' : 'Voir l’état du chiffrement'
  return h('button', { type: 'button', class: cls, title, onclick: onClick },
    icon(open ? 'unlock' : 'lock', 11),
    h('span', {}, compact ? short : label))
}

function renderWho() {
  if (!state.account) return
  const email = state.account.email
  const rtText = realtimeStatus === 'connected' ? 'Temps réel actif'
    : realtimeStatus === 'connecting' ? 'Connexion temps réel…' : 'Temps réel interrompu'
  if (whoEl) {
    clear(whoEl)
    append(whoEl, [
      h('span', { class: 'who-mark', 'aria-hidden': 'true' },
        (Array.from(email)[0] ?? '?').toUpperCase(),
        h('span', { class: `who-rt ${realtimeStatus}`, title: rtText })),
      h('div', { class: 'who-text' },
        h('div', { class: 'who-mail', title: email }, email),
        vaultActionButton(false)),
    ])
  }
  if (mobileVaultEl) {
    clear(mobileVaultEl)
    append(mobileVaultEl, [
      h('span', { class: `rt-dot standalone ${realtimeStatus}`, title: rtText, 'aria-label': rtText }),
      vaultActionButton(true),
    ])
  }
}

const VIEWS: Record<Route, () => HTMLElement> = {
  statistiques: statsView,
  journal: journalView,
  accueil: homeView,
  appareils: devicesView,
  abonnements: playlistsView,
  enregistrements: recordingsView,
  profils: profilesView,
  historique: historyView,
  listes: listsView,
  parental: parentalView,
  reglages: settingsView,
  securite: securityView,
}

function renderView(animate = false) {
  if (!viewEl || !state.account) return
  const r = route()
  renderNav(r)
  renderWho()
  document.title = r === 'accueil' ? 'Vyzo · Compte' : `${page(r).label} · Vyzo`
  const scroll = window.scrollY
  const active = document.activeElement
  const focusId = active instanceof HTMLElement && viewEl.contains(active) ? active.id : ''
  const selection = (active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement) && viewEl.contains(active) && active.id
    ? { start: active.selectionStart, end: active.selectionEnd } : null
  const openDetails = [...viewEl.querySelectorAll('details')]
    .map((d, i) => (d.open ? i : -1)).filter((i) => i >= 0)

  if (animate) {
    viewEl.classList.remove('animate-enter')
    void viewEl.offsetWidth
    viewEl.classList.add('animate-enter')
  } else {
    viewEl.classList.remove('animate-enter')
  }

  clear(viewEl)
  append(viewEl, [VIEWS[r]()])

  if (!animate && openDetails.length > 0) {
    const details = viewEl.querySelectorAll('details')
    for (const i of openDetails) if (details[i]) details[i].open = true
  }
  window.scrollTo({ top: scroll })
  if (focusId) {
    const target = document.getElementById(focusId)
    if (target) {
      target.focus({ preventScroll: true })
      if (selection && (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement)) {
        try { target.setSelectionRange(selection.start, selection.end) } catch { /* type incompatible */ }
      }
    }
  }
}

registerShellHandlers({ renderView, renderWho, renderShell, renderLogin })

window.addEventListener('hashchange', () => {
  window.scrollTo({ top: 0 })
  renderView(true)
  ensureRouteData()
  viewEl?.focus({ preventScroll: true })
})

// La page publiée s'ouvre aussi hors ligne une fois installée (le service worker ne touche pas aux données du compte).
if (import.meta.env.PROD && 'serviceWorker' in navigator && !backend.demo) {
  window.addEventListener('load', () => {
    const hadController = !!navigator.serviceWorker.controller
    void navigator.serviceWorker.register('sw.js').catch(() => undefined)
    if (hadController) {
      navigator.serviceWorker.addEventListener('controllerchange', () => {
        toast('Nouvelle version du site prête', 'ok', { label: 'Recharger', run: () => location.reload() }, 12_000)
      }, { once: true })
    }
  })
}

// ------------------------------------------------------------------ Démarrage

async function start() {
  // Lien reçu par e-mail : ses paramètres sont retirés de l'adresse (historique, favoris) avant tout.
  const link = parseEmailLink(location.search, location.hash)
  if (link) {
    history.replaceState(null, '', location.pathname + (backend.demo ? '?demo' : ''))
    await openEmailLink(link)
    return
  }
  try {
    const account = await backend.currentAccount()
    if (account) await enter(account)
    else renderLogin()
  } catch (e) {
    renderLogin(friendlyError(e))
  }
}

void start()
