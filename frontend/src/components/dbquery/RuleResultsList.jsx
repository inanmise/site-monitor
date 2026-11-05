import { CheckCircle2, CircleHelp, Siren, TriangleAlert, CircleSlash, Hourglass } from 'lucide-react'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import ToneBadge from '../admin/ToneBadge.jsx'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import { RULE_KINDS, formatNumber } from './ruleModel.js'
import { smartResultOf } from './smartResult.js'

/** Tablo ↔ kart eşiği — KAP genişliği (ayrıntı penceresi, kart, deneme paneli farklı genişlikte). */
export const RESULTS_TABLE_MIN_PX = 560

/**
 * Sonucun görünür durumu: `unevaluated` (kolon yok / sayı değil — not ile) · `critical` · `warning` · `passed` ·
 * `unknown` (alanlar eksik — asla "geçti" sayılmaz, asla yeşil değil).
 */
/** Taban kuran notlar (sunucu: ihlal değil, kanıt eksikliği de değil — r177 2. tur) → nötr "taban kuruluyor". */
export const BASELINE_NOTES = ['first_run', 'baseline_reset', 'no_seasonal_baseline',
  // 2026-11-05 (dbq2 D): akıllı kuralın tabanı yeterli örneğe ulaşmadı — ihlal değil, kanıt eksikliği de değil
  'building_baseline']

export function resultState(r) {
  if (!r) return 'unknown'
  if (r.evaluated === false) return BASELINE_NOTES.includes(r.note) ? 'building' : 'unevaluated'
  if (r.level === 'CRITICAL') return 'critical'
  if (r.level === 'WARNING') return 'warning'
  if (r.passed === true) return 'passed'
  return 'unknown'
}

const TONE = { passed: 'success', warning: 'warning', critical: 'danger', unevaluated: 'muted', building: 'muted', unknown: 'muted' }
const ICON = { passed: CheckCircle2, warning: TriangleAlert, critical: Siren, unevaluated: CircleSlash, building: Hourglass, unknown: CircleHelp }

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
export const RESULT_NOTE_CODES = ['column_missing', 'not_numeric', 'no_rows', 'first_run', 'prev_zero', 'partial', 'capped', 'query_failed', 'null_value', 'prev_unreadable',
  // 2026-10-31 (r177): sorgu / kural değişti → taban yeniden kuruluyor; mevsimsel taban yok; tazelik kolonu zaman damgası değil
  'baseline_reset', 'no_seasonal_baseline', 'not_timestamp', 'db_clock_unavailable', 'future_timestamp',
  // 2026-11-05 (dbq2 D): akıllı kural (olağan dışı / donmuş) tabanı kuruluyor
  'building_baseline',
  // 2026-11-05 (be2 inceleme): yeterli çalıştırma var ama örnek az (boş değer / tabandan dışlanan ihlal) — KANIT EKSİK
  // (değerlendirilemedi, kontrol satırı UNKNOWN); BASELINE_NOTES'ta DEĞİL
  'insufficient_samples']

/** Not → metin: bilinen kod çevrilir, bilinmeyen not (eski kayıt / serbest metin) olduğu gibi gösterilir. */
export function resultNoteText(t, note) {
  if (note == null) return null
  const s = String(note).trim()
  if (!s) return null
  return RESULT_NOTE_CODES.includes(s) ? t(`dbq.rr.note.${s}`) : s
}

/**
 * Akıllı kural sonucunun ek gösterimi (2026-11-05, dbq2 D): olağan aralık (ANOMALY — yalnız mini çubuk + örnek sayısı; aralık
 * ve sapma METNİ sunucunun yöne göre yazdığı `actual`ta), donma sayacı (FROZEN — "aynı değer 7 çalıştırmadır") ya da taban sayacı ("taban kuruluyor: 4 / 10 örnek").
 * Sunucu ek alanları yoksa (eski satır) hiçbir şey çizilmez. Renk seviyeyi taşır (çubuktaki değer noktası); bant nötr.
 * Test kancaları: `dbq-rr-smart` (+ `data-kind` band|frozen|building), çubuk `dbq-rr-band`, değer noktası `dbq-rr-band-value`.
 */
