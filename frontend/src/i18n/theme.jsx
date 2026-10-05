import { createContext, useContext, useState, useEffect, useLayoutEffect, useCallback, useMemo } from 'react'
import {
  THEMES, SYSTEM, DEFAULT_POLICY, isKnownTheme, schemeOf, sanitizePolicy, resolveTheme, counterpartTheme, samePolicy,
} from '../theme/themes.js'

/**
 * Tema sağlayıcısı (2026-10-05: sekiz tema — Açık, Koyu + Blueprint, Parchment, Alloy, Obsidian, Slag, Crucible).
 *
 * <p>`<html data-theme="<id>" data-scheme="light|dark">` + `color-scheme`. CSS temaya değil ŞEMAYA bağlıdır (`dark:`
 * yardımcıları, App.css koyu kuralları); ek temalar yalnız jetonlarını `styles/themes.css`'te ezer.
 *
 * <p>Kaynaklar:
 *  • kullanıcının seçimi — localStorage `site-monitor-theme`; 2026-10-05'ten beri kişisel tercihlerle sunucuya da
 *    aynalanır (hooks/userPrefsModel LOCAL_PREF_KEYS): seçim yazımı localStorage üzerinden toplu PUT'a girer, girişte
 *    sunucu kazanır ve App.jsx `reloadChoice()` ile temayı yeniden yüklemeden uygular. Giriş öncesi / çıkış sonrası cihazın
 *    değeri geçerli;
 *  • yönetici politikası — açık temalar + varsayılan (`system` = işletim sistemine göre Açık/Koyu); BrandingProvider
 *    PUBLIC `/api/branding` `themes` alanından `setPolicy` ile verir. İlk boyama için son politika
 *    `site-monitor-theme-policy` anahtarında önbelleklenir (kaldırılmış tema bir an bile görünmesin).
 * Seçilen tema kapatılmışsa / bilinmiyorsa yöneticinin varsayılanı uygulanır; saklı seçim SİLİNMEZ (tema yeniden
 * açılınca geri gelir).
 *
 * <p>Önizleme (Ayarlar → Görünüm → Temalar): `startPreview(id)` temayı YALNIZ bu sekmede, saklamadan uygular;
 * `endPreview()` ya da bir tema seçimi önizlemeyi bitirir.
 */
const STORAGE_KEY = 'site-monitor-theme'
const POLICY_KEY = 'site-monitor-theme-policy'

// Context nesnesi globalThis'e sabitlenir ve varsayılanı çalışır durumdadır —
// gerekçesi i18n/index.jsx'teki LangCtx yorumunda (HMR çift-modülü + hata yüzeyi).
const FALLBACK_THEME_CTX = {
  theme: 'light', scheme: 'light', isDark: false, choice: null, policy: DEFAULT_POLICY, themes: THEMES,
  preview: null, setTheme: () => {}, toggle: () => {}, setPolicy: () => {}, startPreview: () => {}, endPreview: () => {},
  reloadChoice: () => {}, fallback: true,
}

const ThemeCtx = (globalThis.__smThemeCtx ??= createContext(FALLBACK_THEME_CTX))

// localStorage erişimi TRY/CATCH şart: ThemeProvider ErrorBoundary'nin ÜSTÜNDE (main.jsx) —
// burada fırlayan hata sınırca yakalanamaz ve uygulama hata mesajı bile veremeden TAM BEYAZ EKRAN
// olur. Depolama kurumsal politika/gizli mod/kota nedeniyle kapalı olabilir.
function storedTheme() {
  try { return localStorage.getItem(STORAGE_KEY) } catch { return null }
}
function persistTheme(v) {
  try { localStorage.setItem(STORAGE_KEY, v) } catch { /* depolama yok: tema yalnız bu oturumda geçerli */ }
}
function storedPolicy() {
  try {
    const raw = localStorage.getItem(POLICY_KEY)
    return raw ? sanitizePolicy(JSON.parse(raw)) : DEFAULT_POLICY
  } catch { return DEFAULT_POLICY }
}
function persistPolicy(p) {
  try { localStorage.setItem(POLICY_KEY, JSON.stringify(p)) } catch { /* önbellek yok: politika açılışta yeniden gelir */ }
}
function osPrefersDark() {
  try { return !!window.matchMedia('(prefers-color-scheme: dark)').matches } catch { return false }
}

