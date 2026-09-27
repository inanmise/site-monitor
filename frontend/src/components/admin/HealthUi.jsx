import { TONE_CLASS } from './ToneBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Table, TableHead } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * Sistem Sağlığı + Kullanıcı / Oturum panelinin ortak shadcn parçaları. İki ekran eskiden aynı legacy
 * sözlüğü paylaşıyordu (`.health-dbtable` / `.dbtcol-*` tabloları, `.uact-kpi*` kartları, `.fc-card`
 * numaralı bölümler, `.show-field` anahtar/değer ızgarası, `.uact-pill` / `.uact-flag` etiketleri);
 * burada Card / Table / Badge / Button ile tek yerde. Legacy sınıf taşımaz.
 */

/** Yoğun veri tablosu başlık/hücre ölçüleri (eski `.dbtcol-th` / `.dbtcol-num-cell`). */
export const TH = 'h-9 px-3 text-[0.76em] font-bold tracking-wide text-muted-foreground uppercase'
export const TH_NUM = cn(TH, 'text-right')
export const TD = 'px-3 py-1.5 align-middle whitespace-normal'
export const TD_NUM = 'px-3 py-1.5 text-right whitespace-nowrap tabular-nums'
export const MUTED_SM = 'text-xs text-muted-foreground'

/** Kenarlıklı yoğun tablo kabı — shadcn Table (`data-testid` tabloya gider). */
export function DataTable({ testId, className = '', fixed = false, children }) {
  return (
    <div className="mt-2 overflow-hidden rounded-lg border border-border">
      <Table data-testid={testId} className={cn('text-[0.84em]', fixed && 'table-fixed', className)}>{children}</Table>
    </div>
  )
}

/** Sıralanabilir başlık hücresi: `aria-sort` + ghost Button; ok işareti etkin sütunda. */
export function SortTh({ label, active, dir, numeric = false, onSort, className = '' }) {
  return (
    <TableHead className={cn(numeric ? TH_NUM : TH, className)}
      aria-sort={active ? (dir === 'asc' ? 'ascending' : 'descending') : 'none'}>
      <Button type="button" variant="ghost" size="xs"
        className="-mx-1 h-auto px-1 py-0 text-[1em] font-bold tracking-wide uppercase hover:bg-transparent hover:text-primary"
        onClick={onSort}>
        {label}<span className="inline-block w-3.5 opacity-80">{active ? (dir === 'asc' ? ' ↑' : ' ↓') : ''}</span>
      </Button>
    </TableHead>
  )
}

/** Numaralı bölüm kartı (eski `.fc-card.fc-section-card` + `.fc-sec-header`). */
export function SectionCard({ num, title, extra, className = '', children }) {
  return (
    <Card className={cn('gap-3 px-[22px] pt-5 pb-[18px] shadow-xs', className)}>
      <div className="flex flex-wrap items-baseline gap-2.5">
        {num && <span className="min-w-[22px] text-[0.65em] font-semibold tracking-wide text-muted-foreground tabular-nums opacity-70">{num}</span>}
        <span className="font-serif text-[1.25em] leading-tight italic">{title}</span>
        {extra}
      </div>
      {children}
    </Card>
  )
}

/** KPI kutusu tonları (eski `.uact-kpi--ok/--danger/--warn`). */
const KPI_ICON = {
  ok: 'bg-success/15 text-success', danger: 'bg-destructive/15 text-destructive',
  warn: 'bg-amber-500/15 text-amber-700 dark:text-amber-300', default: 'bg-primary/10 text-primary',
}
const KPI_VAL = { danger: 'text-destructive', warn: 'text-amber-700 dark:text-amber-300' }

/**
 * KPI kartı (eski `.uact-kpi`): ikon + (isteğe bağlı kıvılcım) + değer (+ fark) + etiket + alt satır.
 * `onClick` verilirse shadcn Button (outline), yoksa Card. Test kancası: `data-kpi` (+ `data-tone`).
 * `spark` (2026-09-27, Sistem Sağlığı): alt satırın altına tam genişlik minik grafik (ChartContainer) — ekleyici.
 */
export function KpiCard({ kpiKey, icon: Icon, value, label, sub, tone, onClick, title, top, delta, mini = false, spark = null }) {
  const body = (
    <>
      {(Icon || top) && (
        <span className="flex w-full items-center justify-between gap-2">
          {Icon && <span className={cn('grid size-8 place-items-center rounded-[9px]', KPI_ICON[tone] || KPI_ICON.default)}><Icon size={16} /></span>}
          {top}
        </span>
      )}
      <span className={cn('leading-none font-extrabold tracking-tight tabular-nums', mini ? 'text-[22px]' : 'text-[30px]', KPI_VAL[tone])}>
        {value}{delta}
      </span>
      <span className="text-[10.5px] font-bold tracking-widest text-muted-foreground uppercase">{label}</span>
      {sub ? <span className="min-w-0 max-w-full truncate text-[11px] text-muted-foreground">{sub}</span> : null}
      {spark ? <span className="mt-auto w-full pt-1">{spark}</span> : null}
    </>
  )
  const base = cn('flex flex-col items-start gap-[7px] rounded-xl border bg-card text-left shadow-sm border-border', mini ? 'px-3 py-2.5' : 'px-4 py-[15px]')
  if (!onClick) return <Card data-kpi={kpiKey} data-tone={tone} className={cn(base, 'gap-[7px]')} title={title}>{body}</Card>
  return (
    <Button type="button" variant="outline" data-kpi={kpiKey} data-tone={tone} onClick={onClick} title={title}
      className={cn(base, 'h-auto justify-start font-normal whitespace-normal transition-[transform,box-shadow,border-color] hover:-translate-y-0.5 hover:border-primary hover:bg-card hover:shadow-lg motion-reduce:hover:translate-y-0')}>
      {body}
    </Button>
  )
}

