import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowLeft, CircleAlert, Download, History, LayoutTemplate, MailWarning, RefreshCw, Save, Send, Trash2, Undo2,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { ImagesBlock } from './ReportImages.jsx'
import { IssuesBlock, MetricsBlock, TextBlock } from './ReportSections.jsx'
import { StatusBadge, fmtDateTime, periodLabel } from './TechErrorParts.jsx'
import { canonicalValues, normalizeValues, resolvePlaceholders, resolveTextPlaceholders, stableJson } from './techErrorModel.js'
import { storageOwner } from '../../utils/personalStorage.js'

// Kişisel kayıt: çıkışta ve kullanıcı değişince silinir (utils/personalStorage PERSONAL_PREFIXES)
export const BACKUP_PREFIX = 'sm.te.draft.'

function readBackup(id) {
  try {
    const raw = localStorage.getItem(BACKUP_PREFIX + id)
    if (!raw) return null
    const v = JSON.parse(raw)
    return v && typeof v === 'object' && v.values ? v : null
  } catch {
    return null
  }
}

function writeBackup(id, payload) {
  try { localStorage.setItem(BACKUP_PREFIX + id, JSON.stringify(payload)) } catch { /* depolama yok / dolu */ }
}

function dropBackup(id) {
  try { localStorage.removeItem(BACKUP_PREFIX + id) } catch { /* depolama yok */ }
}

const canonJson = (v) => stableJson(canonicalValues(v))
const hasAny = (o) => Object.values(o).some(Boolean)

/** Sürüklenen bir DOSYA mı (metin / bağlantı sürüklemesine dokunulmaz)? */
function isFileDrag(e) {
  const types = e?.dataTransfer?.types
  return !!types && Array.from(types).includes('Files')
}

/**
 * AYLIK RAPOR DÜZENLEYİCİSİ. Bölümler raporun KOPYALANMIŞ şablonundan çizilir (şablon sonradan değişse de bu rapor
 * değişmez; taslakta "şablonun son hâlini uygula" isteyerek alır). Değer kaydı sürüm denetimlidir (409 → uyarı +
 * "en son hâli yükle"); yazarken taslak tarayıcıda yedeklenir; kaydedilmemiş değişiklikle ayrılmak onay ister.
 * PDF ve gönderim KAYITLI içeriği kullanır — kaydedilmemiş değişiklik varsa önce kaydetmeyi önerir.
 *
 * <p>2. inceleme turu: kaydedilmemiş taslak varken kayıt arada başkasınca değiştiyse taban İLERLETİLMEZ (çakışma
 * uyarısı; sonraki kayıt 409 alır, başkasının değişikliği ezilmez); dil değişimi taslağı yeniden yüklemez; aynı
 * değerle yeniden yükleme girişleri yeniden kurmaz (odak / yarım yazım kaybolmaz); yedek zamanlayıcısı vazgeç / kayıtta
 * iptal edilir, taslak temizlenince bu sayfanın yazdığı yedek silinir, sayfadan çıkarken son saniye de yedeklenir.
 *
 * <p>İnceleme 2026-10-13: kayıt sürerken yazılan değişiklik korunur (yanıt yalnız gönderilen taslağın yerini alır),
 * sürüm bir ref'te tutulur (kaydı izleyen "şablonu uygula" eski sürümle 409 almaz), sunucunun alan hataları alan
 * düzenlenince kalkar ve kaydı kilitlemez, yeniden yükleme hatası görünür, sayfaya bırakılan dosya tarayıcıyı
 * rapordan uzaklaştırmaz.
 */
