import { useId } from 'react'
import {
  Target, BellRing, CalendarClock, RefreshCw, FileChartColumn, CircleCheck, TriangleAlert, OctagonAlert, Info, Clock,
  ShieldCheck, KeyRound, ClipboardCheck, CircleDashed,
} from 'lucide-react'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import {
  STATUS_VARIANT, TONE_TEXT, NUMERIC_TYPES, columnLabel, formatValue, kpiView, keys, localized, noteText, sectionTitle,
  verdictText, fmtDateTime,
} from './executiveModel.js'
import { AvailabilityTrend, CryptoCategoryDistribution, RenewalDistribution, TlsGradeDistribution } from './ExecVisuals.jsx'

/** Bölüm anahtarı → başlık ikonu (bilinmeyen bölüm genel ikonla çizilir — genişleme noktası). */
export const SECTION_ICON = {
  availability: Target, noise: BellRing, expirations: CalendarClock, renewals: RefreshCw,
  'tls-grade': ShieldCheck, 'crypto-readiness': KeyRound, 'data-quality': ClipboardCheck,
}

export function sectionIcon(key) {
  return SECTION_ICON[key] || FileChartColumn
}

/** Bölüme özel görsel (isteğe bağlı) — yoksa yalnız genel çizim. Yeni bölüm buraya bir giriş ekleyebilir. */
const SECTION_VISUAL = {
  availability: (section) => <AvailabilityTrend section={section} />,
  renewals: (section) => <RenewalDistribution section={section} />,
  'tls-grade': (section) => <TlsGradeDistribution section={section} />,
  'crypto-readiness': (section) => <CryptoCategoryDistribution section={section} />,
}

const TONE_ICON = { ok: CircleCheck, warn: TriangleAlert, bad: OctagonAlert, info: Info, neutral: Info }

/** Durum → nokta / ikon rengi (renk tek taşıyıcı değil: yanında her zaman metin). */
export const STATUS_DOT = Object.freeze({
  ok: 'bg-success', attention: 'bg-warning', critical: 'bg-destructive', error: 'bg-warning', no_data: 'bg-muted-foreground/50',
})

/** Fark çipinin tonu → arka plan + metin. */
const DELTA_PILL = {
  ok: 'bg-success/10 text-success', warn: 'bg-warning/10 text-warning', bad: 'bg-destructive/10 text-destructive',
  info: 'bg-primary/10 text-primary', neutral: 'bg-muted text-muted-foreground',
}

/** Bölüm durumu → rozet metni anahtarı (literal: i18n used-keys kapısı görebilsin). */
export const STATUS_LABEL = {
  ok: 'exec.status.ok', attention: 'exec.status.attention', critical: 'exec.status.critical',
  no_data: 'exec.status.no_data', error: 'exec.status.notCalculated',
}

/** Durum rozeti — metin + renk (renk tek başına bilgi taşımaz). */
export function StatusBadge({ status, className }) {
  const t = useT()
  const s = STATUS_VARIANT[status] ? status : 'no_data'
  return (
    <Badge variant={STATUS_VARIANT[s]} data-slot="ex-status" data-status={s}
      className={cn('gap-1.5', s === 'ok' && 'border-success/40 text-success', className)}>
      <span aria-hidden="true" className={cn('size-1.5 shrink-0 rounded-full', STATUS_DOT[s],
        (s === 'critical' || s === 'attention') && 'bg-current')} />
      {t(STATUS_LABEL[s])}
    </Badge>
  )
}

/** Tek satırlık hüküm: ton ikonu + metin. */
export function VerdictItem({ sectionKey, verdict }) {
  const t = useT()
  const { lang } = useLanguage()
  const Icon = TONE_ICON[verdict.tone] || Info
  return (
    <li data-slot="ex-verdict" data-tone={verdict.tone} data-code={verdict.code} className="flex min-w-0 items-start gap-2.5">
      <Icon aria-hidden="true" className={cn('mt-0.5 size-4 shrink-0', TONE_TEXT[verdict.tone] || 'text-muted-foreground')} />
      <span className="min-w-0 text-sm leading-relaxed [overflow-wrap:anywhere]">{verdictText(t, sectionKey, verdict, lang)}</span>
    </li>
  )
}

/**
 * Gösterge kutusu — (isteğe bağlı) bölüm başlığı, etiket, değer (ton rengi), fark çipi, ipucu. `caption` üst şeritte
 * göstergenin hangi bölümden geldiğini söyler.
 */
