import { avatarSvg } from './avatars'
import { friendlyError, type Account, type PasswordResetResult } from './backend'
import { append, clear, h, icon } from './dom'
import { emailLinkError, MIN_PASSWORD, type EmailLinkParams } from './model'
import { backend, enter } from './context'
import { appRoot, busy, linkButton, passwordWithReveal, rememberBox, themeSwitch } from './shared'

/** Cadre commun des pages sans compte ouvert (connexion, mot de passe oublié, liens reçus par e-mail). */
function authPage(pageTitle: string, card: HTMLElement, aside?: HTMLElement) {
  const root = appRoot()
  clear(root)
  document.title = `${pageTitle} · Vyzo`
  card.classList.add('auth-card')
  const topbar = h('header', { class: 'auth-topbar' },
    h('div', { class: 'auth-brand' },
      h('img', { src: 'icon.svg', alt: '', width: 28, height: 28 }),
      'Vyzo',
      h('span', { class: 'auth-pill' }, 'Espace compte'),
      backend.demo ? h('span', { class: 'demo-tag' }, 'démo') : null),
    themeSwitch())
  const body = aside
    ? h('div', { class: 'auth-grid' }, aside, card)
    : h('div', { class: 'auth-single' }, card)
  append(root, [h('main', { class: 'auth-shell' }, topbar, body)])
}

const notConfirmed = (e: unknown) => {
  const err = e as { code?: string; message?: string }
  return err?.code === 'email_not_confirmed' || /Email not confirmed/i.test(err?.message ?? '')
}

export function renderLogin(message?: string, prefill = '') {
  const email = h('input', { id: 'email', type: 'email', autocomplete: 'username', required: true, spellcheck: 'false', value: prefill })
  const password = h('input', { id: 'password', type: 'password', autocomplete: 'current-password', required: true })
  const error = h('p', { class: 'form-error', role: 'alert' }, message ?? '')
  const submit = h('button', { class: 'btn primary wide', type: 'submit' }, 'Se connecter')
  const stay = rememberBox('login-remember')
  const resend = h('div', { class: 'auth-extra', hidden: true })

  const sampleChip = (label: string, tone: string, glyph: Node | string) =>
    h('span', { class: 'auth-chip' },
      h('span', { class: `auth-chip-dot ${tone}`, 'aria-hidden': 'true' }, glyph),
      h('span', {}, label))

  const aside = h('section', { class: 'auth-context' },
    h('div', {},
      h('p', { class: 'auth-kicker' }, 'Gestion du foyer'),
      h('h1', {}, 'Tes écrans, tes profils et tes abonnements au même endroit.'),
      h('p', { class: 'lead' }, 'Gère depuis le navigateur les appareils du salon, les profils du foyer et les enregistrements programmés. Tes listes IPTV restent chiffrées sur tes appareils.')),
    h('div', { class: 'auth-eco' },
      h('div', { class: 'auth-eco-head' },
        h('span', {}, 'Aperçu de l’espace synchronisé'),
        h('span', { class: 'mono' }, 'AES-256-GCM')),
      h('div', { class: 'auth-profiles' },
        sampleChip('Yannick', 'amber', avatarSvg('astronaut', 13) ?? 'Y'),
        sampleChip('Léa · Enfant', 'cyan', avatarSvg('star', 13) ?? 'L'),
        sampleChip('Marc', 'violet', 'M')),
      h('div', { class: 'auth-devices' },
        h('div', { class: 'auth-device' }, h('div', { class: 'auth-device-name' }, 'TV Salon'), h('div', { class: 'auth-device-meta' }, 'Android TV · à jour')),
        h('div', { class: 'auth-device' }, h('div', { class: 'auth-device-name' }, 'Téléphone'), h('div', { class: 'auth-device-meta' }, 'Mobile · synchro OK')),
        h('div', { class: 'auth-device' }, h('div', { class: 'auth-device-name' }, 'PC Bureau'), h('div', { class: 'auth-device-meta' }, 'Enregistreur prêt')))))

  const form = h('form', {
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      error.textContent = ''
      resend.hidden = true
      submit.disabled = true
      submit.setAttribute('aria-busy', 'true')
      try {
        const account = await backend.signIn(email.value, password.value, stay.input.checked)
        password.value = ''
        await enter(account)
      } catch (e) {
        error.textContent = friendlyError(e)
        if (notConfirmed(e)) showResend(email.value)
        submit.disabled = false
        submit.removeAttribute('aria-busy')
        password.focus()
      }
    },
  },
    h('h2', {}, 'Connexion'),
    h('p', { class: 'lead' }, 'Identifiants du compte créé dans l’application.'),
    h('label', { for: 'email' }, 'Adresse e-mail'), email,
    h('div', { class: 'label-row' },
      h('label', { for: 'password' }, 'Mot de passe'),
      linkButton('Mot de passe oublié ?', () => renderForgot(email.value))),
    passwordWithReveal(password),
    stay.box,
    error,
    resend,
    submit,
    h('div', { class: 'auth-foot' },
      h('p', { class: 'fine' },
        'Pas encore de compte ? Ouvre Vyzo sur ta TV, ton téléphone ou ton PC puis va dans ',
        h('strong', {}, 'Réglages › Compte Vyzo'),
        '. Un e-mail te demandera de confirmer l’adresse.'),
      import.meta.env.DEV && !backend.demo
        ? h('p', { class: 'fine' }, h('a', { href: '?demo' }, 'Ouvrir la démo (données fictives)'))
        : null),
  )

  function showResend(address: string) {
    const button = h('button', { class: 'btn outline small', type: 'button' }, 'Renvoyer l’e-mail de confirmation')
    const note = h('p', { class: 'form-note', role: 'status' })
    button.addEventListener('click', async () => {
      if (await busy(button, error, () => backend.resendConfirmation(address)))
        note.textContent = `E-mail renvoyé à ${address.trim()}. Pense à regarder dans les indésirables.`
    })
    clear(resend)
    append(resend, [button, note])
    resend.hidden = false
  }

  authPage('Connexion', form, aside)
  if (prefill) password.focus()
  else email.focus()
}

