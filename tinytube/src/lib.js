/** Shared non-UI logic: webauthn parent gate, math-gate challenge, channel
 * filtering, and the three localStorage-backed hooks (settings, watch
 * history, gallery data). */

import { useCallback, useEffect, useMemo, useState } from 'react'
import { getChannelVideosCached } from './youtubeApi.js'

// ---------------------------------------------------------------------------
// webauthn — serverless WebAuthn parent gate: we never verify signatures (the
// adversary is a child, and everything lives in localStorage anyway) — we only
// rely on the OS refusing to resolve credentials.get() without a successful
// biometric (userVerification: 'required' on a platform authenticator).

export function toBase64url(buf) {
  return btoa(String.fromCharCode(...new Uint8Array(buf)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

export function fromBase64url(s) {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(b64), c => c.charCodeAt(0)).buffer
}

const random = bytes => crypto.getRandomValues(new Uint8Array(bytes))

export async function isBiometricAvailable() {
  try {
    return !!window.PublicKeyCredential && (await PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable())
  } catch {
    return false
  }
}

/** Register the device biometric; returns the credential id (base64url) to store. */
export async function enroll() {
  const credential = await navigator.credentials.create({
    publicKey: {
      challenge: random(32),
      rp: { name: 'TinyTube', id: location.hostname },
      user: { id: random(16), name: 'parent', displayName: 'Parent' },
      pubKeyCredParams: [
        { type: 'public-key', alg: -7 }, // ES256
        { type: 'public-key', alg: -257 }, // RS256
      ],
      authenticatorSelection: {
        authenticatorAttachment: 'platform',
        userVerification: 'required',
        residentKey: 'discouraged',
      },
      timeout: 60_000,
      attestation: 'none',
    },
  })
  return toBase64url(credential.rawId)
}

/** Fire the OS biometric prompt for the stored credential. False on cancel/failed scan. */
export async function verify(credentialIdB64) {
  try {
    const assertion = await navigator.credentials.get({
      publicKey: {
        challenge: random(32),
        allowCredentials: [{ type: 'public-key', id: fromBase64url(credentialIdB64) }],
        userVerification: 'required',
        timeout: 60_000,
      },
    })
    return assertion !== null
  } catch {
    return false
  }
}

// ---------------------------------------------------------------------------
// mathGate

/**
 * Parent gate challenge: random 2-digit addition with 4 choices — one correct,
 * three plausible mistakes (off-by-carry, off-by-one...). rand is injectable
 * for tests.
 */
export function makeChallenge(rand = Math.random) {
  const int = (lo, hi) => lo + Math.floor(rand() * (hi - lo + 1))
  const a = int(10, 99)
  const b = int(10, 99)
  const answer = a + b

  const pool = [answer - 10, answer + 10, answer - 1, answer + 1, answer - 2, answer + 2, answer - 20, answer + 20]
  const distractors = [...new Set(pool.filter(n => n > 0 && n !== answer))].slice(0, 3)

  const choices = [answer, ...distractors]
  for (let i = choices.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1))
    ;[choices[i], choices[j]] = [choices[j], choices[i]]
  }
  return { a, b, answer, choices }
}

// ---------------------------------------------------------------------------
// channels

/** Inclusive range overlap: does [min_age, max_age] intersect the parent's [lo, hi]? */
export function overlaps([lo, hi], minAge, maxAge) {
  return minAge <= hi && lo <= maxAge
}

/**
 * Curated channels from videos.json with the parent's per-channel edits
 * applied — overrides[channel_id] may adjust min_age/max_age or set hidden.
 */
export function curatedChannels(db, overrides = {}) {
  return (db?.channels ?? []).map(ch => ({ ...ch, ...overrides[ch.channel_id] }))
}

/**
 * The gallery's channel list: curated channels (edits applied) that aren't
 * hidden or toggled off and overlap the age range, plus parent-added
 * channels — shaped exactly like curated ones ({channel_title, videos}) so
 * gallerySort works unchanged. Curated wins if a parent adds an
 * already-curated channel.
 */
