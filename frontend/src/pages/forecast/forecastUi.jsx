import { useLayoutEffect, useState } from 'react'
import { AlertTriangle, CalendarCheck, CheckCircle2, ShieldOff } from 'lucide-react'
import { formatDateOnly } from '../../api/client'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { expiredAgoText } from '../../utils/dayPhrases.js'
import { classify, windowState, expiryKey, dayDiff, addDays } from '../forecastModel.js'

/**
 * Vade Takvimi'nin ORTAK küçük parçaları (2026-09-27 yeniden tasarım): aciliyet rozeti, tier rozeti, plan
 * rozeti, göreli gün metni, kutucuk (tile) süzgeç eşleşmesi ve ufuk (horizon) kovaları. React durumu yok;
 * sayfa ve alt bileşenler (liste, gün paneli, içgörüler, alan adı paneli) aynı sözlüğü paylaşsın diye burada.
 *
 * Test kancaları: aciliyet rozeti `data-slot="fc-cls"` + `data-cls`, plan rozeti `data-slot="fc-plan"` + `data-state`.
 */
export const CLS_TONE = {
  overdue: 'bg-destructive/10 text-destructive dark:bg-destructive/20',
  unreachable: 'bg-destructive/10 text-destructive dark:bg-destructive/20',
  critical: 'bg-red-500/15 text-red-700 dark:text-red-300',
  high: 'bg-orange-500/15 text-orange-700 dark:text-orange-300',
  warning: 'bg-amber-500/15 text-amber-800 dark:text-amber-300',
  later: 'bg-blue-500/15 text-blue-700 dark:text-blue-300',
}
/** Aciliyet mürekkebi (göreli gün metni, kalan gün sayısı). */
export const CLS_INK = {
  overdue: 'text-destructive', unreachable: 'text-destructive', critical: 'text-red-600 dark:text-red-400',
  high: 'text-orange-600 dark:text-orange-400', warning: 'text-amber-700 dark:text-amber-400', later: 'text-muted-foreground',
}
/** Takvim olay tonu (ui/MonthCalendar sözleşmesi: ok | warn | bad | info). */
export function calTone(cls) {
  if (cls === 'overdue' || cls === 'unreachable' || cls === 'critical') return 'bad'
  if (cls === 'high' || cls === 'warning') return 'warn'
  return 'info'
}

export function ClsBadge({ cls, t, className }) {
  const Icon = cls === 'unreachable' ? ShieldOff : cls === 'overdue' ? AlertTriangle : null
  return (
    <Badge variant="secondary" data-slot="fc-cls" data-cls={cls} className={cn('gap-1 font-semibold', CLS_TONE[cls] ?? CLS_TONE.later, className)}>
      {Icon && <Icon aria-hidden="true" />}{t(`forecast.cls.${cls}`)}
    </Badge>
  )
}

const TIER_TONE = { 1: 'bg-indigo-600 text-white', 2: 'bg-sky-600 text-white', 3: 'bg-cyan-600 text-white', 4: 'bg-zinc-500 text-white' }
export function TierBadge({ tier }) {
  return tier ? <Badge data-slot="fc-tier" className={cn('rounded px-1.5 text-[11px] font-extrabold', TIER_TONE[tier] ?? TIER_TONE[4])}>T{tier}</Badge> : null
}

/** Plan rozeti: planlı (tarih, not `title`'da ve isteğe bağlı satır içi) · yenilendi. */
export function PlanBadge({ row, t, className }) {
  if (row.renewal_plan_state === 'planned') {
    return (
      <Badge variant="secondary" data-slot="fc-plan" data-state="planned" title={row.renewal_planned_note || undefined}
        className={cn('gap-1 bg-violet-500/15 text-violet-800 dark:text-violet-300', className)}>
        <CalendarCheck aria-hidden="true" />{t('forecast.planned', formatDateOnly(row.renewal_planned_at))}
      </Badge>
    )
  }
  if (row.renewal_plan_state === 'done') {
    return (
      <Badge variant="secondary" data-slot="fc-plan" data-state="done" className={cn('gap-1 bg-success/15 text-success dark:bg-success/20', className)}>
        <CheckCircle2 aria-hidden="true" />{t('forecast.planDone')}
      </Badge>
    )
  }
  return null
}

/** "12 gün içinde" / "bugün doluyor" / "3 gün önce doldu" / "—". */
export function relativeDays(days, t) {
  if (days == null) return '—'
  if (days < 0) return expiredAgoText(t, -days)
  if (days === 0) return t('forecast.dueToday')
  return days === 1 ? t('inv.expiresInOne') : t('forecast.inDays', days)
}

