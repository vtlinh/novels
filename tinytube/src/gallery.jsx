/** The kid-facing video grid. */

import { useMemo } from 'react'
import { gallerySort, fraction, WATCHED_THRESHOLD } from './lib.js'

export default function Gallery({ channels, watchStore, onPlay, onParents }) {
  const videos = useMemo(
    () => gallerySort(channels, watchStore.watched),
    [channels, watchStore.watched],
  )

  return (
    <div className="gallery">
      <nav className="gallery-toolbar d-flex align-items-center px-3 py-2">
        <span className="fs-4 fw-bold me-auto">
          {/* not fa-jelly-duo: the kit token only serves classic/duotone/sharp
              fonts, so jelly-duo renders its two layers as two glyphs */}
          <i className="fa-duotone fa-regular fa-tv-retro me-2 text-danger" />
          TinyTube
        </span>
        <button
          type="button"
          className="btn btn-lg"
          aria-label="Parents"
          onClick={onParents}
        >
          <i className="fa-sharp-duotone fa-solid fa-remote text-danger" />
        </button>
      </nav>
      <div className="container-fluid py-3">
        <div className="row g-3">
          {videos.map(video => (
            <div key={video.id} className="col-4 col-md-3 col-lg-2">
              <VideoCard
                video={video}
                entry={watchStore.watched[video.id]}
                onPlay={() => onPlay(video)}
              />
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

function formatDuration(seconds) {
  if (!seconds) return null
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return m >= 60
    ? `${Math.floor(m / 60)}:${String(m % 60).padStart(2, '0')}:${String(s).padStart(2, '0')}`
    : `${m}:${String(s).padStart(2, '0')}`
}

function VideoCard({ video, entry, onPlay }) {
  const f = fraction(entry)
  const isWatched = f > WATCHED_THRESHOLD

  return (
    <button type="button" className="video-card card w-100 border-0 p-0" onClick={onPlay}>
      <div className="position-relative">
        <img src={video.thumbnail} alt="" className="card-img-top" loading="lazy" />
        {video.duration && (
          <span className="badge text-bg-dark position-absolute bottom-0 end-0 m-1">
            {formatDuration(video.duration)}
          </span>
        )}
        {isWatched && (
          <span className="watched-badge position-absolute top-0 end-0 m-1">
            <i className="fa-sharp-duotone fa-regular fa-circle-check" />
          </span>
        )}
        {f > 0 && !isWatched && (
          <div className="progress card-progress position-absolute bottom-0 start-0 w-100">
            <div className="progress-bar bg-danger" style={{ width: `${f * 100}%` }} />
          </div>
        )}
      </div>
      <div className="card-body p-2 text-start">
        <div className="card-title small fw-semibold mb-1 video-title">{video.title}</div>
        <div className="text-secondary" style={{ fontSize: '0.75rem' }}>{video.channelTitle}</div>
      </div>
    </button>
  )
}
