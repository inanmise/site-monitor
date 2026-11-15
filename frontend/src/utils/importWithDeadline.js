import { ApiError, chunkTimeoutMessage, rememberErrorInfo } from './errorMessages.js'
import { LANG_STORAGE_KEY, sessionLangOverride } from '../i18n/dateLocale.js'

/**
 * Süre sınırlı dinamik `import()` (2026-11-15, prod kapısı incelemesi; kullanıcı kuralı "zaman aşımı olmayan istek / bekleme
 * yok"). Tıklamayla yüklenen bir dışa aktarım / Excel / PDF modülünün parça isteği asılı kalırsa (yarı açık bağlantı, ağ
 * geçidi) `await import(…)` sonsuza dek bekler ve düğme "hazırlanıyor"da kalırdı. Burada parça `ms` içinde inmezse söz
 * çevrilmiş, ÖZGÜL bir `ApiError` ile (kod `CHUNK_TIMEOUT`, `kind: 'timeout'`, durum 0) reddedilir; zamanlayıcı her
 * durumda (başarı, ret, süre dolması) söker — geride zamanlayıcı kalmaz. Geç inen modül yok sayılır (tarayıcı onu yine
 * önbelleğe alır; bir sonraki tıklama hızlı açılır).
 *
 * <p>`loader` bir FONKSİYONDUR — `importWithDeadline(() => import('./x.js'))`: `import('./x.js')` sözdizimi kaynakta kalır,
 * böylece Vite parçayı yine ayırır ve tembel parça kapıları (`lazyChunks.test.js`, `manualCertExtractLazy.test.js`)
 * yüklemeyi görür. React.lazy bileşen yüklemeleri kapsam dışıdır (hata sınırı + yeniden yükleme yolu kendi).
 *
 * <p>Kaynak kapısı: `clientTimeoutGate.test.js` bileşen / yardımcı dosyalardaki çıplak `await import(` çağrısını reddeder.
 */
export const IMPORT_DEADLINE_MS = 60_000

/** Hata kodu (kalıcı) — çağıran özgül metni tosta yazar; `errorInfo` "Teknik ayrıntı"da görünür. */
export const CHUNK_TIMEOUT = 'CHUNK_TIMEOUT'

function uiLang() {
  const forced = sessionLangOverride()
  if (forced) return forced
  try { return localStorage.getItem(LANG_STORAGE_KEY) || 'en' } catch { return 'en' }
}

/** Parça süre sınırında inmediğinde reddedilen hata (çevrilmiş, `ApiError`). */
export function importTimeoutError(ms = IMPORT_DEADLINE_MS) {
  const seconds = Math.max(1, Math.round(ms / 1000))
  const err = new ApiError(chunkTimeoutMessage({ lang: uiLang(), seconds }), { status: 0, code: CHUNK_TIMEOUT, kind: 'timeout' })
  rememberErrorInfo(err.message, { status: 0, code: CHUNK_TIMEOUT })
  return err
}

/** Süre sınırı yüzünden mi reddedildi? */
export function isImportTimeout(e) {
  return e?.code === CHUNK_TIMEOUT
}

/**
 * @param {() => Promise<any>} loader  `() => import('./modül.js')`
 * @param {number} [ms]  üst süre (≤ 0 ya da sayı değil → {@link IMPORT_DEADLINE_MS})
 * @returns {Promise<any>} modül; reddi: yükleyicinin kendi hatası (bayat paket, 404) ya da {@link importTimeoutError}
 */
export function importWithDeadline(loader, ms = IMPORT_DEADLINE_MS) {
  const limit = Number.isFinite(ms) && ms > 0 ? ms : IMPORT_DEADLINE_MS
  let pending
  try {
    pending = Promise.resolve(loader())
  } catch (e) {
    return Promise.reject(e)
  }
  let timer
  const deadline = new Promise((_, reject) => {
    timer = setTimeout(() => reject(importTimeoutError(limit)), limit)
  })
  // Promise.race yarışan sözlere işleyici bağlar: süre dolduktan sonra gelen ret "işlenmemiş" sayılmaz
  return Promise.race([pending, deadline]).finally(() => clearTimeout(timer))
}
