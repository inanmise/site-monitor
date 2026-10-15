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

/**
 * Otomatik trend bölümü (2026-10-15, Faz C): seçilen her sayı / yüzde alanı için son 12 ayın çubuk grafiği — rakamlar
 * kanalın önceki raporlarına GİRİLMİŞ değerlerden (sunucu `trend`), bu ayınki düzenleyicideki taslaktan (kaydedilmemiş
 * değişiklik hemen görünür). Başlık / ay adları / etiketler PDF'teki gibi ŞABLON dilinde. Okunamayan veri "en az iki ay
 * gerekir" diye gösterilmez — "okunamadı" der. Bu ay "Hayır" cevabıyla muaf olan alanın grafiği gösterilmez (PDF'e de
 * girmez — muaf alanın kuralı).
 */
export function TrendBlock({ section, template, trend, draftFields, resolve, tplLang }) {
  const t = useT()
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

function TrendChart({ field, points, resolve, tplLang }) {
  const t = useT()
  const [boxRef, boxWidth] = useElementWidth()
  const lang = tplLang === 'en' ? 'en' : 'tr'
  const title = `${resolve(field.label)}${TREND_SUFFIX[lang]}`
  const withValue = points.filter((p) => p.value != null)
  const plotWidth = boxWidth ? boxWidth - MARGIN.left - MARGIN.right : 0
  // Ay adı + yıl (dar grafikte bir atlanarak; yıl değişen ilk görünen ayın altında)
  const ticks = trendTicks(points, lang, plotWidth)
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
  const labels = trendLabelMode(plotWidth, data.map((d) => d.text))
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
}

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
