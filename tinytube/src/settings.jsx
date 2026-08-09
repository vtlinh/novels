import { useEffect, useMemo, useState } from 'react'
import { useReactTable, getCoreRowModel, flexRender } from '@tanstack/react-table'
import { curatedChannels, overlaps, storeApi, fmtMins, usageStats, windowUsed, QUOTA_WINDOW_MS } from './lib.js'
import { searchChannels, resolveChannel, evictChannelCache, formatCount, validateApiKey } from './youtubeApi.js'

const API_CONSOLE_URL = 'https://console.cloud.google.com/apis/library/youtube.googleapis.com'
const looksLikeLink = s => /^@|^UC[0-9A-Za-z_-]{22}$|youtube\.com/.test(s.trim())
const channelUrl = ch => ch.source_url ?? `https://www.youtube.com/channel/${ch.channel_id}`

export default function Settings({ db, store, watchStore, onDone }) {
  // edits accumulate in an in-memory draft; localStorage is only touched by
  // Save, which appears once the draft diverges (back/edge-swipe discards)
  const [settings, setSettings] = useState(store.settings)
  const draft = storeApi(settings, patch => setSettings(prev => ({ ...prev, ...patch })))
  const dirty = JSON.stringify(settings) !== JSON.stringify(store.settings)

  return (
    <div className="settings container-xl py-4">
      {/* explicit back button: iOS standalone PWAs have no browser chrome or
          hardware back, so without it a no-change visit would strand you here;
          like edge-swipe it discards the draft. flex: 1 sides keep the title
          truly centered */}
      <div className="d-flex align-items-center gap-3 mb-4">
        <div style={{ flex: 1 }}>
          <button type="button" className="btn btn-outline-secondary" aria-label="Back to gallery" onClick={onDone}>
            <i className="fa-sharp-duotone fa-regular fa-arrow-left" />
          </button>
        </div>
        <h1 className="fs-3 fw-bold m-0">
          <i className="fa-sharp-duotone fa-solid fa-remote me-2 text-danger" />
          Settings
        </h1>
        <div className="d-flex align-items-center justify-content-end gap-3" style={{ flex: 1 }}>
          <VersionLink />
          {dirty && (
            <>
              {/* discards the draft but stays on the page, unlike Back */}
              <button
                type="button"
                className="btn btn-outline-secondary btn-lg"
                onClick={() => setSettings(store.settings)}
              >
                <i className="fa-sharp-duotone fa-regular fa-arrow-rotate-left me-2" />
                Revert
              </button>
              <button
                type="submit"
                form="api-key-form"
                className="btn btn-danger btn-lg"
                onClick={() => {
                  store.save(settings)
                  onDone()
                }}
              >
                <i className="fa-sharp-duotone fa-regular fa-check me-2" />
                Save
              </button>
            </>
          )}
        </div>
      </div>

      <AgeRow value={settings.ageRange} onChange={draft.setAgeRange} />
      <QuotaRow value={settings.quotaMins} onChange={draft.setQuotaMins} watchStore={watchStore} />
      <MinLengthRow value={settings.minVideoMins} onChange={draft.setMinVideoMins} />
      <ApiKeyRow apiKey={settings.apiKey} onChange={draft.setApiKey} />
      <SearchRow apiKey={settings.apiKey} store={draft} db={db} />
      <ChannelTable db={db} store={draft} />
    </div>
  )
}

function ConfirmModal({ title, body, onConfirm, onCancel }) {
  return (
    <>
      <div className="modal d-block" tabIndex="-1" role="dialog" onClick={onCancel}>
        <div className="modal-dialog modal-dialog-centered" onClick={e => e.stopPropagation()}>
          <div className="modal-content">
            <div className="modal-header">
              <h5 className="modal-title">{title}</h5>
              <button type="button" className="btn-close" aria-label="Close" onClick={onCancel} />
            </div>
            <div className="modal-body">{body}</div>
            <div className="modal-footer">
              <button type="button" className="btn btn-secondary" onClick={onCancel}>
                Cancel
              </button>
              <button type="button" className="btn btn-danger" onClick={onConfirm}>
                <i className="fa-sharp-duotone fa-regular fa-trash me-2" />
                Delete
              </button>
            </div>
          </div>
        </div>
      </div>
      <div className="modal-backdrop show" />
    </>
  )
}