export function KpiTile({ sectionKey, kpi, emphasis = false, caption = null }) {
  const t = useT()
  const { lang } = useLanguage()
  const v = kpiView(t, sectionKey, kpi, lang)
  const Icon = caption ? sectionIcon(sectionKey) : null
  return (
    <div data-slot="ex-kpi" data-code={v.code} data-tone={v.tone}
      className={cn('flex min-w-0 flex-col gap-1.5 rounded-xl border bg-card px-3 py-3 sm:px-3.5',
        emphasis && 'bg-gradient-to-b from-muted/50 to-card')}>
      {caption && (
        <span data-slot="ex-kpi-caption" className="flex min-w-0 items-center gap-1.5 text-[11px] font-medium text-muted-foreground">
          <Icon aria-hidden="true" className="size-3.5 shrink-0" />
          <span className="truncate">{caption}</span>
        </span>
      )}
      <span className="text-xs font-medium text-muted-foreground [overflow-wrap:anywhere]">{v.label}</span>
      <span className={cn('text-2xl leading-none font-semibold tracking-tight tabular-nums [overflow-wrap:anywhere]',
        TONE_TEXT[v.tone] || 'text-foreground')}>
        {v.value}
      </span>
      {v.delta && (
        <span data-slot="ex-kpi-delta" data-tone={v.deltaTone}
          className={cn('w-fit max-w-full rounded-md px-1.5 py-0.5 text-xs font-medium [overflow-wrap:anywhere]',
            DELTA_PILL[v.deltaTone] || DELTA_PILL.neutral)}>
          {v.delta}
        </span>
      )}
      {v.hint && <span className="text-xs leading-snug text-muted-foreground [overflow-wrap:anywhere]">{v.hint}</span>}
    </div>
  )
}

/** Gösterge ızgarası — telefonda 2, tablette 3, geniş ekranda 3–6 sütun. */
export function KpiGrid({ sectionKey, kpis, label }) {
  if (!kpis?.length) return null
  return (
    <div role="group" aria-label={label} data-slot="ex-kpis"
      className={cn('grid grid-cols-2 gap-2.5 sm:grid-cols-3', kpis.length > 4 ? 'xl:grid-cols-6' : 'xl:grid-cols-4')}>
      {kpis.map((k) => <KpiTile key={k.code} sectionKey={sectionKey} kpi={k} />)}
    </div>
  )
}

/** Tablo hücresi metni (biçimli) + durum hücresinde rozet. */
function CellValue({ col, value }) {
  const t = useT()
  const { lang } = useLanguage()
  const text = formatValue(value, col.type, { t, lang })
  if (col.type === 'status') {
    const tone = value || 'neutral'
    return (
      <Badge variant="outline" data-slot="ex-cell-status" data-tone={tone} className={cn('font-normal', TONE_TEXT[tone])}>
        {text}
      </Badge>
    )
  }
  return text
}

/**
 * Bölüm tablosu — ≥ 768 px shadcn Table (kendi kabında yatay kayar), telefonda kart listesi (ilk sütun başlık, diğerleri
 * etiket: değer). Kırpılmış satırlar "+N kayıt daha" ile söylenir.
 */
