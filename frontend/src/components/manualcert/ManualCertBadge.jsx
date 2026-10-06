import { FileUp } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { dateOnly } from '../certcard/certCardModel.js'

/**
 * "Manuel" rozeti — sertifikanın dosyadan yüklendiğini her yüzeyde AYNI biçimde söyler (kart durum satırı, tablolar,
 * envanter, sertifika penceresi başlığı). İkon `FileUp` + kısa etiket; açıklama ("Dosyadan yüklendi · sürüm N · tarih")
 * dokununca da açılır (HintPopover — yalnız-hover bilgi YOK). Ağ satırlarında çağıran hiç çizmez.
 *
 * <p>`triggerClassName`: kartın "stretched button" örtüsünün ÜSTÜNE çıkmak için (`CARD_LAYER`). Tıklama satır/kart
 * tıklamasına sızmaz. Test kancası: `data-slot="manual-cert-badge"` (+ `data-version`).
 */
export default function ManualCertBadge({ version = null, uploadedAt = null, className, triggerClassName, rowLabel }) {
  const t = useT()
  const parts = [t('mcert.badge.uploaded')]
  if (version != null) parts.push(t('mcert.badge.version', version))
  if (uploadedAt) parts.push(dateOnly(uploadedAt))
  const hint = parts.join(' · ')
  return (
    <span className="inline-flex shrink-0" onClick={(e) => e.stopPropagation()} onKeyDown={(e) => e.stopPropagation()}>
      <HintPopover content={hint} aria-label={rowLabel ? t('a11y.rowAction', rowLabel, hint) : hint}
        // Dokunmatikte 20 px rozetin dokunma alanı ::after ile ~40 px (görünüm aynı; kart çipleriyle aynı yöntem)
        triggerClassName={cn('relative rounded-md pointer-coarse:min-h-0 pointer-coarse:after:absolute pointer-coarse:after:-inset-x-1 pointer-coarse:after:-inset-y-2.5', triggerClassName)}>
        <Badge variant="outline" data-slot="manual-cert-badge" data-version={version ?? undefined}
          className={cn('h-5 gap-1 rounded-md border-violet-500/35 bg-violet-500/10 px-1.5 text-[11px] font-semibold text-violet-800 dark:bg-violet-500/20 dark:text-violet-300',
            className)}>
          <FileUp aria-hidden="true" className="size-3" />
          {t('mcert.badge.label')}
        </Badge>
      </HintPopover>
    </span>
  )
}
