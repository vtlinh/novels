import { render, screen, fireEvent, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import PlayerView, { VideoPlayer } from '../src/player.jsx'

// Stub the YouTube iframe: capture the lifecycle callbacks so tests can fire
// onReady / onStateChange / onError exactly like the real player would.
let yt
vi.mock('react-youtube', () => ({
  default: props => {
    yt = props
    return <div data-testid="yt-stub" />
  },
}))

const video = { id: 'abc123', title: 'Test Video', duration: 100, thumbnail: 'thumb.jpg' }
const { ENDED, PLAYING, PAUSED, BUFFERING } = { ENDED: 0, PLAYING: 1, PAUSED: 2, BUFFERING: 3 }

function fakePlayer() {
  return {
    pos: 5, // playback position; quota tests advance it to simulate watching
    playVideo: vi.fn(),
    pauseVideo: vi.fn(),
    seekTo: vi.fn(),
    getCurrentTime() {
      return this.pos
    },
    getDuration: () => 100,
  }
}

let watchStore, onExit, onQuotaExhausted
beforeEach(() => {
  watchStore = {
    watched: {},
    usage: { window: { start: null, secs: 0 }, days: {}, hours: {} },
    saveProgress: vi.fn(),
    markCompleted: vi.fn(),
    // stateful like the real hook: the quota check reads usage back on each tick
    addWatchTime: vi.fn(secs => {
      watchStore.usage.window.start ??= Date.now()
      watchStore.usage.window.secs += secs
    }),
  }
  onExit = vi.fn()
  onQuotaExhausted = vi.fn()
})

const renderPlayer = (Component = VideoPlayer, quotaMins = 60) =>
  render(
    <Component
      video={video}
      watchStore={watchStore}
      quotaMins={quotaMins}
      onExit={onExit}
      onQuotaExhausted={onQuotaExhausted}
    />,
  )

describe('before the player is ready', () => {
  beforeEach(() => renderPlayer())

  // regression: play clicks used to be silently dropped while playerRef was null,
  // making the player look broken until YouTube's iframe API finished loading
  it('shows a loading spinner instead of a play button', () => {
    expect(screen.getByRole('status')).toBeTruthy()
    expect(screen.queryByLabelText('Play')).toBeNull()
  })

  it('ignores control clicks without crashing or exiting', () => {
    fireEvent.click(screen.getByLabelText('Play or pause'))
    expect(onExit).not.toHaveBeenCalled()
  })

})

it('can escape back to the gallery via the top bar while loading', () => {
  renderPlayer(PlayerView)
  fireEvent.click(screen.getByLabelText('Back'))
  expect(onExit).toHaveBeenCalled()
})

describe('once the player is ready', () => {
  let player
  beforeEach(() => {
    renderPlayer()
    player = fakePlayer()
    act(() => yt.onReady({ target: player }))
  })

  it('auto-plays and drops the loading spinner', () => {
    expect(player.playVideo).toHaveBeenCalled()
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('keeps the opaque overlay off while playing and buffering', () => {
    for (const state of [PLAYING, BUFFERING]) {
      act(() => yt.onStateChange({ data: state }))
      expect(screen.queryByLabelText('Play')).toBeNull()
    }
  })

  it('covers the iframe with the paused overlay when paused', () => {
    act(() => yt.onStateChange({ data: PAUSED }))
    fireEvent.click(screen.getByLabelText('Play'))
    expect(player.playVideo).toHaveBeenCalledTimes(2) // onReady + our tap
    expect(screen.queryByLabelText('Play')).toBeNull() // dismissed optimistically
  })

  it('marks the video watched and returns to the gallery on ENDED', () => {
    act(() => yt.onStateChange({ data: ENDED }))
    expect(watchStore.markCompleted).toHaveBeenCalledWith(video.id)
    expect(onExit).toHaveBeenCalled()
  })
})

describe('on player error', () => {
  // regression: errors used to call onExit immediately, bouncing the child
  // back to the gallery with no explanation
  it('shows the error code instead of silently exiting', () => {
    renderPlayer()
    vi.spyOn(console, 'error').mockImplementation(() => {})
    act(() => yt.onError({ data: 150 }))
    expect(onExit).not.toHaveBeenCalled()
    expect(screen.getByText(/error 150/)).toBeTruthy()
    fireEvent.click(screen.getByText(/Pick another video/))
    expect(onExit).toHaveBeenCalled()
  })
})

describe('watch quota', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  const startPlaying = (quotaMins = 60) => {
    renderPlayer(VideoPlayer, quotaMins)
    const player = fakePlayer()
    act(() => yt.onReady({ target: player }))
    act(() => yt.onStateChange({ data: PLAYING }))
    act(() => vi.advanceTimersByTime(1000)) // sync tick: deltas are counted from here
    return player
  }

  // advance the clock and the playback position together, like real playback
  const watchFor = (player, secs) =>
    act(() => {
      for (let i = 0; i < secs; i++) {
        player.pos += 1
        vi.advanceTimersByTime(1000)
      }
    })

  it('flushes watched seconds every 5s and the remainder on pause', () => {
    const player = startPlaying()
    watchFor(player, 5)
    expect(watchStore.addWatchTime).toHaveBeenCalledWith(5)
    watchFor(player, 2)
    act(() => yt.onStateChange({ data: PAUSED })) // flushes the 2s remainder
    expect(watchStore.addWatchTime).toHaveBeenLastCalledWith(2)
    watchStore.addWatchTime.mockClear()
    act(() => vi.advanceTimersByTime(10_000)) // paused: position frozen, time is free
    expect(watchStore.addWatchTime).not.toHaveBeenCalled()
    expect(onQuotaExhausted).not.toHaveBeenCalled()
  })

  it('hard-stops the instant the quota runs out mid-video', () => {
    watchStore.usage.window = { start: Date.now(), secs: 3570 } // 30s left of 60min
    const player = startPlaying()
    watchFor(player, 29)
    expect(onQuotaExhausted).not.toHaveBeenCalled()
    watchFor(player, 1)
    expect(onQuotaExhausted).toHaveBeenCalled()
    expect(watchStore.usage.window.secs).toBe(3600) // spent seconds flushed before exiting
  })

  // regression: quota used to count 1s per timer tick and only while the last
  // iframe event said PLAYING. iOS home-screen apps throttle timers and drop
  // state events, so the countdown froze while the video kept playing.
  it('back-fills watch time when timer ticks are throttled away', () => {
    const player = startPlaying()
    act(() => {
      player.pos += 30
      vi.setSystemTime(Date.now() + 29_000) // 29s pass with no tick firing
      vi.advanceTimersByTime(1000) // the one tick that finally fires
    })
    expect(watchStore.addWatchTime).toHaveBeenCalledWith(30)
  })

  it('counts watch time even if the PLAYING event never arrives', () => {
    renderPlayer(VideoPlayer)
    const player = fakePlayer()
    act(() => yt.onReady({ target: player })) // no onStateChange at all
    act(() => vi.advanceTimersByTime(1000)) // sync tick
    watchFor(player, 5)
    expect(watchStore.addWatchTime).toHaveBeenCalledWith(5)
  })

  it('caps a position jump at wall-clock elapsed so it cannot over-charge', () => {
    const player = startPlaying()
    act(() => {
      player.pos += 50 // stale/glitchy player reports a big jump within one tick
      vi.advanceTimersByTime(1000)
    })
    watchFor(player, 4) // 1s counted above + 4 honest seconds = first 5s flush
    expect(watchStore.addWatchTime).toHaveBeenCalledWith(5)
  })
})

describe('top bar countdown', () => {
  const quotaBar = () => document.querySelector('.player-topbar .progress-bar')

  it('shows a full green bar on a fresh quota', () => {
    renderPlayer(PlayerView)
    expect(screen.getByText('1h')).toBeTruthy() // default 60min quota
    expect(quotaBar().className).toContain('bg-success')
    expect(quotaBar().style.width).toBe('100%')
  })

  it('drains and turns red as the quota runs out', () => {
    watchStore.usage.window = { start: Date.now(), secs: 3570 }
    renderPlayer(PlayerView)
    expect(screen.getByText('1m')).toBeTruthy()
    expect(quotaBar().className).toContain('bg-danger')
  })

  it('never goes negative', () => {
    watchStore.usage.window = { start: Date.now(), secs: 9999 }
    renderPlayer(PlayerView)
    expect(screen.getByText('0m')).toBeTruthy()
    expect(quotaBar().style.width).toBe('0%')
  })
})