export function SectionTable({ sectionKey, table }) {
  const t = useT()
  const id = useId()
  const title = localized(t, keys.table(sectionKey, table.code), table.title)
  const cols = table.columns || []
  const rows = table.rows || []
  const more = Math.max(0, (table.total || 0) - rows.length)
  if (!cols.length) return null
  return (
    <section aria-labelledby={id} data-slot="ex-table" data-code={table.code} className="flex min-w-0 flex-col gap-2">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <h4 id={id} className="m-0 text-sm font-semibold">{title}</h4>
        {rows.length > 0 && (table.total || 0) > 0 && (
          <span className="text-xs text-muted-foreground tabular-nums">{t('exec.tableCount', rows.length, table.total)}</span>
        )}
      </div>
      {rows.length === 0 ? (
        <p data-slot="ex-table-empty" className="m-0 rounded-lg border border-dashed px-3 py-3 text-sm text-muted-foreground">
          {localized(t, `exec.${sectionKey}.table.${table.code}.empty`, table.empty || t('exec.empty'))}
        </p>
      ) : (
        <>
          <div data-slot="ex-table-wide" className="hidden min-w-0 overflow-x-auto rounded-lg border md:block">
            <Table className="text-sm">
              <TableHeader className="bg-muted/40">
                <TableRow>
                  {cols.map((c) => (
                    <TableHead key={c.code} className={cn('whitespace-nowrap text-xs', NUMERIC_TYPES.has(c.type) && 'text-right')}>
                      {columnLabel(t, c)}
                    </TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((r, i) => (
                  <TableRow key={i} data-slot="ex-row">
                    {cols.map((c, ci) => (
                      <TableCell key={c.code}
                        className={cn(NUMERIC_TYPES.has(c.type) && 'text-right tabular-nums', ci === 0 && 'max-w-[22rem] font-medium [overflow-wrap:anywhere] whitespace-normal')}>
                        <CellValue col={c} value={r[c.code]} />
                      </TableCell>
                    ))}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <ul data-slot="ex-table-cards" className="m-0 flex list-none flex-col gap-2 p-0 md:hidden">
            {rows.map((r, i) => (
              <li key={i} data-slot="ex-card-row" className="min-w-0 rounded-lg border bg-card px-3 py-2.5">
                <p className="m-0 text-sm font-semibold [overflow-wrap:anywhere]"><CellValue col={cols[0]} value={r[cols[0].code]} /></p>
                <dl className="m-0 mt-1.5 grid grid-cols-[minmax(0,auto)_minmax(0,1fr)] gap-x-3 gap-y-1 text-sm">
                  {cols.slice(1).map((c) => (
                    <div key={c.code} className="contents">
                      <dt className="text-muted-foreground">{columnLabel(t, c)}</dt>
                      <dd className="m-0 min-w-0 text-right tabular-nums [overflow-wrap:anywhere]"><CellValue col={c} value={r[c.code]} /></dd>
                    </div>
                  ))}
                </dl>
              </li>
            ))}
          </ul>
          {more > 0 && <p data-slot="ex-table-more" className="m-0 text-xs text-muted-foreground">{t('exec.more', more)}</p>}
        </>
      )}
    </section>
  )
}

/**
 * Bölüm kartı — başlık şeridi (ikon, başlık, durum, rapor anı) → hükümler → göstergeler → (bölüme özel görsel) →
 * tablolar → notlar. Sol renk şeridi YOK (ürün kararı); durum rozet + metinle.
 */
export function SectionCard({ section, index = null }) {
  const t = useT()
  const { lang } = useLanguage()
  const titleId = useId()
  const Icon = sectionIcon(section.key)
  const visual = SECTION_VISUAL[section.key]
  const title = sectionTitle(t, section)
  return (
    <Card id={`ex-sec-${section.key}`} data-slot="ex-section" data-key={section.key} data-status={section.status}
      aria-labelledby={titleId} role="region" className="min-w-0 scroll-mt-4 gap-0 overflow-hidden py-0 shadow-xs">
      <CardHeader className="gap-1.5 border-b bg-muted/30 px-4 py-3.5 sm:px-5 [.border-b]:pb-3.5">
        <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-2">
          <span aria-hidden="true" className="inline-flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
            <Icon className="size-[18px]" />
          </span>
          <div className="flex min-w-0 flex-1 flex-col gap-0.5">
            {index != null && (
              <span className="text-[11px] font-medium text-muted-foreground tabular-nums">{t('exec.sectionNo', index)}</span>
            )}
            <CardTitle id={titleId} className="m-0 min-w-0 text-base leading-snug">{title}</CardTitle>
          </div>
          <StatusBadge status={section.status} />
        </div>
        {section.snapshot && section.as_of && (
          <CardDescription className="flex items-center gap-1.5 text-xs">
            <Clock aria-hidden="true" className="size-3.5" />
            {t('exec.asOf', fmtDateTime(section.as_of, lang))}
          </CardDescription>
        )}
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-5 px-4 py-4 sm:px-5 sm:py-5">
        {section.verdicts?.length > 0 && (
          <ul data-slot="ex-verdicts" className="m-0 flex list-none flex-col gap-2 p-0">
            {section.verdicts.map((v) => <VerdictItem key={v.code} sectionKey={section.key} verdict={v} />)}
          </ul>
        )}
        {section.status === 'no_data' && !section.verdicts?.length && (
          <p className="m-0 flex items-center gap-2 text-sm text-muted-foreground">
            <CircleDashed aria-hidden="true" className="size-4 shrink-0" />{t('exec.sectionNoData')}
          </p>
        )}
        <KpiGrid sectionKey={section.key} kpis={section.kpis} label={t('exec.kpisOf', title)} />
        {visual && visual(section)}
        {(section.tables || []).map((tb) => <SectionTable key={tb.code} sectionKey={section.key} table={tb} />)}
        {section.notes?.length > 0 && (
          <ul data-slot="ex-notes" aria-label={t('exec.notes')} className="m-0 flex list-none flex-col gap-1 border-t pt-3 pl-0">
            {section.notes.map((n) => (
              <li key={n.code} data-code={n.code} className="text-xs leading-relaxed text-muted-foreground [overflow-wrap:anywhere]">
                {noteText(t, section.key, n, lang)}
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}
