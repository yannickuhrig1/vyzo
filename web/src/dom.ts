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
  mail: ['M3 6h18v12H3z', 'M3 7l9 6 9-6'],
  home: ['M4 10.5L12 4l8 6.5V20H4z', 'M10 20v-6h4v6'],
  search: ['M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z', 'M20 20l-4-4'],
  shield: ['M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6z', 'M9 12l2 2 4-4'],
  arrow: ['M5 12h14', 'M13 6l6 6-6 6'],
  play: ['M8 5v14l11-7z'],
  cast: ['M3 8V6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6', 'M3 12a8 8 0 0 1 8 8', 'M3 16a4 4 0 0 1 4 4', 'M3 20h.01'],
  calendar: ['M4 6h16v14H4z', 'M4 10h16', 'M9 3v5', 'M15 3v5'],
  user: ['M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M4 21v-1a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v1'],
  playlist: ['M3 6h12', 'M3 12h12', 'M3 18h8', 'M17 18a2 2 0 1 0 0-.01', 'M19 18V7l3-1'],
  clock: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 7v5l3 2'],
  login: ['M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h3', 'M14 17l5-5-5-5', 'M19 12H8'],
  download: ['M12 4v11', 'M7 10l5 5 5-5', 'M5 20h14'],
  upload: ['M12 15V4', 'M7 9l5-5 5 5', 'M5 20h14'],
  message: ['M4 5h16v11H9l-5 4z'],
  pause: ['M8 5v14', 'M16 5v14'],
  chart: ['M4 20V10', 'M10 20V4', 'M16 20v-7', 'M22 20H2'],
  journal: ['M6 3h11l3 3v15H6z', 'M10 8h6', 'M10 12h6', 'M10 16h4', 'M3 7h3', 'M3 12h3', 'M3 17h3'],
  install: ['M12 3v11', 'M8 10l4 4 4-4', 'M4 15v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4'],
  pulse: ['M3 12h4l2-6 4 12 2-6h6'],
  sparkle: ['M12 3v4', 'M12 17v4', 'M3 12h4', 'M17 12h4', 'M6 6l2.5 2.5', 'M15.5 15.5L18 18', 'M18 6l-2.5 2.5', 'M8.5 15.5L6 18'],
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
