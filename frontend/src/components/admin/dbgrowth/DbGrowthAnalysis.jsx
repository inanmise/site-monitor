import { useCallback, useEffect, useRef, useState } from 'react'
import { Stethoscope } from 'lucide-react'
import { api, formatDateSec } from '../../../api/client'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { Panel } from '../httpmetrics/HttpParts.jsx'
import { Button } from '@/components/shadcn/button'
import DbGrowthFindings from './DbGrowthFindings.jsx'
import { ANALYSIS_SECTIONS, analysisTone, formatNumber, sectionTone } from './dbGrowthModel.js'

/**
 * İstek üzerine salt okunur veritabanı sağlık analizi (2026-11-13): vacuum durumu, dizin şişmesi, kullanılmayan dizinler,
 * son 24 saatin en çok satır yazan izlemeleri ve kısaltma adaylarının KESİN (histogram) tahmini. Sunucu: deyim başına 5 sn,
 * toplam 60 sn, pod başına tek analiz, kişi başına dakikada 3. Son sonuç saklanır ve açılışta okunur.
 */
export default function DbGrowthAnalysis({ t, canAnalyze, canApply, canOpenRetention, hasLast, onApplied }) {
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(!!hasLast)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)
  const seq = useRef(0)

  const loadLatest = useCallback(async () => {
    const my = ++seq.current
    setLoading(true); setError(null)
    try {
      const res = await api.admin.dbGrowth.latestAnalysis()
      if (my !== seq.current) return
      if (res?.success) setResult(res.data?.analysis || null)
      else setError(res?.error || t('dbg.an.loadFailed'))
    } catch (e) {
      if (my === seq.current) setError(e?.message || t('dbg.an.loadFailed'))
    } finally {
      if (my === seq.current) setLoading(false)
    }
  }, [t])

  useEffect(() => { if (hasLast) loadLatest() }, [hasLast, loadLatest])

  async function run() {
    const my = ++seq.current
    setRunning(true); setError(null); setNotice(null)
    try {
      const res = await api.admin.dbGrowth.runAnalysis()
      if (my !== seq.current) return
      if (res?.success) setResult(res.data)
      else if (res?.code === 'DB_ANALYSIS_BUSY' || res?.code === 'RATE_LIMITED') setNotice(res.error)
      else setError(res?.error || t('dbg.an.runFailed'))
    } catch (e) {
      if (my === seq.current) setError(e?.message || t('dbg.an.runFailed'))
    } finally {
      if (my === seq.current) setRunning(false)
    }
  }

  const sections = result?.sections || {}
  const bloat = result?.index_bloat
  return (
    <Panel data-slot="dbg-analysis" title={t('dbg.an.title')}
      right={canAnalyze && (
        <Button type="button" size="sm" data-slot="dbg-analysis-run" onClick={run} disabled={running} aria-busy={running || undefined}
          className="pointer-coarse:h-10">
          {running ? <Spinner size={14} inline decorative /> : <Stethoscope aria-hidden="true" />}
          {running ? t('dbg.an.running') : t('dbg.an.run')}
        </Button>
      )}>
      <p className="m-0 text-xs text-muted-foreground">{t('dbg.an.desc')}</p>
      {notice && <AlertBanner tone="info" className="mb-0" onDismiss={() => setNotice(null)} dismissLabel={t('app.close')}>{notice}</AlertBanner>}
      {error && <AlertBanner tone="danger" role="alert" className="mb-0">{error}</AlertBanner>}
      {loading && !result && <p className="m-0 text-sm text-muted-foreground">{t('dbg.an.loading')}</p>}
      {!loading && !result && !error && (
        <p data-slot="dbg-analysis-none" className="m-0 rounded-md bg-muted/40 px-3 py-2.5 text-sm text-muted-foreground">
          {canAnalyze ? t('dbg.an.never') : t('dbg.an.neverReadOnly')}
        </p>
      )}
      {result && (
        <div data-slot="dbg-analysis-result" data-status={result.status} className="flex min-w-0 flex-col gap-3">
          <div className="flex min-w-0 flex-wrap items-center gap-2 text-xs text-muted-foreground">
            <ToneBadge tone={analysisTone(result.status)} data-slot="dbg-analysis-status" className="rounded-md font-semibold">
              {t(`dbg.an.status.${result.status || 'UNKNOWN'}`)}
            </ToneBadge>
            <span className="[overflow-wrap:anywhere]">
              {t('dbg.an.meta', formatDateSec(result.started_at), result.actor || '—', result.pod || '—',
                result.duration_ms != null ? formatNumber(result.duration_ms / 1000, 1) : '—')}
            </span>
          </div>
          {result.unreadable && <AlertBanner tone="warning" className="mb-0">{t('dbg.an.unreadable')}</AlertBanner>}
          {result.supported === false ? (
            <p className="m-0 text-sm text-muted-foreground">{t('dbg.notPostgres')}</p>
          ) : (
            <>
              <ul data-slot="dbg-analysis-sections" className="m-0 flex list-none flex-wrap gap-1.5 p-0">
                {[...ANALYSIS_SECTIONS, ...Object.keys(sections).filter((k) => !ANALYSIS_SECTIONS.includes(k))].map((k) => (
                  <li key={k}>
                    <ToneBadge tone={sectionTone(sections[k])} data-section={k} data-state={sections[k] || 'unknown'}
                      className="h-auto rounded-md whitespace-normal">
                      {t(`dbg.an.sec.${k}`)} · {t(`dbg.an.secState.${sections[k] || 'unknown'}`)}
                    </ToneBadge>
                  </li>
                ))}
              </ul>
              {bloat && sections.index_bloat === 'ok' && (
                <p data-slot="dbg-analysis-bloat" className="m-0 text-xs text-muted-foreground">
                  {t('dbg.an.bloatSummary', formatNumber(bloat.checked), formatNumber((result.findings || []).filter((f) => f.code === 'INDEX_BLOAT').length),
                    formatNumber(bloat.unknown))}
                </p>
              )}
              <DbGrowthFindings findings={result.findings} t={t} canApply={canApply} canOpenRetention={canOpenRetention}
                onApplied={onApplied} slot="dbg-analysis-findings" />
            </>
          )}
        </div>
      )}
    </Panel>
  )
}
