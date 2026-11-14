import { AVATAR_CHOICES, avatarSvg } from '../avatars'
import { append, clear, h, icon } from '../dom'
import {
  MAX_PROFILE_NAME, PROFILE_PALETTE, cleanAvatar, cleanName, hashPin, isValidPin, moveProfile, nextColor, type Profile,
} from '../model'
import { act, avatar, confirmDialog, loading, openDialog } from '../ui'
import { backend, refresh, state } from '../context'
import { errorBanner, pageHeader } from '../shared'

export function profilesView() {
  const profiles = state.profiles
  const add = h('button', { class: 'btn primary', type: 'button', onclick: () => editProfile(null) },
    icon('plus', 16), 'Ajouter un profil')
  const box = h('section', {}, pageHeader('profils', add), errorBanner('profiles'))
  if (!profiles) return append(box, [state.errors.profiles ? null : loading()]), box

  const grid = h('ul', { class: 'profile-grid' })
  profiles.forEach((p, i) => {
    const up = h('button', {
      class: 'icon-btn', type: 'button', title: 'Monter', 'aria-label': `Monter ${p.name}`, disabled: i === 0,
      onclick: (e: Event) => void move(e.currentTarget as HTMLButtonElement, profiles, p.id, -1),
    }, icon('up', 15))
    const down = h('button', {
      class: 'icon-btn', type: 'button', title: 'Descendre', 'aria-label': `Descendre ${p.name}`, disabled: i === profiles.length - 1,
      onclick: (e: Event) => void move(e.currentTarget as HTMLButtonElement, profiles, p.id, 1),
    }, icon('down', 15))
    const a = state.activity?.find((x) => x.profileId === p.id)
    grid.appendChild(h('li', { class: 'profile-card' },
      avatar(p),
      h('div', { class: 'profile-name' }, p.name),
      h('div', { class: 'tags' },
        p.isKid ? h('span', { class: 'tag' }, icon('child', 12), 'Enfant') : null,
        p.pinHash ? h('span', { class: 'tag' }, icon('lock', 12), 'Code') : null),
      a ? h('div', { class: 'profile-stats' },
        `${a.inProgress} en cours · ${a.inLists} dans les listes`) : null,
      h('div', { class: 'card-actions' },
        h('button', { class: 'btn ghost small', type: 'button', onclick: () => editProfile(p) }, icon('edit', 14), 'Modifier'),
        h('span', { class: 'order' }, up, down)),
    ))
  })
  append(box, [grid])
  if (profiles.length === 0) {
    append(box, [h('p', { class: 'empty' }, 'Aucun profil sur ce compte. Le premier appareil connecté crée « Moi » ; tu peux aussi en ajouter un ici.')])
  }
  return box
}

async function move(button: HTMLButtonElement, profiles: Profile[], id: string, delta: -1 | 1) {
  if (await act(button, () => backend.saveProfiles(moveProfile(profiles, id, delta)))) await refresh(['profiles'])
}

