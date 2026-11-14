import { append, h, icon } from '../dom'
import {
  API_KEY_DEFS, AUDIO_LANGUAGES, DEFAULT_SYNCED_SETTINGS, DEFAULT_USER_AGENT,
  type ApiKeyDef, type SyncedSettings,
} from '../model'
import { act, confirmDialog, emptyCard, loading, openDialog } from '../ui'
import { backend, refresh, renderView, state } from '../context'
import { errorBanner, pageHeader, panel, passwordWithReveal } from '../shared'
import { vaultGate } from '../vault-ui'

function shiftLabel(min: number): string {
  if (min === 0) return 'Aucun'
  const abs = Math.abs(min)
  const hPart = Math.floor(abs / 60)
  const mPart = abs % 60
  return `${min > 0 ? '+' : '−'}${hPart} h${mPart ? ` ${String(mPart).padStart(2, '0')}` : ''}`
}

function maskKey(value: string): string {
  const s = value.trim()
  if (s.length <= 4) return '••••'
  return `••••••••${s.slice(-4)}`
}

export function settingsView() {
  const box = h('section', {}, pageHeader('reglages'), errorBanner('apiKeys'), errorBanner('syncedSettings'))
  if (state.vault === null) return append(box, [loading()]), box
  if (state.vault !== 'open') {
    return append(box, [vaultGate('Réglages et clés chiffrés',
      'Tes clés API et tes réglages synchronisés sont chiffrés avec le mot de passe du compte. Saisis-le pour y accéder.')]), box
  }

  append(box, [h('div', { class: 'settings' }, apiKeysPanel(), syncedSettingsPanel())])
  return box
}

// ------------------------------------------------------------------ Clés API personnelles

function apiKeysPanel() {
  const keys = state.apiKeys
  const body: (Node | null)[] = []
  if (!keys) {
    body.push(state.errors.apiKeys ? null : loading(3))
  } else {
    body.push(h('ul', { class: 'rows' }, API_KEY_DEFS.map((def) => {
      const val = keys[def.id] ?? ''
      const configured = Boolean(val.trim())
      return h('li', { class: 'row-card compact' },
        h('span', { class: `kind-badge ${configured ? 'k-locked' : ''}` }, icon('key', 15)),
        h('div', { class: 'row-main' },
          h('div', { class: 'title-line' },
            h('span', { class: 'row-title' }, def.label),
            h('span', { class: `pill ${configured ? 'ok' : 'muted'}` }, configured ? 'Configurée' : 'Par défaut / absente')),
          h('div', { class: 'row-sub' }, configured ? `Enregistrée (${maskKey(val)}) · ${def.hint}` : def.hint)),
        h('div', { class: 'row-actions always' },
          h('button', {
            class: `btn ${configured ? 'ghost' : 'outline'} small`, type: 'button',
            onclick: () => apiKeyDialog(def, val),
          }, icon('edit', 14), configured ? 'Modifier' : 'Configurer')))
    })))
  }
  return panel('Clés API personnelles',
    'Stockées individuellement et chiffrées de bout en bout, synchronisées sur Android, Android TV et Vyzo PC.',
    body,
    [icon('lock', 14), 'Seule la clé saisie quitte ce navigateur sous forme chiffrée. Retirer une clé l’efface sur tous tes appareils.'])
}

