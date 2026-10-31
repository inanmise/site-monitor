import { CheckCircle2, CircleHelp, Siren, TriangleAlert, CircleSlash } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import ToneBadge from '../admin/ToneBadge.jsx'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import { RULE_KINDS } from './ruleModel.js'

/** Tablo ↔ kart eşiği — KAP genişliği (ayrıntı penceresi, kart, deneme paneli farklı genişlikte). */
export const RESULTS_TABLE_MIN_PX = 560

/**
 * Sonucun görünür durumu: `unevaluated` (kolon yok / sayı değil — not ile) · `critical` · `warning` · `passed` ·
 * `unknown` (alanlar eksik — asla "geçti" sayılmaz, asla yeşil değil).
 */
export function resultState(r) {
  if (!r) return 'unknown'
  if (r.evaluated === false) return 'unevaluated'
  if (r.level === 'CRITICAL') return 'critical'
  if (r.level === 'WARNING') return 'warning'
  if (r.passed === true) return 'passed'
  return 'unknown'
}

const TONE = { passed: 'success', warning: 'warning', critical: 'danger', unevaluated: 'muted', unknown: 'muted' }
const ICON = { passed: CheckCircle2, warning: TriangleAlert, critical: Siren, unevaluated: CircleSlash, unknown: CircleHelp }

/** Sonuç rozeti (geçti / UYARI / KRİTİK / değerlendirilemedi / bilinmiyor). `data-slot="dbq-rr-level"` + `data-state`. */
export function ResultLevelBadge({ result, className = '' }) {
  const t = useT()
  const s = resultState(result)
  const Icon = ICON[s]
  return (
    <ToneBadge tone={TONE[s]} data-slot="dbq-rr-level" data-state={s} className={cn('h-auto gap-1 rounded-md whitespace-normal', className)}>
      <Icon aria-hidden="true" />{t(`dbq.rr.state.${s}`)}
    </ToneBadge>
  )
}

const valueOrDash = (v) => (v == null || v === '' ? '—' : String(v))

/** Sunucu not kodları (`DbQueryEvaluator.NOTE_*`) — `dbq.rr.note.<kod>` ile çevrilir. */
export const RESULT_NOTE_CODES = ['column_missing', 'not_numeric', 'no_rows', 'first_run', 'prev_zero', 'partial', 'capped', 'query_failed']

/** Not → metin: bilinen kod çevrilir, bilinmeyen not (eski kayıt / serbest metin) olduğu gibi gösterilir. */
export function resultNoteText(t, note) {
  if (note == null) return null
  const s = String(note).trim()
  if (!s) return null
  return RESULT_NOTE_CODES.includes(s) ? t(`dbq.rr.note.${s}`) : s
}

/**
 * Kural sonuçları (Veritabanı Sorgu İzleme, 2026-10-31) — kural · beklenen · gelen · seviye (+ not). SATIR VERİSİ YOK:
 * yalnız kuralın ölçtüğü değer ve sunucunun yazdığı beklenen metin. Geniş kapta tablo, dar kapta kart; `compact` kart
 * özeti (izleme kartı): her kural tek satır "ad · gelen · rozet".
 */
export default function RuleResultsList({ results, compact = false }) {
  const t = useT()
  const noteOf = (r) => resultNoteText(t, r?.note)
  const [ref, width] = useElementWidth()
  const list = Array.isArray(results) ? results : []
  const name = (r, i) => (r?.label && String(r.label).trim())
    || (RULE_KINDS.includes(r?.kind) ? t(`dbq.rule.kindName.${r.kind}`) : t('dbq.rule.cardTitle', i + 1))

  if (list.length === 0) {
    return <p data-slot="dbq-rr-empty" className="m-0 text-sm text-muted-foreground">{t('dbq.rr.empty')}</p>
  }

  if (compact) {
    return (
      <ul data-slot="dbq-rr" data-layout="compact" className="m-0 flex list-none flex-col gap-1 p-0">
        {list.map((r, i) => (
          <li key={r?.id || i} data-slot="dbq-rr-item" data-state={resultState(r)} className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5 text-xs">
            <span className="min-w-0 font-medium [overflow-wrap:anywhere]">{name(r, i)}</span>
            <span className="text-muted-foreground tabular-nums [overflow-wrap:anywhere]">{valueOrDash(r?.actual)}</span>
            {resultState(r) === 'unevaluated' && noteOf(r) && (
              <span data-slot="dbq-rr-note" data-note={r.note} className="text-muted-foreground [overflow-wrap:anywhere]">· {noteOf(r)}</span>
            )}
            <ResultLevelBadge result={r} className="ml-auto text-[11px]" />
          </li>
        ))}
      </ul>
    )
  }

  const table = width === 0 || width >= RESULTS_TABLE_MIN_PX
  return (
    <div ref={ref} data-slot="dbq-rr" data-layout={table ? 'table' : 'cards'} className="min-w-0">
      {table ? (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="whitespace-normal">{t('dbq.rr.col.rule')}</TableHead>
              <TableHead className="whitespace-normal">{t('dbq.rr.col.expected')}</TableHead>
              <TableHead className="whitespace-normal">{t('dbq.rr.col.actual')}</TableHead>
              <TableHead className="whitespace-normal">{t('dbq.rr.col.level')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {list.map((r, i) => (
              <TableRow key={r?.id || i} data-slot="dbq-rr-item" data-state={resultState(r)}>
                <TableCell className="align-top whitespace-normal">
                  <span className="font-medium [overflow-wrap:anywhere]">{name(r, i)}</span>
                  {noteOf(r) && <span data-slot="dbq-rr-note" data-note={r.note} className="mt-0.5 block text-xs text-muted-foreground [overflow-wrap:anywhere]">{noteOf(r)}</span>}
                </TableCell>
                <TableCell className="align-top text-sm whitespace-normal [overflow-wrap:anywhere]">{valueOrDash(r?.expected)}</TableCell>
                <TableCell className="align-top text-sm font-semibold whitespace-normal tabular-nums [overflow-wrap:anywhere]">{valueOrDash(r?.actual)}</TableCell>
                <TableCell className="align-top whitespace-normal"><ResultLevelBadge result={r} /></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {list.map((r, i) => (
            <li key={r?.id || i} data-slot="dbq-rr-item" data-state={resultState(r)} className="flex min-w-0 flex-col gap-1.5 rounded-md border p-2.5">
              <div className="flex min-w-0 items-start gap-2">
                <span className="min-w-0 flex-1 text-sm font-medium [overflow-wrap:anywhere]">{name(r, i)}</span>
                <ResultLevelBadge result={r} />
              </div>
              <dl className="m-0 grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-0.5 text-sm">
                <dt className="text-xs text-muted-foreground">{t('dbq.rr.col.expected')}</dt>
                <dd className="m-0 [overflow-wrap:anywhere]">{valueOrDash(r?.expected)}</dd>
                <dt className="text-xs text-muted-foreground">{t('dbq.rr.col.actual')}</dt>
                <dd className="m-0 font-semibold tabular-nums [overflow-wrap:anywhere]">{valueOrDash(r?.actual)}</dd>
              </dl>
              {noteOf(r) && <span data-slot="dbq-rr-note" data-note={r.note} className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{noteOf(r)}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