/** Mot de passe oublié : demande du lien. [message] : lien précédent refusé. */
function renderForgot(prefill = '', message?: string) {
  const email = h('input', { id: 'forgot-email', type: 'email', autocomplete: 'username', required: true, spellcheck: 'false', value: prefill.trim() })
  const error = h('p', { class: 'form-error', role: 'alert' })
  const submit = h('button', { class: 'btn primary wide', type: 'submit' }, 'Recevoir le lien')
  const form: HTMLFormElement = h('form', {
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      const address = email.value.trim()
      if (await busy(submit, error, () => backend.requestPasswordReset(address))) renderMailSent(address)
    },
  },
    h('h1', {}, 'Mot de passe oublié'),
    message ? h('p', { class: 'form-error', role: 'alert' }, message) : null,
    h('p', { class: 'lead' }, 'Saisis l’adresse du compte : tu recevras un lien pour choisir un nouveau mot de passe.'),
    h('label', { for: 'forgot-email' }, 'Adresse e-mail'), email,
    error,
    submit,
    h('p', { class: 'fine' }, linkButton('Retour à la connexion', () => renderLogin(undefined, email.value))),
  )
  authPage('Mot de passe oublié', form)
  email.focus()
}

function renderMailSent(address: string) {
  const back = h('button', { class: 'btn outline wide', type: 'button', onclick: () => renderLogin(undefined, address) }, 'Retour à la connexion')
  authPage('E-mail envoyé', h('div', {},
    h('div', { class: 'auth-badge' }, icon('mail', 22)),
    h('h1', {}, 'Regarde tes e-mails'),
    h('p', { class: 'lead' }, `Si un compte Vyzo existe pour ${address}, un e-mail vient de partir. Ouvre le lien qu’il contient, sur cet ordinateur ou sur ton téléphone : il est valable une heure et ne sert qu’une fois.`),
    h('p', { class: 'fine' }, 'Rien après quelques minutes ? Regarde dans les indésirables, puis redemande un lien.'),
    back,
    h('p', { class: 'fine' }, linkButton('Redemander un lien', () => renderForgot(address))),
  ))
  back.focus()
}

/** Arrivée depuis un lien d'e-mail : on le vérifie, puis la suite dépend de son type. */
export async function openEmailLink(link: EmailLinkParams) {
  authPage('Vérification', h('div', {}, h('h1', {}, 'Vérification du lien…'), h('p', { class: 'lead' }, 'Un instant.')))
  if (link.mode === 'error') {
    renderForgot('', emailLinkError(link.code))
    return
  }
  try {
    const { type, account } = await backend.verifyEmailLink(link)
    if (type === 'recovery') renderNewPassword(account)
    else if (type === 'signup') {
      await backend.signOut().catch(() => undefined)
      renderConfirmed(account.email)
    } else {
      await backend.signOut().catch(() => undefined)
      renderEmailChanged(account.email)
    }
  } catch (e) {
    renderForgot('', friendlyError(e))
  }
}

function renderConfirmed(address: string) {
  const go = h('button', { class: 'btn primary wide', type: 'button', onclick: () => renderLogin(undefined, address) }, 'Se connecter sur ce site')
  authPage('Adresse confirmée', h('div', {},
    h('div', { class: 'auth-badge ok' }, icon('check', 22)),
    h('h1', {}, 'Adresse confirmée'),
    h('p', { class: 'lead' }, `Le compte ${address} est prêt. Reviens dans Vyzo, sur la TV, le téléphone ou le PC, et connecte-toi avec cette adresse et ton mot de passe.`),
    go,
    h('p', { class: 'fine' }, 'Tes playlists et réglages sont chiffrés sur tes appareils : le serveur ne peut pas les lire.'),
  ))
  go.focus()
}

