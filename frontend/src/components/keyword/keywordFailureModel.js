/**
 * Keyword kontrol geçmişi HATA TEŞHİSİ modeli — saf fonksiyonlar, React yok (2026-10-04, kullanıcı isteği: "kontrol
 * geçmişinde hata olduğunda herhangi bir detay bulunmuyor … neden hata aldı net göremiyorum").
 *
 * <p>Sunucu her başarısız kontrolde `failure_reason` (kod) + `failure_detail` (TR ayrıntı) + yanıt meta verisi + `hints`
 * (ipucu kodları) + `excerpt` (görünür metinden maskeli alıntı) yazar. Arayüz metni KODDAN kurar (kullanıcının dilinde):
 * `kwfail.<KOD>.short|why|effect|fix` ve `kwhint.<KOD>.title|cause|effect|fix` — adlı yer tutucular ({keyword}, {count},
 * {expected}, {status}, {ms}, {size}, {host}, {final_host}, {redirects}) satırdan doldurulur. Sunucunun TR ayrıntısı
 * "teknik ayrıntı" olarak ayrıca gösterilir.
 *
 * <p>Eski satırlar (bu tarihten önce yazılmış, neden kolonu NULL) zarifçe çizilir: hata metninden ya da durum kodundan
 * EN YAKIN neden türetilir ve `legacy: true` döner (arayüz "ayrıntı kaydedilmemiş — tanılamayı çalıştırın" der).
 */
import { formatBytes, interpolate } from '../http/diagnose/httpDiagnoseModel.js'
import { KW_FAILURE_CODES, KW_HINT_CODES } from './diagnose/keywordDiagCodes.js'
import { failureReason as legacyErrorReason, ruleOf } from './keywordCardModel.js'

const FAILURE_SET = new Set(KW_FAILURE_CODES)
const HINT_SET = new Set(KW_HINT_CODES)

/** Ayar/politika türü nedenler — "hedef çöktü" değil "kurulumu düzelt" (uyarı tonu). */
const WARN_CODES = new Set(['CONFIG_ERROR', 'SSRF_BLOCKED', 'BODY_TRUNCATED'])
/** İstek hiç tamamlanmadı (ağ / TLS / zaman aşımı) — "Hata" durumu; diğerleri sayfa geldi ama kural sağlanmadı. */
export const REQUEST_CODES = new Set(['TIMEOUT_CONNECT', 'TIMEOUT_READ', 'DNS', 'TLS_HANDSHAKE', 'TLS_CERT', 'CONNECTION_REFUSED',
  'CONNECTION_RESET', 'HOST_UNREACHABLE', 'PROXY', 'SSRF_BLOCKED', 'CONFIG_ERROR', 'REDIRECT_LIMIT', 'PROTOCOL_ERROR', 'UNKNOWN'])

/** Kart regex sınıflandırması (eski satır) → neden kodu. */
const LEGACY_KIND = {
  config: 'CONFIG_ERROR', blocked: 'SSRF_BLOCKED', dns: 'DNS', timeout: 'TIMEOUT_READ', tls: 'TLS_HANDSHAKE',
  refused: 'CONNECTION_REFUSED', error: 'UNKNOWN',
}