export function mergeChannels(db, customVideosById, settings) {
  const { ageRange, customChannels, overrides, minVideoMins = 0 } = settings
  const curated = curatedChannels(db, overrides).filter(
    ch => !ch.hidden && !ch.disabled && overlaps(ageRange, ch.min_age, ch.max_age),
  )
  const curatedIds = new Set((db?.channels ?? []).map(ch => ch.channel_id))
  const custom = customChannels
    .filter(ch => !curatedIds.has(ch.channel_id) && !ch.disabled && overlaps(ageRange, ch.min_age, ch.max_age))
    .map(ch => ({ ...ch, videos: customVideosById[ch.channel_id] ?? [] }))
  // unknown durations count as too short: don't let un-probed videos slip past
  return [...curated, ...custom].map(ch => ({
    ...ch,
    videos: (ch.videos ?? []).filter(v => (v.duration ?? 0) >= minVideoMins * 60),
  }))
}

// ---------------------------------------------------------------------------
// useSettings

const SETTINGS_KEY = 'tinytube:settings:v1'

export const DEFAULTS = {
  apiKey: '',
  ageRange: [1, 15], // everything
  quotaMins: 180, // watch quota per rolling 12h window; 0 = no watching (there is no "off")
  minVideoMins: 0, // hide videos shorter than this; 0 = show everything
  customChannels: [], // parent-added, same flat shape as channels.json entries: [{channel_id, channel_title, thumbnail, min_age, max_age}]
  overrides: {}, // per curated channel_id: {min_age?, max_age?, hidden?, disabled?} edited in the table
  passkeyId: null, // WebAuthn credential id (base64url); when set, the parent gate is biometric-only
}

function loadSettings() {
  try {
    const parsed = JSON.parse(localStorage.getItem(SETTINGS_KEY)) ?? {}
    // fold pre-refactor fields into the unified overrides map
    const overrides = { ...parsed.ageOverrides, ...parsed.overrides }
    for (const id of parsed.hiddenChannels ?? []) overrides[id] = { ...overrides[id], hidden: true }
    delete parsed.hiddenChannels
    delete parsed.ageOverrides
    delete parsed.parentLockUntil // lockout mechanism removed
    return { ...DEFAULTS, ...parsed, overrides }
  } catch {
    return { ...DEFAULTS }
  }
}

// mutator API over a settings object; `update` takes shallow patches. Shared
// by the persistent store below and the Settings page's unsaved draft.
export function storeApi(settings, update) {
  return {
    settings,
    setApiKey: apiKey => update({ apiKey: apiKey.trim() }),
    setAgeRange: ([lo, hi]) => update({ ageRange: [Math.min(lo, hi), Math.max(lo, hi)] }),
    setQuotaMins: quotaMins => update({ quotaMins }),
    setMinVideoMins: minVideoMins => update({ minVideoMins }),
    addCustomChannel: ch =>
      update({
        customChannels: [...settings.customChannels.filter(c => c.channel_id !== ch.channel_id), ch],
      }),
    updateCustomChannel: (id, patch) =>
      update({
        customChannels: settings.customChannels.map(c => (c.channel_id === id ? { ...c, ...patch } : c)),
      }),
    removeCustomChannel: id =>
      update({ customChannels: settings.customChannels.filter(c => c.channel_id !== id) }),
    setOverride: (id, patch) =>
      update({ overrides: { ...settings.overrides, [id]: { ...settings.overrides[id], ...patch } } }),
    restoreHidden: () =>
      update({
        overrides: Object.fromEntries(
          Object.entries(settings.overrides)
            .map(([id, { hidden, ...rest }]) => [id, rest])
            .filter(([, rest]) => Object.keys(rest).length > 0),
        ),
      }),
    setPasskey: id => update({ passkeyId: id }),
  }
}

export function useSettings() {
  const [settings, setSettings] = useState(loadSettings)

  const update = useCallback(patch => {
    setSettings(prev => {
      const next = { ...prev, ...patch }
      try {
        localStorage.setItem(SETTINGS_KEY, JSON.stringify(next))
      } catch (e) {
        console.error('settings persist failed', e)
      }
      return next
    })
  }, [])

  return { ...storeApi(settings, update), save: update }
}

