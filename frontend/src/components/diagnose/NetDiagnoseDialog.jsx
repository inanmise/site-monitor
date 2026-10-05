import { useEffect, useRef, useState } from 'react'
import {
  Stethoscope, History, ClipboardCopy, Download, Play, RefreshCw, X, Timer, Route, Waypoints, ShieldCheck, Gauge, ListChecks,
  ArrowLeft, Crosshair, ListFilter,
} from 'lucide-react'
import { api, formatDateSec, getRecentFailures } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { copyText } from '../../utils/copyText.js'
import ModalShell from '../ui/ModalShell.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Switch } from '@/components/shadcn/switch'
import { Label } from '@/components/shadcn/label'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { Kv, KvList } from '../http/diagnose/HttpDiagnoseParts.jsx'
import {
  buildReport, clampTimeout, downloadJson, failureKind, reportFileName, targetTag, targetText,
} from './netDiagnoseModel.js'
import NetDiagnoseResult from './NetDiagnoseResult.jsx'
import NetDiagnoseHistory from './NetDiagnoseHistory.jsx'
import { RateLimitStrip, RunningPanel } from './NetDiagnoseParts.jsx'

/**
 * Türün API uçları — çağrı anında çözülür (testlerdeki `api` mock'u işler).
 * Ping gövdesi `{ traceroute }`; Port ve DNS gövdesiz.
 */
const ENDPOINTS = {
  ping: {
    run: (id, body, opts) => api.monitoring.diagnosePing(id, body, opts),
    getRun: (id, runId) => api.monitoring.pingDiagnoseRun(id, runId),
    loadHistory: (id) => api.monitoring.pingDiagnoseHistory(id),
  },
  port: {
    run: (id, body, opts) => api.monitoring.diagnosePort(id, body, opts),
    getRun: (id, runId) => api.monitoring.portDiagnoseRun(id, runId),
    loadHistory: (id) => api.monitoring.portDiagnoseHistory(id),
  },
  dns: {
    run: (id, body, opts) => api.monitoring.diagnoseDns(id, body, opts),
    getRun: (id, runId) => api.monitoring.dnsDiagnoseRun(id, runId),
    loadHistory: (id) => api.monitoring.dnsDiagnoseHistory(id),
  },
}

/** Telefonda tam ekran (HTTP tanılama penceresiyle aynı). */
const PHONE_FULLSCREEN = 'max-sm:top-0 max-sm:left-0 max-sm:h-dvh max-sm:max-h-dvh max-sm:w-full max-sm:max-w-full max-sm:translate-x-0 max-sm:translate-y-0 max-sm:rounded-none max-sm:border-0 max-sm:px-4 max-sm:pt-4 max-sm:pb-[max(1rem,env(safe-area-inset-bottom))]'
/** Başlık düğmesi: masaüstünde ikon + metin, telefonda yalnız ikon (40 px dokunma hedefi). */
const HEAD_BTN = 'h-8 gap-1.5 px-2 text-muted-foreground hover:text-foreground pointer-coarse:h-10 pointer-coarse:min-w-10'

/**
 * PING / PORT / DNS UÇTAN UCA TANILAMA penceresi (2026-10-05, kullanıcı isteği: "tanılama ve teşhisi eksik olan … izlemeler
 * için tanılama ekleyelim; hata alındığında detaylıca ne hatası aldığını görelim"). HTTP tanılama penceresinin akışı:
 * açılışta ASLA koşmaz (tanılama hedefe gerçek paket/istek gönderir) — başlangıç ekranı neyin deneneceğini söyler (hedef,
 * izlemenin yolu, adımlar, süre sınırı; Ping'de isteğe bağlı traceroute), kullanıcı "Tanılamayı başlat"a basar. Koşu
 * sürerken geçen saniye + İptal; sonuç: hüküm → bulgular → (Port) yollar → adımlar → (DNS) çözücü/yetkili yanıtları →
 * izleme istemcisi → döküm → kaynak. 429 → geri sayımlı şerit; 403/404/ağ → yeniden dene şeridi. `initialRunId` (derin
 * bağlantı `pgdx` / `ptdx` / `dndx`) yalnız KAYITLI çalıştırmayı açar. Başlıkta Geçmiş, Raporu kopyala, JSON indir.
 *
 * <p>Pencere detay penceresinin İÇİNDE çizilir → iç içe kabuk (Escape yalnız bunu kapatır). Telefonda tam ekran.
 *
 * @param {'ping'|'port'|'dns'} type
 * @param {object}   monitor        izleme satırı (snake_case)
 * @param {number}   [initialRunId] derin bağlantıdan gelen kayıtlı çalıştırma
 * @param {Function} onClose
 * @param {Function} [onRunChange]  (runId|null) — gösterilen çalıştırma (sayfa URL'e yazar)
 */
