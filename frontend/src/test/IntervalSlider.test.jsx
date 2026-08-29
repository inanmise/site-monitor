import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import IntervalSlider from '../components/ui/IntervalSlider.jsx'

/**
 * Ortak kontrol-aralığı çubuğunun SÖZLEŞMESİ.
 *
 * Aynı ayar dokuz formda iki farklı biçimde soruluyordu (kaydırma çubuğu vs açılır liste).
 * Aralık listesi bilerek prop'tur: türlerin tabanı farklı (Sayfa Hızı 5 dk, DNS 30 sn).
 */
describe('IntervalSlider', () => {
  const OPTS = [
    { value: 30, labelKey: 'notify.iv30s' },
    { value: 300, labelKey: 'notify.iv5m' },
    { value: 3600, labelKey: 'notify.iv1h' },
  ]

  it('seçili değerin tick’i işaretli ve çubuk o indekste', () => {
    const { container } = render(<IntervalSlider options={OPTS} value={300} onChange={() => {}} />)
    expect(container.querySelector('.http-interval-slider').value).toBe('1')
    expect(container.querySelector('.http-interval-tick.active').textContent).toBe('5m')
  })

  it('sürükleyince SANİYE döner (indeks değil)', () => {
    const onChange = vi.fn()
    const { container } = render(<IntervalSlider options={OPTS} value={30} onChange={onChange} />)
    fireEvent.change(container.querySelector('.http-interval-slider'), { target: { value: '2' } })
    expect(onChange).toHaveBeenCalledWith(3600)
  })

  it('listede OLMAYAN değer çubuğu 0’a düşürmez — EN YAKIN seçenek gösterilir', () => {
    // Eski bir kayıt ya da ayar değişikliği listede olmayan bir değer taşıyabilir. Çubuğun
    // sessizce başa dönmesi kullanıcıya YANLIŞ bir kayıtlı değer gösterirdi.
    const { container } = render(<IntervalSlider options={OPTS} value={280} onChange={() => {}} />)
    expect(container.querySelector('.http-interval-slider').value).toBe('1')   // 300'e en yakın
  })

  it('boş seçenek listesinde hiçbir şey çizmez (çökmez)', () => {
    const { container } = render(<IntervalSlider options={[]} value={30} onChange={() => {}} />)
    expect(container.querySelector('.http-interval-slider')).toBeNull()
  })

  it('not verilirse çubuğun altında gösterilir (tür-özel kısıt açıklaması)', () => {
    render(<IntervalSlider options={OPTS} value={30} onChange={() => {}} note="Taban 5 dakikadır." />)
    expect(screen.getByText('Taban 5 dakikadır.')).toBeDefined()
  })
})
