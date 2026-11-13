/** Les trois thèmes du site. Le choix reste dans ce navigateur (préférence d'affichage, rien de sensible). */

export type Theme = 'sobre' | 'cinema' | 'clair'

export const THEMES: { id: Theme; label: string; hint: string }[] = [
  { id: 'sobre', label: 'Sobre', hint: 'Noir neutre, précis' },
  { id: 'cinema', label: 'Cinéma', hint: 'Noir chaud, titres en serif' },
  { id: 'clair', label: 'Clair', hint: 'Fond clair' },
]

const KEY = 'vyzo-theme'
const BAR_COLOR: Record<Theme, string> = { sobre: '#0a0a0b', cinema: '#070605', clair: '#f6f6f3' }

export function savedTheme(): Theme {
  try {
    const t = localStorage.getItem(KEY)
    if (t === 'sobre' || t === 'cinema' || t === 'clair') return t
  } catch {
    // Stockage refusé : thème par défaut.
  }
  return 'sobre'
}

export function applyTheme(theme: Theme, persist = false) {
  document.documentElement.dataset.theme = theme
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', BAR_COLOR[theme])
  document.querySelector('meta[name="color-scheme"]')?.setAttribute('content', theme === 'clair' ? 'light' : 'dark')
  if (!persist) return
  try {
    localStorage.setItem(KEY, theme)
  } catch {
    // Choix valable pour cette visite seulement.
  }
}
