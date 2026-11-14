import { friendlyError, UserError } from '../backend'
import { append, h, icon } from '../dom'
import { lastBackupAt } from '../model'
import { savedTheme, THEMES } from '../theme'
import { act, confirmDialog, fullFmt, relative, toast } from '../ui'
import { backend, canInstallApp, installApp, refresh, renderView, setTheme, state } from '../context'
import { pageHeader, panel, passwordWithReveal } from '../shared'
import { backupDialog, lockNow, restoreDialog, unlockDialog } from '../vault-ui'

export function securityView() {
  const account = state.account!
  const box = h('section', {}, pageHeader('securite'))

  // Compte
  const vaultText = state.vault === 'open' ? 'Déverrouillées dans cette page'
    : state.vault === 'locked' ? 'Verrouillées' : state.vault === 'missing' ? 'Aucune pour l’instant' : '…'
  const vaultAction = state.vault === 'open'
    ? h('button', { class: 'btn ghost small', type: 'button', onclick: () => void lockNow() }, icon('lock', 14), 'Verrouiller')
    : state.vault === 'locked'
      ? h('button', { class: 'btn ghost small', type: 'button', onclick: () => unlockDialog() }, icon('unlock', 14), 'Déverrouiller')
      : null
  const forget = state.remembered ? h('button', {
    class: 'btn ghost small', type: 'button',
    onclick: async (e: Event) => {
      if (await act(e.currentTarget as HTMLButtonElement, () => backend.forgetRemembered(), 'Clé oubliée sur cet ordinateur')) {
        state.remembered = false
        renderView()
      }
    },
  }, 'Oublier') : null

  const compte = panel('Compte', 'Le compte qui relie la TV, le téléphone et le PC.', [
    h('dl', { class: 'kv' },
      h('dt', {}, 'Adresse e-mail'), h('dd', {}, account.email),
      h('dt', {}, 'Données chiffrées'), h('dd', { class: 'row' }, h('span', {}, vaultText), vaultAction),
      h('dt', {}, 'Sur cet ordinateur'), h('dd', { class: 'row' },
        h('span', {}, state.remembered ? 'Clé gardée (« Rester déverrouillé »)' : 'Rien de gardé'), forget)),
  ])

  // Mot de passe
  const current = h('input', { id: 'pw-current', type: 'password', autocomplete: 'current-password', required: true })
  const next = h('input', { id: 'pw-new', type: 'password', autocomplete: 'new-password', required: true, minlength: 8 })
  const again = h('input', { id: 'pw-again', type: 'password', autocomplete: 'new-password', required: true, minlength: 8 })
  const pwError = h('p', { class: 'form-error', role: 'alert' })
  const pwSubmit = h('button', { class: 'btn primary', type: 'submit' }, 'Changer le mot de passe')
  const pwForm = h('form', {
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      pwError.textContent = ''
      if (next.value.length < 8) { pwError.textContent = 'Le nouveau mot de passe doit faire au moins 8 caractères.'; next.focus(); return }
      if (next.value !== again.value) { pwError.textContent = 'Les deux nouveaux mots de passe ne correspondent pas.'; again.focus(); return }
      if (next.value === current.value) { pwError.textContent = 'Le nouveau mot de passe est identique à l’actuel.'; next.focus(); return }
      pwSubmit.setAttribute('aria-busy', 'true')
      pwSubmit.disabled = true
      try {
        await backend.changePassword(account, current.value, next.value)
        current.value = ''; next.value = ''; again.value = ''
        toast('Mot de passe changé')
        await refresh(['playlists'])
      } catch (e) {
        pwError.textContent = e instanceof UserError ? e.message : friendlyError(e)
      } finally {
        pwSubmit.removeAttribute('aria-busy')
        pwSubmit.disabled = false
      }
    },
  },
    h('label', { for: 'pw-current' }, 'Mot de passe actuel'), passwordWithReveal(current),
    h('div', { class: 'two' },
      h('div', {}, h('label', { for: 'pw-new' }, 'Nouveau mot de passe'), passwordWithReveal(next)),
      h('div', {}, h('label', { for: 'pw-again' }, 'Confirmer'), passwordWithReveal(again))),
    pwError,
    h('div', { class: 'form-actions' }, pwSubmit))
  const motDePasse = panel('Mot de passe', 'Il protège aussi la clé de tes données chiffrées : elle est rechiffrée avec le nouveau.',
    [pwForm], [icon('info', 14), 'Tes appareils restent connectés. Sur un nouvel appareil, utilise le nouveau mot de passe.'])

  // Sessions
  const others = h('button', {
    class: 'btn danger-ghost', type: 'button',
    onclick: async () => {
      const ok = await confirmDialog({
        title: 'Déconnecter les autres appareils ?',
        body: 'Box, téléphones, Vyzo PC et autres navigateurs sont déconnectés du compte. Ils devront s’y reconnecter avec le mot de passe. Cette page reste connectée.',
        confirm: 'Déconnecter', danger: true,
      })
      if (ok) await act(others, () => backend.signOutOthers(), 'Les autres sessions sont fermées')
    },
  }, icon('logout', 15), 'Déconnecter les autres appareils')
  const sessions = panel('Sessions', 'Un appareil perdu, vendu ou prêté ? Ferme toutes les sessions sauf celle-ci.', [h('div', { class: 'form-actions start' }, others)], undefined, true)

  // Affichage
  const current_ = savedTheme()
  const themes = h('div', { class: 'segmented theme-grid', role: 'radiogroup', 'aria-label': 'Thème' },
    THEMES.map((t) => h('button', {
      type: 'button', role: 'radio', 'data-theme-choice': t.id, 'aria-checked': String(t.id === current_), title: t.hint,
      onclick: () => setTheme(t.id),
    }, t.label)))
  const affichage = panel('Affichage', 'Sobre, Cinéma, Clair, Nuit TV, OLED ou Sable. Le choix reste sur cet ordinateur.', [themes])

  // Sauvegarde
  const open = state.vault === 'open'
  const lastBk = lastBackupAt(state.events)
  const backupNote = lastBk
    ? `Dernière sauvegarde exportée le ${fullFmt.format(lastBk)} (${relative(lastBk)}).`
    : state.events ? 'Aucune sauvegarde exportée au cours des 6 derniers mois.' : null
  const sauvegarde = panel('Sauvegarde du compte',
    'Profils, listes, historique, abonnements, contrôle parental et réglages dans un seul fichier, chiffré avec un mot de passe que tu choisis.',
    [
      backupNote ? h('p', { class: 'fine' }, backupNote) : null,
      h('div', { class: 'row' },
        h('button', { class: 'btn primary', type: 'button', disabled: !open, onclick: () => backupDialog() }, icon('download', 15), 'Télécharger une sauvegarde'),
        h('button', { class: 'btn ghost', type: 'button', disabled: !open, onclick: () => restoreDialog() }, icon('upload', 15), 'Restaurer')),
    ],
    [icon('info', 14), open ? 'Garde le fichier et son mot de passe en lieu sûr : sans lui, la sauvegarde ne s’ouvre pas.'
      : 'Déverrouille d’abord les données chiffrées (mot de passe du compte).'])

  // Installation
  const standalone = window.matchMedia('(display-mode: standalone)').matches
  const ios = /iPhone|iPad/.test(navigator.userAgent)
  const installation = panel('Application', standalone ? 'Le site est ouvert comme une application.'
    : 'Ajoute le site à ton bureau ou à l’écran d’accueil du téléphone : il s’ouvre dans sa propre fenêtre.',
  [standalone ? h('p', { class: 'row' }, icon('check', 15), 'Installé sur cet appareil')
    : canInstallApp() ? h('button', { class: 'btn primary', type: 'button', onclick: () => void installApp() }, icon('install', 15), 'Installer')
      : h('p', { class: 'fine' }, ios ? 'Sur iPhone et iPad : bouton Partager de Safari, puis « Sur l’écran d’accueil ».'
        : 'Dans Chrome ou Edge : menu ⋮, puis « Installer Mon compte Vyzo » (ou « Ajouter à l’écran d’accueil »).')])

  append(box, [h('div', { class: 'settings' }, compte, motDePasse, sessions, sauvegarde, installation, affichage)])
  return box
}
