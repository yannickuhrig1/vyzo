/**
 * Construction du DOM sans innerHTML : tout texte passe par des nœuds texte, un nom de profil ou de programme ne peut
 * donc jamais devenir du code. Le site manipulera plus tard des identifiants d'abonnement, la règle compte dès maintenant.
 */
type Child = Node | string | number | null | undefined | false
type Attrs = Record<string, string | number | boolean | EventListener | null | undefined>

export function h<K extends keyof HTMLElementTagNameMap>(tag: K, attrs: Attrs = {}, ...children: (Child | Child[])[]) {
  const el = document.createElement(tag)
  for (const [key, value] of Object.entries(attrs)) {
    if (value === null || value === undefined || value === false) continue
    if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2), value as EventListener)
    else if (key === 'class') el.className = String(value)
    else if (value === true) el.setAttribute(key, '')
    else el.setAttribute(key, String(value))
  }
  append(el, children)
  return el
}

export function append(parent: Node, children: (Child | Child[])[]) {
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue
    parent.appendChild(c instanceof Node ? c : document.createTextNode(String(c)))
  }
}

export function clear(el: Element) {
  while (el.firstChild) el.removeChild(el.firstChild)
}

const SVG = 'http://www.w3.org/2000/svg'

/** Icônes au trait (24×24), dessinées ici pour ne rien charger d'ailleurs. */
const ICONS: Record<string, string[]> = {
  users: ['M16 19v-1.5a3.5 3.5 0 0 0-3.5-3.5h-5A3.5 3.5 0 0 0 4 17.5V19', 'M10 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z', 'M20 19v-1.5a3.5 3.5 0 0 0-2.5-3.35', 'M15.5 4.15a3.5 3.5 0 0 1 0 6.7'],
  history: ['M3 12a9 9 0 1 0 3-6.7L3 8', 'M3 3v5h5', 'M12 7v5l3 2'],
  record: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z'],
  logout: ['M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3', 'M10 17l-5-5 5-5', 'M5 12h11'],
  plus: ['M12 5v14', 'M5 12h14'],
  edit: ['M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16v4z', 'M13.5 6.5l4 4'],
  up: ['M12 19V5', 'M6 11l6-6 6 6'],
  down: ['M12 5v14', 'M18 13l-6 6-6-6'],
  lock: ['M6 11h12v9H6z', 'M8 11V8a4 4 0 0 1 8 0v3'],
  child: ['M12 8a3 3 0 1 0 0-6 3 3 0 0 0 0 6z', 'M8 22v-6l-2-4h12l-2 4v6', 'M10 22v-4h4v4'],
  trash: ['M4 7h16', 'M10 11v6', 'M14 11v6', 'M6 7l1 13h10l1-13', 'M9 7V4h6v3'],
  x: ['M6 6l12 12', 'M18 6L6 18'],
  stop: ['M7 7h10v10H7z'],
  info: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 11v5', 'M12 8h.01'],
  tv: ['M3 6h18v11H3z', 'M8 21h8', 'M12 17v4'],
  phone: ['M7 2h10v20H7z', 'M11 18h2'],
  monitor: ['M3 4h18v12H3z', 'M8 20h8', 'M12 16v4'],
  devices: ['M2 5h14v10H2z', 'M5 19h8', 'M9 15v4', 'M17 9h5v11h-5z'],
  key: ['M8 20a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9z', 'M11.2 12.3L20 3.5', 'M17 6.5l2.5 2.5', 'M14.5 9l2 2'],
  list: ['M9 6h12', 'M9 12h12', 'M9 18h12', 'M4 6h.01', 'M4 12h.01', 'M4 18h.01'],
  film: ['M3 4h18v16H3z', 'M7 4v16', 'M17 4v16', 'M3 9h4', 'M3 15h4', 'M17 9h4', 'M17 15h4'],
  series: ['M4 7h16v13H4z', 'M8 3l4 4 4-4'],
  heart: ['M12 20s-7-4.4-9-9a5 5 0 0 1 9-3 5 5 0 0 1 9 3c-2 4.6-9 9-9 9z'],
  refresh: ['M20 11a8 8 0 0 0-14.9-3', 'M4 4v4h4', 'M4 13a8 8 0 0 0 14.9 3', 'M20 20v-4h-4'],
  eye: ['M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z', 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z'],
  eyeOff: ['M3 3l18 18', 'M10.6 5.1A10.7 10.7 0 0 1 12 5c6.4 0 10 7 10 7a17 17 0 0 1-3 3.9', 'M6.6 6.6C3.8 8.4 2 12 2 12s3.6 7 10 7a10 10 0 0 0 4.4-1', 'M9.9 9.9a3 3 0 0 0 4.2 4.2'],
  unlock: ['M6 11h12v9H6z', 'M8 11V8a4 4 0 0 1 7.5-2'],
  check: ['M5 12l5 5 9-10'],
}

export function icon(name: keyof typeof ICONS | string, size = 18): SVGSVGElement {
  const svg = document.createElementNS(SVG, 'svg')
  svg.setAttribute('viewBox', '0 0 24 24')
  svg.setAttribute('width', String(size))
  svg.setAttribute('height', String(size))
  svg.setAttribute('aria-hidden', 'true')
  svg.setAttribute('class', 'icon')
  for (const d of ICONS[name] ?? []) {
    const path = document.createElementNS(SVG, 'path')
    path.setAttribute('d', d)
    svg.appendChild(path)
  }
  return svg
}
