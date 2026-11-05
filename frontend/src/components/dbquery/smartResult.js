/**
 * Akıllı kural SONUCU modeli (saf; 2026-11-05, dbq2 D). Sunucu `rule_results[]` öğesine YALNIZ bilindiğinde ek alanlar yazar
 * (`DbQueryEvaluator`): `samples`, `min_samples`; ANOMALY'de `baseline_mean`, `baseline_sigma`, `band_low`, `band_high`
 * (olağan aralık = ortalama ∓ k_warning·σ), `deviation` (işaretli z; σ = 0 ve değer ≠ ortalama → ±1e9 = "sınırsız");
 * FROZEN'da `same_runs` (bu çalıştırma dahil ardışık aynı değer; taban kuruluyorsa alt sınır). Eski satırlarda alan yok →
 * görünüm yalnız sunucunun okunur `actual` metni (bugünkü gibi).
 */

const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null)
/** Sunucunun "sınırsız sapma" işareti (σ = 0) — bu büyüklükte z gösterilmez, "sınırsız" yazılır. */
export const UNBOUNDED_DEVIATION = 1e8

/** Taban kuruluyor mu (yeni not; eski notlar `RuleResultsList.BASELINE_NOTES`'ta). */
export const isBuildingBaseline = (r) => r?.note === 'building_baseline'

/**
 * Sonuç → gösterim modeli ya da null (ek alan yok):
 *  - `{ kind: 'building', samples, minSamples }` — taban kuruluyor (sayaç; ikisi de biliniyorsa "4 / 10");
 *  - `{ kind: 'band', value, low, high, mean, deviation, unbounded, samples, position }` — ANOMALY olağan aralık; `position`
 *    [0, 1] ölçeğinde değer / aralık uçları (mini çubuk için; aralık ve değer birlikte sığar);
 *  - `{ kind: 'frozen', sameRuns, samples, atLeast }` — FROZEN sayaç (`atLeast` taban kuruluyorken alt sınır).
 */
export function smartResultOf(r) {
  if (!r || typeof r !== 'object') return null
  const samples = num(r.samples)
  const minSamples = num(r.min_samples)
  if (isBuildingBaseline(r)) {
    if (r.kind === 'FROZEN' && num(r.same_runs) != null) return { kind: 'frozen', sameRuns: r.same_runs, samples, atLeast: true, minSamples }
    return { kind: 'building', samples, minSamples }
  }
  if (r.kind === 'ANOMALY') {
    const low = num(r.band_low), high = num(r.band_high)
    if (low == null && high == null) return null
    const value = num(r.compared) ?? num(r.measure)
    const dev = num(r.deviation)
    const unbounded = dev != null && Math.abs(dev) >= UNBOUNDED_DEVIATION
    return { kind: 'band', value, low, high, mean: num(r.baseline_mean), deviation: dev, unbounded, samples, position: bandPosition(value, low, high) }
  }
  if (r.kind === 'FROZEN' && num(r.same_runs) != null) return { kind: 'frozen', sameRuns: r.same_runs, samples, atLeast: false }
  return null
}

/**
 * Mini çubuk konumları: aralık [low, high] ile değeri birlikte kapsayan ölçek (her yanda %10 pay) → `{ low, high, value }`
 * oranları (0–1). Uçlardan biri yoksa (tek yönlü anomali) o uç ölçeğin kenarıdır. Hesaplanamazsa null.
 */
export function bandPosition(value, low, high) {
  const pts = [value, low, high].filter((v) => v != null)
  if (pts.length < 2) return null
  let lo = Math.min(...pts), hi = Math.max(...pts)
  if (hi === lo) { lo -= 1; hi += 1 }
  const pad = (hi - lo) * 0.1
  lo -= pad; hi += pad
  const at = (v) => (v == null ? null : (v - lo) / (hi - lo))
  return { low: low == null ? 0 : at(low), high: high == null ? 1 : at(high), value: at(value) }
}
