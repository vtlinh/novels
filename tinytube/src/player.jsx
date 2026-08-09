/** Everything video: the fullscreen player view, the kid-proofed YouTube
 * embed, and its overlays/controls. */

import { useCallback, useEffect, useRef, useState } from 'react'
import YouTube from 'react-youtube'
import { fmtMins, windowUsed } from './lib.js'

export default function PlayerView({ video, watchStore, quotaMins, onExit, onQuotaExhausted }) {
  const quotaSecs = quotaMins * 60
  const secsLeft = Math.max(0, quotaSecs - windowUsed(watchStore.usage))
  const pctLeft = quotaSecs ? (secsLeft / quotaSecs) * 100 : 0

  // best-effort landscape: fullscreen + orientation lock works on Android;
  // iOS has neither, so CSS rotates the whole view in portrait (see styles)
  useEffect(() => {
    ;(async () => {
      try {
        await document.documentElement.requestFullscreen?.()
        await screen.orientation?.lock?.('landscape')
      } catch {
        /* unsupported (iOS Safari) — the portrait CSS rotation covers it */
      }
    })()
    return () => {
      screen.orientation?.unlock?.()
      if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
    }
  }, [])

  return (
    <div className="player-view d-flex flex-column">
      <nav className="player-topbar d-flex align-items-center gap-3 px-2">
        <button type="button" className="btn btn-ctl btn-ctl-sm" aria-label="Back" onClick={onExit}>
          <i className="fa-sharp-duotone fa-regular fa-arrow-left" />
        </button>
        <span className="fs-5 fw-bold">
          <i className="fa-duotone fa-regular fa-tv-retro me-2 text-danger" />
          TinyTube
        </span>
        <span className="ms-auto d-flex align-items-center gap-2 text-white-50">
          {/* not fa-utility-duo fa-semibold: the token CSS ships no utility
              fonts or 600 weight, so the duo layers render as two glyphs */}
          <i className="fa-sharp-duotone fa-regular fa-stopwatch" />
          {/* drains as quota is spent; recolors so it reads without the number */}
          <div className="progress" style={{ width: 100, height: 8 }} title="Watch time left">
            <div
              className={`progress-bar ${pctLeft > 50 ? 'bg-success' : pctLeft > 20 ? 'bg-warning' : 'bg-danger'}`}
              style={{ width: `${pctLeft}%` }}
            />
          </div>
          {fmtMins(Math.ceil(secsLeft / 60))}
        </span>
      </nav>
      <div className="player-stage position-relative flex-grow-1">
        <VideoPlayer
          video={video}
          watchStore={watchStore}
          quotaMins={quotaMins}
          onExit={onExit}
          onQuotaExhausted={onQuotaExhausted}
        />
      </div>
    </div>
  )
}

const OPTS = {
  // NOT youtube-nocookie.com: its privacy mode misfires as "error 150,
  // embedding disallowed" on mobile Safari for videos that embed fine
  width: '100%',
  height: '100%',
  playerVars: {
    rel: 0,
    playsinline: 1,
    controls: 0,
    disablekb: 1,
    fs: 0,
    iv_load_policy: 3,
    autoplay: 1,
    modestbranding: 1,
    origin: window.location.origin, // reliable postMessage event delivery
  },
}

const { ENDED, PLAYING, BUFFERING } = { ENDED: 0, PLAYING: 1, BUFFERING: 3 }
const RESUME_MIN = 10 // don't bother resuming the first seconds
const RESUME_TAIL = 20 // ...or into the credits