/** Durum/etiket hapı tonları (eski `.uact-pill` + `.uact-st--*` / `.udir-tour--*`). */
export const PILL_TONE = {
  active: TONE_CLASS.success, completed: TONE_CLASS.success,
  today: TONE_CLASS.info, started: TONE_CLASS.info, remember: TONE_CLASS.info,
  week: 'border-transparent bg-sky-500/15 text-sky-700 dark:text-sky-300',
  month: TONE_CLASS.muted, none: TONE_CLASS.muted, dismissed: TONE_CLASS.muted,
  dormant: TONE_CLASS.warning, snoozed: TONE_CLASS.warning, new: TONE_CLASS.warning,
  never: TONE_CLASS.danger,
}
export function Pill({ tone, status, className = '', title, children }) {
  return (
    <Badge variant="outline" data-status={status} title={title}
      className={cn('rounded-full px-[7px] text-[10.5px] font-bold tracking-wide', PILL_TONE[tone] || TONE_CLASS.muted, className)}>
      {children}
    </Badge>
  )
}

/** Anomali bayrağı (eski `.uact-flag`). Test kancası: `data-flag`. */
export function FlagBadge({ flag, title, children }) {
  return (
    <Badge variant="outline" data-flag={flag} title={title}
      className={cn('my-px mr-1 rounded-md px-[7px] text-[11px] font-semibold', TONE_CLASS.danger)}>
      {children}
    </Badge>
  )
}

/** Satır içi bağlantı düğmesi (eski `.uact-link`). */
export function LinkButton({ className = '', children, ...rest }) {
  return (
    <Button type="button" variant="link" size="xs" className={cn('ml-1 h-auto gap-1 p-0 text-[1em] underline underline-offset-2', className)} {...rest}>
      {children}
    </Button>
  )
}

/** Özet çipi sayı tonları (eski `.udir-chip--ok/--warn/--danger b`). */
const CHIP_VAL = { ok: 'text-green-700 dark:text-green-400', warn: 'text-amber-700 dark:text-amber-300', danger: 'text-destructive' }
/**
 * Özet çipi (eski `.udir-chip`): sayı + etiket. `onClick` verilirse süzen, `aria-pressed`li bir outline
 * Button; verilmezse yalnız bilgi taşıyan Badge (tıklanamaz öğe düğme gibi görünmesin).
 */
export function StatChip({ val, label, on = false, tone, onClick }) {
  const body = <><b className={cn('font-bold tabular-nums', CHIP_VAL[tone] || 'text-foreground')}>{val}</b> {label}</>
  if (!onClick) {
    return <Badge variant="outline" className="h-7 gap-1 rounded-full px-2.5 text-xs font-normal text-muted-foreground">{body}</Badge>
  }
  return (
    <Button type="button" variant="outline" size="xs" aria-pressed={on} onClick={onClick}
      className={cn('h-7 gap-1 rounded-full px-2.5 font-normal text-muted-foreground',
        on && 'border-primary bg-primary/10 text-primary hover:bg-primary/15 hover:text-primary')}>
      {body}
    </Button>
  )
}

/** Kimlik kaynağı rozeti (eski `.udir-src`): LDAP mavi, yerel sessiz. `data-auth-source` kancası. */
export function AuthSourceBadge({ source, localLabel }) {
  const ldap = source === 'LDAP'
  return (
    <Badge variant="outline" data-auth-source={source}
      className={cn('rounded-md px-[7px] text-[11px] font-semibold', ldap ? TONE_CLASS.info : 'text-muted-foreground')}>
      {ldap ? 'LDAP' : localLabel}
    </Badge>
  )
}

/** Anahtar/değer alanı (eski `.show-field`) ve iki sütunlu ızgarası; bölüm başlığı. */
export function KvField({ label, value, mono = false, full = false }) {
  return (
    <div className={cn('flex flex-col gap-[3px]', full && 'col-span-full')}>
      <span className="text-[10px] font-bold tracking-wide text-muted-foreground">{label}</span>
      <span className={cn('text-[13px] leading-normal break-words', mono && 'font-mono text-xs')}>{value || '—'}</span>
    </div>
  )
}
export const KV_GRID = 'grid grid-cols-1 gap-x-5 gap-y-2.5 sm:grid-cols-2'
export function KvSection({ children }) {
  return <div className="mt-1.5 border-b pt-1.5 pb-1 text-[10px] font-bold tracking-wider text-muted-foreground border-border">{children}</div>
}