function VersionLink() {
  const sha = typeof __COMMIT_SHA__ !== 'undefined' ? __COMMIT_SHA__ : ''
  if (!sha) return null
  return (
    <a
      href={`https://github.com/pathikrit/TinyTube/commit/${sha}`}
      target="_blank"
      rel="noreferrer"
      className="text-secondary small text-decoration-none"
    >
      <i className="fa-sharp-duotone fa-regular fa-code-commit me-1" />
      v{sha.slice(0, 7)}
    </a>
  )
}

function DualAgeSlider({ value: [lo, hi], onChange }) {
  const pos = v => `calc(${(v - 1) / 14} * (100% - 32px) + 16px)`
  return (
    <div className="dual-slider flex-grow-1">
      <input
        type="range"
        min="1"
        max="15"
        value={lo}
        aria-label="Youngest age"
        onChange={e => onChange([Math.min(+e.target.value, hi), hi])}
      />
      <input
        type="range"
        min="1"
        max="15"
        value={hi}
        aria-label="Oldest age"
        onChange={e => onChange([lo, Math.max(+e.target.value, lo)])}
      />
      <span className="thumb-label" style={{ left: pos(lo) }}>{lo}</span>
      <span className="thumb-label" style={{ left: pos(hi) }}>{hi}</span>
    </div>
  )
}

function AgeRow({ value, onChange }) {
  return (
    <div className="d-flex align-items-center gap-3 mb-4">
      <span
        className="text-secondary text-nowrap"
        title="Set your child's age range — only enabled channels overlapping this range are shown"
      >
        <i className="fa-duotone fa-solid fa-children me-2" />
        Age
      </span>
      <DualAgeSlider value={value} onChange={onChange} />
    </div>
  )
}

const QUOTA_MAX_MINS = 240 // 4h, in 15-min steps
const fmtClock = mins => (mins ? `${Math.floor(mins / 60)}:${String(mins % 60).padStart(2, '0')}` : '0')

/** Single-thumb sibling of DualAgeSlider on the same track CSS; the fill span
 * shows time already used on the shared 0-4h scale. The thumb is 44px (not the
 * age slider's 32px) so the h:mm label inside it stays readable. */
function QuotaSlider({ value, usedMins, onChange }) {
  const pos = v => `calc(${v / QUOTA_MAX_MINS} * (100% - 44px) + 22px)`
  return (
    <div className="dual-slider quota-slider flex-grow-1">
      {usedMins > 0 && <span className="track-fill" style={{ width: pos(Math.min(usedMins, QUOTA_MAX_MINS)) }} />}
      <input
        type="range"
        min="0"
        max={QUOTA_MAX_MINS}
        step="15"
        value={value}
        aria-label="Watch quota"
        onChange={e => onChange(+e.target.value)}
      />
      <span className="thumb-label" style={{ left: pos(value) }}>{fmtClock(value)}</span>
    </div>
  )
}

// no reset button: dragging the quota above what's used grants time, and the
// 12h window expiry clears usage on its own
function QuotaRow({ value, onChange, watchStore }) {
  const stats = usageStats(watchStore.usage)
  const cols = [
    ['Session', stats.session, `Watched in the current ${QUOTA_WINDOW_MS / 3600_000}h quota window`],
    ['24Hr', stats.last24h, 'Watched in the last 24 hours'],
    ['WTD', stats.wtd, 'Week to date (since Sunday)'],
    ['MTD', stats.mtd, 'Month to date'],
    ['YTD', stats.ytd, 'Year to date'],
  ]
  return (
    // flex-wrap + the slider's min-width: stats sit inline on wide screens
    // and wrap below the slider on phones instead of crushing it
    <div className="d-flex align-items-center gap-3 flex-wrap mb-4">
      <span
        className="text-secondary text-nowrap"
        title={`Set the viewing limit (resets every ${QUOTA_WINDOW_MS / 3600_000} hrs)`}
      >
        <i className="fa-sharp-duotone fa-regular fa-stopwatch me-2" />
        Quota
      </span>
      <QuotaSlider value={value} usedMins={windowUsed(watchStore.usage) / 60} onChange={onChange} />
      <table className="table table-dark table-borderless table-sm w-auto small text-nowrap m-0">
        <tbody>
          <tr className="text-secondary">
            {cols.map(([label, , hover]) => (
              <td key={label} className="py-0 px-2 text-center" title={hover}>{label}</td>
            ))}
          </tr>
          <tr>
            {cols.map(([label, secs, hover]) => (
              <td key={label} className="py-0 px-2 text-center" title={hover}>{fmtMins(Math.round(secs / 60))}</td>
            ))}
          </tr>
        </tbody>
      </table>
    </div>
  )
}

