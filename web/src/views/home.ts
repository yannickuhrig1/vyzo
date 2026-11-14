import { append, h, icon } from '../dom'
import {
  castItemType, deviceName, isOnline, needsUpdate, progressRatio, recordingGroup, sortRecordings, subscriptionState,
  supportsRemote, todayScreenTime, upcomingRecordingAlert, updateInProgress,
  type Playlist, type ProgressItem, type ScreenTimeToday,
} from '../model'
import { avatar, barFill, dayLabel, duration, header, loading, longDayFmt, plural, relative, timeFmt } from '../ui'
import { backend, isHidden, softDelete, state } from '../context'
import { contentTitle, DEVICE_ICON, errorBanner, kindBadge, profilePicker, sectionTitle } from '../shared'
import { titlesNotice, unlockDialog } from '../vault-ui'
import { castButton } from '../cast'
import { announceDialog, messageDialog, playbackControlButtons, playingLine } from './devices'

export function homeView() {
  const now = new Date()
  const hour = now.getHours()
  const devices = (state.devices ?? []).filter((d) => !isHidden(`d:${d.id}`))
  const onlineDevices = devices.filter((d) => isOnline(d))
  const remoteOnline = onlineDevices.filter((d) => supportsRemote(d))

  const headActions = [
    state.vault === 'locked'
      ? h('button', { class: 'btn ghost', type: 'button', onclick: () => unlockDialog() }, icon('unlock', 15), 'Déverrouiller')
      : null,
    remoteOnline.length > 0
      ? h('button', { class: 'btn primary', type: 'button', onclick: () => announceDialog(remoteOnline) }, icon('message', 15), 'Annonce maison')
      : null,
  ]

  const box = h('section', {},
    h('p', { class: 'greeting' }, longDayFmt.format(now)),
    header(hour >= 18 || hour < 5 ? 'Bonsoir' : 'Bonjour', null, ...headActions))

  const outdated = devices.filter((d) => needsUpdate(d, state.latest) && !updateInProgress(d)).length
  const playlists = (state.playlists ?? []).filter((p) => !isHidden(`pl:${p.id}`))
  const recordings = (state.recordings ?? []).filter((r) => !isHidden(`r:${r.id}`))
  const upcoming = sortRecordings(recordings.filter((r) => recordingGroup(r) === 'upcoming'), 'upcoming')
  const next = upcoming[0]
  const kids = (state.profiles ?? []).filter((p) => p.isKid).length
  // L'abonnement qui demande le plus d'attention (expiré, puis le plus proche de sa fin).
  const worst = playlists
    .map((p) => ({ p, st: subscriptionState(p.status) }))
    .filter((x): x is { p: Playlist; st: NonNullable<ReturnType<typeof subscriptionState>> } => !!x.st && x.st.tone !== 'ok')
    .sort((a, b) => (a.st.daysLeft ?? -1) - (b.st.daysLeft ?? -1))[0]

  const recAlert = state.recordings && state.devices ? upcomingRecordingAlert(recordings, devices, now.getTime()) : null
  if (recAlert) {
    append(box, [h('div', { class: 'banner info' }, icon('info', 16),
      h('span', {}, `Aucun Vyzo PC en ligne alors que « ${recAlert.title || recAlert.channelName} » approche ou est en cours.`),
      h('a', { class: 'btn ghost small', href: '#/enregistrements' }, 'Voir'))])
  }

  const kpi = (href: string, iconName: string, label: string, value: Node | string, note: string, warn = false) =>
    h('a', { class: 'kpi', href },
      h('span', { class: 'kpi-label' }, icon(iconName, 14), label),
      h('span', { class: 'kpi-value' }, value),
      h('span', { class: `kpi-note ${warn ? 'warn' : ''}` }, note))

  append(box, [h('div', { class: 'kpis' },
    kpi('#/appareils', 'devices', 'Appareils en ligne',
      state.devices ? h('span', {}, String(onlineDevices.length), h('small', {}, `/ ${devices.length}`)) : '—',
      outdated > 0 ? `${plural(outdated, 'mise', 'mises')} à jour à faire` : devices.length ? 'Tous à jour' : 'Aucun appareil',
      outdated > 0),
    kpi('#/abonnements', 'key', 'Abonnements',
      state.vault === 'open' && state.playlists ? String(playlists.length) : '—',
      state.vault === 'locked' ? 'Verrouillés' : state.vault === 'missing' ? 'Aucun pour l’instant'
        : worst ? `${worst.p.name} · ${worst.st.label.toLowerCase()}` : 'Chiffrés de bout en bout',
      !!worst),
    kpi('#/profils', 'users', 'Profils',
      state.profiles ? String(state.profiles.length) : '—',
      kids ? plural(kids, 'profil enfant', 'profils enfant') : 'Aucun profil enfant'),
    kpi('#/enregistrements', 'record', 'Enregistrements',
      state.recordings ? String(upcoming.length) : '—',
      next ? `${dayLabel(next.startMs)} ${timeFmt.format(next.startMs)} · ${next.title || next.channelName}` : 'Aucun prévu'),
  )])

  // Colonne principale : reprendre + temps d'écran aujourd'hui
  const profile = state.profiles?.find((p) => p.id === state.selected) ?? null
  const main = h('div', {},
    sectionTitle('Reprendre', 'play', null, { href: '#/historique', label: 'Historique' }),
    profilePicker(() => null))
  const progress = state.progress?.profileId === state.selected ? state.progress.items : null
  if (!state.profiles || (profile && !progress && !state.errors.progress)) append(main, [loading(2)])
  else if (!profile) append(main, [h('p', { class: 'empty' }, 'Aucun profil sur ce compte.')])
  else if (progress) {
    const resume = progress
      .filter((x) => x.kind !== 'LIVE' && x.durationMs > 0 && progressRatio(x) < 0.95 && !isHidden(`p:${x.profileId}:${x.contentKey}`))
      .slice(0, 6)
    if (resume.length === 0) {
      append(main, [h('p', { class: 'empty' }, `Rien à reprendre pour ${profile.name}.`)])
    } else {
      append(main, [titlesNotice(resume), h('ul', { class: 'resume' }, resume.map((x) => resumeCard(x)))])
    }
  }

  append(main, [screenTimeTodaySection(now.getTime())])

  // Colonne de droite : appareils (avec contrôle direct) et prochains enregistrements
  const aside = h('div', {},
    sectionTitle('Appareils', 'devices', state.devices ? devices.length : null, { href: '#/appareils', label: 'Gérer' }))
  if (!state.devices) append(aside, [state.errors.devices ? errorBanner('devices') : loading(2)])
  else if (devices.length === 0) append(aside, [h('p', { class: 'empty small' }, 'Aucun appareil pour l’instant.')])
  else {
    const t = Date.now()
    const sorted = [...devices].sort((a, b) => Number(isOnline(b, t)) - Number(isOnline(a, t)) || b.lastSeenAt - a.lastSeenAt)
    append(aside, [h('ul', { class: 'rows' }, sorted.slice(0, 5).map((d) => {
      const on = isOnline(d, t)
      const controls = on ? playbackControlButtons(d, true) : []
      const msgBtn = on && supportsRemote(d) ? h('button', {
        class: 'icon-btn', type: 'button', title: 'Envoyer un message à l’écran',
        'aria-label': `Envoyer un message à ${deviceName(d)}`, onclick: () => messageDialog(d),
      }, icon('message', 14)) : null
      return h('li', { class: 'row-card compact' },
        h('span', { class: 'kind-badge' }, icon(DEVICE_ICON[d.kind] ?? 'devices', 16),
          h('span', { class: `presence ${on ? 'on' : ''}` })),
        h('div', { class: 'row-main' },
          h('div', { class: 'row-title' }, deviceName(d)),
          h('div', { class: 'row-sub' }, on ? playingLine(d) ?? h('span', { class: 'online' }, 'En ligne') : `Vu ${relative(d.lastSeenAt)}`)),
        needsUpdate(d, state.latest) ? h('span', { class: 'pill planned' }, 'Mise à jour') : null,
        (controls.length > 0 || msgBtn) ? h('div', { class: 'row-actions always' }, ...controls, msgBtn) : null)
    }))])
  }
  append(aside, [sectionTitle('À enregistrer', 'calendar', null, { href: '#/enregistrements', label: 'Tout voir' })])
  if (!state.recordings) append(aside, [state.errors.recordings ? null : loading(1)])
  else if (upcoming.length === 0) append(aside, [h('p', { class: 'empty small' }, 'Aucun enregistrement prévu.')])
  else {
    append(aside, [h('ul', { class: 'rows' }, upcoming.slice(0, 3).map((r) => h('li', { class: 'row-card compact' },
      h('div', { class: 'row-main' },
        h('div', { class: 'row-title' }, r.title || 'Programme sans titre'),
        h('div', { class: 'row-sub' }, `${dayLabel(r.startMs)} · ${timeFmt.format(r.startMs)} · ${r.channelName || 'Chaîne inconnue'}`)),
      r.status === 'RECORDING' ? h('span', { class: 'pill live' }, 'En cours') : null)))])
  }

  append(box, [h('div', { class: 'dash-grid' }, main, aside)])
  return box
}

