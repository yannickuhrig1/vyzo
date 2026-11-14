import { friendlyError, UserError, type PlaylistDraft } from '../backend'
import { append, clear, h, icon } from '../dom'
import {
  categoryLocked, isOnline, moveEpgSource, parseChannelRest, parseXtreamUrl, subscriptionState, supportsRemote,
  withCategoryPref, withChannelPref, removeChannelPref,
  type CatalogEntry, type ContentKind, type EpgDraft, type EpgMode, type EpgSource, type Playlist,
  type SealedCategoryPref, type SealedChannelPref, type SealedPrefs,
} from '../model'
import { act, dateFmt, emptyCard, fold, loading, openDialog, plural, toast } from '../ui'
import { backend, ensureRouteData, isHidden, refresh, renderView, softDelete, state } from '../context'
import { errorBanner, hostOf, KIND_ICON, pageHeader, passwordWithReveal, TONE_PILL, TYPE_LABEL } from '../shared'
import { lockNow, vaultGate } from '../vault-ui'
import { checkNow } from './devices'

export type PlaylistsTab = 'playlists' | 'epg'
let currentTab: PlaylistsTab = 'playlists'

export function setPlaylistsTab(tab: PlaylistsTab) {
  currentTab = tab
  renderView()
  ensureRouteData()
}

export function playlistsView() {
  const open = state.vault === 'open'
  const remote = open ? (state.devices ?? []).find((d) => !isHidden(`d:${d.id}`) && isOnline(d) && supportsRemote(d)) ?? null : null
  const verifyBtn: HTMLButtonElement | null = open && currentTab === 'playlists' && remote ? h('button', {
    class: 'btn ghost', type: 'button', title: `Interroger les fournisseurs depuis ${remote.label || remote.name}`,
    onclick: () => { if (verifyBtn) void checkNow(remote, verifyBtn) },
  }, icon('pulse', 15), 'Vérifier maintenant') : null
  const add = open ? h('button', {
    class: 'btn primary', type: 'button',
    onclick: () => (currentTab === 'epg' ? editEpgDialog(null) : editPlaylist(null)),
  }, icon('plus', 16), currentTab === 'epg' ? 'Ajouter une source EPG' : 'Ajouter un abonnement') : null
  const lockBtn = open ? h('button', {
    class: 'btn ghost', type: 'button',
    title: state.remembered ? 'Verrouiller et oublier la clé gardée sur cet ordinateur' : 'Oublier la clé dans ce navigateur',
    onclick: () => void lockNow(),
  }, icon('lock', 15), 'Verrouiller') : null

  const box = h('section', {}, pageHeader('abonnements', verifyBtn, lockBtn, add), errorBanner('playlists'), errorBanner('epg'))
  if (state.vault === null) return append(box, [state.errors.playlists ? null : loading()]), box
  if (!open) return append(box, [vaultGate()]), box

  const playlists = state.playlists?.filter((p) => !isHidden(`pl:${p.id}`)) ?? null
  const epg = state.epg?.filter((e) => !isHidden(`epg:${e.id}`)) ?? null

  const tabs = h('div', { class: 'sub-tabs', role: 'tablist', 'aria-label': 'Sources' },
    h('button', {
      type: 'button', role: 'tab', class: 'sub-tab', 'aria-selected': String(currentTab === 'playlists'),
      onclick: () => setPlaylistsTab('playlists'),
    }, icon('key', 14), h('span', {}, 'Abonnements IPTV'), playlists ? h('span', { class: 'chip-count' }, String(playlists.length)) : null),
    h('button', {
      type: 'button', role: 'tab', class: 'sub-tab', 'aria-selected': String(currentTab === 'epg'),
      onclick: () => setPlaylistsTab('epg'),
    }, icon('globe', 14), h('span', {}, 'Guides TV (EPG)'), epg ? h('span', { class: 'chip-count' }, String(epg.length)) : null))
  append(box, [tabs])

  if (currentTab === 'epg') return append(box, [epgSection(epg)]), box

  if (!playlists) return append(box, [state.errors.playlists ? null : loading()]), box
  if (playlists.length === 0) {
    append(box, [emptyCard('key', 'Aucun abonnement',
      'Ajoute ton abonnement Xtream, ta liste M3U ou ton portail Stalker : il sera proposé sur tous tes appareils.')])
    return box
  }
  append(box, [h('ul', { class: 'rows' }, playlists.map((p) => playlistRow(p, state.playlists ?? playlists)))])
  append(box, [h('p', { class: 'note' }, icon('lock', 15), h('span', {},
    'Le chiffrement se fait dans ce navigateur, avec la clé du compte : le serveur Vyzo ne voit jamais tes identifiants en clair.'))])
  return box
}

