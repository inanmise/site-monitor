import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AlertTriangle, Lock, RadioTower, RefreshCw } from 'lucide-react'
import { api } from '../../../api/client'
import { useLanguage, useT } from '../../../i18n/index.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { LoadingBlock, Spinner } from '../../ui/Progress.jsx'
import { useFormErrors } from '../../../hooks/useFormErrors.js'
import { useVisibleInterval } from '../../../hooks/useVisibleInterval.js'
import { useUnsavedWork } from '../../../utils/unsavedWork.js'
import { navigateTo } from '../../../utils/navigate.js'
import { SETTINGS_STACK, SettingsHeader } from '../SettingsControls.jsx'
import { STATUS_REFRESH_MS, changedSettings, crossFieldErrors, feedUrl, isDirty, limitsOf, toForm, validateChanges } from './alarmFeedModel.js'
import { TOUCH } from './AlarmFeedParts.jsx'
import { CallersCard, StatusCard } from './AlarmFeedStatus.jsx'
import { BlocksCard } from './AlarmFeedBlocks.jsx'
import { SettingsCard } from './AlarmFeedForm.jsx'
import { PreviewCard } from './AlarmFeedPreview.jsx'
import { RequestsCard } from './AlarmFeedRequests.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

const has = (o, k) => Object.prototype.hasOwnProperty.call(o || {}, k)

/**
 * Ayarlar → Platform → Alarm akışı (2026-10-30).
 *
 * Merkezi izleme ekibi SiteMonitor alarmlarını (açık + son N dakikada çözülen) kendi kolon biçimlerinde herkese açık
 * `GET /api/public/alarm-feed` ucundan dakikada bir çeker. Bu sayfa: durum (açık / kapalı, tam adres + örnek çağrılar, son
 * çağrı, 24 saatlik sayılar, uyarılar) · ayarlar (kapsam / biçim / erişim / günlük — yalnız değişen alanlar kaydedilir,
 * alan hatası alanın altında, akışı AÇMAK onay ister) · önizleme ("şimdi ne dönerdi?") · çağıranlar · çağrı günlüğü
 * (süzgeçler, CSV, ayrıntı paneli + yanıtın birebir kopyası). YALNIZ global yönetici (sunucu her uçta 403; kabuk kapsamlı
 * müdüre "yalnız global yönetici" notunu çizer). Test kancaları `af-*`. Saf mantık `alarmFeedModel.js`.
 */
