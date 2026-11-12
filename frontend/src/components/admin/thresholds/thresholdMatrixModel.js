// Eşik KARŞILAŞTIRMA matrisi (2026-11-12, zenginleştirme #3) — saf, test edilebilir. Varsayılan + Tier 1..4'ü
// yan yana kritik/yüksek/uyarı gün + yeniden-uyarı + ETKİ (sertifika sayısı) ile verir. Kendi satırı olan tier'ın
// override alanları işaretli; satırı olmayan tier varsayılanı DEVRALIR (soluk). Tür (monitor tipi) boyutu YOK —
// bu ekran sertifika bitiş eşikleridir ve katman (tier) temellidir. thresholdModel ile aynı kurallar.

import { BUILTIN, TIERS, daysOf, tierOf } from './thresholdModel.js'

const DEF_DAYS = { critical: BUILTIN.critical_days, high: BUILTIN.high_days, warning: BUILTIN.warning_days }

/** impact[key].scope_total → sayı ya da null (bilinmiyor). key: 'default' | 1..4 */
function scopeTotal(impact, key) {
  const v = impact?.[key]
  const n = v ? Number(v.scope_total) : NaN
  return Number.isFinite(n) ? n : null
}

/**
 * Eşik satırları + etki önizlemesi → matris.
 * @returns { rows: [{ tier(null=varsayılan), builtin, days:{critical,high,warning}, reAlert,
 *            override:{critical,high,warning,reAlert}, inherited, impact|null, row }], unclassified|null }
 */
export function buildThresholdMatrix(rows = [], impact = {}) {
  const list = Array.isArray(rows) ? rows : []
  const defRow = list.find((r) => tierOf(r) == null) || null
  const defDays = defRow ? daysOf(defRow) : DEF_DAYS
  const defReAlert = Number(defRow?.re_alert_interval_hours ?? BUILTIN.re_alert_interval_hours)
  const byTier = new Map(list.filter((r) => tierOf(r) != null && r.active !== false).map((r) => [tierOf(r), r]))

  const out = [{
    tier: null, builtin: !defRow, days: defDays, reAlert: defReAlert,
    override: { critical: false, high: false, warning: false, reAlert: false },
    inherited: false, impact: scopeTotal(impact, 'default'), row: defRow,
  }]
  for (const n of TIERS) {
    const own = byTier.get(n)
    const days = own ? daysOf(own) : defDays
    const reAlert = own ? Number(own.re_alert_interval_hours ?? defReAlert) : defReAlert
    const override = own ? {
      critical: days.critical !== defDays.critical,
      high: days.high !== defDays.high,
      warning: days.warning !== defDays.warning,
      reAlert: reAlert !== defReAlert,
    } : { critical: false, high: false, warning: false, reAlert: false }
    out.push({ tier: n, builtin: false, days, reAlert, override, inherited: !own, impact: scopeTotal(impact, n), row: own || null })
  }

  // Sınıflandırılmamış (tier'sız) alan = varsayılan kapsamı − kendi satırı OLMAYAN tier'ların toplamı (hepsi biliniyorsa).
  const freeTiers = TIERS.filter((n) => !byTier.has(n))
  const defTotal = scopeTotal(impact, 'default')
  const knownFree = freeTiers.every((n) => scopeTotal(impact, n) != null)
  const unclassified = defTotal != null && knownFree
    ? Math.max(0, defTotal - freeTiers.reduce((a, n) => a + scopeTotal(impact, n), 0))
    : null

  return { rows: out, unclassified }
}
