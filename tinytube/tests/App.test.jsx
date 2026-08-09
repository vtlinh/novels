import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import App from '../src/main.jsx'
import { verify } from '../src/lib.js'

// partial mock: webauthn now lives in lib.js next to the hooks, which must stay real
vi.mock('../src/lib.js', async importOriginal => ({
  ...(await importOriginal()),
  verify: vi.fn(async () => true),
  isBiometricAvailable: vi.fn(async () => false),
  enroll: vi.fn(async () => 'fresh-credential'),
}))
import { isBiometricAvailable } from '../src/lib.js'

// the quota tests walk into the player view; stub the iframe like VideoPlayer.test does
vi.mock('react-youtube', () => ({ default: () => <div data-testid="yt-stub" /> }))

const db = {
  schema_version: 2,
  generated_at: 'x',
  channels: [
    {
      channel_id: 'UCa',
      channel_title: 'Chan',
      min_age: 1,
      max_age: 15,
      videos: [{ id: 'v1', title: 'Vid', duration: 10, thumbnail: 't.jpg' }],
    },
  ],
}

// Node 22+'s broken experimental localStorage shadows jsdom's — use a real fake
function fakeStorage() {
  let store = {}
  return {
    getItem: k => store[k] ?? null,
    setItem: (k, v) => { store[k] = String(v) },
    removeItem: k => { delete store[k] },
    clear: () => { store = {} },
  }
}

beforeEach(() => {
  vi.stubGlobal('localStorage', fakeStorage())
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => db })))
  verify.mockClear()
})

describe('first-run enrollment', () => {
  it('blocks everything with the enroll screen on a biometric device until enrolled', async () => {
    isBiometricAvailable.mockResolvedValueOnce(true)
    render(<App />)
    expect(await screen.findByText('Enter')).toBeTruthy()
    expect(screen.queryByLabelText('Parents')).toBeNull() // no gallery behind it
    fireEvent.click(screen.getByText('Enter'))
    expect(await screen.findByLabelText('Parents')).toBeTruthy() // gallery after enrolling
    expect(JSON.parse(localStorage.getItem('tinytube:settings:v1')).passkeyId).toBe('fresh-credential')
  })

  it('skips the enroll screen when already enrolled', async () => {
    isBiometricAvailable.mockResolvedValueOnce(true)
    localStorage.setItem('tinytube:settings:v1', JSON.stringify({ passkeyId: 'abc' }))
    render(<App />)
    expect(await screen.findByLabelText('Parents')).toBeTruthy()
    expect(screen.queryByText('Enter')).toBeNull()
  })
})

describe('parent gate', () => {
  it('opens the math gate when no passkey is enrolled', async () => {
    render(<App />)
    fireEvent.click(await screen.findByLabelText('Parents'))
    expect(await screen.findByText(/Grown-ups only/)).toBeTruthy()
    expect(verify).not.toHaveBeenCalled()
  })

  it('goes straight to the biometric and into settings when enrolled', async () => {
    localStorage.setItem('tinytube:settings:v1', JSON.stringify({ passkeyId: 'abc' }))
    render(<App />)
    fireEvent.click(await screen.findByLabelText('Parents'))
    expect(await screen.findByText(/Settings/)).toBeTruthy()
    expect(verify).toHaveBeenCalledWith('abc')
    expect(screen.queryByText(/Grown-ups only/)).toBeNull()
  })

  it('stays on the gallery when the biometric is cancelled', async () => {
    localStorage.setItem('tinytube:settings:v1', JSON.stringify({ passkeyId: 'abc' }))
    verify.mockResolvedValueOnce(false)
    render(<App />)
    fireEvent.click(await screen.findByLabelText('Parents'))
    expect(verify).toHaveBeenCalled()
    expect(screen.queryByText(/Settings/)).toBeNull()
    expect(screen.queryByText(/Grown-ups only/)).toBeNull()
  })
})

describe('watch quota gate', () => {
  const spendQuota = (quotaMins = 15) => {
    localStorage.setItem('tinytube:settings:v1', JSON.stringify({ quotaMins }))
    localStorage.setItem(
      'tinytube:v1',
      JSON.stringify({ watched: {}, usage: { window: { start: Date.now(), secs: quotaMins * 60 }, days: {}, hours: {} } }),
    )
  }

  it('opens the player while under quota', async () => {
    render(<App />)
    fireEvent.click(await screen.findByText('Vid'))
    expect(await screen.findByTitle('Watch time left')).toBeTruthy() // player top bar quota meter
  })

  it('blocks the tap with the exceeded screen when the quota is spent', async () => {
    spendQuota()
    render(<App />)
    fireEvent.click(await screen.findByText('Vid'))
    expect(await screen.findByText(/Watch Quota Exceeded/)).toBeTruthy()
    expect(screen.queryByTitle('Watch time left')).toBeNull() // no player behind it
  })

  it('always blocks when the quota is 0 (no off state)', async () => {
    localStorage.setItem('tinytube:settings:v1', JSON.stringify({ quotaMins: 0 }))
    render(<App />)
    fireEvent.click(await screen.findByText('Vid'))
    expect(await screen.findByText(/Watch Quota Exceeded/)).toBeTruthy()
  })

  it('unblocks by itself once the 12h window has expired', async () => {
    spendQuota()
    const stale = JSON.parse(localStorage.getItem('tinytube:v1'))
    stale.usage.window.start = Date.now() - 13 * 3600_000
    localStorage.setItem('tinytube:v1', JSON.stringify(stale))
    render(<App />)
    fireEvent.click(await screen.findByText('Vid'))
    expect(await screen.findByTitle('Watch time left')).toBeTruthy()
  })

  it('sends a parent through the biometric into settings', async () => {
    spendQuota()
    localStorage.setItem('tinytube:settings:v1', JSON.stringify({ quotaMins: 15, passkeyId: 'abc' }))
    render(<App />)
    fireEvent.click(await screen.findByText('Vid'))
    fireEvent.click(await screen.findByText(/Parents/))
    expect(await screen.findByText(/Settings/)).toBeTruthy()
    expect(verify).toHaveBeenCalledWith('abc')
  })

  it('falls back to the math gate without a passkey', async () => {
    spendQuota()
    render(<App />)
    fireEvent.click(await screen.findByText('Vid'))
    fireEvent.click(await screen.findByText(/Parents/))
    expect(await screen.findByText(/Grown-ups only/)).toBeTruthy()
  })

  it('goes back to the gallery on browser back', async () => {
    spendQuota()
    render(<App />)
    fireEvent.click(await screen.findByText('Vid'))
    await screen.findByText(/Watch Quota Exceeded/)
    fireEvent.popState(window)
    expect(await screen.findByLabelText('Parents')).toBeTruthy()
    expect(screen.queryByText(/Watch Quota Exceeded/)).toBeNull()
  })
})
