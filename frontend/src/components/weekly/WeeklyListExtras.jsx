import { ArrowUp, ArrowDown, Sparkles, Eye, EyeOff } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { STATUS_CHIPS, scoreBand, delta } from './weeklyModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { TableHead } from '@/components/shadcn/table'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'

/*
 * Haftalık Raporlar liste/detay yardımcı parçaları — shadcn (ToggleGroup, TableHead + Button, Badge, Collapsible).
 * Test kancaları `data-slot` ile: wr-status-chips (+ öğede data-chip), wr-score (+ data-band), wr-delta
 * (+ data-good), wr-suggest (+ data-same), wr-prev-note.
 */

const ALL = '__all'

/**
 * Durum çipleri (facet sayaçlı) + "Onayımı bekleyenler" (2026-09-13). Tek seçim; etkin çipe yeniden basmak
 * seçimi kaldırır (''), "Tümü" de '' verir. shadcn ToggleGroup; erişilebilirlik ui/SegmentedControl sözleşmesi
 * (role="group" + aria-pressed'li düğmeler). Telefonda çipler satır kırar.
 */
export function WeeklyStatusChips({ facets, value, onChange, mineCount, showMine }) {
  const t = useT()
  const label = (k) => t(k === 'PENDING_APPROVAL' ? 'wr.statusPending' : `wr.status${k.charAt(0) + k.slice(1).toLowerCase()}`)
  const current = value || ALL
  const chip = (v, text, extra) => (
    <ToggleGroupItem key={v} value={v} role="button" aria-pressed={current === v} aria-checked={undefined} data-chip={v}
      className={cn('h-8 flex-none rounded-full border px-3 text-xs font-medium data-[state=on]:border-primary data-[state=on]:bg-primary data-[state=on]:text-primary-foreground', extra)}>
      {text}
    </ToggleGroupItem>
  )
  return (
    <ToggleGroup type="single" role="group" aria-label={t('wr.statusCol')} data-tour="wr-chips" data-slot="wr-status-chips"
      value={current} spacing={1.5}
      onValueChange={(v) => onChange(!v || v === ALL ? '' : v)}
      className="flex w-auto flex-wrap items-center">
      {chip(ALL, `${t('wr.chipAll')} (${facets.all})`)}
      {showMine && chip('MINE', `${t('wr.chipMine')} (${mineCount})`,
        'data-[state=on]:border-success data-[state=on]:bg-success data-[state=on]:text-white')}
      {STATUS_CHIPS.map((k) => chip(k, `${label(k)} (${facets[k] ?? 0})`))}
    </ToggleGroup>
  )
}

/** Rapor durumu → rozet mürekkebi (ince çerçeve + renkli metin; koyu temada karşılıklı). */
const STATUS_INK = {
  DRAFT: 'text-muted-foreground',
  PENDING_APPROVAL: 'text-amber-600 dark:text-amber-400',
  APPROVED: 'text-success',
  REJECTED: 'text-destructive',
}

/** Durum → i18n etiketi: APPROVED + gönderildi → "Gönderildi", APPROVED + gönderilmedi → "Onaylandı". */
export function statusLabel(t, status, sentAt) {
  if (status === 'APPROVED') return sentAt ? t('wr.statusSent') : t('wr.statusApproved')
  return t(`wr.status${status === 'PENDING_APPROVAL' ? 'Pending' : String(status || '').charAt(0) + String(status || '').slice(1).toLowerCase()}`)
}

/** Durum rozeti — en son gerçek statü (liste, kart, düzenleyici başlığı aynı dil). `data-status` test kancası. */
export function WeeklyStatusBadge({ status, sentAt, className }) {
  const t = useT()
  return (
    <Badge variant="outline" data-status={status} data-slot="wr-status"
      className={cn('rounded-full border-current bg-transparent px-2.5 text-[.72rem] font-bold tracking-[.03em]', STATUS_INK[status], className)}>
      {statusLabel(t, status, sentAt)}
    </Badge>
  )
}

