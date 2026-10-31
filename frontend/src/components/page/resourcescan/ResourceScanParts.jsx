import { ArrowDownRight, ArrowUpRight, Code2, ExternalLink, File, FileCode2, FileText, Film, Frame, Image, Link2, Palette, Type, Minus, Star } from 'lucide-react'
import ToneBadge from '../../admin/ToneBadge.jsx'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

/**
 * Kaynak analizi ortak parçaları (2026-10-31): bölüm kartı, fark rozeti, kategori ikonları, ortak sınıflar.
 * Saf sunum — metin dışarıdan (çağıran `t()` ile verir).
 */

/** Dokunmatikte 40 px hedef (RESPONSIVE.md §4). */
export const TOUCH = 'pointer-coarse:min-h-10'
/** Uzun URL / ad: kesilmez, sarar (CLAUDE.md "Names and long text are never clipped"). */
export const WRAP = 'min-w-0 [overflow-wrap:anywhere]'
/** Sarabilen rozet (çok satırlı içerikte yuvarlak köşe kırpmasın). */
export const CHIP = 'h-auto min-h-5 rounded-md overflow-visible whitespace-normal text-left'

/** Kategori → ikon (lucide; emoji yok). */
export const CATEGORY_ICON = {
  HTML: FileCode2, CSS: Palette, JS: Code2, IMAGE: Image, FONT: Type, MEDIA: Film, IFRAME: Frame, FAVICON: Star,
  DOCUMENT: FileText, LINK: Link2, OTHER: File,
}

/** Ton → SVG / çubuk rengi (proje jetonları; bilinmeyen soluk — asla yeşil değil). */
export const TONE_STROKE = {
  success: 'var(--success)', warning: 'var(--warning)', danger: 'var(--destructive)', muted: 'var(--muted-foreground)', info: 'var(--primary)',
}
/** Ton → metin rengi (uyarı tonu amber-700: --warning beyaz zeminde okunmaz). */
export const TONE_TEXT = {
  success: 'text-success', warning: 'text-amber-700 dark:text-amber-300', danger: 'text-destructive', muted: 'text-muted-foreground',
  info: 'text-primary',
}
/** Ton → `ui/Progress` ProgressBar tonu. */
export const TONE_BAR = { success: 'ok', warning: 'warn', danger: 'crit' }

/** Bölüm kartı: başlık (ikon + metin + açıklama) ve sağda eylemler; gövde çocuklar. */
export function Section({ slot, icon: Icon, title, description, actions, className, contentClassName, children, titleFocusable = false, ...rest }) {
  return (
    <Card data-slot={slot} className={cn('min-w-0 gap-3 py-4 shadow-xs', className)} {...rest}>
      {(title || actions) && (
        <CardHeader className="flex flex-col gap-2 px-4 sm:flex-row sm:items-start sm:justify-between">
          <div className="flex min-w-0 flex-col gap-1">
            {title && (
              // Bölüm geçişinde odak başlığa taşınır (r177 H1) — `tabIndex=-1`: Tab sırasına girmez, yalnız programla odaklanır
              <CardTitle data-focus-target={titleFocusable ? slot : undefined} tabIndex={titleFocusable ? -1 : undefined}
                className="flex min-w-0 items-center gap-2 text-sm leading-snug font-semibold focus-visible:rounded-sm focus-visible:outline-2 focus-visible:outline-ring">
                {Icon && <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />}
                <span className={WRAP}>{title}</span>
              </CardTitle>
            )}
            {description && <CardDescription className={cn('text-xs', WRAP)}>{description}</CardDescription>}
          </div>
          {actions && <div className="flex flex-wrap items-center gap-2 sm:justify-end">{actions}</div>}
        </CardHeader>
      )}
      <CardContent className={cn('flex min-w-0 flex-col gap-3 px-4', contentClassName)}>{children}</CardContent>
    </Card>
  )
}

/**
 * Önceki tamamlanmış analize göre fark rozeti. `d` = model `delta()` sonucu (null → çizilmez). `text` görünen miktar
 * (işaretli, ör. "−0,42 sn"); `srText` ekran okuyucu cümlesi.
 */
export function DeltaChip({ d, text, srText, sameText }) {
  if (!d) return null
  const tone = d.direction === 'better' ? 'success' : d.direction === 'worse' ? 'danger' : 'muted'
  const Icon = d.direction === 'same' ? Minus : d.diff < 0 ? ArrowDownRight : ArrowUpRight
  return (
    <ToneBadge tone={tone} data-slot="prs-delta" data-direction={d.direction} className={cn(CHIP, 'gap-1 font-semibold tabular-nums')}>
      <Icon aria-hidden="true" />
      <span aria-hidden="true">{d.direction === 'same' ? sameText : text}</span>
      <span className="sr-only">{srText}</span>
    </ToneBadge>
  )
}

/** İşaretli miktar metni: eksi için tipografik eksi (−). */
export function signed(diff, fmtAbs) {
  if (diff === 0) return fmtAbs(0)
  return `${diff < 0 ? '−' : '+'}${fmtAbs(Math.abs(diff))}`
}

/**
 * Güvenli bağlantı: yalnız http(s) adresi tıklanır (javascript: / data: şeması bağlanmaz), yeni sekmede, URL sarar.
 * `label` verilirse görünen metin odur (adres yine `aria-label`'da).
 */
export function UrlText({ url, t, className, label }) {
  const safe = typeof url === 'string' && /^https?:\/\//i.test(url)
  if (!safe) return <span className={cn(WRAP, className)}>{label ?? (url || '—')}</span>
  return (
    <a href={url} target="_blank" rel="noopener noreferrer" data-slot="prs-obj-url"
      aria-label={t('prs.obj.openUrl', url)}
      className={cn('text-foreground underline-offset-2 hover:underline focus-visible:rounded-sm focus-visible:outline-2 focus-visible:outline-ring', WRAP, className)}>
      {label ?? url}
      <ExternalLink aria-hidden="true" className="ml-1 inline size-3 align-[-1px] text-muted-foreground" />
    </a>
  )
}

/** Küçük rozet (nesne bayrağı / bulgu) — sarar, kırpılmaz. */
export function SmallBadge({ tone = 'muted', children, className, ...rest }) {
  return (
    <ToneBadge tone={tone} className={cn(CHIP, 'px-1.5 py-0 text-[11px] font-medium', className)} {...rest}>{children}</ToneBadge>
  )
}
