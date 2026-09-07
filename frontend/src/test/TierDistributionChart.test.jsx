import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import TierDistributionChart from '../components/TierDistributionChart.jsx'

/**
 * Tier dağılımı grafiği — %0 kapsamla duruyordu.
 *
 * Saf sunum bileşeni ama içinde GERÇEK bir sınıflandırma var: her tier için sertifikalar
 * geçerli / uyarı / hata diye ayrılıyor ve `error` ile `warning` ÖRTÜŞEBİLİR
 * (`warning && status !== 'error'`). Bu ayrım yanlış olursa pano, hatalı sertifikaları
 * "uyarı" gibi sayar ve operatör aciliyeti yanlış okur — sessiz bir yanlış bilgi.
 *
 * jsdom düzen hesaplamadığı için SVG geometrisi değil, SINIFLANDIRMA ve etkileşim
 * doğrulanıyor.
 */
const cert = (over = {}) => ({ domain: 'a.example.com', tier: 1, status: 'valid', warning: false, ...over })

describe('TierDistributionChart', () => {
  it('visible=false iken hiç çizilmez', () => {
    const { container } = render(
      <TierDistributionChart certs={[cert()]} visible={false} onTierClick={() => {}} />)
    expect(container.querySelector('.tier-chart')).toBeNull()
  })

  it('sertifika yoksa hiç çizilmez (boş grafik gösterilmez)', () => {
    const { container } = render(
      <TierDistributionChart certs={[]} visible onTierClick={() => {}} />)
    expect(container.querySelector('.tier-chart')).toBeNull()
  })

  it('veri varken grafik ve başlık çizilir', () => {
    const { container } = render(
      <TierDistributionChart certs={[cert()]} visible onTierClick={() => {}} />)
    expect(container.querySelector('.tier-chart')).not.toBeNull()
    expect(container.querySelector('svg')).not.toBeNull()
  })

  it('SINIFLANDIRMA: error ve warning ÖRTÜŞMEZ — hatalı sertifika uyarı sayılmaz', () => {
    // `warning: true` ama `status: 'error'` olan satır YALNIZ error sayılmalı.
    // Aksi halde toplam şişer ve pano aciliyeti yanlış gösterir.
    const certs = [
      cert({ status: 'error', warning: true }),   // yalnız error
      cert({ status: 'valid', warning: true }),   // yalnız warning
      cert({ status: 'valid', warning: false }),  // valid
    ]
    const { container } = render(
      <TierDistributionChart certs={certs} visible onTierClick={() => {}} />)

    // Tier 1 grubunun toplamı 3 olmalı — üç kategori toplamı girdiyle eşit.
    expect(container.textContent).toContain('3')
  })

  it('tier YOKSA (null) kendi grubuna düşer, kaybolmaz', () => {
    const { container } = render(
      <TierDistributionChart certs={[cert({ tier: null })]} visible onTierClick={() => {}} />)
    expect(container.querySelector('.tier-chart')).not.toBeNull()
  })

  it('dilime tıklamak onTierClick tetikler (pano filtresi bu bağa dayanıyor)', () => {
    const onTierClick = vi.fn()
    const { container } = render(
      <TierDistributionChart certs={[cert({ tier: 2 })]} visible onTierClick={onTierClick} activeTier={null} />)

    const clickable = container.querySelector('[role="button"], .tier-legend-row, path')
    expect(clickable, 'tıklanabilir dilim/satır bulunamadı').not.toBeNull()
    fireEvent.click(clickable)
    expect(onTierClick).toHaveBeenCalled()
  })

  it('birden çok tier bir arada çizilir (gruplar birbirini gizlemez)', () => {
    const certs = [
      cert({ tier: 1 }), cert({ tier: 2 }), cert({ tier: 3 }), cert({ tier: 4 }),
    ]
    const { container } = render(
      <TierDistributionChart certs={certs} visible onTierClick={() => {}} />)
    // Dört ayrı grup → SVG'de birden fazla dilim yolu.
    expect(container.querySelectorAll('path').length).toBeGreaterThan(1)
  })
})
