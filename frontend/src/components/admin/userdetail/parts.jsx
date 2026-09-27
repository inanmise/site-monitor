import { AlertOctagon, RotateCcw } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'

/**
 * Kullanıcı ayrıntısı ortak parçaları — bölüm kartı, tanım satırı, yükleniyor iskeleti, hata ve boş durum.
 * shadcn Card / Badge / Skeleton / Empty (StatusBlock). SOL RENK ŞERİDİ YOK (kullanıcı kuralı): kartlar nötr kenarlı,
 * durum rozetle taşınır.
 */

/** Bölüm kartı: ikon kutucuğu + başlık (+ sayı rozeti, sağda eylem) → içerik. Başlık gerçek bir `h3`. */
export function SectionCard({ icon: Icon, title, count, action, description, className, bodyClassName, children, ...rest }) {
  return (
    <Card data-slot="ud-section" className={cn('min-w-0 gap-0 py-0 shadow-none', className)} {...rest}>
      <div className="flex min-w-0 flex-wrap items-center gap-x-2.5 gap-y-1.5 border-b px-4 py-2.5">
        {Icon && (
          <span aria-hidden="true" className="flex size-7 shrink-0 items-center justify-center rounded-md bg-muted text-foreground/80">
            <Icon className="size-4" />
          </span>
        )}
        <h3 className="m-0 min-w-0 flex-1 text-sm leading-snug font-semibold">
          {title}
          {count != null && (
            <Badge variant="secondary" data-slot="ud-section-count" className="ml-2 h-5 min-w-5 rounded-full px-1.5 align-middle tabular-nums">{count}</Badge>
          )}
        </h3>
        {action}
      </div>
      <div className={cn('flex min-w-0 flex-col gap-3 px-4 py-3.5', bodyClassName)}>
        {description && <p className="m-0 text-xs leading-relaxed text-muted-foreground">{description}</p>}
        {children}
      </div>
    </Card>
  )
}

/** Tanım satırı (etiket üstte, değer altta — olay ayrıntısıyla aynı dil). `wide` satırın tamamını kaplar. */
export function Fact({ label, children, wide = false, className }) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-0.5', wide && 'col-span-full', className)}>
      <dt className="text-[10.5px] font-semibold tracking-wider text-muted-foreground uppercase">{label}</dt>
      <dd className="m-0 min-w-0 text-sm [overflow-wrap:anywhere]">{children}</dd>
    </div>
  )
}

/** Değer yoksa soluk tire. */
export function Dash() {
  return <span className="text-muted-foreground">—</span>
}

/** Bölüm iskeleti — gerçek satır yüksekliğinde; ekran okuyucuya ayrıca "Yükleniyor" durumu. */
export function SectionSkeleton({ rows = 3, className }) {
  const t = useT()
  return (
    <div data-slot="ud-skeleton" className={cn('flex flex-col gap-2.5', className)}>
      <span role="status" className="sr-only">{t('app.loading')}</span>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="flex items-center gap-3">
          <Skeleton className="size-8 shrink-0 rounded-full motion-reduce:animate-none" />
          <div className="flex min-w-0 flex-1 flex-col gap-1.5">
            <Skeleton className="h-3.5 w-2/5 motion-reduce:animate-none" />
            <Skeleton className="h-3 w-4/5 motion-reduce:animate-none" />
          </div>
        </div>
      ))}
    </div>
  )
}

/** Bölüm hatası — neyin yüklenemediği + sunucu mesajı + Tekrar dene. Diğer bölümler çalışmaya devam eder. */
export function SectionError({ title, error, onRetry }) {
  const t = useT()
  return (
    <StatusBlock tone="danger" icon={AlertOctagon} title={title} description={error}
      className="rounded-lg border border-dashed border-destructive/40 px-4 py-6 md:py-6 text-sm"
      actions={onRetry && (
        <Button type="button" variant="outline" size="sm" className="h-10 sm:h-8" onClick={onRetry}
          aria-label={t('a11y.rowAction', title, t('ud.retry'))}>
          <RotateCcw aria-hidden="true" />{t('ud.retry')}
        </Button>
      )} />
  )
}

/** Boş bölüm — sade, küçük (tam ekran boş durumu değil). */
export function SectionEmpty({ icon, title, description }) {
  return (
    <StatusBlock tone="neutral" icon={icon} title={title} description={description}
      className="rounded-lg border border-dashed px-4 py-6 md:py-6 text-sm" />
  )
}
