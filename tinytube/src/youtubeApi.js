/**
 * Minimal YouTube Data API v3 client for parent-added channels. Every call
 * uses the PARENT's own API key (stored in localStorage) — this repo/site
 * ships no key.
 */

const API = 'https://www.googleapis.com/youtube/v3'
const CACHE_KEY = 'tinytube:videocache:v1'
const CACHE_TTL_MS = 24 * 60 * 60 * 1000
const UC_ID = /UC[0-9A-Za-z_-]{22}/

async function get(path, params) {
  const url = `${API}/${path}?${new URLSearchParams(params)}`
  const resp = await fetch(url)
  const body = await resp.json().catch(() => ({}))
  if (!resp.ok) throw new Error(body?.error?.message ?? `YouTube API: HTTP ${resp.status}`)
  return body
}

function channelFromSnippet(id, snippet) {
  return {
    channel_id: id,
    channel_title: snippet.title,
    thumbnail: snippet.thumbnails?.medium?.url ?? snippet.thumbnails?.default?.url,
  }
}

/** topicCategories are Wikipedia URLs, e.g. .../wiki/Children%27s_music -> "Children's music" */
function topicNames(topicDetails) {
  const names = (topicDetails?.topicCategories ?? []).map(url =>
    decodeURIComponent(url.split('/').pop()).replace(/_/g, ' '),
  )
  return [...new Set(names)]
}

/** Full channels.list item -> channel blob with the kid-relevant extras and stats. */
function channelFromItem(item) {
  return {
    ...channelFromSnippet(item.id, item.snippet),
    made_for_kids: item.status?.madeForKids ?? null, // COPPA designation; null = unknown
    topics: topicNames(item.topicDetails),
    subscribers: Number(item.statistics?.subscriberCount) || null,
    video_count: Number(item.statistics?.videoCount) || null,
    view_count: Number(item.statistics?.viewCount) || null,
  }
}

/**
 * Free-text channel search with preview stats. search.list costs 100 quota
 * units per call — callers must debounce (the 10k/day default quota affords
 * ~100 fired searches). The follow-up channels.list (1 unit) upgrades results
 * with real avatars and subscriber counts for previews.
 */
export async function searchChannels(apiKey, query) {
  const body = await get('search', { part: 'snippet', type: 'channel', q: query, maxResults: 6, key: apiKey })
  const results = (body.items ?? []).map(item => channelFromSnippet(item.id.channelId, item.snippet))
  if (!results.length) return results
  try {
    const details = await get('channels', {
      part: 'snippet,statistics,status,topicDetails',
      id: results.map(r => r.channel_id).join(','),
      key: apiKey,
    })
    const byId = Object.fromEntries((details.items ?? []).map(it => [it.id, it]))
    return results.map(r => {
      const d = byId[r.channel_id]
      return d ? channelFromItem(d) : r
    })
  } catch {
    return results // preview enrichment is best-effort
  }
}

/** Key sanity-check via the cheapest keyed endpoint (i18nLanguages, 1 unit). */
export async function validateApiKey(apiKey) {
  await get('i18nLanguages', { part: 'snippet', key: apiKey })
}

export function formatCount(n) {
  return n ? Intl.NumberFormat('en', { notation: 'compact' }).format(n) : ''
}

/** Resolve a pasted UC id, channel URL (with or without @), or @handle to a channel. 1 unit. */
export async function resolveChannel(apiKey, input) {
  const text = input.trim()
  const id = text.match(UC_ID)?.[0]
  const handle = text.match(/@[\w.-]+/)?.[0]
  // legacy URLs carry no @: /user/Name has its own lookup param, while /c/Name
  // and bare youtube.com/Name custom URLs nearly always match today's handle
  const user = text.match(/youtube\.com\/user\/([\w.-]+)/)?.[1]
  const legacy = text.match(
    /youtube\.com\/(?:c\/)?(?!(?:watch|shorts|playlist|embed|results|feed|channel|user)\b)([\w.-]+)/,
  )?.[1]
  const params = { part: 'snippet,statistics,status,topicDetails', key: apiKey }
  if (id) params.id = id
  else if (handle) params.forHandle = handle
  else if (user) params.forUsername = user
  else if (legacy) params.forHandle = legacy
  else throw new Error('Paste a channel URL, @handle, or UC… id')
  const body = await get('channels', params)
  const item = (body.items ?? [])[0]
  if (!item) throw new Error('Channel not found')
  return channelFromItem(item)
}