/** Belgeye uygular — tarayıcıda boyamadan ÖNCE (yanıp sönme yok); SSR/test ortamında da güvenli. */
function applyToDocument(theme) {
  const root = typeof document !== 'undefined' ? document.documentElement : null
  if (!root) return
  const scheme = schemeOf(theme)
  root.setAttribute('data-theme', theme)
  root.setAttribute('data-scheme', scheme)
  root.style.colorScheme = scheme
}

const useIsoLayoutEffect = typeof window !== 'undefined' ? useLayoutEffect : useEffect

export function ThemeProvider({ children }) {
  const [choice, setChoice] = useState(() => storedTheme())
  const [policy, setPolicyState] = useState(() => storedPolicy())
  const [prefersDark, setPrefersDark] = useState(() => osPrefersDark())
  const [preview, setPreview] = useState(null)

  // İşletim sistemi açık/koyu tercihi canlı izlenir — varsayılan `system` iken tema kendiliğinden döner.
  useEffect(() => {
    let mql
    try { mql = window.matchMedia('(prefers-color-scheme: dark)') } catch { return undefined }
    if (!mql) return undefined
    const onChange = () => setPrefersDark(!!mql.matches)
    try { mql.addEventListener?.('change', onChange) } catch { /* eski tarayıcı */ }
    return () => { try { mql.removeEventListener?.('change', onChange) } catch { /* yok */ } }
  }, [])

  const resolved = resolveTheme(choice, policy, prefersDark)
  const theme = preview && isKnownTheme(preview) ? preview : resolved
  const scheme = schemeOf(theme)

  useIsoLayoutEffect(() => { applyToDocument(theme) }, [theme])

  const setTheme = useCallback((id) => {
    if (!isKnownTheme(id)) return
    setPreview(null)
    setChoice(id)
    persistTheme(id)
  }, [])

  /** Hızlı açık↔koyu (Komut Paleti): etkin temanın şema karşılığı, yalnız açık temalar içinde. */
  const toggle = useCallback(() => {
    const next = counterpartTheme(theme, policy.enabled)
    if (next) setTheme(next)
  }, [theme, policy, setTheme])

  const setPolicy = useCallback((raw) => {
    if (!raw || typeof raw !== 'object') return
    const next = sanitizePolicy(raw)
    setPolicyState((prev) => (samePolicy(prev, next) ? prev : next))
    persistPolicy(next)
  }, [])

  /**
   * Saklı seçimi yeniden okur (2026-10-05, tema kişisel tercihlerle eşitlenir): girişte sunucu belgesi localStorage'a
   * yazıldığında App.jsx bunu çağırır → tema yeniden yüklemeden değişir. Yalnız OKUR (yazım yok → PUT döngüsü yok);
   * değer aynıysa durum değişmez.
   */
  const reloadChoice = useCallback(() => {
    const v = storedTheme()
    setChoice((prev) => (prev === v ? prev : v))
  }, [])

  // Başka sekmede seçilen tema bu sekmeye de geçer (tarayıcının `storage` olayı yalnız DİĞER sekmelerde tetiklenir).
  useEffect(() => {
    const onStorage = (e) => { if (e.key === STORAGE_KEY || e.key === null) reloadChoice() }
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [reloadChoice])

  const startPreview = useCallback((id) => { if (isKnownTheme(id)) setPreview(id) }, [])
  const endPreview = useCallback(() => setPreview(null), [])

  const value = useMemo(() => ({
    theme, scheme, isDark: scheme === 'dark',
    choice: isKnownTheme(choice) ? choice : null,
    policy,
    themes: THEMES.filter((t) => policy.enabled.includes(t.id)),
    preview: preview && isKnownTheme(preview) ? preview : null,
    setTheme, toggle, setPolicy, startPreview, endPreview, reloadChoice,
  }), [theme, scheme, choice, policy, preview, setTheme, toggle, setPolicy, startPreview, endPreview, reloadChoice])

  return <ThemeCtx.Provider value={value}>{children}</ThemeCtx.Provider>
}

export function useTheme() {
  return useContext(ThemeCtx) ?? FALLBACK_THEME_CTX
}

export { SYSTEM }
