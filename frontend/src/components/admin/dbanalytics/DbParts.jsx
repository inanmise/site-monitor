import { forwardRef } from 'react'
import { Skeleton } from '@/components/shadcn/skeleton'
import { TableHead } from '@/components/shadcn/table'
import { Button } from '@/components/shadcn/button'
import ToneBadge from '../ToneBadge.jsx'
import { cn } from '@/lib/utils'

/**
 * Veritabanı Analitiği'nin küçük ortak parçaları (yalnız bu panel). Ortak `HealthUi.jsx` bilinçli olarak
 * kullanılmıyor/değiştirilmiyor: başka ekranlarla paylaşılan bir dosya; buradaki ölçüler (telefonda 40 px dokunma,
 * kart düzeni) yalnız bu panele özgü.
 */

/** Yoğun tablo başlık/hücre ölçüleri. */
export const TH = 'h-9 px-3 text-[11px] font-bold tracking-wide text-muted-foreground uppercase'
export const TH_NUM = cn(TH, 'text-right')
export const TD = 'px-3 py-2 align-middle whitespace-normal'
export const TD_NUM = 'px-3 py-2 text-right align-middle whitespace-nowrap tabular-nums'

/** Sıralanabilir başlık: `aria-sort` + ghost Button (ad = sütun etiketi, etkin sütunda ok). */
export function SortHead({ label, active, dir, numeric = false, onSort, className }) {
  return (
    <TableHead className={cn(numeric ? TH_NUM : TH, className)}
      aria-sort={active ? (dir === 'asc' ? 'ascending' : 'descending') : 'none'}>
      <Button type="button" variant="ghost" size="xs" onClick={onSort}
        className={cn('-mx-1 h-auto px-1 py-0.5 text-[1em] font-bold tracking-wide uppercase hover:bg-transparent hover:text-primary',
          active && 'text-foreground')}>
        {label}<span aria-hidden="true" className="inline-block w-3.5 opacity-80">{active ? (dir === 'asc' ? ' ↑' : ' ↓') : ''}</span>
      </Button>
    </TableHead>
  )
}

/** Tonun sözlü karşılığı — renk tek başına anlam taşımasın. */
export const LEVEL_KEY = { success: 'dba.lvlGood', warning: 'dba.lvlWatch', danger: 'dba.lvlPoor', muted: 'dba.unknown' }
export function LevelBadge({ tone, t, className }) {
  return <ToneBadge tone={tone} data-level={tone} className={cn('font-semibold', className)}>{t(LEVEL_KEY[tone] || 'dba.unknown')}</ToneBadge>
}

/**
 * Küçük ölçü kutusu (etiket üstte, değer altta). `value == null` → "Bilinmiyor" (soluk, italik değil — okunur).
 * `aside`: değerin yanına rozet vb.
 */
export function Stat({ label, value, sub, aside, mono = false, unknownLabel, testId, className }) {
  const unknown = value == null || value === ''
  return (
    <div data-slot="db-stat" data-testid={testId} data-unknown={unknown ? 'true' : undefined}
      className={cn('flex min-w-0 flex-col gap-1 rounded-lg border border-border bg-muted/30 px-3 py-2.5', className)}>
      <span className="text-[11px] font-semibold text-muted-foreground">{label}</span>
      <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
        <span className={cn('min-w-0 text-[15px] leading-tight font-semibold break-words tabular-nums', mono && 'font-mono text-[13px]',
          unknown && 'font-medium text-muted-foreground')}>
          {unknown ? unknownLabel : value}
        </span>
        {aside}
      </span>
      {sub ? <span className="min-w-0 text-xs break-words text-muted-foreground">{sub}</span> : null}
    </div>
  )
}

/**
 * Bölüm başlığı (h4) + sağda isteğe bağlı eylemler; telefonda alt alta sarar. Ref başlığa gider (`tabIndex=-1`):
 * özet kutucuğundan bu bölüme atlanınca odak buraya taşınır.
 */
export const SectionHeading = forwardRef(function SectionHeading({ id, icon: Icon, title, hint, children, className }, ref) {
  return (
    <div className={cn('flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between', className)}>
      <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
        <h4 id={id} ref={ref} tabIndex={-1} className="m-0 flex min-w-0 items-center gap-2 rounded-sm text-sm font-semibold outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50">
          {Icon && <span className="grid size-7 shrink-0 place-items-center rounded-lg bg-primary/10 text-primary"><Icon className="size-4" aria-hidden="true" /></span>}
          <span className="min-w-0 break-words">{title}</span>
        </h4>
        {hint}
      </div>
      {children ? <div className="flex min-w-0 flex-wrap items-center gap-2">{children}</div> : null}
    </div>
  )
})

/** İlk yükleme iskeleti — gerçek yerleşimle aynı iskelet (sıçrama yok); ekran okuyucuya ayrı durum metni. */
export function DbSkeleton({ label }) {
  const sk = 'motion-reduce:animate-none'
  return (
    <div data-testid="db-skeleton" className="flex flex-col gap-4">
      <span role="status" className="sr-only">{label}</span>
      {[0, 1].map((g) => (
        <div key={g} className="flex flex-col gap-2" aria-hidden="true">
          <Skeleton className={cn('h-4 w-48 max-w-full', sk)} />
          <div className="grid grid-cols-2 gap-3 @2xl:grid-cols-4">
            {Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className={cn('h-[118px] rounded-xl', sk)} />)}
          </div>
        </div>
      ))}
      <Skeleton aria-hidden="true" className={cn('h-[340px] rounded-xl', sk)} />
      <Skeleton aria-hidden="true" className={cn('h-[220px] rounded-xl', sk)} />
      <Skeleton aria-hidden="true" className={cn('h-10 w-80 max-w-full', sk)} />
      <Skeleton aria-hidden="true" className={cn('h-[260px] rounded-xl', sk)} />
    </div>
  )
}
