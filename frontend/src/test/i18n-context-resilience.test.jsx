import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render as rawRender, screen } from '@testing-library/react'
import { LangProvider, useT, useLanguage, useDateLocale } from '../i18n/index.jsx'
import { useTheme } from '../i18n/theme.jsx'

/**
 * Canlı bir çökmenin regresyon testi.
 *
 * Olay: backend 500'lerinin ardından AdminSettings'in useT()'si
 * "TypeError: Cannot destructure property 'lang' of 'useLanguage(...)' as it is null"
 * fırlattı. Sebep Vite HMR'ın i18n/index.jsx'i İKİ ayrı modül örneği olarak yüklemesiydi
 * (aynı yol, farklı ?t= damgası): A örneğinin LangProvider'ı B örneğinin createContext'inde
 * görünmediği için useContext null döndü.
 *
 * İki ayrı kilit: (1) provider yokken hook'lar patlamaz — hata yüzeyi useT() kullandığı için
 * throw etmek beyaz ekran demekti; (2) context nesnesi globalThis'e sabitlendiği için iki
 * modül örneği AYNI context'i paylaşır.
 *
 * NOT: bu dosya bilinçli olarak test-utils.jsx#render() KULLANMAZ — o yardımcı ağacı
 * LangProvider ile sarar ve test edilen durumu ortadan kaldırırdı.
 */

function Probe() {
  const t = useT()
  const { lang } = useLanguage()
  const locale = useDateLocale()
  const { theme } = useTheme()
  return <div data-testid="probe">{`${t('stat.total')}|${lang}|${locale}|${theme}`}</div>
}

describe('i18n context dayanıklılığı', () => {
  beforeEach(() => {
    localStorage.removeItem('site-monitor-lang')
  })

  it('LangProvider/ThemeProvider OLMADAN hook\'lar throw etmez, çalışan varsayılana düşer', () => {
    expect(() => rawRender(<Probe />)).not.toThrow()
    // Varsayılan dil 'en' (localStorage boş), tarih yereli en-GB, tema light.
    expect(screen.getByTestId('probe').textContent).toBe('Total|en|en-GB|light')
  })

  it('provider yokken localStorage\'daki dil yine de okunur', async () => {
    localStorage.setItem('site-monitor-lang', 'tr')
    vi.resetModules()
    const mod = await import('../i18n/index.jsx')
    function TrProbe() {
      const t = mod.useT()
      return <span>{t('stat.total')}</span>
    }
    rawRender(<TrProbe />)
    expect(screen.getByText('Toplam')).toBeDefined()
  })

  it('modül iki kez yüklense de context AYNI nesnedir (HMR çift-modül senaryosu)', async () => {
    vi.resetModules()
    const a = await import('../i18n/index.jsx')
    vi.resetModules()
    const b = await import('../i18n/index.jsx')

    // Ayrı modül örnekleri (resetModules sonrası yeniden değerlendirilir)...
    expect(a.useT).not.toBe(b.useT)

    // ...ama A'nın Provider'ı B'nin useT'sini besleyebiliyor: context paylaşılıyor.
    function BProbe() {
      const t = b.useT()
      const { lang } = b.useLanguage()
      return <div data-testid="cross">{`${t('stat.total')}|${lang}`}</div>
    }
    const A = a.LangProvider
    rawRender(<A><BProbe /></A>)
    expect(screen.getByTestId('cross').textContent).toBe('Total|en')
  })

  it('aynı placeholder birden çok kez geçerse HEPSİ doldurulur', () => {
    let t
    function Cap() { t = useT(); return null }
    rawRender(<LangProvider><Cap /></LangProvider>)
    // Sözlükte olmayan anahtar kendisini döndürür — biçimlendirme yolunu izole test eder.
    expect(t('x.repeat {0} ve {0}', 'A')).toBe('x.repeat A ve A')
  })

  it('değiştirme metni $& / $1 içerse bile aynen yazılır (replace tuzağı)', () => {
    let t
    function Cap() { t = useT(); return null }
    rawRender(<LangProvider><Cap /></LangProvider>)
    expect(t('x.dollar {0}', '$&')).toBe('x.dollar $&')
    expect(t('x.dollar {0}', "$`$'$1")).toBe("x.dollar $`$'$1")
  })
})
