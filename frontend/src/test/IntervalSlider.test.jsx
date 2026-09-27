import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import IntervalSlider from '../components/ui/IntervalSlider.jsx'

/**
 * Ortak kontrol-aralığı çubuğunun SÖZLEŞMESİ.
 *
 * Aynı ayar dokuz formda iki farklı biçimde soruluyordu (kaydırma çubuğu vs açılır liste).
 * Aralık listesi bilerek prop'tur: türlerin tabanı farklı (Sayfa Hızı 5 dk, DNS 30 sn).
 * Çizim shadcn Slider (Radix): başparmak role="slider", değer SIRA NUMARASI, okunur değer
 * aria-valuetext. jsdom'da sürükleme (pointer capture) yok — klavyeyle sürülür (Radix'in kendi yolu).
 */
describe('IntervalSlider', () => {
  const OPTS = [
    { value: 30, labelKey: 'notify.iv30s' },
    { value: 300, labelKey: 'notify.iv5m' },
    { value: 3600, labelKey: 'notify.iv1h' },
  ]

  it('seçili değerin tick’i işaretli ve çubuk o indekste (okunur değer aralık metni)', () => {
    const { container } = render(<IntervalSlider options={OPTS} value={300} onChange={() => {}} />)
    const thumb = screen.getByRole('slider')
    expect(thumb.getAttribute('aria-valuenow')).toBe('1')
    expect(thumb.getAttribute('aria-valuetext')).toBe('5m')
    expect(container.querySelector('[data-slot="interval-tick"][data-active="true"]').textContent).toBe('5m')
    expect(container.querySelectorAll('[data-slot="interval-tick"][data-active="true"]')).toHaveLength(1)
  })

  it('başparmağın erişilebilir adı başlıktır (kökteki ad ekran okuyucuya ulaşmaz)', () => {
    render(<IntervalSlider options={OPTS} value={300} onChange={() => {}} />)
    expect(screen.getByRole('slider', { name: /kontrol aralığı|monitor interval/i })).toBeInTheDocument()
  })

  it('sürükleyince (klavye) SANİYE döner (indeks değil)', () => {
    const onChange = vi.fn()
    render(<IntervalSlider options={OPTS} value={30} onChange={onChange} />)
    fireEvent.keyDown(screen.getByRole('slider'), { key: 'End' })
    expect(onChange).toHaveBeenCalledWith(3600)
    onChange.mockClear()
    fireEvent.keyDown(screen.getByRole('slider'), { key: 'ArrowRight' })
    expect(onChange).toHaveBeenCalledWith(300)
  })

  it('listede OLMAYAN değer çubuğu 0’a düşürmez — EN YAKIN seçenek gösterilir', () => {
    // Eski bir kayıt ya da ayar değişikliği listede olmayan bir değer taşıyabilir. Çubuğun
    // sessizce başa dönmesi kullanıcıya YANLIŞ bir kayıtlı değer gösterirdi.
    render(<IntervalSlider options={OPTS} value={280} onChange={() => {}} />)
    expect(screen.getByRole('slider').getAttribute('aria-valuenow')).toBe('1')   // 300'e en yakın
  })

  it('boş seçenek listesinde hiçbir şey çizmez (çökmez)', () => {
    const { container } = render(<IntervalSlider options={[]} value={30} onChange={() => {}} />)
    expect(container.querySelector('[data-slot="slider"]')).toBeNull()
  })

  it('not verilirse çubuğun altında gösterilir (tür-özel kısıt açıklaması)', () => {
    render(<IntervalSlider options={OPTS} value={30} onChange={() => {}} note="Taban 5 dakikadır." />)
    expect(screen.getByText('Taban 5 dakikadır.')).toBeDefined()
  })
})
