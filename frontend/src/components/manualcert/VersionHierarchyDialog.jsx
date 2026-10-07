import { ListTree } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import ManualCertHierarchy from '../certmodal/ManualCertHierarchy.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'

/**
 * Sürümler sekmesi → "Görüntüle" (2026-10-07, kullanıcı isteği): seçilen sürümün sertifika HİYERARŞİSİ (tarayıcı gibi
 * kök → ara → yaprak) İÇ İÇE bir pencerede. ui/ModalShell derinliği React ağacından okur: sertifika penceresinin
 * (kendisi de ModalShell) ÜSTÜNDE açılır (shadcn Sheet kabuğun z-index'inin altında kalırdı). Telefonda ekrana sığar,
 * gövde kayar. Başlıkta sürüm numarası + güncel / önceki sürüm rozeti.
 *
 * Props: `inventoryId`, `version` (sürüm görünümü: `id`, `version`, `current`), `onClose`.
 */
export default function VersionHierarchyDialog({ inventoryId, version, onClose }) {
  const t = useT()
  if (!version) return null
  const current = version.current === true
  const title = (
    <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
      <span className="min-w-0 [overflow-wrap:anywhere]">{t('chier.dialogTitle', version.version)}</span>
      <Badge variant="outline" data-slot="mcert-view-badge" data-current={current ? 'true' : 'false'}
        className={cn('h-auto rounded-md px-1.5 py-0.5 text-[11px] font-semibold',
          current ? 'border-success/40 bg-success/10 text-success dark:bg-success/20' : 'text-muted-foreground')}>
        {current ? t('mcert.ver.current') : t('chier.superseded')}
      </Badge>
    </span>
  )
  return (
    <ModalShell open onClose={onClose} icon={ListTree} size="lg" scrollBody title={title}>
      <div data-slot="mcert-version-viewer" data-version={version.version} className="flex min-w-0 flex-col gap-3 pb-1">
        <ManualCertHierarchy inventoryId={inventoryId} versionId={version.id} />
      </div>
    </ModalShell>
  )
}
