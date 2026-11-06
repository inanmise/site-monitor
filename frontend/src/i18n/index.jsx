import { createContext, useContext, useState, useCallback, useEffect, useMemo, useRef } from 'react'

import { LANG_STORAGE_KEY, localeFor, setDateLocale, setSessionLang } from './dateLocale.js'
import { reloadForStaleBundle } from '../utils/chunkReloadGuard.js'

// Anahtar ve dil→yerel eslemesi dateLocale.js'te TEK kaynakta: `api/client.js`'teki duz
// formatlayicilar da oradan okuyor, iki yerde yazilsa sessizce ayrisirlardi.
const STORAGE_KEY = LANG_STORAGE_KEY

// ── Dictionaries ──────────────────────────────────────────────────────────────

/**
 * Sözlükler AYRI dosyalarda ve İKİSİ DE ayrı (lazy) parça:
 *   • `en.js` — 2026-10-02'den beri (performans önerisi 22 — "açılış paketini küçült").
 *   • `tr.js` — 2026-11-06'dan beri (performans): eskiden STATİK import'la açılış paketindeydi; varsayılan dil İngilizce
 *     olduğu hâlde her kullanıcı ~570 KB gzip'lik Türkçe metni indirip ayrıştırıyordu (açılış parçasının ~%60'ı).
 * Açılış parçası artık yalnız ETKİN dilin sözlüğünü indirir ({@link loadLanguage}; main.jsx isteği ilk çizimden önce başlatır).
 *
 * Testler sözlüklere doğrudan `./tr.js` / `./en.js` üzerinden erişir. Bu modül ikisini de dışa VERMEZ — buradan statik
 * bir `TR` / `EN` dışa aktarımı sözlüğü yeniden açılış paketine sokardı (`lazyChunks.test.js` kaynakta kilitler).
 */

/**
 * Yüklenmiş sözlükler — globalThis'e SABİT (aşağıdaki LangCtx ile aynı gerekçe: Vite HMR bu modülü iki
 * örnek olarak yükleyebilir, testler `vi.resetModules()` ile yeniden değerlendirir). Bir örneğin indirdiği
 * sözlüğü öbürü yeniden indirmez. Anahtarlar: 'tr', 'en'. Testlerin kurulumu (test/setup.js) ikisini de önceden yazar.
 */
const DICTS = (globalThis.__smI18nDicts ??= {})

/**
 * Açılış metinleri — etkin dilin sözlüğü henüz inmemişken (açılış ekranı `LanguageBootSplash`, yavaş indirmede
 * "Beklenenden uzun sürüyor" uyarısı dahil) ya da hiç inmediğinde
 * (sözlüksüz hata ekranı `LanguageBootFailure`) gereken birkaç metin; ayrıca provider'sız çizilen çökme ekranının
 * (ErrorBoundary → ErrorFallback) kendi metinleri, ki sözlük hiç yokken bile ham anahtar basılmasın. Değerler
 * `en.js` / `tr.js`'tekilerin BİREBİR kopyası (`i18n-lazy.test.jsx` eşitliği iki dil için de kilitler).
 */
export const EN_BOOT = Object.freeze({
  'app.loading': 'Loading...',
  'brand.logo.alt.muted': 'SiteMonitor — awaiting data',
  'lang.bootFailed.title': 'The interface language files could not be loaded',
  'lang.bootFailed.body': 'Neither the Turkish nor the English language file could be downloaded. The connection may have dropped, or a new version is being rolled out right now. Wait a few seconds, then reload the page.',
  'lang.bootReload': 'Reload',
  'lang.bootSlow.title': 'The language file is taking longer than expected',
  'lang.bootSlow.body': 'The connection seems slow; the download is still running and the app opens by itself when it finishes. If you would rather not wait, reload the page.',
  'err.title': 'Something went wrong',
  'err.detail': 'This screen stopped because of an unexpected error. Reload the page (unsaved changes may be lost); if it keeps happening, use "Report a Problem" to tell the system administrators.',
  'err.reload': 'Reload',
  'err.details': 'Technical detail (error message)',
  'err.reported': 'The error was automatically reported to the system administrator',
  'err.reportBtn': 'Report a Problem',
  'err.reportFailed': 'We could not send this error report on your behalf — please use "Report a Problem".',
  'err.copyError': 'Copy error text',
  'err.copyRef': 'Copy reference number',
  'err.copied': 'Copied',
})

