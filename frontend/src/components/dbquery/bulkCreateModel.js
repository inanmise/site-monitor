// Veritabanı Sorgu İzleme — "Birden çok bağlantı için oluştur" (B4, 2026-11-05) SAF modeli. Aynı izleme gövdesi seçilen
// her bağlantı için SIRAYLA mevcut create ucuyla oluşturulur; ad kalıbı `{ad} · {bağlantı}`. İlk hatada DURMAZ: o satır
// hatalı yazılır, sıradaki denenir (ad çakışması, başka türde reddedilen sorgu …). Sunucu asıl doğrulayıcıdır.

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

/**
 * Sıralı oluşturma — `create(body)` mevcut create ucu (sunucu yanıtı). Her satır sonucu `onRow` ile bildirilir;
 * `shouldStop()` true dönerse KALAN satırlar `skipped` olur (gönderilen istek beklenir, yarıda kesilmez).
 * Dönüş: `[{ profileId, name, status: 'ok' | 'error' | 'skipped', id?, error?, code? }]`.
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
      res = { success: false, error: e?.message || null, code: 'NETWORK_ERROR' }
    }
    const row = res?.success
      ? { profileId, name: r.name, status: 'ok', id: res.data?.id ?? null }
      : { profileId, name: r.name, status: 'error', error: res?.error || res?.message || null, code: res?.code || null }
    results.push(row)
    onRow?.(i, row)
  }
  return results
}

/** Sonuç sayıları. */
export function bulkCounts(results) {
  const c = { ok: 0, error: 0, skipped: 0 }
  for (const r of results || []) if (c[r.status] != null) c[r.status]++
  return c
}
