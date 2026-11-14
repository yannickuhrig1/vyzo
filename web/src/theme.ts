/** Les six thèmes du site. Le choix reste dans ce navigateur (préférence d'affichage, rien de sensible). */

export type Theme = 'sobre' | 'cinema' | 'clair' | 'nuit' | 'oled' | 'sable'

export const THEMES: { id: Theme; label: string; hint: string; light?: boolean }[] = [
  { id: 'sobre', label: 'Sobre', hint: 'Noir neutre, précis' },
  { id: 'cinema', label: 'Cinéma', hint: 'Noir chaud, titres en serif' },
  { id: 'clair', label: 'Clair', hint: 'Papier blanc net', light: true },
  { id: 'nuit', label: 'Nuit TV', hint: 'Bleu nuit de l’appli TV' },
  { id: 'oled', label: 'OLED', hint: 'Noir pur, accent cyan' },
  { id: 'sable', label: 'Sable', hint: 'Lin chaud, titres en serif', light: true },
]

const KEY = 'vyzo-theme'
const VALID = new Set<string>(THEMES.map((t) => t.id))
const BAR_COLOR: Record<Theme, string> = {
  sobre: '#0a0a0b',
  cinema: '#070605',
  clair: '#f6f6f3',
  nuit: '#0a0e1a',
  oled: '#000000',
  sable: '#f2ece1',
}

export function savedTheme(): Theme {
  try {
    const t = localStorage.getItem(KEY)
    if (t && VALID.has(t)) return t as Theme
  } catch {
    // Stockage refusé : thème par défaut.
  }
  return 'sobre'
}

export function applyTheme(theme: Theme, persist = false) {
  document.documentElement.dataset.theme = theme
  const light = theme === 'clair' || theme === 'sable'
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', BAR_COLOR[theme])
  document.querySelector('meta[name="color-scheme"]')?.setAttribute('content', light ? 'light' : 'dark')
  if (!persist) return
  try {
    localStorage.setItem(KEY, theme)
  } catch {
    // Choix valable pour cette visite seulement.
  }
}