function playlistRow(p: Playlist, all: Playlist[]) {
  const sub = [TYPE_LABEL[p.type], hostOf(p.url), p.type === 'XTREAM' ? p.username : p.type === 'STALKER' ? p.mac : null]
    .filter(Boolean).join(' · ')
  const account = state.account!
  const del = h('button', {
    class: 'icon-btn', type: 'button', title: 'Supprimer', 'aria-label': `Supprimer ${p.name}`,
    onclick: () => softDelete(`pl:${p.id}`, `« ${p.name} » supprimé de tes appareils`, () => backend.deletePlaylist(account, p), ['playlists']),
  }, icon('trash', 15))
  const sub2 = p.status ? [
    p.status.maxConnections > 0 ? plural(p.status.maxConnections, 'connexion', 'connexions') : null,
    p.status.checkedDay > 0 ? `vérifié le ${dateFmt.format(p.status.checkedDay)}` : null,
  ].filter(Boolean).join(' · ') : ''
  return h('li', { class: 'row-card' },
    h('span', { class: 'kind-badge big' }, icon(p.type === 'M3U' ? 'list' : 'key', 18)),
    h('div', { class: 'row-main' },
      h('div', { class: 'title-line' }, h('span', { class: 'row-title' }, p.name), subscriptionPill(p)),
      h('div', { class: 'row-sub' }, h('span', { class: 'ellipsis' }, sub)),
      sub2 ? h('div', { class: 'row-sub faint' }, sub2) : null),
    h('div', { class: 'row-actions' },
      h('button', { class: 'btn ghost small', type: 'button', onclick: () => prefsDialog(p) }, icon('sliders', 14), 'Chaînes'),
      h('button', { class: 'btn ghost small', type: 'button', onclick: () => editPlaylist(p, all) }, icon('edit', 14), 'Modifier'),
      del),
  )
}

/** État chez le fournisseur, tel que le dernier appareil l'a vu. */
function subscriptionPill(p: Playlist) {
  const st = subscriptionState(p.status)
  if (!st) return null
  return h('span', { class: `pill ${TONE_PILL[st.tone]}`, title: p.status?.expiresAt ? `Fin le ${dateFmt.format(p.status.expiresAt)}` : '' }, st.label)
}

const MAC_RE = /^([0-9A-F]{2}:){5}[0-9A-F]{2}$/i

