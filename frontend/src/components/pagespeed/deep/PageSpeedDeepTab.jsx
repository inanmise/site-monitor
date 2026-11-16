import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ArrowLeftRight, FileDown, FileSpreadsheet, RefreshCw } from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { failureKind } from '../pageSpeedInsightsModel.js'
import { useToast } from '../../ui/Toast.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import { ImageToolProvider, useImageToolStore } from '../../page/resourcescan/imagetool/ImageToolContext.jsx'
import { DEADLINE, SNAPSHOT_DEADLINE_MS, buildWaterfall, gradeSnapshot, imageScopeKey, withDeadline } from './pageSpeedDeepModel.js'
import PageSpeedGradeCard from './PageSpeedGradeCard.jsx'
import PageSpeedWaterfall from './PageSpeedWaterfall.jsx'
import { Button } from '@/components/shadcn/button'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'

const PageSpeedCompareDialog = lazy(() => import('./PageSpeedCompareDialog.jsx'))
const TOUCH = 'pointer-coarse:min-h-10'
const WRAP = '[overflow-wrap:anywhere]'

/**
 * Okuma hatasının türü — 1. sürümün kuralı (F12, `failureKind`): 405 ya da 404 + genel `NOT_FOUND` = eski pod ("bu sürümde
 * yok"); kodsuz 404 = izleme silinmiş / görünmüyor; diğerleri okunamadı.
 */
function failStatus(res) {
  const k = failureKind(res)
  return k === 'unsupported' ? 'unsupported' : k === 'gone' ? 'gone' : 'failed'
}

/**
 * Sayfa Hızı 2. sürüm (2026-11-15): "Şelale ve hız notu" sekmesi. Seçili ölçümün (son ölçüm ya da bir ihlal anı) anlık
 * görüntüsünü okur (`/waterfall` — ölçümün saklanan kaynakları + ana HTML fazları), üstte hız notu + öneriler + rapor,
 * altta şelale; iki ölçümü karşılaştırma penceresi. Görsel satırlarında görsel aracı menüsü (yalnız "Şimdi kontrol et"
 * yetkisiyle). Her istek süre sınırlı (istemcinin okuma zaman aşımı); geç gelen yanıt atılır (sıra numarası).
 */
