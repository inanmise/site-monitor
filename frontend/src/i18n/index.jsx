import { createContext, useContext, useState, useCallback, useEffect, useMemo, useRef } from 'react'

import { LANG_STORAGE_KEY, localeFor, setDateLocale, setSessionLang } from './dateLocale.js'
import { TR } from './tr.js'

// Anahtar ve dil→yerel eslemesi dateLocale.js'te TEK kaynakta: `api/client.js`'teki duz
// formatlayicilar da oradan okuyor, iki yerde yazilsa sessizce ayrisirlardi.
const STORAGE_KEY = LANG_STORAGE_KEY

// ── Dictionaries ──────────────────────────────────────────────────────────────

/**
 * Sözlükler AYRI dosyalarda (2026-10-02, performans önerisi 22 — "açılış paketini küçült"):
 *   • `tr.js` — STATİK import: açılış paketinde, ilk boyamada eşzamanlı hazır.
 *   • `en.js` — DİNAMİK import ({@link loadLanguage}): kendi chunk'ı; yalnız İngilizce gerektiğinde iner.
 * Eskiden iki sözlük de bu dosyadaydı ve ~1,8 MB'lık metnin YARISI (kullanılmayan dil) her açılışta
 * indirilip ayrıştırılıyordu.
 *
 * Testler sözlüklere doğrudan `./tr.js` / `./en.js` üzerinden erişir. `TR` geriye uyum için buradan da
 * dışa verilir; `EN` VERİLMEZ — buradan statik bir `EN` dışa aktarımı onu yeniden açılış paketine sokardı.
 */
export { TR }

/**
 * Yüklenmiş sözlükler — globalThis'e SABİT (aşağıdaki LangCtx ile aynı gerekçe: Vite HMR bu modülü iki
 * örnek olarak yükleyebilir, testler `vi.resetModules()` ile yeniden değerlendirir). Bir örneğin indirdiği
 * İngilizce sözlüğü öbürü yeniden indirmez. TR her değerlendirmede tazelenir (HMR'da düzenlenmiş TR).
 */
const DICTS = (globalThis.__smI18nDicts ??= {})
DICTS.tr = TR

/**
 * Açılış ekranının (İngilizce sözlük inerken) ihtiyaç duyduğu birkaç metin — `en.js`'teki değerlerin
 * BİREBİR kopyası (`i18n-lazy.test.jsx` eşitliği kilitler). Ekran böylece İngilizce kullanıcıya
 * Türkçe "Yükleniyor..." ya da ham anahtar göstermez.
 */
export const EN_BOOT = Object.freeze({
  'app.loading': 'Loading...',
  'brand.logo.alt.muted': 'SiteMonitor — awaiting data',
})

/** Yalnız İngilizce ayrı yüklenir; tanınmayan bir dil kodu (bozuk depolama) eskisi gibi TR sözlüğüyle çalışır. */
const needsLoad = (lang) => lang === 'en' && !DICTS.en

let enLoading = null

/**
 * Dil sözlüğünü yükler (önbellekli; aynı anda gelen çağrılar TEK isteği paylaşır). TR ve tanınmayan
 * kodlar anında çözülür. Başarısız indirme önbelleğe ALINMAZ — sonraki çağrı yeniden dener.
 * @returns {Promise<object>} sözlük nesnesi
 */
export function loadLanguage(lang) {
  if (lang !== 'en') return Promise.resolve(TR)
  if (DICTS.en) return Promise.resolve(DICTS.en)
  if (!enLoading) {
    enLoading = import('./en.js').then(
      (m) => { DICTS.en = m.EN; enLoading = null; return m.EN },
      (err) => { enLoading = null; throw err },
    )
  }
  return enLoading
}

/** Sözlük şu an eşzamanlı kullanılabilir mi (TR her zaman; EN yüklendiyse). */
export function isLanguageLoaded(lang) {
  return !needsLoad(lang)
}

function translate(lang, key) {
  if (lang !== 'en') return TR[key] ?? key
  const en = DICTS.en
  if (en) return en[key] ?? key
  // İngilizce henüz inmedi (açılış ekranı ya da provider'sız yedek yol): ham anahtar yerine önce açılış
  // metinleri, sonra Türkçe karşılık. Provider'lı ağaçta bu dal açılış ekranından başka yerde çalışmaz —
  // LangProvider etkin dili sözlük inmeden 'en' yapmaz.
  return EN_BOOT[key] ?? TR[key] ?? key
}

