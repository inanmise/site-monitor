import { CheckCircle2, UserCheck, OctagonAlert, TriangleAlert, CircleAlert, ExternalLink, Bot } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { rcMeta } from '../../utils/incidentMeta.js'
import UserBadge from '../ui/UserBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { MONITOR_TYPE_ICON, MONITOR_TYPE_LABEL_KEY, severityMeta, incidentHref, RESOLVED_BY_TOKEN_KEY } from './incidentsModel.js'

/**
 * Olay konsolunun küçük, tekrar eden rozetleri — durum, önem, kök neden, izleme türü, kaynağa bağlantı.
 * Hepsi shadcn Badge/Button; ton jetonla (koyu tema karşılıkları `dark:`). Sol renk şeridi YOK — durum
 * yalnız rozetle taşınır (kullanıcı kuralı 2026-09-26). Test kancaları: `data-slot` + `data-status|data-level|data-rc`.
 */

/** Durum rozeti — süren: kırmızı + nabız noktası; çözüldü: yeşil onay. */
export function IncidentStatusBadge({ status, className }) {
  const t = useT()
  return status === 'ongoing'
    ? (
      <Badge variant="outline" data-slot="incident-status" data-status="ongoing"
        className={cn('gap-1.5 border-destructive/30 bg-destructive/10 font-bold whitespace-nowrap text-destructive', className)}>
        <span aria-hidden="true" className="size-2 rounded-full bg-destructive ring-[3px] ring-destructive/20 motion-safe:animate-pulse" />
        {t('incov.ongoing')}
      </Badge>
    )
    : (
      <Badge variant="outline" data-slot="incident-status" data-status="resolved"
        className={cn('gap-1 border-success/30 bg-success/10 font-bold whitespace-nowrap text-success', className)}>
        <CheckCircle2 aria-hidden="true" />{t('incov.resolved')}
      </Badge>
    )
}

/** "Onaylandı" işareti — birinin sahiplendiği açık olay (kapalı olayda çizilmez, bilgi artık zaman çizelgesinde). */
export function AckBadge({ className }) {
  const t = useT()
  return (
    <Badge variant="outline" data-slot="incident-ack"
      className={cn('gap-1 border-sky-500/30 bg-sky-500/10 font-semibold whitespace-nowrap text-sky-700 dark:text-sky-300', className)}>
      <UserCheck aria-hidden="true" />{t('incov.acked')}
    </Badge>
  )
}

const SEVERITY_STYLE = {
  CRITICAL: { Icon: OctagonAlert, cls: 'border-transparent bg-destructive text-white' },
  HIGH:     { Icon: TriangleAlert, cls: 'border-orange-500/40 bg-orange-500/10 text-orange-700 dark:text-orange-300' },
  WARNING:  { Icon: CircleAlert,   cls: 'border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-300' },
}

/** Önem rozeti (alert_level) — kritik dolu kırmızı, yüksek turuncu, uyarı amber; bilinmeyen düz çerçeve. */
export function SeverityBadge({ level, className }) {
  const t = useT()
  const key = String(level || '').toUpperCase()
  const meta = severityMeta(key)
  const style = SEVERITY_STYLE[key]
  if (!style) {
    return level ? <Badge variant="outline" data-slot="incident-severity" data-level={key} className={cn('whitespace-nowrap', className)}>{level}</Badge> : null
  }
  return (
    <Badge variant="outline" data-slot="incident-severity" data-level={key}
      className={cn('gap-1 font-bold tracking-wide whitespace-nowrap uppercase', style.cls, className)}>
      <style.Icon aria-hidden="true" />{t(meta.key)}
    </Badge>
  )
}

/** Kök neden — kod rozeti (incidentMeta kalıcı paleti) + isteğe bağlı okunur etiket. */
export function RootCauseChip({ rc, withLabel = true, className }) {
  const t = useT()
  if (!rc) return null
  const meta = rcMeta(rc.category)
  return (
    <span data-slot="incident-cause" className={cn('inline-flex min-w-0 flex-wrap items-center gap-1.5', className)}>
      <Badge data-rc={rc.category} className="rounded-[5px] px-1.5 text-[0.72em] font-bold tracking-[.02em] whitespace-nowrap text-white" style={{ background: meta.color }}>{rc.code}</Badge>
      {withLabel && <span className="text-[0.86em] text-muted-foreground">{t(meta.key)}</span>}
    </span>
  )
}

/** İzleme türü simgesi — kenar çubuğuyla aynı ikon; ekran okuyucuya tür adı, görenlere `title`. */
export function MonitorTypeIcon({ type, className }) {
  const t = useT()
  const Icon = MONITOR_TYPE_ICON[type] || MONITOR_TYPE_ICON.cert
  const label = t(MONITOR_TYPE_LABEL_KEY[type] || MONITOR_TYPE_LABEL_KEY.cert)
  return (
    <span data-slot="monitor-type" data-type={type || 'cert'} title={label}
      className={cn('inline-grid size-7 shrink-0 place-items-center rounded-md border bg-muted/60 text-muted-foreground', className)}>
      <Icon aria-hidden="true" className="size-4" />
      <span className="sr-only">{label}</span>
    </span>
  )
}

/** Kaynağa git — izleme sekmesi + odak (ya da pano + alan adı). Kart/satır tıklamasına SIZMAZ. */
export function MonitorLink({ inc, className, children }) {
  const t = useT()
  const name = inc?.monitor?.name || inc?.domain || '—'
  return (
    <Button asChild variant="link" size="xs" className={cn('h-auto justify-start gap-1 p-0 text-left font-semibold whitespace-normal [overflow-wrap:anywhere]', className)}>
      <a href={incidentHref(inc)} title={t('incov.openMonitor')} onClick={(e) => e.stopPropagation()}>
        {children ?? name} <ExternalLink aria-hidden="true" className="size-3 shrink-0 opacity-60" />
      </a>
    </Button>
  )
}

/** Kim çözdü — sistem jetonu (otomatik kapanış) ya da kişi. */
export function ResolvedBy({ by, className }) {
  const t = useT()
  if (!by) return null
  const tokenKey = RESOLVED_BY_TOKEN_KEY[String(by).toLowerCase()]
  if (tokenKey) {
    return (
      <span data-slot="resolved-by" data-token={String(by).toLowerCase()} className={cn('inline-flex items-center gap-1.5 text-muted-foreground', className)}>
        <Bot aria-hidden="true" className="size-3.5 shrink-0" />{t(tokenKey)}
      </span>
    )
  }
  return <span data-slot="resolved-by" className={cn('inline-flex', className)}><UserBadge displayName={by} username={by} inline size="sm" /></span>
}
