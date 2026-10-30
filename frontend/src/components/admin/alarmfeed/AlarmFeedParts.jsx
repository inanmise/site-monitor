import { useEffect, useId, useRef, useState } from 'react'
import { Check, Copy } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { OUTCOME_TONE, durationParts } from './alarmFeedModel.js'
import { copyText } from '../../../utils/copyText.js'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

/** Dokunmatikte en az 40 px hedef (RESPONSIVE.md §4). */
export const TOUCH = 'pointer-coarse:min-h-10'
/** Ad / adres / IP rozeti — sarar, kırpılmaz (CLAUDE.md "names never clipped"). */
export const CHIP = 'h-auto rounded-md overflow-visible whitespace-normal [overflow-wrap:anywhere]'
/** Uzun değer (IP, user agent, adres) — sarar. */
export const WRAP = 'min-w-0 [overflow-wrap:anywhere]'
/** Tek aralıklı, kayan metin bloğu (yanıt gövdesi, örnek komut). */
export const PRE = 'm-0 max-h-96 overflow-auto rounded-md border bg-muted/50 px-3 py-2.5 font-mono text-xs whitespace-pre-wrap [overflow-wrap:anywhere]'

/** Kart başlığı + isteğe bağlı eylemler — telefonda alt alta, geniş ekranda yan yana. */
export function SectionCard({ slot, icon: Icon, title, description, actions, children, className = '', contentClassName = '', ...rest }) {
  const id = useId()
  return (
    <Card data-slot={slot} aria-labelledby={id} role="region" className={cn('gap-4', className)} {...rest}>
      <CardHeader className="border-b border-border px-4 [.border-b]:pb-4 sm:px-6">
        <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start sm:justify-between sm:gap-4">
          <div className="flex min-w-0 flex-col gap-1">
            <CardTitle id={id} role="heading" aria-level={4} className="flex min-w-0 items-center gap-2 leading-snug">
              {Icon && <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />}
              <span className="min-w-0 [overflow-wrap:anywhere]">{title}</span>
            </CardTitle>
            {description && <CardDescription className="max-w-[78ch]">{description}</CardDescription>}
          </div>
          {actions && <div className="flex flex-wrap items-center gap-2 sm:shrink-0 sm:justify-end">{actions}</div>}
        </div>
      </CardHeader>
      <CardContent className={cn('flex min-w-0 flex-col gap-4 px-4 sm:px-6', contentClassName)}>{children}</CardContent>
    </Card>
  )
}

/** Çağrı sonucu rozeti (OK yeşil, DISABLED soluk, IP_DENIED / RATE_LIMITED sarı, ERROR kırmızı); bilinmiyor → "—". */
export function OutcomeBadge({ outcome, className = '' }) {
  const t = useT()
  if (!outcome) return <span className="text-muted-foreground" data-slot="af-outcome" data-outcome="none">—</span>
  const known = Object.prototype.hasOwnProperty.call(OUTCOME_TONE, outcome)
  return (
    <ToneBadge tone={OUTCOME_TONE[outcome] || 'muted'} className={cn(CHIP, className)} data-slot="af-outcome" data-outcome={outcome}>
      {known ? t(`af.outcome.${outcome}`) : outcome}
    </ToneBadge>
  )
}

/** Süre metni; bilinmiyor → "—". */
export function Dur({ ms, lang }) {
  const t = useT()
  const p = durationParts(ms, lang)
  return p ? t(p.key, ...p.params) : '—'
}

/** Etiket + değer satırı (dl içinde). Değer sarar. */
export function KV({ label, children, slot }) {
  return (
    <div className="grid min-w-0 gap-0.5 sm:grid-cols-[minmax(9rem,30%)_minmax(0,1fr)] sm:items-baseline sm:gap-3" data-slot={slot}>
      <dt className="text-xs font-semibold text-muted-foreground">{label}</dt>
      <dd className="m-0 flex min-w-0 flex-wrap items-center gap-1.5 text-sm [overflow-wrap:anywhere]">{children}</dd>
    </div>
  )
}

/**
 * Metinli kopyala düğmesi ("JSON'u kopyala", "Gövdeyi kopyala") — ui/CopyButton yalnız ikon çizer. Geri bildirim yerinde
 * (2 sn "Kopyalandı"); kopyalanamazsa sessiz (kullanıcı metni elle seçebilir).
 */
export function CopyTextButton({ value, label, copiedLabel, slot, className = '', disabled = false }) {
  const [copied, setCopied] = useState(false)
  const timer = useRef(null)
  useEffect(() => () => clearTimeout(timer.current), [])
  async function onCopy() {
    if (!(await copyText(value))) return
    setCopied(true)
    clearTimeout(timer.current)
    timer.current = setTimeout(() => setCopied(false), 2000)
  }
  return (
    <Button type="button" variant="outline" size="sm" onClick={onCopy} disabled={disabled} {...(slot ? { 'data-slot': slot } : {})}
      className={cn('h-auto min-h-8 whitespace-normal', TOUCH, className)}>
      {copied ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />} {copied ? copiedLabel : label}
    </Button>
  )
}
