import { describe, it, expect, vi } from 'vitest'
import { render, fireEvent } from '@testing-library/react'
import { useEscapeKey } from '../hooks/useEscapeKey.js'

// Regression: ISSUE-002 — dokuz izleme sayfası + Uptime + Sertifika detay modalı Escape ile kapanmıyordu
// (klavye kullanıcısı modalda kilitli). Ortak hook: yalnız etkinken dinler, üstte bir .modal-overlay
// (ModalShell ya da düzenleme formu) varken alttaki detayı KAPATMAZ, defaultPrevented olaylara dokunmaz.
// Found by /qa on 2026-09-13
// Report: .gstack/qa-reports/qa-report-localhost-2026-09-13-flows.md
function Probe({ enabled, onClose }) { useEscapeKey(enabled, onClose); return <div data-testid="probe" /> }

describe('useEscapeKey', () => {
  it('etkinken Escape onClose çağırır; başka tuş çağırmaz; devre dışıyken dinlemez', () => {
    const onClose = vi.fn()
    const { rerender } = render(<Probe enabled onClose={onClose} />)
    fireEvent.keyDown(document, { key: 'Enter' })
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
    rerender(<Probe enabled={false} onClose={onClose} />)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('üstte .modal-overlay (düzenleme formu / ModalShell) varken alttaki detay kapanmaz', () => {
    const onClose = vi.fn()
    render(<Probe enabled onClose={onClose} />)
    const overlay = document.createElement('div'); overlay.className = 'modal-overlay'; document.body.appendChild(overlay)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()
    overlay.remove()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('defaultPrevented Escape (açılır liste kendi kapanışını yaptı) yok sayılır; unmount dinleyiciyi kaldırır', () => {
    const onClose = vi.fn()
    const { unmount } = render(<Probe enabled onClose={onClose} />)
    const ev = new KeyboardEvent('keydown', { key: 'Escape', cancelable: true, bubbles: true })
    ev.preventDefault()
    document.dispatchEvent(ev)
    expect(onClose).not.toHaveBeenCalled()
    unmount()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()
  })
})
