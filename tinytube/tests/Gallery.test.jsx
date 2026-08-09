import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import Gallery from '../src/gallery.jsx'

const channels = [{ channel_title: 'Chan', videos: [{ id: 'v1', title: 'Vid', thumbnail: 't.jpg' }] }]
const watchStore = { watched: {} }

describe('Parents button', () => {
  it('is always visible and opens the parent gate', () => {
    const onParents = vi.fn()
    render(<Gallery channels={channels} watchStore={watchStore} onPlay={() => {}} onParents={onParents} />)
    fireEvent.click(screen.getByLabelText('Parents'))
    expect(onParents).toHaveBeenCalled()
  })
})