/** Sıralanabilir başlık (aria-sort) — shadcn TableHead + Button. */
export function SortTh({ col, label, sort, onSort, style }) {
  const [k, d] = String(sort || '').split('|')
  const on = k === col
  return (
    <TableHead style={style} aria-sort={on ? (d === 'desc' ? 'descending' : 'ascending') : 'none'}>
      <Button type="button" variant="ghost" size="sm" className="-ml-2 h-auto gap-1 px-2 py-1 font-semibold"
        onClick={() => onSort(`${col}|${on && d === 'desc' ? 'asc' : 'desc'}`)}>
        {label} {on ? (d === 'desc' ? <ArrowDown aria-hidden="true" className="size-[11px]" /> : <ArrowUp aria-hidden="true" className="size-[11px]" />) : null}
      </Button>
    </TableHead>
  )
}

/** Skor bandı tonları (WeeklyScoreCalculator eşikleri) — koyu temada karşılıklı. */
const SCORE_TONE = {
  green: 'bg-green-100 text-green-800 dark:bg-green-900/60 dark:text-green-200',
  amber: 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-200',
  red: 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-200',
}
const PILL = 'min-w-[34px] rounded-full px-2 py-0.5 text-[.84em] font-bold'

/** Skor rozeti (gönderim anı; null → —). `title` açıklaması rozetin erişilebilir adında da okunur. */
export function ScoreBadge({ score, title }) {
  const band = scoreBand(score)
  if (band == null) return <Badge variant="ghost" data-slot="wr-score" className={cn(PILL, 'text-muted-foreground')}>—</Badge>
  return (
    <Badge variant="secondary" data-slot="wr-score" data-band={band} title={title} className={cn(PILL, SCORE_TONE[band])}>
      {score}
    </Badge>
  )
}

/** Önceki haftaya göre fark rozeti; goodWhenDown: azalış iyi (olay/alarm sayıları); neutral: iyi/kötü yok (durum dağılımı). */
export function DeltaBadge({ cur, prev, goodWhenDown = true, neutral = false }) {
  const t = useT()
  const d = delta(cur, prev)
  if (d == null || d === 0) return null
  const good = goodWhenDown ? d < 0 : d > 0
  return (
    <Badge variant="secondary" data-slot="wr-delta" data-good={neutral ? undefined : good} title={t('wr.deltaTitle', prev)}
      className={cn('rounded-full px-[7px] py-px text-[.92em] font-bold', neutral ? 'text-muted-foreground' : good ? SCORE_TONE.green : SCORE_TONE.red)}>
      {d > 0 ? '▲' : '▼'} {Math.abs(d)}
    </Badge>
  )
}

/** Sistemden öneri rozeti: değer elle girilenden farklıysa "Uygula". */
export function SuggestBadge({ value, current, onApply, label }) {
  const t = useT()
  if (value == null) return null
  const same = Number(current) === Number(value)
  return (
    <Badge variant="outline" data-slot="wr-suggest" data-same={same || undefined} title={label}
      className={cn('gap-1 rounded-full border-primary px-2 py-0.5 text-[.92em] font-semibold text-primary',
        same ? 'opacity-70' : 'border-dashed')}>
      <Sparkles aria-hidden="true" /> {t('wr.sugSystem')} {value}
      {!same && (
        <Button type="button" variant="link" size="xs" className="h-auto px-1 py-0 text-[1em] font-bold"
          onClick={() => onApply(Number(value))}>{t('wr.sugApply')}</Button>
      )}
    </Badge>
  )
}

/** "Geçen haftanın notu" — bölüm altında açılır-kapanır ham not (markdown metni). shadcn Collapsible. */
export function PrevNoteToggle({ open, onToggle, note, weekLabel }) {
  const t = useT()
  if (!note) return null
  return (
    <Collapsible open={open} onOpenChange={() => onToggle()} className="mt-1 mb-2.5">
      <CollapsibleTrigger asChild>
        <Button type="button" variant="link" size="sm" className="h-auto gap-1 px-1 py-0.5 text-[.84em] font-semibold">
          {open ? <EyeOff aria-hidden="true" className="size-3" /> : <Eye aria-hidden="true" className="size-3" />}
          {open ? t('wr.prevHide') : t('wr.prevShow', weekLabel || '')}
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <pre data-slot="wr-prev-note"
          className="mt-1.5 max-h-60 overflow-auto rounded-md border bg-muted/50 px-2.5 py-2 font-[inherit] text-[.86em] whitespace-pre-wrap text-muted-foreground">
          {note}
        </pre>
      </CollapsibleContent>
    </Collapsible>
  )
}
