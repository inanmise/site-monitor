import { memo, useEffect, useState } from 'react'
import { TrendingUp } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import { Card, CardContent, CardHeader } from '@/components/shadcn/card'
import { ChartContainer, ChartTooltip, BarChart, Bar, XAxis, Cell, LabelList } from '@/components/shadcn/chart'
import {
  TREND_MIN_POINTS, TREND_SUFFIX, compactNumber, fieldExempt, formatValue, monthName, trendLabelMode, trendPoints, trendRefs,
  trendTicks,
} from './techErrorModel.js'

// Çizim kenar boşlukları (px): sağda bu ayın etiketi son çubuğun dışına taşabilsin diye pay
const MARGIN = { top: 18, right: 12, bottom: 0, left: 12 }

// Gerçek yazı genişliği (canvas measureText, sayfanın yazı tipiyle) — karakter tahmini işletim sistemine göre değişiyordu
// (CI Linux'ta etiketler bitişti). jsdom'da canvas yok: ölçüm null, model tahmine düşer.
let measureCtx
let fontFamily = ''
function measureWith(px) {
  return (text) => {
    if (measureCtx === undefined) {
      measureCtx = null
      try {
        if (typeof document !== 'undefined' && !/jsdom/i.test(globalThis.navigator?.userAgent || '')) {
          measureCtx = document.createElement('canvas').getContext('2d')
        }
      } catch { measureCtx = null }
    }
    if (!measureCtx) return null
    // Sayfanın yazı tipi bir kez okunur (her etikette getComputedStyle stil hesabını zorluyordu)
    if (!fontFamily) fontFamily = getComputedStyle(document.body).fontFamily || 'sans-serif'
    const font = `${px}px ${fontFamily}`
    if (measureCtx.font !== font) measureCtx.font = font
    return measureCtx.measureText(text).width
  }
}
const measureLabel = measureWith(9)
const measureTick = measureWith(10)

/**
 * Otomatik trend bölümü (2026-10-15, Faz C): seçilen her sayı / yüzde alanı için son 12 ayın çubuk grafiği — rakamlar
 * kanalın önceki raporlarına GİRİLMİŞ değerlerden (sunucu `trend`), bu ayınki düzenleyicideki taslaktan (kaydedilmemiş
 * değişiklik hemen görünür). Başlık / ay adları / etiketler PDF'teki gibi ŞABLON dilinde. Okunamayan veri "en az iki ay
 * gerekir" diye gösterilmez — "okunamadı" der. Bu ay "Hayır" cevabıyla muaf olan alanın grafiği gösterilmez (PDF'e de
 * girmez — muaf alanın kuralı).
 */
