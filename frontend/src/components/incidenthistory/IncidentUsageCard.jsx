import { useCallback, useEffect, useRef, useState } from 'react'
import { ChevronRight, CircleAlert, ExternalLink, FileStack } from 'lucide-react'
import { api } from '../../api/client'
import { navigateTo } from '../../utils/navigate.js'
import { useT, useLanguage } from '../../i18n/index.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'
import { monthName, statusMeta } from '../techerror/techErrorModel.js'
import { linkStateMeta, readFailureKind, reportParams, usageRows } from '../techerror/incidents/incidentImportModel.js'

const STATE_TONE = {
  ok: 'text-emerald-800 dark:text-emerald-200',
  info: 'text-sky-800 dark:text-sky-200',
  warn: 'text-amber-900 dark:text-amber-200',
  danger: 'text-destructive',
  muted: 'text-muted-foreground',
}

/**
 * "KULLANILDIĞI RAPORLAR" kartı (2026-11-20): bu olay kaydının konu olarak alındığı kanal teknik hata raporları. Kapalı
 * başlar; liste YALNIZ ilk açılışta okunur (her olay ayrıntısında istek gitmesin). Okuma hatası "kullanılmıyor" demez
 * (tehlike bloğu + yeniden dene); eski sürüm (405 / kodsuz 404) "bu sürümde yok", 404 INCIDENT_NOT_FOUND "olay
 * bulunamadı ya da görülemiyor". Satır raporu açar.
 */
