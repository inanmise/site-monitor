import { useEffect, useRef, useState } from 'react'
import { Check, Copy } from 'lucide-react'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { copyText } from '../../../utils/copyText.js'
import UserBadge from '../../ui/UserBadge.jsx'
import SimpleTooltip from '../../ui/SimpleTooltip.jsx'
import { SystemRoleBadge } from '../ToneBadge.jsx'
import { eventDate, relativeTime } from './auditFormat.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Denetim Logu'nun küçük ortak parçaları — tablo, telefon kartı ve ayrıntı AYNI dili konuşsun.
 */

/** Sunucu damgası → yerel kesin zaman ("26/09/2026, 17:01:22"). Bozuksa ham dize. */
export function formatExact(iso, locale, { seconds = false } = {}) {
  const d = eventDate(iso)
  if (!d) return iso || '—'
  return d.toLocaleString(locale, {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    ...(seconds ? { second: '2-digit' } : {}),
  })
}

/** Kesin zamanı biçimleyen kanca (dil değişince yeniden çizilir). */
export function useExactTime() {
  const locale = useDateLocale()
  return (iso, opts) => formatExact(iso, locale, opts)
}

/**
 * Göreli zaman ("12 dk önce") + kesin zaman ipucu. Anlamsal `<time dateTime>` (UTC ISO). `stacked`: kesin
 * zaman altta GÖRÜNÜR (telefon/ayrıntı — dokunmatikte ipucu açılmaz, bilgi yalnız ipucunda kalamaz).
 */
export function AuditTime({ iso, stacked = false, className }) {
  const t = useT()
  const exact = useExactTime()
  const d = eventDate(iso)
  if (!d) return <span className={cn('text-muted-foreground', className)}>—</span>
  const rel = relativeTime(iso, t)
  const full = exact(iso, { seconds: true })
  if (stacked) {
    return (
      <time dateTime={d.toISOString()} className={cn('flex flex-col leading-tight', className)}>
        <span className="font-medium">{rel}</span>
        <span className="text-xs text-muted-foreground tabular-nums">{full}</span>
      </time>
    )
  }
  return (
    <SimpleTooltip content={full}>
      <time dateTime={d.toISOString()} className={cn('whitespace-nowrap tabular-nums', className)}>{rel}</time>
    </SimpleTooltip>
  )
}

/**
 * Anomali çipleri (shadcn Badge). Renk jetondan (`--anomaly-*`, App.css; koyu temada ayrı değer) — satır-içi hex
 * `cssTokens` kapısına görünmüyordu. Etiket `dev.flag.*` çevirisi; bilinmeyen bayrak okunur biçime düşer.
 * Test kancası: `data-flag`.
 */
const ANOMALY_BG = {
  off_hours: 'bg-(--anomaly-warn)', unusual_ip: 'bg-(--anomaly-info)', geo_velocity: 'bg-(--anomaly-danger)',
  brute_force: 'bg-(--anomaly-critical)', rate_limited: 'bg-(--anomaly-muted)', replayed: 'bg-(--anomaly-muted)',
}
export function anomalyLabel(flag, t) {
  const key = `dev.flag.${flag}`
  const label = t(key)
  return label === key ? flag.replace(/_/g, ' ') : label
}
export function AnomalyChips({ flags, className }) {
  const t = useT()
  if (!flags) return null
  const list = String(flags).split(',').map(f => f.trim()).filter(Boolean)
  if (!list.length) return null
  return (
    <span className={cn('flex flex-wrap gap-1', className)}>
      {list.map(flag => (
        <Badge key={flag} data-flag={flag.toLowerCase()} title={flag}
          className={cn('rounded-full px-1.5 text-[0.7rem] font-semibold text-white', ANOMALY_BG[flag.toLowerCase()] || 'bg-(--anomaly-muted)')}>
          {anomalyLabel(flag, t)}
        </Badge>
      ))}
    </span>
  )
}

/**
 * Satır içi kopyala (fare kısayolu): tıklama satıra SIZMAZ, Tab durağı DEĞİLDİR (tabIndex -1) — tablo satır başına
 * tek duraklı kalsın; klavye/ekran okuyucu aynı değeri ayrıntıdaki odaklanabilir kopyala düğmesinden alır.
 */
export function CopyInline({ value, label }) {
  const t = useT()
  const [copied, setCopied] = useState(false)
  const timer = useRef(null)
  useEffect(() => () => clearTimeout(timer.current), [])
  async function onCopy(e) {
    e.stopPropagation()
    if (!(await copyText(value))) return
    setCopied(true)
    clearTimeout(timer.current)
    timer.current = setTimeout(() => setCopied(false), 1500)
  }
  const name = copied ? t('err.copied') : label
  return (
    <Button type="button" variant="ghost" size="icon-xs" tabIndex={-1} onClick={onCopy} aria-label={name} title={name}
      className="text-muted-foreground">
      {copied ? <Check /> : <Copy />}
    </Button>
  )
}

/** Aktör: avatar + ad (UserBadge) ve sistem rolü rozeti. Aktörsüz olay "SİSTEM". */
export function ActorLabel({ row, showRole = true, className }) {
  const t = useT()
  if (!row?.actor) return <span className={cn('font-semibold text-muted-foreground', className)}>{t('audit.systemActor')}</span>
  return (
    <span className={cn('inline-flex min-w-0 max-w-full flex-wrap items-center gap-1.5', className)}>
      <UserBadge username={row.actor} inline size="sm" />
      {showRole && row.actor_role && (
        <SystemRoleBadge role={row.actor_role} className="px-1.5 py-0 text-[0.65rem]">{row.actor_role}</SystemRoleBadge>
      )}
    </span>
  )
}
