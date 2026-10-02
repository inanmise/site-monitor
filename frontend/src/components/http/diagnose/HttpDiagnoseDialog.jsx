import { useEffect, useRef, useState } from 'react'
import {
  Stethoscope, History, ClipboardCopy, Download, Play, RefreshCw, X, Timer, Route, Waypoints, ShieldCheck, Gauge, ListChecks,
  ArrowLeft,
} from 'lucide-react'
import { api, formatDateSec, getRecentFailures } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { copyText } from '../../../utils/copyText.js'
import ModalShell from '../../ui/ModalShell.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import { Spinner, ProgressBar } from '../../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Switch } from '@/components/shadcn/switch'
import { Label } from '@/components/shadcn/label'
import { Skeleton } from '@/components/shadcn/skeleton'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { cn } from '@/lib/utils'
import { buildReport, clampTimeout, downloadJson, failureKind, reportFileName } from './httpDiagnoseModel.js'
import HttpDiagnoseResult from './HttpDiagnoseResult.jsx'
import HttpDiagnoseHistory from './HttpDiagnoseHistory.jsx'
import { Kv, KvList } from './HttpDiagnoseParts.jsx'

/**
 * HTTP UÇTAN UCA TANILAMA penceresi (2026-10-02) — HTTP/Website izlemesinin detayından ("Uçtan uca tanıla") ya da
 * başarısız kontrolün "Hata tanısı" penceresinden ("Canlı tanılama çalıştır") açılır. Tembel yüklenir (HttpMonitorPage
 * `lazy`): giriş paketi büyümez.
 *
 * <p>Akış: açılışta ASLA koşmaz (tanılama hedefe gerçek istek gönderir) — başlangıç ekranı neyin deneneceğini söyler
 * (hedef, izlemenin yolu, öteki yolu da dene, süre sınırı), kullanıcı "Tanılamayı başlat"a basar. Koşu sürerken geçen
 * saniye + İptal (AbortController; sunucu yine de bitirebilir → sonuç Geçmiş'te). Sonuç: hüküm → yollar → ayrıntı →
 * kaynak (HttpDiagnoseResult). 429 → geri sayımlı şerit; 403/404/ağ → yeniden dene şeridi. Derin bağlantı `hdx=<no>`
 * KAYITLI çalıştırmayı açar (yine canlı koşu yok). Başlıkta Geçmiş, Raporu kopyala (Markdown / düz metin), JSON indir.
 *
 * <p>Pencere detay penceresinin İÇİNDE çizilir → ModalShell derinliği bir artar (iç içe kabuk, Escape yalnız bunu
 * kapatır). Telefonda tam ekran, geniş ekranda büyük (xl).
 *
 * @param {object}   monitor        HTTP izleme satırı (snake_case; id, url, method, timeout_ms, proxy_effective …)
 * @param {number}   [initialRunId] derin bağlantıdan gelen kayıtlı çalıştırma
 * @param {Function} onClose
 * @param {Function} [onRunChange]  (runId|null) — gösterilen çalıştırma değişti (sayfa URL'e `hdx` yazar)
 */
const PHONE_FULLSCREEN = 'max-sm:top-0 max-sm:left-0 max-sm:h-dvh max-sm:max-h-dvh max-sm:w-full max-sm:max-w-full max-sm:translate-x-0 max-sm:translate-y-0 max-sm:rounded-none max-sm:border-0 max-sm:px-4 max-sm:pt-4 max-sm:pb-[max(1rem,env(safe-area-inset-bottom))]'
/** Başlık düğmesi: masaüstünde ikon + metin, telefonda yalnız ikon (40 px dokunma hedefi). */
const HEAD_BTN = 'h-8 gap-1.5 px-2 text-muted-foreground hover:text-foreground pointer-coarse:h-10 pointer-coarse:min-w-10'

