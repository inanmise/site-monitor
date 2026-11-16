import { memo, useCallback, useMemo, useState } from 'react'
import { ChartGantt, ChevronDown } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { formatBytes } from '../../../utils/formatBytes.js'
import ImageActions from '../../page/resourcescan/imagetool/ImageActions.jsx'
import { WF_COLORS, WF_TYPES, barBox, imageItemOf } from './pageSpeedDeepModel.js'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

const WRAP = '[overflow-wrap:anywhere]'

/** Adresin kısa adı (yolun son parçası; yoksa sunucu) — tam adres ayrıntıda ve satırın erişilebilir adında. */
function shortName(url) {
  try {
    const u = new URL(url)
    const parts = u.pathname.split('/').filter(Boolean)
    return parts.length ? decodeURIComponent(parts[parts.length - 1]).slice(0, 120) : u.host
  } catch {
    return String(url || '').slice(0, 120)
  }
}

const ms = (v) => `${Math.round(v)} ms`

/**
 * Şelale (Sayfa Hızı 2. sürüm madde 16): ana HTML isteği (ilk bayt bekleme + indirme) ve her kaynağın İSTEĞİNİN ATILDIĞI
 * an → bittiği an, ölçülmüş değerlerle (tahmin değil). Satıra basınca ayrıntı (tam adres, başlangıç, süre, boyut, durum) ve
 * görsel satırında görsel aracı menüsü. Geniş kapta ad | çubuk iki sütun, dar kapta ad üstte çubuk altta. Renkler tür
 * renkleri (durum rengi değil); alınamayan kaynak kesik kenar + "alınamadı" metniyle.
 */
function PageSpeedWaterfall({ wf, data }) {
  const t = useT()
  const [openKey, setOpenKey] = useState(null)
  // Satır aç / kapa: TEK kararlı geri çağırma + not edilmiş satırlar (inceleme 2026-11-17) — 500 satırlık şelalede bir satıra
  // basmak yalnız o satırı (ve kapanan önceki satırı) yeniden çizer, hepsini değil
  const toggle = useCallback((key) => setOpenKey((k) => (k === key ? null : key)), [])
  const byUrl = useMemo(() => {
    const resources = Array.isArray(data?.resources) ? data.resources : []
    return new Map(resources.filter((r) => r && typeof r.url === 'string').map((r) => [r.url, r]))
  }, [data])
  if (!wf) return null
  if (wf.reason === 'no_start') {
    return (
      <p data-slot="pspd-wf-nostart" className={cn('m-0 rounded-lg border border-dashed px-3 py-4 text-sm text-muted-foreground', WRAP)}>
        {t('pspd.wf.noStart')}
      </p>
    )
  }
  const total = wf.totalMs
  return (
    <section data-slot="pspd-wf" className="@container flex min-w-0 flex-col gap-2">
      <div className="flex min-w-0 flex-wrap items-baseline justify-between gap-2">
        <h3 className="m-0 flex items-center gap-1.5 text-sm font-semibold text-foreground">
          <ChartGantt aria-hidden="true" className="size-4" />{t('pspd.wf.title')}
        </h3>
        <p data-slot="pspd-wf-count" className="m-0 text-xs tabular-nums text-muted-foreground">
          {wf.truncated ? t('pspd.wf.countTruncated', wf.shown, wf.total) : t('pspd.wf.count', wf.shown)} · {t('pspd.wf.totalMs', Math.round(total))}
        </p>
      </div>
      <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('pspd.wf.desc')}</p>
      <ul data-slot="pspd-wf-legend" className="m-0 flex list-none flex-wrap gap-x-3 gap-y-1 p-0 text-xs text-muted-foreground">
        {WF_TYPES.map((k) => (
          <li key={k} className="flex items-center gap-1">
            <span aria-hidden="true" className="inline-block size-2.5 rounded-sm" style={{ background: WF_COLORS[k] }} />{t(`pspd.wf.type.${k}`)}
          </li>
        ))}
      </ul>
      {wf.unknownStart > 0 && (
        <p data-slot="pspd-wf-unknown" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('pspd.wf.unknownStart', wf.unknownStart)}</p>
      )}

      <div className="min-w-0 rounded-lg border">
        {/* Eksen: ad sütunu boş, çubuk sütununda ms işaretleri */}
        <div className="hidden border-b px-2 py-1 text-[11px] tabular-nums text-muted-foreground @xl:grid @xl:grid-cols-[minmax(0,2fr)_minmax(0,3fr)] @xl:gap-2">
          <span>{t('pspd.wf.colResource')}</span>
          <div className="relative h-4">
            {wf.ticks.map((v) => {
              const left = Math.min(100, (v / total) * 100)
              return (
                <span key={v} className="absolute top-0 -translate-x-1/2 whitespace-nowrap first:translate-x-0" style={{ left: `${left}%` }}>
                  {v >= 1000 ? `${(v / 1000).toFixed(v % 1000 === 0 ? 0 : 1)} s` : `${v} ms`}
                </span>
              )
            })}
          </div>
        </div>
        <ul className="m-0 flex list-none flex-col p-0" data-slot="pspd-wf-rows">
          {wf.html && <HtmlRow html={wf.html} total={total} t={t} />}
          {wf.rows.map((r) => (
            <ResourceRow key={r.key} row={r} total={total} t={t} open={openKey === r.key} onToggle={toggle} raw={byUrl.get(r.url)} />
          ))}
        </ul>
      </div>
      {wf.html?.phases?.length > 0 && (
        <p data-slot="pspd-wf-phases" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>
          {t('pspd.wf.phases', wf.html.phases.map((p) => `${t(`pspd.ins.${p.key}`)} ${ms(p.ms)}`).join(' · '))}
        </p>
      )}
    </section>
  )
}

