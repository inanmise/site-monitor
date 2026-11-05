import { useEffect, useRef, useState } from 'react'
import { ScanSearch, RotateCw } from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { errorInfoOf } from '../../../utils/errorMessages.js'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { CONTEXT_SOURCES, matchesOf } from './contentDetailModel.js'

const statusOf = (r) => Number(r?.status ?? errorInfoOf(r)?.status)

/**
 * CANLI EŞLEŞMELER (kw E2) — "Eşleşmeleri göster" sayfayı şimdi okur (`POST /keyword/{id}/matches`) ve her kuralın ilk ≤ 5
 * eşleşmesini çevresiyle gösterir (bağlamlar sunucuda maskeli). Kayıt / alarm / temel YAZILMAZ. İstek sıra korumalıdır
 * (geç gelen eski yanıt yenisinin üstüne yazmaz), pencere kapanınca iptal edilir; 429'da sunucunun bekleme metni,
 * 404 / 405'te "desteklemiyor" gösterilir. Yalnız `canExecute` (EXECUTE) ile çağrılır — düğme yoksa panel açıklama gösterir.
 *
 * <p>Kancalar: `kw-matches`, `kw-matches-run`, `kw-matches-rule` (`data-id`), `kw-matches-context`, `kw-matches-none`,
 * `kw-matches-error`, `kw-matches-unsupported`.
 */
export default function KeywordMatchesPanel({ monitorId, canExecute = false, rules = null }) {
  const t = useT()
  const [state, setState] = useState({ status: 'idle' })
  const seq = useRef(0)
  const ctrl = useRef(null)
  useEffect(() => () => { seq.current++; ctrl.current?.abort?.() }, [])
  // İzleme değişince eski sonuç başka izlemeye ait olmasın
  useEffect(() => { seq.current++; ctrl.current?.abort?.(); setState({ status: 'idle' }) }, [monitorId])

  const labels = new Map((Array.isArray(rules) ? rules : []).map((r) => [r?.id, r?.label]))
  const kinds = new Map((Array.isArray(rules) ? rules : []).map((r) => [r?.id, r?.kind]))

  async function run() {
    const my = ++seq.current
    ctrl.current?.abort?.()
    const c = typeof AbortController === 'function' ? new AbortController() : null
    ctrl.current = c
    setState((s) => ({ status: 'loading', data: s.data ?? null }))
    try {
      const r = await api.monitoring.keywordMatches(monitorId, { signal: c?.signal })
      if (my !== seq.current) return
      if (r?.success) setState({ status: 'ok', data: matchesOf(r.data) })
      else if (statusOf(r) === 404 || statusOf(r) === 405) setState({ status: 'unsupported' })
      else setState((s) => ({ status: 'error', error: r?.error || t('keyword.matches.loadFailed'), data: s.data ?? null }))
    } catch (e) {
      if (my === seq.current) setState((s) => ({ status: 'error', error: e?.message || t('keyword.matches.loadFailed'), data: s.data ?? null }))
    }
  }

  const data = state.data
  const loading = state.status === 'loading'
  return (
    <section data-slot="kw-matches" aria-label={t('keyword.matches.title')} className="flex min-w-0 flex-col gap-2">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
        <h4 className="m-0 flex items-center gap-1.5 text-sm font-semibold"><ScanSearch aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />{t('keyword.matches.title')}</h4>
        {canExecute && state.status !== 'unsupported' && (
          <Button type="button" size="sm" variant={data ? 'outline' : 'default'} data-slot="kw-matches-run" onClick={run} disabled={loading}
            aria-busy={loading || undefined} className="pointer-coarse:h-10">
            {data ? <RotateCw aria-hidden="true" /> : <ScanSearch aria-hidden="true" />}
            {loading ? t('keyword.matches.running') : data ? t('keyword.matches.rerun') : t('keyword.matches.run')}
          </Button>
        )}
      </div>
      <p className="m-0 text-xs text-muted-foreground">{canExecute ? t('keyword.matches.intro') : t('keyword.matches.noPermission')}</p>

      {state.status === 'unsupported' && (
        <div data-slot="kw-matches-unsupported"><StatusBlock tone="neutral" title={t('keyword.matches.unsupported')} className="py-5" /></div>
      )}
      {state.status === 'error' && (
        <div data-slot="kw-matches-error">
          <AlertBanner tone="danger" role="alert" className="mb-0" title={t('keyword.matches.loadFailed')}>{String(state.error)}</AlertBanner>
        </div>
      )}
      {loading && !data && <div className="flex flex-col gap-2"><Skeleton className="h-4 w-1/2" /><Skeleton className="h-14 w-full" /></div>}
      {data && (
        <div className="flex min-w-0 flex-col gap-2" aria-live="polite">
          <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">
            {[data.fetchedAt ? t('keyword.matches.fetched', formatDate(data.fetchedAt)) : null,
              data.httpStatus != null ? `HTTP ${data.httpStatus}` : null,
              data.finalUrl ? `${t('keyword.matches.finalUrl')}: ${data.finalUrl}` : null].filter(Boolean).join(' · ')}
          </p>
          {data.truncated && <p className="m-0 text-xs text-amber-700 dark:text-amber-300">{t('keyword.matches.truncated')}</p>}
          <ol className="m-0 flex list-none flex-col gap-2 p-0">
            {data.rules.map((r, i) => {
              const name = (labels.get(r.id) || '').trim() || t('keyword.rule.cardTitle', i + 1)
              return (
                <li key={r.id || i} data-slot="kw-matches-rule" data-id={r.id || undefined} className="flex min-w-0 flex-col gap-1.5 rounded-md border bg-card p-2.5">
                  <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5">
                    <span className="text-sm font-semibold [overflow-wrap:anywhere]">{name}</span>
                    {r.patternPreview && (
                      <span className="font-mono text-xs text-muted-foreground [overflow-wrap:anywhere]">
                        {(r.kind ?? kinds.get(r.id)) === 'REGEX' ? `/${r.patternPreview}/` : `« ${r.patternPreview} »`}
                      </span>
                    )}
                    {r.count != null && <span className="text-xs text-muted-foreground tabular-nums">{r.count === 1 ? t('keyword.card.match1') : t('keyword.card.matches', r.count)}</span>}
                  </div>
                  {r.contexts.length === 0 ? (
                    <p data-slot="kw-matches-none" className="m-0 text-xs text-muted-foreground">{t('keyword.matches.none')}</p>
                  ) : (
                    <ul className="m-0 flex list-none flex-col gap-1 p-0">
                      {r.contexts.map((c, j) => (
                        <li key={j} data-slot="kw-matches-context" className="min-w-0 rounded bg-muted/50 px-2 py-1 font-mono text-xs leading-snug whitespace-pre-wrap [overflow-wrap:anywhere]">
                          <span className="text-muted-foreground">{c.before}</span>
                          <mark className="rounded-[3px] bg-amber-300/60 px-0.5 font-semibold text-foreground dark:bg-amber-400/30">{c.match}</mark>
                          <span className="text-muted-foreground">{c.after}</span>
                          {CONTEXT_SOURCES.includes(c.source) && (
                            <span className="ml-1.5 font-sans text-[10px] font-semibold tracking-[.04em] text-muted-foreground uppercase">{t(`keyword.matches.source.${c.source}`)}</span>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                </li>
              )
            })}
          </ol>
        </div>
      )}
    </section>
  )
}