// ── Context ───────────────────────────────────────────────────────────────────

/**
 * Context nesnesi globalThis'e SABİTLENİR. Vite HMR bu dosyayı bazen iki ayrı modül
 * örneği olarak yükler (aynı yol, farklı `?t=` damgası); her örnek kendi createContext'ini
 * üretirse A örneğinin Provider'ı B örneğinin useContext'inde görünmez ve tüketici null
 * context'le karşılaşır. Tek bir paylaşılan nesne bu sınıfı tamamen kapatır.
 *
 * Varsayılan değer null DEĞİL, çalışan bir context. Gerekçe: hata yüzeyinin kendisi
 * (ErrorBoundary → ErrorFallback) useT() çağırıyor. Provider yokken throw etmek —
 * Toast/Dialog'daki desen — fallback'i patlatır ve onu yakalayacak bir üst sınır yoktur
 * (main.jsx'te ErrorBoundary en içte), sonuç beyaz ekran olur. Bu yüzden burada
 * UserDirectory'deki "çalışan varsayılan" deseni izleniyor: provider'sız da t() çalışır,
 * yalnız toggle() işlevsizdir (hata ekranında dil değiştirme zaten yok).
 */
function storedLang() {
  try { return localStorage.getItem(STORAGE_KEY) || 'en' } catch { return 'en' }
}

function persistLang(lang) {
  try { localStorage.setItem(STORAGE_KEY, lang) } catch { /* depolama yok: dil bu oturumda geçerli */ }
}

/**
 * Saklı dil İngilizce ise sözlüğünü React'in ilk çiziminden ÖNCE istemeye başlar (main.jsx çağırır):
 * chunk isteği ilk render'la paralel yürür. Hata burada yutulur; LangProvider aynı yüklemeyi bekleyip
 * hatayı kendisi ele alır (Türkçe'de kalır + bildirim).
 */
export function preloadStoredLanguage() {
  const lang = storedLang()
  if (needsLoad(lang)) loadLanguage(lang).catch(() => {})
}

// Tek, kimliği sabit yedek nesne: hem context'in varsayılanı hem de useLanguage'in
// provider yokken döndürdüğü değer. `lang` alanı OKUMA anında tazelenir — createContext
// bir kez çalıştığı (globalThis'e sabitli) için burada dondurulmuş bir dil, dili sonradan
// değiştiren bir oturumda bayat kalırdı.
const FALLBACK_CTX = { lang: 'en', toggle: () => Promise.resolve(false), pending: null, loadFailures: 0, fallback: true }

const LangCtx = (globalThis.__smLangCtx ??= createContext(FALLBACK_CTX))

/**
 * Dil sağlayıcısı.
 *
 * <p><b>Etkin dil yalnız sözlüğü HAZIRSA değişir</b> (2026-10-02, öneri 22): İngilizce sözlük ayrı bir chunk.
 * <ul>
 *   <li>Açılış, saklı dil 'en' ve sözlük henüz inmemişken: ağaç yerine {@code fallback} (açılış ekranı) çizilir;
 *       sözlük inince uygulama doğrudan İngilizce açılır — ham anahtar ya da Türkçe'den İngilizce'ye kayma yok.</li>
 *   <li>Çalışırken TR→EN: önce sözlük iner ({@code pending: 'en'} — dil denetimi meşgul göstergesi çizer), sonra
 *       dil tek adımda değişir ve tercih kaydedilir. Sözlük önbellekteyse geçiş eskisi gibi ANINDA olur.</li>
 *   <li>İndirme başarısız: arayüz Türkçe kalır, {@code loadFailures} artar (LanguageLoadNotice bildirim gösterir).
 *       Açılışta başarısızsa saklı tercih DEĞİŞMEZ (geçici hata kalıcı tercihi bozmasın); sunucu iletileri arayüzle
 *       aynı dilde kalsın diye X-Lang bu oturum için 'tr'ye zorlanır (dateLocale.setSessionLang).</li>
 * </ul>
 */
