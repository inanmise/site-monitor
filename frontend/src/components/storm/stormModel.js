// Alarm fırtınası gözlem ekranı — saf yardımcılar (2026-09-30). Sayfa ve Ayarlar paneli buradan okur; birim testi kolay.
import { parseUtc } from '../../utils/incidentMeta.js'

/** Takım durumu → rozet tonu + etiket anahtarı (sunucu: StormStatusService.STATUS_*). */
export const STATUS_META = {
  STORM: { key: 'sf.status.STORM', tone: 'critical', order: 0 },
  NEAR:  { key: 'sf.status.NEAR',  tone: 'warning',  order: 1 },
  WATCH: { key: 'sf.status.WATCH', tone: 'info',     order: 2 },
  CALM:  { key: 'sf.status.CALM',  tone: 'ok',       order: 3 },
}

/** Kapanış nedeni kodları (alert_storms.resolve_reason) → etiket anahtarı. */
export const REASON_KEYS = {
  FLOOR: 'sf.reason.FLOOR',
  SEALED: 'sf.reason.SEALED',
  DISABLED: 'sf.reason.DISABLED',
  LEGACY_RETIRE: 'sf.reason.LEGACY_RETIRE',
  OPEN: 'sf.reason.OPEN',
  UNKNOWN: 'sf.reason.UNKNOWN',
}

export const JOIN_KEYS = { TRIGGER: 'sf.member.join.TRIGGER', PEER: 'sf.member.join.PEER', ATTACH: 'sf.member.join.ATTACH', LEGACY: 'sf.member.join.LEGACY' }
export const LEAVE_KEYS = { RECOVERED: 'sf.member.leave.RECOVERED', NOTIFIED: 'sf.member.leave.NOTIFIED', UNLINKED: 'sf.member.leave.UNLINKED', MOVED: 'sf.member.leave.MOVED', RELEASED: 'sf.member.leave.RELEASED' }

export const ANALYTICS_DAYS = [7, 30, 90]

/** Kapanış nedeni etiketi: açık fırtına → OPEN; bilinmeyen kod → UNKNOWN. */
export function reasonKey(storm) {
  if (!storm) return REASON_KEYS.UNKNOWN
  if (!storm.resolved) return REASON_KEYS.OPEN
  return REASON_KEYS[storm.resolve_reason] || REASON_KEYS.UNKNOWN
}

/** Durum sırası: fırtınalı takımlar üstte, sonra eşiğe yakın, izlenen, sakin; eşitte ada göre. */
export function sortTeams(teams) {
  return [...(teams || [])].sort((a, b) => {
    const oa = STATUS_META[a.status]?.order ?? 9, ob = STATUS_META[b.status]?.order ?? 9
    if (oa !== ob) return oa - ob
    if ((b.window_targets || 0) !== (a.window_targets || 0)) return (b.window_targets || 0) - (a.window_targets || 0)
    return String(a.team_name || '').localeCompare(String(b.team_name || ''), 'tr')
  })
}

/** Pencere doluluk yüzdesi (0–100): hedef / eşik. Eşik 0 → 0. */
export function windowPct(team) {
  const th = Number(team?.threshold) || 0
  if (th <= 0) return 0
  return Math.max(0, Math.min(100, Math.round(((Number(team?.window_targets) || 0) / th) * 100)))
}

/** Mühüre kalan süre (ms, negatif = doldu). `seal_at` UTC ISO (dilimsiz). */
export function sealRemainingMs(storm, nowMs = Date.now()) {
  const at = parseUtc(storm?.seal_at)
  if (!at) return null
  return at.getTime() - nowMs
}

/** Kısa "N dk / N sa" (i18n `t('sf.min', n)` / `t('sf.hour', n)`), sıfırın altı "0 dk". */
export function formatShortDuration(ms, t) {
  const m = Math.max(0, Math.round((ms || 0) / 60000))
  if (m < 60) return t('sf.min', m)
  const h = Math.floor(m / 60), r = m % 60
  return r ? `${t('sf.hour', h)} ${t('sf.min', r)}` : t('sf.hour', h)
}

/** Analiz serisi → grafik satırları: {day, total, t<id>...}; takım anahtarı listesi + renk yapılandırması. */
export function chartRows(analytics) {
  const series = analytics?.series || []
  const teams = (analytics?.teams || []).slice(0, 5)   // en çok fırtınalı 5 takım ayrı seri, kalanı "diğer"
  const known = new Set(teams.map((x) => String(x.team_id ?? 'null')))
  const keys = teams.map((x) => ({ key: `t${x.team_id ?? 'null'}`, id: x.team_id, name: x.team_name }))
  const rows = series.map((s) => {
    const row = { day: s.day, total: s.total || 0, other: 0 }
    for (const k of keys) row[k.key] = 0
    for (const [id, n] of Object.entries(s.by_team || {})) {
      if (known.has(id)) row[`t${id}`] += n
      else row.other += n
    }
    return row
  })
  const hasOther = rows.some((r) => r.other > 0)
  return { rows, keys, hasOther }
}

/** Fırtına ayrıntısı → zaman çizelgesi olayları (sıralı). */
export function stormTimeline(storm, t) {
  if (!storm) return []
  const ev = []
  ev.push({ kind: 'opened', at: storm.created_at, text: t('sf.tl.opened', storm.targets_at_open ?? storm.member_count ?? 0, storm.threshold_effective ?? '—') })
  if (storm.trigger?.domain) ev.push({ kind: 'trigger', at: storm.trigger.created_at || storm.created_at, text: t('sf.tl.trigger', storm.trigger.domain, storm.trigger.alert_type || '') })
  if (storm.last_re_alert_at && storm.last_re_alert_at !== storm.created_at) ev.push({ kind: 'mail', at: storm.last_re_alert_at, text: t('sf.tl.lastMail') })
  if (storm.last_member_at && storm.last_member_at !== storm.created_at) ev.push({ kind: 'member', at: storm.last_member_at, text: t('sf.tl.lastMember') })
  if (!storm.resolved && storm.seal_at) ev.push({ kind: storm.sealed ? 'sealed' : 'seal', at: storm.seal_at, text: t('sf.tl.sealAt', storm.quiet_minutes ?? '—'), future: !storm.sealed })
  if (storm.resolved && storm.resolved_at) ev.push({ kind: 'resolved', at: storm.resolved_at, text: t('sf.tl.resolved', t(reasonKey(storm))) })
  return ev.sort((a, b) => String(a.at || '').localeCompare(String(b.at || '')))
}

/** URL/sunucu tarih girdisi (yyyy-MM-dd) doğrulaması. */
export function isDay(s) { return typeof s === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s) }
