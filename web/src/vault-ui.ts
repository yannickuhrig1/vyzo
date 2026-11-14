import { friendlyError, UserError, type BackupData } from './backend'
import { h, icon } from './dom'
import { type BackupCounts } from './model'
import { act, confirmDialog, emptyCard, fullFmt, openDialog, toast } from './ui'
import { backend, ensureRouteData, flushDeletes, refresh, renderView, resetStateForRestore, state } from './context'
import { backupCountsOf, busy, countsText, passwordWithReveal, rememberBox } from './shared'

/** Formulaire du mot de passe du compte, qui ouvre la clé des données chiffrées. */
export function unlockForm(onDone: () => void) {
  const password = h('input', { id: 'vault-pass', type: 'password', autocomplete: 'current-password', required: true })
  const error = h('p', { class: 'form-error', role: 'alert' })
  const submit = h('button', { class: 'btn primary', type: 'submit' }, icon('unlock', 15), 'Déverrouiller')
  const stay = rememberBox('vault-remember')
  const old = h('input', { id: 'vault-old', type: 'password', autocomplete: 'off' })
  const oldBox = h('div', { class: 'unlock-old', hidden: true },
    h('p', {},
      'C’est bien le mot de passe du compte, mais tes données chiffrées sont encore protégées par l’ancien. Saisis l’ancien mot de passe une seule fois : elles passeront au nouveau, ici et sur tes appareils.'),
    h('label', { for: 'vault-old' }, 'Ancien mot de passe'), passwordWithReveal(old))
  const form = h('form', {
    class: 'unlock-form',
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      const account = state.account!
      if (!oldBox.hidden) {
        if (!await busy(submit, error, () => backend.recoverVault(account, password.value, old.value, stay.input.checked))) {
          old.select()
          return
        }
        old.value = ''
        oldBox.hidden = true
      } else {
        let ok = false
        let current = false
        if (!await busy(submit, error, async () => {
          ok = await backend.unlockVault(account, password.value, stay.input.checked)
          if (!ok) current = await backend.checkPassword(account, password.value)
        })) return
        if (!ok && current) {
          oldBox.hidden = false
          old.focus()
          return
        }
        if (!ok) {
          error.textContent = 'Mot de passe incorrect.'
          password.select()
          return
        }
      }
      password.value = ''
      toast(stay.input.checked ? 'Déverrouillé, et gardé sur cet ordinateur' : 'Données chiffrées déverrouillées')
      onDone()
      await refresh(['playlists', ...(state.progress ? ['progress' as const] : []), ...(state.lists ? ['lists' as const] : [])])
      ensureRouteData()
    },
  },
    h('label', { for: 'vault-pass' }, 'Mot de passe du compte Vyzo'),
    h('div', { class: 'inline-field' }, passwordWithReveal(password), submit),
    oldBox,
    stay.box,
    error,
    h('p', { class: 'fine' },
      'Le déchiffrement se fait dans ce navigateur. Sans la case, la clé reste en mémoire et s’oublie au rechargement de la page.'))
  return { form, focus: () => password.focus() }
}

export function unlockDialog() {
  let dialog: HTMLDialogElement
  const { form, focus } = unlockForm(() => dialog.close())
  dialog = openDialog(h('div', {},
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('lock', 20)), h('h2', {}, 'Déverrouiller')),
    form))
  focus()
}

export async function lockNow() {
  await flushDeletes()
  const wasRemembered = state.remembered
  await backend.lockVault()
  state.vault = 'locked'
  state.remembered = false
  state.playlists = null
  state.epg = null
  state.apiKeys = null
  state.syncedSettings = undefined
  state.parental = undefined
  state.catalog = null
  state.screentime = null
  state.usage = null
  state.playback.clear()
  if (state.progress) state.progress.items = state.progress.items.map((x) => ({ ...x, title: '', subtitle: '' }))
  if (state.lists) state.lists.items = state.lists.items.map((x) => ({ ...x, title: '', subtitle: '' }))
  renderView()
  toast(wasRemembered ? 'Verrouillé : la clé n’est plus gardée sur cet ordinateur' : 'Données chiffrées verrouillées')
}

export function vaultGate(title = 'Abonnements chiffrés',
  text = 'Tes identifiants IPTV sont chiffrés avec le mot de passe du compte : le serveur Vyzo ne peut pas les lire. Saisis-le pour les afficher et les modifier.') {
  if (state.vault === 'missing') {
    return emptyCard('key', 'Rien de chiffré sur ce compte pour l’instant',
      'Connecte Vyzo au compte sur un de tes appareils : il crée la clé du compte et y range tes abonnements. Ils apparaîtront ici ensuite.')
  }
  const { form } = unlockForm(() => {})
  return h('div', { class: 'gate' },
    h('span', { class: 'kind-badge big' }, icon('lock', 20)),
    h('div', { class: 'gate-main' },
      h('h2', {}, title),
      h('p', {}, text),
      form))
}

