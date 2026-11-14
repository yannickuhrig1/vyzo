/** Briques d'interface sans état : formats, notifications, boîtes de dialogue, petits composants. */
import { avatarSvg } from './avatars'
import { friendlyError } from './backend'
import { append, h, icon } from './dom'
import { cleanAvatar, profileColor, type Profile } from './model'

// ------------------------------------------------------------------ Formats

const dayFmt = new Intl.DateTimeFormat('fr-FR', { weekday: 'short', day: 'numeric', month: 'short' })
export const timeFmt = new Intl.DateTimeFormat('fr-FR', { hour: '2-digit', minute: '2-digit' })
export const fullFmt = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'full', timeStyle: 'short' })
export const longDayFmt = new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' })
export const dateFmt = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })
const relFmt = new Intl.RelativeTimeFormat('fr-FR', { numeric: 'auto' })

export function relative(ms: number): string {
  const diff = ms - Date.now()
  const abs = Math.abs(diff)
  if (abs < 60_000) return 'à l’instant'
  if (abs < 3600_000) return relFmt.format(Math.round(diff / 60_000), 'minute')
  if (abs < 86400_000) return relFmt.format(Math.round(diff / 3600_000), 'hour')
  if (abs < 30 * 86400_000) return relFmt.format(Math.round(diff / 86400_000), 'day')
  return dayFmt.format(ms)
}

export function dayLabel(ms: number): string {
  const startOf = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const days = Math.round((startOf(new Date(ms)) - startOf(new Date())) / 86400_000)
  if (days === 0) return 'Aujourd’hui'
  if (days === 1) return 'Demain'
  if (days === -1) return 'Hier'
  return dayFmt.format(ms)
}

export function duration(ms: number): string {
  const min = Math.max(1, Math.round(ms / 60_000))
  if (min < 60) return `${min} min`
  const hours = Math.floor(min / 60)
  const rest = min % 60
  return rest ? `${hours} h ${String(rest).padStart(2, '0')}` : `${hours} h`
}

export const plural = (n: number, one: string, many: string) => `${n} ${n > 1 ? many : one}`

// ------------------------------------------------------------------ Notifications

const toasts = h('div', { class: 'toasts', role: 'status', 'aria-live': 'polite' })
document.body.appendChild(toasts)

export interface ToastAction { label: string; run: () => void }

/** Message bref. Avec [action] (« Annuler »), il reste [ms] millisecondes avec une barre de temps. */
export function toast(message: string, kind: 'ok' | 'error' = 'ok', action?: ToastAction, ms?: number): () => void {
  const life = ms ?? (kind === 'error' ? 6000 : 3200)
  const el = h('div', { class: `toast ${kind}` }, h('span', {}, message))
  const dismiss = () => {
    el.classList.add('out')
    setTimeout(() => el.remove(), 260)
  }
  if (action) {
    append(el, [h('button', { class: 'btn small', type: 'button', onclick: () => { dismiss(); action.run() } }, action.label)])
    const timer = h('span', { class: 'toast-timer' })
    timer.style.animationDuration = `${life}ms`
    el.appendChild(timer)
  }
  toasts.appendChild(el)
  setTimeout(dismiss, life)
  return dismiss
}

/** Action d'écriture : bouton occupé pendant l'envoi, message à la fin. */
export async function act(button: HTMLButtonElement | null, work: () => Promise<void>, done?: string): Promise<boolean> {
  button?.setAttribute('aria-busy', 'true')
  if (button) button.disabled = true
  try {
    await work()
    if (done) toast(done)
    return true
  } catch (e) {
    toast(friendlyError(e), 'error')
    return false
  } finally {
    button?.removeAttribute('aria-busy')
    if (button) button.disabled = false
  }
}

// ------------------------------------------------------------------ Boîtes de dialogue

export function openDialog(content: HTMLElement, kind: '' | 'small' | 'wide' | 'palette' | 'sheet' = ''): HTMLDialogElement {
  const dialog = h('dialog', { class: `dialog ${kind}` }, content)
  // Retirée tout de suite, sans attendre l'événement « close » (différé, voire suspendu dans un onglet caché).
  const close = dialog.close.bind(dialog)
  dialog.close = (value?: string) => { close(value); dialog.remove() }
  dialog.addEventListener('close', () => dialog.remove())
  dialog.addEventListener('click', (e) => { if (e.target === dialog) dialog.close() })
  document.body.appendChild(dialog)
  dialog.showModal()
  return dialog
}

export function confirmDialog(o: { title: string; body: string; confirm: string; cancel?: string; danger?: boolean }): Promise<boolean> {
  return new Promise((resolve) => {
    const answer = (value: boolean) => { resolve(value); dialog.close() }
    const yes = h('button', { class: `btn ${o.danger ? 'danger' : 'primary'}`, type: 'button', onclick: () => answer(true) }, o.confirm)
    const no = h('button', { class: 'btn', type: 'button', onclick: () => answer(false) }, o.cancel ?? 'Annuler')
    const dialog = openDialog(h('div', { class: 'confirm' },
      h('h2', {}, o.title), h('p', {}, o.body),
      h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }), no, yes)), 'small')
    dialog.addEventListener('close', () => resolve(false))
    no.focus()
  })
}

// ------------------------------------------------------------------ Composants

export function header(title: string, lead: string | null, ...actions: (Node | null)[]) {
  return h('header', { class: 'view-head' },
    h('div', {}, h('h1', {}, title), lead ? h('p', { class: 'lead' }, lead) : null),
    h('div', { class: 'head-actions' }, actions))
}

export function loading(rows = 3) {
  return h('div', { class: 'skeleton-list', 'aria-label': 'Chargement' },
    Array.from({ length: rows }, () => h('div', { class: 'skeleton' })))
}

export function emptyCard(iconName: string, title: string, text: string, ...extra: (Node | null)[]) {
  return h('div', { class: 'empty-card' }, icon(iconName, 26), h('h2', {}, title), h('p', {}, text), extra)
}

export function avatar(p: Pick<Profile, 'name' | 'colorIndex' | 'isKid' | 'avatar'>, size: 'lg' | 'sm' | 'xs' = 'lg') {
  const px = size === 'lg' ? 30 : size === 'sm' ? 17 : 11
  const svg = avatarSvg(cleanAvatar(p.avatar), px)
  const initial = Array.from(p.name.trim())[0]?.toUpperCase() ?? '?'
  const el = h('span', { class: `avatar ${size}`, 'aria-hidden': 'true' }, svg ?? initial,
    p.isKid && size !== 'xs' ? h('span', { class: 'kid-dot' }, icon('child', size === 'lg' ? 12 : 9)) : null)
  el.style.setProperty('--c', profileColor(p.colorIndex))
  return el
}

/** Largeur posée par le CSSOM : la politique de sécurité du site refuse les attributs style. */
export function barFill(ratio: number) {
  const el = h('span', {})
  el.style.width = `${Math.round(Math.min(1, Math.max(0, ratio)) * 100)}%`
  return el
}

/** Texte sans accents ni majuscules, pour la recherche. */
export function fold(text: string): string {
  return text.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase()
}
