import { describe, it, expect, vi } from 'vitest'
import { render, screen } from './test-utils.jsx'

vi.mock('../api/client', () => ({ api: {} }))

import WidgetCard, { STALE_AFTER_MS } from '../components/mydash/WidgetCard.jsx'

const frame = { widget: { id: 'w1', type: 'alerts_open', size: 'md' }, edit: false, tv: false }

/**
 * 2026-10-12 inceleme bulgusu: ilk yükleme hata verince kutu, boş veriden üretilen yeşil "her şey yolunda" özetini
 * gösteriyordu — duvar ekranında kesinti "sağlıklı" görünürdü. Artık hiç veri yoksa gövde çizilmez, durum rozeti yok,
 * "veriler alınamadı" denir; son veri varken hata yalnız alt notta ("güncellenemedi").
 */
describe('Panom kutusu — veri alınamadı durumu', () => {
  it('ilk yükleme hatası: gövde (yeşil özet) çizilmez, rozet yok, ton muted, açıklayıcı not', () => {
    render(<WidgetCard frame={frame} title="Açık alarmlar" tone="success" toneLabel="Her şey yolunda" error="HTTP 500" at={null} loading={false}>
      <p data-testid="body">Açık alarm yok</p>
    </WidgetCard>)
    expect(screen.queryByTestId('body')).toBeNull()
    expect(screen.queryByText('Her şey yolunda')).toBeNull()
    const box = document.querySelector('[data-slot="mydash-widget"]')
    expect(box.getAttribute('data-tone')).toBe('muted')
    expect(box.hasAttribute('data-failed')).toBe(true)
    expect(document.querySelector('[data-slot="mydash-widget-failed"]').textContent).toContain('HTTP 500')
    expect(document.querySelector('[data-slot="mydash-widget-foot"]')).toBeNull()
  })

  it('son veri varken hata: gövde ve rozet kalır, alt not "güncellenemedi"', () => {
    render(<WidgetCard frame={frame} title="Açık alarmlar" tone="success" toneLabel="Her şey yolunda" error="HTTP 500" at={Date.now() - 1000} loading={false}>
      <p data-testid="body">Açık alarm yok</p>
    </WidgetCard>)
    expect(screen.getByTestId('body')).toBeTruthy()
    expect(screen.getByText('Her şey yolunda')).toBeTruthy()
    expect(document.querySelector('[data-slot="mydash-widget"]').hasAttribute('data-failed')).toBe(false)
    expect(document.querySelector('[data-slot="mydash-widget-error"]')).toBeTruthy()
  })

  it('inceleme 4: hata iki yoklama aralığından uzun sürerse eski yeşil rozet DÜŞER, ton muted (bilinmeyen yeşil değildir)', () => {
    render(<WidgetCard frame={frame} title="Açık alarmlar" tone="success" toneLabel="Her şey yolunda" error="HTTP 500"
      at={Date.now() - STALE_AFTER_MS - 1000} loading={false}>
      <p data-testid="body">Açık alarm yok</p>
    </WidgetCard>)
    const box = document.querySelector('[data-slot="mydash-widget"]')
    expect(box.getAttribute('data-tone')).toBe('muted')
    expect(box.hasAttribute('data-stale')).toBe(true)
    expect(screen.queryByText('Her şey yolunda')).toBeNull()
    expect(document.querySelector('[data-slot="mydash-widget-error"]')).toBeTruthy()   // "güncellenemedi" notu kalır
    // gövdedeki yeşil içerik de renksiz çizilir (inceleme 5) ve verinin yaşı yazılır
    const body = document.querySelector('[data-slot="mydash-widget-body"]')
    expect(body).toHaveAttribute('data-stale', 'true')
    expect(body.className).toContain('grayscale')
    expect(document.querySelector('[data-slot="mydash-widget-age"]')).toBeTruthy()
  })

  it('inceleme 5: tek başarısız tur (≈ 1 yoklama aralığı) kutuyu bayatlatmaz; iki tur payla bayatlatır', () => {
    const draw = (age) => render(<WidgetCard frame={frame} title="Açık alarmlar" tone="success" toneLabel="Her şey yolunda" error="HTTP 500"
      at={Date.now() - age} loading={false}><p>Açık alarm yok</p></WidgetCard>)
    const a = draw(61_000)
    expect(document.querySelector('[data-slot="mydash-widget"]').hasAttribute('data-stale')).toBe(false)
    a.unmount()
    draw(119_000)   // ikinci başarısız tur, başarılı yanıttan hızlı döndü
    expect(document.querySelector('[data-slot="mydash-widget"]').hasAttribute('data-stale')).toBe(true)
  })
})
