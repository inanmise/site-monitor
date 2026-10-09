import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, act, fireEvent } from '@testing-library/react'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

/**
 * İngilizce sözlük AYRI (lazy) chunk — 2026-10-02, performans önerisi 22 ("açılış paketini küçült").
 *
 * Sözleşme: ekranlar aynı kalır; tek görünür fark dil İngilizce'ye geçerken (ya da İngilizce açılırken) kısa bir
 * yükleme. Bu dosya YÜKLENMEMİŞ sözlük yolunu sınar — setup.js diğer testler için EN'yi önceden yükler, burada
 * küresel kayıttan silinir ve i18n modülü yeniden değerlendirilir. Kilitlenenler:
 *   1. Açılış (saklı dil 'en'): sözlük inene kadar uygulama ağacı DEĞİL açılış ekranı çizilir; ekran İngilizce
 *      ("Loading...") — ham anahtar ya da Türkçe metin yok; sözlük inince ağaç doğrudan İngilizce açılır.
 *   2. Çalışırken TR→EN: önce yükleme (pending='en', arayüz hâlâ Türkçe), sonra tek adımda İngilizce + tercih kaydı.
 *   3. İndirme hatası: arayüz Türkçe kalır, ham anahtar yok, hata bildirimi çıkar; çalışırken hata tercihi
 *      değiştirmez; açılışta hata saklı tercihi KORUR ama X-Lang bu oturumda 'tr' (arayüzle aynı dil).
 *   4. Önbellek: aynı anda gelen istekler tek indirmeyi paylaşır; inmiş sözlükle geçiş eskisi gibi ANINDA.
 */

const STORAGE_KEY = 'site-monitor-lang'
const EN_MODULE = '../i18n/en.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

/** Küresel sözlük kaydından EN'yi siler ve i18n'i (ve ona bağlı modülleri) taze örnekle yükler. */
async function freshI18n({ enModule } = {}) {
  delete globalThis.__smI18nDicts.en
  vi.resetModules()
  if (enModule) vi.doMock(EN_MODULE, enModule)
  const i18n = await import('../i18n/index.jsx')
  const status = await import('../components/LanguageStatus.jsx')
  const toast = await import('../components/ui/Toast.jsx')
  const dateLocale = await import('../i18n/dateLocale.js')
  return { ...i18n, ...status, ToastProvider: toast.ToastProvider, dateLocale }
}

afterEach(() => {
  vi.doUnmock(EN_MODULE)
  vi.resetModules()
  globalThis.__smI18nDicts.en = EN          // diğer testler için kayıt eski hâline
  localStorage.removeItem(STORAGE_KEY)
  document.documentElement.lang = ''
})

