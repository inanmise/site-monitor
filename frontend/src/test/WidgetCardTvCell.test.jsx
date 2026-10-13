import { describe, it, expect } from 'vitest'
import { render } from './test-utils.jsx'
import WidgetCard, { STALE_AFTER_MS } from '../components/mydash/WidgetCard.jsx'

/**
 * TV panel hücresi (2026-10-13 inceleme): yerleşim kutuya TEK hücrelik panel yeri verdiyse (`frame.tvPanel`) gövde hep
 * panel çizilir — bilinmeyen ton (boyanamaz), bayat veri ya da gösterge yok (stat null) iken liste gövdesi küçük hücreye
 * çizilip satırı uzatmaz, sayfayı küçültmez. Kart boyut kabıdır, kırpılmaz; "güncellendi" satırı yalnız hatada.
 */
const frame = (extra = {}) => ({ widget: { id: 'x', type: 'alerts_open', size: 'md' }, tv: true, edit: false, stat: true, tvPanel: true, ...extra })
const Body = () => <ul data-testid="list-body"><li>satır</li></ul>

describe('WidgetCard TV panel hücresi', () => {
  it('bilinmeyen ton (boyanamaz): zeminsiz büyük sayı, liste yok; kart boyut kabı, kırpma yok', () => {
    const { container, queryByTestId } = render(
      <WidgetCard frame={frame()} title="Kutu" tone="muted" at={Date.now()} stat={{ value: 5, label: 'açık' }}><Body /></WidgetCard>)
    expect(queryByTestId('list-body')).toBeNull()
    expect(container.querySelector('[data-slot="mydash-stat-value"]')).toHaveTextContent('5')
    const card = container.querySelector('[data-slot="card"]')
    expect(card.style.containerType).toBe('size')
    expect(card.className).not.toMatch(/overflow-hidden/)
    expect(container.querySelector('[data-slot="mydash-widget-foot"]'), 'hatasızken güncellendi satırı yok').toBeNull()
  })

  it('bayat veri: soluk büyük sayı + "güncellenemedi" satırı; liste yok', () => {
    const { container, queryByTestId } = render(
      <WidgetCard frame={frame()} title="Kutu" tone="danger" at={Date.now() - STALE_AFTER_MS - 5000} error="ağ"
        stat={{ value: 3 }}><Body /></WidgetCard>)
    expect(queryByTestId('list-body')).toBeNull()
    expect(container.querySelector('[data-slot="mydash-widget-body"]')).toHaveAttribute('data-stale', 'true')
    expect(container.querySelector('[data-slot="mydash-widget-error"]')).not.toBeNull()
    expect(container.querySelector('[data-slot="mydash-widget"]')).not.toHaveAttribute('data-stat')
  })

  it('gösterge yok (stat null): "—" + veri yok, soluk; liste yok', () => {
    const { container, queryByTestId } = render(
      <WidgetCard frame={frame()} title="Kutu" tone="success" at={Date.now()} stat={null}><Body /></WidgetCard>)
    expect(queryByTestId('list-body')).toBeNull()
    expect(container.querySelector('[data-slot="mydash-widget-body"]')).toHaveAttribute('data-placeholder', 'true')
    expect(container.querySelector('[data-slot="mydash-stat-value"]')).toHaveTextContent('—')
    expect(container.querySelector('[data-slot="mydash-widget"]')).not.toHaveAttribute('data-stat')
  })

  it('ayrıntılı hücre (tvPanel yok) ve normal pano eskisi gibi: liste gövdesi çizilir', () => {
    const tvDetail = render(
      <WidgetCard frame={frame({ tvPanel: false, stat: false })} title="Kutu" tone="muted" at={Date.now()} stat={null}><Body /></WidgetCard>)
    expect(tvDetail.queryByTestId('list-body')).not.toBeNull()
    tvDetail.unmount()
    const normal = render(
      <WidgetCard frame={{ widget: { id: 'x', type: 'alerts_open', size: 'md' }, tv: false }} title="Kutu" tone="muted" at={Date.now()}
        stat={{ value: 1 }}><Body /></WidgetCard>)
    expect(normal.queryByTestId('list-body')).not.toBeNull()
    expect(normal.container.querySelector('[data-slot="card"]').style.containerType).toBe('')
  })

  it('ilk yükleme hatası: kısa, hücreyle ölçeklenen durum; tam cümle ekran okuyucuda; liste yok', () => {
    const { container, queryByTestId } = render(
      <WidgetCard frame={frame()} title="Kutu" tone="danger" error="Sunucuya ulaşılamadı; ağ bağlantınızı kontrol edin." stat={null}><Body /></WidgetCard>)
    expect(queryByTestId('list-body')).toBeNull()
    const failed = container.querySelector('[data-slot="mydash-widget-failed"]')
    expect(failed).toHaveAttribute('data-short', 'true')
    expect(failed.querySelector('.sr-only')).toHaveTextContent(/Couldn't load the data|Veriler alınamadı/)
    expect(failed).not.toHaveTextContent(/Sunucuya ulaşılamadı/)
  })

  it('yenileme hatası (bayat değil): tek kısa çip, yaş satırı yok; tam cümle ekran okuyucuda', () => {
    const { container } = render(
      <WidgetCard frame={frame()} title="Kutu" tone="danger" at={Date.now() - 30_000} error="ağ" stat={{ value: 2 }}><Body /></WidgetCard>)
    const chip = container.querySelector('[data-slot="mydash-widget-error"]')
    expect(chip).toHaveTextContent(/Not refreshed|Güncellenemedi/)
    expect(chip.querySelector('.sr-only')).toHaveTextContent(/Could not refresh|Güncellenemedi; son/)
    expect(container.querySelector('[data-slot="mydash-widget-age"]')).toBeNull()
    expect(container.querySelector('[data-slot="mydash-widget-foot"]')).not.toHaveTextContent(/Updated|Güncellendi /)
  })

  it('gösterge yokken kutunun kendi nedeni (rozet metni) yazar, yoksa "veri yok"', () => {
    const { container } = render(
      <WidgetCard frame={frame()} title="Kutu" tone="muted" toneLabel="No access" at={Date.now()} stat={null}><Body /></WidgetCard>)
    expect(container.querySelector('[data-slot="mydash-widget-body"]')).toHaveTextContent('No access')
  })
})
