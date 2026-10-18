import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import CaptureCropDialog from '../components/techerror/capture/CaptureCropDialog.jsx'

// Kırpma penceresi (2026-10-18): kare kutuya sığdırılır, seçim kaynak pikselleriyle tutulur. Fare / kalem / dokunma ile
// sürükleyerek yeni seçim, içinden taşıma, tutamakla boyutlandırma; klavyeyle oklar / Shift / Alt; Enter yükler.

const FRAME = { width: 1600, height: 900, canvas: null, uniform: false }

function setup(props = {}) {
  const onConfirm = vi.fn()
  const onCancel = vi.fn()
  const onRetake = vi.fn()
  const view = render(<CaptureCropDialog open frame={{ ...FRAME, canvas: document.createElement('canvas'), ...props.frame }}
    label="Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri" onConfirm={onConfirm} onCancel={onCancel} onRetake={onRetake} {...props} />)
  const stage = () => document.querySelector('[data-slot="te-cap-stage"]')
  // Ekranda 800 × 450 (ölçek 0,5): jsdom yerleşim yapmaz — sahnenin kutusu verilir
  const box = (el) => { el.getBoundingClientRect = () => ({ left: 100, top: 50, width: 800, height: 450, right: 900, bottom: 500 }) }
  return { view, stage, box, onConfirm, onCancel, onRetake }
}

const at = (x, y) => ({ clientX: 100 + x, clientY: 50 + y, pointerId: 1, pointerType: 'mouse', button: 0 })

beforeEach(() => {
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
})