export function editPlaylist(existing: Playlist | null, all: Playlist[] = state.playlists ?? []) {
  const draft: PlaylistDraft = existing
    ? { name: existing.name, type: existing.type, url: existing.url, username: existing.username, password: existing.password,
      mac: existing.mac || '00:1A:79:', userAgent: existing.userAgent }
    : { name: '', type: 'XTREAM', url: '', username: '', password: '', mac: '00:1A:79:', userAgent: '' }

  const input = (id: string, value: string, attrs: Record<string, string | boolean> = {}) =>
    h('input', { id, value, autocomplete: 'off', spellcheck: 'false', ...attrs })
  const name = input('pl-name', draft.name, { placeholder: 'Facultatif', maxlength: '60' })
  const url = input('pl-url', draft.url, { inputmode: 'url' })
  const user = input('pl-user', draft.username)
  const pass = input('pl-pass', draft.password, { type: 'password', autocomplete: 'new-password' })
  const mac = input('pl-mac', draft.mac, { placeholder: '00:1A:79:XX:XX:XX', class: 'mono-input' })
  mac.addEventListener('input', () => { mac.value = mac.value.toUpperCase() })
  const agent = input('pl-agent', draft.userAgent, { placeholder: 'Celui de Vyzo par défaut' })

  const fields = h('div', {})
  const parsedNote = h('p', { class: 'form-note', hidden: true }, 'Identifiants Xtream détectés et remplis automatiquement depuis le lien.')
  const tabs = h('div', { class: 'segmented', role: 'radiogroup', 'aria-label': 'Type d’abonnement' })
  const renderFields = () => {
    clear(tabs)
    for (const t of ['XTREAM', 'M3U', 'STALKER'] as const) {
      tabs.appendChild(h('button', {
        type: 'button', role: 'radio', 'aria-checked': String(draft.type === t),
        onclick: () => { draft.type = t; renderFields(); url.focus() },
      }, TYPE_LABEL[t]))
    }
    clear(fields)
    const urlLabel = draft.type === 'M3U' ? 'Adresse de la liste M3U (ou lien Xtream complet)'
      : draft.type === 'XTREAM' ? 'Adresse du serveur (ou lien get.php complet)' : 'Adresse du portail'
    url.placeholder = draft.type === 'M3U' ? 'https://…/playlist.m3u' : draft.type === 'XTREAM' ? 'http://serveur.exemple:8080' : 'http://portail.exemple/c/'
    append(fields, [h('label', { for: 'pl-url' }, urlLabel), url, parsedNote])
    if (draft.type === 'XTREAM') {
      append(fields, [
        h('div', { class: 'two' },
          h('div', {}, h('label', { for: 'pl-user' }, 'Identifiant'), user),
          h('div', {}, h('label', { for: 'pl-pass' }, 'Mot de passe'), passwordWithReveal(pass))),
      ])
    }
    if (draft.type === 'STALKER') append(fields, [h('label', { for: 'pl-mac' }, 'Adresse MAC'), mac])
  }
  url.addEventListener('input', () => {
    const parsed = parseXtreamUrl(url.value)
    if (!parsed) return
    draft.type = 'XTREAM'
    url.value = parsed.url
    user.value = parsed.username
    pass.value = parsed.password
    parsedNote.hidden = false
    renderFields()
  })
  renderFields()

  const error = h('p', { class: 'form-error', role: 'alert' })
  const save = h('button', { class: 'btn primary', type: 'submit' }, existing ? 'Enregistrer' : 'Ajouter')
  const cancel = h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler')

  const validate = (): string | null => {
    if (!url.value.trim()) {
      return draft.type === 'M3U' ? 'Indique l’adresse de la playlist M3U.'
        : draft.type === 'XTREAM' ? 'Indique l’adresse du serveur.' : 'Indique l’adresse du portail.'
    }
    if (draft.type === 'XTREAM' && (!user.value.trim() || !pass.value)) return 'Identifiant et mot de passe requis.'
    if (draft.type === 'STALKER' && !MAC_RE.test(mac.value.trim())) return 'Adresse MAC invalide (format 00:1A:79:XX:XX:XX).'
    return null
  }

  const form = h('form', {
    method: 'dialog',
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      error.textContent = validate() ?? ''
      if (error.textContent) return
      const final: PlaylistDraft = {
        type: draft.type,
        name: name.value.trim() || hostOf(url.value) || (draft.type === 'M3U' ? 'Playlist M3U' : draft.type === 'XTREAM' ? 'Xtream' : 'Portail'),
        url: url.value.trim(),
        username: draft.type === 'XTREAM' ? user.value.trim() : '',
        password: draft.type === 'XTREAM' ? pass.value : '',
        mac: draft.type === 'STALKER' ? mac.value.trim().toUpperCase() : '',
        userAgent: agent.value.trim(),
      }
      save.setAttribute('aria-busy', 'true')
      save.disabled = true
      try {
        await backend.savePlaylist(state.account!, final, existing, all)
        toast(existing ? 'Abonnement enregistré' : `« ${final.name} » ajouté à tes appareils`)
        dialog.close()
        await refresh(['playlists'])
      } catch (e) {
        error.textContent = e instanceof UserError ? e.message : friendlyError(e)
      } finally {
        save.removeAttribute('aria-busy')
        save.disabled = false
      }
    },
  },
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('key', 20)),
      h('h2', {}, existing ? 'Modifier l’abonnement' : 'Nouvel abonnement')),
    h('span', { class: 'field-label' }, 'Type'), tabs,
    h('label', { for: 'pl-name' }, 'Nom'), name,
    fields,
    h('details', { class: 'more' }, h('summary', {}, 'Options avancées'),
      h('label', { for: 'pl-agent' }, 'User-Agent'), agent),
    existing ? h('p', { class: 'fine' },
      'Changer d’identifiant, d’adresse MAC ou d’adresse M3U en fait un nouvel abonnement pour les appareils : ' +
      'ses réglages de chaînes (masquées, ordre) repartent de zéro.') : null,
    error,
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }), cancel, save),
  )
  const dialog = openDialog(form, 'wide')
  ;(existing ? name : url).focus()
}

// ------------------------------------------------------------------ Sources de guide TV (EPG)

