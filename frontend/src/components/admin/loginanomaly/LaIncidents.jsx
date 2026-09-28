import { useCallback, useEffect, useRef, useState } from 'react'
import { History, RefreshCw, ScrollText, ShieldCheck } from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useIsMobile } from '../../../hooks/use-mobile.js'
import { navigateTo } from '../../../utils/navigate.js'
import { relativeTime } from '../audit/auditFormat.js'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { LoadingBlock, Spinner } from '../../ui/Progress.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { SettingsSection } from '../SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Item } from '@/components/shadcn/item'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { auditParams, fmtMinutes, incidentMinutes, parseSignature } from './laModel.js'
import { RuleBadge } from './laUi.jsx'

/**
 * Son tetiklenen login anomali OLAYLARI (`GET /admin/login-anomaly/incidents`, en yeni üstte, sayfalı).
 *
 * <p>Yükleme durumu sayfa kabuğunda (`useLaIncidents`) — başlıktaki durum satırı ("son 30 günde N olay") da aynı
 * veriden türer, ek istek yok. Yarış: yalnız EN SON isteğin yanıtı yazar (`seq`), bayraklar `finally`'de iner,
 * `alive` bayrağı efekt KURULUMUNDA da `true`'ya çekilir (StrictMode çift koşar).
 *
 * <p>Görünüm: telefonda (< 768 px) ya da liste kabı 560 px'ten darken kart listesi; daha genişte shadcn Table. Tablo sütunları KAP genişliğine göre
 * açılır (`@container`): Ayarlar'da kenar çubuğu + bölüm gezintisi içeriği 1024 px ekranda ~480 px'e indiriyor,
 * görünüm alanı kırılma noktası burada yanıltır. Tam tarih her zaman görünür metin (yalnız-hover bilgi yok).
 * Olaylar IP / hesap taşımaz; "Girişleri gör" olayın penceresini Denetim Logu'nda `LOGIN_FAILED` süzgeciyle açar.
 */
export function useLaIncidents(pageSize = 10) {
  const t = useT()
  const tRef = useRef(t)
  tRef.current = t
  const [items, setItems] = useState(null)
  const [total, setTotal] = useState(0)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [moreError, setMoreError] = useState(null)
  const seq = useRef(0)
  const alive = useRef(true)

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])

  const load = useCallback(async () => {
    const id = ++seq.current
    setLoading(true)
    setMoreError(null)
    try {
      const res = await api.admin.getLoginAnomalyIncidents(0, pageSize)
      if (!alive.current || id !== seq.current) return
      if (res?.success) {
        const data = Array.isArray(res.data) ? res.data : []
        setItems(data)
        setTotal(Number.isFinite(Number(res.total)) ? Number(res.total) : data.length)
        setError(null)
      } else {
        setError(res?.error || res?.message || tRef.current('loginAnomaly.inc.errorTitle'))
      }
    } catch (e) {
      if (alive.current && id === seq.current) setError(e?.message || tRef.current('loginAnomaly.inc.errorTitle'))
    } finally {
      if (alive.current && id === seq.current) setLoading(false)
    }
  }, [pageSize])

  const loadMore = useCallback(async (current) => {
    const id = seq.current   // araya bir yenileme girerse bu yanıt yazmaz
    const page = Math.floor((current?.length || 0) / pageSize)
    setLoadingMore(true)
    setMoreError(null)
    try {
      const res = await api.admin.getLoginAnomalyIncidents(page, pageSize)
      if (!alive.current || id !== seq.current) return
      if (res?.success) {
        const more = Array.isArray(res.data) ? res.data : []
        setItems((prev) => {
          const seen = new Set((prev || []).map((x) => x.id))
          return [...(prev || []), ...more.filter((x) => !seen.has(x.id))]
        })
        if (Number.isFinite(Number(res.total))) setTotal(Number(res.total))
      } else {
        setMoreError(res?.error || res?.message || tRef.current('loginAnomaly.inc.errorTitle'))
      }
    } catch (e) {
      if (alive.current && id === seq.current) setMoreError(e?.message || tRef.current('loginAnomaly.inc.errorTitle'))
    } finally {
      setLoadingMore(false)
    }
  }, [pageSize])

  useEffect(() => { load() }, [load])

  return { items, total, error, loading, loadingMore, moreError, load, loadMore }
}

/**
 * Liste kabı DAR mı (< `limit` px)? Ayarlar'da kenar çubuğu + bölüm gezintisi içeriği tablette ve 1024 px'te ~400 px'e
 * indiriyor; orada tablo rozetleri ezer → kart listesi. Ölçüm yoksa (jsdom, ilk çizim) geniş sayılır.
 * MonitorStatsBar ile aynı yaklaşım (ResizeObserver ile kap genişliği).
 */