describe('kırpma penceresi', () => {
  it('başlık yuva adını taşır; başlangıçta tamamı seçili; ölçü kaynak pikseliyle; karenin kendi tuvali sahnede', async () => {
    const { stage } = setup()
    expect(await screen.findByRole('dialog', { name: 'Ekrandan al: Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri' })).toBeInTheDocument()
    expect(stage()).toHaveAttribute('data-rect', '0,0,1600,900')
    expect(document.querySelector('[data-slot="te-cap-size"]')).toHaveTextContent('Seçim: 1600 × 900 piksel · görüntü 1600 × 900')
    expect(document.querySelector('[data-slot="te-cap-size-live"]')).toHaveTextContent('Seçim: 1600 × 900 piksel · görüntü 1600 × 900')
    expect(document.querySelector('[data-slot="te-cap-frame"]')).not.toBeNull()
    expect(document.querySelectorAll('[data-handle]')).toHaveLength(8)
    // Tamamı seçiliyken "Tümünü seç" kapalı
    expect(screen.getByRole('button', { name: /Tümünü seç/ })).toBeDisabled()
    // Klavye odağı sahnede (oklarla hemen kullanılabilir)
    await waitFor(() => expect(document.activeElement).toBe(stage()))
  })

  it('fare: tamamı seçiliyken sürüklemek YENİ seçim çizer; tıklama (hareketsiz) seçimi değiştirmez', async () => {
    const { stage, box } = setup()
    await screen.findByRole('dialog')
    box(stage())
    fireEvent.pointerDown(stage(), at(10, 10))
    fireEvent.pointerUp(stage(), at(10, 10))
    expect(stage()).toHaveAttribute('data-rect', '0,0,1600,900')
    fireEvent.pointerDown(stage(), at(100, 50))
    fireEvent.pointerMove(stage(), at(200, 120))
    fireEvent.pointerMove(stage(), at(300, 200))
    // Görünen ölçü sürüklerken canlı; ekran okuyucunun canlı bölgesi yalnız sürükleme bitince (inceleme 2026-10-18)
    const size = () => document.querySelector('[data-slot="te-cap-size"]')
    const live = () => document.querySelector('[data-slot="te-cap-size-live"]')
    expect(size()).toHaveTextContent('Seçim: 400 × 300 piksel · görüntü 1600 × 900')
    expect(size()).toHaveAttribute('aria-hidden', 'true')
    expect(live()).toHaveAttribute('aria-live', 'polite')
    expect(live()).toHaveTextContent('Seçim: 1600 × 900 piksel')
    fireEvent.pointerUp(stage(), at(300, 200))
    expect(stage()).toHaveAttribute('data-rect', '200,100,400,300')
    expect(live()).toHaveTextContent('Seçim: 400 × 300 piksel · görüntü 1600 × 900')
    // Klavye değişikliği hemen söylenir
    fireEvent.keyDown(stage(), { key: 'ArrowRight' })
    await waitFor(() => expect(live()).toHaveTextContent('Seçim: 400 × 300 piksel'))
    fireEvent.keyDown(stage(), { key: 'ArrowRight', altKey: true })
    await waitFor(() => expect(live()).toHaveTextContent('Seçim: 401 × 300 piksel'))
  })

  it('içinden sürükleyerek taşır; tutamakla boyutlandırır; dokunma ile de çalışır', async () => {
    const { stage, box } = setup()
    await screen.findByRole('dialog')
    box(stage())
    fireEvent.pointerDown(stage(), at(100, 50))
    fireEvent.pointerMove(stage(), at(300, 200))
    fireEvent.pointerUp(stage(), at(300, 200))
    // Taşı: seçimin içinden (+50, +25 ekran = +100, +50 kaynak)
    fireEvent.pointerDown(stage(), at(200, 100))
    fireEvent.pointerMove(stage(), at(250, 125))
    fireEvent.pointerUp(stage(), at(250, 125))
    expect(stage()).toHaveAttribute('data-rect', '300,150,400,300')
    // Sağ alt tutamak (dokunma): +25, +10 ekran = +50, +20 kaynak
    const se = document.querySelector('[data-handle="se"]')
    fireEvent.pointerDown(se, { ...at(350, 225), pointerType: 'touch' })
    fireEvent.pointerMove(stage(), { ...at(375, 235), pointerType: 'touch' })
    fireEvent.pointerUp(stage(), { ...at(375, 235), pointerType: 'touch' })
    expect(stage()).toHaveAttribute('data-rect', '300,150,450,320')
    // Sol kenar tutamağı: karşı kenarı geçemez, en az 16 kalır
    const w = document.querySelector('[data-handle="w"]')
    fireEvent.pointerDown(w, at(150, 150))
    fireEvent.pointerMove(stage(), at(800, 150))
    fireEvent.pointerUp(stage(), at(800, 150))
    expect(stage()).toHaveAttribute('data-rect', '734,150,16,320')
    // Farenin sağ tuşu seçim başlatmaz
    fireEvent.pointerDown(stage(), { ...at(10, 10), button: 2 })
    fireEvent.pointerMove(stage(), at(400, 400))
    expect(stage()).toHaveAttribute('data-rect', '734,150,16,320')
  })

  it('klavye: oklar 1 px, Shift 10 px taşır; Alt + oklar boyutlandırır; Enter seçili alanı yükler', async () => {
    const { stage, onConfirm } = setup()
    await screen.findByRole('dialog')
    const s = stage()
    fireEvent.keyDown(s, { key: 'ArrowLeft', altKey: true, shiftKey: true })   // genişlik 1590
    fireEvent.keyDown(s, { key: 'ArrowUp', altKey: true })                    // yükseklik 899
    expect(s).toHaveAttribute('data-rect', '0,0,1590,899')
    fireEvent.keyDown(s, { key: 'ArrowRight', shiftKey: true })
    fireEvent.keyDown(s, { key: 'ArrowRight' })
    fireEvent.keyDown(s, { key: 'ArrowDown' })
    expect(s).toHaveAttribute('data-rect', '10,1,1590,899')
    fireEvent.keyDown(s, { key: 'ArrowRight' })   // sağ kenarda durur
    expect(s).toHaveAttribute('data-rect', '10,1,1590,899')
    fireEvent.keyDown(s, { key: 'Enter' })
    expect(onConfirm).toHaveBeenCalledWith({ x: 10, y: 1, w: 1590, h: 899 })
    // "Tümünü seç"
    fireEvent.click(screen.getByRole('button', { name: /Tümünü seç/ }))
    expect(s).toHaveAttribute('data-rect', '0,0,1600,900')
    fireEvent.click(screen.getByRole('button', { name: /Bu alanı yükle/ }))
    expect(onConfirm).toHaveBeenLastCalledWith({ x: 0, y: 0, w: 1600, h: 900 })
  })

  it('yeniden çekim: yeni kare küçükse seçim korunur ama yeni kareye sığdırılır', async () => {
    const onConfirm = vi.fn()
    const frame = { ...FRAME, canvas: document.createElement('canvas') }
    const view = render(<CaptureCropDialog open frame={frame} label="Yuva" onConfirm={onConfirm} onCancel={() => {}} onRetake={() => {}} />)
    await screen.findByRole('dialog')
    const s = () => document.querySelector('[data-slot="te-cap-stage"]')
    fireEvent.keyDown(s(), { key: 'ArrowLeft', altKey: true, shiftKey: true })
    fireEvent.keyDown(s(), { key: 'ArrowRight', shiftKey: true })
    expect(s()).toHaveAttribute('data-rect', '10,0,1590,900')
    view.rerender(<CaptureCropDialog open frame={{ width: 1280, height: 720, canvas: document.createElement('canvas') }} label="Yuva"
      onConfirm={onConfirm} onCancel={() => {}} onRetake={() => {}} />)
    await waitFor(() => expect(s()).toHaveAttribute('data-rect', '0,0,1280,720'))
    expect(s()).toHaveAttribute('data-source', '1280x720')
  })

  it('boş (tek renk) kare uyarısı; yeniden çek / vazgeç düğmeleri; meşgulken kapanmaz', async () => {
    const { onRetake, onCancel, view } = setup({ frame: { uniform: true } })
    await screen.findByRole('dialog')
    expect(screen.getByText('Görüntü boş geldi')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Yeniden çek/ }))
    expect(onRetake).toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Vazgeç' }))
    expect(onCancel).toHaveBeenCalledTimes(1)
    view.rerender(<CaptureCropDialog open busy frame={{ ...FRAME, canvas: document.createElement('canvas') }} label="Yuva"
      onConfirm={() => {}} onCancel={onCancel} onRetake={onRetake} error="Seçilen alan hazırlanamadı." />)
    expect(screen.getByRole('alert')).toHaveTextContent('Seçilen alan hazırlanamadı.')
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: /Hazırlanıyor/ })).toBeDisabled()
  })
})
