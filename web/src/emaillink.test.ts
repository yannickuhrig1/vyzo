import { describe, expect, it } from 'vitest'
import { emailLinkError, parseEmailLink } from './model'

describe('parseEmailLink', () => {
  it('visite ordinaire', () => {
    expect(parseEmailLink('', '')).toBeNull()
    expect(parseEmailLink('', '#/appareils')).toBeNull()
  })

  it('lien des modèles Vyzo', () => {
    expect(parseEmailLink('?token_hash=abc&type=recovery', '')).toEqual({ mode: 'token', type: 'recovery', tokenHash: 'abc' })
    expect(parseEmailLink('?token_hash=abc&type=signup', '')).toEqual({ mode: 'token', type: 'signup', tokenHash: 'abc' })
    expect(parseEmailLink('?token_hash=abc&type=email', '')?.mode).toBe('token')
    expect(parseEmailLink('?token_hash=abc&type=email_change', '')).toMatchObject({ type: 'email_change' })
  })

  it('type inconnu ignoré', () => {
    expect(parseEmailLink('?token_hash=abc&type=autre', '')).toBeNull()
  })

  it('session toute faite refusée', () => {
    expect(parseEmailLink('', '#access_token=a&refresh_token=r&type=recovery&expires_in=3600'))
      .toEqual({ mode: 'error', code: 'session_in_url' })
  })

  it('erreur, dans la requête ou après #, sans le texte venu de l’adresse', () => {
    const fromHash = parseEmailLink('', '#error=access_denied&error_code=otp_expired&error_description=Appelle+le+0800')
    expect(fromHash).toEqual({ mode: 'error', code: 'otp_expired' })
    expect(parseEmailLink('?error=access_denied', '')).toEqual({ mode: 'error', code: 'access_denied' })
  })

  it('messages fixes en français', () => {
    expect(emailLinkError('otp_expired')).toMatch(/expiré/)
    expect(emailLinkError('nimporte quoi')).not.toMatch(/nimporte/)
  })
})
