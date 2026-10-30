import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AlertTriangle, Cpu, RefreshCw, Server } from 'lucide-react'
import { api } from '../../../api/client'
import { useLanguage, useT } from '../../../i18n/index.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import { useFormErrors } from '../../../hooks/useFormErrors.js'
import { useVisibleInterval } from '../../../hooks/useVisibleInterval.js'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { LoadingBlock, Spinner } from '../../ui/Progress.jsx'
import { downloadCsv, toCsv } from '../../../utils/csvExport.js'
import { SETTINGS_STACK, SettingsHeader } from '../SettingsControls.jsx'
import {
  POLL_MS, changedFields, csvFileName, csvTable, idsDirty, isDirty, isOpenScan, normalizeIds, scanTargetCount, switchReadiness,
  toForm, validateChanges,
} from './k6EngineModel.js'
import { EngineActionDialog, EnginesCard, SettingsCard, TOUCH } from './K6EngineParts.jsx'
import { ResultsCard, ScanCard } from './K6EngineScan.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Ayarlar → Platform → k6 motoru (2026-10-29, k6 v0.49 → v2.3 geçişi, "iki ikili + gölge tarama" planı; 2026-10-30'da
 * tamamlandı — imajda tek ikili, sayfa gelecekteki sürüm yükseltmelerinin aracı).
 *
 * Bu sayfa geçişi YÖNETİR: aday ikiliyle bütün etkin senaryo izlemeleri önce bir kez koşulur (uyumluluk taraması — geçmiş
 * satırı / alarm / bildirim yok), sonuçlar son gerçek kontrolle karşılaştırılır; temizse "Adayı etkin yap" tek ayardır
 * (yeniden dağıtım yok) ve "Yedeğe geri dön" aynı yoldan geri alır. YALNIZ global yönetici (sunucu her uçta 403; Ayarlar
 * kabuğu kapsamlı müdüre "yalnız global yönetici" notunu çizer).
 *
 * Bölümler: giriş (bu pod) · motorlar (etkin / yedek / aday, yeniden yokla, geçiş / geri dönüş onayı + hazırlık özeti) ·
 * ayarlar (yedek / aday yolu, User-Agent, TLS kaçışları — yalnız değişen alanlar kaydedilir, alan hatası alanın altında) ·
 * uyumluluk taraması (tarama dışı izlemeler kendi kayıt çubuğuyla, başlat / ilerleme / iptal — 5 sn yoklama, sıra damgalı)
 * · sonuçlar (geçmişten tarama, fark kutucukları, süzgeçler, tablo ↔ kart, satır başına yeniden tarama, CSV).
 * Test kancaları `k6e-*`. Saf mantık `k6EngineModel.js`.
 */
