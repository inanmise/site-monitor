import { Accessibility, ChevronRight, Gauge, LayoutGrid, Search, ShieldCheck } from 'lucide-react'
import { ProgressBar } from '../../ui/Progress.jsx'
import { AUDIT_AREAS, AUDIT_AREA_CATEGORY, areaSummary, categoryScoreRows, formatNumber, plural } from './resourceScanModel.js'
import { Section, TONE_BAR, TONE_TEXT, TOUCH, WRAP } from './ResourceScanParts.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

const ICON = { performance: Gauge, security: ShieldCheck, seo: Search, accessibility: Accessibility }

/**
 * Pano → KATEGORİ PUANLARI (prs3): Performans (sayfanın notu), Güvenlik, SEO, Erişilebilirlik. Her kutucuk ilgili
 * ayrıntılı denetim alanını açar (`onAudit(area)`). Hesaplanamayan puan "—" ve soluk; eski analizde (algoritma < 3)
 * güvenlik / SEO / erişilebilirlik "Bu analizde yok — yeniden analiz edin" der — asla yeşil / "sorun yok" değil.
 * Alt satır, kategorinin alanlarındaki bulgu sayısıdır (performans: yükleme + israf + görsel; SEO / erişilebilirlik:
 * başarısız + uyarılı denetim).
 */
export default function ResourceScanCategoryScores({ scan, analysis, t, locale, onAudit }) {
  const rows = categoryScoreRows(scan, analysis)
  const old = rows.some((r) => r.old)
  return (
    <Section slot="prs-cat-scores" icon={LayoutGrid} title={t('prs.deep.cs.title')} description={t('prs.deep.cs.desc')}>
      <div className="@container min-w-0">
        <ul className="m-0 grid min-w-0 list-none grid-cols-2 gap-2 p-0 @2xl:grid-cols-4">
          {rows.map((r) => <ScoreTile key={r.key} row={r} scan={scan} analysis={analysis} t={t} locale={locale} onAudit={onAudit} />)}
        </ul>
      </div>
      {old && <p data-slot="prs-cat-scores-old" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('prs.deep.cs.oldNote')}</p>}
    </Section>
  )
}

/** Kategorinin denetim alanlarındaki bulgu özeti: `{ state, count }` (state: old | missing | unknown | ok | issues). */
function categorySummary(key, scan, analysis) {
  const areas = AUDIT_AREAS.filter((a) => AUDIT_AREA_CATEGORY[a] === key)
  const sums = areas.map((a) => areaSummary(analysis, scan, a))
  if (sums.every((s) => s.state === 'old')) return { state: 'old', count: null }
  const known = sums.filter((s) => s.state === 'ok' || s.state === 'issues')
  if (!known.length) return { state: sums.some((s) => s.state === 'unknown') ? 'unknown' : 'missing', count: null }
  const count = known.reduce((n, s) => n + (s.count || 0), 0)
  return { state: count > 0 ? 'issues' : (known.length === sums.length ? 'ok' : 'unknown'), count }
}

function ScoreTile({ row, scan, analysis, t, locale, onAudit }) {
  const Icon = ICON[row.key] || Gauge
  const label = t(`prs.deep.cs.${row.key}`)
  const sum = categorySummary(row.key, scan, analysis)
  const scoreText = row.score == null ? '—' : formatNumber(row.score, locale)
  let sub
  if (row.old) sub = t('prs.deep.cs.notInScan')
  else if (row.score == null) sub = t('prs.deep.cs.notComputed')
  else if (row.partial) sub = t('prs.deep.cs.partial')
  else if (sum.state === 'issues') sub = plural(t, 'prs.deep.cs.findings', sum.count, formatNumber(sum.count, locale))
  else if (sum.state === 'ok') sub = t('prs.deep.cs.noFindings')
  else sub = t('prs.deep.cs.findingsUnknown')
  const aria = t('prs.deep.cs.aria', label, row.score == null ? t('prs.deep.cs.noScore') : `${scoreText}/100${row.letter ? ` (${row.letter})` : ''}`, sub)
  // Kartın tamamı tıklanır ("stretched button" — MonitorCard deseni): gerçek düğme alttaki "Ayrıntılı denetim" bağlantısı,
  // `::after` örtüsü kartı kaplar; çubuk ve metin düğmenin İÇİNDE değil (düğmede blok öğe geçersiz HTML)
  return (
    <li data-slot="prs-cat-score" data-key={row.key} data-tone={row.tone} data-available={row.available ? 'true' : 'false'}
      className="relative flex min-w-0 flex-col gap-1.5 rounded-lg border bg-card px-3 py-2.5 transition-colors hover:bg-muted/40">
      <span className="flex min-w-0 items-center gap-1.5 text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase max-sm:text-xs">
        <Icon aria-hidden="true" className="size-3.5 shrink-0" /><span className={WRAP}>{label}</span>
      </span>
      <span className="flex min-w-0 flex-wrap items-baseline gap-x-1.5">
        <span className={cn('text-2xl leading-none font-bold tabular-nums', row.score == null ? 'text-muted-foreground' : TONE_TEXT[row.tone])}>{scoreText}</span>
        <span className="text-xs text-muted-foreground">/100</span>
        {row.letter && <span className={cn('text-xs font-bold', TONE_TEXT[row.tone])}>{row.letter}</span>}
      </span>
      {row.score != null && <ProgressBar value={row.score} max={100} size="sm" tone={TONE_BAR[row.tone]} decorative />}
      <span data-slot="prs-cat-score-sub" className={cn('text-xs text-muted-foreground', WRAP)}>{sub}</span>
      <Button type="button" variant="ghost" size="sm" data-slot="prs-cat-score-open" onClick={() => onAudit?.(row.area)} aria-label={aria}
        className={cn('mt-auto h-auto w-fit justify-start px-0 py-0.5 text-xs font-medium text-primary hover:bg-transparent hover:text-primary',
          'focus-visible:ring-0 after:absolute after:inset-0 after:z-0 after:rounded-lg focus-visible:after:ring-[3px] focus-visible:after:ring-ring/50', TOUCH)}>
        {t('prs.deep.cs.open')}<ChevronRight aria-hidden="true" className="size-3.5" />
      </Button>
    </li>
  )
}
