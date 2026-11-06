import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, act, fireEvent } from '@testing-library/react'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

/**
 * Sözlüklerin İKİSİ DE ayrı (lazy) chunk — İngilizce 2026-10-02 (performans önerisi 22), Türkçe 2026-11-06 (performans:
 * varsayılan dil İngilizce olduğu hâlde ~570 KB gzip'lik Türkçe sözlük her açılışta iniyordu).
 *
 * Sözleşme: ekranlar aynı kalır; tek görünür fark bir dile geçerken (ya da o dilde açılırken) kısa bir yükleme. Bu dosya
 * YÜKLENMEMİŞ sözlük yolunu sınar — setup.js diğer testler için iki sözlüğü de önceden yükler, burada küresel kayıttan
 * silinir ve i18n modülü yeniden değerlendirilir. Kilitlenenler:
 *   1. Açılış: saklı dilin sözlüğü inene kadar uygulama ağacı DEĞİL açılış ekranı çizilir; ekran o dilde (EN_BOOT /
 *      TR_BOOT) — ham anahtar ya da öbür dilde metin yok; sözlük inince ağaç doğrudan o dilde açılır.
 *   2. Çalışırken TR↔EN: önce yükleme (pending, arayüz eski dilde), sonra tek adımda yeni dil + tercih kaydı; hata sayfayı
 *      yenilemez, bildirim çıkar.
 *   3. Açılış hata zinciri: tek yenileme hakkı → öbür dilin sözlüğü (bildirim + oturumluk X-Lang, saklı tercih korunur) →
 *      ikisi de inmezse sözlüksüz, sabit metinli hata ekranı ("Yeniden yükle"); zaman aşımı sonsuz beklemeyi keser.
 *   4. Önbellek: aynı anda gelen istekler tek indirmeyi paylaşır; inmiş sözlükle geçiş ANINDA; tanınmayan kod Türkçe sözlük.
 */

const STORAGE_KEY = 'site-monitor-lang'
const EN_MODULE = '../i18n/en.js'
const TR_MODULE = '../i18n/tr.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

const fails = (msg = 'chunk 404') => () => { throw new Error(msg) }
const hangs = () => () => new Promise(() => {})

/**
 * Küresel sözlük kaydından `drop` dillerini siler (varsayılan: modülü verilen diller, yoksa yalnız İngilizce) ve i18n'i
 * (ve ona bağlı modülleri) taze örnekle yükler. `enModule` / `trModule` verilirse o dilin parça içe aktarımı taklit edilir.
 */
async function freshI18n({ enModule, trModule, drop } = {}) {
  const gone = drop ?? [...(enModule ? ['en'] : []), ...(trModule ? ['tr'] : [])]
  for (const l of (gone.length ? gone : ['en'])) delete globalThis.__smI18nDicts[l]
  vi.resetModules()
  if (enModule) vi.doMock(EN_MODULE, enModule)
  if (trModule) vi.doMock(TR_MODULE, trModule)
  const i18n = await import('../i18n/index.jsx')
  const status = await import('../components/LanguageStatus.jsx')
  const toast = await import('../components/ui/Toast.jsx')
  const dateLocale = await import('../i18n/dateLocale.js')
  // Aynı modül kaydındaki örnek: i18n/index.jsx ve LanguageStatus bunu kullanır (resetModules sonrası yeni örnek)
  const guard = await import('../utils/chunkReloadGuard.js')
  return { ...i18n, ...status, ToastProvider: toast.ToastProvider, dateLocale, guard }
}

/** Açılış hatasında tek yenileme hakkı (çok pod R2b, MP-47) bu testte zaten KULLANILMIŞ: öbür dile düşme yolu sınanır. */
function reloadAlreadyUsed(m) {
  const reload = vi.spyOn(m.guard.reloader, 'reload').mockImplementation(() => {})
  expect(m.guard.claimChunkReload(undefined, Date.now() - 10 * m.guard.RELOAD_COOLDOWN_MS)).toBe(true)
  return reload
}

