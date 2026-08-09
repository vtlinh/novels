import { describe, it, expect } from 'vitest'
import { gallerySort, fraction, windowUsed, accrueUsage, usageStats, fmtMins, QUOTA_WINDOW_MS } from '../src/lib.js'

const channels = [
  { channel_title: 'Busy', videos: [{ id: 'b1' }, { id: 'b2' }, { id: 'b3' }, { id: 'b4' }] },
  { channel_title: 'Quiet', videos: [{ id: 'q1' }, { id: 'q2' }] },
]

describe('gallerySort', () => {
  it('orders: continue-watching, fresh round-robin, abandoned, watched', () => {
    const watched = {
      b2: { pos: 80, dur: 100, completed: false }, // 80% -> continue watching
      q1: { pos: 96, dur: 100, completed: true }, // watched -> last
      b3: { pos: 5, dur: 100, completed: false }, // 5% -> abandoned
    }
    expect(gallerySort(channels, watched).map(v => v.id)).toEqual([
      'b2', // continue watching
      'b1', 'q2', 'b4', // fresh, interleaved across channels
      'b3', // abandoned
      'q1', // watched
    ])
  })

  it('interleaves channels so a busy channel cannot flood a quiet one', () => {
    const ids = gallerySort(channels, {}).map(v => v.id)
    expect(ids).toEqual(['b1', 'q1', 'b2', 'q2', 'b3', 'b4'])
  })

  it('sorts continue-watching by closeness to the end', () => {
    const watched = {
      b1: { pos: 30, dur: 100, completed: false },
      q1: { pos: 90, dur: 100, completed: false },
    }
    expect(gallerySort(channels, watched).slice(0, 2).map(v => v.id)).toEqual(['q1', 'b1'])
  })
})

describe('fraction', () => {
  it('treats completed as fully watched even without positions', () => {
    expect(fraction({ pos: 0, dur: 0, completed: true })).toBe(1)
    expect(fraction(undefined)).toBe(0)
  })
})

// local-noon anchor keeps day boundaries away from timezone edges;
// Jan 15 2026 is a Thursday, so the Sunday-start week begins Jan 11
const NOW = new Date(2026, 0, 15, 12, 0, 0).getTime()
const HOUR = 3600_000
const empty = () => ({ window: { start: null, secs: 0 }, days: {}, hours: {} })

describe('windowUsed', () => {
  it('is 0 with no window, after 12h expiry, and when the clock ran backwards', () => {
    expect(windowUsed(empty(), NOW)).toBe(0)
    expect(windowUsed({ window: { start: NOW - QUOTA_WINDOW_MS, secs: 500 } }, NOW)).toBe(0)
    expect(windowUsed({ window: { start: NOW + 1000, secs: 500 } }, NOW)).toBe(0)
  })

  it('reports the seconds of a live window', () => {
    expect(windowUsed({ window: { start: NOW - HOUR, secs: 500 } }, NOW)).toBe(500)
  })
})

describe('accrueUsage', () => {
  it('starts the window at the first counted second and accumulates within it', () => {
    const first = accrueUsage(empty(), 5, NOW)
    expect(first.window).toEqual({ start: NOW, secs: 5 })
    const second = accrueUsage(first, 10, NOW + HOUR)
    expect(second.window).toEqual({ start: NOW, secs: 15 })
  })

  it('restarts the window after 12h expiry and on backwards clocks', () => {
    const usage = accrueUsage(empty(), 5, NOW)
    expect(accrueUsage(usage, 3, NOW + QUOTA_WINDOW_MS).window).toEqual({ start: NOW + QUOTA_WINDOW_MS, secs: 3 })
    expect(accrueUsage(usage, 3, NOW - 1000).window).toEqual({ start: NOW - 1000, secs: 3 })
  })

  it('bumps day and hour buckets and prunes stale ones', () => {
    const usage = {
      ...empty(),
      days: { '2024-01-01': 60 }, // > 366 days old -> pruned
      hours: { [Math.floor(NOW / HOUR) - 49]: 60 }, // > 48h old -> pruned
    }
    const next = accrueUsage(usage, 5, NOW)
    expect(next.days).toEqual({ '2026-01-15': 5 })
    expect(next.hours).toEqual({ [Math.floor(NOW / HOUR)]: 5 })
  })
})

describe('usageStats', () => {
  it('computes session, rolling 24h, and Sunday-start WTD/MTD/YTD', () => {
    const nowHour = Math.floor(NOW / HOUR)
    const usage = {
      window: { start: NOW - HOUR, secs: 42 },
      days: {
        '2025-12-31': 50, // last year -> YTD excludes
        '2026-01-10': 600, // Saturday -> before the week
        '2026-01-12': 300, // Monday -> in the week
        '2026-01-15': 100, // today
      },
      hours: { [nowHour]: 100, [nowHour - 23]: 200, [nowHour - 24]: 400 },
    }
    expect(usageStats(usage, NOW)).toEqual({ session: 42, last24h: 300, wtd: 400, mtd: 1000, ytd: 1000 })
  })

  it('is all zeros for fresh usage', () => {
    expect(usageStats(empty(), NOW)).toEqual({ session: 0, last24h: 0, wtd: 0, mtd: 0, ytd: 0 })
  })

  it('survives a JSON round-trip (localStorage persistence)', () => {
    const usage = accrueUsage(accrueUsage(empty(), 5, NOW), 7, NOW + 1000)
    expect(usageStats(JSON.parse(JSON.stringify(usage)), NOW + 1000)).toEqual(usageStats(usage, NOW + 1000))
  })
})

describe('fmtMins', () => {
  it('formats hours and minutes compactly', () => {
    expect(fmtMins(180)).toBe('3h')
    expect(fmtMins(105)).toBe('1h 45m')
    expect(fmtMins(45)).toBe('45m')
    expect(fmtMins(0)).toBe('0m')
  })
})
