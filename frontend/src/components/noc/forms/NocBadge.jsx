import { Headset } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import HintPopover from '../../ui/HintPopover.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'

/**
 * "7/24" rozeti (2026-09-27) — izlemenin kritik uyarıları 7/24 izleme ekibine de gidiyor (`noc_notify`). Kartta yalnız
 * Zengin görünümde (çağıran `MonitorCardRich` içine koyar), detay penceresinin başlığında her zaman.
 *
 * Açıklama dokun-gör balonunda (ui/HintPopover — telefonda da açılır; yalnız-hover bilgi YOK). Tetiğin adı satırı
 * ayırt eder: kartta `rowLabel` (izlemenin adresi/adı) verilir → "<hedef> — 7/24 izleme ekibine bildiriliyor".
 * `triggerClassName`: kartta örtünün üstüne çıkmak için `CARD_LAYER`.
 *
 * Test kancası: `data-slot="noc-badge"`.
 */
export default function NocBadge({ rowLabel, triggerClassName, className }) {
  const t = useT()
  const label = rowLabel ? t('a11y.rowAction', rowLabel, t('nocf.badgeAria')) : t('nocf.badgeAria')
  return (
    <HintPopover content={t('nocf.badgeHint')} aria-label={label}
      triggerClassName={cn('max-w-full shrink-0 rounded-md pointer-coarse:min-h-10', triggerClassName)}>
      <Badge variant="outline" data-slot="noc-badge"
        className={cn('h-5 gap-1 rounded-md border-primary/30 bg-primary/5 px-1.5 text-[10.5px] font-bold text-primary dark:bg-primary/15', className)}>
        <Headset aria-hidden="true" />{t('nocf.badge')}
      </Badge>
    </HintPopover>
  )
}
