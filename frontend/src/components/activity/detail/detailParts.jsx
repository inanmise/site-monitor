import {
  Shield, Activity, Globe, Server, Radio, Share2, Search, CalendarClock, ScanSearch, FlaskConical, Gauge, DatabaseSearch,
  CheckCircle, AlertTriangle, XCircle, HelpCircle, Equal,
} from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { navigateTo } from '../../../utils/navigate.js'
import { tabDeepLink } from '../../../utils/monitorDeepLink.js'
import ToneBadge from '../../admin/ToneBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

/**
 * Ayrıntı sayfasının küçük ortak parçaları (2026-11-05): tür rozeti, durum / sağlık rozeti, "yaklaşık eşleşme" rozeti,
 * bölüm kartı ve uygulama içi + yeni sekmede açılabilir bağlantı düğmesi. Listedeki (ActivityLog) tür renkleri ve durum
 * ikonlarıyla AYNI görsel dil — sayfa listeden gelince aynı şeyi tanır.
 */

// Liste ile aynı tür kimliği (ikon + renk). Liste dosyası ayrı ajanın; burada sözlük kopyası — renkler değişirse ikisi birlikte.
const TYPES = {
  CERT: { Icon: Shield, color: '#7c3aed' },
  DOMAIN: { Icon: CalendarClock, color: '#be123c' },
  HTTP: { Icon: Globe, color: '#2563eb' },
  UPTIME: { Icon: Activity, color: '#059669' },
  PORT: { Icon: Server, color: '#0d9488' },
  PING: { Icon: Radio, color: '#0891b2' },
  DNS: { Icon: Share2, color: '#6d28d9' },
  KEYWORD: { Icon: Search, color: '#d97706' },
  PAGE: { Icon: ScanSearch, color: '#0d9488' },
  PAGESPEED: { Icon: Gauge, color: '#c2410c' },
  SCRIPTED: { Icon: FlaskConical, color: '#7e22ce' },
  DBQUERY: { Icon: DatabaseSearch, color: '#0369a1' },
}
const UNKNOWN_TYPE = { Icon: HelpCircle, color: '#71717a' }

/** İzleme türü rozeti — türün kalıcı renk kimliği (ikon + ad); zemin rengin %10'u. */
export function TypeBadge({ type, className }) {
  const tm = TYPES[type] || UNKNOWN_TYPE
  return (
    <Badge variant="outline" data-slot="act-dt-type" data-type={type || undefined}
      className={cn('shrink-0 gap-1 rounded-full border-transparent px-2 text-[0.72em] font-bold tracking-wide', className)}
      style={{ color: tm.color, background: tm.color + '18' }}>
      <tm.Icon aria-hidden="true" /> {type || '—'}
    </Badge>
  )
}

/** Aktivite sonuç durumu (SUCCESS / WARNING / ERROR / TIMEOUT / UNKNOWN) → ton + ikon. Bilinmeyen asla yeşil değil. */
export const STATUS_TONE = {
  SUCCESS: { tone: 'success', Icon: CheckCircle },
  WARNING: { tone: 'warning', Icon: AlertTriangle },
  ERROR: { tone: 'danger', Icon: XCircle },
  TIMEOUT: { tone: 'danger', Icon: XCircle },
  UNKNOWN: { tone: 'muted', Icon: HelpCircle },
}

export function StatusBadge({ status, label, className }) {
  const s = STATUS_TONE[status] || STATUS_TONE.UNKNOWN
  return (
    <ToneBadge tone={s.tone} data-slot="act-dt-status" data-status={status || 'UNKNOWN'}
      className={cn('h-auto gap-1 rounded-md font-semibold whitespace-normal', className)}>
      <s.Icon aria-hidden="true" />{label}
    </ToneBadge>
  )
}

/** Kontrol satırının sağlığı (up / warn / down / unknown) → rozet. */
const HEALTH = {
  up: { tone: 'success', Icon: CheckCircle },
  warn: { tone: 'warning', Icon: AlertTriangle },
  down: { tone: 'danger', Icon: XCircle },
  unknown: { tone: 'muted', Icon: HelpCircle },
}
export function HealthBadge({ health, className }) {
  const t = useT()
  const h = HEALTH[health] ? health : 'unknown'
  const s = HEALTH[h]
  return (
    <ToneBadge tone={s.tone} data-slot="act-dt-health" data-health={h}
      className={cn('h-auto gap-1 rounded-md font-semibold whitespace-normal', className)}>
      <s.Icon aria-hidden="true" />{t('act.dt.h.' + h)}
    </ToneBadge>
  )
}

/** "Yaklaşık eşleşme" rozeti — anlamı rozetin yanında METİN olarak da yazılır (dokunmatikte ipucu açılmaz). */
export function ApproxBadge({ className }) {
  const t = useT()
  return (
    <ToneBadge tone="info" data-slot="act-dt-approx" className={cn('h-auto gap-1 rounded-md whitespace-normal', className)}>
      <Equal aria-hidden="true" className="rotate-90" />{t('act.dt.approx')}
    </ToneBadge>
  )
}

/**
 * Bölüm kartı: başlık (h3) + isteğe bağlı açıklama ve sağ üst eylem. Sol renk şeridi YOK.
 * Test kancası `data-slot` çağırandan (`act-dt-<bölüm>`).
 */
export function SectionCard({ slot, icon: Icon, title, description, action, className, children, ...rest }) {
  return (
    <Card data-slot={slot} className={cn('min-w-0 gap-3 py-4 shadow-xs', className)} {...rest}>
      <CardHeader className="flex min-w-0 flex-col gap-1.5 px-3 sm:flex-row sm:items-start sm:justify-between sm:px-4">
        <div className="flex min-w-0 flex-col gap-0.5">
          <CardTitle role="heading" aria-level={3} className="flex items-center gap-1.5 text-sm font-semibold">
            {Icon && <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />}
            <span className="min-w-0 [overflow-wrap:anywhere]">{title}</span>
          </CardTitle>
          {description && <CardDescription className="text-xs [overflow-wrap:anywhere]">{description}</CardDescription>}
        </div>
        {action && <div className="flex min-w-0 flex-wrap items-center gap-2 sm:justify-end">{action}</div>}
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-3 px-3 sm:px-4">{children}</CardContent>
    </Card>
  )
}

/**
 * Uygulama içi bağlantı düğmesi: düz tık sekmeyi uygulama içinde değiştirir (`navigateTo`); orta tık / Ctrl-⌘ / Shift
 * tıkı ve "yeni sekmede aç" tarayıcıya kalır (gerçek `href` — paylaşılabilir adres). Dokunmatikte ≥ 40 px.
 */
export function LinkButton({ target, children, variant = 'outline', size = 'sm', className, slot, ...rest }) {
  if (!target?.tab) return null
  const href = tabDeepLink(target.tab, target.params) || undefined
  const onClick = (e) => {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
    e.preventDefault()
    navigateTo(target.tab, target.params)
  }
  return (
    <Button asChild variant={variant} size={size} className={cn('pointer-coarse:h-10', className)}>
      <a href={href} onClick={onClick} data-link={slot} {...rest}>{children}</a>
    </Button>
  )
}

/** Anahtar-değer satırı (bölüm içi künye). */
export function Fact({ label, children, className }) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-0.5', className)}>
      <dt className="text-[10.5px] font-semibold tracking-wider text-muted-foreground uppercase">{label}</dt>
      <dd className="m-0 min-w-0 text-sm [overflow-wrap:anywhere]">{children}</dd>
    </div>
  )
}