function epgSection(list: EpgSource[] | null): HTMLElement {
  if (!list) return state.errors.epg ? h('div', {}) : loading(2)
  if (list.length === 0) {
    return emptyCard('globe', 'Aucune source EPG personnalisée',
      'Ajoute une source XMLTV (.xml ou .xml.gz) pour compléter les guides de tes abonnements.')
  }
  const account = state.account!
  const move = async (id: string, delta: -1 | 1, btn: HTMLButtonElement) => {
    const changed = moveEpgSource(list, id, delta)
    if (!changed.length) return
    await act(btn, () => backend.saveEpgOrder(account, changed))
    await refresh(['epg'])
  }
  const toggleEnabled = async (s: EpgSource, btn: HTMLButtonElement) => {
    await act(btn, () => backend.saveEpgSource(account, {
      name: s.name, url: s.url, enabled: !s.enabled, priority: s.priority, mode: s.mode,
    }, s, state.epg ?? list), s.enabled ? `« ${s.name} » désactivée` : `« ${s.name} » activée`)
    await refresh(['epg'])
  }
  return h('div', {},
    h('ul', { class: 'rows' }, list.map((s, idx) => {
      const upBtn: HTMLButtonElement = h('button', {
        class: 'icon-btn', type: 'button', disabled: idx === 0, title: 'Monter dans la priorité',
        'aria-label': `Monter ${s.name}`, onclick: () => void move(s.id, -1, upBtn),
      }, icon('up', 14))
      const downBtn: HTMLButtonElement = h('button', {
        class: 'icon-btn', type: 'button', disabled: idx === list.length - 1, title: 'Descendre dans la priorité',
        'aria-label': `Descendre ${s.name}`, onclick: () => void move(s.id, 1, downBtn),
      }, icon('down', 14))
      const toggleBtn: HTMLButtonElement = h('button', {
        class: 'btn ghost small', type: 'button',
        onclick: () => void toggleEnabled(s, toggleBtn),
      }, s.enabled ? 'Désactiver' : 'Activer')
      return h('li', { class: `row-card ${s.enabled ? '' : 'faded'}` },
        h('span', { class: 'kind-badge big' }, icon('globe', 18)),
        h('div', { class: 'row-main' },
          h('div', { class: 'title-line' },
            h('span', { class: 'row-title' }, s.name),
            h('span', { class: `pill ${s.enabled ? 'ok' : 'muted'}` }, s.enabled ? 'Active' : 'Désactivée'),
            h('span', { class: 'pill muted' }, s.mode === 'GAPS' ? 'Combler les trous' : 'Chaînes sans guide')),
          h('div', { class: 'row-sub' }, h('span', { class: 'ellipsis' }, `${hostOf(s.url)} · priorité ${idx + 1}`))),
        h('div', { class: 'row-actions' },
          upBtn, downBtn, toggleBtn,
          h('button', { class: 'btn ghost small', type: 'button', onclick: () => editEpgDialog(s) }, icon('edit', 14), 'Modifier'),
          h('button', {
            class: 'icon-btn', type: 'button', title: 'Supprimer', 'aria-label': `Supprimer ${s.name}`,
            onclick: () => softDelete(`epg:${s.id}`, `Source « ${s.name} » retirée`, () => backend.deleteEpgSource(account, s), ['epg']),
          }, icon('trash', 15))))
    })),
    h('p', { class: 'note' }, icon('info', 15), h('span', {},
      'Les sources sont évaluées de haut en bas : « Chaînes sans guide » ne couvre que les chaînes sans EPG ; « Combler les trous » remplit aussi les créneaux vides.')))
}

