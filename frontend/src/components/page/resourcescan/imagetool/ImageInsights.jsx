import { useState } from 'react'
import { ChevronDown, Lightbulb, ListChecks, Scale } from 'lucide-react'
import { useT, useDateLocale } from '../../../../i18n/index.jsx'
import { formatNumber, formatSize } from '../resourceScanModel.js'
import { TOUCH, WRAP } from '../ResourceScanParts.jsx'
import { formatGains, howWeDid, insightRecommendations, insightRows } from './imageInsightsModel.js'
import { pctText, typeLabel } from './imageToolModel.js'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'

/**
 * Görsel aracı → AYRINTILI KARŞILAŞTIRMA (2026-11-15): kapalı açılır bölüm. Önce → sonra satırları (boyut, ölçü, en-boy,
 * toplam piksel, PPI, piksel başına bit, sıkıştırma oranı, renk / bit derinliği, JPEG kalitesi, alt örnekleme), denenen
 * biçimlerin kazanımı, öneriler ve "nasıl yaptık". Değişen satır vurgulu; bilinmeyen "—". Telefonda üç sütunlu ızgara sarar.
 */
export default function ImageInsights({ result, className }) {
  const t = useT()
  const locale = useDateLocale()
  const [open, setOpen] = useState(false)
  if (!result?.analysis) return null
  return (
    <Collapsible open={open} onOpenChange={setOpen} data-slot="prs-it-insights" className={cn('min-w-0', className)}>
      <CollapsibleTrigger asChild>
        <Button type="button" variant="ghost" size="sm" data-slot="prs-it-insights-toggle" aria-expanded={open}
          className={cn('h-auto min-h-7 justify-start gap-1 self-start px-1.5 py-1 text-xs font-medium whitespace-normal', TOUCH)}>
          <ChevronDown aria-hidden="true" className={cn('size-3.5 shrink-0 transition-transform', open && 'rotate-180')} />
          {t('prs.it.ins.toggle')}
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent className="flex min-w-0 flex-col gap-3 pt-1">
        {open && <InsightsBody result={result} t={t} locale={locale} />}
      </CollapsibleContent>
    </Collapsible>
  )
}

function InsightsBody({ result, t, locale }) {
  const rows = insightRows(result, t, locale)
  const gains = formatGains(result)
  const recs = insightRecommendations(result)
  const steps = howWeDid(result)
  const a = result.analysis
  return (
    <>
      <div data-slot="prs-it-ins-table" role="table" aria-label={t('prs.it.ins.tableLabel')}
        className="grid min-w-0 grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)_minmax(0,1fr)] gap-x-2 gap-y-1 rounded-lg border bg-muted/20 px-2.5 py-2 text-xs">
        <div role="row" className="contents">
          <span role="columnheader" className="font-semibold text-muted-foreground">{t('prs.it.ins.colMetric')}</span>
          <span role="columnheader" className="font-semibold text-muted-foreground">{t('prs.it.ins.colBefore')}</span>
          <span role="columnheader" className="font-semibold text-muted-foreground">{t('prs.it.ins.colAfter')}</span>
        </div>
        {rows.map((r) => (
          <div key={r.key} role="row" className="contents" data-slot="prs-it-ins-row" data-key={r.key} data-changed={r.changed ? 'true' : undefined}>
            <span role="rowheader" className={cn('text-muted-foreground', WRAP)}>{r.label}</span>
            <span role="cell" className={cn('tabular-nums', WRAP)}>{r.before}</span>
            <span role="cell" className={cn('tabular-nums', r.changed ? 'font-semibold text-foreground' : '', WRAP)}>{r.after}</span>
          </div>
        ))}
      </div>
      {a.colors?.count != null && (
        <p data-slot="prs-it-ins-colors" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>
          {a.colors.atLeast ? t('prs.it.ins.colorsAtLeast', formatNumber(a.colors.count, locale)) : t('prs.it.ins.colors', formatNumber(a.colors.count, locale))}
        </p>
      )}
      {gains.length > 0 && (
        <section data-slot="prs-it-ins-gains" className="flex min-w-0 flex-col gap-1.5">
          <h6 className="m-0 flex items-center gap-1.5 text-xs font-semibold text-foreground"><Scale aria-hidden="true" className="size-3.5" />{t('prs.it.ins.gainsTitle')}</h6>
          <ul className="m-0 flex list-none flex-col gap-1 p-0">
            {gains.map((g) => (
              <li key={g.type} data-slot="prs-it-ins-gain" data-type={g.type} data-chosen={g.chosen ? 'true' : undefined}
                className={cn('flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5 rounded-md border px-2 py-1 text-xs tabular-nums', g.chosen && 'border-primary/50 bg-primary/5')}>
                <span className="font-semibold">{typeLabel(g.type)}</span>
                <span>{formatSize(g.bytes, { locale })}</span>
                <span className={g.larger ? 'text-warning' : g.pct > 0 ? 'text-success' : 'text-muted-foreground'}>
                  {g.larger ? t('prs.it.ins.gainLarger') : g.pct > 0 ? t('prs.it.ins.gainPct', pctText(g.pct, locale)) : t('prs.it.ins.gainNone')}
                </span>
                {g.quality != null && <span className="text-muted-foreground">{t('prs.it.res.quality', formatNumber(g.quality, locale))}</span>}
                {g.chosen && <span className="text-primary">{t('prs.it.ins.chosen')}</span>}
              </li>
            ))}
          </ul>
        </section>
      )}
      {recs.length > 0 && (
        <section data-slot="prs-it-ins-recs" className="flex min-w-0 flex-col gap-1.5">
          <h6 className="m-0 flex items-center gap-1.5 text-xs font-semibold text-foreground"><Lightbulb aria-hidden="true" className="size-3.5" />{t('prs.it.ins.recsTitle')}</h6>
          <ul className="m-0 flex list-disc flex-col gap-1 pl-5 text-xs text-muted-foreground">
            {recs.map((r) => <li key={r.code} data-slot="prs-it-ins-rec" data-code={r.code} className={WRAP}>{recText(r, t, locale)}</li>)}
          </ul>
        </section>
      )}
      <section data-slot="prs-it-ins-how" className="flex min-w-0 flex-col gap-1.5">
        <h6 className="m-0 flex items-center gap-1.5 text-xs font-semibold text-foreground"><ListChecks aria-hidden="true" className="size-3.5" />{t('prs.it.ins.howTitle')}</h6>
        <ol className="m-0 flex list-decimal flex-col gap-1 pl-5 text-xs text-muted-foreground">
          {steps.map((s) => <li key={s.code} data-slot="prs-it-ins-step" data-code={s.code} className={WRAP}>{stepText(s, t, locale)}</li>)}
        </ol>
      </section>
    </>
  )
}

