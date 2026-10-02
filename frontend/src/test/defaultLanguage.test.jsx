import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { EN } from '../i18n/en.js'

/**
 * Varsayılan dil İNGİLİZCE — ürün kararı (2026-10-02, kullanıcı: "varsayılan dil ingilizce olsun").
 *
 * Dil seçmemiş (saklı tercihi olmayan) kullanıcı uygulamayı İngilizce görür; seçtiği dil kalıcıdır. Varsayılan
 * ÜÇ yerde okunur ve üçü aynı olmak zorunda — biri 'tr'ye dönerse arayüz, tarih biçimi ve sunucu iletileri
 * (X-Lang → Msg.t) farklı dillerde konuşur:
 *   • i18n/index.jsx  storedLang()   → arayüz dili, <html lang>
 *   • i18n/dateLocale.js dateLocale() → tarih/sayı yereli (en-GB)
 *   • api/client.js   X-Lang başlığı  → sunucu iletileri (client-lang-header.test.js da kilitler)
 */

const STORAGE_KEY = 'site-monitor-lang'

afterEach(() => {
  vi.resetModules()
  localStorage.removeItem(STORAGE_KEY)
  document.documentElement.lang = ''
})

describe('varsayılan dil İngilizce (ürün kararı 2026-10-02)', () => {
  it('saklı tercih yoksa arayüz İngilizce açılır ve <html lang="en">', async () => {
    localStorage.removeItem(STORAGE_KEY)
    vi.resetModules()
    const m = await import('../i18n/index.jsx')
    function App() {
      const t = m.useT()
      const { lang } = m.useLanguage()
      return <p data-testid="app" data-lang={lang}>{t('stat.total')}</p>
    }
    render(<m.LangProvider fallback={<m.LanguageBootSplash />}><App /></m.LangProvider>)
    const app = await screen.findByTestId('app')
    expect(app).toHaveAttribute('data-lang', 'en')
    expect(app).toHaveTextContent(EN['stat.total'])
    expect(document.documentElement.lang).toBe('en')
  })

  it('saklı tercih yoksa tarih yereli en-GB', async () => {
    localStorage.removeItem(STORAGE_KEY)
    vi.resetModules()
    const { dateLocale } = await import('../i18n/dateLocale.js')
    expect(dateLocale()).toBe('en-GB')
  })

  it('kullanıcının seçtiği Türkçe varsayılanı ezer', async () => {
    localStorage.setItem(STORAGE_KEY, 'tr')
    vi.resetModules()
    const m = await import('../i18n/index.jsx')
    function App() {
      const { lang } = m.useLanguage()
      return <p data-testid="app" data-lang={lang} />
    }
    render(<m.LangProvider><App /></m.LangProvider>)
    expect(await screen.findByTestId('app')).toHaveAttribute('data-lang', 'tr')
    expect(document.documentElement.lang).toBe('tr')
  })
})
