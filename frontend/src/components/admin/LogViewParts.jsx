import { Children } from 'react'
import { ArrowLeft, ChevronUp, ChevronDown, X } from 'lucide-react'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { TONE_CLASS } from './ToneBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { TableHead } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * Gönderim logu ekranlarının (SMTP Gönderim Logu · Webhook Push Logu) ortak shadcn parçaları. İki sayfa
 * eskiden aynı legacy sözlüğü (`.sml-*`, `.rn-kpi*`, `.smtp-kind-*`, `.smtp-trigger-*`, `.today-level*`)
 * paylaşıyordu; burada aynı görsel dil Card / Button / Badge / Table / ToggleGroup ile tek yerde.
 */

/** Sayfa başlığı: geri + ikonlu başlık + alt satır + eylemler. */
export function LogHeader({ icon: Icon, title, subtitle, backLabel, onBack, actions }) {
  return (
    <div className="flex flex-wrap items-center gap-3">
      <Button type="button" variant="secondary" onClick={onBack}><ArrowLeft size={14} /> {backLabel}</Button>
      <div className="flex min-w-0 flex-1 basis-[240px] items-center gap-2.5">
        <Icon size={18} aria-hidden="true" className="shrink-0 text-primary" />
        <div>
          <h3 className="text-[1.1em] font-semibold">{title}</h3>
          <div className="text-xs text-muted-foreground">{subtitle}</div>
        </div>
      </div>
      <div className="flex gap-2">{actions}</div>
    </div>
  )
}

/** Zaman aralığı seçici (24s / 7g / 30g / özel) — ui/SegmentedControl (shadcn ToggleGroup). */
export function RangeControl({ value, onChange, ranges, label, t }) {
  return (
    <SegmentedControl value={value} onChange={onChange} ariaLabel={label}
      options={[...ranges, 'custom'].map((r) => ({ value: r, label: t(`sml.range.${r}`) }))} />
  )
}

/**
 * KPI kutusu — tıklanabilir (süzgeç) ise shadcn Button, değilse Card. Ton `color` (hex) ile verilir:
 * `--kpi` özel değişkeni → sayı rengi + etiket önündeki nokta (`text-(--kpi)` / `bg-(--kpi)`). Sol renk
 * şeridi YOK (kullanıcı kararı 2026-09-26); seçili kutu çerçevenin TAMAMIYLA belirtilir.
 */
export function KpiTile({ color, value, label, active = false, onClick, small = false }) {
  const body = (
    <>
      <span className={cn('leading-none font-extrabold text-(--kpi) tabular-nums', small ? 'text-base' : 'text-[1.9em]')}>{value}</span>
      <span className="inline-flex items-center gap-1.5 text-[0.74em] font-semibold tracking-wide text-muted-foreground uppercase">
        <span aria-hidden="true" className="size-2 flex-none rounded-full bg-(--kpi)" />{label}
      </span>
    </>
  )
  const base = 'flex min-h-10 flex-col items-start gap-1 rounded-lg border px-3.5 py-3 text-left border-border'
  if (!onClick) {
    return <Card style={{ '--kpi': color }} className={cn(base, 'shadow-none')}>{body}</Card>
  }
  return (
    <Button type="button" variant="outline" onClick={onClick} aria-pressed={active} style={{ '--kpi': color }}
      className={cn(base, 'h-auto justify-start whitespace-normal shadow-none transition-[box-shadow,transform] hover:-translate-y-px hover:shadow-md motion-reduce:hover:translate-y-0',
        active && 'border-(--kpi) bg-(--kpi)/10')}>
      {body}
    </Button>
  )
}

/** Bölüm kartı (başlık + ipucu satırı) — shadcn Card. */
export function LogCard({ title, hint, className = '', children, ...rest }) {
  return (
    <Card className={cn('min-w-0 gap-2 px-3.5 py-3 shadow-none', className)} {...rest}>
      {(title || hint) && (
        <div className="flex flex-wrap items-center justify-between gap-2 font-semibold">
          <span>{title}</span>
          {hint && <span className="text-xs font-normal text-muted-foreground">{hint}</span>}
        </div>
      )}
      {children}
    </Card>
  )
}

/** Sıralanabilir sütun başlığı — shadcn TableHead + ghost Button; etkin sütunda yön oku. */
export function SortHead({ label, field, sort, onSort, className = '' }) {
  const [cur, dir] = String(sort || '').split(',')
  return (
    <TableHead className={cn('px-2', className)}>
      <Button type="button" variant="ghost" size="xs" className="-ml-1 h-auto px-1 py-0.5 font-medium hover:text-primary"
        onClick={() => onSort(field)}>
        {label} {cur === field ? (dir === 'asc' ? <ChevronUp size={12} /> : <ChevronDown size={12} />) : null}
      </Button>
    </TableHead>
  )
}

/** Çocuklar yalnız metin/sayıdan oluşuyorsa birleşik metin (çipin `title`'ı) — öğe varsa undefined. */
function plainText(children) {
  const parts = Children.toArray(children)
  return parts.length && parts.every((p) => typeof p === 'string' || typeof p === 'number') ? parts.join('') : undefined
}