const MIN_LENGTH_MAX_MINS = 60 // 1h, in 5-min steps

/** Same 44px-thumb track as QuotaSlider, but no usage fill — the thumb label
 * is the minimum video length; 0 means show everything. */
function MinLengthSlider({ value, onChange }) {
  const pos = v => `calc(${v / MIN_LENGTH_MAX_MINS} * (100% - 44px) + 22px)`
  return (
    <div className="dual-slider quota-slider flex-grow-1">
      <input
        type="range"
        min="0"
        max={MIN_LENGTH_MAX_MINS}
        step="5"
        value={value}
        aria-label="Minimum video length"
        onChange={e => onChange(+e.target.value)}
      />
      {/* ">" marks it as a floor; at 0 nothing is filtered, so "all" */}
      <span className="thumb-label" style={{ left: pos(value) }}>{value ? `>${fmtMins(value)}` : 'all'}</span>
    </div>
  )
}

function MinLengthRow({ value, onChange }) {
  return (
    <div className="d-flex align-items-center gap-3 mb-4">
      <span
        className="text-secondary text-nowrap"
        title="Hide videos shorter than this — keeps quick-hit clips out of the gallery (0m shows everything)"
      >
        <i className="fa-duotone fa-solid fa-video-arrow-up-right me-2" />
        Video Length
      </span>
      <MinLengthSlider value={value} onChange={onChange} />
    </div>
  )
}

// enough of a stored key to recognize it without exposing the whole thing;
// one dot per hidden character so the preview keeps the key's real length
const maskKey = k => `${k.slice(0, 6)}${'•'.repeat(k.length - 10)}${k.slice(-4)}`

