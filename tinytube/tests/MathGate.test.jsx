import { render, screen, fireEvent, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { MathGate } from '../src/landing.jsx'

const challenge = { a: 47, b: 78, answer: 125, choices: [115, 125, 126, 124] }
// partial mock: makeChallenge lives in lib.js next to the hooks, which must stay real
vi.mock('../src/lib.js', async importOriginal => ({
  ...(await importOriginal()),
  makeChallenge: () => challenge,
}))

let onPass, onFail
beforeEach(() => {
  vi.useFakeTimers()
  onPass = vi.fn()
  onFail = vi.fn()
  render(<MathGate onPass={onPass} onFail={onFail} />)
})
afterEach(() => vi.useRealTimers())

describe('MathGate', () => {
  it('shows the question and all four choices', () => {
    expect(screen.getByText('47 + 78 = ?')).toBeTruthy()
    for (const c of challenge.choices) expect(screen.getByText(String(c))).toBeTruthy()
  })

  it('fails when the 5 second countdown runs out', () => {
    act(() => vi.advanceTimersByTime(5000))
    expect(onFail).toHaveBeenCalled()
    expect(onPass).not.toHaveBeenCalled()
  })

  it('fails immediately on a wrong answer', () => {
    fireEvent.click(screen.getByText('126'))
    expect(onFail).toHaveBeenCalledTimes(1)
    expect(onPass).not.toHaveBeenCalled()
  })

  it('passes on the correct answer and the countdown stops mattering', () => {
    fireEvent.click(screen.getByText('125'))
    expect(onPass).toHaveBeenCalledTimes(1)
    expect(onFail).not.toHaveBeenCalled()
  })
})
