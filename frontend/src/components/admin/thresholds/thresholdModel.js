/**
 * Uyarı eşikleri — saf yardımcılar (çizimden bağımsız, birim testine açık).
 *
 * <p>Sunucu kuralı (AdminController.validateThreshold + ThresholdResolution.levelFor):
 * gün değerleri tam sayı ve ≥ 0, sıra kritik ≤ yüksek ≤ uyarı; seviye `kalan ≤ kritik → KRİTİK`,
 * `≤ yüksek → YÜKSEK`, `≤ uyarı → UYARI`, üstü alarm yok. Süresi geçmiş sertifika (kalan < 0) KRİTİK'tir.
 * Eşitlik sunucuda geçerlidir ama bir seviyeyi ulaşılmaz kılar → arayüz engellemez, uyarır.
 *
 * <p>Arayüz tavanı ({@link MAX_DAYS}) YALNIZ arayüzde: sunucu üst sınır koymuyor; 365 günün üstündeki
 * bir uyarı eşiği (sertifika ömrü ≤ 398 gün) her alanı sürekli UYARI'da tutar — yazım hatası sayılır.
 */

export const TIERS = [1, 2, 3, 4]
export const MAX_DAYS = 365
/** Sunucunun kendi yedeği (ThresholdResolution.defaults) — hiç satır yokken geçerli değerler. */
export const BUILTIN = { warning_days: 30, high_days: 15, critical_days: 7, re_alert_interval_hours: 24 }
/** Yeniden uyarı aralığı hazır değerleri (saat). Listede olmayan kayıtlı değer "özel" seçenek olarak eklenir. */
export const RE_ALERT_PRESETS = [1, 2, 4, 6, 12, 24, 48, 72, 168]
/** Ölçekte soldan sağa (kalan gün artarak). */
export const LEVELS = ['critical', 'high', 'warning', 'ok']

/** Sunucu tier'ı sayı ya da null döner; bozuk/eski değer (ör. metin) varsayılan sayılır. */
export function tierOf(row) {
  const n = Number(row?.tier)
  return Number.isInteger(n) && n >= 1 && n <= 4 ? n : null
}

/** Satırın gün üçlüsü (sayı). */
export function daysOf(row) {
  return {
    critical: Number(row?.critical_days ?? BUILTIN.critical_days),
    high: Number(row?.high_days ?? BUILTIN.high_days),
    warning: Number(row?.warning_days ?? BUILTIN.warning_days),
  }
}

/** Girdi dizesi → tam sayı ya da NaN ('' dâhil). */
export function parseDays(raw) {
  if (raw === '' || raw == null) return NaN
  const s = String(raw).trim()
  return /^[0-9]+$/.test(s) ? Number(s) : NaN
}

/**
 * Canlı doğrulama. Dönüş:
 *   fields  { critical?, high?, warning? } → alan hatası i18n anahtarı ({ key, arg }) — tam sayı / tavan
 *   order   true = sıra bozuk (kritik ≤ yüksek ≤ uyarı değil); `orderFields` sıra ihlaline karışan alanlar
 *   equal   ['critical-high' | 'high-warning'] — geçerli ama bir seviye ulaşılmaz
 *   valid   kaydedilebilir mi
 *   values  { critical, high, warning } (sayı; geçersizse NaN)
 */
export function validateDays(raw) {
  const values = { critical: parseDays(raw.critical), high: parseDays(raw.high), warning: parseDays(raw.warning) }
  const fields = {}
  for (const k of ['critical', 'high', 'warning']) {
    const v = values[k]
    if (!Number.isInteger(v)) fields[k] = { key: 'thr.errWhole' }
    else if (v > MAX_DAYS) fields[k] = { key: 'thr.errMax', arg: MAX_DAYS }
  }
  const allNumbers = Object.keys(fields).length === 0
  const order = allNumbers && !(values.critical <= values.high && values.high <= values.warning)
  const orderFields = new Set()
  if (order) {
    if (values.critical > values.high) { orderFields.add('critical'); orderFields.add('high') }
    if (values.high > values.warning) { orderFields.add('high'); orderFields.add('warning') }
  }
  const equal = []
  if (allNumbers && !order) {
    if (values.critical === values.high) equal.push('critical-high')
    if (values.high === values.warning) equal.push('high-warning')
  }
  return { fields, order, orderFields, equal, valid: allNumbers && !order, values }
}

