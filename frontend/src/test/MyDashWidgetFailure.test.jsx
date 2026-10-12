import { describe, it, expect, vi } from 'vitest'
import { render, screen } from './test-utils.jsx'

vi.mock('../api/client', () => ({ api: {} }))

import WidgetCard from '../components/mydash/WidgetCard.jsx'

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
})