export function LangProvider({ children, fallback = null }) {
  // storedLang() try/catch'li. LangProvider ErrorBoundary'nin ÜSTÜNDE: fırlarsa beyaz ekran (bkz. theme.jsx aynı düzeltme).
  const [state, setState] = useState(() => {
    const lang = storedLang()
    return { lang, ready: !needsLoad(lang), pending: null, loadFailures: 0 }
  })
  const { lang, ready, pending, loadFailures } = state
  // Güncel etkin dil — toggle hedefini hesaplamak için; her dil değişiminde setState ile AYNI anda yazılır.
  const langRef = useRef(lang)
  const switching = useRef(null)
  const alive = useRef(true)

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])

  useEffect(() => {
    document.documentElement.lang = lang
    // Hook kullanamayan duz formatlayicilar (api/client.js formatDate*) icin ayna.
    setDateLocale(lang)
  }, [lang])

  // Açılış: saklı dilin sözlüğü henüz yoksa iner; inene kadar fallback çizilir.
  useEffect(() => {
    if (ready) return undefined
    let cancelled = false
    loadLanguage(lang).then(
      () => { if (!cancelled) setState((s) => ({ ...s, ready: true })) },
      () => {
        if (cancelled) return
        langRef.current = 'tr'
        setSessionLang('tr')
        setState((s) => ({ ...s, lang: 'tr', ready: true, loadFailures: s.loadFailures + 1 }))
      },
    )
    return () => { cancelled = true }
  }, [ready, lang])

  const switchTo = useCallback((next) => {
    if (!needsLoad(next)) {
      langRef.current = next
      persistLang(next)
      setSessionLang(null)
      setState((s) => ({ ...s, lang: next, ready: true, pending: null }))
      return Promise.resolve(true)
    }
    if (switching.current) return switching.current   // aynı indirme sürüyor — ikinci tık yeni istek açmaz
    setState((s) => ({ ...s, pending: next }))
    switching.current = loadLanguage(next).then(
      () => {
        switching.current = null
        langRef.current = next
        persistLang(next)
        setSessionLang(null)
        if (alive.current) setState((s) => ({ ...s, lang: next, ready: true, pending: null }))
        return true
      },
      () => {
        switching.current = null
        if (alive.current) setState((s) => ({ ...s, pending: null, loadFailures: s.loadFailures + 1 }))
        return false
      },
    )
    return switching.current
  }, [])

  /** TR ↔ EN. Sözlük inmemişse önce yükler; Promise<boolean> döner (true = dil değişti). */
  const toggle = useCallback(() => switchTo(langRef.current === 'tr' ? 'en' : 'tr'), [switchTo])

  const value = useMemo(() => ({ lang, toggle, pending, loadFailures }), [lang, toggle, pending, loadFailures])

  return <LangCtx.Provider value={value}>{ready ? children : fallback}</LangCtx.Provider>
}

/**
 * Alt ağacı SABİT bir dilde çizer — arayüz dilini DEĞİŞTİRMEZ (2026-10-02, Sistem Bakımı önizlemesi: yönetici giriş kartını,
 * şeritleri ve geri sayım penceresini TR/EN olarak yayına almadan görür). İngilizce sözlük inmemişse çağıran önce
 * {@link loadLanguage}'i bekler (inmemişse metinler açılış yedeğine / TR'ye düşer — ham anahtar basılmaz).
 */
export function FixedLangProvider({ lang, children }) {
  const parent = useContext(LangCtx)
  const value = useMemo(() => ({
    lang, toggle: parent?.toggle ?? (() => Promise.resolve(false)), pending: null, loadFailures: 0,
  }), [lang, parent?.toggle])
  return <LangCtx.Provider value={value}>{children}</LangCtx.Provider>
}

let warnedNoProvider = false

export function useLanguage() {
  const ctx = useContext(LangCtx)
  if (!ctx || ctx.fallback) {
    // Geliştirmede bir kez uyar: sessizce İngilizce'ye düşmek asıl hatayı gizlerdi.
    if (import.meta.env?.DEV && !warnedNoProvider) {
      warnedNoProvider = true
      console.warn('[i18n] LangProvider bulunamadı — yedek sözlükle devam ediliyor.')
    }
    FALLBACK_CTX.lang = storedLang()
    return FALLBACK_CTX
  }
  return ctx
}

export function useT() {
  const { lang } = useLanguage()
  return useCallback((key, ...args) => {
    let str = translate(lang, key)
    // split/join bilinçli: String.replace string desende bile YALNIZ ilk eşleşmeyi değiştirir
    // ve replacement içindeki $&, $1, $` dizilerini özel yorumlar — monitör adı "$&" içerirse
    // çıktı bozulurdu. split/join her iki sınıfı da kapatır.
    args.forEach((arg, i) => { str = str.split(`{${i}}`).join(String(arg ?? '')) })
    return str
  }, [lang])
}

export function useDateLocale() {
  const { lang } = useLanguage()
  return localeFor(lang)
}
