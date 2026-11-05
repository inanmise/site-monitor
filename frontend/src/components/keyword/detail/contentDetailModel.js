/**
 * Anahtar kelime izlemesi ayrıntı penceresi "İçerik" sekmesinin saf yardımcıları (kw paket D + E2, 2026-11-05):
 * değişim özeti (`change_summary`), içerik serisi (`content-series`) ve canlı eşleşme bağlamları.
 */

const MAX_DIFF_LINES = 20
const MAX_LINE = 200
const MAX_SUMMARY_JSON = 4000 + 64   // sunucu ≤ 4000 karakter yazar; biraz pay

const num = (v) => {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

const lines = (raw) => (Array.isArray(raw) ? raw : [])
  .filter((s) => typeof s === 'string')
  .slice(0, MAX_DIFF_LINES)
  .map((s) => (s.length > MAX_LINE ? `${s.slice(0, MAX_LINE)}…` : s))

/**
 * Değişim özeti (nesne ya da JSON dizgesi) → `{ linesBefore, linesAfter, removed, added, removedTotal, addedTotal,
 * truncated, removedMore, addedMore }`; okunamazsa null. Toplamlar listeden küçük gelirse liste uzunluğu esas alınır.
 */
export function summaryOf(raw) {
  let s = raw
  if (typeof raw === 'string') {
    if (!raw.trim() || raw.length > MAX_SUMMARY_JSON) return null
    try { s = JSON.parse(raw) } catch { return null }
  }
  if (!s || typeof s !== 'object' || Array.isArray(s)) return null
  const removed = lines(s.removed)
  const added = lines(s.added)
  const removedTotal = Math.max(removed.length, num(s.removed_total) ?? removed.length)
  const addedTotal = Math.max(added.length, num(s.added_total) ?? added.length)
  return {
    linesBefore: num(s.lines_before),
    linesAfter: num(s.lines_after),
    removed, added, removedTotal, addedTotal,
    removedMore: removedTotal - removed.length,
    addedMore: addedTotal - added.length,
    truncated: s.truncated === true,
  }
}

/** Değişen satır sayısı (özet kartı / rozet): kaldırılan + eklenen toplam; özet yoksa null. */
export function changedLineCount(summary) {
  return summary ? summary.removedTotal + summary.addedTotal : null
}

/**
 * Sunucu içerik serisi → grafik satırları (zaman sırasıyla): `{ t, ts, checks, failed, occAvg, occMin, occMax, occBand,
 * bytes, changed }`. Kontrolü olmayan kova (checks 0) ve değeri olmayan alan NULL kalır → grafikte BOŞLUK (0 değil).
 */
export function shapeContentSeries(data) {
  const pts = Array.isArray(data?.points) ? data.points : []
  const rows = []
  for (const p of pts) {
    const t = Date.parse(p?.ts && !/Z|[+-]\d\d:?\d\d$/.test(p.ts) ? `${p.ts}Z` : p?.ts)
    if (!Number.isFinite(t)) continue
    const checks = num(p.checks) ?? 0
    const has = checks > 0
    const occMin = has ? num(p.occ_min) : null
    const occMax = has ? num(p.occ_max) : null
    rows.push({
      t, ts: p.ts, checks, failed: num(p.failed) ?? 0,
      occAvg: has ? num(p.occ_avg) : null, occMin, occMax,
      occBand: occMin != null && occMax != null ? [occMin, occMax] : null,
      bytes: has ? num(p.bytes_avg) : null,
      changed: p.changed === true || num(p.changed) > 0,
    })
  }
  rows.sort((a, b) => a.t - b.t)
  return rows
}

/** Komşusuz tek değer mi? (çizgi çizilemez → nokta çizilir — Metrikler sayfasının kuralı). */
export function isLonePoint(rows, key, index) {
  if (!Array.isArray(rows) || !rows[index]) return false
  const has = (i) => rows[i] != null && rows[i][key] != null
  return has(index) && !has(index - 1) && !has(index + 1)
}

/** Seri özeti (ekran okuyucu): kova, kontrol, başarısız, en çok eşleşme, değişen kova. */
export function seriesStats(rows) {
  let checks = 0
  let failed = 0
  let maxOcc = null
  let changed = 0
  let buckets = 0
  for (const r of rows || []) {
    if (r.checks > 0) buckets++
    checks += r.checks
    failed += r.failed
    if (r.occMax != null) maxOcc = maxOcc == null ? r.occMax : Math.max(maxOcc, r.occMax)
    if (r.changed) changed++
  }
  return { buckets, checks, failed, maxOcc, changed }
}

/**
 * Son kontrolün boyut kuralı sonucu: sınır yoksa ya da boyut bilinmiyorsa null (bilinmiyor ≠ sağlıklı); aksi hâlde
 * `min ≤ bayt ≤ max` (kırpılan gövde maksimumu aşmış sayılır — sunucu kuralı).
 */
export function sizeOkOf(m) {
  const min = num(m?.min_bytes)
  const max = num(m?.max_bytes)
  const bytes = num(m?.body_bytes)
  if ((min == null && max == null) || bytes == null) return null
  if (min != null && bytes < min) return false
  if (max != null && (bytes > max || m?.body_truncated === true)) return false
  return true
}

/** Canlı eşleşme yanıtı → güvenli biçim (bozuk alanlar düşer; kural başına ≤ 5 bağlam). */
export function matchesOf(data) {
  if (!data || typeof data !== 'object') return null
  const rules = (Array.isArray(data.rules) ? data.rules : []).filter((r) => r && typeof r === 'object').slice(0, 10).map((r) => ({
    id: r.id ?? null,
    // Sözleşmede tür alanı yok — gelirse kullanılır, yoksa panel izlemenin kuralından (kimlikle) bulur
    kind: r.kind === 'REGEX' || r.kind === 'TEXT' ? r.kind : null,
    patternPreview: typeof r.pattern_preview === 'string' ? r.pattern_preview : '',
    count: num(r.count),
    contexts: (Array.isArray(r.contexts) ? r.contexts : []).filter((c) => c && typeof c === 'object').slice(0, 5).map((c) => ({
      before: String(c.before ?? ''), match: String(c.match ?? ''), after: String(c.after ?? ''),
      source: typeof c.source === 'string' ? c.source.toUpperCase() : null,
    })),
  }))
  return {
    fetchedAt: data.fetched_at ?? null,
    httpStatus: num(data.http_status),
    finalUrl: typeof data.final_url === 'string' ? data.final_url : null,
    truncated: data.truncated === true,
    rules,
  }
}

/** Eşleşme bağlamının kaynağı (yalnız bilinen değerler gösterilir). */
export const CONTEXT_SOURCES = Object.freeze(['PAGE', 'VISIBLE', 'RAW', 'CSS', 'JSON'])