export default function AlarmFeedSettings() {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [state, setState] = useState({ status: 'loading', data: null, error: null, res: null })
  const [saved, setSaved] = useState(null)
  const [form, setForm] = useState(null)
  const [saving, setSaving] = useState(false)
  const [preview, setPreview] = useState({ status: 'idle', data: null, error: null, res: null })
  const fe = useFormErrors(null)
  const alive = useRef(true)
  const loadSeq = useRef(0)
  const previewSeq = useRef(0)
  const savedRef = useRef(null)
  const savingRef = useRef(false)
  savedRef.current = saved
  savingRef.current = saving

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])

  /**
   * Sunucu verisini uygular. `sent` (kayıt yanıtı): yalnız GÖNDERİLEN alanlar kayıtlı değeri alır — kayıt sürerken yazılan
   * değişiklik silinmez. `sent` yoksa (okuma / yenileme): kaydedilmemiş taslak korunur, temiz form sunucuya eşitlenir.
   */
  const applyData = useCallback((data, sent = null) => {
    const next = toForm(data.settings)
    const prev = savedRef.current
    setState({ status: 'ok', data, error: null, res: null })
    setSaved(next)
    setForm((cur) => {
      if (sent && cur) {
        const merged = { ...cur }
        for (const k of Object.keys(sent)) merged[k] = next[k]
        return merged
      }
      return cur && prev && isDirty(prev, cur) ? cur : next
    })
  }, [])

  const load = useCallback(async () => {
    if (savingRef.current) return undefined   // kayıt sürerken eski değeri "kayıtlı" diye getirmesin
    const my = ++loadSeq.current
    setState((s) => ({ ...s, status: s.data ? 'refreshing' : 'loading', error: null }))
    try {
      const res = await api.alarmFeed.get()
      if (!alive.current || my !== loadSeq.current) return undefined
      if (!res || res.success === false || !res.data) {
        setState((s) => ({ ...s, status: res?.status === 403 ? 'forbidden' : 'failed', error: res?.error || null, res: res || null }))
        return undefined
      }
      applyData(res.data)
    } catch (e) {
      if (!alive.current || my !== loadSeq.current) return undefined
      setState((s) => ({ ...s, status: 'failed', error: e?.message || null, res: e || null }))
    }
    return undefined
  }, [applyData])

  useEffect(() => { load() }, [load])
  // Durum kartı canlı kalsın (sekme gizliyken durur, yığılmaz); taslak korunur
  useVisibleInterval(() => (state.data ? load() : undefined), STATUS_REFRESH_MS, false)

  const data = state.data
  const limits = useMemo(() => limitsOf(data), [data])
  const dirty = !!saved && !!form && isDirty(saved, form)

  useUnsavedWork(dirty)   // başka pencereden gelen "raporu aç" isteği değişikliği sessizce bırakmasın
  useEffect(() => {
    if (!dirty) return undefined
    const warn = (e) => { e.preventDefault(); e.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  const setField = useCallback((key, value) => {
    setForm((f) => ({ ...f, [key]: value }))
    fe.clear(key)
  }, [fe])

  async function save() {
    if (saving || !saved || !form) return
    const body = changedSettings(saved, form)
    if (!Object.keys(body).length) return
    const map = {}
    for (const [k, err] of Object.entries(validateChanges(body, limits))) map[k] = t(err.key, ...err.params)
    for (const [k, err] of Object.entries(crossFieldErrors(body, form))) if (!map[k]) map[k] = t(err.key, ...err.params)
    if (fe.check(map)) return
    // Akışı AÇMAK: uç parolasız erişilebilir olur — açık onay
    if (body.enabled === true) {
      const { url } = feedUrl(data?.base_url, data?.endpoint)
      const ok = await showConfirm({
        title: t('af.confirmOn.title'), message: t('af.confirmOn.body', url),
        confirmText: t('af.confirmOn.confirm'), cancelText: t('af.cancel'), variant: 'warning',
      })
      if (!ok || !alive.current) return
    }
    setSaving(true)
    savingRef.current = true
    loadSeq.current++   // kayıttan ÖNCE başlamış bir okuma sonra gelirse eski değerleri "kayıtlı" göstermesin
    try {
      const res = await api.alarmFeed.save(body)
      if (!alive.current) return
      if (!res || res.success === false || !res.data) {
        const fieldMap = {}
        if (res?.field && has(body, res.field)) fieldMap[res.field] = res.error || t('af.saveProblem')
        if (res?.fields && typeof res.fields === 'object' && !Array.isArray(res.fields)) {
          for (const [k, v] of Object.entries(res.fields)) if (has(body, k) && !fieldMap[k]) fieldMap[k] = typeof v === 'string' && v ? v : (res.error || t('af.saveProblem'))
        }
        if (Object.keys(fieldMap).length) fe.check(fieldMap)
        else toast.error(res?.error || t('af.saveProblem'), { details: res || undefined })
        return
      }
      applyData(res.data, body)
      fe.reset()
      toast.success(res.message || t('af.saved'))
    } catch (e) {
      toast.error(e?.message || t('af.saveProblem'), { details: e })
    } finally {
      savingRef.current = false
      if (alive.current) setSaving(false)
    }
  }

  /** Engel listesi yanıtı (yenile / kaldır) — yalnız engel alanları güncellenir, form ve taslak dokunulmaz. */
  const applyBlocks = useCallback((d) => {
    if (!d || !alive.current) return
    loadSeq.current++   // bu yanıttan ÖNCE başlamış bir yenileme eski listeyi geri getirmesin
    setState((s) => (s.data ? {
      ...s,
      data: { ...s.data, blocks: d.blocks ?? null, active_blocks: d.active_blocks ?? null, blocks_error: d.blocks_error ?? null,
        active_truncated: d.active_truncated === true },
    } : s))
  }, [])

  function discard() {
    setForm(saved)
    fe.reset()
  }

  const loadPreview = useCallback(async () => {
    const my = ++previewSeq.current
    setPreview((p) => ({ ...p, status: 'loading', error: null }))
    try {
      const res = await api.alarmFeed.preview()
      if (!alive.current || my !== previewSeq.current) return
      if (!res || res.success === false || !res.data) {
        setPreview((p) => ({ ...p, status: 'failed', error: res?.error || null, res: res || null }))
        return
      }
      setPreview({ status: 'ok', data: res.data, error: null, res: null })
    } catch (e) {
      if (!alive.current || my !== previewSeq.current) return
      setPreview((p) => ({ ...p, status: 'failed', error: e?.message || null, res: e || null }))
    }
  }, [])

  /** Başka ayar bölümüne geçiş (Veri saklama, Genel): kaydedilmemiş değişiklik varsa önce sorar. */
  async function goSection(sec) {
    if (dirty) {
      const ok = await showConfirm({
        title: t('af.leave.title'), message: t('af.leave.body'), confirmText: t('af.leave.confirm'), cancelText: t('af.cancel'), variant: 'warning',
      })
      if (!ok) return
    }
    navigateTo('settings', { sec })
  }

  if (state.status === 'loading' && !data) {
    return <div data-slot="af-root" data-state="loading"><LoadingBlock label={t('af.loading')} /></div>
  }
  if (state.status === 'forbidden' && !data) {
    return (
      <div data-slot="af-root" data-state="forbidden" className={SETTINGS_STACK}>
        <StatusBlock tone="neutral" icon={Lock} title={t('af.forbidden.title')} description={t('af.forbidden.body')} />
      </div>
    )
  }
  if (!data || !form) {
    return (
      <div data-slot="af-root" data-state="failed" className={SETTINGS_STACK}>
        <StatusBlock tone="danger" role="alert" icon={AlertTriangle} title={t('af.load.title')}
          description={state.error ? String(state.error) : t('af.load.body')} details={state.res || undefined}
          actions={<Button type="button" variant="outline" onClick={load} className={TOUCH} data-slot="af-retry"><RefreshCw aria-hidden="true" /> {t('af.retry')}</Button>} />
      </div>
    )
  }

  return (
    <div data-slot="af-root" data-state={state.status} className={cn(SETTINGS_STACK, '@container')}>
      <SettingsHeader icon={RadioTower} title={t('af.title')} description={t('af.desc')}
        actions={(
          <Button type="button" variant="outline" onClick={load} disabled={state.status === 'refreshing' || saving}
            className={TOUCH} data-slot="af-refresh">
            {state.status === 'refreshing' ? <Spinner size={15} inline decorative /> : <RefreshCw aria-hidden="true" />} {t('af.refresh')}
          </Button>
        )} />

      {(state.status === 'failed' || state.status === 'forbidden') && (
        <div data-slot="af-stale">
          <AlertBanner tone="warning" className="mb-0" title={t('af.load.staleTitle')}>
            {state.error ? String(state.error) : t('af.load.body')}
          </AlertBanner>
        </div>
      )}

      <StatusCard data={data} lang={lang} now={Date.now()} onGoGeneral={() => goSection('general')} />

      <SettingsCard data={data} form={form} dirty={dirty} saving={saving} fe={fe} limits={limits} lang={lang}
        onChange={setField} onSave={save} onDiscard={discard} onGoRetention={() => goSection('retention')} />

      <PreviewCard preview={preview} onLoad={loadPreview} lang={lang} dirty={dirty} />

      {Object.prototype.hasOwnProperty.call(data, 'blocks') && (
        <BlocksCard data={data} lang={lang} onBlocks={applyBlocks} />
      )}

      <CallersCard callers={Object.prototype.hasOwnProperty.call(data, 'callers') ? data.callers : null} lang={lang} />

      <RequestsCard lang={lang} />
    </div>
  )
}