export default function ReportEditor({ reportId, onBack, onOpenTemplate }) {
  const t = useT()
  const { lang: uiLang } = useLanguage()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [state, setState] = useState({ loading: true, error: null, status: null })
  const [data, setData] = useState(null)
  const [draft, setDraft] = useState(null)
  const [base, setBase] = useState({ json: null, version: null })
  const [gen, setGen] = useState(0)                       // taslak bütünüyle değişince girişler yeniden kurulur
  const [images, setImages] = useState([])
  const [clientErrors, setClientErrors] = useState({})    // istemcinin alan denetimi (kaydı kilitler)
  const [serverErrors, setServerErrors] = useState({})    // sunucunun alan hataları (gösterilir; alan düzenlenince kalkar)
  const [conflict, setConflict] = useState(false)
  const [backup, setBackup] = useState(null)
  const [busy, setBusy] = useState(null)   // 'save' | 'pdf' | 'send' | 'refresh' | 'delete'
  const seq = useRef(0)
  const loadInFlight = useRef(false)
  const inFlightKeepDraft = useRef(true)
  const draftRef = useRef(draft)
  draftRef.current = draft
  const baseRef = useRef(base)
  // Dil değişimi taslağı yeniden yüklemesin: yükleyici t'ye bağlı değil (yalnız hata metni için okunur)
  const tRef = useRef(t)
  tRef.current = t
  const backupTimer = useRef(null)
  const wroteBackup = useRef(false)
  const skipFlush = useRef(false)

  const applyBase = useCallback((b) => { baseRef.current = b; setBase(b) }, [])

  /** Sunucudan okunan raporu uygular. `keepDraft`: kaydedilmemiş taslak korunur (gönderim sonrası / yeniden dene). */
  const applyLoaded = useCallback((resData, keepDraft) => {
    const values = normalizeValues(resData.values)
    const json = canonJson(values)
    const cur = draftRef.current
    const wasClean = cur == null || canonJson(cur) === baseRef.current.json
    const versionMoved = baseRef.current.version != null && resData.report.version !== baseRef.current.version
    setData(resData)
    setImages(resData.images || [])
    if (keepDraft && !wasClean && versionMoved && json === canonJson(cur)) {
      // Sunucu tam olarak bu taslağı tutuyor (ör. kayıt yanıtı ağda kayboldu): çakışma değil — taban benimsenir
      applyBase({ json, version: resData.report.version })
      setConflict(false)
      return
    }
    if (keepDraft && !wasClean && versionMoved) {
      // Elde kaydedilmemiş değişiklik var VE kayıt arada başkası tarafından değişti: taban İLERLETİLMEZ — sonraki
      // kayıt 409 alır, başkasının değişikliği sessizce ezilmez; kullanıcıya çakışma söylenir
      setConflict(true)
      return
    }
    applyBase({ json, version: resData.report.version })
    if (!keepDraft || (wasClean && canonJson(cur) !== json)) {
      setDraft(values)
      setGen((g) => g + 1)
      setClientErrors({})
      setServerErrors({})
    }
    if (!keepDraft) {
      const b = readBackup(reportId)
      const same = b && canonJson(normalizeValues(b.values)) === json
      setBackup(b && !same ? b : null)
      if (same) dropBackup(reportId)
    }
    // Kaydedilmemiş taslak korunurken eldeki çakışma uyarısı kaldırılmaz
    if (!keepDraft || wasClean) setConflict(false)
  }, [reportId, applyBase])

  const load = useCallback(async ({ keepDraft = false } = {}) => {
    const my = ++seq.current
    loadInFlight.current = true
    inFlightKeepDraft.current = keepDraft
    setState((s) => ({ ...s, loading: true }))
    try {
      const res = await api.techErrors.report(reportId)
      if (my !== seq.current) return
      if (res?.success && res.data?.report) {
        applyLoaded(res.data, keepDraft)
        setState({ loading: false, error: null, status: null })
      } else {
        setState({ loading: false, error: res?.error || tRef.current('te.loadError'), status: res?.status ?? null })
      }
    } catch (e) {
      if (my !== seq.current) return
      setState({ loading: false, error: e?.message || tRef.current('te.loadError'), status: 0 })
    } finally {
      if (my === seq.current) loadInFlight.current = false
    }
  }, [reportId, applyLoaded])
  useEffect(() => { load() }, [load])

  const dirty = draft != null && base.json != null && canonJson(draft) !== base.json
  const readOnly = !data?.can_edit
  const hasClientErrors = hasAny(clientErrors)
  const fieldErrors = useMemo(() => ({ ...serverErrors, ...clientErrors }), [serverErrors, clientErrors])

  // Yazarken yerel yedek (1 sn sonra) — sekme kapanır / ağ düşerse emek kaybolmasın. Taslak yeniden temizlenince bu
  // sayfanın yazdığı yedek silinir (sonraki ziyarette boşuna "geri yükle" önerilmesin).
  useEffect(() => {
    if (readOnly) return undefined
    if (!dirty) {
      if (wroteBackup.current) { dropBackup(reportId); wroteBackup.current = false }
      return undefined
    }
    const h = setTimeout(() => {
      writeBackup(reportId, { version: base.version, values: draft, at: Date.now() })
      wroteBackup.current = true
    }, 1000)
    backupTimer.current = h
    return () => clearTimeout(h)
  }, [draft, dirty, readOnly, reportId, base.version])

  // Sayfadan çıkarken (uygulamada başka sekme, geri) son saniyenin düzenlemesi de yedeklenir
  const flushBackup = useRef(null)
  flushBackup.current = () => {
    // Çıkış (logout) kişisel kayıtları sildikten sonra yeniden yazılmaz: sahip işareti yoksa oturum kapanmıştır
    if (skipFlush.current || readOnly || !dirty || !draft || !storageOwner()) return
    writeBackup(reportId, { version: base.version, values: draft, at: Date.now() })
  }
  useEffect(() => () => flushBackup.current?.(), [])

  useEffect(() => {
    if (!dirty) return undefined
    const h = (e) => { e.preventDefault(); e.returnValue = '' }
    window.addEventListener('beforeunload', h)
    return () => window.removeEventListener('beforeunload', h)
  }, [dirty])

  // Grafik yuvasının DIŞINA bırakılan dosya tarayıcıyı görsele götürmesin (kaydedilmemiş taslak kaybolurdu)
  useEffect(() => {
    if (readOnly) return undefined
    const guard = (e) => { if (isFileDrag(e)) e.preventDefault() }
    window.addEventListener('dragover', guard)
    window.addEventListener('drop', guard)
    return () => {
      window.removeEventListener('dragover', guard)
      window.removeEventListener('drop', guard)
    }
  }, [readOnly])

  const tpl = data?.template
  const report = data?.report
  const channel = data?.channel
  const resolve = useCallback((p) => resolvePlaceholders(p, channel?.name, report?.year, report?.month, tpl?.lang),
    [channel?.name, report?.year, report?.month, tpl?.lang])
  const resolveText = useCallback((p) => resolveTextPlaceholders(p, channel?.name, report?.year, report?.month, tpl?.lang),
    [channel?.name, report?.year, report?.month, tpl?.lang])

  /** Sunucu hatalarından verilen öneklerle eşleşenleri kaldırır (+ belge geneli `values`). */
  const clearServer = (match) => setServerErrors((e) => {
    const keys = Object.keys(e).filter((k) => k === 'values' || match(k))
    if (!keys.length) return e
    const out = { ...e }
    for (const k of keys) delete out[k]
    return out
  })
  const setField = (id, v) => {
    setDraft((d) => ({ ...d, fields: { ...d.fields, [id]: v } }))
    clearServer((k) => k === `values.${id}`)
  }
  const setIssues = (sid, list) => {
    setDraft((d) => ({ ...d, issues: { ...d.issues, [sid]: list } }))
    clearServer((k) => k.startsWith(`issues.${sid}.`))
  }
  const setText = (sid, v) => {
    setDraft((d) => ({ ...d, texts: { ...d.texts, [sid]: v } }))
    clearServer((k) => k === `text.${sid}`)
  }
  const setFieldError = useCallback((key, msg) => setClientErrors((e) => {
    if ((e[key] || null) === (msg || null)) return e
    const out = { ...e }
    if (msg) out[key] = msg
    else delete out[key]
    return out
  }), [])

  /** Kaydeder; başarıyla (ya da değişiklik yoksa) true döner. */
  async function save({ quiet = false } = {}) {
    if (!draftRef.current || readOnly) return false
    if (hasClientErrors) { toast.error(t('te.fixFieldErrors')); return false }
    const sent = canonicalValues(draftRef.current)
    const sentJson = stableJson(sent)
    if (sentJson === baseRef.current.json) return true
    // Uçuştaki bir yükleme (ör. gönderim sonrası) bu kaydın sürümünü eskisiyle ezmesin: düşürülür ve kayıttan SONRA
    // yeniden yapılır (gönderim durumu / "yeniden gönder" düğmesi eski kalmasın)
    const droppedLoad = loadInFlight.current
    const droppedKeepDraft = inFlightKeepDraft.current
    seq.current++
    loadInFlight.current = false
    setBusy('save')
    try {
      const res = await api.techErrors.saveReport(reportId, sent, baseRef.current.version)
      if (res?.success && res.data) {
        const values = normalizeValues(res.data.values)
        applyBase({ json: canonJson(values), version: res.data.version })
        // Kayıt sürerken yazılan değişiklik korunur: yanıt yalnız GÖNDERİLEN taslağın yerini alır
        const untouched = canonJson(draftRef.current) === sentJson
        if (untouched) {
          setDraft(values)
          dropBackup(reportId)
          setBackup(null)
        }
        setServerErrors({})
        setState((s) => (s.loading ? { ...s, loading: false } : s))
        setData((d) => (d ? { ...d, report: { ...d.report, version: res.data.version, updated_at: res.data.updated_at,
          updated_by_name: res.data.updated_by_name ?? d.report.updated_by_name,
          status: d.report.status === 'sent' && res.data.changed ? 'changed' : d.report.status } } : d))
        if (!quiet) toast.success(t('te.saved'))
        return true
      }
      if (res?.code === 'CONCURRENT_MODIFICATION') {
        setConflict(true)
      } else if (res?.code === 'VALIDATION_FAILED' && res?.field) {
        setServerErrors((e) => ({ ...e, [res.field]: res.error }))
        toast.error(res.error)
      } else {
        toast.error(res?.error || t('te.saveError'), { details: res?.errorInfo })
      }
      return false
    } catch (e) {
      toast.error(e?.message || t('te.saveError'))
      return false
    } finally {
      setBusy(null)
      // Düşürülen yükleme AYNI kiple yeniden: "En son hâli yükle" tam yüklemeydi (taslak yerine sunucu + yedek önerisi)
      if (droppedLoad) load({ keepDraft: droppedKeepDraft })
    }
  }

  /** PDF / gönderim / şablon uygulama öncesi: geçersiz alan varsa durur; kaydedilmemiş değişiklik varsa kaydetmeyi önerir. */
  async function ensureSaved() {
    if (readOnly) return true
    if (hasClientErrors) { toast.error(t('te.fixFieldErrors')); return false }
    if (canonJson(draftRef.current) === baseRef.current.json) return true
    const ok = await showConfirm({ title: t('te.saveFirstTitle'), message: t('te.saveFirstBody'), variant: 'warning',
      confirmText: t('te.saveAndContinue'), cancelText: t('te.cancel') })
    if (!ok) return false
    return save({ quiet: true })
  }

  async function downloadPdf() {
    if (!(await ensureSaved())) return
    setBusy('pdf')
    try {
      const res = await api.techErrors.downloadPdf(reportId)
      if (!res?.success) toast.error(res?.error || t('te.pdfError'), { details: res?.errorInfo })
    } catch (e) {
      toast.error(e?.message || t('te.pdfError'))
    } finally {
      setBusy(null)
    }
  }

  async function send() {
    if (!(await ensureSaved())) return
    const ok = await showConfirm({
      title: t('te.sendTitle'),
      message: t('te.sendBody', channel?.team_name || '—', resolve(tpl?.title)),
      variant: 'info', confirmText: t('te.sendConfirm'), cancelText: t('te.cancel'),
    })
    if (!ok) return
    setBusy('send')
    try {
      const res = await api.techErrors.send(reportId)
      if (res?.success && res.data) {
        if (res.data.state === 'QUEUED') toast.info(t('te.sendQueued'))
        else toast.success(t('te.sendDone', (res.data.to || []).join(', ')))
      } else {
        toast.error(res?.error || t('te.sendError'), { details: res?.errorInfo })
      }
    } catch (e) {
      toast.error(e?.message || t('te.sendError'))
    } finally {
      setBusy(null)
      load({ keepDraft: true })
    }
  }

  async function applyTemplate() {
    if (!(await ensureSaved())) return
    setBusy('refresh')
    try {
      // Kayıttan SONRAKİ önizleme ve sürüm: kaybolacak sayılar kaydedilen değerlere göre değişmiş olabilir
      let fresh
      try {
        fresh = await api.techErrors.report(reportId)
      } catch (e) {
        toast.error(e?.message || t('te.loadError'))
        return
      }
      if (!fresh?.success || !fresh.data?.report) {
        toast.error(fresh?.error || t('te.loadError'), { details: fresh?.errorInfo })
        return
      }
      const clean = canonJson(draftRef.current) === baseRef.current.json
      if (fresh.data.report.version !== baseRef.current.version && !clean) { setConflict(true); return }
      // Temiz taslak: başkasının kaydı sorun değil — taze hâl alınır (sahte çakışma uyarısı yok)
      applyLoaded(fresh.data, true)
      const upd = fresh.data.template_update
      if (!upd?.differs) {
        toast.info(t('te.applyTemplateNone'))
        return
      }
      const loss = upd.lost_values || upd.lost_images
      const ok = await showConfirm({
        title: t('te.applyTemplateTitle'),
        message: loss ? t('te.applyTemplateLoss', upd.lost_values || 0, upd.lost_images || 0) : t('te.applyTemplateBody'),
        variant: loss ? 'danger' : 'warning',
        confirmText: t('te.applyTemplateConfirm'), cancelText: t('te.cancel'),
      })
      if (!ok) return
      // Onay sırasında yazılan değişiklik şablon uygulamasıyla kaybolmasın
      if (canonJson(draftRef.current) !== baseRef.current.json) { toast.error(t('te.fixUnsavedFirst')); return }
      const res = await api.techErrors.refreshTemplate(reportId, baseRef.current.version)
      if (res?.success) { toast.success(t('te.applyTemplateDone')); dropBackup(reportId); await load() }
      else if (res?.code === 'CONCURRENT_MODIFICATION') setConflict(true)
      else toast.error(res?.error || t('te.saveError'), { details: res?.errorInfo })
    } catch (e) {
      toast.error(e?.message || t('te.saveError'))
    } finally {
      setBusy(null)
    }
  }

  async function remove() {
    const ok = await showConfirm({
      title: t('te.deleteReportTitle'),
      message: t('te.deleteReportBody', periodLabel(report, t), channel?.name || ''),
      variant: 'danger', confirmText: t('te.deleteReportConfirm'), cancelText: t('te.cancel'),
    })
    if (!ok) return
    setBusy('delete')
    try {
      const res = await api.techErrors.deleteReport(reportId)
      if (res?.success) {
        skipFlush.current = true
        clearTimeout(backupTimer.current)
        dropBackup(reportId)
        toast.success(t('te.reportDeleted'))
        onBack(channel?.id)
      } else {
        toast.error(res?.error || t('te.deleteError'), { details: res?.errorInfo })
        if (res?.code === 'REPORT_SENT_OR_SENDING') load({ keepDraft: true })
      }
    } catch (e) {
      toast.error(e?.message || t('te.deleteError'))
    } finally {
      setBusy(null)
    }
  }

  async function leave(next) {
    if (dirty && !readOnly) {
      const ok = await showConfirm({ title: t('te.leaveTitle'), message: t('te.leaveBody'), variant: 'warning',
        confirmText: t('te.leaveConfirm'), cancelText: t('te.stay') })
      if (!ok) return
    }
    next()
  }

  function replaceDraft(values) {
    setDraft(values)
    setGen((g) => g + 1)
    setClientErrors({})
    setServerErrors({})
  }

  const sections = useMemo(() => tpl?.sections || [], [tpl])

  const back = (
    <Button type="button" variant="ghost" size="sm" data-slot="te-back"
      className="h-auto min-h-8 w-fit max-w-full justify-start whitespace-normal py-1.5 text-left pointer-coarse:min-h-10"
      onClick={() => leave(() => onBack(channel?.id))}>
      <ArrowLeft aria-hidden="true" />
      <span className="[overflow-wrap:anywhere]">{channel?.name ? t('te.backToChannel', channel.name) : t('te.backToChannels')}</span>
    </Button>
  )

  if (state.loading && !data) {
    return (
      <div className="flex flex-col gap-4" role="status" aria-live="polite">
        <span className="sr-only">{t('te.loading')}</span>
        <Skeleton className="h-28 w-full rounded-xl motion-reduce:animate-none" />
        <Skeleton className="h-72 w-full rounded-xl motion-reduce:animate-none" />
      </div>
    )
  }
  if (state.error && !data) {
    const gone = state.status === 404 || state.status === 403
    return (
      <div className="flex flex-col gap-4">
        <Button type="button" variant="ghost" size="sm" className="w-fit pointer-coarse:min-h-10" onClick={() => onBack(null)}>
          <ArrowLeft aria-hidden="true" /> {t('te.backToChannels')}
        </Button>
        <StatusBlock tone={gone ? 'neutral' : 'danger'} icon={CircleAlert} title={gone ? t('te.reportGoneTitle') : t('te.loadErrorTitle')}
          description={state.error}
          actions={gone ? null : <Button type="button" variant="outline" onClick={() => load()}>{t('te.retry')}</Button>} />
      </div>
    )
  }
  if (!data || !draft) return null

  const upd = data.template_update
  return (
    <div className="flex flex-col gap-4 pb-24" data-slot="te-report" data-report={reportId}>
      {back}
      <header className="flex flex-col gap-2">
        <h2 className="text-xl font-bold leading-tight [overflow-wrap:anywhere] sm:text-2xl" data-slot="te-report-title">
          {resolve(tpl.title)}
        </h2>
        <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <StatusBadge status={report.status} sendState={report.last_send_status} />
          <TeamBadge teamId={channel?.team_id} teamName={channel?.team_name} static wrap />
          <span>· {t('te.lastSaved', fmtDateTime(report.updated_at, uiLang), report.updated_by_name || '—')}</span>
          {report.last_sent_at && (
            <span className="[overflow-wrap:anywhere]">· {t('te.lastSent', fmtDateTime(report.last_sent_at, uiLang), report.last_sent_to || '—')}</span>
          )}
        </div>
      </header>

      {state.error && (
        <AlertBanner tone="danger" title={t('te.reloadFailed')}
          actions={<Button type="button" size="sm" variant="outline" onClick={() => load({ keepDraft: true })}>{t('te.retry')}</Button>}>
          {state.error}
        </AlertBanner>
      )}
      {readOnly && <AlertBanner tone="info" title={t('te.readOnlyTitle')}>{t('te.readOnlyBody')}</AlertBanner>}
      {conflict && (
        <AlertBanner tone="warning" title={t('te.conflictTitle')}
          actions={<Button type="button" size="sm" variant="outline" onClick={() => {
            // Değişiklik kaybolmasın: en son hâli yüklemeden önce taslak yedeğe yazılır; yükleme sonrası "geri yükle" önerilir.
            // Bu yedek bilerek bırakılır: taslak temizlenince silinmesin
            clearTimeout(backupTimer.current)
            writeBackup(reportId, { version: base.version, values: draft, at: Date.now() })
            wroteBackup.current = false
            load()
          }}>{t('te.loadLatest')}</Button>}>
          {t('te.conflictBody')}
        </AlertBanner>
      )}
      {backup && !readOnly && (
        <AlertBanner tone="info" icon={History} title={t('te.backupTitle')}
          actions={(
            <div className="flex flex-wrap gap-2">
              <Button type="button" size="sm" onClick={() => { replaceDraft(normalizeValues(backup.values)); setBackup(null) }}>{t('te.backupRestore')}</Button>
              <Button type="button" size="sm" variant="outline" onClick={() => { dropBackup(reportId); setBackup(null) }}>{t('te.backupDiscard')}</Button>
            </div>
          )}>
          {backup.version === base.version ? t('te.backupBody') : t('te.backupBodyStale')}
        </AlertBanner>
      )}
      {!readOnly && data.team_email_set === false && (
        <AlertBanner tone="warning" icon={MailWarning} title={t('te.noTeamEmailTitle')}>{t('te.noTeamEmailBody')}</AlertBanner>
      )}
      {report.status === 'changed' && !readOnly && (
        <AlertBanner tone="info" title={t('te.changedTitle')}>{t('te.changedBody')}</AlertBanner>
      )}
      {upd?.differs && !readOnly && (
        <AlertBanner tone="info" icon={LayoutTemplate} title={t('te.templateChangedTitle')}
          actions={<Button type="button" size="sm" variant="outline" disabled={busy != null} onClick={applyTemplate}>{t('te.applyTemplate')}</Button>}>
          {t('te.templateChangedBody')}
        </AlertBanner>
      )}

      {sections.map((s) => {
        const key = `${s.id}:${gen}`
        if (s.kind === 'metrics') {
          return <MetricsBlock key={key} section={s} values={draft} resolve={resolve} uiLang={uiLang} previous={data.previous}
            readOnly={readOnly} errors={fieldErrors} onField={setField} onFieldError={setFieldError} />
        }
        if (s.kind === 'issues') {
          return <IssuesBlock key={key} section={s} values={draft} resolve={resolve} uiLang={uiLang} readOnly={readOnly}
            errors={fieldErrors} onIssues={setIssues} onFieldError={setFieldError} />
        }
        if (s.kind === 'images') {
          return <ImagesBlock key={s.id} section={s} reportId={reportId} images={images} resolve={resolve} uiLang={uiLang}
            readOnly={readOnly} onImagesChange={(fn) => { setImages(fn); setData((d) => (d ? { ...d, report: { ...d.report,
              status: d.report.status === 'sent' ? 'changed' : d.report.status } } : d)) }} />
        }
        return <TextBlock key={key} section={s} values={draft} resolve={resolve} resolveText={resolveText} readOnly={readOnly}
          errors={fieldErrors} onText={setText} />
      })}

      <div className="sticky bottom-0 z-10 -mx-1 flex flex-wrap items-center justify-between gap-2 rounded-t-xl border-t bg-background/95 px-1 py-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] backdrop-blur"
        data-slot="te-report-actions">
        <p className="text-sm text-muted-foreground" aria-live="polite">
          {readOnly ? t('te.readOnlyShort') : dirty ? t('te.unsaved') : t('te.allSaved')}
        </p>
        <div className="flex flex-wrap items-center gap-2">
          {/* İkincil işlemler menüde: telefonda çubuk tek-iki satırda kalsın, içerik örtülmesin (2026-10-13 e2e) */}
          <KebabMenu variant="labeled" label={t('te.moreActions')} rowLabel={resolve(tpl.title)} items={[
            { label: t('te.openTemplate'), icon: <LayoutTemplate aria-hidden="true" />, onClick: () => leave(() => onOpenTemplate(channel?.id)), hidden: readOnly },
            { label: t('te.discard'), icon: <Undo2 aria-hidden="true" />, hidden: readOnly || !dirty || busy != null,
              onClick: () => { clearTimeout(backupTimer.current); replaceDraft(normalizeValues(JSON.parse(base.json))); dropBackup(reportId) } },
            { label: t('te.delete'), icon: <Trash2 aria-hidden="true" />, danger: true, hidden: !data.can_delete || busy != null, onClick: remove },
          ]} />
          <Button type="button" variant="outline" className="pointer-coarse:min-h-10" disabled={busy != null} onClick={downloadPdf}
            data-slot="te-pdf">
            {busy === 'pdf' ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : <Download aria-hidden="true" />}
            {t('te.downloadPdf')}
          </Button>
          {!readOnly && (
            <Button type="button" variant="outline" className="pointer-coarse:min-h-10" data-slot="te-send"
              disabled={busy != null || data.team_email_set === false || report.status === 'sending'} onClick={send}>
              {busy === 'send' ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : <Send aria-hidden="true" />}
              {report.last_sent_at ? t('te.resend') : t('te.send')}
            </Button>
          )}
          {!readOnly && (
            <Button type="button" className="pointer-coarse:min-h-10" disabled={!dirty || busy != null} onClick={() => save()} data-slot="te-save">
              <Save aria-hidden="true" /> {busy === 'save' ? t('te.saving') : t('te.save')}
            </Button>
          )}
        </div>
      </div>
    </div>
  )
}
