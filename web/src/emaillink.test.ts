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

  it('session renvoyée par les modèles par défaut', () => {
    expect(parseEmailLink('', '#access_token=a&refresh_token=r&type=recovery&expires_in=3600'))
      .toEqual({ mode: 'session', type: 'recovery', accessToken: 'a', refreshToken: 'r' })
  })

  it('erreur, dans la requête ou après #', () => {
    const fromHash = parseEmailLink('', '#error=access_denied&error_code=otp_expired&error_description=Email+link+is+invalid+or+has+expired')
    expect(fromHash).toEqual({ mode: 'error', code: 'otp_expired', message: 'Email link is invalid or has expired' })
    expect(parseEmailLink('?error=access_denied', '')).toMatchObject({ mode: 'error', code: 'access_denied' })
  })

  it('message en français', () => {
    expect(emailLinkError('otp_expired', '')).toMatch(/expiré/)
  })
})