/** main.jsx'teki ağaç: açılış ekranı + hata ekranı + bildirim. */
function Tree({ m, children }) {
  return (
    <m.LangProvider fallback={<m.LanguageBootSplash />} failure={<m.LanguageBootFailure />}>
      <m.ToastProvider>
        <m.LanguageLoadNotice />
        {children}
      </m.ToastProvider>
    </m.LangProvider>
  )
}

/** Sayfa metninde sözlük ANAHTARI gibi görünen ve gerçekten bir anahtar olan parçalar (ham anahtar sızıntısı). */
const rawKeys = () => (document.body.textContent.match(/\b[a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9_-]+)+\b/g) || [])
  .filter((tk) => tk in TR)

afterEach(() => {
  vi.restoreAllMocks()
  sessionStorage.removeItem('eb-chunk-reload')
  vi.doUnmock(EN_MODULE)
  vi.doUnmock(TR_MODULE)
  vi.doUnmock('../components/ui/Toast.jsx')
  vi.resetModules()
  globalThis.__smI18nDicts.en = EN          // diğer testler için kayıt eski hâline
  globalThis.__smI18nDicts.tr = TR
  localStorage.removeItem(STORAGE_KEY)
  document.documentElement.lang = ''
})

describe('i18n — sözlükler ayrı chunk (öneri 22 + 2026-11-06 Türkçe)', () => {
  it('açılış metinleri (EN_BOOT / TR_BOOT) en.js / tr.js ile BİREBİR aynı ve aynı anahtarlar — sapma yok', async () => {
    const { EN_BOOT, TR_BOOT } = await import('../i18n/index.jsx')
    expect(Object.keys(EN_BOOT).length).toBeGreaterThan(0)
    expect(Object.keys(TR_BOOT)).toEqual(Object.keys(EN_BOOT))
    for (const [k, v] of Object.entries(EN_BOOT)) expect(v, k).toBe(EN[k])
    for (const [k, v] of Object.entries(TR_BOOT)) expect(v, k).toBe(TR[k])
    // Açılış ve sözlüksüz hata ekranı metinleri açılış metinlerinde (ekranlar sözlük yokken de ham anahtar basmaz)
    for (const k of ['app.loading', 'brand.logo.alt.muted', 'lang.bootFailed.title', 'lang.bootFailed.body', 'lang.bootReload',
      'err.title', 'err.detail', 'err.reload']) {
      expect(EN_BOOT[k], k).toBeTruthy()
    }
  })

  it('index.jsx statik TR / EN dışa VERMEZ (verirse sözlük açılış paketine geri girer)', async () => {
    const mod = await import('../i18n/index.jsx')
    expect(mod.EN).toBeUndefined()
    expect(mod.TR).toBeUndefined()
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

  it('açılış, saklı dil tr: Türkçe sözlük de iner — açılış ekranı TÜRKÇE, sonra uygulama doğrudan Türkçe', async () => {
    localStorage.setItem(STORAGE_KEY, 'tr')
    const gate = deferred()
    let enImports = 0
    const m = await freshI18n({
      trModule: async () => { await gate.promise; return { TR } },
      enModule: async () => { enImports++; return { EN } },
    })
    expect(m.isLanguageLoaded('tr')).toBe(false)
    function App() {
      const t = m.useT()
      return <p data-testid="app">{t('stat.total')}</p>
    }
    render(<Tree m={m}><App /></Tree>)
    expect(screen.queryByTestId('app')).toBeNull()
    expect(screen.getByRole('status').textContent).toContain(TR['app.loading'])
    expect(screen.getByRole('img').getAttribute('alt')).toBe(TR['brand.logo.alt.muted'])
    expect(document.body.textContent).not.toContain(EN['app.loading'])
    expect(rawKeys()).toEqual([])

    await act(async () => { gate.resolve() })
    expect(await screen.findByTestId('app')).toHaveTextContent(TR['stat.total'])
    expect(document.documentElement.lang).toBe('tr')
    expect(m.isLanguageLoaded('tr')).toBe(true)
    expect(m.isLanguageLoaded('en')).toBe(false)
    expect(enImports).toBe(0)                                                  // Türkçe kullanıcı İngilizce sözlüğü indirmez
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

  it('çalışırken EN→TR (Türkçe sözlük inmemiş): pending, sonra Türkçe + tercih kaydı', async () => {
    localStorage.setItem(STORAGE_KEY, 'en')
    const gate = deferred()
    const m = await freshI18n({ trModule: async () => { await gate.promise; return { TR } } })
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
    expect(screen.getByTestId('text')).toHaveTextContent(EN['stat.total'])
    fireEvent.click(screen.getByRole('button', { name: 'switch' }))
    expect(screen.getByTestId('state')).toHaveTextContent('en|tr')
    expect(screen.getByTestId('text')).toHaveTextContent(EN['stat.total'])
    expect(localStorage.getItem(STORAGE_KEY)).toBe('en')
    await act(async () => { gate.resolve() })
    await expect(result).resolves.toBe(true)
    expect(screen.getByTestId('state')).toHaveTextContent('tr|-')
    expect(screen.getByTestId('text')).toHaveTextContent(TR['stat.total'])
    expect(localStorage.getItem(STORAGE_KEY)).toBe('tr')
  })

  it('çalışırken indirme hatası: Türkçe kalır, tercih değişmez, hata bildirimi (Türkçe) çıkar', async () => {
    localStorage.setItem(STORAGE_KEY, 'tr')
    const m = await freshI18n({ enModule: fails() })

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
    const reload = vi.spyOn(m.guard.reloader, 'reload').mockImplementation(() => {})
    let ok
    await act(async () => { ok = await toggle() })
    expect(ok).toBe(false)
    expect(reload).not.toHaveBeenCalled()                                    // çalışırken hata sayfayı YENİLEMEZ
    expect(screen.getByTestId('text')).toHaveTextContent(`${TR['stat.total']}|tr|-|1`)
    expect(localStorage.getItem(STORAGE_KEY)).toBe('tr')
    expect(await screen.findByText(TR['lang.loadFailed'])).toBeInTheDocument()
    expect(m.isLanguageLoaded('en')).toBe(false)                               // başarısız indirme önbelleğe alınmaz
  })

  it('çalışırken EN→TR indirme hatası: İngilizce kalır, sayfa yenilenmez, bildirim İngilizce ve DOĞRU dosyayı söyler', async () => {
    localStorage.setItem(STORAGE_KEY, 'en')
    const m = await freshI18n({ trModule: fails() })
    let toggle
    function Probe() {
      const t = m.useT()
      const lang = m.useLanguage()
      toggle = lang.toggle
      return <p data-testid="text">{`${t('stat.total')}|${lang.lang}|${lang.loadFailures}`}</p>
    }
    render(<Tree m={m}><Probe /></Tree>)
    const reload = vi.spyOn(m.guard.reloader, 'reload').mockImplementation(() => {})
    let ok
    await act(async () => { ok = await toggle() })
    expect(ok).toBe(false)
    expect(reload).not.toHaveBeenCalled()
    expect(screen.getByTestId('text')).toHaveTextContent(`${EN['stat.total']}|en|1`)
    expect(localStorage.getItem(STORAGE_KEY)).toBe('en')
    expect(await screen.findByText(EN['lang.loadFailedTr'])).toBeInTheDocument()
    expect(screen.queryByText(EN['lang.loadFailed'])).toBeNull()              // "İngilizce dosya yüklenemedi" DEMEZ
    expect(m.isLanguageLoaded('tr')).toBe(false)
  })

  it("açılışta İLK indirme hatası (yuvarlanan dağıtım): sayfa BİR KEZ yenilenir, açılış ekranı kalır, öbür dile düşülmez", async () => {
    localStorage.setItem(STORAGE_KEY, 'en')
    const m = await freshI18n({ enModule: fails() })
    const reload = vi.spyOn(m.guard.reloader, 'reload').mockImplementation(() => {})
    function App() {
      const t = m.useT()
      return <p data-testid="app">{t('stat.total')}</p>
    }
    render(<Tree m={m}><App /></Tree>)
    await vi.waitFor(() => expect(reload).toHaveBeenCalledTimes(1))
    await act(async () => { await Promise.resolve() })
    expect(screen.queryByTestId('app')).toBeNull()                         // yenilenene kadar açılış ekranı
    expect(screen.getByRole('status').textContent).toContain(EN['app.loading'])
    expect(m.dateLocale.sessionLangOverride()).toBeNull()                    // X-Lang Türkçe'ye zorlanmadı
    expect(screen.queryByText(TR['lang.loadFailed'])).toBeNull()
    expect(localStorage.getItem(STORAGE_KEY)).toBe('en')
  })

  it('açılış, saklı dil tr, Türkçe sözlüğün İLK hatası: aynı ortak hakla BİR KEZ yenilenir (simetrik)', async () => {
    localStorage.setItem(STORAGE_KEY, 'tr')
    let enImports = 0
    const m = await freshI18n({ trModule: fails(), enModule: async () => { enImports++; return { EN } } })
    const reload = vi.spyOn(m.guard.reloader, 'reload').mockImplementation(() => {})
    render(<Tree m={m}><p data-testid="app">x</p></Tree>)
    await vi.waitFor(() => expect(reload).toHaveBeenCalledTimes(1))
    await act(async () => { await Promise.resolve() })
    expect(screen.queryByTestId('app')).toBeNull()
    expect(screen.getByRole('status').textContent).toContain(TR['app.loading'])
    expect(enImports).toBe(0)                                                // yenileme beklenirken öbür dil denenmez
  })

  it('açılışta indirme hatası (yenileme hakkı kullanılmış): uygulama Türkçe açılır, saklı tercih KORUNUR, X-Lang bu oturumda tr, bildirim', async () => {
    localStorage.setItem(STORAGE_KEY, 'en')
    const m = await freshI18n({ enModule: fails('offline') })
    reloadAlreadyUsed(m)

    function App() {
      const t = m.useT()
      return <p data-testid="app">{t('stat.total')}</p>
    }
    render(<Tree m={m}><App /></Tree>)
    expect(await screen.findByTestId('app')).toHaveTextContent(TR['stat.total'])
    expect(document.documentElement.lang).toBe('tr')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('en')
    expect(m.dateLocale.sessionLangOverride()).toBe('tr')
    expect(await screen.findByText(TR['lang.loadFailed'])).toBeInTheDocument()
  })

  it('açılış, saklı dil en: İngilizce inmez, Türkçe de henüz yüklü değil → Türkçe sözlük İNDİRİLİR ve uygulama Türkçe açılır', async () => {
    localStorage.setItem(STORAGE_KEY, 'en')
    let trImports = 0
    const m = await freshI18n({ enModule: fails('offline'), trModule: async () => { trImports++; return { TR } } })
    reloadAlreadyUsed(m)
    function App() {
      const t = m.useT()
      return <p data-testid="app">{t('stat.total')}</p>
    }
    render(<Tree m={m}><App /></Tree>)
    expect(await screen.findByTestId('app')).toHaveTextContent(TR['stat.total'])
    expect(trImports).toBe(1)
    expect(m.dateLocale.sessionLangOverride()).toBe('tr')
    expect(await screen.findByText(TR['lang.loadFailed'])).toBeInTheDocument()
  })

  it('açılış, saklı dil tr: Türkçe inmez (hak kullanılmış) → uygulama İNGİLİZCE açılır, tercih tr kalır, X-Lang en, bildirim İngilizce', async () => {
    localStorage.setItem(STORAGE_KEY, 'tr')
    const m = await freshI18n({ trModule: fails('offline'), enModule: async () => ({ EN }) })
    const reload = reloadAlreadyUsed(m)
    function App() {
      const t = m.useT()
      return <p data-testid="app">{t('stat.total')}</p>
    }
    render(<Tree m={m}><App /></Tree>)
    expect(await screen.findByTestId('app')).toHaveTextContent(EN['stat.total'])
    expect(document.documentElement.lang).toBe('en')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('tr')
    expect(m.dateLocale.sessionLangOverride()).toBe('en')
    expect(m.dateLocale.dateLocale()).toBe('en-GB')                          // tarih yereli arayüzle aynı dil
    expect(await screen.findByText(EN['lang.loadFailedTr'])).toBeInTheDocument()
    expect(reload).not.toHaveBeenCalled()
  })

  for (const stored of ['en', 'tr']) {
    it(`açılışta İKİ sözlük de inmezse (saklı dil ${stored}): sözlüksüz, sabit metinli hata ekranı + "Yeniden yükle"`, async () => {
      localStorage.setItem(STORAGE_KEY, stored)
      const m = await freshI18n({ enModule: fails(), trModule: fails() })
      const reload = reloadAlreadyUsed(m)
      const boot = stored === 'en' ? m.EN_BOOT : m.TR_BOOT
      render(<Tree m={m}><p data-testid="app">x</p></Tree>)
      const alert = await screen.findByRole('alert')
      expect(screen.queryByTestId('app')).toBeNull()
      expect(screen.queryByRole('status')).toBeNull()                         // "Yükleniyor..." ekranında kalınmaz
      expect(alert).toHaveTextContent(boot['lang.bootFailed.title'])
      expect(alert).toHaveTextContent(boot['lang.bootFailed.body'])
      const wrap = document.querySelector('[data-slot="lang-boot-failure"]')
      expect(wrap).not.toBeNull()
      expect(wrap.getAttribute('lang')).toBe(stored)
      expect(screen.getByRole('img').getAttribute('alt')).toBe(boot['brand.logo.alt.muted'])
      expect(rawKeys()).toEqual([])
      expect(reload).not.toHaveBeenCalled()
      fireEvent.click(screen.getByRole('button', { name: boot['lang.bootReload'] }))
      expect(reload).toHaveBeenCalledTimes(1)                                 // kullanıcının kendi yenilemesi sayaçsız
      expect(m.isLanguageLoaded('en')).toBe(false)
      expect(m.isLanguageLoaded('tr')).toBe(false)
      expect(localStorage.getItem(STORAGE_KEY)).toBe(stored)
    })
  }

  it('StrictMode (geliştirme): açılış hatası bildirimi sağlayıcıyla aynı işlemede doğsa da KAYBOLMAZ', async () => {
    // Gerçek ToastProvider'ın sözleşmesi: sökümünde (StrictMode'un sahte sökümü dahil) açık bütün kutuları kapatır.
    // Bildirim bileşeni sağlayıcıyla AYNI işlemede doğar (dil hazır olunca ağaç ilk kez çizilir) — yeniden kurulumda
    // bildirimi yeniden açmalı. jsdom'da Sonner'ın kapatma yarışı görünmediği için sağlayıcı burada sadeleştirilmiş eşi.
    vi.doMock('../components/ui/Toast.jsx', async () => {
      const React = await import('react')
      const Ctx = React.createContext(null)
      function ToastProvider({ children }) {
        const [items, setItems] = React.useState([])
        const api = React.useMemo(() => ({ error: (msg) => setItems((xs) => [...xs, msg]) }), [])
        React.useEffect(() => () => setItems([]), [])                       // söküm: açık kutular kapanır
        return <Ctx.Provider value={api}>{children}<ul data-testid="toasts">{items.map((x, i) => <li key={i}>{x}</li>)}</ul></Ctx.Provider>
      }
      return { ToastProvider, useToast: () => React.useContext(Ctx) }
    })
    localStorage.setItem(STORAGE_KEY, 'en')
    const m = await freshI18n({ enModule: fails('offline') })
    reloadAlreadyUsed(m)
    const { StrictMode } = await import('react')
    function App() {
      const t = m.useT()
      return <p data-testid="app">{t('stat.total')}</p>
    }
    render(
      <StrictMode>
        <m.LangProvider fallback={<m.LanguageBootSplash />}>
          <m.ToastProvider>
            <m.LanguageLoadNotice />
            <App />
          </m.ToastProvider>
        </m.LangProvider>
      </StrictMode>,
    )
    expect(await screen.findByTestId('app')).toHaveTextContent(TR['stat.total'])
    await act(async () => { await Promise.resolve() })
    expect(screen.getByTestId('toasts')).toHaveTextContent(TR['lang.loadFailed'])
  })

  it('açılışta sözlük HİÇ inmezse (asılı istek): zaman aşımında öbür dile düşülür — açılış ekranında sonsuza dek kalınmaz (2026-10-09)', async () => {
    localStorage.setItem(STORAGE_KEY, 'en')
    const m = await freshI18n({ enModule: hangs() })
    reloadAlreadyUsed(m)
    function App() {
      const t = m.useT()
      return <p data-testid="app">{t('stat.total')}</p>
    }
    vi.useFakeTimers()
    try {
      render(<Tree m={m}><App /></Tree>)
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

  it('iki sözlük de asılı kalırsa: en çok 2 × zaman aşımı sonra hata ekranı (sonsuz bekleme yok)', async () => {
    localStorage.setItem(STORAGE_KEY, 'tr')
    const m = await freshI18n({ enModule: hangs(), trModule: hangs() })
    reloadAlreadyUsed(m)
    vi.useFakeTimers()
    try {
      render(<Tree m={m}><p data-testid="app">x</p></Tree>)
      await act(async () => { await vi.advanceTimersByTimeAsync(m.LANG_LOAD_TIMEOUT_MS + 100) })
      expect(screen.getByRole('status').textContent).toContain(TR['app.loading'])   // ikinci dil deneniyor
      expect(screen.queryByRole('alert')).toBeNull()
      await act(async () => { await vi.advanceTimersByTimeAsync(m.LANG_LOAD_TIMEOUT_MS) })
      expect(screen.getByRole('alert')).toHaveTextContent(m.TR_BOOT['lang.bootFailed.title'])
      expect(screen.queryByTestId('app')).toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })

  it('aynı anda gelen yüklemeler TEK indirmeyi paylaşır; inmiş sözlükle geçiş ANINDA (pending yok)', async () => {
    let imports = 0
    let trImports = 0
    const m = await freshI18n({
      enModule: async () => { imports++; return { EN } },
      trModule: async () => { trImports++; return { TR } },
    })
    m.preloadStoredLanguage()                                                // saklı dil yok → varsayılan en
    const [a, b] = await Promise.all([m.loadLanguage('en'), m.loadLanguage('en')])
    expect(a).toBe(EN)
    expect(b).toBe(EN)
    expect(imports).toBe(1)
    expect(trImports).toBe(0)                                                // varsayılan kullanıcı Türkçe sözlüğü indirmez
    const [c, d] = await Promise.all([m.loadLanguage('tr'), m.loadLanguage('tr')])
    expect(c).toBe(TR)
    expect(d).toBe(TR)
    expect(trImports).toBe(1)

    localStorage.setItem(STORAGE_KEY, 'tr')
    let ctx
    function Probe() { ctx = m.useLanguage(); return null }
    render(<m.LangProvider><Probe /></m.LangProvider>)
    act(() => { ctx.toggle() })
    expect(ctx.lang).toBe('en')
    expect(ctx.pending).toBeNull()
    expect(imports).toBe(1)
  })

  it('preloadStoredLanguage: saklı dil tr ise Türkçe sözlük isteğini ilk çizimden önce başlatır (İngilizce istenmez)', async () => {
    localStorage.setItem(STORAGE_KEY, 'tr')
    let trImports = 0
    let enImports = 0
    const m = await freshI18n({
      enModule: async () => { enImports++; return { EN } },
      trModule: async () => { trImports++; return { TR } },
    })
    m.preloadStoredLanguage()
    await vi.waitFor(() => expect(m.isLanguageLoaded('tr')).toBe(true))
    expect(trImports).toBe(1)
    expect(enImports).toBe(0)
    m.preloadStoredLanguage()                                                // yüklüyse yeni istek yok
    expect(trImports).toBe(1)
  })

  it('tanınmayan saklı kod (bozuk depolama) eskisi gibi Türkçe sözlükle çalışır — Türkçe sözlüğü yükler', async () => {
    localStorage.setItem(STORAGE_KEY, 'de')
    let trImports = 0
    const m = await freshI18n({ trModule: async () => { trImports++; return { TR } } })
    expect(m.dictLang('de')).toBe('tr')
    expect(m.isLanguageLoaded('de')).toBe(false)
    function App() {
      const t = m.useT()
      return <p data-testid="app">{t('stat.total')}</p>
    }
    render(<Tree m={m}><App /></Tree>)
    expect(await screen.findByTestId('app')).toHaveTextContent(TR['stat.total'])
    expect(trImports).toBe(1)
    expect(m.isLanguageLoaded('de')).toBe(true)
    expect(m.dateLocale.dateLocale()).toBe('tr-TR')                          // localeFor ile aynı kural
  })

  it('başarısız indirme önbelleğe ALINMAZ: sonraki çağrı yeniden dener', async () => {
    // vitest taklit modülü önbelleğe alır (fabrika bir kez çalışır) — ilk okumada sözlük yok, ikincide var: i18n'in kendi
    // önbelleği (DICTS + sürmekte olan istek) başarısızlığı saklasaydı ikinci çağrı da reddedilirdi.
    let reads = 0
    const m = await freshI18n({ trModule: () => ({ get TR() { reads++; return reads === 1 ? undefined : TR } }) })
    await expect(m.loadLanguage('tr')).rejects.toThrow()
    expect(m.isLanguageLoaded('tr')).toBe(false)
    await expect(m.loadLanguage('tr')).resolves.toBe(TR)
    expect(m.isLanguageLoaded('tr')).toBe(true)
    expect(reads).toBe(2)
  })

  it('sözlük nesnesi taşımayan modül başarısızlık sayılır (yanlış dışa aktarım "hazır" görünmez)', async () => {
    const m = await freshI18n({ trModule: async () => ({ NOT_TR: {} }) })
    await expect(m.loadLanguage('tr')).rejects.toThrow()
    expect(m.isLanguageLoaded('tr')).toBe(false)
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

  it('provider yokken HİÇBİR sözlük yokken: çökme ekranının metinleri açılış metinlerinden (ham anahtar yok)', async () => {
    localStorage.setItem(STORAGE_KEY, 'tr')
    const m = await freshI18n({ drop: ['en', 'tr'] })
    function Probe() {
      const t = m.useT()
      return <span data-testid="p">{`${t('err.title')}|${t('err.reload')}|${t('app.loading')}`}</span>
    }
    const spy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      render(<Probe />)
    } finally {
      spy.mockRestore()
    }
    expect(screen.getByTestId('p')).toHaveTextContent(`${TR['err.title']}|${TR['err.reload']}|${TR['app.loading']}`)
  })
})