// ---------------------------------------------------------------------------
// watch quota — cumulative PLAYING seconds against a parent-set cap
// (settings.quotaMins) per rolling 12h window. The window starts at the first
// counted second and expires lazily on read; no timers anywhere. Day/hour
// buckets exist only to feed the parent-facing stats table.

export const QUOTA_WINDOW_MS = 12 * 3600_000
const HOUR_MS = 3600_000
const DAY_MS = 86_400_000
const EMPTY_USAGE = { window: { start: null, secs: 0 }, days: {}, hours: {} }

const pad2 = n => String(n).padStart(2, '0')
const localDate = d => `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`

/** "3h", "1h 45m", "45m", "0m" */
export function fmtMins(mins) {
  const h = Math.floor(mins / 60)
  const m = Math.round(mins % 60)
  return h && m ? `${h}h ${m}m` : h ? `${h}h` : `${m}m`
}

/** Seconds used in the current 12h window; 0 if none, expired, or the clock ran backwards. */
export function windowUsed(usage, now = Date.now()) {
  const { start, secs } = usage?.window ?? {}
  return start != null && now >= start && now - start < QUOTA_WINDOW_MS ? secs : 0
}

/** Count watched seconds: extend (or start) the 12h window and bump the stats buckets. */
export function accrueUsage(usage, secs, now = Date.now()) {
  const window = { start: windowUsed(usage, now) ? usage.window.start : now, secs: windowUsed(usage, now) + secs }
  const dayKey = localDate(new Date(now))
  const days = { ...usage.days, [dayKey]: (usage.days[dayKey] ?? 0) + secs }
  const oldestDay = localDate(new Date(now - 366 * DAY_MS)) // YYYY-MM-DD compares lexicographically
  for (const k of Object.keys(days)) if (k < oldestDay) delete days[k]
  const hourKey = Math.floor(now / HOUR_MS)
  const hours = { ...usage.hours, [hourKey]: (usage.hours[hourKey] ?? 0) + secs }
  for (const k of Object.keys(hours)) if (+k < hourKey - 48) delete hours[k]
  return { window, days, hours }
}

/** Parent-facing stats, all in seconds. Session = the current 12h quota window; WTD weeks start Sunday. */
export function usageStats(usage, now = Date.now()) {
  const d = new Date(now)
  const daysSince = key =>
    Object.entries(usage.days).reduce((total, [k, secs]) => (k >= key ? total + secs : total), 0)
  const nowHour = Math.floor(now / HOUR_MS)
  return {
    session: windowUsed(usage, now),
    last24h: Object.entries(usage.hours).reduce((total, [k, secs]) => (+k > nowHour - 24 ? total + secs : total), 0),
    wtd: daysSince(localDate(new Date(d.getFullYear(), d.getMonth(), d.getDate() - d.getDay()))),
    mtd: daysSince(localDate(new Date(d.getFullYear(), d.getMonth(), 1))),
    ytd: daysSince(localDate(new Date(d.getFullYear(), 0, 1))),
  }
}

// ---------------------------------------------------------------------------
// useWatchStore

const WATCH_KEY = 'tinytube:v1'
const MAX_ENTRIES = 500
export const WATCHED_THRESHOLD = 0.95 // beyond this it's just credits/outros
const LIKED_THRESHOLD = 0.2 // bailed before this -> probably didn't like it

function loadWatchStore() {
  try {
    const parsed = JSON.parse(localStorage.getItem(WATCH_KEY)) ?? {}
    return { lastVideoId: null, watched: {}, ...parsed, usage: { ...EMPTY_USAGE, ...parsed.usage } }
  } catch {
    return { lastVideoId: null, watched: {}, usage: EMPTY_USAGE }
  }
}

function persist(store) {
  const ids = Object.keys(store.watched)
  if (ids.length > MAX_ENTRIES) {
    ids.sort((a, b) => store.watched[a].updatedAt - store.watched[b].updatedAt)
    for (const id of ids.slice(0, ids.length - MAX_ENTRIES)) delete store.watched[id]
  }
  localStorage.setItem(WATCH_KEY, JSON.stringify(store))
}

export function fraction(entry) {
  if (!entry) return 0
  if (entry.completed) return 1
  return entry.dur ? Math.min(entry.pos / entry.dur, 1) : 0
}

