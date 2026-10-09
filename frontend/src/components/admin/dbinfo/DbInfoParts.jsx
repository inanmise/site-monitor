import { createContext, useContext, useId } from 'react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import CopyButton from '../../ui/CopyButton.jsx'
import HelpTip from '../../ui/HelpTip.jsx'
import { ProgressBar } from '../../ui/Progress.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { DASH, isBlank, PROGRESS_TONE, STATUS_KEY } from './dbInfoModel.js'

/**
 * Ayarlar → Veritabanı Bilgileri'nin küçük shadcn parçaları (yalnız bu sayfa). Sol renk şeridi YOK (kullanıcı kararı
 * 2026-09-26): durum rozet + `data-status` ile taşınır. Dokunma hedefleri telefonda / dokunmatikte 40 px.
 */

/** Kopya düğmesi ölçüsü: masaüstünde 32 px, telefonda / dokunmatikte 40 px. */
export const COPY_TOUCH = 'shrink-0 max-md:size-10 pointer-coarse:size-10'

/** Genel durum rozeti (UP / DEGRADED / DOWN / bilinmiyor) — metinli; renk tek başına anlam taşımaz. */
export function StatusBadge({ t, status, className, ...rest }) {
  const tone = { UP: 'success', DEGRADED: 'warning', DOWN: 'danger' }[status] || 'muted'
  return (
    <ToneBadge tone={tone} data-status={status || 'UNKNOWN'} className={cn('gap-1.5 font-semibold', className)} {...rest}>
      <span aria-hidden="true" className={cn('size-1.5 rounded-full', {
        success: 'bg-success', warning: 'bg-amber-500', danger: 'bg-destructive', muted: 'bg-muted-foreground',
      }[tone])} />
      {status ? t(STATUS_KEY[status]) : t('dba.unknown')}
    </ToneBadge>
  )
}

/**
 * Özet kutucuğu (KPI): etiket + büyük değer + isteğe bağlı doluluk çubuğu ve alt satır. Bilinmeyen değer okunur
 * "Bilinmiyor" yazar. Test kancası: `data-slot="dbinfo-kpi"` + `data-kpi` + `data-tone`.
 */
export function KpiTile({ id, icon: Icon, label, value, sub, tone = 'muted', bar, unknownLabel, aside }) {
  const unknown = isBlank(value)
  return (
    <div data-slot="dbinfo-kpi" data-kpi={id} data-tone={tone} data-unknown={unknown ? 'true' : undefined}
      className="flex min-w-0 flex-col gap-2 rounded-xl border bg-card px-3.5 py-3 shadow-xs sm:px-4">
      <div className="flex min-w-0 items-center gap-2 text-xs font-semibold text-muted-foreground">
        {Icon && (
          <span aria-hidden="true" className="grid size-7 shrink-0 place-items-center rounded-lg bg-primary/10 text-primary">
            <Icon className="size-4" />
          </span>
        )}
        <span className="min-w-0 break-words">{label}</span>
      </div>
      <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
        <span data-slot="dbinfo-kpi-value"
          className={cn('min-w-0 text-xl leading-tight font-bold tracking-tight break-words tabular-nums sm:text-2xl',
            unknown && 'text-base font-medium text-muted-foreground sm:text-base')}>
          {unknown ? unknownLabel : value}
        </span>
        {aside}
      </div>
      {bar && bar.max > 0 && (
        <ProgressBar value={bar.value} max={bar.max} size="sm" label={bar.label} tone={PROGRESS_TONE[tone]} />
      )}
      {sub ? <span className="min-w-0 text-xs break-words text-muted-foreground">{sub}</span> : null}
    </div>
  )
}

/**
 * Bilgi kartı — shadcn Card: başlık satırı (ikon kutusu + başlık h4 + açıklama + sağda isteğe bağlı rozet) ve
 * `@container` gövde (satırlar kartın KENDİ genişliğine göre yan yana ya da alt alta dizilir).
 * Test kancası: `data-slot="dbinfo-card"` + `data-card`.
 */