/**
 * Etkin süzgeç çipi (tıklayınca kaldırır) — hap biçimli outline Button. Uzun değer (alıcı e-postası, alan adı) çipi
 * kapsayıcıdan taşırmaz: çip en fazla satır genişliğinde, metin "…" ile kısalır, tamamı `title`'da (2026-10-09).
 * Telefon/dokunmatikte 40 px yükseklik.
 */
export function FilterChip({ onClick, children }) {
  return (
    <Button type="button" variant="outline" size="xs" onClick={onClick}
      className="max-w-full rounded-full border-primary bg-primary/10 font-normal text-primary hover:bg-primary/15 hover:text-primary max-md:h-10 pointer-coarse:h-10">
      <span className="min-w-0 truncate" title={plainText(children)}>{children}</span> <X size={11} aria-hidden="true" />
    </Button>
  )
}

/** Gönderim durumu rozeti (ikonlu) — ton: success | danger | muted. */
export function KindBadge({ tone, icon: Icon, title, children }) {
  return (
    <Badge variant="outline" title={title} data-tone={tone}
      className={cn('rounded-[5px] px-2 font-bold tracking-wide', TONE_CLASS[tone] || TONE_CLASS.muted)}>
      {Icon && <Icon size={11} aria-hidden="true" />}{children}
    </Badge>
  )
}

/** Tetikleyici rozetleri (SMTP + push tetik sözlükleri) — büyük harf, tonlu. */
const TRIGGER_TONE = {
  initial: TONE_CLASS.info, open: TONE_CLASS.info,
  escalation: 'border-transparent bg-orange-500/15 text-orange-700 dark:text-orange-300',
  daily_realert: 'border-transparent bg-violet-500/15 text-violet-700 dark:text-violet-300',
  re_alert: 'border-transparent bg-violet-500/15 text-violet-700 dark:text-violet-300',
  resolution: TONE_CLASS.success, resolve: TONE_CLASS.success,
  manual: TONE_CLASS.muted,
}
export function TriggerBadge({ trigger, children }) {
  const key = String(trigger || '').toLowerCase()
  return (
    <Badge variant="outline" data-trigger={key || undefined}
      className={cn('rounded-sm text-[0.72em] font-bold tracking-wider uppercase', TRIGGER_TONE[key] || TONE_CLASS.muted)}>
      {children}
    </Badge>
  )
}

/** Alarm seviyesi rozeti (eski `.today-level--*`). */
const LEVEL_TONE = {
  critical: TONE_CLASS.danger,
  high: 'border-transparent bg-orange-500/15 text-orange-700 dark:text-orange-300',
  warning: 'border-transparent bg-yellow-400/20 text-yellow-800 dark:text-yellow-200',
}
export function LevelBadge({ level }) {
  const key = String(level || '').toLowerCase()
  return (
    <Badge variant="outline" data-level={key || undefined}
      className={cn('rounded-md px-1.5 text-[0.7em] font-bold', LEVEL_TONE[key] || TONE_CLASS.muted)}>
      {level}
    </Badge>
  )
}

/** İzleme türü rozeti (HTTP/PING/PORT…; eski `.today-type`). */
export function TypeBadge({ children }) {
  return (
    <Badge variant="outline" className={cn('rounded-md px-1.5 text-[0.68em] font-bold tracking-wide', TONE_CLASS.muted)}>
      {children}
    </Badge>
  )
}

/** Hata sınıfı rozeti (eski `.sml-cls`). */
export function ErrorClassBadge({ children }) {
  return <Badge variant="outline" className={cn('mt-0.5 rounded-md px-1.5 text-[0.7em] font-bold tracking-wide', TONE_CLASS.danger)}>{children}</Badge>
}

/** Ayrıntı penceresi: etiket–değer satırı ve bölüm etiketi. */
export function MetaRow({ label, children }) {
  return (
    <div className="flex flex-wrap items-baseline gap-2.5 text-sm">
      <span className="min-w-20 shrink-0 text-[0.72em] font-bold tracking-wider text-muted-foreground uppercase">{label}</span>
      {children}
    </div>
  )
}
export function SectionLabel({ children }) {
  return <div className="pt-2.5 pb-1 text-[0.72em] font-bold tracking-wider text-muted-foreground uppercase">{children}</div>
}

/** Aynı alarmın/batch'in diğer gönderimleri — seçilebilir liste (shadcn outline Button); geçerli kayıt pasif. */
export function ChainList({ items, currentId, onPick, render }) {
  return (
    <ul className="flex list-none flex-col gap-1">
      {items.map((c) => {
        const current = c.id === currentId
        return (
          <li key={c.id}>
            <Button type="button" variant="outline" size="sm" data-chain-id={c.id} disabled={current} onClick={() => onPick(c.id)}
              className={cn('h-auto w-full flex-wrap justify-start gap-2.5 px-2 py-1.5 font-normal',
                current && 'border-primary bg-primary/10 disabled:opacity-100')}>
              {render(c)}
            </Button>
          </li>
        )
      })}
    </ul>
  )
}

/** Ön biçimli metin kutusu (push mesajı / ham yanıt). */
export const PRE = 'mb-2 max-h-64 overflow-auto rounded-lg border bg-muted/60 px-3 py-2.5 text-[0.82em] break-words whitespace-pre-wrap border-border'
/** İkincil küçük metin. */
export const MUTED_SM = 'text-xs text-muted-foreground'
