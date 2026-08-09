import { describe, it, expect } from 'vitest'
import { makeChallenge } from '../src/lib.js'

describe('makeChallenge', () => {
  it('holds the invariants across many random challenges', () => {
    for (let i = 0; i < 500; i++) {
      const { a, b, answer, choices } = makeChallenge()
      expect(a).toBeGreaterThanOrEqual(10)
      expect(a).toBeLessThanOrEqual(99)
      expect(b).toBeGreaterThanOrEqual(10)
      expect(b).toBeLessThanOrEqual(99)
      expect(answer).toBe(a + b)
      expect(choices).toHaveLength(4)
      expect(new Set(choices).size).toBe(4)
      expect(choices.filter(c => c === answer)).toHaveLength(1)
      for (const c of choices) {
        expect(c).toBeGreaterThan(0)
        expect(Math.abs(c - answer)).toBeLessThanOrEqual(20) // plausible mistakes only
      }
    }
  })

  it('is deterministic for a fixed rng', () => {
    const rand = () => 0.5
    const c1 = makeChallenge(rand)
    const c2 = makeChallenge(rand)
    expect(c1).toEqual(c2)
  })
})