function ApiKeyRow({ apiKey, onChange }) {
  const [confirming, setConfirming] = useState(false)
  const [focused, setFocused] = useState(false)
  const [check, setCheck] = useState(null) // null | 'busy' | 'ok' | error message
  // masked preview only when idle; while editing the password dots take over.
  // short strings are left unmasked — slices would overlap and it's not a key
  const masked = !focused && apiKey.length > 12

  // validate as soon as a key lands in the field (keys are usually pasted, so
  // the 500ms debounce mostly guards slow typists; i18nLanguages = 1 quota
  // unit per check). Also re-verifies a saved key on every Settings visit.
  useEffect(() => {
    if (!apiKey) {
      setCheck(null)
      return
    }
    let stale = false
    const timer = setTimeout(async () => {
      setCheck('busy')
      try {
        await validateApiKey(apiKey)
        if (!stale) setCheck('ok')
      } catch (err) {
        if (!stale) setCheck(`This API key did not work: ${err.message}`)
      }
    }, 500)
    return () => {
      stale = true
      clearTimeout(timer)
    }
  }, [apiKey])

  return (
    <div className="mb-4">
      <div className="d-flex align-items-center gap-3">
        <span
          className="text-secondary text-nowrap"
          title="Required only for adding new channels"
        >
          <i className="fa-sharp-duotone fa-regular fa-key me-2" />
          <a href={API_CONSOLE_URL} target="_blank" rel="noreferrer">YouTube API Key</a>
        </span>
        {/* real <form> + username/current-password hints so the browser's
            password manager offers to save the key; Save submits it via
            form="api-key-form" and preventDefault keeps the SPA in place */}
        <form
          id="api-key-form"
          className="d-flex align-items-center gap-3 flex-grow-1"
          onSubmit={e => e.preventDefault()}
        >
          <input type="text" name="username" value="youtube-api-key" autoComplete="username" readOnly hidden />
          <div className="position-relative flex-grow-1">
            <input
              type="password"
              name="api-key"
              className="form-control"
              placeholder="AIza… (needed to add channels)"
              value={apiKey}
              onChange={e => {
                setCheck(null)
                onChange(e.target.value)
              }}
              // validation is automatic; preventDefault stops the implicit
              // form submission from clicking the Save button
              onKeyDown={e => {
                if (e.key === 'Enter') e.preventDefault()
              }}
              onFocus={() => setFocused(true)}
              onBlur={() => setFocused(false)}
              // dots hidden under the masked preview; still a password input
              // so the browser's save-key prompt keeps working
              style={masked ? { color: 'transparent' } : undefined}
              autoComplete="current-password"
            />
            {masked && (
              <span
                className="position-absolute top-50 translate-middle-y pe-none font-monospace"
                style={{ left: '0.75rem' }}
              >
                {maskKey(apiKey)}
              </span>
            )}
            {check === 'busy' && (
              <span
                className="spinner-border spinner-border-sm position-absolute top-50 end-0 translate-middle-y me-2"
                role="status"
              />
            )}
            {check === 'ok' && (
              <i
                className="fa-sharp-duotone fa-regular fa-check text-success position-absolute top-50 end-0 translate-middle-y me-2"
                aria-label="API key is valid"
              />
            )}
          </div>
        </form>
        {apiKey && (
          <button
            type="button"
            className="btn btn-outline-danger btn-sm"
            aria-label="Delete API key"
            onClick={() => setConfirming(true)}
          >
            <i className="fa-sharp-duotone fa-regular fa-trash" />
          </button>
        )}
      </div>
      {check && check !== 'busy' && check !== 'ok' && <div className="alert alert-warning mt-2 py-2">{check}</div>}
      {confirming && (
        <ConfirmModal
          title="Delete API key?"
          body="You won't be able to search for or add channels until you enter a new key."
          onConfirm={() => {
            onChange('')
            setConfirming(false)
          }}
          onCancel={() => setConfirming(false)}
        />
      )}
    </div>
  )
}