export default function NetDiagnoseDialog({ type = 'ping', monitor, initialRunId = null, onClose, onRunChange }) {
  const t = useT()
  const toast = useToast()
  const ep = ENDPOINTS[type] || ENDPOINTS.ping
  const [traceroute, setTraceroute] = useState(false)
  const [view, setView] = useState('main')   // main | history
  const [st, setSt] = useState(() => (initialRunId ? { phase: 'loading', runId: initialRunId } : { phase: 'start' }))
  const [historyKey, setHistoryKey] = useState(0)
  const ctrlRef = useRef(null)
  const seq = useRef(0)
  const alive = useRef(true)
  const onRunChangeRef = useRef(onRunChange)
  onRunChangeRef.current = onRunChange

  useEffect(() => {
    alive.current = true
    if (initialRunId) openStored({ id: initialRunId })
    return () => { alive.current = false; ctrlRef.current?.abort() }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const running = st.phase === 'running'
  const data = st.phase === 'done' ? st.data : null
  const announce = (id) => onRunChangeRef.current?.(id ?? null)

  async function run() {
    const my = ++seq.current
    ctrlRef.current?.abort()
    const ctrl = new AbortController()
    ctrlRef.current = ctrl
    setView('main')
    setSt({ phase: 'running', since: Date.now(), traceroute: type === 'ping' && traceroute })
    announce(null)
    let res
    try {
      res = await ep.run(monitor.id, type === 'ping' ? { traceroute } : undefined, { signal: ctrl.signal })
    } catch (e) {
      res = { success: false, status: 0, error: e?.message || null }
    }
    if (!alive.current || my !== seq.current) return
    if (ctrl.signal.aborted) { setSt({ phase: 'cancelled' }); return }
    if (res?.success && res.data) {
      setSt({ phase: 'done', data: res.data, stored: false })
      setHistoryKey((k) => k + 1)
      announce(res.data.run_id)
      return
    }
    const kind = failureKind(res, getRecentFailures(), type)
    setSt(kind === 'rateLimited' ? { phase: 'rateLimited' } : { phase: 'error', kind, message: res?.error || null })
  }

  /** İptal: istek kesilir, ekran HEMEN iptal durumuna geçer; geç gelen yanıt (seq) yok sayılır. */
  function cancel() {
    seq.current++
    ctrlRef.current?.abort()
    setSt({ phase: 'cancelled' })
  }

  async function openStored(row) {
    const my = ++seq.current
    ctrlRef.current?.abort()
    setView('main')
    setSt({ phase: 'loading', runId: row.id })
    let res
    try {
      res = await ep.getRun(monitor.id, row.id)
    } catch (e) {
      res = { success: false, status: 0, error: e?.message || null }
    }
    if (!alive.current || my !== seq.current) return
    if (res?.success && res.data) {
      setSt({ phase: 'done', data: res.data, stored: true, row })
      announce(res.data.run_id ?? row.id)
      return
    }
    setSt({ phase: 'error', kind: failureKind(res, [], type), message: res?.error || null, storedRow: row })
    announce(null)
  }

  async function copyReport(format) {
    if (!data) return
    const text = buildReport({ data, t, formatDate: formatDateSec, format, type })
    if (await copyText(text)) toast.success(t('httpdx.reportCopied'))
    else toast.error(t('httpdx.copyFailed'))
  }

  function saveJson() {
    if (!data) return
    downloadJson(reportFileName(data, new Date(), type), data)
  }

  const retry = () => (st.storedRow ? openStored(st.storedRow) : run())
  const runHint = type === 'ping' && st.traceroute ? t('ndx.running.hintTraceroute') : t('ndx.running.hint')

  return (
    <ModalShell open onClose={onClose} icon={Stethoscope} size="xl" scrollBody closeLabel={t('httpdx.close')}
      className={PHONE_FULLSCREEN}
      title={<span data-slot="ndx-title" className="min-w-0 truncate">{t(`ndx.title.${type}`)}</span>}
      headerExtra={(
        <div data-slot="ndx-actions" className="ml-auto flex shrink-0 items-center gap-0.5">
          <Button type="button" variant={view === 'history' ? 'secondary' : 'ghost'} size="sm" className={HEAD_BTN}
            data-slot="ndx-history-btn" aria-pressed={view === 'history'} aria-label={t('httpdx.historyBtn')} title={t('httpdx.historyBtn')}
            disabled={running} onClick={() => setView((v) => (v === 'history' ? 'main' : 'history'))}>
            <History aria-hidden="true" /><span className="hidden sm:inline">{t('httpdx.historyBtn')}</span>
          </Button>
          <DropdownMenu modal={false}>
            <DropdownMenuTrigger asChild>
              <Button type="button" variant="ghost" size="sm" className={HEAD_BTN} disabled={!data}
                data-slot="ndx-copy-report" aria-label={t('httpdx.copyReport')} title={t('httpdx.copyReport')}>
                <ClipboardCopy aria-hidden="true" /><span className="hidden sm:inline">{t('httpdx.copyReport')}</span>
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" collisionPadding={8} className="z-(--z-menu)">
              <DropdownMenuItem onSelect={() => copyReport('markdown')}>{t('httpdx.copyMarkdown')}</DropdownMenuItem>
              <DropdownMenuItem onSelect={() => copyReport('text')}>{t('httpdx.copyText')}</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
          <Button type="button" variant="ghost" size="sm" className={HEAD_BTN} disabled={!data} onClick={saveJson}
            data-slot="ndx-download" aria-label={t('httpdx.downloadJson')} title={t('httpdx.downloadJson')}>
            <Download aria-hidden="true" /><span className="hidden sm:inline">{t('httpdx.downloadJson')}</span>
          </Button>
        </div>
      )}
      footer={<>
        <Button type="button" variant="secondary" className="pointer-coarse:h-10" onClick={onClose}>{t('httpdx.close')}</Button>
        {running ? (
          <Button type="button" variant="outline" data-slot="ndx-cancel" className="pointer-coarse:h-10" onClick={cancel}>
            <X aria-hidden="true" /> {t('httpdx.cancel')}
          </Button>
        ) : (
          <Button type="button" data-slot="ndx-run" className="pointer-coarse:h-10" onClick={run} disabled={st.phase === 'loading'}>
            {st.phase === 'start' ? <Play aria-hidden="true" /> : <RefreshCw aria-hidden="true" />}
            {st.phase === 'start' ? t('httpdx.run') : t('httpdx.rerun')}
          </Button>
        )}
      </>}>
      <div data-slot="ndx-body" data-phase={st.phase} data-view={view} data-type={type} className="flex min-w-0 flex-col gap-4">
        <TargetStrip type={type} monitor={data?.monitor || monitor} />

        {view === 'history' ? (
          <>
            <div>
              <Button type="button" variant="secondary" size="sm" className="pointer-coarse:h-10" onClick={() => setView('main')}>
                <ArrowLeft aria-hidden="true" /> {t('httpdx.historyBack')}
              </Button>
            </div>
            <NetDiagnoseHistory monitorId={monitor.id} load={ep.loadHistory} reloadKey={historyKey}
              activeRunId={data?.run_id ?? null} onOpen={openStored} />
          </>
        ) : (
          <>
            {st.phase === 'start' && (
              <StartPanel type={type} monitor={monitor} traceroute={traceroute} onTraceroute={setTraceroute} />
            )}
            {st.phase === 'running' && <RunningPanel since={st.since} hint={runHint} />}
            {st.phase === 'loading' && (
              <div data-slot="ndx-loading" role="status" className="flex items-center gap-2 text-sm">
                <Spinner size={16} inline decorative /> {t('httpdx.stored.loading')}
              </div>
            )}
            {st.phase === 'cancelled' && (
              <AlertBanner tone="info" className="mb-0" title={t('httpdx.cancelled.title')}
                actions={<Button type="button" size="sm" variant="secondary" className="pointer-coarse:h-10" onClick={() => setView('history')}>{t('httpdx.historyBtn')}</Button>}>
                <span data-slot="ndx-cancelled">{t('httpdx.cancelled.body')}</span>
              </AlertBanner>
            )}
            {st.phase === 'rateLimited' && <RateLimitStrip onRetry={run} />}
            {st.phase === 'error' && (
              <AlertBanner tone="danger" role="alert" className="mb-0" title={t('httpdx.err.title')}
                actions={<Button type="button" size="sm" variant="secondary" className="pointer-coarse:h-10" onClick={retry}>{t('httpdx.err.retry')}</Button>}>
                <span data-slot="ndx-error-msg" data-kind={st.kind}>{errorText(st, t)}</span>
              </AlertBanner>
            )}
            {st.phase === 'done' && (
              <NetDiagnoseResult key={`${st.stored ? 's' : 'l'}-${st.data?.run_id ?? 'x'}-${historyKey}`}
                type={type} data={st.data} stored={st.stored} row={st.row} />
            )}
          </>
        )}
      </div>
    </ModalShell>
  )
}

/** Hata şeridi metni: sunucu mesajı varsa o (Msg.t ile arayüz dilinde), yoksa türe göre yerel metin. */
function errorText(st, t) {
  if (st.message && st.kind !== 'network') return st.message
  if (st.kind === 'forbidden') return t('httpdx.err.forbidden')
  if (st.kind === 'notFound') return t('httpdx.err.notFound')
  if (st.kind === 'network') return t('httpdx.err.network')
  return st.message || t('httpdx.err.generic')
}

/** Hedef şeridi: ad + hedef + tür etiketi — her evrede görünür (kullanıcı neyi tanıladığını görsün). */
function TargetStrip({ type, monitor }) {
  const m = monitor || {}
  const target = targetText(type, m)
  const tag = targetTag(type, m)
  const showName = m.name && m.name !== target && m.name !== m.host && m.name !== m.domain
  return (
    <div data-slot="ndx-target" className="flex min-w-0 flex-col gap-1">
      {showName && <span className="min-w-0 text-sm font-semibold break-words">{m.name}</span>}
      <div className="flex min-w-0 flex-wrap items-center gap-1.5">
        {tag && <ToneBadge tone="info" className="font-mono">{tag}</ToneBadge>}
        <code data-slot="ndx-target-text" className="min-w-0 font-mono text-[13px] break-all">{target}</code>
      </div>
    </div>
  )
}

/** Başlangıç: neyin deneneceği (hedef, yol, adımlar, süre) + Ping'de traceroute anahtarı. Koşu düğmesi altlıkta. */
function StartPanel({ type, monitor, traceroute, onTraceroute }) {
  const t = useT()
  const m = monitor || {}
  const udp = type === 'port' && String(m.protocol || '').toUpperCase() === 'UDP'
  const via = type === 'port' && !udp ? m.proxy_effective : 'direct'
  const routeValue = type === 'port' && !udp
    ? (via === 'proxy' ? t('httpdx.start.ownRouteProxy') : via === 'direct' ? t('httpdx.start.ownRouteDirect') : t('httpdx.start.ownRouteUnknown'))
    : t('httpdx.start.ownRouteDirect')
  const routeNote = type === 'ping' ? t('ndx.start.routeNote.ping') : type === 'dns' ? t('ndx.start.routeNote.dns')
    : udp ? t('ndx.start.routeNote.udp') : t('ndx.start.routeNote.port')
  const expected = type === 'dns' ? expectedText(m.expected_value) : null
  return (
    <section data-slot="ndx-start" data-type={type} className="flex min-w-0 flex-col gap-3 rounded-xl border bg-card px-4 py-3.5">
      <h3 className="flex items-center gap-1.5 text-sm font-semibold"><ListChecks aria-hidden="true" className="size-4 text-primary" />{t('httpdx.start.title')}</h3>
      <KvList className="gap-2.5">
        <Kv label={<span className="inline-flex items-center gap-1"><Crosshair aria-hidden="true" className="size-3.5" />{t('ndx.start.target')}</span>} mono>
          <span data-slot="ndx-start-target">{targetText(type, m)}{type === 'dns' && m.record_type ? ` (${m.record_type})` : ''}</span>
        </Kv>
        <Kv label={<span className="inline-flex items-center gap-1">{via === 'proxy' ? <Waypoints aria-hidden="true" className="size-3.5" /> : <Route aria-hidden="true" className="size-3.5" />}{t('httpdx.start.ownRoute')}</span>}>
          <span data-slot="ndx-own-route" data-via={via || ''}>{routeValue}</span>
          <span className="block text-xs text-muted-foreground">{routeNote}</span>
        </Kv>
        {expected && (
          <Kv label={<span className="inline-flex items-center gap-1"><ListFilter aria-hidden="true" className="size-3.5" />{t('ndx.start.expected')}</span>} mono>
            {expected}
          </Kv>
        )}
        <Kv label={<span className="inline-flex items-center gap-1"><Timer aria-hidden="true" className="size-3.5" />{t('httpdx.start.timeout')}</span>}>
          {type === 'dns' ? t('ndx.start.timeoutDns') : t('ndx.start.timeoutValue', clampTimeout(m.timeout_ms))}
        </Kv>
        <Kv label={<span className="inline-flex items-center gap-1"><Gauge aria-hidden="true" className="size-3.5" />{t('httpdx.start.steps')}</span>}>
          {t(`ndx.start.steps.${type}`)}
        </Kv>
      </KvList>
      {type === 'ping' && (
        <div data-slot="ndx-traceroute" data-on={traceroute ? 'true' : 'false'}
          className="flex min-w-0 items-start gap-3 rounded-lg border bg-muted/30 px-3 py-2.5 pointer-coarse:min-h-10">
          <Switch id="ndx-traceroute" checked={traceroute} onCheckedChange={(v) => onTraceroute(!!v)}
            className="mt-0.5 pointer-coarse:mt-1" />
          <div className="flex min-w-0 flex-col gap-0.5">
            <Label htmlFor="ndx-traceroute" className="cursor-pointer text-sm font-medium pointer-coarse:min-h-6">{t('ndx.start.traceroute')}</Label>
            <span className="text-xs text-muted-foreground">{t('ndx.start.tracerouteHint')}</span>
          </div>
        </div>
      )}
      <p className="flex items-start gap-1.5 text-xs text-muted-foreground">
        <ShieldCheck aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
        <span className="min-w-0">{t('ndx.start.safe')}</span>
      </p>
    </section>
  )
}

/** DNS beklenen değer (dize, satır listesi ya da dizi) → tek satır. */
function expectedText(v) {
  if (v == null || v === '') return null
  if (Array.isArray(v)) return v.filter(Boolean).join(', ') || null
  return String(v).split(/\r?\n|,/).map((x) => x.trim()).filter(Boolean).join(', ') || null
}
