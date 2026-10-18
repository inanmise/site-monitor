import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { CircleAlert, ExternalLink, ImageOff, X } from 'lucide-react'
import { api } from '../../../api/client'
import { useLanguage, useT } from '../../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { useToast } from '../../ui/Toast.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { ImagesBlock } from '../ReportImages.jsx'
import { periodLabel } from '../TechErrorParts.jsx'
import { resolvePlaceholders } from '../techErrorModel.js'
import { CaptureSessionChip, CaptureStopNotice, useCaptureScope } from './CaptureParts.jsx'
import { KIND_IMAGES, KIND_REPORT, onImagesChanged } from './imageSync.js'
import { openInMainWindow } from './panelWindow.js'
import { useFileDropGuard } from './useFileDropGuard.js'
import PanelIssueImages, { panelIssueSection } from './PanelIssueImages.jsx'

/** Pencereye dönülünce (odak / görünür) rapor en çok bu sıklıkla yeniden okunur — şablon uygulandı mı, silindi mi. */
export const PANEL_FOCUS_RELOAD_MS = 5000
/** Sessiz okuma sürerken bu panelde görsel değişirse okuma en çok bu kadar kez yeniden yapılır. */
export const PANEL_SYNC_RETRY_MAX = 3

/**
 * GÖRSEL PANELİ (2026-10-18, kullanıcı isteği: "ayrı bir panelde görsellerin olduğu sayfa açık olacak, site monitör
 * üzerinden diğer sayfaya geçip ekran görüntüsünü alabilmeliyim"): `?tab=channelreports&te_view=images&te_rep=<id>`
 * adresinde App'in kenar çubuksuz kabuğunda açılır (`window.open`, 520 × 900). Raporun YALNIZ görsel bölümleri ve
 * yuvaları — yükleme, sürükle-bırak, yapıştırma, ekrandan alma (kendi paylaşım oturumu), değiştirme / silme, büyük
 * görünüm, rapor toplamı ve sınırlar — rapor düzenleyicisindeki yuvanın AYNISI (ImagesBlock). Görsel alan anomali
 * bölümlerinin KAYITLI konularının görselleri de şablon sırasında, konu başlığının altında (`PanelIssueImages`).
 *
 * <p>Yetki aynıdır: salt okunur görüntüleyen yalnız görselleri görür; başka takımın / silinmiş rapor modülün olağan
 * "bulunamadı / yetki yok" durumunu gösterir. Ana pencereyle eşitleme `imageSync` üzerinden (iki yönlü); ana pencere
 * şablonu uygular ya da raporu silerse panel raporu yeniden okur (pencereye dönünce de).
 */
