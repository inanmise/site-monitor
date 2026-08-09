import { Info, CheckCircle2, AlertTriangle, AlertOctagon, X } from 'lucide-react'

/**
 * Satır içi durum bildirimi — bilgi / başarı / uyarı / hata.
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
 */
const TONES = {
  info:    { icon: Info,          cls: 'alert-banner--info' },
  success: { icon: CheckCircle2,  cls: 'alert-banner--success' },
  warning: { icon: AlertTriangle, cls: 'alert-banner--warning' },
  danger:  { icon: AlertOctagon,  cls: 'alert-banner--danger' },
}

export default function AlertBanner({
  tone = 'info', title, children, icon, actions,
  onDismiss, dismissLabel, role = 'status', className = '',
}) {
  const spec = TONES[tone] ?? TONES.info
  const Icon = icon ?? spec.icon
  return (
    <div className={['alert-banner', spec.cls, className].filter(Boolean).join(' ')} role={role}>
      <Icon size={16} className="alert-banner-icon" aria-hidden="true" />
      <div className="alert-banner-body">
        {title && <div className="alert-banner-title">{title}</div>}
        {children && <div className="alert-banner-text">{children}</div>}
      </div>
      {actions && <div className="alert-banner-actions">{actions}</div>}
      {onDismiss && (
        <button type="button" className="alert-banner-close" onClick={onDismiss} aria-label={dismissLabel}>
          <X size={14} />
        </button>
      )}
    </div>
  )
}
