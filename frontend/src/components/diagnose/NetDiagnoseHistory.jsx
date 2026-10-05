import { useEffect, useState } from 'react'
import { History, ChevronRight } from 'lucide-react'
import { formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { findingTitle, historyItems, routeText, verdictTone } from './netDiagnoseModel.js'

/**
 * Ping / Port / DNS tanılama geçmişi — bu izlemenin son çalıştırmaları (hüküm, zaman, çalıştıran, hedef, yol, süre, no).
 * Satır = tam genişlik outline düğme (≥ 44 px; telefonda kart gibi sarar, tablo yok → yatay kaydırma yok). Tıklayınca
 * kayıtlı sonuç açılır (yeniden koşu YOK). Yetki kapısı çağıranda ve sunucuda (403 ise hata şeridi).
 *
 * @param {number}   monitorId
 * @param {Function} load      (monitorId) => Promise — türün geçmiş ucu
 * @param {Function} onOpen    (row) => void
 * @param {number}   [reloadKey] her canlı koşu bitince artar (liste tazelensin)
 */
export default function NetDiagnoseHistory({ monitorId, load, onOpen, activeRunId = null, reloadKey = 0 }) {
  const t = useT()
  const [state, setState] = useState({ loading: true, items: null, error: null })

  useEffect(() => {
    let alive = true
    setState((s) => ({ ...s, loading: true, error: null }))
    Promise.resolve(load(monitorId)).then((res) => {
      if (!alive) return
      if (res?.success) setState({ loading: false, items: historyItems(res.data), error: null })
      else setState({ loading: false, items: null, error: res?.error || t('httpdx.history.error') })
    }).catch(() => { if (alive) setState({ loading: false, items: null, error: t('httpdx.history.error') }) })
    return () => { alive = false }
  }, [monitorId, reloadKey]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div data-slot="ndx-history" className="flex min-w-0 flex-col gap-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="flex items-center gap-1.5 text-sm font-semibold"><History aria-hidden="true" className="size-4 text-primary" />{t('httpdx.history.title')}</h3>
        <span className="text-xs text-muted-foreground">{t('ndx.history.note')}</span>
      </div>
      {state.loading && (
        <div role="status" className="flex items-center gap-1.5 text-[13px]"><Spinner size={14} inline decorative /> {t('httpdx.history.loading')}</div>
      )}
      {state.error && <AlertBanner tone="danger" className="mb-0">{state.error}</AlertBanner>}
      {state.items && !state.items.length && (
        <StatusBlock tone="neutral" icon={History} title={t('httpdx.history.empty')} className="py-6" />
      )}
      {state.items && state.items.length > 0 && (
        <ul className="flex min-w-0 flex-col gap-2">
          {state.items.map((r) => {
            const when = formatDateSec(r.started_at)
            const status = r.verdict_status || 'warn'
            const title = r.verdict_code ? findingTitle(r.verdict_code, t) : '—'
            return (
              <li key={r.id} className="min-w-0">
                <Button type="button" variant="outline" data-slot="ndx-history-row" data-run={r.id}
                  aria-current={activeRunId === r.id ? 'true' : undefined}
                  aria-label={t('httpdx.history.open', `#${r.id} · ${when} · ${title}`)}
                  onClick={() => onOpen?.(r)}
                  className="h-auto min-h-11 w-full min-w-0 justify-start gap-3 px-3 py-2 text-left font-normal whitespace-normal aria-[current=true]:border-primary">
                  <span className="flex min-w-0 flex-1 flex-col gap-1">
                    <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                      <ToneBadge tone={verdictTone(status)} data-status={status} className="font-semibold">{t(`httpdx.status.${status}`)}</ToneBadge>
                      <span className="min-w-0 text-sm font-medium break-words">{title}</span>
                    </span>
                    <span className="flex min-w-0 flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
                      <span className="tabular-nums">{when}</span>
                      {r.executed_by && <span>{r.executed_by}</span>}
                      {r.target && <span className="min-w-0 font-mono break-all">{r.target}</span>}
                      {r.route && <span>{routeText(r.route, t)}</span>}
                      {r.traceroute && <span>{t('ndx.history.traceroute')}</span>}
                      {r.findings != null && <span>{t('ndx.history.findings', r.findings)}</span>}
                      {r.duration_ms != null && <span className="tabular-nums">{r.duration_ms} ms</span>}
                      <span className="tabular-nums">#{r.id}</span>
                    </span>
                  </span>
                  <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
                </Button>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
