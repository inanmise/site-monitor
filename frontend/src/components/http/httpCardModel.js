/**
 * HTTP / Web Sitesi izleme KARTI modeli — saf yardımcılar (React yok; kart ve testleri paylaşır). 2026-09-27.
 *
 * <p>Satır alanları (GET /monitoring/http → MonitoringController.enrichHttp): istek ayarları `method` (GET|HEAD|POST),
 * `expected_status` (varsayılan "200-399"; "200", "2xx", "200-399" biçimleri), `follow_redirects`, `verify_ssl`,
 * `check_ssl_errors`, `ssl_expiry_reminders` + `ssl_reminder_days`, `domain_expiry_reminders` + `domain_reminder_days`,
 * `timeout_ms`, `interval_seconds`, `use_proxy` AUTO|ON|OFF + `proxy_effective`/`proxy_bypassed`; son kontrol: `status`
 * up|down|error|unknown (error = istisna — yanıt yok; down = yanıt geldi ama beklenen koda uymadı), `ok`,
 * `http_status`, `response_ms` (hata anında da dolu: hataya kadar geçen süre), `error` (istisna metni), `checked_at`.
 *
 * <p>Gelişmiş istek (2026-10-01): `slow_response_enabled` + `slow_threshold_ms` (açıksa süre kutusu Anahtar Kelime
 * kartıyla aynı kuralla tonlanır — üstü "Yavaş"), `slow_alarm` (açık HTTP_SLOW), `basic_auth_user` / `has_custom_headers`
 * (değerler ASLA gelmez), `json_path` / `json_expected`, son kontrolde `json_assertion_failed` (neden `error`'da).
 *
 * <p>Satırda OLMAYANLAR (kart uydurmaz — API boşluğu): TTFB, yönlendirme sayısı, içerik boyutu, TLS kalan gün,
 * hata tanısı (`error_detail` yalnız geçmiş satırında).
 *
 * <p>Genel HTTP yardımcıları Anahtar Kelime / Ping / Sayfa Hızı modellerinden GELİR (kopya yok): durum sınıfı,
 * süre biçimi, hata sınıflandırması, vekil kipi, URL parçaları, sıklık metni, 24 sa gecikme tabanı.
 */
import { failureReason, httpClass, metaRow, msParts, msText, proxyMode, responseAssessment } from '../keyword/keywordCardModel.js'

export { metaRow, proxyMode }