export default function K6EngineSettings() {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [state, setState] = useState({ status: 'loading', data: null, error: null, res: null })
  const [saved, setSaved] = useState(null)
  const [form, setForm] = useState(null)
  const [exSaved, setExSaved] = useState([])
  const [exDraft, setExDraft] = useState([])
  const [scans, setScans] = useState({ status: 'loading', list: [], error: null, res: null })
  const [selectedId, setSelectedId] = useState(null)
  const [detail, setDetail] = useState({ status: 'idle', data: null, error: null, res: null })
  const [live, setLive] = useState(null)
  const [busy, setBusy] = useState(null)
  const [saving, setSaving] = useState(null)   // 'settings' | 'excluded'
  const [action, setAction] = useState(null)   // 'switch' | 'rollback'
  const fe = useFormErrors(null)
  const exFe = useFormErrors(null)

  const alive = useRef(true)
  const loadSeq = useRef(0)
  const scansSeq = useRef(0)
  const detailSeq = useRef(0)
  const pollSeq = useRef(0)
  const savedRef = useRef(null)
  const exSavedRef = useRef([])
  const liveRef = useRef(null)
  const selectedRef = useRef(null)
  savedRef.current = saved
  exSavedRef.current = exSaved
  liveRef.current = live
  selectedRef.current = selectedId

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])

  /** Sunucu verisini uygular — kaydedilmemiş taslaklar korunur (Yenile / yoklama formu ezmez). */
  const applyData = useCallback((data) => {
    const next = toForm(data.settings)
    const prev = savedRef.current
    setState({ status: 'ok', data, error: null, res: null })
    setSaved(next)
    setForm((cur) => (cur && prev && isDirty(prev, cur) ? cur : next))
    const ex = normalizeIds(data.settings?.excluded_ids)
    const prevEx = exSavedRef.current
    setExSaved(ex)
    setExDraft((cur) => (idsDirty(prevEx, cur) ? cur : ex))
    if (isOpenScan(data.scan)) setLive((cur) => (cur && cur.id === data.scan.id && isOpenScan(cur) ? cur : data.scan))
  }, [])

  const load = useCallback(async () => {
    const my = ++loadSeq.current
    setState((s) => ({ ...s, status: s.data ? 'refreshing' : 'loading', error: null }))
    try {
      const res = await api.admin.k6Engine()
      if (!alive.current || my !== loadSeq.current) return
      if (!res || res.success === false || !res.data) {
        setState((s) => ({ ...s, status: 'failed', error: res?.error || null, res: res || null }))
        return
      }
      applyData(res.data)
    } catch (e) {
      if (!alive.current || my !== loadSeq.current) return
      setState((s) => ({ ...s, status: 'failed', error: e?.message || null, res: e || null }))
    }
  }, [applyData])

  const loadScans = useCallback(async ({ select } = {}) => {
    const my = ++scansSeq.current
    setScans((s) => ({ ...s, status: s.list.length ? 'refreshing' : 'loading', error: null }))
    try {
      const res = await api.admin.k6Scans()
      if (!alive.current || my !== scansSeq.current) return
      if (!res || res.success === false || !res.data) {
        setScans((s) => ({ ...s, status: 'failed', error: res?.error || null, res: res || null }))
        return
      }
      const list = Array.isArray(res.data.scans) ? res.data.scans : []
      setScans({ status: 'ok', list, error: null, res: null })
      setSelectedId((cur) => {
        if (select != null && list.some((s) => s.id === select)) return select
        if (cur != null && list.some((s) => s.id === cur)) return cur
        return list[0]?.id ?? null
      })
      const open = list.find(isOpenScan)
      if (open) setLive((cur) => (cur && cur.id === open.id && isOpenScan(cur) ? cur : open))
    } catch (e) {
      if (!alive.current || my !== scansSeq.current) return
      setScans((s) => ({ ...s, status: 'failed', error: e?.message || null, res: e || null }))
    }
  }, [])

  const loadDetail = useCallback(async (id) => {
    const my = ++detailSeq.current
    if (id == null) { setDetail({ status: 'idle', data: null, error: null, res: null }); return }
    setDetail((d) => ({ ...d, status: 'loading', error: null, data: d.data?.id === id ? d.data : null }))
    try {
      const res = await api.admin.k6Scan(id)
      if (!alive.current || my !== detailSeq.current) return
      if (!res || res.success === false || !res.data) {
        setDetail((d) => ({ ...d, status: 'failed', error: res?.error || null, res: res || null }))
        return
      }
      setDetail({ status: 'ok', data: res.data, error: null, res: null })
    } catch (e) {
      if (!alive.current || my !== detailSeq.current) return
      setDetail((d) => ({ ...d, status: 'failed', error: e?.message || null, res: e || null }))
    }
  }, [])

  useEffect(() => { load(); loadScans() }, [load, loadScans])
  useEffect(() => { loadDetail(selectedId) }, [selectedId, loadDetail])

  /**
   * Açık taramanın yoklaması (5 sn, sekme gizliyken durur, yığılmaz). Geç gelen yanıt düşer (sıra damgası + tarama kimliği).
   * Tarama bitince sayfa verisi ve geçmiş yeniden okunur, biten tarama seçilir.
   */
  const poll = useCallback(async () => {
    const id = liveRef.current?.id
    if (id == null) return undefined
    const my = ++pollSeq.current
    try {
      const res = await api.admin.k6Scan(id)
      if (!alive.current || my !== pollSeq.current || liveRef.current?.id !== id) return undefined
      if (!res || res.success === false || !res.data) return undefined   // bir sonraki turda yeniden denenir
      const d = res.data
      if (selectedRef.current === id) {
        detailSeq.current++
        setDetail({ status: 'ok', data: d, error: null, res: null })
      }
      if (isOpenScan(d)) {
        setLive(d)
      } else {
        setLive(null)
        load()
        loadScans({ select: id })
      }
    } catch { /* ağ hatası: bir sonraki turda yeniden denenir */ }
    return undefined
  }, [load, loadScans])
  useVisibleInterval(poll, isOpenScan(live) ? POLL_MS : null, false)

  const data = state.data
  const dirty = !!saved && !!form && isDirty(saved, form)
  const exDirty = idsDirty(exSaved, exDraft)
  const scanRunning = isOpenScan(live) || (scans.list || []).some(isOpenScan) || isOpenScan(data?.scan)
  const readiness = useMemo(() => switchReadiness({
    scans: scans.status === 'ok' || scans.status === 'refreshing' ? scans.list : null,
    scansKnown: scans.status === 'ok' || scans.status === 'refreshing',
    latest: live || data?.scan || null,
    candidateVersion: data?.engines?.candidate?.version || null,
    otherPods: data && Object.prototype.hasOwnProperty.call(data, 'other_version_pods') ? data.other_version_pods : undefined,
  }), [scans, live, data])
  const targetCount = useMemo(() => scanTargetCount(data?.monitors, exSaved), [data, exSaved])

  function setField(key, value) {
    setForm((f) => ({ ...f, [key]: value }))
    fe.clear(key)
  }

  /** Ortak kayıt: yalnız gönderilen alanlar; yanıt bütün veriyi döner — öteki taslak korunur. */
  async function saveBody(body, which) {
    setSaving(which)
    loadSeq.current++   // kayıttan ÖNCE başlamış bir okuma kayıttan sonra gelirse eski değerleri "kayıtlı" göstermesin
    const errs = which === 'excluded' ? exFe : fe
    try {
      const res = await api.admin.saveK6EngineSettings(body)
      if (!alive.current) return false
      if (!res || res.success === false || !res.data) {
        const field = res?.field
        if (field && Object.prototype.hasOwnProperty.call(body, field)) errs.check({ [field]: res.error || t('k6e.saveFailedText') })
        else toast.error(res?.error || t('k6e.saveFailedText'), { details: res || undefined })
        return false
      }
      applyData(res.data)
      // Kayıt sürerken yazılan değişiklik silinmez: yalnız gönderilen alanlar kayıtlı değeri alır
      if (which === 'settings') {
        const next = toForm(res.data.settings)
        setForm((cur) => {
          const merged = { ...(cur || next) }
          for (const k of Object.keys(body)) merged[k] = next[k]
          return merged
        })
      } else {
        setExDraft(normalizeIds(res.data.settings?.excluded_ids))
      }
      toast.success(res.message || t('k6e.saved'))
      return true
    } catch (e) {
      toast.error(e?.message || t('k6e.saveFailedText'), { details: e })
      return false
    } finally {
      if (alive.current) setSaving(null)
    }
  }

  async function saveSettings() {
    if (saving || !saved || !form) return
    const body = changedFields(saved, form)
    if (!Object.keys(body).length) return
    const map = {}
    for (const [k, err] of Object.entries(validateChanges(body))) map[k] = t(err.key, ...err.params)
    if (fe.check(map)) return
    await saveBody(body, 'settings')
  }

  async function saveExcluded() {
    if (saving || !exDirty) return
    exFe.reset()
    await saveBody({ excluded_ids: normalizeIds(exDraft) }, 'excluded')
  }

  async function run(kind, call, okToast) {
    if (busy) return null
    setBusy(kind)
    try {
      const res = await call()
      if (!alive.current) return null
      if (!res || res.success === false) {
        toast.error(res?.error || t('k6e.actionFailedText'), { details: res || undefined })
        return res || null
      }
      if (okToast) toast.success(res.message || okToast)
      return res
    } catch (e) {
      toast.error(e?.message || t('k6e.actionFailedText'), { details: e })
      return null
    } finally {
      if (alive.current) setBusy(null)
    }
  }

  async function reprobe() {
    const res = await run('reprobe', () => api.admin.k6EngineReprobe(), t('k6e.reprobed'))
    if (res?.success && res.data) applyData(res.data)
  }

  async function confirmAction() {
    const kind = action
    if (!kind) return
    const res = await run(kind, () => (kind === 'switch' ? api.admin.k6EngineSwitch() : api.admin.k6EngineRollback()),
      t(kind === 'switch' ? 'k6e.switched' : 'k6e.rolledBack'))
    if (!alive.current) return
    setAction(null)
    if (res?.success && res.data) applyData(res.data)
    load()
    loadScans()
  }

  async function startScan() {
    if (busy || scanRunning) return
    const cand = data?.engines?.candidate
    const ok = await showConfirm({
      title: t('k6e.scan.confirmTitle'),
      message: t('k6e.scan.confirmBody', targetCount, cand?.version || t('k6e.versionUnknownShort')),
      confirmText: t('k6e.scan.start'), cancelText: t('k6e.cancel'), variant: 'warning',
    })
    if (!ok) return
    await launch({})
  }

  async function launch(body) {
    const kind = body?.monitor_ids ? `rescan:${body.monitor_ids[0]}` : 'start'
    const res = await run(kind, () => api.admin.k6ScanStart(body), null)
    if (!alive.current || !res) return
    if (res.success === false) {
      if (res.code === 'K6_SCAN_RUNNING') loadScans({ select: res.scan_id ?? undefined })
      return
    }
    const s = res.data
    if (s?.id != null) {
      if (isOpenScan(s)) setLive(s)
      toast.success(body?.monitor_ids ? t('k6e.scan.rescanStarted') : t('k6e.scan.started', s.id))
      loadScans({ select: s.id })
    }
  }

  function rescan(r) {
    if (busy || scanRunning) return
    launch({ monitor_ids: [r.monitor_id] })
  }

  async function cancelScan() {
    const s = live
    if (!s || busy) return
    const ok = await showConfirm({
      title: t('k6e.scan.cancelTitle'), message: t('k6e.scan.cancelBody', s.id),
      confirmText: t('k6e.scan.cancel'), cancelText: t('k6e.keepRunning'), variant: 'warning',
    })
    if (!ok) return
    const res = await run('cancel', () => api.admin.k6ScanCancel(s.id), t('k6e.scan.cancelRequested'))
    if (!alive.current || !res) return
    if (res.success && res.data?.id != null) {
      if (isOpenScan(res.data)) setLive(res.data)
      else { setLive(null); load(); loadScans({ select: res.data.id }) }
    } else if (res.code === 'K6_SCAN_NOT_RUNNING') {
      setLive(null)
      load()
      loadScans({ select: s.id })
    }
  }

  function exportCsv(rows, scan) {
    const { header, rows: body } = csvTable(rows, t, lang)
    downloadCsv(csvFileName(scan?.id), toCsv(header, body))
  }

  if (state.status === 'loading' && !data) {
    return <div data-slot="k6e-root" data-state="loading"><LoadingBlock label={t('k6e.loading')} /></div>
  }
  if (!data || !form) {
    return (
      <div data-slot="k6e-root" data-state="failed" className={SETTINGS_STACK}>
        <StatusBlock tone="danger" role="alert" icon={AlertTriangle} title={t('k6e.loadFailedTitle')}
          description={state.error ? String(state.error) : t('k6e.loadFailedBody')} details={state.res || undefined}
          actions={<Button type="button" variant="outline" onClick={load} className={TOUCH} data-slot="k6e-retry"><RefreshCw aria-hidden="true" /> {t('k6e.retry')}</Button>} />
      </div>
    )
  }

  return (
    <div data-slot="k6e-root" data-state={state.status} className={cn(SETTINGS_STACK, '@container')}>
      <SettingsHeader icon={Cpu} title={t('k6e.title')} description={t('k6e.desc')}
        meta={(
          <Badge variant="outline" className="h-auto gap-1 font-normal whitespace-normal text-muted-foreground [overflow-wrap:anywhere]" data-slot="k6e-pod">
            <Server aria-hidden="true" /> {t('k6e.thisPod', data.pod || '—')}
          </Badge>
        )}
        actions={(
          <Button type="button" variant="outline" onClick={() => { load(); loadScans() }} disabled={state.status === 'refreshing' || !!saving}
            className={TOUCH} data-slot="k6e-refresh">
            {state.status === 'refreshing' ? <Spinner size={15} inline decorative /> : <RefreshCw aria-hidden="true" />} {t('k6e.refresh')}
          </Button>
        )} />

      <div data-slot="k6e-intro">
        <AlertBanner tone="info" className="mb-0" title={t('k6e.introTitle')}>{t('k6e.introBody')}</AlertBanner>
      </div>

      {state.status === 'failed' && (
        <div data-slot="k6e-stale">
          <AlertBanner tone="warning" className="mb-0" title={t('k6e.refreshFailedTitle')}>
            {state.error ? String(state.error) : t('k6e.loadFailedBody')}
          </AlertBanner>
        </div>
      )}

      <EnginesCard data={data} lang={lang} busy={busy} scanRunning={scanRunning}
        onReprobe={reprobe} onSwitch={() => setAction('switch')} onRollback={() => setAction('rollback')} />

      <SettingsCard data={data} form={form} dirty={dirty} saving={saving === 'settings'} fe={fe} onChange={setField}
        onSave={saveSettings} onDiscard={() => { setForm(saved); fe.reset() }} />

      <ScanCard data={data} lang={lang} exDraft={exDraft} exDirty={exDirty} exSaving={saving === 'excluded'} exFe={exFe}
        onExChange={(ids) => { setExDraft(normalizeIds(ids)); exFe.clear('excluded_ids') }} onExSave={saveExcluded}
        onExDiscard={() => { setExDraft(exSaved); exFe.reset() }} targetCount={targetCount} live={live} busy={busy}
        onStart={startScan} onCancel={cancelScan} />

      <ResultsCard lang={lang} scans={scans} selectedId={selectedId} onSelect={setSelectedId} detail={detail}
        onRetryScans={() => loadScans()} onRetryDetail={() => loadDetail(selectedId)} scanRunning={scanRunning} busy={busy}
        onRescan={rescan} onExport={exportCsv} />

      <EngineActionDialog kind={action} data={data} readiness={readiness} busy={busy}
        onConfirm={confirmAction} onCancel={() => { if (!busy) setAction(null) }} />
    </div>
  )
}
