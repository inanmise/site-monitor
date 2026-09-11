import { Tag, Rocket, RotateCcw, Undo2, Power, PenLine, History, Zap, Sparkles, Wrench, HelpCircle } from 'lucide-react'

/**
 * Sürüm & dağıtım yüzeylerinin ORTAK sözlüğü (Nav çipi, Yardım → Yenilikler, Sistem Sağlığı bölümü).
 * Tek yerde durur ki bir tür (UPGRADE/ROLLBACK/…) üç ekranda üç farklı ikon/tonla çizilmesin.
 * Saf modül: React yok, i18n yok — testlenebilir.
 */

/** Dağıtım türü → VersionTimeline stili (ikon + ton). Tonlar sc-vt-dot--* sınıflarıyla eşleşir. */
export const DEPLOY_KIND_STYLE = {
  FIRST_SEEN: { icon: Rocket,    tone: 'up' },
  UPGRADE:    { icon: Rocket,    tone: 'up' },
  RESTART:    { icon: RotateCcw, tone: 'edit' },
  ROLLBACK:   { icon: Undo2,     tone: 'danger' },
  CHANGED:    { icon: Rocket,    tone: 'down' },
  UNKNOWN:    { icon: History,   tone: 'muted' },
  SHUTDOWN:   { icon: Power,     tone: 'edit' },
  MANUAL:     { icon: PenLine,   tone: 'edit' },
  BACKFILL:   { icon: History,   tone: 'muted' },
  RELEASE:    { icon: Tag,       tone: 'new' },
}

/** Yayın bump türü → ikon. */
export const BUMP_ICON = { major: Zap, minor: Sparkles, patch: Wrench, initial: Tag }
export function bumpIcon(bump) { return BUMP_ICON[bump] ?? HelpCircle }

/** Saniye → "3g 4s 12d" (TR) / "3d 4h 12m" (EN). `units` = {d,h,m}; 60 sn altı → justNow. */
export function fmtDuration(seconds, units, justNow = '') {
  const s = Number(seconds)
  if (!Number.isFinite(s) || s < 0) return ''
  if (s < 60) return justNow
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  const parts = []
  if (d) parts.push(`${d}${units.d}`)
  if (h) parts.push(`${h}${units.h}`)
  if (m && !d) parts.push(`${m}${units.m}`)
  return parts.join(' ') || `${m}${units.m}`
}

/** Değişiklik türü → gruplama anahtarı (feat / fix / other). */
export function changeGroup(type) {
  if (type === 'feat') return 'feat'
  if (type === 'fix') return 'fix'
  return 'other'
}

/** Değişiklikleri feat → fix → other sırasında gruplar; her grup en fazla `limit` (kalanı `more`). */
export function groupChanges(changes = [], limit = Infinity) {
  const groups = { feat: [], fix: [], other: [] }
  for (const c of changes) groups[changeGroup(c?.type)].push(c)
  return ['feat', 'fix', 'other']
    .filter(k => groups[k].length)
    .map(k => ({ key: k, items: groups[k].slice(0, limit), more: Math.max(0, groups[k].length - limit) }))
}

/** Kısa commit (8 hex). */
export function shortSha(sha) { return typeof sha === 'string' ? sha.slice(0, 8) : '' }

/** Son görülen sürüm damgası (E1) — localStorage güvenli okuma/yazma. */
export const LAST_SEEN_KEY = 'sm.release.lastSeenVersion'
export function readLastSeenVersion() {
  try { return localStorage.getItem(LAST_SEEN_KEY) || '' } catch { return '' }
}
export function writeLastSeenVersion(v) {
  if (!v) return
  try { localStorage.setItem(LAST_SEEN_KEY, v) } catch { /* private mode / kapalı depolama */ }
}
/** Nokta yalnız GERÇEKTEN yeni sürümde: ilk ziyarette karşılaştırılacak damga yok → sessizce yazılır. */
export function isNewVersion(seen, current) {
  return !!current && !!seen && seen !== current
}
