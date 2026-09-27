import { ChevronRight, CheckCircle2, XCircle, MinusCircle, AlertTriangle, CircleDashed } from 'lucide-react'
import { Spinner } from '../ui/Progress.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import { KvSection } from '../admin/HealthUi.jsx'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'

/**
 * Tanılama yüzeylerinin ortak küçük parçaları (pencere + geçmiş + ek analizler). Tümü shadcn/Tailwind;
 * legacy sınıf yok. Eski DiagnosticsModal'daki yardımcılar buraya taşındı (2026-09-26 yeniden tasarım).
 */

/** Anahtar/değer alanı (eski `.show-field`). Değer `??` ile düşer: 0 ms gibi sıfır değerler "—" olmaz. */
export function ShowField({ label, value, mono, full }) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-[3px]', full && 'col-span-full mb-1.5')}>
      <span className="text-[10px] font-bold tracking-wide text-muted-foreground">{label}</span>
      <span className={cn('text-[13px] leading-normal break-words', mono && 'font-mono text-xs')}>{value ?? '—'}</span>
    </div>
  )
}

/** Ham çıktı kutusu (eski `.show-pre`). */
export const PRE = 'max-h-[260px] overflow-auto rounded-md border bg-muted/60 px-3 py-2 font-mono text-xs break-words whitespace-pre-wrap border-border'
export const MUTED = 'text-muted-foreground'

/** Açılır bölüm (eski `<details>`): shadcn Collapsible; `bordered` kartlı görünüm. */
export function Disclosure({ summary, bordered = false, triggerClassName = '', defaultOpen = false, children, ...rest }) {
  return (
    <Collapsible defaultOpen={defaultOpen} className={cn('group/disc mb-1.5 min-w-0', bordered && 'rounded-md border border-border')} {...rest}>
      <CollapsibleTrigger className={cn('flex min-h-10 w-full cursor-pointer flex-wrap items-center gap-2 text-left outline-none focus-visible:ring-2 focus-visible:ring-ring/50',
        bordered ? 'rounded-md px-2.5 py-[7px] hover:bg-muted/40' : 'py-1', triggerClassName)}>
        <ChevronRight size={14} aria-hidden="true" className="shrink-0 transition-transform group-data-[state=open]/disc:rotate-90 motion-reduce:transition-none" />
        {summary}
      </CollapsibleTrigger>
      <CollapsibleContent className={cn('min-w-0', bordered && 'px-2.5 pb-2')}>{children}</CollapsibleContent>
    </Collapsible>
  )
}

/** Bölüm başlığı (eski `.show-section-header`), üst boşluklu. */
export function Section({ children, first = false }) {
  return <div className={first ? '' : 'mt-3'}><KvSection>{children}</KvSection></div>
}

/** Yükleniyor satırı. */
export function Running({ label }) {
  return <div role="status" className="flex items-center gap-1.5 text-[13px]"><Spinner size={14} inline decorative /> {label}</div>
}

/** Adım/kontrol durumu → ikon + ton + rozet anahtarı. Ton yalnız ikon/rozet rengidir (sol şerit YOK). */
export const STATUS_META = {
  ok:      { Icon: CheckCircle2, tone: 'success', icon: 'text-success', key: 'diag.status.ok' },
  warning: { Icon: AlertTriangle, tone: 'warning', icon: 'text-amber-600 dark:text-amber-400', key: 'diag.status.warning' },
  failed:  { Icon: XCircle, tone: 'danger', icon: 'text-destructive', key: 'diag.status.failed' },
  skipped: { Icon: MinusCircle, tone: 'muted', icon: 'text-muted-foreground', key: 'diag.status.skipped' },
  pending: { Icon: CircleDashed, tone: 'muted', icon: 'text-muted-foreground', key: 'diag.status.pending' },
}
export const statusMeta = (s) => STATUS_META[s] || STATUS_META.pending

/** Durum rozeti (metin çağırandan — i18n). */
export function StatusBadge({ status, className, children }) {
  const m = statusMeta(status)
  return <ToneBadge tone={m.tone} data-status={status} className={cn('font-semibold', className)}>{children}</ToneBadge>
}