/** PT1H2M3S -> seconds; null when unparsable (e.g. P0D live placeholders). */
export function parseDuration(iso) {
  const m = iso?.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?/)
  if (!m || (!m[1] && !m[2] && !m[3])) return null
  return (Number(m[1]) || 0) * 3600 + (Number(m[2]) || 0) * 60 + (Number(m[3]) || 0)
}

/**
 * Latest long-form uploads (UULF playlist excludes Shorts), shaped like the
 * scraper's output. 18+ age-restricted videos (ytRating) are dropped — they
 * wouldn't play in an embed anyway and this is a kids app. 2 units.
 */
export async function fetchChannelVideos(apiKey, channelId) {
  const playlist = await get('playlistItems', {
    part: 'snippet,contentDetails',
    playlistId: `UULF${channelId.slice(2)}`,
    maxResults: 50,
    key: apiKey,
  })
  const items = playlist.items ?? []
  if (!items.length) return []

  const ids = items.map(it => it.contentDetails.videoId)
  const durations = {}
  const ageRestricted = new Set()
  try {
    const details = await get('videos', { part: 'contentDetails', id: ids.join(','), key: apiKey })
    for (const v of details.items ?? []) {
      durations[v.id] = parseDuration(v.contentDetails?.duration)
      if (v.contentDetails?.contentRating?.ytRating === 'ytAgeRestricted') ageRestricted.add(v.id)
    }
  } catch (e) {
    console.warn('duration lookup failed, continuing without', e)
  }

  return items
    .filter(it => !ageRestricted.has(it.contentDetails.videoId))
    .map(it => ({
      id: it.contentDetails.videoId,
      title: it.snippet.title,
      duration: durations[it.contentDetails.videoId] ?? null,
      thumbnail: `https://i.ytimg.com/vi/${it.contentDetails.videoId}/mqdefault.jpg`,
    }))
}

function readCache() {
  try {
    return JSON.parse(localStorage.getItem(CACHE_KEY)) ?? {}
  } catch {
    return {}
  }
}

function writeCache(cache) {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(cache))
  } catch (e) {
    console.warn('video cache persist failed', e)
  }
}

export function evictChannelCache(channelId) {
  const cache = readCache()
  delete cache[channelId]
  writeCache(cache)
}

const inflight = {} // StrictMode double-effect / concurrent-render guard

/**
 * Cache-first channel videos: fresh (<24h) cache -> no network; stale/missing
 * with a key -> refetch; fetch failure or no key -> stale cache or [].
 */
export function getChannelVideosCached(apiKey, channelId) {
  const cached = readCache()[channelId]
  const fresh = cached && Date.now() - cached.fetchedAt < CACHE_TTL_MS
  if (fresh || !apiKey) return Promise.resolve(cached?.videos ?? [])

  inflight[channelId] ??= fetchChannelVideos(apiKey, channelId)
    .then(videos => {
      writeCache({ ...readCache(), [channelId]: { fetchedAt: Date.now(), videos } })
      return videos
    })
    .catch(e => {
      console.error(`fetch failed for ${channelId}, using stale cache`, e)
      return cached?.videos ?? []
    })
    .finally(() => delete inflight[channelId])
  return inflight[channelId]
}

