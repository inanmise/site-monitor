import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
import { ToastProvider, useToast } from '../components/ui/Toast.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

/*
 * Eylemli bildirim (2026-11-20, "Geri al"): düğme eylemi çalıştırır ve kutuyu kapatır; `onClose` kutu HANGİ yoldan
 * kapanırsa kapansın (süre, X, gövdeye tıklama, tavanda düşme, sağlayıcının sökülmesi, dismiss) BİR KEZ çağrılır; eylemli
 * bildirimler aynı metinle de birleşmez (her biri kendi geri alınacak şeyini taşır). Zamanlama yardımcıları Toast.test.jsx'teki
 * gibi (Sonner kutuyu bir setTimeout(0) sonra çizer, kapanışta çıkış animasyonu bekler).
 */
const EXIT_MS = 400
const flush = () => act(() => { vi.advanceTimersByTime(0) })
function advance(ms, step = 50) {
  for (let done = 0; done < ms; done += step) act(() => { vi.advanceTimersByTime(Math.min(step, ms - done)) })
}
const CLOSE_NAME = new RegExp(`^(${EN['toast.close']}|${TR['toast.close']})$`)

let api = null
function Grab() {
  api = useToast()
  return null
}
function setup() {
  const utils = render(<ToastProvider><Grab /></ToastProvider>)
  return utils
}
function showAction(msg = 'Görsel kaldırıldı', extra = {}) {
  const onAction = vi.fn()
  const onClose = vi.fn()
  let id
  act(() => { id = api.action(msg, { label: 'Geri al', onAction, onClose, ...extra }) })
  flush()
  return { id, onAction, onClose }
}

describe('Toast — eylemli bildirim', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers(); api = null })

  it('düğme eylemi çalıştırır, kutu kapanır, onClose bir kez', () => {
    setup()
    const { onAction, onClose } = showAction()
    const btn = screen.getByRole('button', { name: 'Geri al' })
    expect(btn).toHaveAttribute('data-slot', 'toast-action')
    expect(btn.className).toContain('pointer-coarse:min-h-10')
    fireEvent.click(btn)
    expect(onAction).toHaveBeenCalledTimes(1)
    expect(onClose).toHaveBeenCalledTimes(1)
    advance(EXIT_MS)
    expect(screen.queryByText('Görsel kaldırıldı')).toBeNull()
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('varsayılan süre 10 sn: dolunca kapanır, onClose bir kez; eylem çalışmaz', () => {
    setup()
    const { onAction, onClose } = showAction()
    advance(9900)
    expect(screen.getByText('Görsel kaldırıldı')).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
    advance(100 + EXIT_MS)
    expect(screen.queryByText('Görsel kaldırıldı')).toBeNull()
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(onAction).not.toHaveBeenCalled()
  })

  it('X ile kapanış da onClose çağırır (bir kez)', () => {
    setup()
    const { onClose } = showAction()
    fireEvent.click(screen.getByRole('button', { name: CLOSE_NAME }))
    advance(EXIT_MS + 200)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('dismiss(id) erken kapatır, onClose bir kez', () => {
    setup()
    const { id, onClose } = showAction()
    act(() => { api.dismiss(id) })
    act(() => { api.dismiss(id) })
    advance(EXIT_MS)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('aynı metinli iki eylemli bildirim birleşmez; her biri kendi eylemini taşır', () => {
    setup()
    const a = showAction('Konu silindi')
    const b = showAction('Konu silindi')
    expect(a.id).not.toBe(b.id)
    const buttons = screen.getAllByRole('button', { name: 'Geri al' })
    expect(buttons).toHaveLength(2)
    expect(screen.queryByText('×2')).toBeNull()
  })

  it('tavan (4) aşılınca düşen eylemli kutunun onClose çağrılır', () => {
    setup()
    const first = showAction('Bir')
    showAction('İki')
    showAction('Üç')
    showAction('Dört')
    expect(first.onClose).not.toHaveBeenCalled()
    showAction('Beş')
    expect(first.onClose).toHaveBeenCalledTimes(1)
  })

  it('sağlayıcı sökülünce açık eylemli bildirimlerin onClose çağrılır', () => {
    const { unmount } = setup()
    const a = showAction('Bir')
    const b = showAction('İki')
    unmount()
    expect(a.onClose).toHaveBeenCalledTimes(1)
    expect(b.onClose).toHaveBeenCalledTimes(1)
  })

  it('eylem hata verse de kutu kapanır, onClose çağrılır', () => {
    setup()
    const onClose = vi.fn()
    act(() => { api.action('Hatalı', { label: 'Geri al', onAction: () => { throw new Error('x') }, onClose }) })
    flush()
    fireEvent.click(screen.getByRole('button', { name: 'Geri al' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('eylemsiz bildirimler eskisi gibi birleşir (×2)', () => {
    setup()
    act(() => { api.info('Aynı') })
    flush()
    act(() => { api.info('Aynı') })
    flush()
    expect(screen.getByText('×2')).toBeInTheDocument()
  })
})
