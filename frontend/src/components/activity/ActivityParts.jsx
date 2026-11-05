import {
  Shield, Activity, Globe, Server, Radio, Share2, Search, CalendarClock, ScanSearch, FlaskConical, Gauge, DatabaseSearch,
  CheckCircle, AlertTriangle, XCircle, HelpCircle,
} from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { reasonLabel, reasonParams } from './activityModel.js'

/**
 * Aktivite ekranlarının ortak küçük parçaları (2026-11-05): liste (ActivityLog), izleme zaman çizelgesi ve "aynı anda ne
 * oldu?" paneli aynı tür rozetini, durum ikonunu ve hata nedeni etiketini kullanır — tek kaynak.
 */

// Her izleme türünün ayırt edici ikon + rengi (badge). Sıra activityModel.TYPE_KEYS ile aynı (kapı: activityModel.test).
export const TYPES = [
  { key: 'CERT',    Icon: Shield,        color: '#7c3aed' },
  { key: 'DOMAIN',  Icon: CalendarClock, color: '#be123c' },
  { key: 'HTTP',    Icon: Globe,         color: '#2563eb' },
  { key: 'UPTIME',  Icon: Activity,      color: '#059669' },
  { key: 'PORT',    Icon: Server,        color: '#0d9488' },
  { key: 'PING',    Icon: Radio,         color: '#0891b2' },
  { key: 'DNS',     Icon: Share2,        color: '#6d28d9' },
  { key: 'KEYWORD', Icon: Search,        color: '#d97706' },
  { key: 'PAGE',    Icon: ScanSearch,    color: '#0d9488' },
  { key: 'PAGESPEED', Icon: Gauge,      color: '#c2410c' },
  // Backend SCRIPTED kaydi yaziyor (ActivityLogService.SCRIPTED) ama burada karsiligi yoktu:
  // satirlar gri "?" rozetiyle cikiyor ve tur filtresi cipi hic uretilmiyordu.
  { key: 'SCRIPTED',Icon: FlaskConical,  color: '#7e22ce' },
  { key: 'DBQUERY', Icon: DatabaseSearch, color: '#0369a1' },
]
export const TYPE_MAP = Object.fromEntries(TYPES.map((x) => [x.key, x]))
const UNKNOWN_TYPE = { Icon: HelpCircle, color: '#71717a' }

// Sonuç durumu → ikon + mürekkep (jeton sınıfı; koyu temada karşılıklı).
export const STATUS_META = {
  SUCCESS: { Icon: CheckCircle,   ink: 'text-success' },
  WARNING: { Icon: AlertTriangle, ink: 'text-amber-600 dark:text-amber-400' },
  ERROR:   { Icon: XCircle,       ink: 'text-destructive' },
  TIMEOUT: { Icon: XCircle,       ink: 'text-destructive' },
  UNKNOWN: { Icon: HelpCircle,    ink: 'text-muted-foreground' },
}

/** İzleme türü rozeti — türün kalıcı renk kimliği (ikon + ad); zemin rengin %10'u. */
export function TypeBadge({ type, className }) {
  const key = String(type || '').toUpperCase()
  const tm = TYPE_MAP[key] || UNKNOWN_TYPE
  return (
    <Badge variant="outline" data-type={type} className={cn('shrink-0 gap-1 rounded-full border-transparent px-2 text-[0.72em] font-bold tracking-wide', className)}
      style={{ color: tm.color, background: tm.color + '18' }}>
      <tm.Icon aria-hidden="true" /> {key || type}
    </Badge>
  )
}

/** Hata satırı mı (ERROR / TIMEOUT). */
export const isFailure = (status) => status === 'ERROR' || status === 'TIMEOUT'

/**
 * Hata nedeni rozeti — kısa ad (`chkfail.<KOD>.short`, yoksa `kwfail`); kod sözlükte yoksa tek aralıklı ham kod. Rozet
 * KESİLMEZ (uzun neden adları sarar).
 */
export function ReasonBadge({ row, code, t, className }) {
  const c = code ?? row?.failure_reason
  if (!c) return null
  const label = reasonLabel(c, t, row ? reasonParams(row) : {})
  const raw = label === String(c)
  return (
    <Badge variant="outline" data-slot="act-reason" data-code={c}
      className={cn('h-auto rounded-md border-destructive/30 bg-destructive/5 px-1.5 py-0 text-[0.78em] font-medium whitespace-normal text-destructive [overflow-wrap:anywhere] overflow-visible',
        raw && 'font-mono', className)}>
      {label}
    </Badge>
  )
}
