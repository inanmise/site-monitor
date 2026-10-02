import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { clientPhase, dismiss, isDismissed, secondsUntil, windowText } from '../../utils/systemMaintenance.js'
import { MaintenanceAdminStrip, MaintenanceAnnounceStrip, MaintenanceEndedStrip, MaintenanceWarningStrip } from './MaintenanceStrips.jsx'
import MaintenanceCountdownDialog from './MaintenanceCountdownDialog.jsx'
import SysMaintExtendDialog from '../admin/sysmaint/SysMaintExtendDialog.jsx'

/**
 * Sistem Bakım Modu — UYGULAMA KATMANI (2026-10-02, kullanıcı kararı). App yalnız sunucu bloğunu (`maintenance`) ve saat
 * farkını (`offset`, `server_now`'dan) tutar; saniyelik tik BURADA yaşar — uygulamanın tamamı her saniye yeniden çizilmez
 * (izleme sayfalarındaki geri sayım dersinin aynısı). Aşamalar sunucu saatine göre ({@link clientPhase}):
 *
 *  • global yönetici OLMAYAN: duyuru şeridi (kapatılabilir) → uyarı şeridi (geri sayım, kapatılabilir) → son 60 sn
 *    kapatılamayan pencere → 0'da `onExpire` (çıkış + /?session=maintenance). Oturum sunucuda kesildiyse (`ended`, 401
 *    MAINTENANCE) aynı pencere kısa (10 sn) geri sayımla açılır.
 *  • global yönetici: pencere/çıkış YOK; başlamak üzereyken ve bakım sürerken kalıcı admin şeridi (Uzat · Hemen bitir ·
 *    Yönet). Duyuru şeridini o da görür.
 *  • bakım BİTTİKTEN sonra (sunucu `state: 'ended'`, bildirim süresince — 2026-10-02 kullanıcı isteği): HERKESE
 *    kapatılabilir "Planlı bakım tamamlandı" şeridi (kapatma pencere + sürüm başına, tür `ended`). Pencere/sayaç yok.
 *
 * Tik aralığı: uyarı/son dakika/bakım sırasında 1 sn, yalnız duyuruda 30 sn, bakım yokken ve "tamamlandı"da hiç.
 * `stripsHidden`: yalnız pencere (zorunlu parola değişimi ekranı gibi kabuksuz görünümlerde).
 */
export default function SystemMaintenanceLayer({ block, offset = 0, globalAdmin = false, ended = false, onExpire, onChanged,
  onOpenSettings, stripsHidden = false, style }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [now, setNow] = useState(() => Date.now() + offset)
  const [, bump] = useState(0)          // kapatma sonrası yeniden çizim (localStorage okunur)
  const [extendOpen, setExtendOpen] = useState(false)
  const [busy, setBusy] = useState(false)

  const phase = clientPhase(block, now)
  const fast = phase === 'warning' || phase === 'final' || phase === 'active'
  const endedNotice = block?.state === 'ended'   // bakım tamamlandı (sunucu bildirim süresi) — zaman hesabı yok
  useEffect(() => {
    setNow(Date.now() + offset)
    if (!block || block.state === 'none' || block.state === 'ended') return undefined
    const id = setInterval(() => setNow(Date.now() + offset), fast ? 1000 : 30_000)
    return () => clearInterval(id)
  }, [block, offset, fast])

  const toStart = secondsUntil(block?.start_at, now)
  const dialogOpen = ended || (!globalAdmin && (phase === 'final' || phase === 'active'))
  const dialogMode = ended ? 'ended' : 'final'

  async function endNow() {
    const ok = await showConfirm({
      title: t('sysmaint.confirm.endTitle'),
      message: t('sysmaint.confirm.endBody', windowText(block)) + '\n\n' + t('sysmaint.confirm.endImpact'),
      variant: 'warning', confirmText: t('sysmaint.confirm.endOk'), cancelText: t('app.cancel'),
    })
    if (!ok || !block?.id) return
    setBusy(true)
    try {
      const r = await api.systemMaintenance.endNow(block.id)
      if (r?.success) toast.success(t('sysmaint.toast.ended'))
      else toast.error(r?.error || t('sysmaint.err.generic'))
      onChanged?.()
    } finally {
      setBusy(false)
    }
  }

  const strip = (() => {
    if (stripsHidden || ended || !block) return null
    if (globalAdmin && (phase === 'warning' || phase === 'final' || phase === 'active')) {
      return (
        <MaintenanceAdminStrip block={block} phase={phase} secondsLeft={toStart} busy={busy}
          onExtend={phase === 'active' ? () => setExtendOpen(true) : undefined}
          onEndNow={phase === 'active' ? endNow : undefined} onOpenSettings={onOpenSettings} />
      )
    }
    if (!globalAdmin && phase === 'warning' && !isDismissed('warning', block)) {
      return <MaintenanceWarningStrip block={block} secondsLeft={toStart} onDismiss={() => { dismiss('warning', block); bump((n) => n + 1) }} />
    }
    if (phase === 'announced' && !isDismissed('announce', block)) {
      return <MaintenanceAnnounceStrip block={block} onDismiss={() => { dismiss('announce', block); bump((n) => n + 1) }} />
    }
    if (endedNotice && !isDismissed('ended', block)) {
      return <MaintenanceEndedStrip block={block} onDismiss={() => { dismiss('ended', block); bump((n) => n + 1) }} />
    }
    return null
  })()

  return (
    <>
      {strip && <div data-slot="maint-strips" style={style}>{strip}</div>}
      {!globalAdmin && (
        <MaintenanceCountdownDialog open={dialogOpen} mode={dialogMode} seconds={toStart} block={block} onExpire={onExpire} />
      )}
      {globalAdmin && block?.id && (
        <SysMaintExtendDialog open={extendOpen} onClose={() => setExtendOpen(false)}
          window={{ id: block.id, end_at: block.end_at }}
          onSaved={() => { setExtendOpen(false); onChanged?.() }} />
      )}
    </>
  )
}
