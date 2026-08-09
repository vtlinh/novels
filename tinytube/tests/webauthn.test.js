import { describe, it, expect, vi, afterEach } from 'vitest'
import { isBiometricAvailable, enroll, verify, toBase64url, fromBase64url } from '../src/lib.js'

afterEach(() => vi.unstubAllGlobals())

describe('base64url helpers', () => {
  it('round-trips arbitrary bytes', () => {
    const bytes = new Uint8Array([0, 1, 250, 251, 252, 253, 254, 255, 62, 63]).buffer
    expect(new Uint8Array(fromBase64url(toBase64url(bytes)))).toEqual(new Uint8Array(bytes))
  })

  it('emits url-safe characters only', () => {
    const encoded = toBase64url(new Uint8Array([251, 255, 254]).buffer)
    expect(encoded).not.toMatch(/[+/=]/)
  })
})

describe('isBiometricAvailable', () => {
  it('is false when the API is missing', async () => {
    vi.stubGlobal('PublicKeyCredential', undefined)
    expect(await isBiometricAvailable()).toBe(false)
  })

  it('reflects the platform authenticator check', async () => {
    vi.stubGlobal('PublicKeyCredential', {
      isUserVerifyingPlatformAuthenticatorAvailable: async () => true,
    })
    expect(await isBiometricAvailable()).toBe(true)
  })

  it('is false when the check throws', async () => {
    vi.stubGlobal('PublicKeyCredential', {
      isUserVerifyingPlatformAuthenticatorAvailable: async () => {
        throw new Error('nope')
      },
    })
    expect(await isBiometricAvailable()).toBe(false)
  })
})

describe('enroll', () => {
  it('requires a platform authenticator with user verification and returns the id', async () => {
    const rawId = new Uint8Array([1, 2, 3, 4]).buffer
    const create = vi.fn(async ({ publicKey }) => {
      expect(publicKey.authenticatorSelection).toMatchObject({
        authenticatorAttachment: 'platform',
        userVerification: 'required',
      })
      expect(publicKey.rp.id).toBe(location.hostname)
      return { rawId }
    })
    vi.stubGlobal('navigator', { credentials: { create } })
    expect(await enroll()).toBe(toBase64url(rawId))
  })
})

describe('verify', () => {
  it('passes the stored credential id and requires user verification', async () => {
    const idB64 = toBase64url(new Uint8Array([9, 8, 7]).buffer)
    const get = vi.fn(async ({ publicKey }) => {
      expect(publicKey.userVerification).toBe('required')
      expect(new Uint8Array(publicKey.allowCredentials[0].id)).toEqual(new Uint8Array([9, 8, 7]))
      return { id: 'assertion' }
    })
    vi.stubGlobal('navigator', { credentials: { get } })
    expect(await verify(idB64)).toBe(true)
  })

  it('returns false on cancel/failed scan (NotAllowedError)', async () => {
    vi.stubGlobal('navigator', {
      credentials: { get: async () => Promise.reject(new Error('NotAllowedError')) },
    })
    expect(await verify(toBase64url(new Uint8Array([1]).buffer))).toBe(false)
  })
})
