import { ShieldCheck } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { cn } from '@/lib/utils'

/**
 * "Özel anahtar (N) tarayıcınızda ayıklandı; sunucuya gönderilmedi. Parola da yalnız tarayıcıda kullanıldı."
 * (2026-10-08, kullanıcı isteği: keystore yüklemesinde özel anahtar ASLA sunucuya gitmez). Tarayıcıdaki ayıklama
 * sonucundan çizilir; ayıklama yoksa (henüz analiz edilmedi) genel güvence satırı.
 *
 * <p>Test kancası: `data-slot="mcert-kept-local"` + `data-keys` (sayı) + `data-password="used"`; genel satır
 * `data-slot="mcert-local-hint"`.
 */
export default function KeptLocalNote({ extraction, className }) {
  const t = useT()
  const row = 'm-0 flex min-w-0 items-start gap-2 rounded-md border px-2.5 py-1.5 text-[13px] leading-snug'
  if (!extraction) {
    return (
      <p data-slot="mcert-local-hint" className={cn(row, 'border-border bg-muted/30 text-muted-foreground', className)}>
        <ShieldCheck aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 text-success" />
        <span className="min-w-0">{t('mcert.local.fileHint')}</span>
      </p>
    )
  }
  const n = Number(extraction.private_keys_removed) || 0
  const pw = !!extraction.password_used
  const parts = []
  if (n > 0) parts.push(t('mcert.local.keys', n))
  if (pw) parts.push(n > 0 ? t('mcert.local.password') : t('mcert.local.passwordOnly'))
  if (!parts.length) parts.push(t('mcert.local.publicOnly'))
  return (
    <p data-slot="mcert-kept-local" data-keys={n} data-password={pw ? 'used' : undefined}
      className={cn(row, 'border-success/30 bg-success/5 text-foreground dark:bg-success/10', className)}>
      <ShieldCheck aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 text-success" />
      <span className="min-w-0 [overflow-wrap:anywhere]">{parts.join(' ')}</span>
    </p>
  )
}