/** Yeniden uyarı aralığının okunur metni ("Günde bir (24 saat)", "Her 6 saatte bir"). */
export function hoursKey(h) {
  if (h === 1) return ['thr.every1h']
  if (h === 24) return ['thr.everyDay']
  if (h === 168) return ['thr.everyWeek']
  if (h > 0 && h % 24 === 0) return ['thr.everyNDays', h / 24, h]
  return ['thr.everyNHours', h]
}

/** Ölçeğin sağ ucu: en az 60 gün, uyarı eşiğinin ~1.5 katı (10'a yuvarlı) — "eşik dışı" dilimi hep görünür. */
export function axisMaxFor(warning) {
  const w = Number.isFinite(warning) ? warning : BUILTIN.warning_days
  return Math.max(60, Math.ceil((w * 1.5) / 10) * 10)
}

/** Görsel alt sınır: dar dilim (ör. kritik 1 gün) yine görünür ve dokunulabilir kalsın. */
const MIN_PCT = 4

/**
 * Ölçek modeli — dört dilim (kritik/yüksek/uyarı/eşik dışı) + sınır işaretleri.
 * Dilim `from`/`to` KAPSAYICI gün aralığıdır (kritik 0..c; yüksek c+1..h; uyarı h+1..w; eşik dışı w+1..).
 * Boş dilim (eşitlik) `empty: true`, genişliği 0.
 * `linear`: asgari genişlik YOK — dilimler gerçek orantıda (kaydırıcı başparmakları sınırlarla hizalı kalsın).
 */
export function scaleModel({ critical: c, high: h, warning: w }, axisMax, { linear = false } = {}) {
  const end = Math.max(axisMax ?? axisMaxFor(w), w + 1)
  const minPct = linear ? 0 : MIN_PCT
  const raw = [
    { level: 'critical', from: 0, to: c, len: c, empty: false },
    { level: 'high', from: c + 1, to: h, len: h - c, empty: h <= c },
    { level: 'warning', from: h + 1, to: w, len: w - h, empty: w <= h },
    { level: 'ok', from: w + 1, to: null, len: end - w, empty: false },
  ]
  const weights = raw.map((s) => (s.empty ? 0 : Math.max((s.len / end) * 100, minPct)))
  const total = weights.reduce((a, b) => a + b, 0) || 1
  let acc = 0
  const segments = raw.map((s, i) => {
    const pct = (weights[i] / total) * 100
    const seg = { ...s, start: acc, pct }
    acc += pct
    return seg
  })
  // Sınır işaretleri: 0 + her boş olmayan dilimin sağ ucu (eşik dışı hariç). Yakın işaretler ikinci satıra.
  const ticks = [{ value: 0, pos: 0 }]
  for (const s of segments.slice(0, 3)) {
    if (s.empty) continue
    ticks.push({ value: s.to, pos: s.start + s.pct })
  }
  const merged = []
  for (const tk of ticks) {
    const prev = merged[merged.length - 1]
    if (prev && prev.value === tk.value) continue
    merged.push(tk)
  }
  let lastRow0 = -Infinity
  for (const tk of merged) {
    if (tk.pos - lastRow0 < 7) tk.row = 1
    else { tk.row = 0; lastRow0 = tk.pos }
  }
  return { segments, ticks: merged, axisMax: end }
}

/** "svc.example.com (6g)" (sunucu örnek biçimi) → { domain, days }; çözülemezse { domain: ham }. */
export function parseSample(s) {
  const m = /^(.*) [(](-?[0-9]+)g[)]$/.exec(String(s ?? ''))
  return m ? { domain: m[1], days: Number(m[2]) } : { domain: String(s ?? ''), days: null }
}

/** Önizleme sayımları (current/proposed) → sayı dörtlüsü. */
export function countsOf(obj) {
  const o = obj || {}
  return {
    critical: Number(o.critical ?? 0), high: Number(o.high ?? 0),
    warning: Number(o.warning ?? 0), ok: Number(o.ok ?? 0),
  }
}