export default function PageSpeedDeepTab({ monitor }) {
  const t = useT()
  const locale = useDateLocale()
  const toast = useToast()
  const id = monitor?.id
  const [checkId, setCheckId] = useState(null)          // null = son ölçüm
  // basis: okuma BAŞLARKEN izlemenin bilinen son ölçüm anı ("son ölçüm" seçiliyken "daha eski" kararı buna göre — inceleme
  // 2026-11-17: canlı liste yoklaması last_check'i ileri taşıyınca not yanlışlıkla "daha eski" diyordu)
  const [snap, setSnap] = useState({ status: 'loading', data: null, res: null, basis: null, followFailed: false })
  const [breaches, setBreaches] = useState([])
  const [compareOpen, setCompareOpen] = useState(false)
  const [exporting, setExporting] = useState(null)
  const seq = useRef(0)
  const alive = useRef(true)
  const breachFallback = useRef(false)                   // eski pod: şelale yanıtında breaches yok → kaynak listesi ucu BİR kez
  const cache = useRef(new Map())                        // kontrol kimliği → anlık görüntü (karşılaştırma yeniden okumasın)
  const monitorLast = monitor?.last_check || monitor?.checked_at || null
  const lastRef = useRef(monitorLast)
  lastRef.current = monitorLast
  const fetchImage = useCallback((scanId, url, signal) => api.monitoring.pageSpeedImage(id, url, { signal }), [id])
  const store = useImageToolStore(id, fetchImage)

  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])

  // İhlal anları — eski pod yolu (şelale yanıtı breaches taşımıyor): kaynak listesi ucunun breaches alanı, süre sınırlı
  const loadLegacyBreaches = useCallback(() => {
    withDeadline(api.monitoring.getPageSpeedResources(id, { size: 1 }), SNAPSHOT_DEADLINE_MS).then((res) => {
      if (alive.current && res !== DEADLINE && res?.success && Array.isArray(res.data?.breaches)) setBreaches(res.data.breaches)
    }).catch(() => {})
  }, [id])

  /**
   * Anlık görüntüyü okur. follow: "son ölçüm" seçiliyken izlemenin son ölçümü ilerledi (canlı yoklama / "Şimdi kontrol et") —
   * gösterilen veri okunurken yerinde kalır; okunamazsa da kalır ve "yeni ölçüm okunamadı" denir (eski ölçüm sessizce "son
   * ölçüm" diye sunulmaz).
   */
  const load = useCallback(async (target = checkId, { follow = false } = {}) => {
    const my = ++seq.current
    const basis = lastRef.current
    // Seçilen ölçüm değişti: eski anlık görüntü rapora / karşılaştırmaya gitmesin (inceleme 2026-11-15) — veri atılır
    setSnap((s) => (s.data && target != null && String(s.data.check_id) !== String(target)
      ? { status: 'loading', data: null, res: null, basis: null, followFailed: false } : { ...s, status: 'loading' }))
    let res = null
    try {
      res = await withDeadline(api.monitoring.getPageSpeedWaterfall(id, { checkId: target ?? undefined }), SNAPSHOT_DEADLINE_MS)
    } catch { res = null }
    if (res === DEADLINE) res = null
    if (my !== seq.current || !alive.current) return
    if (res?.success) {
      if (res.data?.check_id != null) cache.current.set(String(res.data.check_id), res.data)
      if (target == null) cache.current.set('latest', res.data)
      // İhlal anları şelale yanıtında (inceleme 2026-11-17: ağır kaynak listesi ucu ayrıca çağrılmaz); eski pod → bir kez eski uç
      if (Array.isArray(res.data?.breaches)) setBreaches(res.data.breaches)
      else if (!breachFallback.current) { breachFallback.current = true; loadLegacyBreaches() }
      setSnap({ status: 'ready', data: res.data, res, basis, followFailed: false })
    } else if (follow) {
      setSnap((s) => (s.data ? { ...s, status: 'ready', followFailed: true }
        : { status: failStatus(res), data: null, res, basis: null, followFailed: false }))
    } else {
      setSnap({ status: failStatus(res), data: null, res, basis: null, followFailed: false })
    }
  }, [id, checkId, loadLegacyBreaches])

  useEffect(() => { load(checkId) }, [load, checkId])

  // "Son ölçüm" seçiliyken izlemenin son ölçümü ilerledi (canlı liste yoklaması, "Şimdi kontrol et") → yeni ölçümü izle
  const followed = useRef(monitorLast)
  useEffect(() => {
    if (followed.current === monitorLast) return
    followed.current = monitorLast
    if (checkId == null && monitorLast) load(null, { follow: true })
  }, [monitorLast, checkId, load])

  const data = snap.data
  const wf = useMemo(() => buildWaterfall(data), [data])
  const grade = useMemo(() => (data ? gradeSnapshot(data) : null), [data])
  const latestId = checkId == null ? data?.check_id ?? null : null
  // "Son ölçüm" seçiliyken saklı kaynakları olan ölçüm izlemenin son ölçümünden ESKİ olabilir (son ölçümde sayfa açılamadı ya
  // da kaynak saklanmadı) — o zaman not "son ölçüm" diye sunulmaz, nötr tonda tarihiyle söylenir. Karşılaştırma OKUMA ANINDAKİ
  // son ölçümle (basis): yoklama son ölçümü ilerletince yukarıdaki izleme yeni ölçümü okur, arada not "daha eski" demez.
  const older = checkId == null && !!data?.check?.checked_at && !!snap.basis && String(snap.basis) !== String(data.check.checked_at)
  const options = useMemo(() => {
    const out = [{ value: '', label: t('pspd.deep.latest') }]
    for (const b of breaches) {
      if (b?.check_id == null) continue
      out.push({ value: String(b.check_id), label: t('pspd.deep.breachAt', formatDate(b.checked_at)) })
    }
    return out
  }, [breaches, t])

  // Karşılaştırma için anlık görüntü okuyucu (önbellekten ya da uçtan; süre sınırlı)
  const readSnapshot = useCallback(async (cid) => {
    const key = cid == null ? 'latest' : String(cid)
    if (cache.current.has(key)) return { ok: true, data: cache.current.get(key) }
    let res = null
    try {
      res = await withDeadline(api.monitoring.getPageSpeedWaterfall(id, { checkId: cid ?? undefined }), SNAPSHOT_DEADLINE_MS)
    } catch { res = null }
    if (res === DEADLINE) res = null
    if (res?.success) {
      if (res.data?.check_id != null) cache.current.set(String(res.data.check_id), res.data)
      return { ok: true, data: res.data }
    }
    return { ok: false, res }
  }, [id])

  const doExport = async (kind) => {
    if (!data || exporting || snap.status !== 'ready') return
    setExporting(kind)
    try {
      // Parça yüklemesi de süre sınırlı (kullanıcı kuralı: süresiz bekleme yok) — dolarsa "hazırlanamadı"
      const mod = await withDeadline(import('./pageSpeedReport.js'), SNAPSHOT_DEADLINE_MS)
      if (mod === DEADLINE) throw new Error('REPORT_CHUNK_TIMEOUT')
      const fn = kind === 'pdf' ? mod.exportPageSpeedPdf : mod.exportPageSpeedXlsx
      const ok = await fn({ monitor, data, grade, waterfall: wf, t, locale })
      if (ok === false) toast.error(t('pspd.rep.downloadFailed'))
      else toast.success(t(kind === 'pdf' ? 'pspd.rep.pdfDone' : 'pspd.rep.xlsxDone'))
    } catch {
      toast.error(t('pspd.rep.buildFailed'))
    } finally {
      setExporting(null)
    }
  }

  return (
    <div data-slot="pspd-deep" className="flex min-w-0 flex-col gap-3">
      <div className="flex min-w-0 flex-wrap items-end gap-2">
        {options.length > 1 && (
          <label className="flex min-w-0 flex-col gap-1 text-xs text-muted-foreground">
            <span>{t('pspd.deep.snapshot')}</span>
            <NativeSelect data-slot="pspd-deep-snapshot" value={checkId == null ? '' : String(checkId)}
              onChange={(e) => setCheckId(e.target.value === '' ? null : e.target.value)} className={cn('min-w-0 max-w-full', TOUCH)}>
              {options.map((o) => <NativeSelectOption key={o.value || 'latest'} value={o.value}>{o.label}</NativeSelectOption>)}
            </NativeSelect>
          </label>
        )}
        <div className="ms-auto flex flex-wrap gap-2">
          <Button type="button" variant="outline" size="sm" className={TOUCH} data-slot="pspd-deep-compare"
            disabled={options.length < 2 || snap.status !== 'ready'} title={options.length < 2 ? t('pspd.cmp.needTwo') : undefined}
            onClick={() => setCompareOpen(true)}>
            <ArrowLeftRight aria-hidden="true" />{t('pspd.cmp.open')}
          </Button>
          <Button type="button" variant="outline" size="sm" className={TOUCH} data-slot="pspd-deep-pdf"
            disabled={!data || !!exporting || snap.status !== 'ready'} onClick={() => doExport('pdf')}>
            <FileDown aria-hidden="true" />{exporting === 'pdf' ? t('pspd.rep.preparing') : t('pspd.rep.pdf')}
          </Button>
          <Button type="button" variant="outline" size="sm" className={TOUCH} data-slot="pspd-deep-xlsx"
            disabled={!data || !!exporting || snap.status !== 'ready'} onClick={() => doExport('xlsx')}>
            <FileSpreadsheet aria-hidden="true" />{exporting === 'xlsx' ? t('pspd.rep.preparing') : t('pspd.rep.xlsx')}
          </Button>
        </div>
      </div>
      {options.length < 2 && (
        <p data-slot="pspd-deep-compare-hint" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('pspd.cmp.needTwoHint')}</p>
      )}

      {snap.status === 'loading' && !data && <LoadingBlock label={t('pspd.deep.loading')} className="py-8" />}
      {snap.status === 'gone' && (
        <div data-slot="pspd-deep-gone"><AlertBanner tone="info" className="mb-0">{t('pspd.deep.gone')}</AlertBanner></div>
      )}
      {snap.status === 'unsupported' && (
        <div data-slot="pspd-deep-unsupported"><AlertBanner tone="info" className="mb-0">{t('pspd.deep.unsupported')}</AlertBanner></div>
      )}
      {snap.followFailed && data && (
        <div data-slot="pspd-deep-follow-failed">
          <AlertBanner tone="warning" className="mb-0"
            actions={<Button type="button" variant="outline" size="sm" className={TOUCH} onClick={() => load(null, { follow: true })}><RefreshCw aria-hidden="true" />{t('pspd.deep.retry')}</Button>}>
            {t('pspd.deep.followFailed')}
          </AlertBanner>
        </div>
      )}
      {snap.status === 'failed' && (
        <div data-slot="pspd-deep-failed">
          <AlertBanner tone="danger" className="mb-0"
            actions={<Button type="button" variant="outline" size="sm" className={TOUCH} onClick={() => load(checkId)}><RefreshCw aria-hidden="true" />{t('pspd.deep.retry')}</Button>}>
            {snap.res?.error || t('pspd.deep.failed')}
          </AlertBanner>
        </div>
      )}
      {data && (
        <ImageToolProvider store={store} monitorId={id} scanId={imageScopeKey(data.check_id)} canRun={monitor?.can_check === true}
          itemsKept monitorName={monitor?.name || monitor?.url || ''}>
          {wf.reason === 'none' ? (
            <p data-slot="pspd-deep-empty" className={cn('m-0 rounded-lg border border-dashed px-3 py-6 text-center text-sm text-muted-foreground', WRAP)}>
              {t('pspd.deep.empty')}
            </p>
          ) : (
            <>
              <PageSpeedGradeCard grade={grade} checkedAt={wf.checkedAt} isLatest={latestId != null} older={older} />
              <PageSpeedWaterfall wf={wf} data={data} />
            </>
          )}
        </ImageToolProvider>
      )}

      {compareOpen && (
        <Suspense fallback={null}>
          <PageSpeedCompareDialog options={options} initialB={checkId == null ? '' : String(checkId)} readSnapshot={readSnapshot}
            onClose={() => setCompareOpen(false)} />
        </Suspense>
      )}
    </div>
  )
}
