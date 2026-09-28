import { Fragment } from 'react'
import { AlertTriangle, CheckCircle2, Info, XCircle } from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import HintPopover from '../../ui/HintPopover.jsx'
import { cn } from '@/lib/utils'

/**
 * HTTP istekleri ekranlarının (Sistem Sağlığı bölümü + İstek Gezgini) ortak shadcn parçaları (2026-09-28).
 * Legacy sınıf yok; ton yalnız jeton + simge (renk tek başına anlam taşımaz — ekran okuyucuya metin olarak da söylenir).
 * Kartlarda SOL RENKLİ ŞERİT YOK (kullanıcı kuralı 2026-09-26).
 */

/** ModalShell (z 2000+) üstünde açılan Sheet'in katmanı — örtü de içerik de pencerenin üstünde kalsın. */
export const OVER_MODAL_Z = 'z-[calc(var(--z-modal)_+_5)]'

export const TONE_INK = { ok: 'text-success', warn: 'text-amber-700 dark:text-amber-300', crit: 'text-destructive', neutral: 'text-foreground' }
const TONE_ICON = { ok: CheckCircle2, warn: AlertTriangle, crit: XCircle }

/** Tonlu değer: uyarı/kritikte simge + gizli ton metni (renk tek başına bilgi taşımaz). */
export function ToneValue({ tone = 'neutral', t, reserve = false, className, children }) {
  const Icon = tone === 'warn' || tone === 'crit' ? TONE_ICON[tone] : null
  // Tablo sütununda "iyi" değer yeşil boyanmaz (her satır yeşil = gürültü); yalnız uyarı/kritik öne çıkar.
  const ink = reserve && tone === 'ok' ? TONE_INK.neutral : (TONE_INK[tone] || TONE_INK.neutral)
  return (
    <span data-tone={tone} className={cn('inline-flex items-center gap-1 tabular-nums', ink, className)}>
      {children}
      {Icon && <Icon aria-hidden="true" className="size-3.5 shrink-0" />}
      {!Icon && reserve && <span aria-hidden="true" className="size-3.5 shrink-0" />}
      {Icon && <span className="sr-only">({t(`hreq.tone.${tone}`)})</span>}
    </span>
  )
}

/** HTTP yöntemi rozeti — nötr, eş genişlikte (tablo/kart hizası), eşmerkezli yazı. */
export function MethodBadge({ method, className }) {
  if (!method) return null
  return (
    <Badge variant="outline" data-slot="http-method" data-method={method}
      className={cn('h-5 min-w-[3.25rem] justify-center rounded-md px-1.5 font-mono text-[10.5px] font-bold tracking-wide text-muted-foreground', className)}>
      {method}
    </Badge>
  )
}

/**
 * Yöntem + yol şablonu: yol eş aralıklı ve KIRPILMADAN sarılır. Kırılma önce "/" sınırlarında (`<wbr>`), yalnız tek
 * parça sığmazsa harf ortasında (`overflow-wrap:anywhere`) — "/api/auth/logi|n" gibi okunaksız bölünme olmasın.
 */
export function EndpointLabel({ endpoint, method, path, className }) {
  const parts = String(path || endpoint || '').split('/')
  return (
    <span data-slot="http-endpoint" className={cn('flex min-w-0 items-start gap-2', className)}>
      <MethodBadge method={method} className="mt-px shrink-0" />
      <span className="min-w-0 font-mono text-[12.5px] leading-snug [overflow-wrap:anywhere] text-foreground">
        {parts.map((seg, i) => (
          <Fragment key={i}>{i > 0 && <>/<wbr /></>}{seg}</Fragment>
        ))}
      </span>
    </span>
  )
}

/**
 * Özet kutucuğu. Açıklama DOKUN-GÖR (`ui/HintPopover`, telefonda da açılır). `onClick` verilirse kutucuk bir eylem
 * düğmesidir (ör. "en yavaş uç" → gezgini o uçla açar) ve açıklama yerine alt satır okunur. Erişilebilir ad =
 * etiket + değer + ton + alt satır. Test kancaları: `data-slot="hreq-tile"`, `data-kpi`, `data-tone`.
 */
export function Tile({ id, label, value, sub, hint, tone = 'neutral', t, onClick, actionLabel, className, valueClassName }) {
  const Icon = TONE_ICON[tone]
  // Düğme adı açıkça: iç içe span metinleri boşluksuz birleşip "Toplam istek1.500ort. 25/dk" okunuyordu.
  const name = [label, value, (tone === 'warn' || tone === 'crit') ? t(`hreq.tone.${tone}`) : null, sub].filter(Boolean).join(', ')
  const body = (
    <>
      <span className="flex w-full items-center gap-1 text-xs font-medium text-muted-foreground">
        <span className="min-w-0 truncate">{label}</span>
        {!onClick && hint && <Info aria-hidden="true" className="ml-auto size-3 shrink-0 opacity-50" />}
      </span>
      <span className="flex max-w-full min-w-0 items-center gap-1.5">
        <span data-slot="hreq-tile-value" className={cn('truncate text-lg leading-tight font-bold tabular-nums', TONE_INK[tone] || TONE_INK.neutral, valueClassName)}>{value}</span>
        {Icon && tone !== 'ok' && <Icon aria-hidden="true" className={cn('size-4 shrink-0', TONE_INK[tone])} />}
        {(tone === 'warn' || tone === 'crit') && <span className="sr-only">({t(`hreq.tone.${tone}`)})</span>}
      </span>
      {sub && <span className="w-full truncate text-xs text-muted-foreground tabular-nums">{sub}</span>}
    </>
  )
  const box = cn(
    'flex h-full min-h-[76px] w-full min-w-0 flex-col items-start justify-start gap-1 rounded-lg border bg-card px-3 py-2.5 text-left font-normal whitespace-normal shadow-xs',
    'transition-[border-color,box-shadow] hover:border-ring/60 hover:bg-card motion-reduce:transition-none dark:hover:bg-card',
    className,
  )
  if (onClick) {
    return (
      <Button type="button" variant="outline" data-slot="hreq-tile" data-kpi={id} data-tone={tone} onClick={onClick}
        aria-label={actionLabel} className={cn(box, 'h-auto')}>
        {body}
      </Button>
    )
  }
  if (!hint) {
    return <Card data-slot="hreq-tile" data-kpi={id} data-tone={tone} className={cn(box, 'gap-1')}>{body}</Card>
  }
  return (
    <HintPopover content={hint} data-slot="hreq-tile" data-kpi={id} data-tone={tone} aria-label={name} triggerClassName={box}>
      {body}
    </HintPopover>
  )
}

/** Panel kartı: başlık satırı (başlık + sağ öğeler) + gövde. */
export function Panel({ title, titleId, right, className, children, ...rest }) {
  return (
    <Card className={cn('min-w-0 gap-3 px-3 py-3 shadow-xs sm:px-4 sm:py-4', className)} {...rest}>
      {(title || right) && (
        <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between">
          {title && <h4 id={titleId} className="m-0 min-w-0 text-sm font-semibold break-words text-foreground">{title}</h4>}
          {right && <div className="flex min-w-0 flex-wrap items-center gap-1.5">{right}</div>}
        </div>
      )}
      {children}
    </Card>
  )
}
