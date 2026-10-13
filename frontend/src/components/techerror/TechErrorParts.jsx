import { Badge } from '@/components/shadcn/badge'
import { useT } from '../../i18n/index.jsx'
import { cn } from '@/lib/utils'
import { lastAttemptMeta, monthName, statusMeta } from './techErrorModel.js'

const TONE_CLASS = {
  success: 'bg-green-600/10 text-green-800 dark:bg-green-500/15 dark:text-green-300',
  warning: 'bg-amber-500/15 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300',
  info: 'bg-primary/10 text-primary',
  danger: 'bg-destructive/10 text-destructive',
  muted: 'bg-muted text-muted-foreground',
}

/** "Eylül 2026" — arayüz diline göre (rapor dili değil). */
export function periodLabel(period, t) {
  if (!period?.year || !period?.month) return ''
  return `${t(`te.month.${period.month}`)} ${period.year}`
}

/** Sunucu ay adı ile aynı (PDF dili) — şablon diline göre. */
export function periodLabelIn(period, lang) {
  if (!period?.year || !period?.month) return ''
  return `${monthName(period.month, lang)} ${period.year}`
}

/**
 * Rapor durumu rozeti — durum metinle de yazılır (renk tek başına taşımaz). Durum yoksa "Başlanmadı" (yeşil DEĞİL).
 * Son deneme başarısızsa / kuyruktaysa ikinci rozet.
 */
export function StatusBadge({ status, sendState = null, prefix = null, className }) {
  const t = useT()
  const meta = status ? statusMeta(status) : { tone: 'muted', key: 'te.status.notStarted' }
  const attempt = lastAttemptMeta(sendState)
  return (
    <span className={cn('inline-flex flex-wrap items-center gap-1.5', className)} data-slot="te-status" data-status={status || 'none'}>
      <Badge className={cn('rounded-md overflow-visible whitespace-normal [overflow-wrap:anywhere]', TONE_CLASS[meta.tone])}>
        {prefix ? `${prefix} · ${t(meta.key)}` : t(meta.key)}
      </Badge>
      {attempt && (
        <Badge className={cn('rounded-md overflow-visible whitespace-normal', TONE_CLASS[attempt.tone])} data-slot="te-attempt">
          {t(attempt.key)}
        </Badge>
      )}
    </span>
  )
}

/** Yerel saatle kısa tarih-saat (dd.MM.yyyy HH:mm). */
export function fmtDateTime(iso, lang) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return new Intl.DateTimeFormat(lang === 'en' ? 'en-GB' : 'tr-TR', {
    day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Istanbul',
  }).format(d)
}