const num = (v) => {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** Sunucunun varsayılan beklenen durum aralığı (HttpMonitor.expectedStatus). */
export const DEFAULT_EXPECTED = '200-399'

/** Beklenen durum metni — boşsa sunucu varsayılanı. */
export function expectedOf(m) {
  const raw = String(m?.expected_status ?? '').trim()
  return raw || DEFAULT_EXPECTED
}

/** Beklenen durum varsayılandan farklı mı (boşluklar yok sayılır) — kart yalnız o zaman rozet çizer. */
export function isCustomExpected(m) {
  return expectedOf(m).replace(/\s+/g, '') !== DEFAULT_EXPECTED
}

/** HTTP yöntemi — büyük harf, yoksa GET. */
export function methodOf(m) {
  return String(m?.method || 'GET').trim().toUpperCase() || 'GET'
}

/**
 * Son kontrolün HÜKMÜ: `{ kind, tone, cls, code }`. `kind`:
 * `pending` (hiç kontrol yok) · `error` (yanıt yok: zaman aşımı / DNS / TLS / bağlantı) · `ok` (beklenen kod geldi) ·
 * `mismatch` (yanıt geldi ama beklenen koda uymadı). Karar SUNUCUNUNDUR (`status`); kart yalnız adlandırır.
 * Beklenen kod geldiyse sınıf ne olursa olsun ton `ok` (ör. "404 bekleniyor" izlemesinde 404 yeşildir).
 */
export function statusVerdict(m) {
  const status = m?.status
  const code = num(m?.http_status)
  const cls = httpClass(code)
  if (status === 'error') return { kind: 'error', tone: 'bad', cls, code }
  if (status === 'up') return { kind: 'ok', tone: 'ok', cls, code }
  if (status === 'down') return { kind: 'mismatch', tone: 'bad', cls, code }
  return { kind: 'pending', tone: 'neutral', cls: null, code: null }
}

/**
 * Kapalı izlemenin NEDENİ (tek satır). null: sağlıklı ya da bekleyen. Tür — istisna metninden (Anahtar Kelime
 * modeliyle aynı sınıflandırma): `config` · `blocked` (SSRF kalkanı) · `dns` · `timeout` (`detail` = zaman aşımı ms) ·
 * `tls` · `refused` · `error` (ham metnin ilk satırı); yanıt geldiyse: `http4xx` / `http5xx` / `mismatch` (2xx/3xx
 * ama beklenen değil) — `detail` = kod, `expected` = beklenen metin; metinsiz kapalı: `down`.
 */
export function httpFailureReason(m) {
  const status = m?.status
  if (status !== 'down' && status !== 'error') return null
  // JSON doğrulaması düştü (2026-10-01): yanıt geldi ama gövde doğrulanamadı — hata metni TLS/zaman aşımı sözcüğü
  // içerse bile (yol adı "$.timeout" olabilir) sınıflandırmaya sokulmaz; neden önekten arındırılıp gösterilir.
  if (m?.json_assertion_failed) {
    const err = String(m?.error || '')
    return { kind: 'json', detail: err.replace(/^JSON doğrulaması başarısız:\s*/, '').split(/\r?\n/)[0] || err }
  }
  const expected = expectedOf(m)
  const r = failureReason(m)
  if (r && (r.kind === 'http4xx' || r.kind === 'http5xx')) return { ...r, expected }
  if (r) return r
  const code = num(m?.http_status)
  if (status === 'down' && code != null) return { kind: 'mismatch', detail: code, expected }
  return { kind: 'down', detail: null }
}

/**
 * Yanıt süresi kutusu: `{ tone, parts, limit, timedOut, failedAfter }`.
 * - Zaman aşımında `bad` + süre (sunucunun beklediği süre) + "Zaman aşımı".
 * - Yanıt hiç gelmediyse (DNS / TLS / bağlantı / SSRF) değer YOK (`parts` null) — hataya kadar geçen birkaç ms bir
 *   yanıt süresi değildir; `failedAfter` alt satırda bilgi olarak kalır. Ton nötr: kırmızıyı neden satırı taşır.
 * - Aksi hâlde Anahtar Kelime kuralı: yavaşlık eşiği satırda varsa üstü `warn`, altı `ok`; yoksa `neutral` (uydurulmaz).
 */
export function responseView(m, reason = httpFailureReason(m)) {
  if (reason?.kind === 'timeout') return { tone: 'bad', parts: msParts(m?.response_ms), limit: null, timedOut: true, failedAfter: null }
  if (m?.status === 'error') return { tone: 'neutral', parts: null, limit: null, timedOut: false, failedAfter: msText(m?.response_ms) }
  const a = responseAssessment(m, reason)
  return { tone: a.tone, parts: msParts(m?.response_ms), limit: a.limit, timedOut: false, failedAfter: null }
}

/** Düz http:// mi (şifresiz trafik — kartta amber şema + "Şifresiz" çipi). */
export function isPlainHttp(url) {
  return /^http:\/\//i.test(String(url ?? '').trim())
}

/**
 * İstek satırındaki ayar çipleri — yalnız VARSAYILANDAN farklı ya da bilgi taşıyan ayarlar (gürültü yok):
 * `expected` (özel beklenen kod), `redirects` (yönlendirme takip edilmiyor), `strictTls` (sertifika doğrulanıyor),
 * `alerts` (TLS hata alarmı / sertifika-alan adı bitiş hatırlatmaları — tek çip, ayrıntı dokun-gör balonunda),
 * `plain` (düz http). Yöntem rozeti her zaman ayrıca çizilir.
 */
export function requestChips(m) {
  const chips = []
  if (isPlainHttp(m?.url)) chips.push({ key: 'plain' })
  // Beklenen durum kodu HER ZAMAN görünür (2026-09-30, kullanıcı: bazı kartlarda var bazılarında yok — karışıklık);
  // varsayılan aralık nötr tonda, elle değiştirilmişse vurgulu (`custom`).
  chips.push({ key: 'expected', value: expectedOf(m), custom: isCustomExpected(m) })
  if (m?.follow_redirects === false) chips.push({ key: 'redirects' })
  if (m?.verify_ssl) chips.push({ key: 'strictTls' })
  const tlsErrors = !!m?.check_ssl_errors
  const sslExpiry = m?.ssl_expiry_reminders ? String(m?.ssl_reminder_days || '30,14,7') : null
  const domainExpiry = m?.domain_expiry_reminders ? String(m?.domain_reminder_days || '30,14,7') : null
  if (tlsErrors || sslExpiry || domainExpiry) {
    const variant = tlsErrors && (sslExpiry || domainExpiry) ? 'both' : tlsErrors ? 'tls' : 'expiry'
    chips.push({ key: 'alerts', variant, tlsErrors, sslExpiry, domainExpiry })
  }
  // Gelişmiş istek (2026-10-01): yalnız KULLANILIYORSA — eklentisiz kartta hiçbir çip eklenmez.
  if (m?.basic_auth_user || m?.has_custom_headers) chips.push({ key: 'auth' })
  const jsonPath = String(m?.json_path ?? '').trim()
  // Çip metni kısa (dar kartta sığsın), tam yol dokun-gör balonunda (`full`)
  if (jsonPath) chips.push({ key: 'json', value: jsonPath.length > 32 ? `${jsonPath.slice(0, 31)}…` : jsonPath, full: jsonPath })
  return chips
}

/** Hatırlatma günleri "30,14,7" → "30, 14, 7" (okunur). */
export function daysText(days) {
  return String(days ?? '').split(',').map((s) => s.trim()).filter(Boolean).join(', ')
}