function SearchRow({ apiKey, store, db }) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  // inline autocomplete: debounced 500ms, min 3 chars (search.list = 100
  // quota units per fired query, so don't search every keystroke)
  useEffect(() => {
    const q = query.trim()
    if (!apiKey || q.length < 3) {
      setResults([])
      setError(null)
      return
    }
    let stale = false
    const timer = setTimeout(async () => {
      setBusy(true)
      try {
        const found = looksLikeLink(q) ? [await resolveChannel(apiKey, q)] : await searchChannels(apiKey, q)
        if (!stale) {
          setResults(found)
          setError(null)
        }
      } catch (err) {
        if (!stale) {
          setResults([])
          setError(err.message)
        }
      } finally {
        if (!stale) setBusy(false)
      }
    }, 500)
    return () => {
      stale = true
      clearTimeout(timer)
    }
  }, [query, apiKey])

  return (
    <div className="mb-4">
      <div className="d-flex align-items-center gap-3">
        <span className="text-secondary text-nowrap">
          <i className="fa-brands fa-youtube me-2" />
          Add Channel
        </span>
        <div className="position-relative flex-grow-1">
          <input
            type="text"
            className="form-control"
            placeholder={apiKey ? 'Channel name, @handle, or URL' : 'Enter an API key above first'}
            disabled={!apiKey}
            value={query}
            onChange={e => setQuery(e.target.value)}
          />
          {busy && (
            <span
              className="spinner-border spinner-border-sm position-absolute top-50 end-0 translate-middle-y me-2"
              role="status"
            />
          )}
        </div>
      </div>
      {error && <div className="alert alert-warning mt-2 py-2">{error}</div>}
      {results.map(ch => {
        const isCustom = store.settings.customChannels.some(c => c.channel_id === ch.channel_id)
        const isCurated = (db?.channels ?? []).some(c => c.channel_id === ch.channel_id)
        // a deleted (hidden) curated channel counts as not added: Add un-hides
        // it, since adding it as custom would be a no-op (curated wins on merge)
        const added = isCustom || (isCurated && !store.settings.overrides[ch.channel_id]?.hidden)
        return (
          <div key={ch.channel_id} className="d-flex align-items-center gap-3 bg-body-tertiary rounded p-2 mt-2">
            <img src={ch.thumbnail} alt="" width="36" height="36" className="rounded-circle" />
            <span className="fw-semibold text-truncate">{ch.channel_title}</span>
            <TopicBadges ch={ch} />
            <div className="ms-auto">
              <StatsCell ch={ch} />
            </div>
            {added ? (
              // same semantics as the table's delete: drop custom, hide curated
              <button
                type="button"
                className="btn btn-outline-danger btn-sm"
                onClick={() => {
                  if (isCustom) {
                    store.removeCustomChannel(ch.channel_id)
                    evictChannelCache(ch.channel_id)
                  } else {
                    store.setOverride(ch.channel_id, { hidden: true })
                  }
                }}
              >
                <i className="fa-sharp-duotone fa-regular fa-trash me-1" />
                Remove
              </button>
            ) : (
              <button
                type="button"
                className="btn btn-danger btn-sm"
                onClick={() => {
                  if (isCurated) store.setOverride(ch.channel_id, { hidden: false })
                  else store.addCustomChannel({ ...ch, min_age: 1, max_age: 15 })
                  setQuery('')
                }}
              >
                <i className="fa-sharp-duotone fa-regular fa-plus me-1" />
                Add
              </button>
            )}
          </div>
        )
      })}
    </div>
  )
}

// the API's canonical topicCategories plus the COPPA made-for-kids flag
function TopicBadges({ ch }) {
  const labels = ch.topics ?? []
  if (!labels.length && !ch.made_for_kids) return null
  return (
    <div className="d-flex flex-wrap gap-1" title={labels.join(', ')}>
      {ch.made_for_kids && (
        <span className="badge text-bg-success fw-normal">
          <i className="fa-duotone fa-solid fa-child me-1" />
          made for kids
        </span>
      )}
      {labels.slice(0, 3).map(t => (
        <span key={t} className="badge text-bg-secondary fw-normal">{t}</span>
      ))}
    </div>
  )
}

function StatsCell({ ch }) {
  const stats = [
    [ch.subscribers, 'fa-users', 'subscribers'],
    [ch.video_count, 'fa-clapperboard', 'videos'],
    [ch.view_count, 'fa-eye', 'views'],
  ].filter(([n]) => n)
  return (
    <div className="text-secondary small text-nowrap">
      {stats.map(([n, icon, label]) => (
        <div key={label} title={`${n.toLocaleString('en')} ${label}`}>
          <i className={`fa-sharp-duotone fa-regular ${icon} me-1`} />
          {formatCount(n)} {label}
        </div>
      ))}
    </div>
  )
}

// per-channel edits land on the custom channel object itself or in the
// curated channel's override, depending on where the row came from
const channelPatcher = (ch, store) =>
  ch.custom
    ? patch => store.updateCustomChannel(ch.channel_id, patch)
    : patch => store.setOverride(ch.channel_id, patch)

function ChannelAgeSlider({ ch, store }) {
  const save = channelPatcher(ch, store)
  return (
    <DualAgeSlider
      value={[ch.min_age, ch.max_age]}
      onChange={([min_age, max_age]) => save({ min_age, max_age })}
    />
  )
}

/** Quick on/off without deleting; the age filter still applies independently. */
function EnabledCheckbox({ ch, store }) {
  const save = channelPatcher(ch, store)
  return (
    <input
      type="checkbox"
      className="form-check-input"
      checked={!ch.disabled}
      aria-label={`Enable ${ch.channel_title}`}
      onChange={e => save({ disabled: !e.target.checked })}
    />
  )
}

