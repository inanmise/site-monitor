/**
 * Veritabanı Sorgu İzleme — EŞİK ÖNERİSİ modeli (saf; 2026-11-05, dbq2 B3). İstemcide hesaplanır, öneri UYGULANMADAN
 * gösterilir (kullanıcı Uygula / Vazgeç der); sunucuya istek yalnız kayıtlı izlemenin değer serisini okumak içindir.
 *
 * <p>Kaynaklar:
 *  (a) DENEME — son "Dene"nin bu kurala ait karşılaştırılan değeri (`rule_results[].compared`; yoksa `measure`, o da yoksa
 *      sayıya çevrilebilen `actual`). Tek ölçüm: p95 = p99 = değer (öneri kaba; kullanıcıya söylenir).
 *  (b) SON 7 GÜN — kayıtlı izlemede `response-series?metric=value&days=7`. Bu seri, sunucuda değeri belirleyen kuralın
 *      (kayıtlı kurallardan metin eşleşmesi OLMAYAN ilki) ölçtüğü sayıdır; yalnız o kural için ve kuralın ölçüm tanımı
 *      (tür, kolon, toplama, değişim ayarları, saat dilimi) ve sorgu kayıtlıyla AYNIYKEN kullanılır. Örnekler kova
 *      başına: üst yönlü öneride kovanın en büyüğü, alt yönlüde en küçüğü (ondalıklı veride kova ortalaması — seri tam
 *      sayıya yuvarlanmış min / maks taşır).
 *
 * <p>Yön işleçten: büyük(eşit)se → ÜST (uyarı ≈ p95 × 1,2, kritik ≈ p99 × 1,5); küçük(eşit)se → ALT (uyarı ≈ p5 × 0,8,
 * kritik ≈ p1 × 0,5); aralık dışındaysa → BANT (alt ve üst birlikte). Eşitlik / aralık içi işleçlerinde öneri yok. Pay her
 * zaman |değer| üzerinden (eksi değerde yön bozulmaz). Yuvarlama: iki anlamlı basamak, üst sınır yukarı / alt sınır aşağı;
 * tam sayı verisinde tam sayı; tazelikte (saniye) ≥ 1 dk ise dakikaya. Kritik uyarıdan sıkı çıkamaz (aynı değere
 * yuvarlanırsa kritik önerilmez).
 */
import { RULE_KINDS } from './ruleModel.js'

/** Seriyle öneri için en az örnek (kova) — azsa "veri yetersiz". */
export const SUGGEST_MIN_SAMPLES = 12
/** Seri penceresi (gün). */
export const SUGGEST_DAYS = 7
export const WARN_MARGIN = 0.2
export const CRIT_MARGIN = 0.5
/** Öneri yapılabilen kural türleri (eşiği sayı olan) — metin eşleşmesi ve yeni "akıllı" türler (kendi parametreleri) hariç. */
export const SUGGEST_KINDS = Object.freeze(['ROW_COUNT', 'COLUMN_VALUE', 'CHANGE', 'FRESHNESS'])

const isNum = (v) => typeof v === 'number' && Number.isFinite(v)

/** Kural için öneri yönü: upper | lower | band | null (işleç desteklenmiyor / tür uygun değil). */
export function suggestDirection(rule) {
  if (!rule || !SUGGEST_KINDS.includes(rule.kind)) return null
  const op = rule.operator || 'GT'
  if (op === 'GT' || op === 'GTE') return 'upper'
  if (rule.kind === 'FRESHNESS') return null
  if (op === 'LT' || op === 'LTE') return 'lower'
  if (op === 'OUTSIDE') return 'band'
  return null
}

/** Sıralı dizide en yakın sıra yüzdeliği (sunucu kova p95 tanımıyla aynı). */
export function quantile(sorted, q) {
  if (!sorted.length) return null
  const i = Math.min(sorted.length - 1, Math.max(0, Math.ceil(q * sorted.length) - 1))
  return sorted[i]
}

/**
 * İki anlamlı basamağa yuvarlama — `up`: sonsuza doğru (üst sınır gevşer), `down`: eksi sonsuza. `integral` ise sonuç tam
 * sayı (ve tam sayı adımına yuvarlanır). 0 → 0.
 */