export function SmartResultDetail({ result, compact = false }) {
  const t = useT()
  const { lang } = useLanguage()
  const d = smartResultOf(result)
  if (!d) return null
  const n = (v) => formatNumber(v, lang)
  if (d.kind === 'building') {
    const text = d.samples != null && d.minSamples != null ? t('dbq.rr.smart.building', n(d.samples), n(d.minSamples)) : t('dbq.rr.smart.buildingNoCount')
    return <span data-slot="dbq-rr-smart" data-kind="building" className="block text-xs text-muted-foreground [overflow-wrap:anywhere]">{text}</span>
  }
  if (d.kind === 'frozen') {
    // "değer değişti" yalnız DEĞERLENDİRİLMİŞ sonuçta (O5) — taban kuruluyorken / sıfırlanmışken söylenmez
    const key = d.atLeast ? 'dbq.rr.smart.frozenAtLeast'
      : d.sameRuns <= 1 ? (d.evaluated ? 'dbq.rr.smart.frozenChanged' : null) : 'dbq.rr.smart.frozen'
    if (!key) return null
    const text = t(key, n(d.sameRuns))
    return <span data-slot="dbq-rr-smart" data-kind="frozen" className="block text-xs text-muted-foreground [overflow-wrap:anywhere]">{text}</span>
  }
  // Olağan aralık ve sapma METİN olarak yazılmaz (O5): sunucunun `actual` metni kuralın YÖNÜNE göre tek uçlu ("olağan
  // ≤ 700 · sapma +9,4σ") ve hemen üstte; sonuç öğesi yönü taşımadığı için iki uçlu bant ("300 – 700", σ = 0'da "500 –
  // 500") onunla çelişirdi. Burada yalnız mini çubuk (konum) ve örnek sayısı.
  const pos = d.position
  if (compact || ((!pos || pos.value == null) && d.samples == null)) return null
  const level = result?.level === 'CRITICAL' ? 'critical' : result?.level === 'WARNING' ? 'warning' : 'ok'
  const dot = { critical: 'bg-destructive', warning: 'bg-amber-500', ok: 'bg-foreground/70' }[level]
  return (
    <span data-slot="dbq-rr-smart" data-kind="band" className="flex min-w-0 flex-col gap-1">
      {pos && pos.value != null && (
        <span data-slot="dbq-rr-band" aria-hidden="true" className="relative block h-2 w-full max-w-56 rounded-full bg-muted">
          <span className="absolute inset-y-0 rounded-full bg-primary/25"
            style={{ left: `${Math.max(0, pos.low) * 100}%`, width: `${Math.max(0, Math.min(1, pos.high) - Math.max(0, pos.low)) * 100}%` }} />
          <span data-slot="dbq-rr-band-value" data-level={level}
            className={cn('absolute top-1/2 size-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full ring-2 ring-background', dot)}
            style={{ left: `${Math.max(0, Math.min(1, pos.value)) * 100}%` }} />
        </span>
      )}
      {d.samples != null && <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('dbq.rr.smart.samples', n(d.samples))}</span>}
    </span>
  )
}

/** Not metni — taban kuruluyorken (akıllı kural) sayaç zaten ek gösterimde: genel not tekrarlanmaz. */
function shownNote(t, r) {
  if (r?.note === 'building_baseline' && smartResultOf(r)) return null
  return resultNoteText(t, r?.note)
}

/**
 * Kural sonuçları (Veritabanı Sorgu İzleme, 2026-10-31) — kural · beklenen · gelen · seviye (+ not). SATIR VERİSİ YOK:
 * yalnız kuralın ölçtüğü değer ve sunucunun yazdığı beklenen metin. Geniş kapta tablo, dar kapta kart; `compact` kart
 * özeti (izleme kartı): her kural tek satır "ad · gelen · rozet".
 */
export default function RuleResultsList({ results, compact = false }) {
  const t = useT()
  const noteOf = (r) => shownNote(t, r)
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
            {['unevaluated', 'building'].includes(resultState(r)) && noteOf(r) && (
              <span data-slot="dbq-rr-note" data-note={r.note} className="text-muted-foreground [overflow-wrap:anywhere]">· {noteOf(r)}</span>
            )}
            <SmartResultDetail result={r} compact />
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
                <TableCell className="align-top text-sm font-semibold whitespace-normal tabular-nums [overflow-wrap:anywhere]">
                  {valueOrDash(r?.actual)}
                  {smartResultOf(r) && <span className="mt-1 block font-normal"><SmartResultDetail result={r} /></span>}
                </TableCell>
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
                <dd className="m-0 font-semibold tabular-nums [overflow-wrap:anywhere]">
                  {valueOrDash(r?.actual)}
                  {smartResultOf(r) && <span className="mt-1 block font-normal"><SmartResultDetail result={r} /></span>}
                </dd>
              </dl>
              {noteOf(r) && <span data-slot="dbq-rr-note" data-note={r.note} className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{noteOf(r)}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