function screenTimeTodaySection(now: number): HTMLElement | null {
  if (state.vault !== 'open' || !state.screentime || !state.profiles) return null
  const usage = state.usage ?? []
  const items = state.profiles
    .map((p) => ({ p, info: todayScreenTime(usage, p.id, state.screentime!.get(p.id), now) }))
    .filter((x): x is { p: NonNullable<typeof state.profiles>[number]; info: ScreenTimeToday } => !!x.info)
  if (items.length === 0) return null

  return h('div', {},
    sectionTitle('Temps d’écran aujourd’hui', 'clock', null, { href: '#/parental', label: 'Régler' }),
    h('ul', { class: 'rows' }, items.map(({ p, info }) => {
      const metric = info.limitMs !== null
        ? `${info.usedMs ? duration(info.usedMs) : '0 min'} / ${duration(info.limitMs)}`
        : `${info.usedMs ? duration(info.usedMs) : '0 min'} aujourd’hui`
      const badge = info.exceeded ? h('span', { class: 'pill bad' }, 'Limite atteinte')
        : !info.inWindow ? h('span', { class: 'pill planned' }, 'Hors plage')
          : info.windowLabel ? h('span', { class: 'pill ok' }, info.windowLabel) : null
      return h('li', { class: 'row-card compact' },
        avatar(p, 'sm'),
        h('div', { class: 'row-main' },
          h('div', { class: 'title-line' }, h('span', { class: 'row-title' }, p.name), badge),
          info.ratio !== null
            ? h('div', { class: 'progress-line' },
              h('span', { class: `bar ${info.exceeded ? 'over' : ''}` }, barFill(info.ratio)),
              h('span', { class: 'bar-label' }, metric))
            : h('div', { class: 'row-sub' }, metric)))
    })))
}