export function editEpgDialog(existing: EpgSource | null) {
  const all = state.epg ?? []
  const name = h('input', { id: 'epg-name', value: existing?.name ?? '', placeholder: 'ex. XMLTV France', maxlength: 60, autocomplete: 'off' })
  const url = h('input', { id: 'epg-url', value: existing?.url ?? '', type: 'url', placeholder: 'https://…/guide.xml.gz', inputmode: 'url', autocomplete: 'off', spellcheck: 'false' })
  let mode: EpgMode = existing?.mode ?? 'FILL'
  const enabled = h('input', { id: 'epg-en', type: 'checkbox', checked: existing?.enabled ?? true })
  const modeGroup = h('div', { class: 'segmented', role: 'radiogroup', 'aria-label': 'Mode de fusion EPG' })
  const renderMode = () => {
    clear(modeGroup)
    for (const [m, label] of [['FILL', 'Chaînes sans guide'], ['GAPS', 'Combler les trous']] as const) {
      modeGroup.appendChild(h('button', {
        type: 'button', role: 'radio', 'aria-checked': String(mode === m),
        onclick: () => { mode = m; renderMode() },
      }, label))
    }
  }
  renderMode()
  const error = h('p', { class: 'form-error', role: 'alert' })
  const save: HTMLButtonElement = h('button', { class: 'btn primary', type: 'submit' }, existing ? 'Enregistrer' : 'Ajouter')
  const form = h('form', {
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      error.textContent = ''
      const rawUrl = url.value.trim()
      if (!/^https?:\/\/.+/i.test(rawUrl)) { error.textContent = 'Indique une adresse http:// ou https:// valide.'; url.focus(); return }
      const priority = existing?.priority ?? (all.length ? Math.max(...all.map((s) => s.priority)) + 1 : 0)
      const draft: EpgDraft = { name: name.value.trim() || hostOf(rawUrl), url: rawUrl, enabled: enabled.checked, priority, mode }
      save.setAttribute('aria-busy', 'true')
      save.disabled = true
      try {
        await backend.saveEpgSource(state.account!, draft, existing, all)
        toast(existing ? 'Source EPG enregistrée' : `« ${draft.name} » ajoutée`)
        dialog.close()
        await refresh(['epg'])
      } catch (e) {
        error.textContent = e instanceof UserError ? e.message : friendlyError(e)
      } finally {
        save.removeAttribute('aria-busy')
        save.disabled = false
      }
    },
  },
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('globe', 20)),
      h('h2', {}, existing ? 'Modifier la source EPG' : 'Nouvelle source EPG')),
    h('label', { for: 'epg-name' }, 'Nom'), name,
    h('label', { for: 'epg-url' }, 'Adresse XMLTV (.xml / .xml.gz)'), url,
    h('span', { class: 'field-label' }, 'Rôle si une chaîne a déjà un guide'), modeGroup,
    h('label', { class: 'check compact', for: 'epg-en' }, enabled, h('span', {}, h('strong', {}, 'Source active'))),
    error,
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler'), save))
  const dialog = openDialog(form)
  ;(existing ? name : url).focus()
}

// ------------------------------------------------------------------ Éditeur catégories & chaînes (SealedPrefs)