/** {@link EN_BOOT}'un Türkçe eşi — `tr.js` değerlerinin BİREBİR kopyası. */
export const TR_BOOT = Object.freeze({
  'app.loading': 'Yükleniyor...',
  'brand.logo.alt.muted': 'SiteMonitor — veri bekleniyor',
  'lang.bootFailed.title': 'Arayüz dil dosyaları yüklenemedi',
  'lang.bootFailed.body': 'Türkçe ve İngilizce dil dosyalarının ikisi de indirilemedi — bağlantı kesilmiş ya da yeni sürüm şu anda dağıtılıyor olabilir. Birkaç saniye bekleyip sayfayı yeniden yükleyin.',
  'lang.bootReload': 'Yeniden yükle',
  'lang.bootSlow.title': 'Dil dosyası beklenenden uzun sürüyor',
  'lang.bootSlow.body': 'Bağlantı yavaş görünüyor; indirme sürüyor ve bitince uygulama kendiliğinden açılır. Beklemek istemezseniz sayfayı yeniden yükleyin.',
  'err.title': 'Bir şey ters gitti',
  'err.detail': 'Bu ekran beklenmedik bir hata yüzünden durdu. Sayfayı yenileyin (kaydedilmemiş değişiklikler kaybolabilir); sorun sürerse "Sorun Bildir" ile sistem yöneticilerine iletin.',
  'err.reload': 'Yenile',
  'err.details': 'Teknik detay (hata mesajı)',
  'err.reported': 'Hata, sistem yöneticisine otomatik olarak bildirildi',
  'err.reportBtn': 'Sorun Bildir',
  'err.reportFailed': 'Bu hata sistem yöneticisine kendiliğinden iletilemedi — lütfen "Sorun Bildir" ile gönderin.',
  'err.copyError': 'Hata metnini kopyala',
  'err.copyRef': 'Referans numarasını kopyala',
  'err.copied': 'Kopyalandı',
})

/**
 * Saklı dil kodu → sözlüğü: 'en' İngilizce; 'tr' ve TANINMAYAN her kod (bozuk depolama) Türkçe — bugünkü anlam korunur
 * (eskiden tanınmayan kod statik Türkçe sözlükle çalışıyordu; şimdi Türkçe sözlük yüklenir). `dateLocale.localeFor`
 * ve sunucunun `Msg.t`'si de 'en' dışındaki her kodu Türkçe sayar — üçü aynı.
 */
export function dictLang(lang) {
  return lang === 'en' ? 'en' : 'tr'
}

/** Açılışta etkin dilin sözlüğü inmezse denenen öbür dil (Türkçe kullanıcıda İngilizce, İngilizce'de Türkçe). */
const otherLang = (lang) => (dictLang(lang) === 'en' ? 'tr' : 'en')

const needsLoad = (lang) => !DICTS[dictLang(lang)]

/** Literal dinamik import'lar ŞART: Vite her sözlüğü kendi parçasına bu çağrılardan ayırır (`lazyChunks.test.js`). */
const IMPORTERS = {
  tr: () => import('./tr.js').then((m) => m.TR),
  en: () => import('./en.js').then((m) => m.EN),
}

/** Dil başına sürmekte olan parça indirmesi — süresiz (aynı anda gelen çağrılar TEK isteği paylaşır). */
const inflight = {}

/** Bir sözlük indiğinde haber verilecekler (açılış hata ekranının kendiliğinden toparlanması). */
const dictListeners = new Set()

/**
 * Sözlük parçasını indirir — SÜRESİZ, tek paylaşılan istek. Zaman aşımı bu indirmeyi KESMEZ: yavaş hatta süren indirme
 * sonradan inerse sözlük önbelleğe yazılır ve dinleyicilere bildirilir. Gerçek hata (parça 404, ağ hatası, sözlüksüz
 * modül) reddedilir ve önbelleğe ALINMAZ — sonraki çağrı yeniden dener.
 */
