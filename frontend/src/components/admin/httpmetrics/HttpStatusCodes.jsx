import { Info } from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { Panel } from './HttpParts.jsx'
import { CLASS_COLOR, STATUS_CLASSES, classOf, fmtInt, fmtPct } from './httpMetricsModel.js'

/**
 * Durum kodu dağılımı: üstte sınıf başına bölümlü şerit (2 px aralıklı; renk + yanındaki metin — renk tek başına
 * anlam taşımaz), altında sınıf anahtarı (ad + adet + pay), en altta kod listesi (kod rozeti + göreli çubuk + adet +
 * pay; en çok {@link MAX_CODES}). Durum süzgeci etkinse seçili olmayan sınıflar soluk. Eski (kod bilgisi olmadan
 * kaydedilmiş) istekler "sınıfsız" olarak ayrıca söylenir.
 */
const MAX_CODES = 12

export default function HttpStatusCodes({ t, summary, codes, statusFilter }) {
  const total = summary.total
  const dim = (c) => statusFilter?.length > 0 && !statusFilter.includes(c)
  const classRows = [...STATUS_CLASSES, 'other'].map((c) => ({ c, n: summary.classes[c] || 0 })).filter((r) => r.n > 0)
  const shown = codes.slice(0, MAX_CODES)
  const maxCode = shown.reduce((m, r) => Math.max(m, r.count), 0)
  const share = (n) => (total > 0 ? (n / total) * 100 : 0)

  return (
    <Panel data-slot="hreq-codes" title={t('hreq.codes.title')}
      right={<span className="text-xs text-muted-foreground tabular-nums">{t('hreq.codes.total', fmtInt(total))}</span>}>
      {classRows.length === 0 && summary.unclassified === 0 ? (
        <p className="m-0 text-xs text-muted-foreground">{t('hreq.codes.empty')}</p>
      ) : (
        <>
          <div data-slot="hreq-codes-bar" aria-hidden="true" className="flex h-3 w-full gap-0.5 overflow-hidden rounded-full bg-muted">
            {classRows.map(({ c, n }) => (
              <span key={c} data-class={c} className={cn('h-full min-w-1 bg-(--seg) first:rounded-l-full last:rounded-r-full', dim(c) && 'opacity-35')}
                style={{ '--seg': CLASS_COLOR[c], width: `${share(n)}%` }} />
            ))}
            {summary.unclassified > 0 && (
              <span data-class="unclassified" className="h-full min-w-1 bg-(--seg) opacity-40 last:rounded-r-full"
                style={{ '--seg': CLASS_COLOR.unclassified, width: `${share(summary.unclassified)}%` }} />
            )}
          </div>
          <ul data-slot="hreq-class-legend" className="m-0 grid list-none grid-cols-2 gap-x-4 gap-y-1.5 p-0 text-xs sm:grid-cols-4">
            {classRows.map(({ c, n }) => (
              <li key={c} data-class={c} className={cn('flex min-w-0 items-center gap-1.5', dim(c) && 'opacity-50')}>
                <span aria-hidden="true" className="size-2.5 shrink-0 rounded-sm bg-(--dot)" style={{ '--dot': CLASS_COLOR[c] }} />
                <span className="min-w-0 truncate text-muted-foreground">{t(`hreq.class.${c}`)}</span>
                <span className="ml-auto font-semibold text-foreground tabular-nums">{fmtInt(n)}</span>
                <span className="w-12 text-right text-muted-foreground tabular-nums">{fmtPct(share(n))}</span>
              </li>
            ))}
          </ul>
          {shown.length > 0 && (
            <ul data-slot="hreq-code-list" className="m-0 flex list-none flex-col gap-1 border-t p-0 pt-2.5">
              {shown.map((r) => {
                const c = classOf(r.code)
                return (
                  <li key={r.code} data-code={r.code} className={cn('grid grid-cols-[3.5rem_minmax(0,1fr)_auto_3.5rem] items-center gap-2 text-xs', dim(c) && 'opacity-50')}>
                    <Badge variant="outline" className="h-5 justify-center gap-1 rounded-md px-1.5 font-mono font-bold tabular-nums">
                      <span aria-hidden="true" className="size-1.5 rounded-full bg-(--dot)" style={{ '--dot': CLASS_COLOR[c] }} />
                      {r.code === 0 ? '—' : r.code}
                    </Badge>
                    <span aria-hidden="true" className="h-1.5 overflow-hidden rounded-full bg-muted">
                      <span className="block h-full rounded-full bg-(--dot)" style={{ '--dot': CLASS_COLOR[c], width: `${maxCode ? (r.count / maxCode) * 100 : 0}%` }} />
                    </span>
                    <span className="text-right font-semibold text-foreground tabular-nums">{fmtInt(r.count)}</span>
                    <span className="text-right text-muted-foreground tabular-nums">{fmtPct(share(r.count))}</span>
                  </li>
                )
              })}
              {codes.length > MAX_CODES && (
                <li className="text-xs text-muted-foreground">{t('hreq.codes.more', codes.length - MAX_CODES)}</li>
              )}
            </ul>
          )}
          {summary.unclassified > 0 && (
            <p data-slot="hreq-legacy-note" className="m-0 flex items-start gap-1.5 text-xs text-muted-foreground">
              <Info aria-hidden="true" className="mt-px size-3.5 shrink-0" />
              {t('hreq.codes.legacyNote', fmtInt(summary.unclassified))}
            </p>
          )}
        </>
      )}
    </Panel>
  )
}