/** Bandeau des sections où les titres sont chiffrés. */
export function titlesNotice(items: { title: string }[]) {
  if (items.length === 0) return null
  if (state.vault === 'locked') {
    return h('div', { class: 'banner info' }, icon('lock'),
      h('span', {}, 'Les titres sont chiffrés avec le mot de passe du compte. Déverrouille pour les afficher.'),
      h('button', { class: 'btn ghost small', type: 'button', onclick: () => unlockDialog() }, icon('unlock', 15), 'Déverrouiller'))
  }
  if (state.vault === 'open' && items.some((x) => !x.title)) {
    return h('p', { class: 'note' }, icon('info', 16), h('span', {},
      'Certains titres ne sont pas encore connus du compte. Ils arrivent quand un appareil à jour se synchronise.'))
  }
  return null
}

export function backupDialog() {
  if (state.vault !== 'open') return unlockDialog()
  const account = state.account!
  const pw = h('input', { id: 'bk-pw', type: 'password', autocomplete: 'new-password', minlength: 8, required: true })
  const again = h('input', { id: 'bk-again', type: 'password', autocomplete: 'new-password', minlength: 8, required: true })
  const error = h('p', { class: 'form-error', role: 'alert' })
  const go_: HTMLButtonElement = h('button', { class: 'btn primary', type: 'submit' }, icon('download', 14), 'Créer le fichier')
  const form = h('form', {
    onsubmit: async (e: Event) => {
      e.preventDefault()
      error.textContent = ''
      if (pw.value.length < 8) { error.textContent = 'Au moins 8 caractères.'; return pw.focus() }
      if (pw.value !== again.value) { error.textContent = 'Les deux mots de passe ne correspondent pas.'; return again.focus() }
      let made: { file: string; counts: BackupCounts } | null = null
      if (!await act(go_, async () => { made = await backend.exportBackup(account, pw.value) })) return
      const { file, counts } = made!
      const url = URL.createObjectURL(new Blob([file], { type: 'application/json' }))
      const a = h('a', { href: url, download: `vyzo-sauvegarde-${new Date().toISOString().slice(0, 10)}.json` })
      document.body.appendChild(a)
      a.click()
      a.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 30_000)
      dialog.close()
      toast(`Sauvegarde téléchargée : ${countsText(counts)}`)
      if (state.events) void refresh(['events'])
    },
  },
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('download', 20)),
      h('div', {}, h('h2', {}, 'Sauvegarde du compte'), h('p', { class: 'dialog-sub' }, 'Un fichier chiffré, à garder en lieu sûr.'))),
    h('div', { class: 'two' },
      h('div', {}, h('label', { for: 'bk-pw' }, 'Mot de passe du fichier'), passwordWithReveal(pw)),
      h('div', {}, h('label', { for: 'bk-again' }, 'Confirmer'), passwordWithReveal(again))),
    h('p', { class: 'fine' }, 'Il contient tes identifiants d’abonnements IPTV : sans ce mot de passe, personne ne peut le lire, pas même toi.'),
    error,
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler'), go_))
  const dialog = openDialog(form)
  pw.focus()
}

export function restoreDialog() {
  if (state.vault !== 'open') return unlockDialog()
  const account = state.account!
  const file = h('input', { id: 'rs-file', type: 'file', accept: '.json,application/json', required: true })
  const pw = h('input', { id: 'rs-pw', type: 'password', autocomplete: 'off', required: true })
  const error = h('p', { class: 'form-error', role: 'alert' })
  const go_: HTMLButtonElement = h('button', { class: 'btn primary', type: 'submit' }, 'Ouvrir')
  const form = h('form', {
    onsubmit: async (e: Event) => {
      e.preventDefault()
      error.textContent = ''
      const f = file.files?.[0]
      if (!f) return file.focus()
      if (f.size > 50 * 1024 * 1024) { error.textContent = 'Fichier trop gros pour une sauvegarde Vyzo.'; return }
      go_.setAttribute('aria-busy', 'true')
      go_.disabled = true
      let data: BackupData | null = null
      try {
        data = await backend.readBackup(await f.text(), pw.value)
        if (!data) { error.textContent = 'Mot de passe incorrect pour ce fichier.'; return pw.focus() }
      } catch (err) {
        error.textContent = err instanceof UserError ? err.message : friendlyError(err)
        return
      } finally {
        go_.removeAttribute('aria-busy')
        go_.disabled = false
      }
      dialog.close()
      const ok = await confirmDialog({
        title: 'Restaurer cette sauvegarde ?',
        body: `Sauvegarde du ${data.createdAt ? fullFmt.format(data.createdAt) : 'jour inconnu'} : ${countsText(backupCountsOf(data))}. ` +
          'Ce qu’elle contient remplace la version actuelle sur tous tes appareils ; ce qui a été ajouté depuis est gardé.',
        confirm: 'Restaurer',
      })
      if (!ok) return
      const restored = data
      if (await act(null, () => backend.restoreBackup(account, restored).then(() => undefined), 'Sauvegarde restaurée')) {
        resetStateForRestore(account)
        await refresh(['profiles', 'activity', 'recordings', 'devices', 'playlists'])
        ensureRouteData()
      }
    },
  },
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('upload', 20)),
      h('div', {}, h('h2', {}, 'Restaurer une sauvegarde'), h('p', { class: 'dialog-sub' }, 'Tu pourras vérifier son contenu avant.'))),
    h('label', { for: 'rs-file' }, 'Fichier de sauvegarde'), file,
    h('label', { for: 'rs-pw' }, 'Mot de passe du fichier'), passwordWithReveal(pw),
    error,
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler'), go_))
  const dialog = openDialog(form, 'small')
}