export function niceRound(v, mode = 'up', { integral = false } = {}) {
  if (!isNum(v) || v === 0) return isNum(v) ? 0 : null
  const mag = Math.floor(Math.log10(Math.abs(v)))
  let step = 10 ** (mag - 1)
  if (integral) step = Math.max(1, step)
  const f = mode === 'up' ? Math.ceil : Math.floor
  const out = f(v / step) * step
  // Kayan nokta artığı (0.30000000000000004) temizlenir — en çok 12 ondalık
  return Number(out.toFixed(Math.max(0, Math.min(12, -Math.floor(Math.log10(step))))))
}

/** Tazelik (saniye) yuvarlaması: ≥ 60 sn ise tam dakikaya, değilse 10 sn'ye (yönle). */
function roundSeconds(v, mode) {
  if (!isNum(v)) return null
  const unit = Math.abs(v) >= 60 ? 60 : 10
  const f = mode === 'up' ? Math.ceil : Math.floor
  return f(v / unit) * unit
}

const widen = (v, margin, dir) => (dir === 'up' ? v + Math.abs(v) * margin : v - Math.abs(v) * margin)

/**
 * Örnekler + kural → öneri. Dönüş:
 *  - `{ ok: true, warning, critical, stats: { n, p1, p5, p95, p99, min, max }, direction }` (critical null olabilir);
 *  - `{ ok: false, reason }` — reason: `kind` (tür uygun değil) | `operator` (işleç desteklemiyor) | `no_data` | `too_few` |
 *    `floor` (alt yönde öneri sıfırın altına düştü — eksi değer görmemiş veride hiç tetiklenmez).
 * `minSamples` tek ölçümlü deneme için 1.
 */
export function suggestThresholds(rule, values, { minSamples = SUGGEST_MIN_SAMPLES } = {}) {
  if (!rule || !SUGGEST_KINDS.includes(rule.kind)) return { ok: false, reason: 'kind' }
  const direction = suggestDirection(rule)
  if (!direction) return { ok: false, reason: 'operator' }
  const xs = (Array.isArray(values) ? values : []).filter(isNum).sort((a, b) => a - b)
  if (!xs.length) return { ok: false, reason: 'no_data' }
  if (xs.length < minSamples) return { ok: false, reason: 'too_few', n: xs.length, min: minSamples }
  const stats = {
    n: xs.length, min: xs[0], max: xs[xs.length - 1],
    p1: quantile(xs, 0.01), p5: quantile(xs, 0.05), p95: quantile(xs, 0.95), p99: quantile(xs, 0.99),
  }
  const integral = xs.every((v) => Number.isInteger(v))
  const fresh = rule.kind === 'FRESHNESS'
  const round = (v, mode) => (fresh ? roundSeconds(v, mode) : niceRound(v, mode, { integral }))
  const upper = () => {
    const w = round(widen(stats.p95, WARN_MARGIN, 'up'), 'up')
    let c = round(widen(stats.p99, CRIT_MARGIN, 'up'), 'up')
    if (!(c > w)) c = null
    return { w, c }
  }
  // Eksi değer görmemiş veride (sayılar, yaşlar) sıfırın altındaki ya da "< 0" alt sınır hiç tetiklenmez — önerilmez
  const nonNegative = xs[0] >= 0
  const useless = (b) => b == null || (nonNegative && (b < 0 || (b === 0 && rule.operator === 'LT')))
  const lower = () => {
    let w = round(widen(stats.p5, WARN_MARGIN, 'down'), 'down')
    let c = round(widen(stats.p1, CRIT_MARGIN, 'down'), 'down')
    if (useless(w)) w = null
    if (useless(c) || w == null || !(c < w)) c = null
    return { w, c }
  }
  if (direction === 'upper') { const { w, c } = upper(); return { ok: true, direction, warning: w, critical: c, stats } }
  if (direction === 'lower') {
    const { w, c } = lower()
    return w == null ? { ok: false, reason: 'floor', stats } : { ok: true, direction, warning: w, critical: c, stats }
  }
  // Bant (aralık dışındaysa): uyarı [p5 × 0,8, p95 × 1,2], kritik [p1 × 0,5, p99 × 1,5] — kritik bant uyarıyı kapsamıyorsa yok
  // Alt uç anlamsızsa (eksi değer görmemiş veride sıfırın altı) bant alt ucu en küçük gözlenen değerin yarısına iner
  const u = upper(), l = lower()
  const lowW = l.w ?? niceRound(stats.min / 2, 'down', { integral })
  const warning = [lowW, u.w]
  const critical = u.c != null && l.c != null ? [l.c, u.c] : null
  return { ok: true, direction, warning, critical, stats }
}

