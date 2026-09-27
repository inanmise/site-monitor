import { useId } from 'react'
import { Ban, BellOff, CircleCheck, CirclePause, ShieldCheck, ShieldOff } from 'lucide-react'
import { TAB_META } from '../palette/paletteModel.js'
import ToneBadge from '../admin/ToneBadge.jsx'
import HelpTip from '../ui/HelpTip.jsx'
import { Label } from '@/components/shadcn/label'
import { Switch } from '@/components/shadcn/switch'
import { cn } from '@/lib/utils'

/**
 * Aç/kapa satırı (shadcn Field "choice card" deseni): etiket + yardım balonu + Switch TEK bir <label> içinde — satırın
 * tamamı dokunma hedefidir (≥44 px; Switch'in kendisi 18 px). HelpTip etiketin içinde (span role=button; tıklaması
 * etiket davranışını keser — helpLabel ile aynı sözleşme). Açıkken çerçeve vurgulu. Test kancası: `data-slot="noc-switch-row"`.
 */
export function NocSwitchRow({ checked, onChange, label, hint, helpKey, disabled = false }) {
  const id = useId()
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <Label htmlFor={id} data-slot="noc-switch-row" data-on={checked ? 'true' : 'false'}
        className={cn('flex min-h-11 w-full cursor-pointer items-center gap-3 rounded-lg border px-3 py-2 font-medium leading-snug transition-colors',
          checked ? 'border-primary/30 bg-primary/5 dark:bg-primary/10' : 'bg-card', disabled && 'cursor-default')}>
        <span className="min-w-0 flex-1">{label}{helpKey && <HelpTip helpKey={helpKey} label={label} />}</span>
        <Switch id={id} checked={!!checked} onCheckedChange={onChange} disabled={disabled} />
      </Label>
      {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  )
}

/**
 * 7/24 (NOC) yüzeylerinin ortak sunum parçaları (2026-09-27): tür ikonu/etiketi ve kapsam durumu rozeti.
 *
 * Tür ikonları kenar çubuğundakilerle AYNI (TAB_META — Nav.jsx'in kopyası, komut paleti de oradan okur); SSL bir
 * izleme sekmesi değil sertifika envanteri → sertifika grubunun ikonu (ShieldCheck).
 */
export const NOC_TYPE_ICON = {
  SSL: ShieldCheck,
  PING: TAB_META.ping.Icon,
  HTTP: TAB_META.http.Icon,
  KEYWORD: TAB_META.keyword.Icon,
  PAGE: TAB_META.page.Icon,
  PAGESPEED: TAB_META.pagespeed.Icon,
  SCRIPTED: TAB_META.scripted.Icon,
  DNS: TAB_META.dns.Icon,
  PORT: TAB_META.port.Icon,
  DOMAIN: TAB_META.domain.Icon,
}

/** Tür etiketi (kısa) — `noc.type.<TÜR>`. */
export const typeLabel = (t, type) => t(`noc.type.${type}`)

/** Tür ikonu + (isteğe bağlı) etiket. İkon süs: ad metinde. */
export function NocTypeTag({ type, t, withLabel = true, className }) {
  const Icon = NOC_TYPE_ICON[type]
  return (
    <span data-slot="noc-type" data-type={type} className={cn('inline-flex min-w-0 items-center gap-1.5 text-muted-foreground', className)}>
      {Icon && <Icon aria-hidden="true" className="size-4 shrink-0" />}
      {withLabel && <span className="truncate text-xs font-medium">{typeLabel(t, type)}</span>}
    </span>
  )
}

/** Kapsam durumu → ton + ikon. Anahtar: `covered` ya da `reason` değeri. */
const REASON_META = {
  covered:         { tone: 'success', Icon: CircleCheck },
  MONITOR_OFF:     { tone: 'warning', Icon: BellOff },
  TYPE_DISABLED:   { tone: 'muted',   Icon: Ban },
  NO_ACTIVE_GROUP: { tone: 'danger',  Icon: ShieldOff },
  PAUSED:          { tone: 'muted',   Icon: CirclePause },
}

/**
 * Kapsam rozeti — düz sözcüklerle ("İzleme 7/24'e bildirmiyor", "Tür yönetici tarafından kapatıldı" …). Durum
 * metin + ton ile taşınır (renk tek başına anlam taşımaz). Test kancası: `data-slot="noc-reason"` + `data-reason`.
 */
export function ReasonBadge({ item, t, className }) {
  const key = item?.covered ? 'covered' : (REASON_META[item?.reason] ? item.reason : 'MONITOR_OFF')
  const { tone, Icon } = REASON_META[key]
  return (
    <ToneBadge tone={tone} data-slot="noc-reason" data-reason={key}
      className={cn('max-w-full gap-1 font-semibold whitespace-normal text-left', className)}>
      <Icon aria-hidden="true" />
      <span className="min-w-0">{t(`noc.reason.${key}`)}</span>
    </ToneBadge>
  )
}
