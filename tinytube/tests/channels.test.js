import { describe, it, expect } from 'vitest'
import { overlaps, curatedChannels, mergeChannels } from '../src/lib.js'
import { DEFAULTS } from '../src/lib.js'

// videos.json v2: flat channel list, each with its own age range
const db = {
  channels: [
    { channel_id: 'UCa', channel_title: 'Toddler', min_age: 2, max_age: 4, videos: [{ id: 'v1' }] },
    { channel_id: 'UCb', channel_title: 'Preschool', min_age: 3, max_age: 7, videos: [{ id: 'v2' }] },
    { channel_id: 'UCc', channel_title: 'School', min_age: 5, max_age: 10, videos: [{ id: 'v3' }] },
  ],
}

const settings = (overrides = {}) => ({ ...DEFAULTS, ...overrides })

describe('overlaps', () => {
  it('is inclusive at the boundaries', () => {
    expect(overlaps([4, 4], 2, 4)).toBe(true)
    expect(overlaps([4, 4], 3, 7)).toBe(true)
    expect(overlaps([4, 4], 5, 10)).toBe(false)
  })
})

describe('mergeChannels', () => {
  it('default range shows everything', () => {
    expect(mergeChannels(db, {}, settings()).map(c => c.channel_id)).toEqual(['UCa', 'UCb', 'UCc'])
  })

  it('filters channels by age overlap', () => {
    expect(mergeChannels(db, {}, settings({ ageRange: [5, 6] })).map(c => c.channel_id)).toEqual([
      'UCb', 'UCc',
    ])
  })

  it('excludes hidden channels', () => {
    const s = settings({ overrides: { UCb: { hidden: true } } })
    expect(mergeChannels(db, {}, s).map(c => c.channel_id)).toEqual(['UCa', 'UCc'])
  })

  it('excludes toggled-off channels even when their age range matches', () => {
    const s = settings({ overrides: { UCb: { disabled: true } } })
    expect(mergeChannels(db, {}, s).map(c => c.channel_id)).toEqual(['UCa', 'UCc'])
  })

  it('excludes toggled-off custom channels', () => {
    const custom = [{ channel_id: 'UCx', channel_title: 'Off', min_age: 1, max_age: 15, disabled: true }]
    const merged = mergeChannels(db, {}, settings({ customChannels: custom }))
    expect(merged.find(c => c.channel_id === 'UCx')).toBeUndefined()
  })

  it('re-enabling via disabled:false restores the channel', () => {
    const s = settings({ overrides: { UCb: { disabled: false } } })
    expect(mergeChannels(db, {}, s).map(c => c.channel_id)).toEqual(['UCa', 'UCb', 'UCc'])
  })

  it('applies parent age edits to curated channels', () => {
    const s = settings({ overrides: { UCa: { min_age: 6, max_age: 12 } }, ageRange: [5, 6] })
    // UCa's own range 2-4 wouldn't overlap [5,6], but the edit 6-12 does
    expect(mergeChannels(db, {}, s).find(c => c.channel_id === 'UCa')).toMatchObject({
      min_age: 6,
      max_age: 12,
    })
  })

  it('shapes custom channels like curated ones and filters by their own range', () => {
    const custom = [
      { channel_id: 'UCx', channel_title: 'Custom', min_age: 1, max_age: 15 },
      { channel_id: 'UCy', channel_title: 'Teen', min_age: 13, max_age: 15 },
    ]
    const merged = mergeChannels(db, { UCx: [{ id: 'cv1' }] }, settings({ customChannels: custom, ageRange: [3, 8] }))
    expect(merged.find(c => c.channel_id === 'UCx')).toMatchObject({
      channel_title: 'Custom',
      videos: [{ id: 'cv1' }],
    })
    expect(merged.find(c => c.channel_id === 'UCy')).toBeUndefined()
  })

  it('drops videos shorter than minVideoMins, counting unknown durations as too short', () => {
    const withDurations = {
      channels: [
        {
          channel_id: 'UCa',
          channel_title: 'Mixed',
          min_age: 1,
          max_age: 15,
          videos: [{ id: 'short', duration: 299 }, { id: 'long', duration: 300 }, { id: 'unknown' }],
        },
      ],
    }
    const merged = mergeChannels(withDurations, {}, settings({ minVideoMins: 5 }))
    expect(merged[0].videos.map(v => v.id)).toEqual(['long'])
  })

  it('minVideoMins of 0 keeps videos with unknown durations', () => {
    const merged = mergeChannels(db, {}, settings())
    expect(merged.map(c => c.videos.length)).toEqual([1, 1, 1])
  })

  it('curated wins when a parent adds an already-curated channel', () => {
    const custom = [{ channel_id: 'UCa', channel_title: 'Dup', min_age: 1, max_age: 15 }]
    const merged = mergeChannels(db, {}, settings({ customChannels: custom }))
    expect(merged.filter(c => c.channel_id === 'UCa')).toHaveLength(1)
    expect(merged.find(c => c.channel_id === 'UCa').channel_title).toBe('Toddler')
  })
})

describe('curatedChannels', () => {
  it('carries hidden flags from overrides so editors can see them', () => {
    const chans = curatedChannels(db, { UCc: { hidden: true } })
    expect(chans.find(c => c.channel_id === 'UCc').hidden).toBe(true)
  })
})