export function editProfile(existing: Profile | null) {
  const profiles = state.profiles ?? []
  const isNew = !existing
  const draft: Profile = existing ? { ...existing } : {
    id: crypto.randomUUID(), name: '', colorIndex: nextColor(profiles), isKid: false, pinHash: '', avatar: '',
    position: profiles.reduce((m, p) => Math.max(m, p.position), -1) + 1, updatedAt: 0,
  }
  /** null : on garde le code actuel ; '' : on le retire ; 4 chiffres : nouveau code. */
  let newPin: string | null = null

  const name = h('input', { id: 'pname', value: draft.name, maxlength: MAX_PROFILE_NAME, required: true, autocomplete: 'off' })
  let previewEl = avatar({ ...draft, name: draft.name || '?' })
  const initialGlyph = h('span', { class: 'avatar-choice-initial', 'aria-hidden': 'true' },
    Array.from(draft.name.trim())[0]?.toUpperCase() ?? 'A')
  const refreshPreview = () => {
    initialGlyph.textContent = Array.from(name.value.trim())[0]?.toUpperCase() ?? 'A'
    const fresh = avatar({ name: name.value || '?', colorIndex: draft.colorIndex, isKid: kid.checked, avatar: draft.avatar })
    previewEl.replaceWith(fresh)
    previewEl = fresh
  }
  name.addEventListener('input', refreshPreview)

  const avatarGrid = h('div', { class: 'avatar-grid', role: 'radiogroup', 'aria-label': 'Avatar' },
    AVATAR_CHOICES.map((choice) => {
      const b = h('button', {
        type: 'button', class: 'avatar-choice', role: 'radio', 'aria-checked': String(choice.id === cleanAvatar(draft.avatar)),
        onclick: () => {
          draft.avatar = choice.id
          for (const item of avatarGrid.querySelectorAll('.avatar-choice')) item.setAttribute('aria-checked', 'false')
          b.setAttribute('aria-checked', 'true')
          refreshPreview()
        },
      }, choice.id ? avatarSvg(choice.id, 20) : initialGlyph, h('span', {}, choice.label))
      return b
    }))

  const swatches = h('div', { class: 'swatches', role: 'radiogroup', 'aria-label': 'Couleur' },
    PROFILE_PALETTE.map((color, i) => {
      const b = h('button', {
        type: 'button', class: 'swatch', role: 'radio', 'aria-checked': String(i === draft.colorIndex), 'aria-label': `Couleur ${i + 1}`,
        onclick: () => {
          draft.colorIndex = i
          for (const s of swatches.querySelectorAll('.swatch')) s.setAttribute('aria-checked', 'false')
          b.setAttribute('aria-checked', 'true')
          refreshPreview()
        },
      })
      b.style.setProperty('--c', color)
      return b
    }))

  const kid = h('input', { id: 'pkid', type: 'checkbox', checked: draft.isKid, onchange: () => refreshPreview() })

  // Code du profil
  const pinBox = h('div', { class: 'pin-box' })
  const pin1 = h('input', { type: 'password', inputmode: 'numeric', maxlength: 4, pattern: '\\d{4}', autocomplete: 'new-password', 'aria-label': 'Nouveau code', placeholder: '••••' })
  const pin2 = h('input', { type: 'password', inputmode: 'numeric', maxlength: 4, pattern: '\\d{4}', autocomplete: 'new-password', 'aria-label': 'Confirmer le code', placeholder: '••••' })
  const renderPin = (editing: boolean) => {
    clear(pinBox)
    const hasPin = newPin === null ? !!draft.pinHash : newPin !== ''
    if (editing) {
      pin1.value = ''; pin2.value = ''
      append(pinBox, [
        h('div', { class: 'pin-fields' },
          h('label', {}, 'Nouveau code', pin1), h('label', {}, 'Confirmer', pin2)),
        h('button', { class: 'btn ghost small', type: 'button', onclick: () => { newPin = null; renderPin(false) } }, 'Garder le code actuel'),
      ])
      pin1.focus()
      return
    }
    append(pinBox, [
      h('p', { class: 'pin-state' }, icon('lock', 14), hasPin
        ? (newPin ? 'Nouveau code prêt à être enregistré' : 'Code à 4 chiffres demandé à l’ouverture du profil')
        : 'Aucun code : le profil s’ouvre directement'),
      h('div', { class: 'row' },
        h('button', { class: 'btn ghost small', type: 'button', onclick: () => renderPin(true) }, hasPin ? 'Changer le code' : 'Ajouter un code'),
        hasPin ? h('button', { class: 'btn ghost small', type: 'button', onclick: () => { newPin = ''; renderPin(false) } }, 'Retirer le code') : null),
    ])
  }
  renderPin(false)

  const error = h('p', { class: 'form-error', role: 'alert' })
  const save = h('button', { class: 'btn primary', type: 'submit' }, isNew ? 'Créer le profil' : 'Enregistrer')
  const cancel = h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler')
  const canDelete = !isNew && profiles.length > 1
  const del = isNew ? null : h('button', {
    class: 'btn danger-ghost', type: 'button', disabled: !canDelete,
    title: canDelete ? '' : 'Un compte garde toujours au moins un profil',
    onclick: async () => {
      const ok = await confirmDialog({
        title: `Supprimer « ${existing!.name} » ?`,
        body: 'Le profil disparaît de tous tes appareils, avec sa progression, son historique et ses listes. Impossible de revenir en arrière.',
        confirm: 'Supprimer le profil', danger: true,
      })
      if (!ok) return
      if (await act(del, () => backend.deleteProfile(existing!), `Profil « ${existing!.name} » supprimé`)) {
        dialog.close()
        await refresh(['profiles', 'activity'])
      }
    },
  }, icon('trash', 15), 'Supprimer')

  const form = h('form', {
    method: 'dialog',
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      error.textContent = ''
      if (pinBox.contains(pin1)) {
        if (!isValidPin(pin1.value)) { error.textContent = 'Le code doit faire 4 chiffres.'; pin1.focus(); return }
        if (pin1.value !== pin2.value) { error.textContent = 'Les deux codes ne correspondent pas.'; pin2.focus(); return }
        newPin = pin1.value
      }
      const final: Profile = {
        ...draft,
        name: cleanName(name.value),
        avatar: cleanAvatar(draft.avatar),
        isKid: kid.checked,
        pinHash: newPin === null ? draft.pinHash : newPin === '' ? '' : await hashPin(draft.id, newPin),
      }
      if (await act(save, () => backend.saveProfiles([final]), isNew ? `Profil « ${final.name} » créé` : 'Profil enregistré')) {
        dialog.close()
        await refresh(['profiles', 'activity'])
      }
    },
  },
    h('div', { class: 'dialog-head' }, previewEl, h('h2', {}, isNew ? 'Nouveau profil' : 'Modifier le profil')),
    h('label', { for: 'pname' }, 'Nom'), name,
    h('span', { class: 'field-label' }, 'Avatar'), avatarGrid,
    h('span', { class: 'field-label' }, 'Couleur'), swatches,
    h('label', { class: 'check' }, kid, h('span', {}, h('strong', {}, 'Profil enfant'),
      h('small', {}, 'Les catégories verrouillées par le contrôle parental sont toujours cachées, même avec le code parental.'))),
    h('span', { class: 'field-label' }, 'Code du profil'), pinBox,
    error,
    h('div', { class: 'dialog-actions' }, del, h('span', { class: 'spacer' }), cancel, save),
  )
  const dialog = openDialog(form)
  if (isNew) name.focus()
}