const HtmlRow = memo(function HtmlRow({ html, total, t }) {
  const box = barBox(0, html.end, total)
  return (
    <li data-slot="pspd-wf-row" data-type="html" className="grid min-w-0 grid-cols-1 gap-1 border-b px-2 py-1.5 @xl:grid-cols-[minmax(0,2fr)_minmax(0,3fr)] @xl:items-center @xl:gap-2">
      <span className={cn('min-w-0 text-xs font-medium text-foreground', WRAP)}>
        {t('pspd.wf.htmlRow')} <span className="font-normal text-muted-foreground">· {ms(html.end)}{html.bytes != null ? ` · ${formatBytes(html.bytes)}` : ''}</span>
      </span>
      <div className="relative h-3 min-w-0 rounded-sm bg-muted/50" aria-hidden="true">
        <div className="absolute inset-y-0 rounded-sm" style={{ left: `${box.left}%`, width: `${box.width}%`, background: WF_COLORS.html }} />
      </div>
    </li>
  )
})

const ResourceRow = memo(function ResourceRow({ row, total, t, open, onToggle, raw }) {
  const box = barBox(row.start, row.end, total)
  const img = imageItemOf(raw)
  const label = t('pspd.wf.rowLabel', shortName(row.url), t(`pspd.wf.type.${row.type}`), ms(row.start), ms(row.duration))
  return (
    <li data-slot="pspd-wf-row" data-type={row.type} data-failed={row.failed ? 'true' : undefined} className="border-b last:border-b-0">
      <Button type="button" variant="ghost" aria-expanded={open} aria-label={label} onClick={() => onToggle(row.key)} data-slot="pspd-wf-toggle"
        className="grid h-auto w-full min-w-0 grid-cols-1 items-stretch justify-stretch gap-1 rounded-none px-2 py-1.5 text-left font-normal whitespace-normal hover:bg-muted/40 @xl:grid-cols-[minmax(0,2fr)_minmax(0,3fr)] @xl:items-center @xl:gap-2 pointer-coarse:min-h-10">
        <span className="flex min-w-0 items-center gap-1.5 text-xs">
          <ChevronDown aria-hidden="true" className={cn('size-3.5 shrink-0 text-muted-foreground transition-transform', open && 'rotate-180')} />
          <span className={cn('min-w-0 text-foreground', WRAP)}>{shortName(row.url)}</span>
          <span className="shrink-0 tabular-nums text-muted-foreground">{ms(row.duration)}</span>
          {row.failed && <span className="shrink-0 font-medium text-destructive">{t('pspd.wf.unloaded')}</span>}
        </span>
        <span className="relative block h-3 min-w-0 rounded-sm bg-muted/50" aria-hidden="true">
          <span className={cn('absolute inset-y-0 rounded-sm', row.failed && 'border border-dashed border-destructive')}
            style={{ left: `${box.left}%`, width: `${box.width}%`, background: WF_COLORS[row.type] }} />
        </span>
      </Button>
      {open && (
        <div data-slot="pspd-wf-detail" className="flex min-w-0 flex-col gap-1.5 bg-muted/30 px-3 py-2 text-xs">
          <span className={cn('font-mono text-[11px] text-foreground', WRAP)}>{row.url}</span>
          <dl className="m-0 grid grid-cols-2 gap-x-3 gap-y-0.5 text-muted-foreground sm:grid-cols-4">
            <dt>{t('pspd.wf.start')}</dt><dd className="m-0 tabular-nums text-foreground">{ms(row.start)}</dd>
            <dt>{t('pspd.wf.duration')}</dt><dd className="m-0 tabular-nums text-foreground">{ms(row.duration)}</dd>
            <dt>{t('pspd.wf.size')}</dt><dd className="m-0 tabular-nums text-foreground">{formatBytes(row.bytes, row.truncatedBytes)}</dd>
            <dt>{t('pspd.wf.status')}</dt><dd className="m-0 tabular-nums text-foreground">{row.status ?? '—'}</dd>
            <dt>{t('pspd.wf.party')}</dt><dd className="m-0 text-foreground">{t(row.thirdParty ? 'pspd.wf.third' : 'pspd.wf.first')}</dd>
          </dl>
          {row.foundIn && (
            <span data-slot="pspd-wf-foundin" className={cn('text-muted-foreground', WRAP)}>
              {t('pspd.res.foundIn')} <span className="font-mono text-[11px] text-foreground">{row.foundIn}</span>
            </span>
          )}
          {img && <ImageActions item={img} showSuggestion={false} />}
        </div>
      )}
    </li>
  )
})

export default memo(PageSpeedWaterfall)