describe('i18n — İngilizce sözlük ayrı chunk (öneri 22)', () => {
  it('açılış metinleri (EN_BOOT) en.js ile BİREBİR aynı — sapma yok', async () => {
    const { EN_BOOT } = await import('../i18n/index.jsx')
    expect(Object.keys(EN_BOOT).length).toBeGreaterThan(0)
    for (const [k, v] of Object.entries(EN_BOOT)) expect(v, k).toBe(EN[k])
  })

  it('index.jsx statik EN dışa VERMEZ (verirse sözlük açılış paketine geri girer); TR geriye uyumlu', async () => {
    const mod = await import('../i18n/index.jsx')
    expect(mod.EN).toBeUndefined()
    // Aynı modül kaydındaki tr.js örneği (önceki testlerin resetModules'u yeni örnek üretmiş olabilir)
    expect(mod.TR).toBe((await import('../i18n/tr.js')).TR)
    expect(Object.keys(mod.TR)).toEqual(Object.keys(TR))
  })

  it('açılış, saklı dil en: sözlük inene kadar İngilizce açılış ekranı, sonra uygulama doğrudan İngilizce', async () => {
    localStorage.setItem(STORAGE_KEY, 'en')
    const gate = deferred()
    const m = await freshI18n({ enModule: async () => { await gate.promise; return { EN } } })
    expect(m.isLanguageLoaded('en')).toBe(false)

    function App() {
      const t = m.useT()
      return <p data-testid="app">{t('stat.total')}</p>
    }
    render(<m.LangProvider fallback={<m.LanguageBootSplash />}><App /></m.LangProvider>)

    // Açılış ekranı: uygulama ağacı yok, durum bölgesi İngilizce, ham anahtar yok
    expect(screen.queryByTestId('app')).toBeNull()
    const status = screen.getByRole('status')
    expect(status.textContent).toContain(EN['app.loading'])
    expect(document.body.textContent).not.toMatch(/app\.loading|brand\.logo/)
    expect(screen.getByRole('img').getAttribute('alt')).toBe(EN['brand.logo.alt.muted'])

    await act(async () => { gate.resolve() })
    expect(await screen.findByTestId('app')).toHaveTextContent(EN['stat.total'])
    expect(screen.queryByRole('status')).toBeNull()
    expect(document.documentElement.lang).toBe('en')
    expect(m.isLanguageLoaded('en')).toBe(true)
  })

  it('çalışırken TR→EN: önce yükleme (pending, arayüz Türkçe), sonra tek adımda İngilizce + tercih kaydı', async () => {
    localStorage.setItem(STORAGE_KEY, 'tr')
    const gate = deferred()
    const m = await freshI18n({ enModule: async () => { await gate.promise; return { EN } } })

    let result
    function Probe() {
      const t = m.useT()
      const { lang, pending, toggle } = m.useLanguage()
      return (
        <div>
          <p data-testid="text">{t('stat.total')}</p>
          <p data-testid="state">{`${lang}|${pending ?? '-'}`}</p>
          <button type="button" onClick={() => { result = toggle() }}>switch</button>
        </div>
      )
    }
    render(<m.LangProvider><Probe /></m.LangProvider>)
    expect(screen.getByTestId('text')).toHaveTextContent(TR['stat.total'])

    fireEvent.click(screen.getByRole('button', { name: 'switch' }))
    expect(screen.getByTestId('state')).toHaveTextContent('tr|en')           // meşgul: hedef en, etkin dil tr
    expect(screen.getByTestId('text')).toHaveTextContent(TR['stat.total'])   // yarım İngilizce / ham anahtar yok
    expect(localStorage.getItem(STORAGE_KEY)).toBe('tr')                      // inmeden tercih yazılmaz

    fireEvent.click(screen.getByRole('button', { name: 'switch' }))           // ikinci tık yeni indirme açmaz
    await act(async () => { gate.resolve() })
    await expect(result).resolves.toBe(true)
    expect(screen.getByTestId('state')).toHaveTextContent('en|-')
    expect(screen.getByTestId('text')).toHaveTextContent(EN['stat.total'])
    expect(localStorage.getItem(STORAGE_KEY)).toBe('en')
    expect(m.dateLocale.sessionLangOverride()).toBeNull()
  })

  it('çalışırken indirme hatası: Türkçe kalır, tercih değişmez, hata bildirimi (Türkçe) çıkar', async () => {
    localStorage.setItem(STORAGE_KEY, 'tr')
    const m = await freshI18n({ enModule: () => { throw new Error('chunk 404') } })

    let toggle
    function Probe() {
      const t = m.useT()
      const lang = m.useLanguage()
      toggle = lang.toggle
      return <p data-testid="text">{`${t('stat.total')}|${lang.lang}|${lang.pending ?? '-'}|${lang.loadFailures}`}</p>
    }
    render(
      <m.LangProvider>
        <m.ToastProvider>
          <m.LanguageLoadNotice />
          <Probe />
        </m.ToastProvider>
      </m.LangProvider>,
    )
    let ok
    await act(async () => { ok = await toggle() })
    expect(ok).toBe(false)
    expect(screen.getByTestId('text')).toHaveTextContent(`${TR['stat.total']}|tr|-|1`)
    expect(localStorage.getItem(STORAGE_KEY)).toBe('tr')
    expect(await screen.findByText(TR['lang.loadFailed'])).toBeInTheDocument()
    expect(m.isLanguageLoaded('en')).toBe(false)                               // başarısız indirme önbelleğe alınmaz
  })

  it('açılışta indirme hatası: uygulama Türkçe açılır, saklı tercih KORUNUR, X-Lang bu oturumda tr, bildirim', async () => {
    localStorage.setItem(STORAGE_KEY, 'en')
    const m = await freshI18n({ enModule: () => { throw new Error('offline') } })

    function App() {
      const t = m.useT()
      return <p data-testid="app">{t('stat.total')}</p>
    }
    render(
      <m.LangProvider fallback={<m.LanguageBootSplash />}>
        <m.ToastProvider>
          <m.LanguageLoadNotice />
          <App />
        </m.ToastProvider>
      </m.LangProvider>,
    )
    expect(await screen.findByTestId('app')).toHaveTextContent(TR['stat.total'])
    expect(document.documentElement.lang).toBe('tr')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('en')
    expect(m.dateLocale.sessionLangOverride()).toBe('tr')
    expect(await screen.findByText(TR['lang.loadFailed'])).toBeInTheDocument()
  })

  it('açılışta sözlük HİÇ inmezse (asılı istek): zaman aşımında Türkçe yedek yol — açılış ekranında sonsuza dek kalınmaz (2026-10-09)', async () => {
    localStorage.setItem(STORAGE_KEY, 'en')
    const m = await freshI18n({ enModule: () => new Promise(() => {}) })
    function App() {
      const t = m.useT()
      return <p data-testid="app">{t('stat.total')}</p>
    }
    vi.useFakeTimers()
    try {
      render(
        <m.LangProvider fallback={<m.LanguageBootSplash />}>
          <m.ToastProvider>
            <m.LanguageLoadNotice />
            <App />
          </m.ToastProvider>
        </m.LangProvider>,
      )
      expect(screen.queryByTestId('app')).toBeNull()
      await act(async () => { await vi.advanceTimersByTimeAsync(m.LANG_LOAD_TIMEOUT_MS - 100) })
      expect(screen.queryByTestId('app')).toBeNull()                         // süre dolmadan yedek yola düşülmez
      await act(async () => { await vi.advanceTimersByTimeAsync(200) })
      expect(screen.getByTestId('app')).toHaveTextContent(TR['stat.total'])
      expect(localStorage.getItem(STORAGE_KEY)).toBe('en')
      expect(m.dateLocale.sessionLangOverride()).toBe('tr')
      expect(m.isLanguageLoaded('en')).toBe(false)
      // Bildirim sahte saatte doğrulanır: gerçek saate dönüşte bekleyen sahte zamanlayıcılar (toast yerleşimi) düşer.
      await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
      expect(screen.getByText(TR['lang.loadFailed'])).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('aynı anda gelen yüklemeler TEK indirmeyi paylaşır; inmiş sözlükle geçiş ANINDA (pending yok)', async () => {
    let imports = 0
    const m = await freshI18n({ enModule: async () => { imports++; return { EN } } })
    m.preloadStoredLanguage()                                                // saklı dil yok → varsayılan en
    const [a, b] = await Promise.all([m.loadLanguage('en'), m.loadLanguage('en')])
    expect(a).toBe(EN)
    expect(b).toBe(EN)
    expect(imports).toBe(1)
    await expect(m.loadLanguage('tr')).resolves.toBe(m.TR)

    localStorage.setItem(STORAGE_KEY, 'tr')
    let ctx
    function Probe() { ctx = m.useLanguage(); return null }
    render(<m.LangProvider><Probe /></m.LangProvider>)
    act(() => { ctx.toggle() })
    expect(ctx.lang).toBe('en')
    expect(ctx.pending).toBeNull()
    expect(imports).toBe(1)
  })

  it('provider yokken ve EN inmemişken t() ham anahtar değil Türkçe karşılığı döner', async () => {
    localStorage.setItem(STORAGE_KEY, 'en')
    const m = await freshI18n()
    function Probe() { const t = m.useT(); return <span data-testid="p">{t('stat.total')}</span> }
    const spy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      render(<Probe />)
    } finally {
      spy.mockRestore()
    }
    expect(screen.getByTestId('p')).toHaveTextContent(TR['stat.total'])
    expect(m.isLanguageLoaded('en')).toBe(false)
  })
})
