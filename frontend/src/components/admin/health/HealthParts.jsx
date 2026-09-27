import { RefreshCw } from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { ChartContainer, AreaChart, Area } from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'
import CollapsibleSection from '../../ui/CollapsibleSection.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import SimpleTooltip from '../../ui/SimpleTooltip.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { BADGE_TONE } from './healthModel.js'

/**
 * Sistem Sağlığı'nın ortak shadcn parçaları (2026-09-27 yeniden tasarım). Legacy sınıf taşımaz; `.hb-ecg-*` /
 * `.smtp-stream-*` süsleri kaldırıldı (heartbeat şeridi artık ChartContainer, HeartbeatSection).
 */
export const OK_T = 'text-success'
export const WARN_T = 'text-amber-600 dark:text-amber-400'
export const ERR_T = 'text-destructive'
export const MUTED = 'text-[0.9em] text-muted-foreground'
export const LEVEL_T = { ok: OK_T, warn: WARN_T, down: ERR_T, off: 'text-muted-foreground', unknown: 'text-muted-foreground' }
/** Düşük öncelikli sütunlar telefonda gizli (mobil-önce): md = 768 px+, lg = 1024 px+. */
export const MD = 'hidden md:table-cell'
export const LG = 'hidden lg:table-cell'
export const CARD_GRID = 'grid grid-cols-[repeat(auto-fit,minmax(min(300px,100%),1fr))] gap-3 sm:gap-4'
export const CHART_GRID = 'grid grid-cols-[repeat(auto-fit,minmax(min(280px,100%),1fr))] gap-3'

/** Seviye rozeti (ok/warn/down/off/unknown → metin sözlükten). Test kancası: `data-level`. */
export function LevelBadge({ level = 'unknown', t, className, ...rest }) {
  const key = { ok: 'health.stOk', warn: 'health.stWarn', down: 'health.stDown', off: 'health.stOff' }[level] || 'health.stUnknown'
  return (
    <ToneBadge tone={BADGE_TONE[level] || 'muted'} data-level={level} className={cn('font-semibold', className)} {...rest}>
      {t(key)}
    </ToneBadge>
  )
}

/**
 * Katlanır bölüm: shadcn Collapsible (ui/CollapsibleSection, kanca `data-slot="stats-toggle"`), başlıkta seviye rozeti,
 * kapalıyken özet ipucu. `?sec=` derin bağlantısı ve KPI tıklaması `data-section` ile kaydırır. İçerik kapalıyken DOM'da yok.
 */
export function HealthSection({ id, icon, label, level, summary, open, onToggle, t, sectionRef, children }) {
  const title = (
    <span className="inline-flex min-w-0 flex-wrap items-center gap-2">
      <span className="min-w-0 break-words">{label}</span>
      {level && level !== 'ok' && <LevelBadge level={level} t={t} data-slot="section-level" />}
      {level === 'ok' && <LevelBadge level="ok" t={t} data-slot="section-level" className="hidden sm:inline-flex" />}
    </span>
  )
  return (
    <div ref={sectionRef} data-section={id} className="scroll-mt-28 md:scroll-mt-4">
      <CollapsibleSection open={open} onOpenChange={() => onToggle(id)} icon={icon} label={title}
        hint={summary || t('health.sectionShow', label)} toggleLabel={t('health.sectionShow', label)} contentClassName="pt-3">
        {children}
      </CollapsibleSection>
    </div>
  )
}

/** Bölüm içi araç satırı: sol açıklama/güncellenme, sağ eylemler (başlık tetiği düğme olduğu için eylemler burada). */
export function SectionToolbar({ left, children }) {
  if (!left && !children) return null
  return (
    <div className="mb-3 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
      <div className="min-w-0 text-xs text-muted-foreground">{left}</div>
      {children && <div className="flex flex-wrap items-center gap-2">{children}</div>}
    </div>
  )
}

/** Tazeleme ikon düğmesi (40 px dokunma hedefi). */
export function RefreshButton({ onClick, busy, label, size = 'icon' }) {
  return (
    <SimpleTooltip content={label}>
      <Button type="button" variant="ghost" size={size} onClick={onClick} disabled={busy} aria-label={label} aria-busy={busy || undefined}>
        <RefreshCw aria-hidden="true" className={cn(busy && 'animate-spin motion-reduce:animate-none')} />
      </Button>
    </SimpleTooltip>
  )
}

/**
 * Sistem kartı: başlık satırı (ikon + başlık + sağ öğeler) + gövde. Alarmda kartın TAMAMI kırmızı çerçevelenir — sol
 * renk şeridi YOK (kullanıcı kararı 2026-09-26). `onOpen` verilirse karta tıklamak açar (fare kısayolu); klavye yolu
 * gövdedeki CTA düğmesidir. Test kancaları: `data-slot="sys-card"`, `data-alarm`, `data-card`.
 */