function ChannelTable({ db, store }) {
  const { customChannels, overrides } = store.settings
  const hiddenCount = Object.values(overrides).filter(o => o.hidden).length

  const data = useMemo(
    () =>
      [
        ...customChannels.map(ch => ({ ...ch, custom: true })),
        ...curatedChannels(db, overrides).filter(ch => !ch.hidden),
      ].sort((a, b) => b.min_age - a.min_age || b.max_age - a.max_age),
    [db, customChannels, overrides],
  )

  const { ageRange } = store.settings
  const inRange = data.filter(ch => !ch.disabled && overlaps(ageRange, ch.min_age, ch.max_age)).length

  const columns = useMemo(
    () => [
      {
        id: 'enabled',
        header: '',
        cell: ({ row }) => <EnabledCheckbox ch={row.original} store={store} />,
      },
      {
        header: `Channel (${inRange}/${data.length})`,
        accessorKey: 'channel_title',
        cell: ({ row }) => {
          const ch = row.original
          const avatar = ch.thumbnail ?? ch.videos?.[0]?.thumbnail
          return (
            <a
              href={channelUrl(ch)}
              target="_blank"
              rel="noreferrer"
              className="fw-semibold d-inline-flex align-items-center gap-2"
            >
              {avatar && <img src={avatar} alt="" width="36" height="36" className="rounded-circle object-fit-cover" />}
              {ch.channel_title}
            </a>
          )
        },
      },
      {
        header: 'Stats',
        id: 'stats',
        cell: ({ row }) => <StatsCell ch={row.original} />,
      },
      {
        header: 'Topics',
        id: 'topics',
        cell: ({ row }) => <TopicBadges ch={row.original} />,
      },
      {
        header: 'Age',
        id: 'ages',
        cell: ({ row }) => <ChannelAgeSlider ch={row.original} store={store} />,
      },
      {
        id: 'delete',
        header: '',
        cell: ({ row }) => (
          <button
            type="button"
            className="btn btn-outline-danger btn-sm"
            aria-label={`Delete ${row.original.channel_title}`}
            onClick={() => {
              const ch = row.original
              if (ch.custom) {
                store.removeCustomChannel(ch.channel_id)
                evictChannelCache(ch.channel_id)
              } else {
                store.setOverride(ch.channel_id, { hidden: true })
              }
            }}
          >
            <i className="fa-sharp-duotone fa-regular fa-trash" />
          </button>
        ),
      },
    ],
    [store, data, inRange],
  )

  const table = useReactTable({ data, columns, getCoreRowModel: getCoreRowModel() })

  return (
    <>
      {/* the ages column reserves real width so the 1-15 dual slider stays as
          readable as the global one up top; narrow screens scroll horizontally
          via table-responsive instead of crushing the track */}
      <div className="table-responsive">
        <table className="table table-dark align-middle">
          <thead>
            {table.getHeaderGroups().map(hg => (
              <tr key={hg.id}>
                {hg.headers.map(h => (
                  <th
                    key={h.id}
                    className="text-secondary fw-normal"
                    style={h.column.id === 'ages' ? { width: '55%', minWidth: 420 } : undefined}
                  >
                    {flexRender(h.column.columnDef.header, h.getContext())}
                  </th>
                ))}
              </tr>
            ))}
          </thead>
          <tbody>
            {table.getRowModel().rows.map(row => (
              <tr
                key={row.id}
                className={
                  !row.original.disabled &&
                  overlaps(store.settings.ageRange, row.original.min_age, row.original.max_age)
                    ? undefined
                    : 'out-of-range' // hidden from the kid: age-filtered or toggled off
                }
              >
                {row.getVisibleCells().map(cell => (
                  <td key={cell.id}>{flexRender(cell.column.columnDef.cell, cell.getContext())}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {hiddenCount > 0 && (
        <button
          type="button"
          className="btn btn-link btn-sm text-secondary"
          onClick={() => store.restoreHidden()}
        >
          restore {hiddenCount} deleted built-in channel{hiddenCount > 1 ? 's' : ''}
        </button>
      )}
    </>
  )
}
