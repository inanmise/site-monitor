import { Lock } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { cn } from '@/lib/utils'

/**
 * Kimlik izi (IP / konum / kuruluş / tarayıcı) sunucuda bu görüntüleyici için DÜŞÜRÜLDÜĞÜNDE boş / "—" yerine açık durum:
 * kilit + "Gizli"; ipucu ve ekran okuyucu metni kimin görebileceğini söyler (global yönetici + denetçi).
 *
 * Sunucu kuralı `IdentityMask` (2026-09-28c): alan hiç gönderilmez ve satır `identity_masked: true` taşır — "IP yok"
 * (kayıt boş) ile "IP gizli" (yetki) ayrımı satırdan okunur (`row.identity_masked === true`). Kullanıcı / Oturum, değişiklik
 * geçmişi (Yönetim Paneli, bildirim grupları, saklama, İzleme Değişiklikleri) ve giriş sorunu yüzeylerinin ortak parçası.
 * Test kancası: `data-slot="id-masked"`.
 */
export default function MaskedValue({ className }) {
  const t = useT()
  return (
    <span data-slot="id-masked" title={t('uact.idMaskedHint')}
      className={cn('inline-flex items-center gap-1 font-sans text-xs text-muted-foreground', className)}>
      <Lock className="size-3 shrink-0" aria-hidden="true" />{t('uact.idMasked')}
      <span className="sr-only"> — {t('uact.idMaskedHint')}</span>
    </span>
  )
}