export default function IncidentUsageCard({ incidentId }) {
  const t = useT()
  const { lang: uiLang } = useLanguage()
  const [open, setOpen] = useState(false)
  const [state, setState] = useState({ loaded: false, loading: false, rows: null, failure: null })
  const seq = useRef(0)

  const load = useCallback(async () => {
    const my = ++seq.current
    setState((s) => ({ ...s, loading: true, failure: null }))
    let res
    try {
      res = await api.techErrors.incidentUsage(incidentId)
    } catch {
      res = null
    }
    if (my !== seq.current) return
    const rows = usageRows(res)
    if (rows) setState({ loaded: true, loading: false, rows, failure: null })
    else setState({ loaded: true, loading: false, rows: null, failure: readFailureKind(res?.success ? { status: 500 } : res) })
  }, [incidentId])

  // Yalnız ilk açılışta (ve olay değişince sıfırdan)
  useEffect(() => { seq.current++; setState({ loaded: false, loading: false, rows: null, failure: null }) }, [incidentId])
  useEffect(() => { if (open && !state.loaded && !state.loading) load() }, [open, state.loaded, state.loading, load])

  // Eski sürüm (405 / kodsuz ya da genel 404) 'bu sürümde yok'; 404 INCIDENT_NOT_FOUND olay gerçekten yok / görülemiyor
  const unsupported = state.failure === 'unsupported' || state.failure === 'missing'
  const gone = state.failure === 'incident_gone' || state.failure === 'invalid'
  const neutral = unsupported || gone || state.failure === 'forbidden'
  return (
    <Card data-slot="ih-usage" className="gap-0 py-0">
      <Collapsible open={open} onOpenChange={setOpen}>
        <h3 className="m-0">
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost" data-slot="ih-usage-toggle"
              className="h-auto min-h-10 w-full justify-start gap-2 whitespace-normal px-4 py-3 text-left text-xs font-bold tracking-wide text-muted-foreground uppercase">
              <ChevronRight aria-hidden="true" className={cn('size-4 shrink-0 transition-transform motion-reduce:transition-none', open && 'rotate-90')} />
              <FileStack aria-hidden="true" className="size-4 shrink-0" />
              <span className="[overflow-wrap:anywhere]">{t('inc.usedIn.title')}</span>
            </Button>
          </CollapsibleTrigger>
        </h3>
        <CollapsibleContent>
          <div className="flex min-w-0 flex-col gap-3 px-4 pb-4" data-slot="ih-usage-body">
            <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('inc.usedIn.hint')}</p>
            {state.loading && !state.rows ? (
              <div role="status" aria-live="polite" className="flex flex-col gap-2">
                <span className="sr-only">{t('inc.usedIn.loading')}</span>
                <Skeleton className="h-12 w-full rounded-md motion-reduce:animate-none" />
              </div>
            ) : state.failure ? (
              <div data-slot="ih-usage-failed" data-kind={state.failure}>
                <StatusBlock tone={neutral ? 'neutral' : 'danger'} icon={CircleAlert}
                  role={neutral ? undefined : 'alert'}
                  title={unsupported ? t('inc.usedIn.unsupportedTitle') : gone ? t('inc.usedIn.goneTitle')
                    : state.failure === 'forbidden' ? t('te.inc.forbiddenTitle') : t('inc.usedIn.loadFailedTitle')}
                  description={unsupported ? t('inc.usedIn.unsupported') : gone ? t('inc.usedIn.gone')
                    : state.failure === 'forbidden' ? t('inc.usedIn.noAccess') : t('inc.usedIn.loadFailed')}
                  actions={neutral ? null : (
                    <Button type="button" variant="outline" className="pointer-coarse:min-h-10" onClick={load} data-slot="ih-usage-retry">
                      {t('te.retry')}
                    </Button>
                  )} />
              </div>
            ) : state.rows && state.rows.length === 0 ? (
              <p className="m-0 rounded-md border border-dashed p-3 text-sm text-muted-foreground" data-slot="ih-usage-empty" role="status">
                {t('inc.usedIn.empty')}
              </p>
            ) : state.rows ? (
              <ul className="m-0 flex list-none flex-col gap-2 p-0" data-slot="ih-usage-list">
                {state.rows.map((r) => {
                  const st = linkStateMeta(r.state)
                  const rs = statusMeta(r.status)
                  const period = r.year && r.month ? `${monthName(r.month, uiLang)} ${r.year}` : '—'
                  return (
                    <li key={r.reportId} className="flex min-w-0 flex-col gap-2 rounded-lg border p-3 sm:flex-row sm:items-start"
                      data-slot="ih-usage-row" data-report={r.reportId}>
                      <div className="flex min-w-0 flex-1 flex-col gap-1">
                        <span className="text-sm font-semibold [overflow-wrap:anywhere]">{t('inc.usedIn.reportLine', r.channelName || '—', period)}</span>
                        {r.teamName && <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{r.teamName}</span>}
                        {r.topic && <span className="text-sm [overflow-wrap:anywhere]">{t('inc.usedIn.topic', r.topic)}</span>}
                        <span className="flex flex-wrap gap-1.5">
                          <Badge variant="outline" className="h-auto whitespace-normal rounded-md overflow-visible">{t(rs.key)}</Badge>
                          <Badge variant="outline" className={cn('h-auto whitespace-normal rounded-md overflow-visible', STATE_TONE[st.tone])}
                            data-slot="ih-usage-state" data-state={r.state}>
                            {/* Bu kartta `hidden` = olay başka takıma taşındı, o rapor artık onu izlemiyor (rapordaki "erişim yok ya da
                                taşındı" belirsizliği burada yok — kartı açan olayı görebiliyor) */}
                            {r.state === 'hidden' ? t('inc.usedIn.state.hidden') : t(st.key)}
                          </Badge>
                        </span>
                      </div>
                      <Button type="button" size="sm" variant="outline" className="w-fit shrink-0 pointer-coarse:min-h-10"
                        aria-label={t('inc.usedIn.openAria', r.channelName || '—', period)}
                        onClick={() => navigateTo('channelreports', reportParams(r.reportId, r.channelId))} data-slot="ih-usage-open">
                        <ExternalLink aria-hidden="true" /> {t('inc.usedIn.open')}
                      </Button>
                    </li>
                  )
                })}
              </ul>
            ) : null}
          </div>
        </CollapsibleContent>
      </Collapsible>
    </Card>
  )
}
