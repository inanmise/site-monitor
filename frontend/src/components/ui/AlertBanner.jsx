import { Info, CheckCircle2, AlertTriangle, AlertOctagon, X } from 'lucide-react'
import { Alert, AlertTitle, AlertDescription } from '@/components/shadcn/alert'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Satır içi durum bildirimi — bilgi / başarı / uyarı / hata. Çizim shadcn Alert; dört ton
 * alert.jsx'teki proje varyantlarıdır (info/success/warning/danger, koyu tema karşılıklarıyla).
 *
 * Neden var: `.alert-msg` ailesi yalnız "başarı" (yeşil) ve "hata" varyantlarına sahipti;
 * `--warn` hiç tanımlı değildi, dolayısıyla uyarılar YEŞİL başarı kutusu olarak çıkıyordu.
 * Bu bileşen dört tonu da token'lı ve koyu-tema uyumlu olarak tek yerde toplar.
 *
 * Saf sunum: içinde useT() YOKTUR, tüm metinler prop olarak gelir.
 *
 * role: varsayılan "status" (kibar duyuru). "alert" YALNIZ kullanıcının o an düzeltmesi
 * gereken bir şey için verilmeli ve bir ekranda tek bir alert bulunmalı — ekran okuyucu
 * birden çok alert'i araya girerek okur, ayrıca getByRole('alert') sorguları çoğullaşır.
 * (shadcn Alert kendi başına role="alert" basar; burada bilinçli olarak ezilir.)
 *
 * Test kancası: kök `data-slot="alert"` + `data-tone`.
 */
const TONES = {
  info:    Info,
  success: CheckCircle2,
  warning: AlertTriangle,
  danger:  AlertOctagon,
}

export default function AlertBanner({
  tone = 'info', title, children, icon, actions,
  onDismiss, dismissLabel, role = 'status', className = '',
}) {
  const key = TONES[tone] ? tone : 'info'
  const Icon = icon ?? TONES[key]
  const side = Boolean(actions || onDismiss)
  return (
    <Alert
      variant={key}
      role={role}
      data-tone={key}
      // Alt boşluk eski .alert-banner'dan (93 çağrı yeri ona güveniyor); kabın son çocuğuysa
      // gereksiz — yüzen kaplarda (.modal-wide-float) kutunun altında boşluk bırakmasın.
      className={cn(
        'mb-3 last:mb-0',
        // Eylem/kapatma varsa üçüncü sütun: eski düzendeki gibi SAĞDA, başlık hizasında.
        side && 'has-[>svg]:grid-cols-[calc(var(--spacing)*4)_1fr_auto] grid-cols-[0_1fr_auto]',
        className,
      )}
    >
      <Icon aria-hidden="true" />
      {title && <AlertTitle className="line-clamp-none font-bold">{title}</AlertTitle>}
      {children && <AlertDescription className="[overflow-wrap:anywhere]">{children}</AlertDescription>}
      {side && (
        <div data-slot="alert-actions" className="col-start-3 row-start-1 flex items-center gap-1.5 self-start">
          {actions}
          {onDismiss && (
            <Button type="button" variant="ghost" size="icon-xs" className="opacity-70 hover:opacity-100"
              onClick={onDismiss} aria-label={dismissLabel}>
              <X />
            </Button>
          )}
        </div>
      )}
    </Alert>
  )
}