export function TrendBlock({ section, template, trend, draftFields: liveFields, resolve, tplLang }) {
  const t = useT()
  // Yazarken grafik çizilmez: bu ayın değeri yazma durunca (TREND_DRAFT_DELAY_MS) grafiğe geçer. 2026-10-15 ölçümü (4×
  // yavaş CPU): her tuş 5 grafiği yeniden çiziyordu (tek uzun görev 740 ms); düşük öncelik (useDeferredValue) yazılan
  // alanın grafiğini yine her tuşta çizdi. Her grafik ayrıca yalnız kendi verisi değişince çizilir (TrendChart memo).
  const draftFields = useSettled(liveFields, TREND_DRAFT_DELAY_MS)
  // Web yazı tipi yüklenince yeniden ölçülür (önce yedek yazı tipiyle ölçülmüş olabilir)
  const [, setFontsReady] = useState(false)
  useEffect(() => {
    let alive = true
    globalThis.document?.fonts?.ready?.then(() => { if (alive) setFontsReady(true) }).catch(() => {})
    return () => { alive = false }
  }, [])
  const refs = trendRefs(template, section).filter((r) => r.status === 'ok' && !fieldExempt(r.field, draftFields))
  if (!refs.length) return null                     // çizilecek alan yok (şablon düzenleyici uyarır)
  const unavailable = !trend || trend.unavailable === true
  return (
    <Card className="gap-3" data-slot="te-trend" data-section={section.id}>
      <CardHeader className="gap-1">
        <h3 className="flex items-start gap-2 text-base font-semibold leading-snug [overflow-wrap:anywhere]">
          <TrendingUp aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
          <span className="min-w-0">{resolve(section.title)}</span>
        </h3>
        <p className="text-sm text-muted-foreground">{t('te.trend.hint')}</p>
      </CardHeader>
      <CardContent>
        {unavailable ? (
          <p className="m-0 text-sm text-destructive" role="alert" data-slot="te-trend-unavailable">{t('te.trend.unavailable')}</p>
        ) : (
          // Sütun sayısı KAP genişliğine göre (768 px tablette kenar çubuğuyla daralmış sayfada iki sütun çubukları eziyordu)
          <div className="@container">
            <div className="grid gap-3 @2xl:grid-cols-2">
              {refs.map((r) => (
                <TrendChart key={r.id} field={r.field} points={trendPoints(trend, r.id, draftFields, r.field)} resolve={resolve}
                  tplLang={tplLang} />
              ))}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

/** Yazma durunca grafiğe geçen gecikme (ms) */
const TREND_DRAFT_DELAY_MS = 250

/** Değer `delay` ms boyunca değişmeyince döner (ilk değer hemen) — yazarken ağır çizimi erteler. */
function useSettled(value, delay) {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    if (settled === value) return undefined
    const h = setTimeout(() => setSettled(value), delay)
    return () => clearTimeout(h)
  }, [value, delay, settled])
  return settled
}

/** Noktalar değer olarak aynı mı (her çizimde yeni dizi gelir) */
function samePoints(a, b) {
  if (a === b) return true
  if (!a || !b || a.length !== b.length) return false
  for (let i = 0; i < a.length; i++) {
    const x = a[i]
    const y = b[i]
    if (x.year !== y.year || x.month !== y.month || x.value !== y.value || x.current !== y.current) return false
  }
  return true
}

const TrendChart = memo(function TrendChart({ field, points, resolve, tplLang }) {
  const t = useT()
  const [boxRef, boxWidth] = useElementWidth()
  const lang = tplLang === 'en' ? 'en' : 'tr'
  const title = `${resolve(field.label)}${TREND_SUFFIX[lang]}`
  const withValue = points.filter((p) => p.value != null)
  const plotWidth = boxWidth ? boxWidth - MARGIN.left - MARGIN.right : 0
  // Ay adı + yıl (dar grafikte bir atlanarak; yıl değişen ilk görünen ayın altında)
  const ticks = trendTicks(points, lang, plotWidth, measureTick)
  const data = points.map((p, i) => {
    const text = p.value == null ? '' : compactNumber(p.value, field.type, lang)
    return {
      key: `${p.year}-${p.month}`,
      label: ticks[i]?.label || '',
      year: ticks[i]?.year || '',
      full: `${monthName(p.month, lang)} ${p.year}`,
      value: p.value,
      text,
      currentText: p.current ? text : '',
      current: p.current,
    }
  })
  const labels = trendLabelMode(plotWidth, data.map((d) => d.text), measureLabel)
  return (
    <figure className="m-0 flex min-w-0 flex-col gap-1.5 rounded-xl border p-3" data-slot="te-trend-chart" data-field={field.id}
      data-points={withValue.length} data-labels={labels}>
      <figcaption className="text-sm font-semibold leading-snug [overflow-wrap:anywhere]">{title}</figcaption>
      {withValue.length < TREND_MIN_POINTS ? (
        <p className="m-0 text-sm text-muted-foreground" data-slot="te-trend-note">{t('te.trend.notEnough', withValue.length)}</p>
      ) : (
        <>
          {/* Ölçüm kabı: çubuk etiketlerinin sığıp sığmadığı çizim genişliğine göre seçilir */}
          <div ref={boxRef} className="w-full">
            <ChartContainer config={{ value: { label: title, color: 'var(--primary)' } }}
              className="aspect-auto h-[200px] w-full" aria-hidden="true">
              <BarChart data={data} margin={MARGIN} barCategoryGap="22%">
                <XAxis dataKey="key" tickLine={false} axisLine={false} interval={0} height={30}
                  tick={<MonthTick data={data} />} />
                <ChartTooltip cursor={{ fillOpacity: 0.08 }} content={<TrendTooltip field={field} lang={lang} />} />
                <Bar dataKey="value" radius={[3, 3, 0, 0]} isAnimationActive={false}>
                  {data.map((d) => (
                    <Cell key={d.key} fill={d.current ? 'var(--primary)' : 'var(--muted-foreground)'} fillOpacity={d.current ? 1 : 0.45} />
                  ))}
                  <LabelList dataKey={labels === 'all' ? 'text' : 'currentText'} position="top" fontSize={9}
                    className="fill-foreground" />
                </Bar>
              </BarChart>
            </ChartContainer>
          </div>
          {/* Grafik ekran okuyucuya ay ay liste olarak okunur */}
          <ul className="sr-only">
            {data.map((d) => (
              <li key={d.key}>{`${d.full}: ${d.value == null ? t('te.trend.noValue') : formatValue(field, d.value, lang)}`}</li>
            ))}
          </ul>
        </>
      )}
    </figure>
  )
}, (prev, next) => prev.field === next.field && prev.resolve === next.resolve && prev.tplLang === next.tplLang
  && samePoints(prev.points, next.points))

/** Ay adı; yıl değişen ilk görünen ayın altında yıl (gizli ay çizilmez) */
function MonthTick({ x, y, payload, data }) {
  const d = data?.[payload?.index]
  if (!d?.label) return null
  return (
    <g transform={`translate(${x},${y})`}>
      <text textAnchor="middle" fontSize={10} className="fill-muted-foreground" data-slot="te-trend-tick">
        <tspan x={0} dy={10}>{d.label}</tspan>
        {d.year && <tspan x={0} dy={12}>{d.year}</tspan>}
      </text>
    </g>
  )
}

function TrendTooltip({ active, payload, field, lang }) {
  const d = active && payload?.length ? payload[0]?.payload : null
  if (!d || d.value == null) return null
  return (
    <div className="rounded-md border bg-background px-2 py-1 text-xs shadow-sm">
      <span className="text-muted-foreground">{d.full}: </span>
      <span className="font-medium">{formatValue(field, d.value, lang)}</span>
    </div>
  )
}