function apiKeyDialog(def: ApiKeyDef, current: string) {
  const account = state.account!
  const input = h('input', {
    id: 'ak-value', type: def.secret ? 'password' : 'text', value: current,
    placeholder: def.placeholder, autocomplete: 'off', spellcheck: 'false', class: 'mono-input',
  })
  const error = h('p', { class: 'form-error', role: 'alert' })
  const save: HTMLButtonElement = h('button', { class: 'btn primary', type: 'submit' }, 'Enregistrer')
  const write = async (next: string, done: string, btn: HTMLButtonElement) => {
    if (await act(btn, () => backend.saveApiKey(account, def.id, next), done)) {
      dialog.close()
      await refresh(['apiKeys'])
    }
  }
  const removeBtn: HTMLButtonElement | null = current.trim() ? h('button', {
    class: 'btn danger-ghost', type: 'button',
    onclick: async () => {
      const ok = await confirmDialog({
        title: `Retirer la clé ${def.label} ?`,
        body: 'Elle sera aussi retirée sur tous tes appareils connectés à ce compte.',
        confirm: 'Retirer', danger: true,
      })
      if (ok) void write('', `Clé ${def.label} retirée`, removeBtn!)
    },
  }, 'Retirer') : null

  const form = h('form', {
    onsubmit: (e: Event) => {
      e.preventDefault()
      error.textContent = ''
      const clean = input.value.trim()
      if (!clean) { error.textContent = 'Saisis une clé ou utilise « Retirer ».'; input.focus(); return }
      void write(clean, `Clé ${def.label} enregistrée`, save)
    },
  },
    h('div', { class: 'dialog-head' },
      h('span', { class: 'kind-badge big' }, icon('key', 20)),
      h('div', {}, h('h2', {}, def.label), h('p', { class: 'dialog-sub' }, def.hint))),
    h('label', { for: 'ak-value' }, 'Valeur de la clé'),
    def.secret ? passwordWithReveal(input) : input,
    error,
    h('div', { class: 'dialog-actions' }, removeBtn, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler'), save))
  const dialog = openDialog(form)
  input.focus()
}

// ------------------------------------------------------------------ Réglages synchronisés

function syncedSettingsPanel() {
  const s = state.syncedSettings
  if (s === undefined) {
    return panel('Réglages synchronisés', 'Préférences communes à tous tes appareils Vyzo.',
      [state.errors.syncedSettings ? null : loading(3)])
  }
  const account = state.account!
  const save = async (next: SyncedSettings, btn: HTMLButtonElement | null = null, done = 'Réglage synchronisé') => {
    await act(btn, async () => {
      await backend.saveSyncedSettings(account, next)
      state.syncedSettings = next
      renderView()
    }, done)
  }

  if (s === null) {
    const initBtn: HTMLButtonElement = h('button', {
      class: 'btn primary', type: 'button',
      onclick: () => void save({ ...DEFAULT_SYNCED_SETTINGS }, initBtn, 'Réglages partagés initialisés'),
    }, icon('sliders', 15), 'Initialiser avec les réglages par défaut')
    return panel('Réglages synchronisés',
      'Aucun appareil n’a encore envoyé ses réglages sur ce compte.',
      [emptyCard('sliders', 'Pas encore de réglages partagés',
        'Ouvre Vyzo sur un appareil connecté ou initialise-les ici avec les réglages recommandés.', initBtn)])
  }

  const toggle = (id: string, title: string, desc: string, checked: boolean, patch: (on: boolean) => SyncedSettings) => {
    const cb = h('input', {
      id, type: 'checkbox', checked,
      onchange: () => void save(patch(cb.checked)),
    })
    return h('label', { class: 'check', for: id }, cb, h('span', {}, h('strong', {}, title), h('small', {}, desc)))
  }

  const selectRow = <T extends string | number>(
    id: string, title: string, desc: string, current: T, options: [T, string][], patch: (val: T) => SyncedSettings, parse: (raw: string) => T,
  ) => {
    const sel = h('select', {
      id,
      onchange: () => void save(patch(parse(sel.value))),
    }, options.map(([val, label]) => h('option', { value: String(val), selected: val === current }, label)))
    return h('div', { class: 'setting-row' },
      h('div', { class: 'setting-text' }, h('label', { for: id }, title), desc ? h('small', {}, desc) : null),
      h('div', { class: 'setting-control' }, sel))
  }

  // User-Agent avec validation explicite
  const uaInput = h('input', {
    id: 'set-ua', type: 'text', value: s.userAgent, placeholder: DEFAULT_USER_AGENT, autocomplete: 'off', spellcheck: 'false',
  })
  const uaSave: HTMLButtonElement = h('button', {
    class: 'btn outline small', type: 'button',
    onclick: () => {
      const nextUa = uaInput.value.trim() || DEFAULT_USER_AGENT
      uaInput.value = nextUa
      void save({ ...s, userAgent: nextUa }, uaSave, 'User-Agent enregistré')
    },
  }, 'Appliquer')

  const epgShifts = Array.from({ length: 49 }, (_, i) => (i - 24) * 30).map((m): [number, string] => [m, shiftLabel(m)])
  const langOptions = AUDIO_LANGUAGES.map(([code, label]): [string, string] => [code, label])
  const subOptions: [string, string][] = [['', 'Désactivés'], ...AUDIO_LANGUAGES.filter(([code]) => code !== '').map(([c, l]): [string, string] => [c, l])]

  return h('div', { class: 'settings-stack' },
    panel('Abonnements & Guide TV (EPG)', 'Comportement des listes et de la grille des programmes.', [
      selectRow('set-exp', 'Prévenir avant expiration', 'Rappel pour les comptes Xtream et Stalker.',
        s.expiryWarnDays, [[0, 'Jamais'], [1, '1 jour avant'], [3, '3 jours avant'], [7, '7 jours avant'], [14, '14 jours avant'], [30, '30 jours avant']],
        (v) => ({ ...s, expiryWarnDays: v }), Number),
      selectRow('set-epg-ahead', 'Jours à venir chargés', 'Horizon du guide XMLTV.',
        s.epgDaysAhead, [1, 2, 3, 4, 5, 6, 7].map((d) => [d, `${d} jour${d > 1 ? 's' : ''}`]),
        (v) => ({ ...s, epgDaysAhead: v }), Number),
      selectRow('set-epg-behind', 'Programmes passés conservés', 'Utile pour le replay / rattrapage.',
        s.epgDaysBehind, [0, 1, 2, 3, 4, 5, 6, 7].map((d) => [d, d === 0 ? 'Aucun' : `${d} jour${d > 1 ? 's' : ''}`]),
        (v) => ({ ...s, epgDaysBehind: v }), Number),
      selectRow('set-epg-shift', 'Décalage horaire du guide', 'Corrige un XMLTV décalé d’un fuseau.',
        s.epgShiftMinutes, epgShifts, (v) => ({ ...s, epgShiftMinutes: v }), Number),
      toggle('set-all-pl', 'Afficher toutes les playlists ensemble',
        'Réunit chaînes, films et séries de tous tes abonnements actifs.', s.showAllPlaylists,
        (on) => ({ ...s, showAllPlaylists: on })),
    ]),

    panel('Lecture, Langues & Zapping', 'Paramètres de lecture partagés entre TV, téléphone et PC.', [
      selectRow('set-xtream', 'Format des flux Xtream', 'MPEG-TS recommandé par défaut ; HLS si ton fournisseur le préfère.',
        s.xtreamFormat, [['ts', 'MPEG-TS (.ts)'], ['m3u8', 'HLS (.m3u8)']],
        (v) => ({ ...s, xtreamFormat: v }), (raw) => (raw === 'm3u8' ? 'm3u8' : 'ts')),
      selectRow('set-timeshift', 'Pause du direct', 'Mémoire tampon sur l’appareil (environ 2 Go/h en HD).',
        s.timeshiftMinutes, [[0, 'Désactivée'], [15, '15 min'], [30, '30 min'], [60, '60 min'], [120, '120 min']],
        (v) => ({ ...s, timeshiftMinutes: v }), Number),
      selectRow('set-audio', 'Langue audio préférée', 'Piste choisie automatiquement quand disponible.',
        s.audioLang, langOptions, (v) => ({ ...s, audioLang: v }), String),
      selectRow('set-sub', 'Sous-titres par défaut', 'Actifs automatiquement dans cette langue.',
        s.subtitleLang, subOptions, (v) => ({ ...s, subtitleLang: v }), String),
      toggle('set-bg', 'Continuer la lecture en arrière-plan',
        'Affiche un mini-lecteur flottant pendant la navigation dans Vyzo.', s.continuePlaybackInBackground,
        (on) => ({ ...s, continuePlaybackInBackground: on })),
      toggle('set-nums', 'Afficher les numéros de chaîne',
        'Affiche le numéro à côté des noms dans le direct et le zapping.', s.showNumbers,
        (on) => ({ ...s, showNumbers: on })),
      toggle('set-inv', 'Inverser le zapping',
        'La touche ▲ passe à la chaîne précédente.', s.invertZap,
        (on) => ({ ...s, invertZap: on })),
      toggle('set-auto', 'Lancer la dernière chaîne au démarrage',
        'Reprend directement le direct à l’ouverture de l’application.', s.autoplayLast,
        (on) => ({ ...s, autoplayLast: on })),
      h('div', { class: 'setting-row stack-mobile' },
        h('div', { class: 'setting-text' }, h('label', { for: 'set-ua' }, 'User-Agent par défaut'),
          h('small', {}, `Vide rétablit « ${DEFAULT_USER_AGENT} ».`)),
        h('div', { class: 'setting-ua' }, uaInput, uaSave)),
    ]),

    panel('Séries & Enregistrements', 'Enchaînement automatique et marges d’enregistrement.', [
      toggle('set-intro', 'Sauter automatiquement les intros',
        'Uniquement lorsque TheIntroDB fournit le repère exact.', s.autoSkipIntro,
        (on) => ({ ...s, autoSkipIntro: on })),
      toggle('set-recap', 'Sauter automatiquement les récaps',
        'Ignore « précédemment dans… » si disponible sur TheIntroDB.', s.autoSkipRecap,
        (on) => ({ ...s, autoSkipRecap: on })),
      toggle('set-next', 'Épisode suivant automatique',
        'Lance l’épisode suivant 30 s avant la fin (avec compte à rebours).', s.autoNextEpisodeOnOutro,
        (on) => ({ ...s, autoNextEpisodeOnOutro: on })),
      selectRow('set-pad-before', 'Marge avant l’enregistrement', 'Démarre plus tôt pour ne pas rater le début.',
        s.recPaddingBeforeMin, [0, 1, 2, 5, 10, 15].map((m) => [m, `${m} min`]),
        (v) => ({ ...s, recPaddingBeforeMin: v }), Number),
      selectRow('set-pad-after', 'Marge après l’enregistrement', 'Prolonge en cas de retard de diffusion.',
        s.recPaddingAfterMin, [0, 2, 5, 10, 15, 30].map((m) => [m, `${m} min`]),
        (v) => ({ ...s, recPaddingAfterMin: v }), Number),
      selectRow('set-reminder', 'Rappel avant une émission', 'Notification avant le début d’un programme suivi.',
        s.reminderMinutesBefore, [0, 1, 2, 5, 10, 15].map((m) => [m, m === 0 ? 'À l’heure' : `${m} min avant`]),
        (v) => ({ ...s, reminderMinutesBefore: v }), Number),
    ]),
  )
}