/** Önerinin kurala uygulanmış hâli (yalnız eşikler değişir; kimlik, tür, işleç, kolon aynı). */
export function applySuggestion(rule, s) {
  if (!rule || !s?.ok) return rule
  return { ...rule, warning: s.warning ?? null, critical: s.critical ?? null }
}

/**
 * Deneme sonucundan bu kuralın değeri: `rule_results` içinde AYNI kimlik ve AYNI tür; değerlendirilmiş olmalı.
 * `compared` → `measure` → sayıya çevrilebilen `actual` (ham sayı). Yoksa null.
 */
export function trialValueFor(rule, testResult) {
  const list = Array.isArray(testResult?.rule_results) ? testResult.rule_results : []
  const r = list.find((x) => x && rule && x.id === rule.id)
  if (!r || (r.kind && r.kind !== rule.kind) || r.evaluated === false) return null
  if (isNum(r.compared)) return r.compared
  if (isNum(r.measure)) return r.measure
  if (isNum(r.actual)) return r.actual
  return null
}

const DEF_KEYS = ['kind', 'column', 'aggregate', 'timezone']
const sameDef = (a, b) => DEF_KEYS.every((k) => (a?.[k] ?? null) === (b?.[k] ?? null))
  && JSON.stringify(a?.change ?? null) === JSON.stringify(b?.change ?? null)

/**
 * Kayıtlı izlemenin değer serisi bu kurala uygulanabilir mi: kural kayıtlı kuralların METİN EŞLEŞMESİ olmayan İLKİ
 * (sunucunun serideki değeri belirleyen kuralı), ölçüm tanımı kayıtlıyla aynı ve sorgu değişmemiş. Kayıtlı izleme yoksa false.
 */
export function seriesAppliesTo(rule, saved, currentSql) {
  if (!rule || !saved || saved.id == null) return false
  const rules = Array.isArray(saved.rules) ? saved.rules : []
  const first = rules.find((r) => r && RULE_KINDS.includes(r.kind) && r.kind !== 'TEXT_MATCH')
  if (!first || first.id !== rule.id || !sameDef(first, rule)) return false
  if (typeof currentSql === 'string' && typeof saved.sql_text === 'string' && currentSql !== saved.sql_text) return false
  return true
}

/**
 * Seri yanıtı → örnekler. Değeri olmayan (başarısız) kova atlanır. Ondalıklı veride (`metric_value` tam sayı değilse)
 * kova ortalaması; aksi hâlde yöne göre kovanın en büyüğü / en küçüğü (bant için ikisi de).
 */
export function seriesSamples(data, direction) {
  const list = Array.isArray(data?.series) ? data.series : []
  const pts = list.filter((p) => p && typeof p === 'object')
  const fractional = pts.some((p) => isNum(p.metric_value) && !Number.isInteger(p.metric_value))
  const out = []
  for (const p of pts) {
    const mean = isNum(p.metric_value) ? p.metric_value : (isNum(p.avg) ? p.avg : null)
    if (fractional) { if (mean != null) out.push(mean); continue }
    if (direction === 'lower') { const v = isNum(p.min) ? p.min : mean; if (v != null) out.push(v) }
    else if (direction === 'band') {
      if (isNum(p.min)) out.push(p.min)
      if (isNum(p.max)) out.push(p.max)
      if (!isNum(p.min) && !isNum(p.max) && mean != null) out.push(mean)
    } else { const v = isNum(p.max) ? p.max : mean; if (v != null) out.push(v) }
  }
  return out
}
