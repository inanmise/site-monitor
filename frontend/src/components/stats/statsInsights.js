/**
 * İstatistikler — zenginleştirme modeli (2026-11-13, saf). Güvenlik duruşu, sağlayıcı / katman dağılımı, 12 haftalık bitiş
 * çubuğu, takım karnesi, izleme türü özeti, alarm eğilimi ve sayfaya özel yüzde biçimi. Bileşenler yalnız çizer; testler
 * burayı doğrudan sınar.
 *
 * Kural: bilinmeyen ≠ 0 ≠ iyi. Okunamayan kaynağın alanı `null` kalır ve "—" çizilir; notsuz sertifika "iyi" sayılmaz.
 */
import { dateLocale, formatPercent } from '../../i18n/dateLocale.js'
import { TLS_GRADES } from '../tlsgrade/tlsGradeCodes.js'
import { TIERS, bucketOf, gradeOf, inWeek, issuerOf, secFlagsOf, SEC_FLAGS } from './statsModel.js'

/**
 * Sayfaya özel yüzde (2026-11-13): YEREL ondalık ayırıcı, en çok bir ondalık — TR "%97,5", EN "97.5%". `formatPercent`'e
 * dokunulmadı (bütün uygulama onu kullanıyor; "%97.5" yazıyordu). Sayı değilse tire.
 */
export function statsPct(v, dash = '—') {
  if (v === null || v === undefined || v === '' || !Number.isFinite(Number(v))) return dash
  return formatPercent(Number(v).toLocaleString(dateLocale(), { maximumFractionDigits: 1 }))
}

/** Tam sayı (yerel binlik ayırıcı); sayı değilse tire. */
export function statsNum(v, dash = '—') {
  if (v === null || v === undefined || v === '' || !Number.isFinite(Number(v))) return dash
  return Number(v).toLocaleString(dateLocale())
}

// ── Güvenlik duruşu ───────────────────────────────────────────────────────────────────────────

/**
 * Not dağılımı + bayrak sayıları. `graded` = notu olan; `none` = notsuz (elle yüklenen, hiç kontrol edilmemiş, son
 * kontrolü başarısız) — ayrı sayılır, asla iyi değildir.
 */
export function securityCounts(certs) {
  const grades = Object.fromEntries([...TLS_GRADES, 'none'].map((g) => [g, 0]))
  const flags = Object.fromEntries(SEC_FLAGS.map((f) => [f, 0]))
  let flagged = 0
  for (const c of certs || []) {
    grades[gradeOf(c)]++
    const fs = secFlagsOf(c)
    if (fs.length) flagged++
    for (const f of fs) flags[f]++
  }
  const graded = TLS_GRADES.reduce((s, g) => s + grades[g], 0)
  const weakGrades = (grades.C || 0) + (grades.D || 0) + (grades.F || 0)
  return { grades, flags, flagged, graded, ungraded: grades.none, weakGrades, total: (certs || []).length }
}

// ── Sağlayıcı ve katman dağılımı ──────────────────────────────────────────────────────────────

/** En çok `top` sağlayıcı (sayı çoktan aza, eşitlikte ad); kalanı `other`; adı okunamayan `unknown` (hata satırları). */
export function caDistribution(certs, top = 8) {
  const counts = new Map()
  let unknown = 0
  for (const c of certs || []) {
    const i = issuerOf(c)
    if (!i) { unknown++; continue }
    counts.set(i, (counts.get(i) || 0) + 1)
  }
  const all = [...counts.entries()].map(([name, count]) => ({ name, count }))
    .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name))
  const head = all.slice(0, top)
  const other = all.slice(top).reduce((s, x) => s + x.count, 0)
  return { rows: head, other, otherNames: all.length - head.length, unknown, total: (certs || []).length }
}

/** Katman sayıları (`TIERS` sırası; 0 = sınıflandırılmamış). */
export function tierDistribution(certs) {
  const counts = Object.fromEntries(TIERS.map((t) => [t, 0]))
  for (const c of certs || []) {
    const t = c?.tier == null ? 0 : Number(c.tier)
    if (counts[t] != null) counts[t]++
    else counts[0]++
  }
  return TIERS.map((tier) => ({ tier, count: counts[tier] }))
}

// ── 12 haftalık bitiş çubuğu ────────────────────────────────────────────────────────────────────

