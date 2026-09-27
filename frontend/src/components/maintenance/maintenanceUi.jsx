import { useCallback } from 'react'
import { Globe, Plug, TextSearch, Radio, FileSearch, Gauge, Network, AtSign, ShieldCheck, FlaskConical, Repeat } from 'lucide-react'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { compressDays, daysOfWeekSet, durationMs, humanMinutes, parseIso, safeTz, wallClock } from './maintenanceSchedule.js'

/** Pencere durumu → rozet tonu (etkin yeşil, yaklaşan mavi, duraklatılmış amber, bitmiş sessiz). */
export const STATUS_TONE = { active: 'success', upcoming: 'info', scheduled: 'info', paused: 'warning', completed: 'muted' }

/** İzleme türü → ikon (hedef özetinde tür simgeleri; sayı metin olarak da yazılır — ikon ek bilgi). */
export const TYPE_ICON = {
  http: Globe, port: Plug, keyword: TextSearch, ping: Radio, page: FileSearch, pagespeed: Gauge,
  dns: Network, domain: AtSign, cert: ShieldCheck, scripted: FlaskConical,
}

/** Sayılı metin: 1 için tekil anahtar, diğerleri çoğul ("1 monitor" / "3 monitors"). */
export const countText = (t, n, oneKey, manyKey) => (Number(n) === 1 ? t(oneKey) : t(manyKey, n))

export function StatusBadge({ status, className }) {
  const t = useT()
  return (
    <ToneBadge tone={STATUS_TONE[status] || 'muted'} data-status={status}
      className={cn('rounded-full font-bold whitespace-nowrap', className)}>
      {t('mw.st.' + (STATUS_TONE[status] ? status : 'upcoming'))}
    </ToneBadge>
  )
}

/**
 * Hedef özeti: "Tüm monitörler" rozeti YA DA "N monitör" + tür simgeleri (her tür bir kez, `title` tür adı).
 * Uzun hedef listesi burada açılmaz; ayrıntı düzenleyicide.
 */
export function TargetsSummary({ w, className, iconSize = 14 }) {
  const t = useT()
  if (w.all_monitors) {
    return (
      <Badge variant="warning" data-slot="mw-all-monitors" className={cn('font-bold', className)}>{t('mw.allMonitors')}</Badge>
    )
  }
  const types = [...new Set((w.targets || []).map((x) => x.type).filter((ty) => TYPE_ICON[ty]))]
  const n = w.target_count ?? (w.targets || []).length
  return (
    <span className={cn('inline-flex min-w-0 flex-wrap items-center gap-1.5', className)}>
      <span className="whitespace-nowrap tabular-nums">{countText(t, n, 'mw.targetsOne', 'mw.targetsCount')}</span>
      {types.length > 0 && (
        <span className="inline-flex items-center gap-0.5 text-muted-foreground" data-slot="mw-target-types">
          {types.map((ty) => { const Icon = TYPE_ICON[ty]; return <Icon key={ty} size={iconSize} aria-hidden="true" title={t('mw.type.' + ty)} /> })}
        </span>
      )}
    </span>
  )
}

/** Tekrar simgesi (tekrarlayan pencerelerde ad yanında). */
export function RecurringMark({ w, className }) {
  const t = useT()
  if (!w.recurrence || w.recurrence === 'NONE') return null
  return <Repeat size={13} aria-label={t('mw.rec.' + w.recurrence)} className={cn('inline-block shrink-0 text-muted-foreground', className)} />
}

/**
 * Zamanlamayı düz sözcüklerle anlatan metin üreticileri (dil + saat dilimi farkındalıklı).
 *   sentence(w)              "Every Mon–Fri 22:00–23:00" · "Once, 28 Sep 01:00–03:00" · "Monthly on day 1, 02:00–04:00"
 *   timeOf(ms, tz)           "22:00" (pencerenin diliminde)
 *   dayOf(ms, tz)            "28 Sep" (yıl farklıysa "28 Sep 2027")
 *   dateTimeOf(ms, tz)       "28 Sep 01:00"
 *   range(startMs, endMs, tz) "01:00–03:00" ya da gün aşımında "28 Sep 23:00 – 29 Sep 01:00"
 *   minutes(n)               "1 h 30 min"
 */
export function useScheduleText() {
  const t = useT()
  const locale = useDateLocale()
  const timeOf = useCallback((ms, tz) => {
    try { return new Date(ms).toLocaleTimeString(locale, { timeZone: safeTz(tz), hour: '2-digit', minute: '2-digit', hour12: false }) }
    catch { return '' }
  }, [locale])
  const dayOf = useCallback((ms, tz) => {
    try {
      const sameYear = new Date(ms).getUTCFullYear() === new Date().getUTCFullYear()
      return new Date(ms).toLocaleDateString(locale, { timeZone: safeTz(tz), day: 'numeric', month: 'short', ...(sameYear ? {} : { year: 'numeric' }) })
    } catch { return '' }
  }, [locale])
  const dateTimeOf = useCallback((ms, tz) => `${dayOf(ms, tz)} ${timeOf(ms, tz)}`, [dayOf, timeOf])
  const minutes = useCallback((n) => humanMinutes(n, { hour: (h) => t('mw.unit.h', h), minute: (m) => t('mw.unit.min', m) }), [t])
  const range = useCallback((start, end, tz, { withDay = false } = {}) => {
    const z = safeTz(tz)
    const sameDay = wallClock(start, z).d === wallClock(end, z).d && end - start < 86_400_000
    if (sameDay) return `${withDay ? dayOf(start, z) + ' ' : ''}${timeOf(start, z)}–${timeOf(end, z)}`
    return `${dateTimeOf(start, z)} – ${dateTimeOf(end, z)}`
  }, [dayOf, timeOf, dateTimeOf])
  const dayList = useCallback((w) => {
    const days = [...daysOfWeekSet(w)]
    if (days.length === 7) return null
    return compressDays(days).map(([a, b]) => (a === b ? t('mw.dow.' + a) : b === a + 1 ? `${t('mw.dow.' + a)}, ${t('mw.dow.' + b)}` : `${t('mw.dow.' + a)}–${t('mw.dow.' + b)}`)).join(', ')
  }, [t])
  const sentence = useCallback((w) => {
    const start = parseIso(w.start_at)
    if (start == null) return ''
    const z = safeTz(w.timezone)
    const end = start + durationMs(w)
    const rec = w.recurrence || 'NONE'
    if (rec === 'NONE') return t('mw.sched.once', range(start, end, z, { withDay: true }))
    const tod = `${timeOf(start, z)}–${timeOf(end, z)}`
    const spill = wallClock(start, z).d !== wallClock(end, z).d ? ` (${t('mw.sched.nextDay')})` : ''
    if (rec === 'DAILY') return t('mw.sched.daily', tod) + spill
    if (rec === 'WEEKLY') {
      const list = dayList(w)
      return (list ? t('mw.sched.weekly', list, tod) : t('mw.sched.daily', tod)) + spill
    }
    if (rec === 'MONTHLY') return t('mw.sched.monthly', Number(w.day_of_month) >= 1 ? Number(w.day_of_month) : 1, tod) + spill
    return tod
  }, [t, range, timeOf, dayList])
  return { sentence, timeOf, dayOf, dateTimeOf, range, minutes }
}