export default function ImagesPanelPage({ reportId }) {
  const t = useT()
  const toast = useToast()
  const { lang: uiLang } = useLanguage()
  const [state, setState] = useState({ loading: true, error: null, status: null })
  const [data, setData] = useState(null)
  const [images, setImages] = useState([])
  const seq = useRef(0)
  // Bu paneldeki görsel değişikliği sayacı (inceleme 2026-10-18): öteki pencerenin iletisiyle başlayan okuma, sürerken
  // burada yapılan yüklemeyi ezmesin — okuma yeniden yapılır (yeni okuma ikisini de görür)
  const localGen = useRef(0)
  const tRef = useRef(t)
  tRef.current = t
  useCaptureScope()
  // Yuvanın dışına bırakılan dosya paneli o dosyaya götürmesin (panel ve ekran paylaşımı kaybolurdu)
  useFileDropGuard(true)

  const load = useCallback(async ({ quiet = false, attempt = 0 } = {}) => {
    const my = ++seq.current
    const gen = localGen.current
    if (!quiet) setState((s) => ({ ...s, loading: true }))
    try {
      const res = await api.techErrors.report(reportId)
      if (my !== seq.current) return
      if (localGen.current !== gen && res?.success) {
        if (attempt < PANEL_SYNC_RETRY_MAX) load({ quiet: true, attempt: attempt + 1 })
        return
      }
      if (res?.success && res.data?.report) {
        setData(res.data)
        setImages(res.data.images || [])
        setState({ loading: false, error: null, status: null })
      } else {
        setState({ loading: false, error: res?.error || tRef.current('te.loadError'), status: res?.status ?? null })
        // Rapor silindi / yetki kalktı: eski görseller gösterilmez
        if (res?.status === 404 || res?.status === 403) setData(null)
      }
    } catch (e) {
      if (my !== seq.current) return
      setState({ loading: false, error: e?.message || tRef.current('te.loadError'), status: 0 })
    }
  }, [reportId])
  useEffect(() => { load() }, [load])

  // Öteki pencerenin değişikliği (görsel ya da rapor) → sessiz yeniden okuma
  useEffect(() => onImagesChanged(reportId, () => load({ quiet: true }), [KIND_IMAGES, KIND_REPORT]), [reportId, load])

  // Pencereye dönülünce (ana pencerede şablon uygulandıysa yuva listesi değişmiş olabilir)
  useEffect(() => {
    let last = Date.now()
    const h = () => {
      if (document.visibilityState !== 'visible' || Date.now() - last < PANEL_FOCUS_RELOAD_MS) return
      last = Date.now()
      load({ quiet: true })
    }
    window.addEventListener('focus', h)
    document.addEventListener('visibilitychange', h)
    return () => {
      window.removeEventListener('focus', h)
      document.removeEventListener('visibilitychange', h)
    }
  }, [load])

  const onImagesChange = useCallback((fn) => { localGen.current++; setImages(fn) }, [])
  const tpl = data?.template
  const report = data?.report
  const channel = data?.channel
  const resolve = useCallback((p) => resolvePlaceholders(p, channel?.name, report?.year, report?.month, tpl?.lang),
    [channel?.name, report?.year, report?.month, tpl?.lang])
  // Şablon sırasıyla: görsel bölümleri + görsel alan anomali bölümleri (konu görselleri konunun altında — 2026-10-18)
  const sections = useMemo(() => (tpl?.sections || []).filter((s) => s.kind === 'images' || panelIssueSection(s, tpl?.lang)), [tpl])
  const firstImagesId = sections.find((s) => s.kind === 'images')?.id
  const readOnly = !data?.can_edit
  const canClose = (() => { try { return !!window.opener } catch { return false } })()

  if (state.loading && !data) {
    return (
      <div className="flex min-h-dvh flex-col gap-3 bg-background p-4" role="status" aria-live="polite" data-slot="te-panel-loading">
        <span className="sr-only">{t('te.loading')}</span>
        <Skeleton className="h-16 w-full rounded-xl motion-reduce:animate-none" />
        <Skeleton className="h-64 w-full rounded-xl motion-reduce:animate-none" />
      </div>
    )
  }
  if (!data) {
    const gone = state.status === 404 || state.status === 403
    return (
      <div className="flex min-h-dvh flex-col bg-background p-4" data-slot="te-panel" data-state={gone ? 'gone' : 'error'}>
        <StatusBlock tone={gone ? 'neutral' : 'danger'} icon={CircleAlert} title={gone ? t('te.reportGoneTitle') : t('te.loadErrorTitle')}
          description={state.error}
          actions={gone ? null : <Button type="button" variant="outline" className="pointer-coarse:min-h-10" onClick={() => load()}>{t('te.retry')}</Button>} />
      </div>
    )
  }

  return (
    <div className="flex min-h-dvh flex-col bg-background" data-slot="te-panel" data-report={reportId} data-state="ready">
      {/* Dar pencere (520 px): başlık tam genişlikte, eylemler altında — başlık düğmelerin yanında sıkışmaz. Başlık akışta
          kalır (yapışkan değil: dar pencerede yuvalara yer kalsın); paylaşım çipi aşağıda yapışkan */}
      <header className="flex flex-col gap-2 border-b bg-background px-4 py-3" data-slot="te-panel-header">
        <div className="flex flex-col gap-2">
          <div className="flex min-w-0 flex-col gap-0.5">
            <p className="text-xs font-medium text-muted-foreground">{t('te.cap.panelKicker')}</p>
            <h1 className="text-base font-semibold leading-snug [overflow-wrap:anywhere]" data-slot="te-panel-title">{resolve(tpl.title)}</h1>
            <p className="text-sm text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-panel-period">
              {[periodLabel(report, t), channel?.name].filter(Boolean).join(' · ')}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" variant="outline" size="sm" className="pointer-coarse:min-h-10" data-slot="te-panel-main"
              onClick={async () => {
                // Ana pencerede açık pencere / kaydedilmemiş iş varsa rapor açılmaz (inceleme 2026-10-18): ne yapılacağı söylenir
                const how = await openInMainWindow(reportId, channel?.id)
                if (how === 'blocked') toast.info(t('te.cap.panelMainBusy'), 10000)
              }}>
              <ExternalLink aria-hidden="true" /> {t('te.cap.panelOpenMain')}
            </Button>
            {canClose && (
              <Button type="button" variant="ghost" size="sm" className="pointer-coarse:min-h-10" data-slot="te-panel-close"
                onClick={() => { try { window.close() } catch { /* yok */ } }}>
                <X aria-hidden="true" /> {t('te.cap.panelClose')}
              </Button>
            )}
          </div>
        </div>
      </header>
      <main className="flex flex-col gap-4 p-4" data-slot="te-panel-body">
        {/* Paylaşım açıkken kaydırırken de görünür: hangi yüzey paylaşılıyor, tek dokunuşla Durdur */}
        <CaptureSessionChip className="sticky top-2 z-10 bg-background shadow-sm" />
        <CaptureStopNotice />
        {state.error && (
          <AlertBanner tone="danger" title={t('te.reloadFailed')}
            actions={<Button type="button" size="sm" variant="outline" onClick={() => load({ quiet: true })}>{t('te.retry')}</Button>}>
            {state.error}
          </AlertBanner>
        )}
        {readOnly && <AlertBanner tone="info" title={t('te.readOnlyTitle')}>{t('te.cap.panelReadOnly')}</AlertBanner>}
        {sections.length === 0 ? (
          <StatusBlock tone="neutral" icon={ImageOff} title={t('te.cap.panelNoImages')} description={t('te.cap.panelNoImagesBody')} />
        ) : sections.map((s) => (s.kind === 'images' ? (
          <ImagesBlock key={s.id} section={s} reportId={reportId} images={images} resolve={resolve} uiLang={uiLang}
            showTotals={s.id === firstImagesId} readOnly={readOnly} onImagesChange={onImagesChange} inPanel />
        ) : (
          <PanelIssueImages key={s.id} section={s} issues={data.values?.issues?.[s.id] || undefined} reportId={reportId}
            images={images} uiLang={uiLang} readOnly={readOnly} onImagesChange={onImagesChange} resolve={resolve} />
        )))}
      </main>
    </div>
  )
}