export function useWatchStore() {
  const [store, setStore] = useState(loadWatchStore)

  const saveProgress = useCallback((id, pos, dur) => {
    setStore(prev => {
      const entry = prev.watched[id]
      const completed = (entry?.completed ?? false) || (dur > 0 && pos / dur > WATCHED_THRESHOLD)
      const next = {
        ...prev,
        lastVideoId: id,
        watched: { ...prev.watched, [id]: { pos, dur, completed, updatedAt: Date.now() } },
      }
      persist(next)
      return next
    })
  }, [])

  const markCompleted = useCallback(id => {
    setStore(prev => {
      const entry = prev.watched[id] ?? { pos: 0, dur: 0 }
      const next = {
        ...prev,
        lastVideoId: id,
        watched: { ...prev.watched, [id]: { ...entry, completed: true, updatedAt: Date.now() } },
      }
      persist(next)
      return next
    })
  }, [])

  const addWatchTime = useCallback(secs => {
    setStore(prev => {
      const next = { ...prev, usage: accrueUsage(prev.usage, secs) }
      persist(next)
      return next
    })
  }, [])

  return { watched: store.watched, usage: store.usage, saveProgress, markCompleted, addWatchTime }
}

/** Interleave lists round-robin: first item of each list, then second of each, ... */
function roundRobin(lists) {
  const out = []
  const longest = Math.max(0, ...lists.map(l => l.length))
  for (let i = 0; i < longest; i++) {
    for (const list of lists) if (i < list.length) out.push(list[i])
  }
  return out
}

/**
 * Gallery order:
 * 1. continue watching (20-95% done), closest to finished first
 * 2. fresh videos, round-robin across channels (newest first within a channel)
 *    so a high-volume channel can't flood out a quiet one
 * 3. abandoned (<20%, started but bailed), same round-robin
 * 4. watched (>95%) last
 */
export function gallerySort(channels, watched) {
  const inProgress = []
  const freshPerChannel = []
  const abandonedPerChannel = []
  const done = []

  for (const ch of channels) {
    const fresh = []
    const abandoned = []
    for (const video of ch.videos) {
      const v = { ...video, channelTitle: ch.channel_title }
      const entry = watched[v.id]
      const f = fraction(entry)
      if (f > WATCHED_THRESHOLD) done.push(v)
      else if (f >= LIKED_THRESHOLD) inProgress.push({ v, f })
      else if (entry) abandoned.push(v)
      else fresh.push(v)
    }
    freshPerChannel.push(fresh)
    abandonedPerChannel.push(abandoned)
  }

  inProgress.sort((a, b) => b.f - a.f)
  return [
    ...inProgress.map(x => x.v),
    ...roundRobin(freshPerChannel),
    ...roundRobin(abandonedPerChannel),
    ...done,
  ]
}

// ---------------------------------------------------------------------------
// useVideos

/**
 * Gallery data: curated channels from videos.json filtered by the parent's
 * settings (age range, hidden), merged with parent-added channels whose
 * videos are fetched via the Data API (cache-first).
 */
export function useVideos(settings) {
  const [db, setDb] = useState(null)
  const [error, setError] = useState(null)
  const [customVideosById, setCustomVideosById] = useState({})

  useEffect(() => {
    fetch(import.meta.env.BASE_URL + 'videos.json')
      .then(r => {
        if (!r.ok) throw new Error(`videos.json: HTTP ${r.status}`)
        return r.json()
      })
      .then(setDb)
      .catch(setError)
  }, [])

  const { apiKey, customChannels } = settings
  useEffect(() => {
    let cancelled = false
    Promise.all(
      customChannels.map(ch =>
        getChannelVideosCached(apiKey, ch.channel_id).then(videos => [ch.channel_id, videos]),
      ),
    ).then(entries => {
      if (!cancelled) setCustomVideosById(Object.fromEntries(entries))
    })
    return () => {
      cancelled = true
    }
  }, [apiKey, customChannels])

  const channels = useMemo(
    () => (db ? mergeChannels(db, customVideosById, settings) : null),
    [db, customVideosById, settings],
  )

  return { db, channels, error }
}
