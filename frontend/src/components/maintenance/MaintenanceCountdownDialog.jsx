import { useEffect, useRef, useState } from 'react'
import { LogOut, Wrench } from 'lucide-react'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { ENDED_COUNTDOWN_SECONDS, messageFor, windowEnds, windowText } from '../../utils/systemMaintenance.js'
import { formatDuration } from '../../utils/incidentMeta.js'
import {
  AlertDialog, AlertDialogAction, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader,
  AlertDialogMedia, AlertDialogTitle,
} from '@/components/shadcn/alert-dialog'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/** Örtü ve pencere HER katmanın üstünde (açık modal / onay / bildirim) — pasif hesap penceresiyle aynı katman. */
const TOP_LAYER = 'z-(--z-critical)'

/**
 * Pencere gövdesi — saf sunum (önizleme bunu kart içinde, gerçek pencere AlertDialog içinde çizer).
 * `mode`: `final` (bakıma son 60 sn — sayaç sunucu saatine göre) · `ended` (oturum sunucuda kesildi — kısa sayaç).
 */
export function MaintenanceDialogBody({ mode, seconds, block, onLogout, preview = false }) {
  const t = useT()
  const { lang } = useLanguage()
  const msg = messageFor(block, lang)
  const win = windowText(block)
  const ends = windowEnds(block)
  const Title = preview ? 'h3' : AlertDialogTitle
  const Desc = preview ? 'p' : AlertDialogDescription
  const Header = preview ? 'div' : AlertDialogHeader
  const Media = preview ? 'span' : AlertDialogMedia
  const Footer = preview ? 'div' : AlertDialogFooter
  const Action = preview ? Button : AlertDialogAction
  return (
    <>
      <Header className={cn(preview && 'flex flex-col items-center gap-2 text-center')}>
        <Media className={cn('bg-amber-500/15 text-amber-700 dark:text-amber-400',
          preview && 'inline-flex size-10 items-center justify-center rounded-md [&_svg]:size-5')}>
          <Wrench aria-hidden="true" />
        </Media>
        <Title className={cn(preview && 'm-0 text-base font-semibold')}>
          {mode === 'ended' ? t('sysmaint.dialog.titleEnded') : t('sysmaint.dialog.titleFinal')}
        </Title>
        <Desc className={cn(preview && 'm-0 text-sm text-muted-foreground')}>
          {/* Bakımın başlangıç/bitiş saati + toplam süresi (2026-10-02 kullanıcı isteği); zaman yoksa eski metin */}
          {ends
            ? (mode === 'ended'
              ? t('sysmaint.dialog.bodyEndedTimed', ends.end, formatDuration(ends.durationMs, t))
              : t('sysmaint.dialog.bodyFinalTimed', ends.start, ends.end, formatDuration(ends.durationMs, t)))
            : (mode === 'ended' ? t('sysmaint.dialog.bodyEnded') : t('sysmaint.dialog.bodyFinal'))}
        </Desc>
      </Header>
      <div className="flex min-w-0 flex-col items-center gap-1 py-1 text-center">
        <span data-slot="maint-dialog-countdown" role="timer" aria-label={t('sysmaint.dialog.countdown', seconds)}
          className="text-6xl leading-none font-bold tabular-nums text-amber-700 dark:text-amber-400">
          {seconds}
        </span>
        <span aria-hidden="true" className="text-sm text-muted-foreground">{t('sysmaint.dialog.countdown', seconds)}</span>
        {win && <span className="mt-1 text-xs text-muted-foreground">{t('sysmaint.windowLine', win)}</span>}
        {msg && <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{msg}</span>}
      </div>
      <Footer className={cn(preview && 'flex justify-center')}>
        <Action data-slot="maint-dialog-logout" variant="destructive" onClick={onLogout} type="button"
          className="min-h-10 w-full sm:w-auto">
          <LogOut aria-hidden="true" /> {t('sysmaint.dialog.logoutNow')}
        </Action>
      </Footer>
    </>
  )
}

/**
 * "Sistem bakıma giriyor" — BLOKLAYAN, kapatılamaz pencere (2026-10-02, kullanıcı kararı; pasif hesap penceresinin aynası:
 * components/AccountInactiveDialog.jsx). İki kip:
 *  • `final` — bakıma son 60 sn: sayaç (`seconds`) ÇAĞIRANDAN gelir, sunucu saatine göre hesaplanmış; 0'da `onExpire`.
 *  • `ended` — oturum sunucuda kesildi (401 MAINTENANCE): 10 → 0 kendi sayar; 0'da `onExpire`.
 * "Şimdi çıkış yap" da `onExpire`'ı çağırır (tek sefer). Escape / örtü / dış etkileşim yutulur.
 *
 * Test kancaları: `data-slot="maint-dialog"` (`data-mode`), `maint-dialog-countdown`, `maint-dialog-logout`.
 */
export default function MaintenanceCountdownDialog({ open, mode = 'final', seconds, block, onExpire }) {
  const [left, setLeft] = useState(ENDED_COUNTDOWN_SECONDS)
  const firedRef = useRef(false)
  const expireRef = useRef(onExpire)
  expireRef.current = onExpire

  useEffect(() => {
    if (!open) return undefined
    firedRef.current = false
    if (mode !== 'ended') return undefined
    setLeft(ENDED_COUNTDOWN_SECONDS)
    const id = setInterval(() => setLeft((s) => Math.max(0, s - 1)), 1000)
    return () => clearInterval(id)
  }, [open, mode])

  const shown = mode === 'ended' ? left : Math.max(0, Number(seconds) || 0)

  const finish = () => {
    if (firedRef.current) return
    firedRef.current = true
    expireRef.current?.()
  }

  useEffect(() => {
    if (open && shown <= 0) finish()
  }, [open, shown])

  return (
    <AlertDialog open={!!open} onOpenChange={() => { /* kapatılamaz */ }}>
      <AlertDialogContent data-slot="maint-dialog" data-mode={mode} className={TOP_LAYER}
        overlayProps={{ className: TOP_LAYER }}
        onEscapeKeyDown={(e) => e.preventDefault()}>
        <MaintenanceDialogBody mode={mode} seconds={shown} block={block} onLogout={finish} />
      </AlertDialogContent>
    </AlertDialog>
  )
}
