import { useEffect, useRef, useState } from 'react'
import { LogOut, UserX } from 'lucide-react'
import { useT } from '../i18n/index.jsx'
import { ACCOUNT_INACTIVE_COUNTDOWN } from '../utils/accountInactive.js'
import {
  AlertDialog, AlertDialogAction, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader,
  AlertDialogMedia, AlertDialogTitle,
} from '@/components/shadcn/alert-dialog'

/** Örtü ve pencere HER katmanın üstünde (açık modal / onay penceresi / bildirim): oturum bitti, başka iş yapılamaz. */
const TOP_LAYER = 'z-(--z-critical)'

/**
 * "Hesabınız pasife alındı" — BLOKLAYAN, kapatılamaz pencere (2026-10-02, kullanıcı kararı). Yönetici kullanıcıyı pasif
 * yaptığında sunucu oturumu o anda keser ve sonraki ilk istek (en geç ~15 sn'lik oturum yoklaması) `ACCOUNT_INACTIVE`
 * sinyali döner; uygulama bu pencereyi açar. Büyük 10 → 0 geri sayımı biter (ya da "Şimdi çıkış yap" basılır) bitmez
 * `onExpire` çağrılır — çağıran istemci tarafını temizler ve giriş sayfasına (`/?session=inactive`) gider.
 *
 * <p>Kapatma yolu YOK: Escape, örtü tıklaması ve dış etkileşim yutulur (shadcn AlertDialog zaten dış tıklamayla kapanmaz;
 * `open` denetimli ve `onOpenChange` yok sayılır). Telefonda pencere genişliği ekrana sığar (`max-w-[calc(100%-2rem)]`).
 *
 * Test kancaları: `data-slot="account-inactive-dialog"`, `account-inactive-countdown`, `account-inactive-logout`.
 */
export default function AccountInactiveDialog({ open, onExpire, seconds = ACCOUNT_INACTIVE_COUNTDOWN }) {
  const t = useT()
  const [left, setLeft] = useState(seconds)
  const firedRef = useRef(false)
  const expireRef = useRef(onExpire)
  expireRef.current = onExpire

  useEffect(() => {
    if (!open) return undefined
    firedRef.current = false
    setLeft(seconds)
    const id = setInterval(() => setLeft((s) => Math.max(0, s - 1)), 1000)
    return () => clearInterval(id)
  }, [open, seconds])

  const finish = () => {
    if (firedRef.current) return
    firedRef.current = true
    expireRef.current?.()
  }

  useEffect(() => {
    if (open && left <= 0) finish()
  }, [open, left])

  return (
    <AlertDialog open={!!open} onOpenChange={() => { /* kapatılamaz */ }}>
      <AlertDialogContent data-slot="account-inactive-dialog" className={TOP_LAYER}
        overlayProps={{ className: TOP_LAYER }}
        onEscapeKeyDown={(e) => e.preventDefault()}>
        <AlertDialogHeader>
          <AlertDialogMedia className="bg-destructive/10 text-destructive">
            <UserX aria-hidden="true" />
          </AlertDialogMedia>
          <AlertDialogTitle>{t('acct.inactive.title')}</AlertDialogTitle>
          <AlertDialogDescription>{t('acct.inactive.body')}</AlertDialogDescription>
        </AlertDialogHeader>
        <div className="flex min-w-0 flex-col items-center gap-1 py-1 text-center">
          <span data-slot="account-inactive-countdown" role="timer" aria-label={t('acct.inactive.countdown', left)}
            className="text-6xl leading-none font-bold tabular-nums text-destructive">
            {left}
          </span>
          <span aria-hidden="true" className="text-sm text-muted-foreground">{t('acct.inactive.countdown', left)}</span>
        </div>
        <AlertDialogFooter>
          <AlertDialogAction data-slot="account-inactive-logout" variant="destructive" onClick={finish}
            className="min-h-10 w-full sm:w-auto">
            <LogOut aria-hidden="true" /> {t('acct.inactive.logoutNow')}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