function renderEmailChanged(address: string) {
  const go = h('button', { class: 'btn primary wide', type: 'button', onclick: () => renderLogin(undefined, address) }, 'Se connecter')
  authPage('Adresse modifiée', h('div', {},
    h('div', { class: 'auth-badge ok' }, icon('check', 22)),
    h('h1', {}, 'Adresse confirmée'),
    h('p', { class: 'lead' }, `Le compte utilise maintenant ${address}. Si un e-mail est aussi arrivé sur l’autre adresse, ouvre son lien pour terminer.`),
    go,
  ))
  go.focus()
}

function renderNewPassword(account: Account) {
  const pass1 = h('input', { id: 'new-password', type: 'password', autocomplete: 'new-password', required: true, minlength: MIN_PASSWORD })
  const pass2 = h('input', { id: 'new-password-2', type: 'password', autocomplete: 'new-password', required: true })
  const old = h('input', { id: 'old-password', type: 'password', autocomplete: 'current-password' })
  const error = h('p', { class: 'form-error', role: 'alert' })
  const submit = h('button', { class: 'btn primary wide', type: 'submit' }, 'Enregistrer le mot de passe')
  const form = h('form', {
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      error.textContent = ''
      if (pass1.value.length < MIN_PASSWORD) {
        error.textContent = `Au moins ${MIN_PASSWORD} caractères`
        return
      }
      if (pass1.value !== pass2.value) {
        error.textContent = 'Les deux mots de passe ne sont pas identiques'
        pass2.focus()
        return
      }
      let result: PasswordResetResult = { vault: 'none', othersSignedOut: true }
      const next = pass1.value
      if (await busy(submit, error, async () => { result = await backend.resetPassword(account, next, old.value) })) {
        pass1.value = ''
        pass2.value = ''
        old.value = ''
        renderPasswordReset(account, result)
      }
    },
  },
    h('h1', {}, 'Nouveau mot de passe'),
    h('p', { class: 'lead' }, `Pour le compte ${account.email}.`),
    h('label', { for: 'new-password' }, 'Nouveau mot de passe'), passwordWithReveal(pass1),
    h('label', { for: 'new-password-2' }, 'Encore une fois'), passwordWithReveal(pass2),
    h('label', { for: 'old-password' }, 'Ancien mot de passe (facultatif)'), passwordWithReveal(old),
    h('p', { class: 'fine' }, 'Tes abonnements et réglages chiffrés sont protégés par l’ancien mot de passe. Si tu t’en souviens, saisis-le : ils passeront au nouveau tout de suite. Sinon, tu pourras le faire plus tard, ou un appareil déjà connecté s’en chargera.'),
    error,
    submit,
    h('p', { class: 'fine' }, 'Tous les appareils et navigateurs connectés au compte seront déconnectés : chacun te redemandera le nouveau mot de passe.'),
  )
  authPage('Nouveau mot de passe', form)
  pass1.focus()
}

function renderPasswordReset(account: Account, { vault, othersSignedOut }: PasswordResetResult) {
  const go = h('button', { class: 'btn primary wide', type: 'button', onclick: () => void enter(account) }, 'Ouvrir mon compte')
  const vaultText = vault === 'kept'
    ? 'Tes abonnements et réglages chiffrés restent lisibles : ils sont maintenant protégés par le nouveau mot de passe.'
    : vault === 'pending'
      ? 'Tes abonnements et réglages chiffrés sont encore protégés par l’ancien mot de passe. Pour les passer au nouveau : dans le compte, choisis « Déverrouiller » et saisis l’ancien quand le site le demande, ou reconnecte-toi avec le nouveau sur une TV, un téléphone ou un PC où Vyzo était déjà connecté.'
      : null
  authPage('Mot de passe changé', h('div', {},
    h('div', { class: 'auth-badge ok' }, icon('check', 22)),
    h('h1', {}, 'Mot de passe changé'),
    othersSignedOut
      ? h('p', { class: 'lead' }, 'Les autres appareils et navigateurs ont été déconnectés. Chacun te redemandera le nouveau mot de passe.')
      : h('p', { class: 'auth-warn' }, 'Les autres appareils et navigateurs n’ont pas pu être déconnectés. Une fois dans le compte, ouvre Sécurité et choisis « Déconnecter les autres appareils ».'),
    vaultText ? h('p', { class: vault === 'kept' ? 'form-note' : 'auth-warn' }, vaultText) : null,
    go,
  ))
  go.focus()
}