function prefsDialog(playlist: Playlist) {
  const account = state.account!
  const body = h('div', { class: 'prefs-body' }, loading(3))
  let tab: 'categories' | 'channels' = 'categories'
  let catQuery = ''
  let catFilter: 'all' | 'hidden' | 'locked' | 'renamed' = 'all'
  let chQuery = ''
  let loaded: { entries: CatalogEntry[]; prefs: SealedPrefs } | null = null
  const autoAdult = state.parental?.autoAdult ?? true

  const reload = async () => {
    try {
      loaded = await backend.catalog(account, playlist.id)
      renderBody()
    } catch (e) {
      clear(body)
      body.appendChild(h('p', { class: 'form-error' }, friendlyError(e)))
    }
  }

  const updatePrefs = async (btn: HTMLButtonElement | null, done: string, fn: (cur: SealedPrefs) => SealedPrefs) => {
    await act(btn, async () => {
      const next = await backend.savePrefs(account, playlist.id, fn)
      if (loaded) loaded.prefs = next
      if (state.catalog?.playlistId === playlist.id) state.catalog = { ...state.catalog, prefs: next }
      renderBody()
      void refresh(['playlists'])
    }, done)
  }

  const renderBody = () => {
    const prevScroll = body.querySelector('.prefs-list')?.scrollTop ?? 0
    clear(body)
    if (!loaded) { body.appendChild(loading(3)); return }
    const { entries, prefs } = loaded
    const tabBar = h('div', { class: 'segmented', role: 'tablist' },
      h('button', {
        type: 'button', role: 'tab', 'aria-selected': String(tab === 'categories'),
        onclick: () => { tab = 'categories'; renderBody() },
      }, `Catégories (${entries.length || prefs.categories.length})`),
      h('button', {
        type: 'button', role: 'tab', 'aria-selected': String(tab === 'channels'),
        onclick: () => { tab = 'channels'; renderBody() },
      }, `Chaînes personnalisées (${prefs.channels.length})`))
    body.appendChild(tabBar)

    if (tab === 'categories') renderCategoriesTab(entries, prefs)
    else renderChannelsTab(prefs)
    const listEl = body.querySelector('.prefs-list')
    if (listEl && prevScroll > 0) listEl.scrollTop = prevScroll
  }

  const renderCategoriesTab = (entries: CatalogEntry[], prefs: SealedPrefs) => {
    const prefMap = new Map(prefs.categories.map((c) => [c.rest, c]))
    if (entries.length === 0 && prefs.categories.length === 0) {
      body.appendChild(h('p', { class: 'empty small' },
        'Aucun appareil n’a encore envoyé la liste des catégories de cet abonnement. Ouvre Vyzo sur un appareil connecté une fois.'))
      return
    }
    const items: { rest: string; origName: string; kind: ContentKind | null; adult: boolean; pref?: SealedCategoryPref }[] = entries.length
      ? entries.map((e) => ({ rest: e.rest, origName: e.name, kind: e.kind, adult: e.adult, pref: prefMap.get(e.rest) }))
      : prefs.categories.map((c) => ({ rest: c.rest, origName: parseChannelRest(c.rest), kind: null, adult: false, pref: c }))

    const search = h('input', {
      id: 'prefs-cat-q', type: 'search', value: catQuery, placeholder: `Filtrer parmi ${items.length} catégories…`,
      autocomplete: 'off', spellcheck: 'false',
    })
    const results = h('div', { class: 'prefs-list' })
    const filterChip = (id: typeof catFilter, label: string) => h('button', {
      type: 'button', class: 'chip', 'aria-pressed': String(catFilter === id),
      onclick: () => { catFilter = id; renderBody() },
    }, label)

    const drawRows = () => {
      clear(results)
      const tokens = fold(catQuery).split(/\s+/).filter(Boolean)
      const shown = items.filter((x) => {
        const isLocked = x.kind ? categoryLocked({ rest: x.rest, name: x.origName, kind: x.kind, adult: x.adult }, x.pref, autoAdult) : x.pref?.locked === true
        if (catFilter === 'hidden' && !x.pref?.hidden) return false
        if (catFilter === 'locked' && !isLocked) return false
        if (catFilter === 'renamed' && !x.pref?.name) return false
        if (!tokens.length) return true
        const hay = fold(`${x.pref?.name ?? ''} ${x.origName}`)
        return tokens.every((t) => hay.includes(t))
      }).slice(0, 120)

      if (!shown.length) {
        results.appendChild(h('p', { class: 'empty small' }, 'Aucune catégorie ne correspond.'))
        return
      }
      results.appendChild(h('ul', { class: 'rows' }, shown.map((x) => {
        const displayName = x.pref?.name || x.origName
        const isHiddenCat = x.pref?.hidden === true
        const isLocked = x.kind
          ? categoryLocked({ rest: x.rest, name: x.origName, kind: x.kind, adult: x.adult }, x.pref, autoAdult)
          : x.pref?.locked === true
        const renameBtn: HTMLButtonElement = h('button', {
          class: 'icon-btn', type: 'button', title: 'Renommer', 'aria-label': `Renommer ${displayName}`,
          onclick: () => renameCategoryDialog(displayName, x.origName, x.pref?.name ?? '', (newName) =>
            updatePrefs(null, newName ? `Renommée en « ${newName} »` : 'Nom d’origine rétabli',
              (cur) => withCategoryPref(cur, x.rest, { name: newName }))),
        }, icon('edit', 14))
        const hideBtn: HTMLButtonElement = h('button', {
          class: `btn ${isHiddenCat ? 'primary' : 'ghost'} small`, type: 'button',
          onclick: () => void updatePrefs(hideBtn, isHiddenCat ? `« ${displayName} » réaffichée` : `« ${displayName} » masquée`,
            (cur) => withCategoryPref(cur, x.rest, { hidden: !isHiddenCat })),
        }, icon(isHiddenCat ? 'eye' : 'eyeOff', 13), isHiddenCat ? 'Afficher' : 'Masquer')
        const lockBtn: HTMLButtonElement = h('button', {
          class: 'icon-btn', type: 'button', title: isLocked ? 'Déverrouiller' : 'Verrouiller',
          'aria-label': `${isLocked ? 'Déverrouiller' : 'Verrouiller'} ${displayName}`,
          onclick: () => void updatePrefs(lockBtn, isLocked ? `« ${displayName} » déverrouillée` : `« ${displayName} » verrouillée`,
            (cur) => withCategoryPref(cur, x.rest, { locked: !isLocked })),
        }, icon(isLocked ? 'unlock' : 'lock', 14))

        return h('li', { class: `row-card compact ${isHiddenCat ? 'faded' : ''}` },
          h('span', { class: `kind-badge ${isLocked ? 'k-locked' : ''}` }, icon(x.kind ? KIND_ICON[x.kind] : 'list', 15)),
          h('div', { class: 'row-main' },
            h('div', { class: 'title-line' },
              h('span', { class: 'row-title' }, displayName),
              isHiddenCat ? h('span', { class: 'pill muted' }, 'Masquée') : null,
              isLocked ? h('span', { class: 'pill planned' }, 'Verrouillée') : null),
            x.pref?.name && x.pref.name !== x.origName ? h('div', { class: 'row-sub faint' }, `Origine : ${x.origName}`) : null),
          h('div', { class: 'row-actions always' }, renameBtn, lockBtn, hideBtn))
      })))
    }
    search.addEventListener('input', () => { catQuery = search.value; drawRows() })
    body.appendChild(h('div', { class: 'prefs-toolbar' },
      h('div', { class: 'filter-search' }, icon('search', 15), search),
      h('div', { class: 'chips' }, filterChip('all', 'Toutes'), filterChip('hidden', 'Masquées'), filterChip('locked', 'Verrouillées'), filterChip('renamed', 'Renommées'))))
    drawRows()
    body.appendChild(results)
  }

  const renderChannelsTab = (prefs: SealedPrefs) => {
    const search = h('input', {
      id: 'prefs-ch-q', type: 'search', value: chQuery, placeholder: 'Chercher par nom, n° ou ID guide…',
      autocomplete: 'off', spellcheck: 'false',
    })
    const addBtn = h('button', {
      class: 'btn outline small', type: 'button',
      onclick: () => channelPrefDialog(null, (rest, patch) => updatePrefs(null, 'Préférence chaîne enregistrée',
        (cur) => withChannelPref(cur, rest, patch))),
    }, icon('plus', 14), 'Ajouter')
    const results = h('div', { class: 'prefs-list' })
    const sorted = [...prefs.channels].sort((a, b) => (a.number || 999_999) - (b.number || 999_999) || (a.name || a.rest).localeCompare(b.name || b.rest, 'fr'))

    const drawRows = () => {
      clear(results)
      const tokens = fold(chQuery).split(/\s+/).filter(Boolean)
      const shown = tokens.length
        ? sorted.filter((c) => tokens.every((t) => fold(`${c.name} ${c.number || ''} ${c.epgKey} ${c.rest}`).includes(t)))
        : sorted
      if (!shown.length) {
        results.appendChild(h('p', { class: 'empty small' }, sorted.length === 0
          ? 'Aucune chaîne personnalisée pour cet abonnement. Les chaînes modifiées dans Vyzo (nom, n°, EPG, masquage) apparaissent ici et se synchronisent sur tous tes appareils.'
          : 'Aucune chaîne personnalisée ne correspond.'))
        return
      }
      results.appendChild(h('ul', { class: 'rows' }, shown.map((c) => {
        const title = c.name || parseChannelRest(c.rest)
        const delBtn: HTMLButtonElement = h('button', {
          class: 'icon-btn', type: 'button', title: 'Réinitialiser cette chaîne', 'aria-label': `Réinitialiser ${title}`,
          onclick: () => void updatePrefs(delBtn, `« ${title} » revenue aux valeurs d’origine`, (cur) => removeChannelPref(cur, c.rest)),
        }, icon('trash', 14))
        const meta = [c.number > 0 ? `N° ${c.number}` : null, c.epgKey ? `EPG : ${c.epgKey}` : null, c.rest].filter(Boolean).join(' · ')
        return h('li', { class: `row-card compact ${c.hidden ? 'faded' : ''}` },
          h('span', { class: `kind-badge ${c.locked ? 'k-locked' : ''}` }, icon(c.locked ? 'lock' : 'tv', 15)),
          h('div', { class: 'row-main' },
            h('div', { class: 'title-line' },
              h('span', { class: 'row-title' }, title),
              c.hidden ? h('span', { class: 'pill muted' }, 'Masquée') : null,
              c.locked ? h('span', { class: 'pill planned' }, 'Verrouillée') : null),
            h('div', { class: 'row-sub' }, h('span', { class: 'ellipsis' }, meta))),
          h('div', { class: 'row-actions always' },
            h('button', {
              class: 'btn ghost small', type: 'button',
              onclick: () => channelPrefDialog(c, (rest, patch) => updatePrefs(null, 'Chaîne mise à jour',
                (cur) => withChannelPref(cur, rest, patch))),
            }, icon('edit', 14), 'Modifier'),
            delBtn))
      })))
    }
    search.addEventListener('input', () => { chQuery = search.value; drawRows() })
    body.appendChild(h('div', { class: 'prefs-toolbar' },
      h('div', { class: 'filter-search' }, icon('search', 15), search), addBtn))
    drawRows()
    body.appendChild(results)
  }

  const dialog = openDialog(h('div', {},
    h('div', { class: 'dialog-head' },
      h('span', { class: 'kind-badge big' }, icon('sliders', 20)),
      h('div', {}, h('h2', {}, `Chaînes & catégories · ${playlist.name}`),
        h('p', { class: 'dialog-sub' }, 'Personnalisations chiffrées partagées sur tous tes appareils.')),
      h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Fermer', onclick: () => dialog.close() }, icon('x', 16))),
    body,
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn primary', type: 'button', onclick: () => dialog.close() }, 'Terminé'))), 'wide')
  void reload()
}

function renameCategoryDialog(displayName: string, origName: string, current: string, onSave: (name: string) => Promise<void>) {
  const input = h('input', { id: 'rc-name', value: current, placeholder: origName, maxlength: 80, autocomplete: 'off' })
  const save: HTMLButtonElement = h('button', { class: 'btn primary', type: 'submit' }, 'Enregistrer')
  const form = h('form', {
    onsubmit: async (e: Event) => {
      e.preventDefault()
      save.disabled = true
      save.setAttribute('aria-busy', 'true')
      try {
        await onSave(input.value.trim())
        dialog.close()
      } finally {
        save.disabled = false
        save.removeAttribute('aria-busy')
      }
    },
  },
    h('h2', {}, 'Renommer la catégorie'),
    h('p', { class: 'dialog-sub' }, origName !== displayName ? `Nom d’origine : ${origName}` : displayName),
    h('label', { for: 'rc-name' }, 'Nom affiché (vide = nom d’origine)'), input,
    h('div', { class: 'dialog-actions' },
      current ? h('button', { class: 'btn ghost', type: 'button', onclick: () => { input.value = ''; form.requestSubmit() } }, 'Rétablir') : null,
      h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler'), save))
  const dialog = openDialog(form, 'small')
  input.focus()
}

function channelPrefDialog(
  existing: SealedChannelPref | null,
  onSave: (rest: string, patch: Partial<Omit<SealedChannelPref, 'rest'>>) => Promise<void>,
) {
  const restInput = h('input', {
    id: 'cp-rest', value: existing?.rest ?? '', placeholder: 'ex. L:101', readOnly: Boolean(existing),
    class: 'mono-input', autocomplete: 'off', spellcheck: 'false',
  })
  const nameInput = h('input', { id: 'cp-name', value: existing?.name ?? '', placeholder: 'Nom affiché personnalisé', maxlength: 80, autocomplete: 'off' })
  const numInput = h('input', { id: 'cp-num', type: 'number', min: 0, max: 9999, value: existing?.number ? String(existing.number) : '', placeholder: '0 (auto)' })
  const epgInput = h('input', { id: 'cp-epg', value: existing?.epgKey ?? '', placeholder: 'ex. TF1.fr', autocomplete: 'off', spellcheck: 'false' })
  const hiddenCb = h('input', { id: 'cp-hidden', type: 'checkbox', checked: existing?.hidden ?? false })
  const lockedCb = h('input', { id: 'cp-locked', type: 'checkbox', checked: existing?.locked ?? false })
  const error = h('p', { class: 'form-error', role: 'alert' })
  const save: HTMLButtonElement = h('button', { class: 'btn primary', type: 'submit' }, 'Enregistrer')
  const form = h('form', {
    onsubmit: async (e: Event) => {
      e.preventDefault()
      error.textContent = ''
      const rest = (existing ? existing.rest : restInput.value).trim()
      if (!rest) { error.textContent = 'Identifiant de chaîne requis.'; restInput.focus(); return }
      const num = Math.max(0, Math.round(Number(numInput.value) || 0))
      save.disabled = true
      save.setAttribute('aria-busy', 'true')
      try {
        await onSave(rest, {
          name: nameInput.value.trim(), number: num, epgKey: epgInput.value.trim(),
          hidden: hiddenCb.checked, locked: lockedCb.checked,
        })
        dialog.close()
      } finally {
        save.disabled = false
        save.removeAttribute('aria-busy')
      }
    },
  },
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('tv', 20)),
      h('h2', {}, existing ? 'Modifier la chaîne' : 'Personnaliser une chaîne')),
    existing ? null : h('div', {}, h('label', { for: 'cp-rest' }, 'Identifiant technique (rest)'), restInput),
    h('label', { for: 'cp-name' }, 'Nom personnalisé'), nameInput,
    h('div', { class: 'two' },
      h('div', {}, h('label', { for: 'cp-num' }, 'Numéro'), numInput),
      h('div', {}, h('label', { for: 'cp-epg' }, 'Clé EPG (tvg-id)'), epgInput)),
    h('label', { class: 'check compact', for: 'cp-hidden' }, hiddenCb, h('span', {}, h('strong', {}, 'Masquer cette chaîne'))),
    h('label', { class: 'check compact', for: 'cp-locked' }, lockedCb, h('span', {}, h('strong', {}, 'Protéger par code parental'))),
    error,
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler'), save))
  const dialog = openDialog(form)
  ;(existing ? nameInput : restInput).focus()
}
