// Veritabanı Sorgu İzleme — "Birden çok bağlantı için oluştur" (B4, 2026-11-05) SAF modeli. Aynı izleme gövdesi seçilen
// her bağlantı için SIRAYLA mevcut create ucuyla oluşturulur; ad kalıbı `{ad} · {bağlantı}`. İlk hatada DURMAZ: o satır
// hatalı yazılır, sıradaki denenir (ad çakışması, başka türde reddedilen sorgu …). Sunucu asıl doğrulayıcıdır.

import { countText } from './dbqueryPageModel.js'

/** Tek seferde en çok bağlantı (istek sayısı sınırlı — sonsuz döngü / yığın istek yok). */
export const MAX_BULK = 50
export const NAME_MAX = 200

/** Ad kalıbının yer tutucuları — TR ve EN yazımı birlikte kabul edilir. */
const NAME_TOKENS = ['{ad}', '{name}']
const CONN_TOKENS = ['{bağlantı}', '{baglanti}', '{connection}']

/** Kalıp bağlantı adını içeriyor mu (içermiyorsa bütün adlar aynı olur → çakışma). */
export function patternHasConnection(pattern) {
  const p = String(pattern || '').toLowerCase()
  return CONN_TOKENS.some((tk) => p.includes(tk))
}

function replaceAllI(text, token, value) {
  // yer tutucu küçük harf duyarsız; değer olduğu gibi (split/join — düzenli ifade yok)
  let out = ''
  let rest = text
  for (let guard = 0; guard < 50; guard++) {
    const at = rest.toLowerCase().indexOf(token)
    if (at < 0) break
    out += rest.slice(0, at) + value
    rest = rest.slice(at + token.length)
  }
  return out + rest
}

/** Kalıp → ad (baştaki / sondaki boşluk kırpılır, art arda boşluk teke iner). */
export function applyNamePattern(pattern, baseName, connectionName) {
  let s = String(pattern || '')
  for (const tk of NAME_TOKENS) s = replaceAllI(s, tk, String(baseName || '').trim())
  for (const tk of CONN_TOKENS) s = replaceAllI(s, tk, String(connectionName || '').trim())
  return s.replace(/\s{2,}/g, ' ').trim()
}

/**
 * Plan → `[{ profile, name, problem }]` — `problem`: `empty` (boş ad), `tooLong` (> 200), `duplicate` (planda aynı ad —
 * büyük / küçük harf duyarsız, sunucunun LOWER(ad) tekilliği gibi). Sunucuya gitmeden ÖNCE gösterilir.
 */
export function bulkPlan(pattern, baseName, profiles) {
  const list = (Array.isArray(profiles) ? profiles : []).slice(0, MAX_BULK)
  const rows = list.map((p) => ({ profile: p, name: applyNamePattern(pattern, baseName, p?.name), problem: null }))
  const counts = new Map()
  for (const r of rows) { const k = r.name.toLocaleLowerCase('tr-TR'); counts.set(k, (counts.get(k) || 0) + 1) }
  for (const r of rows) {
    if (!r.name) r.problem = 'empty'
    else if (r.name.length > NAME_MAX) r.problem = 'tooLong'
    else if (counts.get(r.name.toLocaleLowerCase('tr-TR')) > 1) r.problem = 'duplicate'
  }
  return rows
}

/** Plan gönderilebilir mi (en az bir satır, hiç sorun yok). */
export function planReady(rows) {
  return Array.isArray(rows) && rows.length > 0 && rows.every((r) => !r.problem)
}

/** Yanıt alınamayan / ağ geçidinin kestiği istek kodları — istek sunucuda tamamlanmış OLABİLİR (O2). */
const UNSURE_CODES = new Set(['NETWORK_ERROR', 'OFFLINE', 'TIMEOUT', 'GATEWAY_TIMEOUT', 'NO_RESPONSE'])

/**
 * Başarısız yanıt "sonuç bilinmiyor" mu: istek atıldı ama yanıt gelmedi (ağ / zaman aşımı) ya da ağ geçidi kesti
 * (502 / 503 / 504, durum 0). Sunucunun reddi (400 / 403 / 409 …) kesin hatadır.
 */
export function isUnsureFailure(res) {
  if (!res) return true
  const status = Number(res.status ?? res.errorInfo?.status)
  if (status === 0 || status === 502 || status === 503 || status === 504) return true
  return UNSURE_CODES.has(String(res.code || res.errorInfo?.code || ''))
}

/**
 * Sıralı oluşturma — `create(body)` mevcut create ucu (sunucu yanıtı). Her satır sonucu `onRow` ile bildirilir;
 * `shouldStop()` true dönerse KALAN satırlar `skipped` olur (gönderilen istek beklenir, yarıda kesilmez).
 * Yanıt alınamayan satır `unknown` (sonuç bilinmiyor — izleme oluşmuş olabilir; O2), sunucunun reddi `error`.
 * Dönüş: `[{ profileId, name, status: 'ok' | 'error' | 'unknown' | 'skipped', id?, error?, code?, warnings?, planWarning? }]`.
 */
