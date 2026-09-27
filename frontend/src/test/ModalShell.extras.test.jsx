import { describe, it, expect, vi } from 'vitest'
import { createRef } from 'react'
import { render, screen, fireEvent } from './test-utils.jsx'
import ModalShell from '../components/ui/ModalShell.jsx'

/**
 * ModalShell'in 2026-09-25 ek kancaları (izleme sayfalarının shadcn geçişi için; varsayılanlarıyla
 * eski davranış aynı): `headerExtra`, `hideClose`, `bodyRef`, `dismissOnEscape`.
 */
describe('ModalShell — ek kancalar', () => {
  it('varsayılan: X ve Escape kapatır (eski sözleşme bozulmadı)', () => {
    const onClose = vi.fn()
    render(<ModalShell open onClose={onClose} title="Pencere">gövde</ModalShell>)
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: /^(kapat|close)$/i }))
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('dismissOnEscape={false}: Escape kapatmaz, X yine kapatır', () => {
    const onClose = vi.fn()
    render(<ModalShell open onClose={onClose} title="Form" dismissOnEscape={false}>gövde</ModalShell>)
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /^(kapat|close)$/i }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('hideClose: yerleşik X çizilmez; headerExtra başlık satırında çizilir', () => {
    render(<ModalShell open onClose={() => {}} title="Detay" hideClose
      headerExtra={<button type="button">Eylem</button>}>gövde</ModalShell>)
    expect(screen.queryByRole('button', { name: /^(kapat|close)$/i })).toBeNull()
    const header = screen.getByRole('dialog').querySelector('[data-slot="dialog-header"]')
    expect(header.contains(screen.getByRole('button', { name: 'Eylem' }))).toBe(true)
  })

  it('bodyRef kaydırılan gövdeye bağlanır (nesne ve geri çağrı ref)', () => {
    const ref = createRef()
    const { unmount } = render(<ModalShell open onClose={() => {}} title="A" scrollBody bodyRef={ref}>gövde</ModalShell>)
    expect(ref.current).toBe(document.querySelector('[data-slot="modal-shell-body"]'))
    unmount()
    const cb = vi.fn()
    render(<ModalShell open onClose={() => {}} title="B" bodyRef={cb}>gövde</ModalShell>)
    expect(cb).toHaveBeenCalledWith(document.querySelector('[data-slot="modal-shell-body"]'))
  })
})
