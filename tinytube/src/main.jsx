/** App shell: view routing (gallery | player | gate | settings | quota), the
 * parent gate dispatch, and the browser boot. */

import { StrictMode, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import 'bootstrap/dist/css/bootstrap.min.css'
import './styles.css'
import { useSettings, useVideos, useWatchStore, verify, isBiometricAvailable, windowUsed } from './lib.js'
import Gallery from './gallery.jsx'
import PlayerView from './player.jsx'
import Settings from './settings.jsx'
import { EnrollGate, MathGate, QuotaGate } from './landing.jsx'

export default function App() {
  const store = useSettings()
  const { db, channels, error } = useVideos(store.settings)
  const watchStore = useWatchStore()
  const [current, setCurrent] = useState(null) // video being played, or null
  const [view, setView] = useState('gallery') // 'gallery' | 'gate' | 'settings' | 'quota'
  const [biometric, setBiometric] = useState(null) // null = still checking

  useEffect(() => {
    isBiometricAvailable().then(setBiometric)
  }, [])

  // player/gate/settings are history entries so the browser back button
  // (and iOS edge-swipe) lands back on the gallery instead of leaving the app
  useEffect(() => {
    const onPop = () => {
      setCurrent(null)
      setView('gallery')
    }
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])

  const open = fn => arg => {
    history.pushState({ tinytube: true }, '')
    fn(arg)
  }
  const close = () => history.back() // popstate does the state reset

  if (error) {
    return (
      <div className="d-flex vh-100 align-items-center justify-content-center text-center p-4">
        <div>
          <i className="fa-sharp-duotone fa-regular fa-cloud-exclamation fa-3x mb-3" />
          <p className="fs-4">Could not load videos. Try again later!</p>
        </div>
      </div>
    )
  }

  if (!channels || biometric === null) {
    return (
      <div className="d-flex vh-100 align-items-center justify-content-center">
        <div className="spinner-border text-danger" role="status" />
      </div>
    )
  }

  // first run on a biometric-capable device: enroll before anything else
  if (biometric && !store.settings.passkeyId) {
    return <EnrollGate onEnrolled={store.setPasskey} />
  }

  if (current) {
    return (
      <PlayerView
        video={current}
        watchStore={watchStore}
        quotaMins={store.settings.quotaMins}
        onExit={close}
        onQuotaExhausted={() => {
          // same history depth: the player's entry becomes the quota screen's,
          // so back still lands on the gallery
          setCurrent(null)
          setView('quota')
        }}
      />
    )
  }

  if (view === 'gate') {
    return (
      <MathGate
        onPass={() => setView('settings')} // same history depth: back from settings -> gallery
        onFail={close}
      />
    )
  }

  if (view === 'settings') {
    return <Settings db={db} store={store} watchStore={watchStore} onDone={close} />
  }

  // enrolled device -> OS biometric prompt (called inside the tap handler to
  // keep iOS user activation); otherwise the math gate bootstraps enrollment.
  // Returns the view to show, or null (biometric cancelled/failed).
  const parentGate = async () => {
    if (store.settings.passkeyId) return (await verify(store.settings.passkeyId)) ? 'settings' : null
    return 'gate'
  }

  if (view === 'quota') {
    return (
      <QuotaGate
        onParents={async () => {
          const v = await parentGate()
          if (v) setView(v) // same history depth, like MathGate onPass
        }}
        onBack={close}
      />
    )
  }

  const onParents = async () => {
    const v = await parentGate()
    if (v) open(() => setView(v))()
  }

  return (
    <Gallery
      channels={channels}
      watchStore={watchStore}
      onPlay={video => {
        // checked at tap time, not render time: a 12h window that expires while
        // the gallery sits idle must unblock immediately (expiry is lazy)
        const over = windowUsed(watchStore.usage) >= store.settings.quotaMins * 60
        if (over) open(() => setView('quota'))()
        else open(setCurrent)(video)
      }}
      onParents={onParents}
    />
  )
}

// browser boot; absent in jsdom where tests import <App/> directly
const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
}