export async function runBulk(rows, baseBody, create, { onRow, shouldStop } = {}) {
  const results = []
  const list = (Array.isArray(rows) ? rows : []).slice(0, MAX_BULK)
  for (let i = 0; i < list.length; i++) {
    const r = list[i]
    const profileId = r.profile?.id ?? null
    if (shouldStop?.()) {
      const rest = list.slice(i).map((x) => ({ profileId: x.profile?.id ?? null, name: x.name, status: 'skipped' }))
      results.push(...rest)
      rest.forEach((x, k) => onRow?.(i + k, x))
      break
    }
    let res
    try {
      res = await create({ ...baseBody, profile_id: profileId, name: r.name })
    } catch (e) {
      res = { success: false, error: e?.message || null, code: 'NETWORK_ERROR', status: 0 }
    }
    let row
    if (res?.success) {
      const warnings = Array.isArray(res.warnings) ? res.warnings.length : (Array.isArray(res.data?.warnings) ? res.data.warnings.length : 0)
      const planWarning = !!(res.plan_warning || res.data?.plan_warning)
      row = { profileId, name: r.name, status: 'ok', id: res.data?.id ?? null, warnings, planWarning }
    } else if (isUnsureFailure(res)) {
      row = { profileId, name: r.name, status: 'unknown', error: res?.error || res?.message || null, code: res?.code || null }
    } else {
      row = { profileId, name: r.name, status: 'error', error: res?.error || res?.message || null, code: res?.code || null }
    }
    results.push(row)
    onRow?.(i, row)
  }
  return results
}

/**
 * Sonucu bilinmeyen satırları izleme listesiyle çözer (O2): aynı adlı izleme listede varsa satır `ok` olur (ad tüm
 * takımlarda tekil — büyük / küçük harf duyarsız, sunucunun LOWER(ad) kuralı), yoksa `unknown` kalır. Liste okunamadıysa
 * (`monitors` dizi değil) satırlar değişmez.
 */
export function resolveUnknownRows(results, monitors) {
  if (!Array.isArray(results) || !Array.isArray(monitors)) return results
  const byName = new Map()
  for (const m of monitors) if (m?.name) byName.set(String(m.name).toLocaleLowerCase('tr-TR'), m)
  let changed = false
  const out = results.map((r) => {
    if (r?.status !== 'unknown') return r
    const hit = byName.get(String(r.name || '').toLocaleLowerCase('tr-TR'))
    if (!hit) return r
    changed = true
    return { ...r, status: 'ok', id: hit.id ?? null, resolved: true, error: null, code: null }
  })
  return changed ? out : results
}

/** Yeniden denenecek bağlantılar: reddedilen ve atlanan satırlar (sonucu bilinmeyen satır izleme oluşmuş olabileceği için HARİÇ). */
export function retryProfileIds(results) {
  return (results || []).filter((r) => r?.status === 'error' || r?.status === 'skipped').map((r) => r.profileId).filter((id) => id != null)
}

/**
 * Sonuç özeti — sayılı parçalar (`dbq.bulk.sum.<durum>` + tekil `.one`; D10), sıfır olan parça yazılmaz (oluşturulan sayısı
 * her zaman). `"3 izleme oluşturuldu · 1 reddedildi · 1 sonucu bilinmiyor"`.
 */
export function bulkSummaryText(t, counts) {
  const c = counts || {}
  const parts = [countText(t, 'dbq.bulk.sum.ok', c.ok || 0)]
  // Hata sayısının anahtarı `rejected` (anahtar adında "error" geçen metin hata iletisi sayılır — kısa sayaç değil)
  for (const [k, key] of [['error', 'rejected'], ['unknown', 'unknown'], ['skipped', 'skipped']]) if (c[k] > 0) parts.push(countText(t, `dbq.bulk.sum.${key}`, c[k]))
  return parts.join(' · ')
}

/** Sonuç sayıları. */
export function bulkCounts(results) {
  const c = { ok: 0, error: 0, unknown: 0, skipped: 0 }
  for (const r of results || []) if (c[r.status] != null) c[r.status]++
  return c
}

/** Sonuçta başarılı olmayan (reddedilen / bilinmeyen / atlanan) satır var mı — kapatmada onay gerekir (O1). */
export function hasUnfinished(results) {
  return (results || []).some((r) => r?.status === 'error' || r?.status === 'unknown' || r?.status === 'skipped')
}

/** Koşu ve önizlemenin TEK listesi: takımın bağlantılarının ilk `MAX_BULK` tanesi (O6). */
export function bulkCandidates(list) {
  return (Array.isArray(list) ? list : []).slice(0, MAX_BULK)
}