/** Hafta başına dolacak sertifika sayısı + en yakın kalan gün (çubuk tonu); pencereler `weekWindows`. */
export function weekHistogram(certs, windows) {
  return (windows || []).map((w) => {
    const hit = (certs || []).filter((c) => inWeek(c, w))
    const minDays = hit.reduce((m, c) => Math.min(m, c.days_remaining), Infinity)
    return { ...w, count: hit.length, minDays: Number.isFinite(minDays) ? minDays : null }
  })
}

/** Çubuk tonu: bu hafta / gelecek hafta kritik, 4 hafta içi uyarı, sonrası nötr. */
export function weekTone(index) {
  if (index <= 1) return 'crit'
  if (index <= 3) return 'warn'
  return 'info'
}

// ── Takım karnesi ─────────────────────────────────────────────────────────────────────────────

const idKey = (v) => (v == null ? null : String(v))

/**
 * Takım karnesi satırları — her takım için bilinen parçalar; okunamayan / izinsiz kaynak `null` (çizimde "—").
 *  - sertifika: İSTEMCİ sayımı, tablonun takım süzgeciyle AYNI kural (SY ∪ UG ∩ yüklü sertifikalar; `bucketOf`):
 *    `ok`, `under30` (0–30 gün), `expired`, `error`, `unchecked`; sağlık % = temiz / değerlendirilen.
 *  - alarm: `/api/admin/alerts/team-stats` (tüm türler; `open`, `last30`) — `alerts` null ise bilinmiyor.
 *  - SLA: yönetici özetinin `sla.by_team` (ihlal / monitör) — `sla` null ise bilinmiyor.
 *  - veri kalitesi: `/api/data-quality` takım puanı — `dq` null ise bilinmiyor.
 * Takım kümesi: kaynaklardaki tüm takımlar (takımsız satır `none` yalnız sertifika parçasından).
 */
export function teamScorecard({ teams = [], certs = [], alerts = null, sla = null, dq = null }) {
  const byDomain = new Map((certs || []).map((c) => [c.domain, c]))
  const rows = new Map()
  const row = (id, name) => {
    const k = idKey(id)
    if (!rows.has(k)) rows.set(k, { id, name: name || null, certs: null, alerts: null, sla: null, dq: null })
    const r = rows.get(k)
    if (!r.name && name) r.name = name
    return r
  }
  for (const tm of teams || []) {
    let ok = 0, under30 = 0, expired = 0, error = 0, unchecked = 0, total = 0
    for (const d of tm.domains || []) {
      const c = byDomain.get(d)
      if (!c) continue
      total++
      const b = bucketOf(c)
      if (b === 'ok') ok++
      else if (b === 'expired') expired++
      else if (b === 'error') error++
      else if (b === 'unknown') unchecked++
      else under30++
    }
    if (total === 0) continue
    const evaluated = total - unchecked
    const r = row(tm.id, tm.teamless ? null : tm.name)
    r.teamless = !!tm.teamless
    r.certs = { total, ok, under30, expired, error, unchecked, evaluated, health: evaluated > 0 ? Math.round((ok * 1000) / evaluated) / 10 : null }
  }
  if (alerts) {
    for (const a of alerts.teams || []) {
      if (a?.team_id == null) continue
      row(a.team_id, a.team_name).alerts = { open: Number(a.open ?? 0), last30: Number(a.last30 ?? 0) }
    }
  }
  if (sla) {
    for (const s of sla.by_team || []) {
      if (s?.team_id == null) continue
      row(s.team_id, null).sla = { breaches: Number(s.breaches ?? 0), monitors: Number(s.monitors ?? 0) }
    }
  }
  if (dq) {
    for (const t of dq.teams || []) {
      if (t?.id == null) continue
      row(t.id, t.name).dq = { score: t.score ?? null, band: t.band ?? null }
    }
  }
  // Bir kaynak OKUNDUYSA, o kaynakta görünmeyen takımın değeri gerçek sıfırdır (alarm / ihlal yok) — bilinmiyor değil.
  for (const r of rows.values()) {
    if (alerts && r.alerts == null && !r.teamless) r.alerts = { open: 0, last30: 0 }
    if (sla && r.sla == null && !r.teamless) r.sla = { breaches: 0, monitors: 0 }
  }
  return [...rows.values()]
}