/** KPI kutucukları — süzgeç anahtarları (URL `f_urg`). */
export const TILE_KEYS = ['overdue', 'critical', 'high', 'warning', 'late', 'planned']
export function matchesTile(c, key, th, today) {
  const cls = classify(c, th)
  switch (key) {
    case 'overdue': return cls === 'overdue' || cls === 'unreachable'
    case 'critical': case 'high': case 'warning': return cls === key
    case 'late': return windowState(c, today) === 'late'
    case 'planned': return c.renewal_plan_state === 'planned'
    default: return true
  }
}

const HORIZON_SERIES = ['critical', 'high', 'warning', 'later']
/**
 * Ufuk kovaları: 90/180 gün → haftalık, 365 gün → aylık. Her kova aciliyet sınıfına göre sayılır (dolmuş /
 * erişilemeyen kovaya girmez — ileride bir bitiş tarihi yok). `from`/`to` YYYY-MM-DD (kapalı aralık).
 */
export function horizonBuckets(certs, th, range, today, locale = 'en-GB', t) {
  const buckets = []
  if (range >= 365) {
    const base = new Date(today + 'T00:00:00')
    for (let i = 0; i < 12; i++) {
      const first = new Date(base.getFullYear(), base.getMonth() + i, 1)
      const last = new Date(base.getFullYear(), base.getMonth() + i + 1, 0)
      const from = i === 0 ? today : ymd(first)
      const label = first.toLocaleDateString(locale, { month: 'short', year: '2-digit' })
      buckets.push({ key: from, from, to: ymd(last), label, title: first.toLocaleDateString(locale, { month: 'long', year: 'numeric' }) })
    }
  } else {
    const weeks = Math.ceil(range / 7)
    for (let i = 0; i < weeks; i++) {
      const from = addDays(today, i * 7)
      const to = addDays(today, Math.min(i * 7 + 6, range - 1))
      const label = new Date(from + 'T00:00:00').toLocaleDateString(locale, { day: '2-digit', month: 'short' })
      buckets.push({ key: from, from, to, label, title: t ? t('forecast.horizonWeek', formatDateOnly(from)) : label })
    }
  }
  for (const b of buckets) { for (const s of HORIZON_SERIES) b[s] = 0; b.total = 0 }
  const last = buckets[buckets.length - 1]
  for (const c of certs) {
    const key = expiryKey(c); if (!key) continue
    const diff = dayDiff(today, key); if (diff < 0 || key > last.to) continue
    const cls = classify(c, th); if (!HORIZON_SERIES.includes(cls)) continue
    const b = buckets.find((x) => key >= x.from && key <= x.to); if (!b) continue
    b[cls]++; b.total++
  }
  return buckets
}

function ymd(d) { return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }

/**
 * Kabın GERÇEK genişliği (px) — `[width, setEl]`; `ref={setEl}` ile bağlanır (öğe sonradan mount olsa da ölçülür).
 * Neden: tablette (768) kenar çubuğu açıkken içerik ~440 px kalıyor; görünüm alanı kırılma noktası (`useIsMobile`)
 * tabloyu seçiyor ve kullanıcı eylem sütununa yatay kaydırarak ulaşıyordu. Liste kart/tablo kararını kabın
 * genişliğiyle verir. jsdom'da genişlik 0 = bilinmiyor (çağıran görünüm alanı kararına düşer).
 */
export function useElementWidth() {
  const [el, setEl] = useState(null)
  const [width, setWidth] = useState(0)
  useLayoutEffect(() => {
    if (!el) return undefined
    const measure = () => setWidth(Math.round(el.getBoundingClientRect().width))
    measure()
    if (typeof ResizeObserver === 'undefined') return undefined
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [el])
  return [width, setEl]
}

/** Liste sıralaması: aciliyet (varsayılan sıra = upcoming()), bitiş, alan adı, takım, veren. */
export function sortRows(rows, { key, dir }) {
  if (!key || key === 'urgency') return dir === 'desc' ? [...rows].reverse() : rows
  const cmp = {
    expiry: (a, b) => (a.days_remaining ?? 99999) - (b.days_remaining ?? 99999),
    domain: (a, b) => a.domain.localeCompare(b.domain),
    team: (a, b) => (a.team_name || '￿').localeCompare(b.team_name || '￿'),
    issuer: (a, b) => (a.issuer_cn || '￿').localeCompare(b.issuer_cn || '￿'),
  }[key]
  if (!cmp) return rows
  const out = [...rows].sort(cmp)
  return dir === 'desc' ? out.reverse() : out
}
