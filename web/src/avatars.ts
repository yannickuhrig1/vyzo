/**
 * Catalogue d'avatars vectoriels partagé avec l'appli (ProfileAvatars.kt).
 * Construit en DOM SVG pur (zéro innerHTML, zéro ressource externe).
 */

const SVG_NS = 'http://www.w3.org/2000/svg'

export interface AvatarChoice {
  id: string
  label: string
}

interface AvatarSpec {
  id: string
  label: string
  paths: readonly string[]
}

const SPECS: readonly AvatarSpec[] = [
  { id: 'popcorn', label: 'Ciné', paths: ['M6 10h12l-1.5 10h-9z', 'M7 10a3 3 0 0 1 2.5-4.6A3 3 0 0 1 14.5 5 3 3 0 0 1 17 10', 'M10 10v10', 'M14 10v10'] },
  { id: 'clapper', label: 'Clap', paths: ['M4 9h16v11H4z', 'M4 9l2-5h12l2 5', 'M9 4l2 5', 'M14 4l2 5'] },
  { id: 'headphones', label: 'Audio', paths: ['M5 13a7 7 0 0 1 14 0', 'M5 13v4a2 2 0 0 0 2 2h1v-6H5z', 'M19 13v4a2 2 0 0 1-2 2h-1v-6h3z'] },
  { id: 'gamepad', label: 'Jeux', paths: ['M6 10h12a3 3 0 0 1 2.8 4l-1 3.5a2 2 0 0 1-3.5.7L14 16h-4l-2.3 2.2a2 2 0 0 1-3.5-.7l-1-3.5A3 3 0 0 1 6 10z', 'M8 13h2', 'M9 12v2', 'M15.5 12.5h.01', 'M17 14h.01'] },
  { id: 'ticket', label: 'Séance', paths: ['M4 8h16v3a1.5 1.5 0 0 0 0 3v3H4v-3a1.5 1.5 0 0 0 0-3z', 'M10 8v9', 'M14 11.5h3', 'M14 13.5h2'] },
  { id: 'cat', label: 'Chat', paths: ['M5 9l1.5-5 3.5 2.5h4L17.5 4 19 9a6.5 6.5 0 0 1-14 0z', 'M9.5 11.5h.01', 'M14.5 11.5h.01', 'M10.5 14.5l1.5 1 1.5-1'] },
  { id: 'fox', label: 'Renard', paths: ['M4 5l4 4h8l4-4-1 8-7 6-7-6z', 'M9.5 11.5h.01', 'M14.5 11.5h.01', 'M11 16h2'] },
  { id: 'bear', label: 'Ours', paths: ['M5 8a2.5 2.5 0 1 1 3.5-2.3', 'M15.5 5.7A2.5 2.5 0 1 1 19 8', 'M6 13a6 6 0 1 1 12 0 6 6 0 0 1-12 0z', 'M10 12h.01', 'M14 12h.01', 'M10.5 15h3'] },
  { id: 'owl', label: 'Hibou', paths: ['M6 7l3 2h6l3-2 1 6a7 7 0 0 1-14 0z', 'M9.5 12a1.5 1.5 0 1 0 0 .01', 'M14.5 12a1.5 1.5 0 1 0 0 .01', 'M12 14l-1 1.5h2z'] },
  { id: 'astronaut', label: 'Espace', paths: ['M6 10a6 6 0 1 1 12 0v3a6 6 0 0 1-12 0z', 'M8 10h8v3a4 4 0 0 1-8 0z', 'M4 19c2-2 14-2 16 0'] },
  { id: 'robot', label: 'Robot', paths: ['M6 8h12v10H6z', 'M12 4v4', 'M9.5 12h.01', 'M14.5 12h.01', 'M9 15.5h6', 'M4 12h2', 'M18 12h2'] },
  { id: 'ghost', label: 'Fantôme', paths: ['M6 19v-7a6 6 0 0 1 12 0v7l-2-1.5L14 19l-2-1.5L10 19l-2-1.5z', 'M10 11.5h.01', 'M14 11.5h.01'] },
  { id: 'dino', label: 'Dino', paths: ['M7 19v-8a5 5 0 0 1 9-3l3 2-2 2v7h-3v-3h-4v3z', 'M14 9h.01', 'M7 10l-2-1', 'M7 13l-2-1'] },
  { id: 'rocket', label: 'Fusée', paths: ['M12 3c4 3 5 7 5 11l-5 3-5-3c0-4 1-8 5-11z', 'M12 10a1.5 1.5 0 1 0 0 .01', 'M7 14l-3 3h3', 'M17 14l3 3h-3'] },
  { id: 'star', label: 'Étoile', paths: ['M12 3l2.6 5.3 5.9.9-4.3 4.2 1 5.9L12 16.5l-5.2 2.8 1-5.9-4.3-4.2 5.9-.9z'] },
  { id: 'sun', label: 'Soleil', paths: ['M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M12 3v2', 'M12 19v2', 'M4.9 4.9l1.4 1.4', 'M17.7 17.7l1.4 1.4', 'M3 12h2', 'M19 12h2', 'M4.9 19.1l1.4-1.4', 'M17.7 6.3l1.4-1.4'] },
]

const BY_ID = new Map(SPECS.map((s) => [s.id, s]))

export const AVATAR_CHOICES: readonly AvatarChoice[] = [
  { id: '', label: 'Initiale' },
  ...SPECS.map(({ id, label }) => ({ id, label })),
]

export function avatarSvg(id: string, size: number): SVGSVGElement | null {
  const spec = BY_ID.get(id)
  if (!spec) return null
  const svg = document.createElementNS(SVG_NS, 'svg')
  svg.setAttribute('viewBox', '0 0 24 24')
  svg.setAttribute('width', String(size))
  svg.setAttribute('height', String(size))
  svg.setAttribute('aria-hidden', 'true')
  svg.setAttribute('class', 'avatar-svg')
  for (const d of spec.paths) {
    const path = document.createElementNS(SVG_NS, 'path')
    path.setAttribute('d', d)
    svg.appendChild(path)
  }
  return svg
}
