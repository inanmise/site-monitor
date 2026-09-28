import { useState } from 'react'
import { ArrowDown, ArrowRight, Lock, Minus, Plus } from 'lucide-react'
import TeamBadge from '../../ui/TeamBadge.jsx'
import { useTeamDirectory } from '../../ui/TeamDirectory.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { formatValue } from '../../history/changeFields.js'
import { collapseContext, lineDiff } from '../../../utils/lineDiff.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { diffModel, fullText, listDelta } from './changeModel.js'

/**
 * Alan düzeyinde fark — "önce → sonra", her alan ayrı satır, insan etiketiyle (`changeFields`). Zaman çizelgesinde
 * satır içi açılır, ayrıntı panelinde tam hâliyle durur (aynı bileşen; ekranda iki farklı fark dili olmasın).
 *
 * Değer türüne göre çizim (`changeModel.valueKind`): açık/kapalı rozet, süre ("5 dk"), takım rozeti, liste (eklenen /
 * çıkan öğeler vurgulu), nesne (biçimli JSON), çok satırlı metin (satır farkı — script gövdesi gibi), uzun tek satır
 * (katlanır, "Tamamını göster"), gizli değer (kilit + "gizli"), boş ("boş"). Sunucu değeri kırptıysa (`…(+N)`) olduğu
 * gibi gösterilir — kırpma sunucunun bilinçli kararı (AuditDiff tavanı).
 *
 * Yerleşim mobil-önce: telefonda önce ve sonra ALT ALTA (küçük "Önce / Sonra" etiketleriyle), `sm` ve üstünde yan
 * yana, arada ok. Sol renk şeridi YOK: iki taraf tam çerçeveli, hafif tonlu kutu.
 *
 * Test kancaları: kap `data-slot="chg-diff"`, satır `data-diff-row=<alan>` + `data-mode`, hücreler
 * `data-diff="field" | "from" | "to"` (metinleri YALNIZ değerdir — ekran okuyucu etiketi dışarıda).
 */

const BOX = 'min-w-0 flex-1 rounded-md border px-2 py-1.5 text-sm break-words [overflow-wrap:anywhere]'
const FROM_BOX = 'border-destructive/25 bg-destructive/5'
const TO_BOX = 'border-success/30 bg-success/5'

/** Uzun tek satırlık metin — üç satıra katlanır, "Tamamını göster" açar (DEĞER açılımı; liste sayfalaması değil). */
function LongText({ text, t }) {
  const [open, setOpen] = useState(false)
  return (
    <span className="flex min-w-0 flex-col items-start gap-1">
      <span className={cn('whitespace-pre-wrap', !open && 'line-clamp-3')}>{text}</span>
      <Button type="button" variant="link" size="xs" className="h-auto p-0 text-xs max-md:min-h-8"
        aria-expanded={open} onClick={() => setOpen(o => !o)}>
        {open ? t('chg.collapseValue') : t('chg.expandValue')}
      </Button>
    </span>
  )
}

/** Takım kimliği → takım rozeti (üye listesini açar); dizinde yoksa (silinmiş / kapsam dışı) "#id". */
function TeamValue({ id }) {
  const dir = useTeamDirectory()
  const entry = dir.byId.get(id)
  if (!entry?.name) return <span className="tabular-nums">#{id}</span>
  return <TeamBadge teamId={id} teamName={entry.name} size={12} />
}

/** Tek taraf değeri — türüne göre. */
function Value({ k, v, kind, t }) {
  switch (kind) {
    case 'empty':
      return <span className="text-muted-foreground italic">{t('chg.valueEmpty')}</span>
    case 'masked':
      return (
        <span className="inline-flex items-center gap-1 text-muted-foreground">
          <Lock aria-hidden="true" className="size-3.5" />{t('chg.valueHidden')}
        </span>
      )
    case 'boolean': {
      const on = v === true || v === 'true'
      return <ToneBadge tone={on ? 'success' : 'muted'} data-bool={on ? 'on' : 'off'} className="font-semibold">{formatValue(k, v, { t })}</ToneBadge>
    }
    case 'team':
      return <TeamValue id={Number(v)} />
    // Düz metin türleri ÇIPLAK metin döner: hücrenin (`data-diff`) kendi metni olsun — ekran okuyucu ve
    // testing-library `getByText(…, { selector })` aynı düğümü bulur.
    case 'duration':
    case 'number':
      return formatValue(k, v, { t })
    case 'list':
      return (
        <span className="flex min-w-0 flex-wrap gap-1">
          {v.length === 0 ? <span className="text-muted-foreground italic">{t('chg.valueEmpty')}</span>
            : v.map((x, i) => <Badge key={i} variant="outline" className="max-w-full font-normal"><span className="truncate">{String(x)}</span></Badge>)}
        </span>
      )
    case 'object':
      return <pre className="m-0 max-h-48 overflow-auto font-mono text-xs whitespace-pre-wrap">{fullText(k, v, t)}</pre>
    case 'binary':
      return <span className="text-muted-foreground">{fullText(k, v, t)}</span>
    case 'long':
      return <LongText text={String(v)} t={t} />
    default:
      return String(v)
  }
}