function importDictionary(key) {
  if (DICTS[key]) return Promise.resolve(DICTS[key])
  if (!inflight[key]) {
    const attempt = IMPORTERS[key]().then((dict) => {
      if (!dict || typeof dict !== 'object') throw new Error('language module without dictionary')
      DICTS[key] = dict
      for (const cb of [...dictListeners]) { try { cb(key) } catch { /* dinleyici hatası yüklemeyi bozmaz */ } }
      return dict
    })
    inflight[key] = attempt
    const settle = () => { if (inflight[key] === attempt) inflight[key] = null }
    attempt.then(settle, settle)
  }
  return inflight[key]
}

/** Zaman aşımı hatası: GERÇEK bir yükleme hatası değil — indirme sürüyor olabilir ({@link isLanguageLoadTimeout}). */
const TIMEOUT_CODE = 'LANG_LOAD_TIMEOUT'

/** Hata bir zaman aşımı mı (indirme sürüyor) yoksa gerçek bir yükleme hatası mı (parça yok / ağ / sözlüksüz modül)? */
export function isLanguageLoadTimeout(err) {
  return err?.code === TIMEOUT_CODE
}

/**
 * Bekleme eşiği (2026-10-09, sonsuz bekleme denetimi; 2026-11-06 inceleme ORTA-1 ile anlamı değişti).
 * <ul>
 *   <li>Çalışırken dil geçişinde ({@link loadLanguage}): bu sürede inmezse geçiş BAŞARISIZ sayılır (bildirim, dil
 *       değişmez); süren indirme kesilmez — sonradan inen sözlük önbelleğe yazılır, sonraki geçiş anında olur.</li>
 *   <li>Açılışta ({@link LangProvider}): zaman aşımı başarısızlık DEĞİLDİR — yenileme yok, öbür dile geçiş yok, yeni
 *       indirme yok. Açılış ekranı kalır ve "Beklenenden uzun sürüyor" + "Yeniden yükle" gösterir; AYNI indirme
 *       beklenmeye devam eder, inince uygulama saklı dilde açılır. Yavaş hat (yavaş 3G, zayıf VPN — ~540 KB gzip'lik
 *       sözlük) cezalandırılmaz, asılı bir istek de çıkışsız kalmaz (düğme).</li>
 * </ul>
 */
export const LANG_LOAD_TIMEOUT_MS = 10_000

/**
 * Dil sözlüğünü yükler (önbellekli; aynı anda gelen çağrılar TEK isteği paylaşır). Tanınmayan kod Türkçe sözlüğü
 * yükler ({@link dictLang}). Gerçek hata önbelleğe ALINMAZ — sonraki çağrı yeniden dener; sözlük nesnesi taşımayan bir
 * modül de hata sayılır. {@link LANG_LOAD_TIMEOUT_MS} içinde bitmezse bu çağrı zaman aşımıyla reddedilir
 * ({@link isLanguageLoadTimeout}) ama indirme SÜRER ve inince önbelleğe yazılır.
 * @returns {Promise<object>} sözlük nesnesi
 */
export function loadLanguage(lang) {
  const key = dictLang(lang)
  if (DICTS[key]) return Promise.resolve(DICTS[key])
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      const err = new Error('language load timeout')
      err.code = TIMEOUT_CODE
      reject(err)
    }, LANG_LOAD_TIMEOUT_MS)
    importDictionary(key).then(
      (dict) => { clearTimeout(timer); resolve(dict) },
      (err) => { clearTimeout(timer); reject(err) },
    )
  })
}

/** Dilin sözlüğü şu an eşzamanlı kullanılabilir mi (yüklendiyse). */
export function isLanguageLoaded(lang) {
  return !needsLoad(lang)
}

/**
 * Metin çözümü. Etkin dilin sözlüğü varsa yalnız ondan (eksik anahtar = anahtarın kendisi — i18n-used-keys kapısı
 * kodda olmayanı yakalar). Sözlük yoksa (açılış ekranı, sözlüksüz hata ekranı, provider'sız yol): önce etkin dilin
 * açılış metni, sonra YÜKLÜYSE öbür dilin sözlüğü, en son anahtar. Provider'lı ağaç etkin dilin sözlüğü hazır olmadan
 * çizilmez (LangProvider) — son iki adım yalnız provider'sız yolda (testler, olağan dışı yerleşim) çalışır; orada da
 * çökme ekranının metinleri açılış metinlerinde olduğundan o yüzey ham anahtar basmaz.
 */