function resumeCard(x: ProgressItem) {
  const t = contentTitle(x)
  const ratio = progressRatio(x)
  const account = state.account!
  const remove = h('button', {
    class: 'icon-btn', type: 'button', title: 'Retirer de Reprendre', 'aria-label': `Retirer ${t.text} de l’historique`,
    onclick: () => softDelete(`p:${x.profileId}:${x.contentKey}`, t.known ? `« ${t.text} » retiré de l’historique` : 'Retiré de l’historique',
      () => backend.removeProgress(account, x.profileId, [x.contentKey]), ['progress', 'activity']),
  }, icon('x', 14))
  return h('li', { class: 'resume-card' },
    h('div', { class: 'resume-top' }, kindBadge(x.kind),
      h('div', { class: 'resume-actions' },
        castButton({ contentKey: x.contentKey, itemType: castItemType(x.kind, true), title: x.title, positionMs: x.positionMs }, t.text),
        remove)),
    h('div', {},
      h('div', { class: `resume-title ${t.known ? '' : 'unknown'}` }, t.text),
      h('div', { class: 'resume-sub' }, x.subtitle || relative(x.updatedAt))),
    h('div', { class: 'resume-foot' },
      h('span', { class: 'bar' }, barFill(ratio)),
      h('span', { class: 'bar-label' }, `reste ${duration(x.durationMs - x.positionMs)}`)))
}