/** Liste farkı: çıkan öğeler kırmızı "−", eklenenler yeşil "+", kalanlar nötr. */
function ListDelta({ from, to, t }) {
  const { removed, added, kept } = listDelta(from, to)
  const chip = (x, tone, Icon, key) => (
    <ToneBadge key={key} tone={tone} data-list={tone === 'danger' ? 'removed' : tone === 'success' ? 'added' : 'kept'}
      className="max-w-full gap-0.5 font-normal">
      {Icon && <Icon aria-hidden="true" />}<span className="truncate">{x}</span>
    </ToneBadge>
  )
  return (
    <div data-diff="to" className="flex min-w-0 flex-col gap-1.5">
      {(removed.length > 0 || added.length > 0) && (
        <span className="sr-only">{t('chg.listDeltaSr', added.length, removed.length)}</span>
      )}
      <span className="flex min-w-0 flex-wrap gap-1">
        {removed.map((x, i) => chip(x, 'danger', Minus, `r${i}`))}
        {added.map((x, i) => chip(x, 'success', Plus, `a${i}`))}
        {kept.map((x, i) => chip(x, 'muted', null, `k${i}`))}
        {removed.length + added.length + kept.length === 0 && (
          <span className="text-muted-foreground italic">{t('chg.valueEmpty')}</span>
        )}
      </span>
    </div>
  )
}

/** Çok satırlı metin (script, başlık listesi): satır farkı — değişmeyen uzun bölümler "N satır aynı" diye katlanır. */
function LinesDelta({ from, to, t }) {
  const d = lineDiff(from == null ? '' : String(from), to == null ? '' : String(to))
  if (d.truncated) {
    return <p className="m-0 text-sm text-muted-foreground">{t('chg.linesTooLong', d.oldLines, d.newLines)}</p>
  }
  const rows = collapseContext(d.rows, 2)
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <span className="text-xs text-muted-foreground">{t('chg.linesSummary', d.added, d.removed)}</span>
      <pre data-diff="to" className="m-0 max-h-72 min-w-0 overflow-auto rounded-md border bg-muted/30 py-1 font-mono text-xs leading-relaxed">
        {rows.map((r, i) => r.type === 'gap' ? (
          <span key={i} className="block px-2 text-muted-foreground italic">{t('chg.linesSame', r.count)}</span>
        ) : (
          <span key={i} data-line={r.type}
            className={cn('block px-2 whitespace-pre-wrap [overflow-wrap:anywhere]',
              r.type === 'add' && 'bg-success/10', r.type === 'del' && 'bg-destructive/10')}>
            <span aria-hidden="true" className="mr-2 inline-block w-3 text-muted-foreground select-none">{r.type === 'add' ? '+' : r.type === 'del' ? '−' : ' '}</span>
            {r.type !== 'ctx' && <span className="sr-only">{r.type === 'add' ? t('chg.lineAdded') : t('chg.lineRemoved')}: </span>}
            {r.text || ' '}
          </span>
        ))}
      </pre>
    </div>
  )
}

/**
 * @param changes  ham `changes` JSON'u
 * @param dense    zaman çizelgesi içi (biraz daha sıkı) — ayrıntı panelinde false
 */
export default function ChangeDiff({ changes, t, dense = false, className }) {
  const entries = diffModel(changes, t)
  if (entries.length === 0) return null
  return (
    <ul data-slot="chg-diff" className={cn('m-0 flex min-w-0 list-none flex-col p-0', dense ? 'gap-1.5' : 'gap-2', className)}>
      {entries.map(e => (
        <li key={e.key} data-diff-row={e.key} data-mode={e.mode}
          className={cn('grid min-w-0 gap-1.5 rounded-lg border bg-card', dense ? 'p-2' : 'p-2.5',
            'md:grid-cols-[minmax(7rem,11rem)_minmax(0,1fr)] md:gap-3')}>
          <div data-diff="field" className="min-w-0 text-xs font-semibold break-words text-muted-foreground md:pt-1.5">{e.label}</div>
          {e.mode === 'lines' ? (
            <LinesDelta from={e.from} to={e.to} t={t} />
          ) : e.mode === 'list' ? (
            <ListDelta from={e.from} to={e.to} t={t} />
          ) : (
            <div className="flex min-w-0 flex-col gap-1 sm:flex-row sm:items-start sm:gap-2">
              {/* "Önce / Sonra" etiketi telefonda GÖRÜNÜR (alt alta dizilişte yön oksuz belli olsun), sm+ yalnız ekran okuyucuda */}
              <div className={cn(BOX, FROM_BOX)}>
                <span className="mb-0.5 block text-[10px] font-semibold tracking-wide text-muted-foreground uppercase sm:sr-only">{t('chg.before')}</span>
                <div data-diff="from" className="min-w-0 whitespace-pre-wrap tabular-nums"><Value k={e.key} v={e.from} kind={e.fromKind} t={t} /></div>
              </div>
              <ArrowRight aria-hidden="true" className="mt-2 hidden size-4 shrink-0 text-muted-foreground sm:block" />
              <ArrowDown aria-hidden="true" className="mx-auto size-3.5 shrink-0 text-muted-foreground sm:hidden" />
              <div className={cn(BOX, TO_BOX)}>
                <span className="mb-0.5 block text-[10px] font-semibold tracking-wide text-muted-foreground uppercase sm:sr-only">{t('chg.after')}</span>
                <div data-diff="to" className="min-w-0 whitespace-pre-wrap tabular-nums"><Value k={e.key} v={e.to} kind={e.toKind} t={t} /></div>
              </div>
            </div>
          )}
        </li>
      ))}
    </ul>
  )
}