function translate(lang, key) {
  const own = dictLang(lang)
  const dict = DICTS[own]
  if (dict) return dict[key] ?? key
  const boot = own === 'en' ? EN_BOOT : TR_BOOT
  if (boot[key] != null) return boot[key]
  return DICTS[own === 'en' ? 'tr' : 'en']?.[key] ?? key
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
 * Saklı dilin (Türkçe ya da İngilizce — artık İKİSİ de ayrı parça) sözlüğünü React'in ilk çiziminden ÖNCE istemeye
 * başlar (main.jsx çağırır): parça isteği ilk render'la paralel yürür. Hata burada yutulur; LangProvider aynı yüklemeyi
 * bekleyip hatayı kendisi ele alır (açılış hata zinciri).
 */
export function preloadStoredLanguage() {
  const lang = storedLang()
  if (needsLoad(lang)) importDictionary(dictLang(lang)).catch(() => {})
}

/** Bir sözlük indiğinde çağrılır; aboneliği bırakan işlev döner. */
function onDictionaryLoaded(cb) {
  dictListeners.add(cb)
  return () => { dictListeners.delete(cb) }
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
 * <p><b>Etkin dil yalnız sözlüğü HAZIRSA değişir</b> (2026-10-02, öneri 22; 2026-11-06'dan beri iki dil için de):
 * <ul>
 *   <li>Açılışta saklı dilin sözlüğü henüz inmemişken: ağaç yerine {@code fallback} (açılış ekranı, etkin dilin açılış
 *       metinleriyle) çizilir; sözlük inince uygulama doğrudan o dilde açılır — ham anahtar ya da dil kayması yok.</li>
 *   <li>Çalışırken TR↔EN: önce sözlük iner ({@code pending: <hedef>} — dil denetimi meşgul göstergesi çizer), sonra
 *       dil tek adımda değişir ve tercih kaydedilir. Sözlük önbellekteyse geçiş ANINDA olur. İndirme başarısızsa (zaman
 *       aşımı dahil) arayüz mevcut dilde kalır, {@code loadFailures} artar (LanguageLoadNotice bildirim gösterir); sayfa
 *       YENİLENMEZ (kaydedilmemiş iş kaybolmasın). Zaman aşımında süren indirme kesilmez: inince sonraki geçiş anında olur.</li>
 *   <li><b>Açılışta yavaş indirme</b> (2026-11-06 inceleme ORTA-1): {@link LANG_LOAD_TIMEOUT_MS} dolunca indirme
 *       BAŞARISIZ SAYILMAZ — yenileme, öbür dile geçiş, yeni indirme yok. Açılış ekranı kalır, {@code bootSlow} ile
 *       "Beklenenden uzun sürüyor" + "Yeniden yükle" gösterir; AYNI indirme beklenir, inince uygulama o dilde açılır
 *       (bildirim yok).</li>
 *   <li><b>Açılış hata zinciri</b> (yalnız GERÇEK hata: parça 404, ağ hatası, sözlüksüz modül):
 *     <ol>
 *       <li>Sayfa BİR KEZ yenilenir (2026-10-23, çok pod R2b · MP-47): yuvarlanan dağıtımda bu paketin sözlük parçası
 *           öbür podda olabilir — yenileme taze index'i ve onun parçalarını getirir; açılış ekranı yenilenene kadar kalır.
 *           Hak açılış koruması ve ErrorBoundary ile ORTAK (utils/chunkReloadGuard.js).</li>
 *       <li>Hak yoksa ÖBÜR dilin sözlüğü denenir (İngilizce kullanıcıda Türkçe, Türkçe kullanıcıda İngilizce; o da yavaşsa
 *           aynı yavaş indirme kuralı): inerse uygulama o dilde açılır, {@code loadFailures} artar; saklı tercih DEĞİŞMEZ
 *           (geçici hata kalıcı tercihi bozmasın); sunucu iletileri arayüzle aynı dilde kalsın diye X-Lang bu oturum için
 *           o dile zorlanır (dateLocale.setSessionLang). Çalışan oturumun dili sonradan kendiliğinden DEĞİŞMEZ.</li>
 *       <li>İkisi de gerçek hatayla inmezse {@code failure} (sözlüksüz, sabit metinli hata ekranı + "Yeniden yükle")
 *           çizilir. Hata ekranındayken bir sözlük inerse (ör. başka bir yerden tetiklenen indirme) ekran kendiliğinden
 *           toparlanır: saklı dil öncelikli, yoksa öbür dil (yedek kuralıyla).</li>
 *     </ol></li>
 * </ul>
 */
export function LangProvider({ children, fallback = null, failure = null }) {
  // storedLang() try/catch'li. LangProvider ErrorBoundary'nin ÜSTÜNDE: fırlarsa beyaz ekran (bkz. theme.jsx aynı düzeltme).
  // lang: etkin dil (açılışta saklı dil; yalnız öbür dile düşülünce değişir) · bootTarget: açılışta şu an indirilen dil
  // (saklı dil ya da gerçek hatadan sonra öbür dil; hazırken null) · slow: bu indirme bekleme eşiğini geçti.
  const [state, setState] = useState(() => {
    const lang = storedLang()
    const ready = !needsLoad(lang)
    return { lang, ready, failed: false, slow: false, bootTarget: ready ? null : lang, pending: null, loadFailures: 0 }
  })
  const { lang, ready, failed, slow, bootTarget, pending, loadFailures } = state
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

  // Açılış: bootTarget'ın sözlüğü SÜRESİZ beklenir (bekleme eşiğinde yalnız `slow`); gerçek hatada zincir ilerler.
  useEffect(() => {
    if (ready || failed || !bootTarget) return undefined
    let cancelled = false
    const fellBack = bootTarget !== lang                            // öbür dil deneniyor (saklı dil gerçek hatayla inmedi)
    const slowTimer = setTimeout(() => {
      if (!cancelled) setState((s) => (s.slow ? s : { ...s, slow: true }))
    }, LANG_LOAD_TIMEOUT_MS)
    importDictionary(dictLang(bootTarget)).then(
      () => {
        clearTimeout(slowTimer)
        if (cancelled) return
        if (!fellBack) {
          setState((s) => ({ ...s, ready: true, slow: false, bootTarget: null }))
          return
        }
        langRef.current = bootTarget
        setSessionLang(bootTarget)
        setState((s) => ({ ...s, lang: bootTarget, ready: true, slow: false, bootTarget: null, loadFailures: s.loadFailures + 1 }))
      },
      () => {
        clearTimeout(slowTimer)
        if (cancelled) return
        if (reloadForStaleBundle()) return                          // bir kez yenile; açılış ekranı yerinde kalır
        setState((s) => (fellBack
          ? { ...s, failed: true, slow: false, bootTarget: null }
          : { ...s, slow: false, bootTarget: otherLang(s.lang) }))
      },
    )
    return () => { cancelled = true; clearTimeout(slowTimer) }
  }, [ready, failed, bootTarget, lang])

  // Hata ekranındayken bir sözlük inerse kendiliğinden toparlan (saklı dil öncelikli; öbür dil yedek kuralıyla).
  useEffect(() => {
    if (!failed) return undefined
    const recover = () => {
      if (DICTS[dictLang(lang)]) {
        setState((s) => (s.failed ? { ...s, failed: false, ready: true } : s))
        return
      }
      const other = otherLang(lang)
      if (!DICTS[dictLang(other)]) return
      langRef.current = other
      setSessionLang(other)
      setState((s) => (s.failed ? { ...s, lang: other, failed: false, ready: true, loadFailures: s.loadFailures + 1 } : s))
    }
    recover()
    return onDictionaryLoaded(recover)
  }, [failed, lang])

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

  // bootSlow: yalnız açılış ekranı okur (LanguageBootSplash — "Beklenenden uzun sürüyor"); hazır olunca hep false.
  const value = useMemo(() => ({ lang, toggle, pending, loadFailures, bootSlow: slow }), [lang, toggle, pending, loadFailures, slow])

  return <LangCtx.Provider value={value}>{ready ? children : (failed ? failure : fallback)}</LangCtx.Provider>
}

/**
 * Alt ağacı SABİT bir dilde çizer — arayüz dilini DEĞİŞTİRMEZ (2026-10-02, Sistem Bakımı önizlemesi: yönetici giriş kartını,
 * şeritleri ve geri sayım penceresini TR/EN olarak yayına almadan görür). Dilin sözlüğü inmemişse çağıran önce
 * {@link loadLanguage}'i bekler (iki dil için de; inmemişse metinler açılış metnine / yüklü öbür dile düşer — ham anahtar
 * basılmaz).
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
