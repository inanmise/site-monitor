import { ChevronRight, CircleCheck, Snail, TriangleAlert } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { EndpointLabel, Panel, ToneValue } from './HttpParts.jsx'
import { errTone, fmtInt, fmtPct, msTone } from './httpMetricsModel.js'

/**
 * Bölümün iki kısa listesi (son 24 saat, kalıcı seriden): "En çok hata veren uçlar" ve "En yavaş uçlar" (p95, en az 5
 * istek). Her satır bir düğmedir → İstek Gezgini'ni o uca odaklı açar (klavye + dokunmatik, 40 px+). Erişilebilir ad
 * satırı ayırt eder (uç adı + eylem). Liste yoksa (eski sunucu / veritabanı hatası) bölüm onu hiç çizmez.
 */
function Row({ ep, t, onOpen, children }) {
  return (
    <li>
      <Button type="button" variant="ghost" data-slot="hreq-top-row" data-endpoint={ep.endpoint}
        aria-label={t('hreq.top.open', ep.endpoint)} onClick={() => onOpen(ep.endpoint)}
        className="h-auto min-h-11 w-full flex-wrap items-start justify-between gap-x-3 gap-y-1 rounded-md px-2 py-2 text-left font-normal whitespace-normal">
        <EndpointLabel endpoint={ep.endpoint} method={ep.method} path={ep.path} className="min-w-0 flex-1 basis-48" />
        <span className="ml-auto flex shrink-0 items-center gap-1 text-xs text-muted-foreground">
          {children}
          <ChevronRight aria-hidden="true" className="size-4 opacity-60" />
        </span>
      </Button>
    </li>
  )
}

export default function HttpTopLists({ t, top, fmt, onOpen }) {
  if (!top) return null
  return (
    <div data-slot="hreq-top" className="grid grid-cols-1 gap-3 md:grid-cols-2">
      <Panel title={<span className="inline-flex items-center gap-1.5"><TriangleAlert aria-hidden="true" className="size-4 text-destructive" />{t('hreq.top.errors')}</span>}
        data-slot="hreq-top-errors" className="gap-2">
        {top.errors.length === 0 ? (
          <p className="m-0 flex items-center gap-1.5 text-xs text-success"><CircleCheck aria-hidden="true" className="size-3.5" />{t('hreq.top.noErrors')}</p>
        ) : (
          <ul className="m-0 flex list-none flex-col gap-0.5 p-0">
            {top.errors.map((ep) => (
              <Row key={ep.endpoint} ep={ep} t={t} onOpen={onOpen}>
                <ToneValue t={t} tone={errTone(ep.errorRate, ep.count)} className="font-semibold">
                  {t('hreq.top.errLine', fmtInt(ep.errors), fmtPct(ep.errorRate))}
                </ToneValue>
              </Row>
            ))}
          </ul>
        )}
      </Panel>
      <Panel title={<span className="inline-flex items-center gap-1.5"><Snail aria-hidden="true" className="size-4 text-muted-foreground" />{t('hreq.top.slowest')}</span>}
        data-slot="hreq-top-slowest" className="gap-2">
        {top.slowest.length === 0 ? (
          <p className="m-0 text-xs text-muted-foreground">{t('hreq.top.noData')}</p>
        ) : (
          <ul className="m-0 flex list-none flex-col gap-0.5 p-0">
            {top.slowest.map((ep) => (
              <Row key={ep.endpoint} ep={ep} t={t} onOpen={onOpen}>
                <ToneValue t={t} tone={msTone(ep.p95)} className="font-semibold">{t('hreq.top.slowLine', fmt.value(ep.p95))}</ToneValue>
                <span className="max-sm:hidden">· {t('hreq.top.reqLine', fmtInt(ep.count))}</span>
              </Row>
            ))}
          </ul>
        )}
      </Panel>
    </div>
  )
}
