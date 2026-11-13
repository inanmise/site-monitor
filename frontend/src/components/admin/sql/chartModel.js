import { cellText, isTimeType } from './sqlUtils.js'

/**
 * Sonuçtan grafik modeli (2026-11-13, C2) — yalnız EKRANDAKİ satırlar (≤ 1000). Çubuk / çizgi: X + 1–2 sayısal seri;
 * pasta: X + 1 seri (kategori başına toplam, en büyük {@link PIE_MAX_SLICES} dilim + "Diğer"). Boş / sayı olmayan değer
 * BOŞLUK (null — çizgi kesilir, asla 0 değil). Maskeli kolon seri olamaz. Zaman X kolonu otomatik seçilir ve sıralanır.
 */
export const CHART_KINDS = ['bar', 'line', 'pie']
export const MAX_SERIES = 2
export const BAR_MAX_CATEGORIES = 100
export const PIE_MAX_SLICES = 12
export const OTHER_KEY = '__other__'

const toNum = (v) => {
  if (v == null || v === '' || typeof v === 'boolean') return null
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : null
}

/** Kolonları sınıflar: sayısal (seri olabilir), zaman, kategori (X olabilir). Maskeli kolonlar seri olamaz. */
export function chartColumns(cols, types, masked = new Set()) {
  const numeric = []
  const time = []
  const category = []
  for (const c of cols || []) {
    const ty = types?.[c]
    if (ty === 'number' && !masked.has(c)) numeric.push(c)
    if (isTimeType(ty)) time.push(c)
    if (ty !== 'json' && ty !== 'null') category.push(c)
  }
  return { numeric, time, category }
}

/** Varsayılan grafik: zaman + sayı → çizgi; kategori + sayı → çubuk; tek sayı → satır sırasıyla çubuk; sayı yoksa null. */
export function defaultChartSpec(cols, types, masked) {
  const { numeric, time, category } = chartColumns(cols, types, masked)
  if (!numeric.length) return null
  if (time.length) return { kind: 'line', x: time[0], ys: [numeric[0]] }
  const xCat = category.find((c) => !numeric.includes(c))
  if (xCat) return { kind: 'bar', x: xCat, ys: [numeric[0]] }
  return { kind: 'bar', x: null, ys: numeric.slice(0, 1) }   // X yok: satır numarası
}

/** Seçimi geçerli kolonlara oturtur (kolonlar değişince). Geçersizse varsayılan. */
export function normalizeSpec(spec, cols, types, masked) {
  const def = defaultChartSpec(cols, types, masked)
  if (!def) return null
  if (!spec || !CHART_KINDS.includes(spec.kind)) return def
  const { numeric } = chartColumns(cols, types, masked)
  // Aynı kolon iki seride olamaz (yinelenen seri — grafik anahtarı çakışır, açıklamada iki kez görünür)
  const ys = [...new Set((spec.ys || []).filter((y) => numeric.includes(y)))].slice(0, spec.kind === 'pie' ? 1 : MAX_SERIES)
  const x = spec.x == null || (cols || []).includes(spec.x) ? spec.x : def.x
  return { kind: spec.kind, x, ys: ys.length ? ys : def.ys.slice(0, 1) }
}

const labelOf = (v, i) => (v == null ? '—' : String(cellText(v) ?? i + 1))

/**
 * Grafik verisi.
 * @returns {{ data: Array, truncated: boolean, skipped: number }}
 *   çubuk / çizgi: `[{ x, <y1>, <y2> }]`; pasta: `[{ name, value, key }]`. `skipped` = pastada değeri sayı olmayan / ≤ 0 satır.
 */
export function chartData(rows, spec, types) {
  const list = Array.isArray(rows) ? rows : []
  if (!spec || !spec.ys?.length) return { data: [], truncated: false, skipped: 0 }
  if (spec.kind === 'pie') {
    const y = spec.ys[0]
    const sums = new Map()
    let skipped = 0
    list.forEach((r, i) => {
      const v = toNum(r?.[y])
      if (v == null || v <= 0) { skipped++; return }
      const name = spec.x ? labelOf(r?.[spec.x], i) : String(i + 1)
      sums.set(name, (sums.get(name) ?? 0) + v)
    })
    const sorted = [...sums.entries()].sort((a, b) => b[1] - a[1])
    const head = sorted.slice(0, PIE_MAX_SLICES).map(([name, value], i) => ({ name, value, key: `s${i}` }))
    const rest = sorted.slice(PIE_MAX_SLICES).reduce((s, [, v]) => s + v, 0)
    if (rest > 0) head.push({ name: OTHER_KEY, value: rest, key: 'other' })
    return { data: head, truncated: sorted.length > PIE_MAX_SLICES, skipped }
  }
  let points = list.map((r, i) => {
    const p = { x: spec.x ? labelOf(r?.[spec.x], i) : String(i + 1), _t: null }
    if (spec.x && isTimeType(types?.[spec.x])) {
      const ms = Date.parse(String(r?.[spec.x]))
      p._t = Number.isFinite(ms) ? ms : null
    }
    spec.ys.forEach((y) => { p[y] = toNum(r?.[y]) })
    return p
  })
  if (spec.x && isTimeType(types?.[spec.x])) {
    points = points.filter((p) => p._t != null).sort((a, b) => a._t - b._t)
  }
  let truncated = false
  if (spec.kind === 'bar' && points.length > BAR_MAX_CATEGORIES) { points = points.slice(0, BAR_MAX_CATEGORIES); truncated = true }
  return { data: points.map((pt) => { const o = { ...pt }; delete o._t; return o }), truncated, skipped: 0 }
}