export function InfoCard({ id, icon: Icon, title, description, badge, className, contentClassName, children }) {
  const titleId = useId()
  return (
    <Card role="region" aria-labelledby={titleId} data-slot="dbinfo-card" data-card={id}
      className={cn('min-w-0 gap-0 py-0 shadow-xs', className)}>
      <CardHeader className="flex flex-wrap items-start justify-between gap-x-3 gap-y-2 border-b px-4 py-3.5 sm:px-5 [.border-b]:pb-3.5">
        {/* Dar kartta rozet başlığın ALTINA iner (açıklama sıkışmasın): sol blok en az 15rem ister */}
        <div className="flex min-w-[min(100%,15rem)] flex-1 items-start gap-2.5">
          {Icon && (
            <span aria-hidden="true" className="mt-0.5 grid size-8 shrink-0 place-items-center rounded-lg bg-primary/10 text-primary">
              <Icon className="size-4" />
            </span>
          )}
          <div className="flex min-w-0 flex-col gap-1">
            <CardTitle id={titleId} role="heading" aria-level={4} className="text-[0.95rem] leading-snug break-words">{title}</CardTitle>
            {description && <CardDescription className="text-xs leading-relaxed">{description}</CardDescription>}
          </div>
        </div>
        {badge && <div className="flex shrink-0 flex-wrap items-center gap-1.5">{badge}</div>}
      </CardHeader>
      <CardContent className={cn('@container min-w-0 px-4 py-1.5 sm:px-5', contentClassName)}>{children}</CardContent>
    </Card>
  )
}

/** Liste düzeni satırlara bağlamla iner: iki sütunda satır HER ZAMAN etiket-üstte (yarım sütunda yan yana sığmaz). */
const ListCols = createContext({ cols: 1, at: 'sm' })

/** İki sütuna geçiş eşiği (kart içeriğinin genişliği) — Tailwind sınıfları LİTERAL kalmalı (derleyici tarar). */
const TWO_COLS = { sm: '@sm:grid-cols-2 @sm:gap-x-6', '2xs': '@2xs:grid-cols-2 @2xs:gap-x-6' }
const SPAN_TWO = { sm: '@sm:col-span-2', '2xs': '@2xs:col-span-2' }

/**
 * Satır listesi kabı (description list). `cols={2}`: kısa değerli uzun listeler (sunucu, havuz) kart içeriği ≥ 24rem
 * (`at="sm"`) ya da yalnız sayı taşıyan listede ≥ 18rem (`at="2xs"`, telefonda da iki sütun) iken iki sütun — kart boyu
 * yarıya iner. Ayraç çizgisi her satırın ÜST kenarı; ilk satır(lar)ınki dış kabın `overflow-hidden` + `-mt-px` ile
 * gizlenir (iki sütunda da tutarlı; `divide-y` ızgarada çalışmaz).
 */
export function InfoList({ children, className, cols = 1, at = 'sm' }) {
  return (
    <div className={cn('min-w-0 overflow-hidden', className)}>
      <ListCols.Provider value={{ cols, at }}>
        <dl data-slot="dbinfo-list" data-cols={cols}
          className={cn('m-0 -mt-px grid min-w-0 grid-cols-1', cols === 2 && TWO_COLS[at])}>
          {children}
        </dl>
      </ListCols.Provider>
    </div>
  )
}

/**
 * Etiket / değer satırı. Dar kartta etiket üstte, geniş kartta (≥ 28rem) iki sütun. Uzun değerler (JDBC URL, sürüm
 * metni) `break-all` ile kartın İÇİNDE sarar — yatay taşma yok; `copy` verilirse yanında Kopyala düğmesi (ad satırı
 * ayırır: "Kopyala — JDBC URL"). `secondary`: değerin altında soluk ikinci satır (tam sürüm, ham süre…).
 * `badge`: değerin yanında rozet. `help`: etiketin yanında yardım balonu (dokun-gör). `wide`: iki sütunlu listede tam
 * satır (havuz adı). Boş değer "—". Test kancası: `data-slot="dbinfo-row"` + `data-key`.
 */