export function SysCard({ icon: Icon, iconClass, title, right, alarm = false, onOpen, testId, cardKey, className, children }) {
  return (
    <Card data-slot="sys-card" data-card={cardKey} data-alarm={alarm ? 'true' : undefined} data-testid={testId} onClick={onOpen}
      className={cn('min-w-0 gap-2.5 px-4 py-3.5 shadow-xs',
        alarm && 'border-destructive/60 dark:border-destructive/70',
        onOpen && 'cursor-pointer transition-[border-color,box-shadow] hover:border-primary/60 hover:shadow-md motion-reduce:transition-none',
        className)}>
      <div className="flex items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2">
          {Icon && <Icon size={20} aria-hidden="true" className={cn('shrink-0', iconClass)} />}
          <h3 className="min-w-0 truncate text-[0.95em] font-semibold">{title}</h3>
        </div>
        {right && <div className="flex shrink-0 items-center gap-1.5" onClick={(e) => e.stopPropagation()}>{right}</div>}
      </div>
      {children}
    </Card>
  )
}

/** Anahtar/değer listesi. */
export function KvList({ children, className }) {
  return (
    <dl data-slot="kv-list"
      className={cn('grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 text-[0.86em] [&_dd]:min-w-0 [&_dd]:break-words [&_dt]:text-muted-foreground', className)}>
      {children}
    </dl>
  )
}

/** Kart alt eylem düğmesi (kartı açan tıklamanın KLAVYE yolu). */
export function CardCta({ icon: Icon, label, badge, onClick, variant = 'outline' }) {
  return (
    <Button type="button" variant={variant} size="sm" className="mt-1 h-10 w-full justify-start gap-2 sm:h-9"
      onClick={(e) => { e.stopPropagation(); onClick() }}>
      {Icon && <Icon aria-hidden="true" />}
      <span className="min-w-0 flex-1 truncate text-left">{label}</span>
      {badge && <Badge variant="secondary" className="font-normal">{badge}</Badge>}
    </Button>
  )
}

/** Bölüm içi alt başlık. */
export function SubTitle({ children, className }) {
  return <h3 className={cn('mt-1 flex flex-wrap items-center gap-2 text-[13px] font-bold tracking-[.06em] uppercase', className)}>{children}</h3>
}

/**
 * Minik kıvılcım grafiği (shadcn ChartContainer + recharts Area). Dekoratif: değer yanındaki metinde yazılı.
 * KpiCard bir Button'dur ve iç SVG'yi `size-4`'e zorlar → `[&_svg]:size-full!` ile kap boyutuna geri alınır.
 */
export function HealthSpark({ data, color = 'var(--primary)', className }) {
  if (!Array.isArray(data) || data.length < 2) return null
  return (
    <ChartContainer config={{ v: { color } }} aria-hidden="true" data-slot="health-spark"
      className={cn('pointer-events-none aspect-auto h-8 w-full [&_svg]:size-full!', className)}>
      <AreaChart data={data} margin={{ top: 2, right: 0, bottom: 0, left: 0 }}>
        <Area type="monotone" dataKey="v" stroke="var(--color-v)" fill="var(--color-v)" fillOpacity={0.14} strokeWidth={1.5}
          dot={false} isAnimationActive={false} />
      </AreaChart>
    </ChartContainer>
  )
}

/** Bölüm yükleme hatası — StatusBlock (danger) + "Tekrar dene". Test kancası: `[data-slot="empty"][data-tone="danger"]`. */
export function SectionError({ t, onRetry, description }) {
  return (
    <StatusBlock tone="danger" title={t('health.loadErrorTitle')} description={description || t('health.sectionFailed')}
      className="py-6" role="alert"
      actions={onRetry && <Button type="button" variant="secondary" size="sm" onClick={onRetry}>{t('db.retry')}</Button>} />
  )
}

/** İlk yükleme iskeleti: bant + KPI ızgarası + bölüm şeritleri (gerçek yerleşimle aynı boyutlar, zıplama yok). */
export function HealthSkeleton({ sections = 8, label }) {
  const sk = 'motion-reduce:animate-none'
  return (
    <div data-slot="health-skeleton" role="status" aria-live="polite" className="flex flex-col gap-3 py-1">
      <span className="sr-only">{label}</span>
      <Skeleton className={cn('h-[92px] rounded-xl', sk)} />
      <div className="grid grid-cols-2 gap-2 sm:gap-3 md:grid-cols-4">
        {Array.from({ length: 8 }, (_, i) => <Skeleton key={i} className={cn('h-[118px] rounded-xl', sk)} />)}
      </div>
      {Array.from({ length: sections }, (_, i) => <Skeleton key={i} className={cn('h-11 rounded-[10px]', sk)} />)}
    </div>
  )
}