/*
 * CLI (`npm run download` -> `node src/youtubeApi.js`): fetch approved
 * channels (channels.json) into a static public/videos.json using the same
 * client the webapp imports above, so downloader and webapp cannot drift.
 * ~3 quota units per channel per run.
 *
 * Usage: YOUTUBE_API_KEY=... node src/youtubeApi.js [--channels channels.json]
 *        [--out public/videos.json] [--seed previous-videos.json]
 *
 * --seed (the currently-deployed videos.json) is a stale per-channel fallback
 * so one broken channel or API hiccup never blanks the site.
 */
async function downloadCli() {
  // node builtins imported lazily so vite never bundles them for the browser
  const { readFile, writeFile } = await import('node:fs/promises')
  const { parseArgs } = await import('node:util')

  const { values: args } = parseArgs({
    options: {
      channels: { type: 'string', default: 'channels.json' },
      out: { type: 'string', default: 'public/videos.json' },
      seed: { type: 'string' },
    },
  })

  const KEY = process.env.YOUTUBE_API_KEY
  if (!KEY) {
    console.error('YOUTUBE_API_KEY is not set — get a YouTube Data API v3 key from')
    console.error('  https://console.cloud.google.com/apis/library/youtube.googleapis.com')
    console.error('then: cp .env.sample .env   # and paste your key after YOUTUBE_API_KEY=')
    process.exit(1)
  }

  /** bare name | @handle | UCxxxx | any youtube.com URL -> canonical https://www.youtube.com/... URL */
  const normalize = entry => {
    const e = entry.trim().replace(/\/+$/, '')
    if (new RegExp(`^${UC_ID.source}$`).test(e)) return `https://www.youtube.com/channel/${e}`
    if (e.startsWith('@')) return `https://www.youtube.com/${e}`
    if (/^[\w.-]+$/.test(e)) return `https://www.youtube.com/@${e}` // bare channel name
    return e.replace(/^(https?:\/\/)?(www\.|m\.)?youtube\.com/, 'https://www.youtube.com')
  }

  /** Previous videos.json -> {source_url: channel blob} for stale fallback. */
  const loadSeed = async path => {
    if (!path) return {}
    try {
      const prev = JSON.parse(await readFile(path, 'utf8'))
      return Object.fromEntries((prev.channels ?? []).map(ch => [ch.source_url, ch]))
    } catch (e) {
      console.warn(`seed ${path} unusable (${e.message}); continuing without`)
      return {}
    }
  }

  /** channels.json entry -> videos.json channel blob; null when it can't be fetched or seeded. */
  const download = async (entry, seed) => {
    const source_url = normalize(entry.channel)
    const ages = { min_age: entry.min_age ?? 1, max_age: entry.max_age ?? 15 }
    try {
      const ch = await resolveChannel(KEY, source_url)
      const videos = await fetchChannelVideos(KEY, ch.channel_id)
      if (!videos.length) throw new Error('no long-form uploads')
      return { ...ch, source_url, ...ages, videos }
    } catch (e) {
      const stale = seed[source_url]
      if (!stale) {
        console.warn(`${source_url}: ${e.message} — SKIPPED (no seed fallback)`)
        return null
      }
      console.warn(`${source_url}: ${e.message} — using stale seed data`)
      return { ...stale, ...ages }
    }
  }

  const entries = JSON.parse(await readFile(args.channels, 'utf8'))
  const seed = await loadSeed(args.seed)

  const channels = []
  for (const entry of entries) {
    const blob = await download(entry, seed)
    if (!blob) continue
    channels.push(blob)
    console.log(`${blob.channel_title} (ages ${blob.min_age}-${blob.max_age}): ${blob.videos.length} videos`)
  }

  const total = channels.reduce((n, ch) => n + ch.videos.length, 0)
  if (total === 0) {
    console.error('FATAL: zero videos across all channels')
    process.exit(1)
  }

  const out = { schema_version: 2, generated_at: new Date().toISOString(), channels }
  await writeFile(args.out, JSON.stringify(out, null, 1))
  console.log(`wrote ${args.out}: ${total} videos`)
}

// run only when executed directly by node — never in the browser or vitest
if (typeof process !== 'undefined' && process.argv?.[1]?.endsWith('youtubeApi.js')) downloadCli()