export function InfoRow({ t, k, label, value, mono = false, wrap = false, copy = false, copyValue, secondary, badge, help, wide = false, children }) {
  const { cols, at } = useContext(ListCols)
  const side = cols === 1   // tek sütunlu listede geniş kartta etiket solda, değer sağda
  const blank = isBlank(value) && !children
  const shown = blank ? DASH : value
  const copyText = copyValue ?? (typeof value === 'string' || typeof value === 'number' ? String(value) : null)
  return (
    <div data-slot="dbinfo-row" data-key={k} data-blank={blank ? 'true' : undefined}
      className={cn('grid min-w-0 grid-cols-1 gap-1 border-t border-border py-2.5', wide && cols === 2 && SPAN_TWO[at],
        side && '@md:grid-cols-[minmax(0,12rem)_minmax(0,1fr)] @md:gap-4 @lg:grid-cols-[minmax(0,14rem)_minmax(0,1fr)]')}>
      <dt className={cn('flex min-w-0 items-center self-start text-xs font-medium text-muted-foreground', side && '@md:min-h-8')}>
        <span className="min-w-0 break-words">{label}</span>
        {help && <HelpTip helpKey={help} label={label} />}
      </dt>
      <dd className="m-0 flex min-w-0 items-center gap-2">
        <div className={cn('flex min-w-0 flex-1 flex-col gap-0.5', side && '@md:min-h-8 @md:justify-center')}>
          <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
            {children ?? (
              <span data-slot="dbinfo-value" title={!blank && typeof shown === 'string' && shown.length > 40 ? shown : undefined}
                className={cn('min-w-0 text-sm text-foreground', mono && 'font-mono text-[0.8125rem]',
                  wrap ? 'break-all' : 'break-words', blank && 'text-muted-foreground')}>
                {shown}
              </span>
            )}
            {badge}
          </div>
          {secondary ? (
            <span data-slot="dbinfo-secondary" className="min-w-0 text-xs break-words text-muted-foreground">{secondary}</span>
          ) : null}
        </div>
        {copy && !blank && copyText && (
          <CopyButton value={copyText} variant="ghost" buttonSize="icon-sm" className={COPY_TOUCH}
            label={t('a11y.rowAction', t('dba.copyCode'), label)} copiedLabel={t('db.copied')} />
        )}
      </dd>
    </div>
  )
}

/**
 * Havuz şeridi — kullanımda / boşta / açılabilir dilimleri (2 px aralıklı) + METİNLİ gösterge. Renk tek başına anlam
 * taşımaz; ekran okuyucuya tek cümle (role="img").
 */
export function PoolBar({ t, segments }) {
  if (!segments?.length) return null
  const label = { active: t('db.poolActive'), idle: t('db.poolIdle'), free: t('dbinfo.poolFree') }
  const summary = segments.map((s) => `${label[s.key]} ${s.count}`).join(', ')
  return (
    <div className="flex flex-col gap-2 py-3" data-slot="dbinfo-pool-bar">
      <div role="img" aria-label={t('dbinfo.poolBarAria', summary)} className="flex h-2.5 w-full gap-0.5 overflow-hidden rounded-full">
        {segments.map((s) => (
          <span key={s.key} data-seg={s.key} className={cn('h-full min-w-1 first:rounded-l-full last:rounded-r-full', s.dot)}
            style={{ flexGrow: s.count, flexBasis: 0 }} />
        ))}
      </div>
      <ul className="m-0 flex list-none flex-wrap gap-x-4 gap-y-1 p-0 text-xs">
        {segments.map((s) => (
          <li key={s.key} className="flex items-center gap-1.5">
            <span aria-hidden="true" className={cn('size-2.5 shrink-0 rounded-full', s.dot)} />
            <span className="text-muted-foreground">{label[s.key]}</span>
            <span className="font-semibold tabular-nums">{s.count}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** İlk yükleme iskeleti — gerçek yerleşimle aynı kaba düzen (sıçrama yok); ekran okuyucuya ayrı durum metni. */
export function DbInfoSkeleton({ label }) {
  const sk = 'motion-reduce:animate-none'
  return (
    <div data-testid="dbinfo-skeleton" className="flex flex-col gap-4">
      <span role="status" className="sr-only">{label}</span>
      <div aria-hidden="true" className="grid grid-cols-1 gap-3 @xs/dbinfo:grid-cols-2 @3xl/dbinfo:grid-cols-4">
        {Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className={cn('h-[104px] rounded-xl', sk)} />)}
      </div>
      <div aria-hidden="true" className="grid grid-cols-1 gap-4 @3xl/dbinfo:grid-cols-2">
        {Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className={cn('h-[260px] rounded-xl', sk)} />)}
      </div>
    </div>
  )
}