export default function HttpDiagnoseDialog({ monitor, initialRunId = null, onClose, onRunChange }) {
  const t = useT()
  const toast = useToast()
  const [compare, setCompare] = useState(true)
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
  const timeoutMs = clampTimeout(monitor?.timeout_ms)
  const announce = (id) => onRunChangeRef.current?.(id ?? null)

  async function run() {
    const my = ++seq.current
    ctrlRef.current?.abort()
    const ctrl = new AbortController()
    ctrlRef.current = ctrl
    setView('main')
    setSt({ phase: 'running', since: Date.now() })
    announce(null)
    let res
    try {
      res = await api.monitoring.diagnoseHttp(monitor.id, { compare }, { signal: ctrl.signal })
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
    const kind = failureKind(res, getRecentFailures())
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
      res = await api.monitoring.httpDiagnoseRun(monitor.id, row.id)
    } catch (e) {
      res = { success: false, status: 0, error: e?.message || null }
    }
    if (!alive.current || my !== seq.current) return
    if (res?.success && res.data) {
      setSt({ phase: 'done', data: res.data, stored: true, row })
      announce(res.data.run_id ?? row.id)
      return
    }
    setSt({ phase: 'error', kind: failureKind(res, []), message: res?.error || null, storedRow: row })
    announce(null)
  }

  async function copyReport(format) {
    if (!data) return
    const text = buildReport({ data, t, formatDate: formatDateSec, format })
    if (await copyText(text)) toast.success(t('httpdx.reportCopied'))
    else toast.error(t('httpdx.copyFailed'))
  }

  function saveJson() {
    if (!data) return
    downloadJson(reportFileName(data), data)
  }

  const retry = () => (st.storedRow ? openStored(st.storedRow) : run())

  return (
    <ModalShell open onClose={onClose} icon={Stethoscope} size="xl" scrollBody closeLabel={t('httpdx.close')}
      className={PHONE_FULLSCREEN}
      title={<span data-slot="httpdx-title" className="min-w-0 truncate">{t('httpdx.title')}</span>}
      headerExtra={(
        <div data-slot="httpdx-actions" className="ml-auto flex shrink-0 items-center gap-0.5">
          <Button type="button" variant={view === 'history' ? 'secondary' : 'ghost'} size="sm" className={HEAD_BTN}
            aria-pressed={view === 'history'} aria-label={t('httpdx.historyBtn')} title={t('httpdx.historyBtn')}
            disabled={running} onClick={() => setView((v) => (v === 'history' ? 'main' : 'history'))}>
            <History aria-hidden="true" /><span className="hidden sm:inline">{t('httpdx.historyBtn')}</span>
          </Button>
          <DropdownMenu modal={false}>
            <DropdownMenuTrigger asChild>
              <Button type="button" variant="ghost" size="sm" className={HEAD_BTN} disabled={!data}
                aria-label={t('httpdx.copyReport')} title={t('httpdx.copyReport')}>
                <ClipboardCopy aria-hidden="true" /><span className="hidden sm:inline">{t('httpdx.copyReport')}</span>
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" collisionPadding={8} className="z-(--z-menu)">
              <DropdownMenuItem onSelect={() => copyReport('markdown')}>{t('httpdx.copyMarkdown')}</DropdownMenuItem>
              <DropdownMenuItem onSelect={() => copyReport('text')}>{t('httpdx.copyText')}</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
          <Button type="button" variant="ghost" size="sm" className={HEAD_BTN} disabled={!data} onClick={saveJson}
            aria-label={t('httpdx.downloadJson')} title={t('httpdx.downloadJson')}>
            <Download aria-hidden="true" /><span className="hidden sm:inline">{t('httpdx.downloadJson')}</span>
          </Button>
        </div>
      )}
      footer={<>
        <Button type="button" variant="secondary" onClick={onClose}>{t('httpdx.close')}</Button>
        {running ? (
          <Button type="button" variant="outline" data-slot="httpdx-cancel" onClick={cancel}>
            <X aria-hidden="true" /> {t('httpdx.cancel')}
          </Button>
        ) : (
          <Button type="button" data-slot="httpdx-run" onClick={run} disabled={st.phase === 'loading'}>
            {st.phase === 'start' ? <Play aria-hidden="true" /> : <RefreshCw aria-hidden="true" />}
            {st.phase === 'start' ? t('httpdx.run') : t('httpdx.rerun')}
          </Button>
        )}
      </>}>
      <div data-slot="httpdx-body" data-phase={st.phase} data-view={view} className="flex min-w-0 flex-col gap-4">
        <TargetStrip monitor={data?.monitor || monitor} />

        {view === 'history' ? (
          <>
            <div>
              <Button type="button" variant="secondary" size="sm" className="pointer-coarse:h-10" onClick={() => setView('main')}>
                <ArrowLeft aria-hidden="true" /> {t('httpdx.historyBack')}
              </Button>
            </div>
            <HttpDiagnoseHistory monitorId={monitor.id} reloadKey={historyKey} activeRunId={data?.run_id ?? null} onOpen={openStored} />
          </>
        ) : (
          <>
            {st.phase === 'start' && (
              <StartPanel monitor={monitor} compare={compare} onCompare={setCompare} timeoutMs={timeoutMs} />
            )}
            {st.phase === 'running' && <RunningPanel since={st.since} timeoutMs={timeoutMs} />}
            {st.phase === 'loading' && (
              <div data-slot="httpdx-loading" role="status" className="flex items-center gap-2 text-sm">
                <Spinner size={16} inline decorative /> {t('httpdx.stored.loading')}
              </div>
            )}
            {st.phase === 'cancelled' && (
              <AlertBanner tone="info" className="mb-0" title={t('httpdx.cancelled.title')}
                actions={<Button type="button" size="sm" variant="secondary" onClick={() => setView('history')}>{t('httpdx.historyBtn')}</Button>}>
                <span data-slot="httpdx-cancelled">{t('httpdx.cancelled.body')}</span>
              </AlertBanner>
            )}
            {st.phase === 'rateLimited' && <RateLimitStrip onRetry={run} />}
            {st.phase === 'error' && (
              <AlertBanner tone="danger" role="alert" className="mb-0" title={t('httpdx.err.title')}
                actions={<Button type="button" size="sm" variant="secondary" onClick={retry}>{t('httpdx.err.retry')}</Button>}>
                <span data-slot="httpdx-error-msg" data-kind={st.kind}>{errorText(st, t)}</span>
              </AlertBanner>
            )}
            {st.phase === 'done' && (
              <HttpDiagnoseResult key={`${st.stored ? 's' : 'l'}-${st.data?.run_id ?? 'x'}-${historyKey}`}
                data={st.data} stored={st.stored} row={st.row} />
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

/** Hedef şeridi: ad + yöntem + URL + beklenen durum — her evrede görünür (kullanıcı neyi tanıladığını görsün). */
function TargetStrip({ monitor }) {
  const t = useT()
  const m = monitor || {}
  const showName = m.name && m.name !== m.url
  return (
    <div data-slot="httpdx-target" className="flex min-w-0 flex-col gap-1">
      {showName && <span className="min-w-0 text-sm font-semibold break-words">{m.name}</span>}
      <div className="flex min-w-0 flex-wrap items-center gap-1.5">
        <ToneBadge tone="info" className="font-mono">{m.method || 'GET'}</ToneBadge>
        <code data-slot="httpdx-url" className="min-w-0 font-mono text-[13px] break-all">{m.url}</code>
        {m.expected_status && <ToneBadge tone="muted">{t('httpdx.expected', m.expected_status)}</ToneBadge>}
      </div>
    </div>
  )
}

/** Başlangıç: neyin deneneceği + "öteki yolu da dene" anahtarı. Koşu düğmesi altlıkta (telefonda sabit). */
function StartPanel({ monitor, compare, onCompare, timeoutMs }) {
  const t = useT()
  const via = monitor?.proxy_effective
  const src = monitor?.proxy_source
  const ownRoute = via === 'proxy' ? t('httpdx.start.ownRouteProxy') : via === 'direct' ? t('httpdx.start.ownRouteDirect') : t('httpdx.start.ownRouteUnknown')
  const srcText = src && ['monitor', 'inventory', 'none'].includes(src) ? t(`httpdx.decision.source.${src}`) : null
  return (
    <section data-slot="httpdx-start" className="flex min-w-0 flex-col gap-3 rounded-xl border bg-card px-4 py-3.5">
      <h3 className="flex items-center gap-1.5 text-sm font-semibold"><ListChecks aria-hidden="true" className="size-4 text-primary" />{t('httpdx.start.title')}</h3>
      <KvList className="gap-2.5">
        <Kv label={<span className="inline-flex items-center gap-1">{via === 'proxy' ? <Waypoints aria-hidden="true" className="size-3.5" /> : <Route aria-hidden="true" className="size-3.5" />}{t('httpdx.start.ownRoute')}</span>}>
          <span data-slot="httpdx-own-route" data-via={via || ''}>{ownRoute}</span>
          {srcText && <span className="text-muted-foreground"> · {srcText}</span>}
          {monitor?.proxy_bypassed && <span className="text-muted-foreground"> · {t('httpdx.decision.bypassed')}</span>}
        </Kv>
        <Kv label={<span className="inline-flex items-center gap-1"><Timer aria-hidden="true" className="size-3.5" />{t('httpdx.start.timeout')}</span>}>
          {t('httpdx.start.timeoutValue', timeoutMs)}
        </Kv>
        <Kv label={<span className="inline-flex items-center gap-1"><Gauge aria-hidden="true" className="size-3.5" />{t('httpdx.start.steps')}</span>}>
          {t('httpdx.start.stepsValue')}
        </Kv>
      </KvList>
      <div className="flex min-w-0 items-start gap-3 rounded-lg border bg-muted/30 px-3 py-2.5">
        <Switch id="httpdx-compare" checked={compare} onCheckedChange={(v) => onCompare(!!v)} className="mt-0.5 pointer-coarse:mt-1" />
        <div className="flex min-w-0 flex-col gap-0.5">
          <Label htmlFor="httpdx-compare" className="cursor-pointer text-sm font-medium">{t('httpdx.start.compare')}</Label>
          <span className="text-xs text-muted-foreground">{t('httpdx.start.compareHint')}</span>
        </div>
      </div>
      <p className="flex items-start gap-1.5 text-xs text-muted-foreground">
        <ShieldCheck aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
        <span className="min-w-0">{t('httpdx.start.safe')}</span>
      </p>
    </section>
  )
}

/** Koşuyor: geçen saniye (yalnız sayaç her saniye çizilir) + belirsiz çubuk + sonuç iskeleti. */
function RunningPanel({ since, timeoutMs }) {
  const t = useT()
  return (
    <div data-slot="httpdx-running" className="flex min-w-0 flex-col gap-4">
      <div className="flex min-w-0 items-start gap-3 rounded-lg border bg-muted/40 px-3 py-3">
        <Spinner size={18} inline decorative className="mt-0.5" />
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span role="status" className="text-sm font-medium">{t('httpdx.running.title')}</span>
          <span className="text-xs text-muted-foreground">{t('httpdx.running.hint', timeoutMs)}</span>
          <span className="text-xs text-muted-foreground">{t('httpdx.running.cancelNote')}</span>
        </div>
        <Elapsed since={since} />
      </div>
      <ProgressBar decorative size="sm" />
      <div className="grid grid-cols-1 gap-3 md:grid-cols-2" aria-hidden="true">
        <Skeleton className="h-24 w-full md:col-span-2" />
        <Skeleton className="h-32 w-full" />
        <Skeleton className="h-32 w-full" />
      </div>
    </div>
  )
}

/** Geçen süre sayacı — saniyede bir YALNIZ kendisi çizilir (pencerenin geri kalanı değil). */
function Elapsed({ since }) {
  const t = useT()
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [])
  const s = Math.max(0, Math.floor((now - (since || now)) / 1000))
  return <span data-slot="httpdx-elapsed" data-seconds={s} className="shrink-0 text-sm font-semibold tabular-nums">{t('httpdx.running.elapsed', s)}</span>
}

/** Sunucu penceresi 60 sn (kullanıcı ve izleme başına dakikada 6) — Retry-After gelmez, tam pencere beklenir. */
const RATE_WINDOW_S = 60

/** 429 şeridi: dostça açıklama + geri sayım + süre dolunca "yeniden dene" (diagnostics/RateLimitBanner deseni). */
function RateLimitStrip({ onRetry }) {
  const t = useT()
  const [left, setLeft] = useState(RATE_WINDOW_S)
  useEffect(() => {
    const id = setInterval(() => setLeft((s) => (s <= 1 ? 0 : s - 1)), 1000)
    return () => clearInterval(id)
  }, [])
  const ready = left <= 0
  return (
    <div data-slot="httpdx-rate-limit">
      <AlertBanner tone="warning" icon={Timer} role="alert" className="mb-0" title={t('httpdx.rateLimited')}
        actions={ready ? <Button type="button" size="sm" onClick={onRetry}>{t('httpdx.err.retry')}</Button> : null}>
        <span aria-live="polite" className={cn(!ready && 'tabular-nums')}>{ready ? t('httpdx.rateLimitedReady') : t('httpdx.rateLimitedBody', left)}</span>
      </AlertBanner>
    </div>
  )
}

