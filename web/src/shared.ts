import { friendlyError, type BackupData } from './backend'
import { clear, h, icon } from './dom'
import { type BackupCounts, type ContentKind, type Playlist, type Profile, type Tone } from './model'
import { savedTheme, THEMES } from './theme'
import { avatar, header, plural } from './ui'
import { ensureRouteData, page, refresh, renderView, setTheme, state, type DataKey, type Route } from './context'

export const appRoot = () => document.getElementById('app')!
export const isMac = /Mac|iPhone|iPad/.test(navigator.platform)

export const DEVICE_ICON: Record<string, string> = { tv: 'tv', mobile: 'phone', pc: 'monitor' }
export const KIND_ICON: Record<ContentKind, string> = { LIVE: 'tv', MOVIE: 'film', SERIES: 'series' }
export const KIND_LABEL: Record<ContentKind, string> = { LIVE: 'Chaîne', MOVIE: 'Film', SERIES: 'Série' }
export const TYPE_LABEL: Record<Playlist['type'], string> = { XTREAM: 'Xtream Codes', M3U: 'Liste M3U', STALKER: 'Portail Stalker' }
export const TONE_PILL: Record<Tone, string> = { ok: 'ok', warn: 'planned', bad: 'bad' }

export function hostOf(url: string): string {
  const raw = url.trim()
  try {
    return new URL(/^[a-z][a-z0-9+.-]*:\/\//i.test(raw) ? raw : `http://${raw}`).host
  } catch {
    return raw
  }
}

/** 1:05:09 ou 12:40. */
export function clock(ms: number): string {
  const t = Math.max(0, Math.floor(ms / 1000))
  const hh = Math.floor(t / 3600)
  const mm = Math.floor((t % 3600) / 60)
  const ss = t % 60
  const two = (n: number) => String(n).padStart(2, '0')
  return hh ? `${hh}:${two(mm)}:${two(ss)}` : `${mm}:${two(ss)}`
}

export function countsText(c: BackupCounts): string {
  return [plural(c.profiles, 'profil', 'profils'), plural(c.lists + c.items, 'élément de liste', 'éléments de listes'),
    plural(c.progress, 'titre commencé', 'titres commencés'), plural(c.sealed, 'réglage chiffré', 'réglages chiffrés')].join(', ')
}

export const backupCountsOf = (d: BackupData): BackupCounts => ({
  profiles: d.profiles.length, lists: d.lists.length, items: d.items.length, progress: d.progress.length,
  recordings: d.recordings.length, sealed: d.sealed.length,
})

export function themeSwitch() {
  const current = savedTheme()
  return h('div', { class: 'theme-switch', role: 'group', 'aria-label': 'Thème' },
    THEMES.map((t) => h('button', {
      type: 'button', 'data-theme-choice': t.id, 'aria-pressed': String(t.id === current), title: t.hint,
      onclick: () => setTheme(t.id),
    }, t.label)))
}

export function pageHeader(r: Route, ...actions: (Node | null)[]) {
  const p = page(r)
  return header(r === 'abonnements' ? 'Abonnements IPTV' : p.label, p.lead, ...actions)
}

export function errorBanner(key: DataKey) {
  const message = state.errors[key]
  if (!message) return null
  return h('div', { class: 'banner error', role: 'alert' }, icon('info'), h('span', {}, message),
    h('button', { class: 'btn ghost small', type: 'button', onclick: () => void refresh([key]) }, 'Réessayer'))
}

export function sectionTitle(text: string, iconName: string | null, count: number | null, link?: { href: string; label: string }) {
  return h('h2', { class: 'section-title' }, iconName ? icon(iconName, 15) : null, text,
    count !== null ? h('span', { class: 'count' }, String(count)) : null,
    link ? h('a', { href: link.href }, link.label, ' →') : null)
}

export function selectProfile(id: string) {
  if (state.selected === id) return
  state.selected = id
  state.progress = null
  state.lists = null
  renderView()
  ensureRouteData()
}

/** Choix du profil dans Accueil, Historique et Listes. */
export function profilePicker(count: (p: Profile) => number | null) {
  const profiles = state.profiles ?? []
  if (profiles.length < 2) return null
  return h('div', { class: 'picker', role: 'tablist', 'aria-label': 'Profil' },
    profiles.map((p) => {
      const n = count(p)
      return h('button', {
        type: 'button', class: 'chip', role: 'tab', 'aria-selected': String(p.id === state.selected),
        onclick: () => selectProfile(p.id),
      }, avatar(p, 'xs'), h('span', {}, p.name), n !== null ? h('span', { class: 'chip-count' }, String(n)) : null)
    }))
}

/** Titre affiché : celui que l'appli a chiffré sur le compte, sinon une indication honnête. */
export function contentTitle(x: { title: string; kind: ContentKind }): { text: string; known: boolean } {
  if (x.title) return { text: x.title, known: true }
  const why = state.vault === 'open' ? 'titre pas encore connu' : 'titre masqué'
  return { text: `${KIND_LABEL[x.kind] ?? 'Contenu'} · ${why}`, known: false }
}

export function kindBadge(kind: ContentKind) {
  return h('span', { class: `kind-badge k-${kind.toLowerCase()}`, title: KIND_LABEL[kind] }, icon(KIND_ICON[kind] ?? 'film', 16))
}

export function panel(title: string, lead: string | null, body: (Node | null)[], foot?: (Node | string | null)[], danger = false) {
  return h('section', { class: `panel ${danger ? 'danger-zone' : ''}` },
    h('div', { class: 'panel-head' }, h('h2', {}, title), lead ? h('p', {}, lead) : null),
    h('div', { class: 'panel-body' }, body),
    foot ? h('div', { class: 'panel-foot' }, foot) : null)
}

/** Case « Rester déverrouillé sur cet ordinateur », décochée par défaut. */
export function rememberBox(id: string) {
  const input = h('input', { id, type: 'checkbox' })
  const box = h('label', { class: 'check compact', for: id }, input, h('span', {},
    h('strong', {}, 'Rester déverrouillé sur cet ordinateur'),
    h('small', {}, 'Plus besoin du mot de passe à chaque visite. À éviter sur un ordinateur partagé : tes abonnements et ton historique y resteraient lisibles. « Verrouiller » ou la déconnexion l’oublient.')))
  return { input, box }
}

/** Champ mot de passe avec bouton d'affichage / masquage sans état partagé. */
export function passwordWithReveal(inputEl: HTMLInputElement) {
  const reveal: HTMLButtonElement = h('button', {
    class: 'icon-btn field-btn', type: 'button', 'aria-label': 'Afficher le mot de passe', 'aria-pressed': 'false',
    onclick: () => {
      const shown = inputEl.type === 'text'
      inputEl.type = shown ? 'password' : 'text'
      reveal.setAttribute('aria-pressed', String(!shown))
      reveal.setAttribute('aria-label', shown ? 'Afficher le mot de passe' : 'Masquer le mot de passe')
      clear(reveal)
      reveal.appendChild(icon(shown ? 'eye' : 'eyeOff', 15))
    },
  }, icon('eye', 15))
  return h('div', { class: 'with-btn' }, inputEl, reveal)
}

/** Bouton qui ressemble à un lien (action secondaire sous un formulaire). */
export const linkButton = (label: string, onclick: () => void) => h('button', { class: 'link-btn', type: 'button', onclick }, label)

export type KindFilter = 'ALL' | ContentKind

/** Barre recherche + filtres par type (chaînes, films, séries) pour Historique et Listes. */
export function contentFilterBar(opts: {
  id: string
  placeholder: string
  query: string
  kind: KindFilter
  onQuery: (q: string) => void
  onKind: (k: KindFilter) => void
  extra?: Node | null
}) {
  const input = h('input', {
    id: opts.id, type: 'search', placeholder: opts.placeholder, value: opts.query,
    autocomplete: 'off', spellcheck: 'false', 'aria-label': opts.placeholder,
  })
  input.addEventListener('input', () => opts.onQuery(input.value))
  const kinds: [KindFilter, string][] = [['ALL', 'Tous'], ['MOVIE', 'Films'], ['SERIES', 'Séries'], ['LIVE', 'Chaînes']]
  const chips = h('div', { class: 'chips', role: 'group', 'aria-label': 'Filtrer par type' },
    kinds.map(([k, label]) => {
      const b = h('button', {
        type: 'button', class: 'chip', 'aria-pressed': String(opts.kind === k),
        onclick: () => {
          opts.onKind(k)
          for (const item of chips.querySelectorAll('.chip')) item.setAttribute('aria-pressed', 'false')
          b.setAttribute('aria-pressed', 'true')
        },
      }, label)
      return b
    }))
  return h('div', { class: 'filter-bar' },
    h('div', { class: 'filter-search' }, icon('search', 15), input),
    chips,
    opts.extra ?? null)
}

/** Envoi pendant lequel le bouton principal est occupé ; renvoie false et affiche l'erreur en cas d'échec. */
export async function busy(button: HTMLButtonElement, error: HTMLElement, work: () => Promise<void>): Promise<boolean> {
  error.textContent = ''
  button.disabled = true
  button.setAttribute('aria-busy', 'true')
  try {
    await work()
    return true
  } catch (e) {
    error.textContent = friendlyError(e)
    return false
  } finally {
    button.disabled = false
    button.removeAttribute('aria-busy')
  }
}