export function VideoPlayer({ video, watchStore, quotaMins, onExit, onQuotaExhausted }) {
  const playerRef = useRef(null)
  const [ready, setReady] = useState(false)
  const [error, setError] = useState(null)
  const [playerState, setPlayerState] = useState(-1)
  const [progress, setProgress] = useState({ pos: 0, dur: video.duration ?? 0 })
  const [showControls, setShowControls] = useState(true)
  const hideTimer = useRef(null)
  const pending = useRef(0) // watched seconds not yet flushed to the quota store
  const lastTick = useRef(null) // { at: epoch ms, pos: playback secs } at the previous poll

  const playing = playerState === PLAYING
  // don't flash the opaque overlay while buffering into playback
  const active = playing || playerState === BUFFERING

  const save = useCallback(() => {
    if (pending.current) {
      watchStore.addWatchTime(pending.current)
      pending.current = 0
    }
    const p = playerRef.current
    if (!p) return
    const pos = Math.floor(p.getCurrentTime() ?? 0)
    const dur = Math.floor(p.getDuration() ?? 0)
    if (pos > 0 && dur > 0) watchStore.saveProgress(video.id, pos, dur)
  }, [video.id, watchStore])

  // poll every second; watch time = playback-position delta, NOT ticks gated
  // on the PLAYING event: iOS standalone throttles/suspends timers and drops
  // iframe state events, either of which froze the quota while the video
  // kept playing. Position only advances by actually watching (there is no
  // scrubber), a paused video contributes 0, and a late tick back-fills the
  // whole gap. Capped at wall-clock elapsed so a stale player object can
  // never over-charge. Persist every 5 watched seconds and on pause/hide/unmount.
  useEffect(() => {
    const interval = setInterval(() => {
      const p = playerRef.current
      if (!p) return
      const now = Date.now()
      const pos = p.getCurrentTime() ?? 0
      setProgress({ pos, dur: p.getDuration() ?? 0 })
      const prev = lastTick.current
      lastTick.current = { at: now, pos }
      if (!prev) return
      const watched = Math.min(pos - prev.pos, (now - prev.at) / 1000)
      if (watched <= 0) return
      pending.current += watched
      // hard stop the moment the quota runs out — otherwise a video started
      // with 1 min left plays to the end for free
      if (windowUsed(watchStore.usage) + pending.current >= quotaMins * 60) {
        save()
        onQuotaExhausted()
      } else if (pending.current >= 5) {
        save()
      }
    }, 1000)
    const onHide = () => save()
    document.addEventListener('visibilitychange', onHide)
    window.addEventListener('pagehide', onHide)
    return () => {
      clearInterval(interval)
      document.removeEventListener('visibilitychange', onHide)
      window.removeEventListener('pagehide', onHide)
      save()
    }
  }, [save, watchStore, quotaMins, onQuotaExhausted])

  const pokeControls = useCallback(() => {
    setShowControls(true)
    clearTimeout(hideTimer.current)
    hideTimer.current = setTimeout(() => setShowControls(false), 3000)
  }, [])

  const onReady = e => {
    playerRef.current = e.target
    setReady(true)
    const entry = watchStore.watched[video.id]
    if (entry && !entry.completed && entry.pos > RESUME_MIN && entry.pos < entry.dur - RESUME_TAIL) {
      e.target.seekTo(entry.pos, true)
    }
    e.target.playVideo()
    pokeControls()
  }

  const onStateChange = e => {
    setPlayerState(e.data)
    if (e.data === ENDED) {
      watchStore.markCompleted(video.id)
      onExit()
    } else if (e.data !== PLAYING && e.data !== BUFFERING) {
      save()
    }
  }

  const onError = e => {
    console.error(`YouTube player error ${e.data} for video ${video.id}`)
    setError(e.data)
  }

  const togglePlay = () => {
    const p = playerRef.current
    if (!p) return
    if (playing) {
      p.pauseVideo()
    } else {
      setPlayerState(BUFFERING) // dismiss the overlay immediately; ENDED/PAUSED events correct us if wrong
      p.playVideo()
    }
    pokeControls()
  }

  return (
    <div className="video-player position-relative w-100 h-100 bg-black">
      <YouTube
        videoId={video.id}
        opts={OPTS}
        className="yt-frame"
        iframeClassName="yt-iframe"
        onReady={onReady}
        onStateChange={onStateChange}
        onError={onError}
      />
      <TouchShield onTap={() => (showControls ? setShowControls(false) : pokeControls())} />
      {error !== null && <ErrorOverlay video={video} code={error} onExit={onExit} />}
      {error === null && !ready && <LoadingOverlay video={video} />}
      {error === null && ready && !active && <PausedOverlay video={video} onPlay={togglePlay} />}
      {(showControls || (ready && !active)) && (
        <ControlsBar playing={playing} progress={progress} onTogglePlay={togglePlay} />
      )}
    </div>
  )
}

/**
 * Transparent layer over the whole iframe: every touch lands here instead of
 * on YouTube's UI. A tap only toggles our own controls.
 */
function TouchShield({ onTap }) {
  return <div className="touch-shield" onPointerUp={onTap} />
}

function fmt(seconds) {
  const s = Math.floor(seconds ?? 0)
  const m = Math.floor(s / 60)
  return `${m}:${String(s % 60).padStart(2, '0')}`
}

function ControlsBar({ playing, progress, onTogglePlay }) {
  const pct = progress.dur > 0 ? (progress.pos / progress.dur) * 100 : 0
  return (
    <div className="controls-bar d-flex flex-column gap-2 p-3">
      {/* display only, deliberately not a scrubber: start and stop are the only controls */}
      <div className="progress" style={{ height: 6 }}>
        <div className="progress-bar bg-danger" style={{ width: `${pct}%` }} />
      </div>
      <div className="d-flex align-items-center gap-3">
        <span className="text-white-50 small">{fmt(progress.pos)} / {fmt(progress.dur)}</span>
        <button type="button" className="btn btn-ctl ms-auto" onClick={onTogglePlay} aria-label="Play or pause">
          <i className={`fa-sharp-duotone fa-regular ${playing ? 'fa-pause' : 'fa-play'}`} />
        </button>
      </div>
    </div>
  )
}

/**
 * Opaque overlay shown whenever the video isn't playing, so YouTube's paused
 * "More videos" tray and end screen can never be seen or tapped.
 */
function PausedOverlay({ video, onPlay }) {
  return (
    <div className="paused-overlay" style={{ backgroundImage: `url(${video.thumbnail})` }}>
      <div className="paused-overlay-scrim d-flex flex-column align-items-center justify-content-center gap-3 p-4">
        <button type="button" className="btn btn-play-big" onClick={onPlay} aria-label="Play">
          <i className="fa-sharp-duotone fa-regular fa-play" />
        </button>
      </div>
    </div>
  )
}

function LoadingOverlay({ video }) {
  return (
    <div className="paused-overlay" style={{ backgroundImage: `url(${video.thumbnail})` }}>
      <div className="paused-overlay-scrim d-flex flex-column align-items-center justify-content-center gap-3 p-4">
        <div className="spinner-border text-danger" role="status" />
      </div>
    </div>
  )
}

// 101/150 = embedding disabled by the channel; 2/5/100 = bad/unplayable video
function ErrorOverlay({ video, code, onExit }) {
  return (
    <div className="paused-overlay" style={{ backgroundImage: `url(${video.thumbnail})` }}>
      <div className="paused-overlay-scrim d-flex flex-column align-items-center justify-content-center gap-3 p-4">
        <i className="fa-sharp-duotone fa-regular fa-face-frown fa-3x" />
        <div className="fs-5 text-center">This video can't play here (error {code})</div>
        <button type="button" className="btn btn-danger btn-lg" onClick={onExit}>
          <i className="fa-sharp-duotone fa-regular fa-grid-2 me-2" />
          Pick another video
        </button>
      </div>
    </div>
  )
}