function recText(r, t, locale) {
  const [p0, p1] = r.params
  switch (r.code) {
    case 'BANNER_WIDTH': return t('prs.it.ins.rec.BANNER_WIDTH', formatNumber(p0, locale), formatNumber(p1, locale))
    case 'CONTENT_WIDTH': return t('prs.it.ins.rec.CONTENT_WIDTH', formatNumber(p0, locale))
    case 'QUALITY_HIGH': return t('prs.it.ins.rec.QUALITY_HIGH', formatNumber(p0, locale))
    case 'JPEG_RESAVE': return p0 != null ? t('prs.it.ins.rec.JPEG_RESAVE_Q', formatNumber(p0, locale)) : t('prs.it.ins.rec.JPEG_RESAVE')
    case 'BANDING': return t(p0 === 'bit_depth' ? 'prs.it.ins.rec.BANDING_BITS' : 'prs.it.ins.rec.BANDING_PALETTE')
    default: return t(`prs.it.ins.rec.${r.code}`)
  }
}

function stepText(s, t, locale) {
  const [p0, p1] = s.params
  switch (s.code) {
    case 'RESIZED': return t('prs.it.ins.how.RESIZED', formatNumber(p0, locale), formatNumber(p1, locale))
    case 'QUALITY_SEARCH': return p0 != null ? t('prs.it.ins.how.QUALITY_SEARCH', formatNumber(p0, locale)) : t('prs.it.ins.how.QUALITY_SEARCH_NONE')
    case 'BROWSER_ENCODER':
      if (p1 === 'webp') return t('prs.it.ins.how.BROWSER_ENCODER_WEBP')
      return p0 ? t('prs.it.ins.how.BROWSER_ENCODER_SS', p0) : t('prs.it.ins.how.BROWSER_ENCODER')
    case 'PNG_PALETTE': return t('prs.it.ins.how.PNG_PALETTE', formatNumber(p0, locale))
    case 'METADATA': return t(p0 === 'kept' ? 'prs.it.ins.how.METADATA_KEPT' : p0 === 'removed' ? 'prs.it.ins.how.METADATA_REMOVED' : 'prs.it.ins.how.METADATA')
    default: return t(`prs.it.ins.how.${s.code}`)
  }
}