function useNarrow(ref, limit = 560) {
  const [narrow, setNarrow] = useState(false)
  useEffect(() => {
    const el = ref.current
    if (!el || typeof ResizeObserver === 'undefined') return undefined
    const ro = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect?.width || 0
      if (w > 0) setNarrow(w < limit)
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [ref, limit])
  return narrow
}

/** Tek olayın gösterim alanları (kart ve tablo aynı kaynaktan). */
function rowView(it, t, now) {
  const exact = formatDate(it.opened_at)
  const mins = incidentMinutes(it, now)
  const dur = mins == null ? '—' : fmtMinutes(mins, t)
  const realerts = Number(it.realert_count) || 0
  return {
    exact,
    rel: relativeTime(it.opened_at, t, now) || exact,
    dur,
    durText: it.resolved ? t('loginAnomaly.inc.lasted', dur) : t('loginAnomaly.inc.ongoing', dur),
    codes: parseSignature(it.rules_signature),
    peak: Number(it.peak_total) || 0,
    realertText: realerts === 0 ? null : realerts === 1 ? t('loginAnomaly.inc.realertOne') : t('loginAnomaly.inc.realertMany', realerts),
  }
}

function StatusBadge({ t, resolved }) {
  return (
    <ToneBadge tone={resolved ? 'success' : 'danger'} className="font-semibold" data-status={resolved ? 'resolved' : 'open'}>
      {resolved ? t('loginAnomaly.stResolved') : t('loginAnomaly.stActive')}
    </ToneBadge>
  )
}

function Rules({ t, codes }) {
  if (!codes.length) return <span className="text-muted-foreground">—</span>
  return (
    <span className="flex min-w-0 flex-wrap gap-1">
      {codes.map((c) => <RuleBadge key={c} t={t} code={c} />)}
    </span>
  )
}

function AuditButton({ t, it, view, windowMinutes, compact = false }) {
  return (
    <Button type="button" variant={compact ? 'ghost' : 'outline'} size={compact ? 'icon' : 'sm'} data-action="la-open-audit"
      aria-label={t('loginAnomaly.inc.auditRow', view.exact)} title={t('loginAnomaly.inc.auditRow', view.exact)}
      className={compact ? 'size-10 shrink-0' : 'h-10 sm:h-8 sm:pointer-coarse:h-10'}
      onClick={() => navigateTo('system', auditParams(it, windowMinutes))}>
      <ScrollText aria-hidden="true" />
      {!compact && <span className="hidden @2xl:inline">{t('loginAnomaly.inc.viewSignins')}</span>}
    </Button>
  )
}

function IncidentCards({ t, items, now, windowMinutes }) {
  return (
    <ul role="list" aria-label={t('loginAnomaly.inc.list')} data-slot="la-incident-list" className="flex list-none flex-col gap-2.5">
      {items.map((it) => {
        const v = rowView(it, t, now)
        return (
          <Item key={it.id} asChild variant="outline" size="sm" data-la="incident" data-status={it.resolved ? 'resolved' : 'open'}
            className="flex-col items-stretch gap-2 bg-card">
            <li>
              <div className="flex min-w-0 items-start gap-2">
                <div className="flex min-w-0 flex-1 flex-col gap-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <StatusBadge t={t} resolved={it.resolved} />
                    <time dateTime={it.opened_at} className="text-sm font-semibold">{v.rel}</time>
                  </div>
                  <p className="text-xs text-muted-foreground tabular-nums">{v.exact} · {v.durText}</p>
                </div>
                <AuditButton t={t} it={it} view={v} windowMinutes={windowMinutes} compact />
              </div>
              <Rules t={t} codes={v.codes} />
              <p className="text-xs text-muted-foreground tabular-nums">
                {t('loginAnomaly.inc.peak', v.peak)}{v.realertText && <> · {v.realertText}</>}
              </p>
            </li>
          </Item>
        )
      })}
    </ul>
  )
}

function IncidentTable({ t, items, now, windowMinutes }) {
  return (
    <div className="@container min-w-0 overflow-hidden rounded-lg border">
      <Table data-testid="la-incidents">
        <TableHeader className="bg-muted/50">
          <TableRow>
            <TableHead>{t('loginAnomaly.colOpened')}</TableHead>
            <TableHead>{t('loginAnomaly.colRules')}</TableHead>
            <TableHead className="hidden @xl:table-cell">{t('loginAnomaly.inc.colDuration')}</TableHead>
            <TableHead>{t('loginAnomaly.colStatus')}</TableHead>
            <TableHead className="w-0"><span className="sr-only">{t('loginAnomaly.inc.viewSignins')}</span></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.map((it) => {
            const v = rowView(it, t, now)
            return (
              <TableRow key={it.id} data-la="incident" data-status={it.resolved ? 'resolved' : 'open'}>
                <TableCell className="align-top whitespace-normal">
                  <time dateTime={it.opened_at} title={v.exact} className="block font-medium">{v.rel}</time>
                  <span className="block text-xs whitespace-nowrap text-muted-foreground tabular-nums">{v.exact}</span>
                </TableCell>
                <TableCell className="align-top whitespace-normal @md:min-w-[10rem]">
                  <Rules t={t} codes={v.codes} />
                  <span className="mt-1 block text-xs text-muted-foreground tabular-nums">
                    {t('loginAnomaly.inc.peak', v.peak)}{v.realertText && <> · {v.realertText}</>}
                  </span>
                </TableCell>
                <TableCell className="hidden align-top text-sm tabular-nums @xl:table-cell">
                  {it.resolved ? v.dur : t('loginAnomaly.inc.ongoing', v.dur)}
                </TableCell>
                <TableCell className="align-top"><StatusBadge t={t} resolved={it.resolved} /></TableCell>
                <TableCell className="w-0 text-right align-top">
                  <AuditButton t={t} it={it} view={v} windowMinutes={windowMinutes} />
                </TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}

export default function LaIncidents({ state, windowMinutes }) {
  const t = useT()
  const isMobile = useIsMobile()
  const listRef = useRef(null)
  const narrow = useNarrow(listRef)
  const { items, total, error, loading, loadingMore, moreError, load, loadMore } = state
  const now = Date.now()
  const left = items ? Math.max(0, total - items.length) : 0

  return (
    <SettingsSection
      title={<span className="inline-flex items-center gap-2"><History size={16} aria-hidden="true" />{t('loginAnomaly.recentTitle')}</span>}
      description={t('loginAnomaly.inc.desc')} contentClassName="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" variant="outline" size="sm" className="h-10 flex-1 sm:h-8 sm:flex-none sm:pointer-coarse:h-10"
          onClick={load} disabled={loading} aria-busy={loading || undefined} data-action="la-incidents-refresh">
          {loading && items ? <Spinner size={14} inline decorative /> : <RefreshCw aria-hidden="true" />}
          {t('loginAnomaly.inc.refresh')}
        </Button>
        <Button type="button" variant="outline" size="sm" className="h-10 flex-1 sm:h-8 sm:flex-none sm:pointer-coarse:h-10"
          onClick={() => navigateTo('system', { a_eventType: 'LOGIN_FAILED' })} data-action="la-audit-log">
          <ScrollText aria-hidden="true" />{t('loginAnomaly.inc.audit')}
        </Button>
      </div>

      {error && (
        <AlertBanner tone="danger" role="alert" title={t('loginAnomaly.inc.errorTitle')} className="mb-0"
          actions={(
            <Button type="button" variant="outline" size="sm" className="h-10 sm:h-8 sm:pointer-coarse:h-10" onClick={load} disabled={loading}>
              <RefreshCw aria-hidden="true" />{t('loginAnomaly.retry')}
            </Button>
          )}>
          {String(error)}
        </AlertBanner>
      )}

      {!items && !error && <LoadingBlock label={t('settings.loading')} className="justify-start px-0 py-4" />}

      {items && items.length === 0 && (
        <StatusBlock tone="success" icon={ShieldCheck} title={t('loginAnomaly.inc.emptyTitle')}
          description={t('loginAnomaly.inc.emptyDesc')} className="rounded-xl border border-dashed py-8 md:py-8" />
      )}

      <div ref={listRef} className="min-w-0 empty:hidden">
        {items && items.length > 0 && (isMobile || narrow
          ? <IncidentCards t={t} items={items} now={now} windowMinutes={windowMinutes} />
          : <IncidentTable t={t} items={items} now={now} windowMinutes={windowMinutes} />)}
      </div>

      {moreError && <AlertBanner tone="danger" className="mb-0">{String(moreError)}</AlertBanner>}

      {items && left > 0 && (
        <Button type="button" variant="outline" className="h-10 w-full sm:h-9 sm:w-auto sm:self-center sm:pointer-coarse:h-10"
          onClick={() => loadMore(items)} disabled={loadingMore} aria-busy={loadingMore || undefined} data-action="la-incidents-more">
          {loadingMore && <Spinner size={14} inline decorative />}
          {t('loginAnomaly.inc.more', left)}
        </Button>
      )}
    </SettingsSection>
  )
}