/** Karne sıralaması: anahtar + yön; bilinmeyen (null) her yönde SONA. */
export const SCORE_SORTS = ['health', 'name', 'under30', 'problems', 'alerts', 'sla', 'dq']
export function scoreValue(r, key) {
  switch (key) {
    case 'name': return r.name ? r.name.toLocaleLowerCase('tr') : null
    case 'health': return r.certs?.health ?? null
    case 'under30': return r.certs ? r.certs.under30 : null
    case 'problems': return r.certs ? r.certs.expired + r.certs.error : null
    case 'alerts': return r.alerts ? r.alerts.open : null
    case 'sla': return r.sla ? r.sla.breaches : null
    case 'dq': return r.dq?.score ?? null
    default: return null
  }
}
export function sortScorecard(rows, key = 'health', dir = 'asc') {
  const sign = dir === 'desc' ? -1 : 1
  return [...(rows || [])].sort((a, b) => {
    const x = scoreValue(a, key), y = scoreValue(b, key)
    if (x == null || y == null) {
      if (x == null && y == null) return String(a.name || '').localeCompare(String(b.name || ''), 'tr')
      return x == null ? 1 : -1
    }
    const c = typeof x === 'string' ? x.localeCompare(y, 'tr') : x - y
    return sign * c || String(a.name || '').localeCompare(String(b.name || ''), 'tr')
  })
}

/** Sağlık tonu — yönetici özetiyle aynı eşikler (≥ 95 iyi, ≥ 90 uyarı). Bilinmiyor → nötr. */
export function healthTone(v) {
  if (v == null) return 'neutral'
  return v >= 95 ? 'ok' : v >= 90 ? 'warn' : 'bad'
}

// ── Alarm eğilimi ───────────────────────────────────────────────────────────────────────────────

/** Gürültü yanıtından 30 günlük seri + MTTR; yanıt yoksa null. */
export function alarmTrend(noise) {
  if (!noise || !Array.isArray(noise.series)) return null
  const series = noise.series.map((p) => ({ date: p.date, count: Number(p.count) || 0 }))
  return {
    series,
    total: Number(noise.total ?? series.reduce((s, p) => s + p.count, 0)),
    critical: noise.critical ?? null,
    mttr: noise.mttr_minutes ?? null,
    resolvedPct: noise.resolved_pct ?? null,
    peak: series.reduce((m, p) => Math.max(m, p.count), 0),
  }
}

/** MTTR dakika → "45 dk" / "2,5 sa" / "1,2 gün" (yerel ondalık); null → tire. */
export function formatMinutes(min, t) {
  if (min == null || !Number.isFinite(Number(min))) return '—'
  const m = Number(min)
  const loc = dateLocale()
  if (m < 90) return t('stx.unit.min', Math.round(m).toLocaleString(loc))
  if (m < 48 * 60) return t('stx.unit.hour', (m / 60).toLocaleString(loc, { maximumFractionDigits: 1 }))
  return t('stx.unit.day', (m / 1440).toLocaleString(loc, { maximumFractionDigits: 1 }))
}

// ── İzleme türü özeti ───────────────────────────────────────────────────────────────────────────

/**
 * İzleme Panosu yanıtından tür kartları — yalnız izlemesi olan (ya da okunamayan) türler; ton İzleme Panosu kuralı
 * (`typeTone`) çağıranda. Başarı oranı okunamadıysa null.
 */
export function monitorTypeRows(overview, order) {
  const types = Array.isArray(overview?.types) ? overview.types : []
  const byType = new Map(types.map((ty) => [ty.type, ty]))
  return (order || []).map((k) => byType.get(k)).filter((ty) => ty && (ty.unavailable === true
    || Number(ty.active || 0) + Number(ty.paused || 0) > 0))
}

// ── Tazelik ─────────────────────────────────────────────────────────────────────────────────────

/** Sayfanın "son güncelleme" anı: parçaların EN ESKİSİ (tazeliği abartmaz); hiçbiri yoksa null. */
export function freshestCommon(...isoOrMs) {
  const ms = isoOrMs.map((v) => (v == null ? null : typeof v === 'number' ? v : Date.parse(/[zZ]|[+-]\d\d:?\d\d$/.test(String(v)) ? v : `${v}Z`)))
    .filter((v) => Number.isFinite(v))
  return ms.length ? Math.min(...ms) : null
}
