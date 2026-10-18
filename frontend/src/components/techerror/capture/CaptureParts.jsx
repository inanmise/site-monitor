import { useEffect } from 'react'
import { CircleStop, PanelRightOpen, ScreenShare } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { useT } from '../../../i18n/index.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { useIsMobile } from '../../../hooks/use-mobile.js'
import { canCaptureScreen, captureSession, useCaptureState } from './captureSession.js'
import { openImagesPanel } from './panelWindow.js'
import { shortcutKeys } from './SlotCapture.jsx'
import { friendlyTrackLabel, shortcutFamily } from './captureModel.js'

/** Görünüm bağlıyken paylaşım oturumunu tutar; son görünüm ayrılınca paylaşım durur (rapordan / panelden çıkış). */
export function useCaptureScope() {
  useEffect(() => captureSession.retain(), [])
}

/** Paylaşılan yüzeyin okunur adı: tarayıcının iz etiketi, yoksa yüzey türü (sekme / pencere / ekran). */
function sourceName(state, t) {
  const label = friendlyTrackLabel(state.label)
  if (label) return label
  if (state.surface === 'browser') return t('te.cap.surfaceTab')
  if (state.surface === 'window') return t('te.cap.surfaceWindow')
  if (state.surface === 'monitor') return t('te.cap.surfaceScreen')
  return t('te.cap.surfaceUnknown')
}

/**
 * "Paylaşım açık: <kaynak>" + "Durdur" (paylaşım açıkken her görsel bölümünün başında, panelde başlıkta). Seçici açıkken
 * "seçiliyor". Kapalıyken hiçbir şey çizmez.
 */
export function CaptureSessionChip({ className = '' }) {
  const t = useT()
  const st = useCaptureState()
  if (st.status === 'off') return null
  const live = st.status === 'live'
  return (
    <div className={cn('flex min-w-0 flex-wrap items-center gap-2 rounded-lg border bg-muted/40 px-3 py-2 text-sm', className)}
      data-slot="te-cap-chip" data-state={st.status}>
      <ScreenShare aria-hidden="true" className="size-4 shrink-0 text-primary" />
      <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">
        {live ? t('te.cap.chipLive', sourceName(st, t)) : t('te.cap.chipStarting')}
      </span>
      {live && (
        <Button type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10" data-slot="te-cap-stop"
          onClick={() => captureSession.stop('user')}>
          <CircleStop aria-hidden="true" /> {t('te.cap.stop')}
        </Button>
      )}
    </div>
  )
}

/**
 * Paylaşım kendiliğinden kapanınca (tarayıcıdan "Paylaşımı durdur", 10 dk kullanılmama) TEK bildirim — görünümde bir
 * kez bağlanır (rapordaki ilk görsel bölümü, panel). Kullanıcının kendi "Durdur"u, ayrılma ve çıkış söylenmez.
 */
export function CaptureStopNotice() {
  const t = useT()
  const toast = useToast()
  const st = useCaptureState()
  useEffect(() => {
    if (st.stopReason === 'idle') toast.info(t('te.cap.stoppedIdle'), 8000)
    else if (st.stopReason === 'ended') toast.info(t('te.cap.stoppedEnded'), 6000)
    if (st.stopReason) captureSession.clearStopReason()
  }, [st.stopReason, st.stoppedAt, t, toast])
  return null
}

/** Açılır pencere engellenince bildirimdeki "yeni sekmede aç" bağlantısı. */
function PanelBlocked({ url }) {
  const t = useT()
  return (
    <span className="flex flex-col gap-1" data-slot="te-panel-blocked">
      <span>{t('te.cap.panelBlocked')}</span>
      <Button asChild variant="link" size="sm" className="h-auto w-fit p-0">
        <a href={url} target="_blank" rel="noopener noreferrer" data-slot="te-panel-blocked-link">{t('te.cap.panelOpenTab')}</a>
      </Button>
    </span>
  )
}

/** "Görsel paneli": raporun görsellerini ayrı pencerede açar (masaüstü genişliği, düzenleyebilen). */
export function ImagesPanelButton({ reportId }) {
  const t = useT()
  const toast = useToast()
  return (
    <Button type="button" size="sm" variant="outline" className="w-fit pointer-coarse:min-h-10" data-slot="te-images-panel-open"
      onClick={() => {
        const r = openImagesPanel(reportId)
        if (r.blocked) toast.info(<PanelBlocked url={r.url} />, 12000)
      }}>
      <PanelRightOpen aria-hidden="true" /> {t('te.cap.panelButton')}
    </Button>
  )
}

/**
 * İlk görsel bölümünün BAŞLIK satırındaki "Görsel paneli" düğmesi: bölüm kapalıyken de görünür (bölümler kapalı başlar).
 * Yalnız düzenleyebilen kullanıcıya ve masaüstü genişliğinde (≥ 768 px); telefonda ve panelin içinde yok.
 */
export function ImagesPanelHeaderButton({ reportId, readOnly = false }) {
  const isMobile = useIsMobile()
  if (readOnly || isMobile) return null
  return <ImagesPanelButton reportId={reportId} />
}

/**
 * Görsel bölümünün ipucu satırları (ilk görsel bölümünde): işletim sistemine göre yapıştırma kısayolu, ekrandan alma ve
 * gizlilik notu, Görsel panelinin ne işe yaradığı. Telefonda kısayol / ekrandan alma / panel yoktur.
 */
export function CaptureHint({ readOnly = false, inPanel = false }) {
  const t = useT()
  const isMobile = useIsMobile()
  if (readOnly) return null
  const desktop = inPanel || !isMobile
  if (!desktop) return null
  const family = shortcutFamily()
  const keys = shortcutKeys(family)
  const snip = canCaptureScreen()
  return (
    <div className="flex flex-col gap-1.5" data-slot="te-cap-hint">
      <p className="text-sm text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-cap-hint-paste" data-family={family}>
        {family === 'other' ? t('te.cap.hintOther', keys.paste) : t('te.cap.hintShortcut', keys.snip, keys.paste)}
      </p>
      {snip && (
        <p className="text-sm text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-cap-hint-snip">{t('te.cap.hintSnip')}</p>
      )}
      {!inPanel && (
        <p className="text-sm text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-cap-hint-panel">{t('te.cap.panelHint')}</p>
      )}
    </div>
  )
}