const num = (v) => {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** Satırın ipucu kodları (bilinmeyen kod süzülür — sözlükte olmayan anahtar ekrana ham düşmesin). */
export function hintsOf(row) {
  return Array.isArray(row?.hints) ? row.hints.filter((h) => HINT_SET.has(h)) : []
}

/**
 * Başarısız satırın nedeni: `{ code, legacy }` ya da null (başarılı satır).
 * Sunucu kodu varsa o; yoksa (eski satır) hata metninden / durum kodundan / adetten EN YAKIN kod türetilir.
 */
export function reasonOf(row, monitor) {
  if (!row || row.ok === true) return null
  const code = row.failure_reason
  if (code && FAILURE_SET.has(code)) return { code, legacy: false }
  const err = String(row.error || '').trim()
  if (err) {
    const legacy = legacyErrorReason({ status: 'error', error: err })
    return { code: LEGACY_KIND[legacy?.kind] || 'UNKNOWN', legacy: true }
  }
  const status = num(row.http_status)
  const count = num(row.occurrences) ?? (row.found ? 1 : 0)
  if (count === 0) {
    if (status != null && status >= 400) return { code: 'HTTP_STATUS', legacy: true }
    return { code: 'KEYWORD_NOT_FOUND', legacy: true }
  }
  return { code: ruleOf(monitor).kind === 'absent' ? 'KEYWORD_FOUND_FORBIDDEN' : 'KEYWORD_COUNT_MISMATCH', legacy: true }
}

/** Neden tonu (ToneBadge dili). */
export function reasonTone(code) {
  return WARN_CODES.has(code) ? 'warning' : 'danger'
}

/** Kuralın okunur hâli: "en az 1 kez" (mevcut `keyword.expect.*` anahtarları). */
export function expectPhrase(monitor, t) {
  const { op, n } = ruleOf(monitor)
  return t(`keyword.expect.${op}`, n)
}

function hostOf(url) {
  if (!url) return null
  try { return new URL(String(url).replace('{timestamp}', '0')).host } catch { return null }
}

/** Metinlerin adlı parametreleri — satır + izleme. Boş değer "—" olur (interpolate). */
export function failureParams(row, monitor, t) {
  const r = row || {}
  const m = monitor || {}
  return {
    keyword: m.keyword ?? '',
    count: num(r.occurrences) ?? 0,
    expected: expectPhrase(m, t),
    status: num(r.http_status),
    ms: num(m.timeout_ms) ?? num(r.response_ms),
    size: num(r.body_bytes) != null ? formatBytes(num(r.body_bytes)) : null,
    host: hostOf(m.url),
    final_host: hostOf(r.final_url),
    redirects: num(r.redirect_count) ?? 0,
    route: r.via === 'proxy' || r.via === 'direct' ? t(`httpdx.route.${r.via}`) : null,
  }
}

/** Başarısız satırın kısa etiketi + neden / etkisi / ne yapmalı metinleri (kullanıcının dilinde). */
export function failureTexts(row, monitor, t) {
  const reason = reasonOf(row, monitor)
  if (!reason) return null
  const p = failureParams(row, monitor, t)
  const k = `kwfail.${reason.code}`
  return {
    code: reason.code,
    legacy: reason.legacy,
    tone: reasonTone(reason.code),
    short: interpolate(t(`${k}.short`), p),
    why: interpolate(t(`${k}.why`), p),
    effect: interpolate(t(`${k}.effect`), p),
    fix: interpolate(t(`${k}.fix`), p),
  }
}

/** İpucu metinleri (başlık + neden → etkisi → ne yapmalı). */
export function hintTexts(code, row, monitor, t) {
  const p = failureParams(row, monitor, t)
  const k = `kwhint.${code}`
  return {
    code,
    title: interpolate(t(`${k}.title`), p),
    cause: interpolate(t(`${k}.cause`), p),
    effect: interpolate(t(`${k}.effect`), p),
    fix: interpolate(t(`${k}.fix`), p),
  }
}

/**
 * Ayrıntı satırları (Kv listesi) — yalnız KAYITTA olan değerler; eski satırda meta yoksa liste kısalır (uydurma yok).
 * @returns {Array<{key, value, tone?}>}
 */
export function detailRows(row, monitor, t) {
  const r = row || {}
  const rows = []
  const status = num(r.http_status)
  rows.push({ key: 'status', value: status != null ? `HTTP ${status}` : t('kwhist.noResponse'), tone: status == null || status >= 400 ? 'bad' : null })
  const count = num(r.occurrences)
  if (count != null || r.found != null) {
    rows.push({ key: 'count', value: t('kwhist.countVsRule', count ?? (r.found ? 1 : 0), expectPhrase(monitor, t)) })
  }
  if (r.final_url) {
    const red = num(r.redirect_count) ?? 0
    rows.push({ key: 'finalUrl', value: r.final_url, mono: true, sub: red > 0 ? t('kwhist.redirects', red) : null })
  }
  if (r.content_type) rows.push({ key: 'contentType', value: r.content_type, mono: true })
  if (num(r.body_bytes) != null) {
    rows.push({ key: 'size', value: formatBytes(num(r.body_bytes)), sub: r.body_truncated ? t('kwhist.truncated') : null,
      tone: r.body_truncated ? 'warn' : null })
  }
  if (r.charset) rows.push({ key: 'charset', value: r.charset, mono: true })
  if (num(r.response_ms) != null) rows.push({ key: 'responseMs', value: `${num(r.response_ms)} ms` })
  if (r.via === 'proxy' || r.via === 'direct') rows.push({ key: 'route', value: t(`httpdx.route.${r.via}`) })
  return rows
}

/** Sayfa geldi ama kural sağlanmadı — kartın hüküm satırı ("Bulunamadı" vb.) bunları zaten söyler. */
const CONDITION_CODES = new Set(['KEYWORD_NOT_FOUND', 'KEYWORD_FOUND_FORBIDDEN', 'KEYWORD_COUNT_MISMATCH'])

/**
 * KART için son başarısızlığın kısa nedeni (2026-10-04) — sunucu kodu varsa:
 * - istek tamamlanmadıysa (`error` hükmü): "Sayfa okunamadı" yerine kısa neden ("Zaman aşımı", "DNS çözümlenemedi" …);
 * - sayfa geldiyse: hüküm satırının söylemediği bir şey varsa çip — kod koşul ailesi DEĞİLSE kısa neden ("Boş yanıt",
 *   "Okuma sınırı aşıldı" …), koşul ailesiyse ilk ipucu ("Giriş sayfası geldi" …); ikisi de yoksa null.
 * Eski satır (kod yok) → null: kart eski görünümünde kalır.
 * @returns {{ code, label, tone, hint: boolean } | null}
 */
export function cardReason(m, t) {
  const code = m?.failure_reason
  if (!code || !FAILURE_SET.has(code) || m?.status === 'up' || m?.status === 'unknown' || m?.status == null) return null
  if (!CONDITION_CODES.has(code)) {
    return { code, label: interpolate(t(`kwfail.${code}.short`), failureParams(m, m, t)), tone: reasonTone(code), hint: false }
  }
  const h = hintsOf(m)[0]
  return h ? { code: h, label: t(`kwhint.${h}.title`), tone: 'warning', hint: true } : null
}

/** Alıntıda anahtar kelime aranır mı / bulundu mu — `null` (alıntı yok) | true | false (harf kuralıyla). */
export function excerptHasKeyword(excerpt, keyword, caseSensitive = false) {
  const e = String(excerpt ?? '')
  const k = String(keyword ?? '').replace(/\s+/g, ' ').trim()
  if (!e || !k) return null
  return caseSensitive ? e.includes(k) : e.toLocaleLowerCase('tr').includes(k.toLocaleLowerCase('tr'))
}
