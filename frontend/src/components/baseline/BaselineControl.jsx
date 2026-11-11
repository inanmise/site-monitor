import { lazy, Suspense, useState } from 'react'
import { Activity } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/shadcn/button'
import { useT } from '../../i18n/index.jsx'
import { MON_ACT, MON_ACT_TONE } from '../ui/CheckRunning.jsx'

const BaselineDialog = lazy(() => import('./BaselineDialog.jsx'))   // dinamik taban çizgisi (2026-11-12)

/**
 * İzleme detay başlığındaki "dinamik taban çizgisi" düğmesi + diyaloğu — kendi kendine yeten (açık/kapalı durumu içeride).
 * Her izleme türü sayfası tek satırla kullanır: {@code <BaselineControl type="ping" monitor={selected} name={...}
 * canManage={canManageRow(selected)} />}. HTTP sayfası tarihsel olarak satır-içi kurar (aynı data-slot / davranış); diğer altı
 * tür bu bileşeni paylaşır. Diyalog Radix portalıyla body'ye çizilir, bu yüzden başlıktan render edilmesi görünümü etkilemez.
 */
export default function BaselineControl({ type, monitor, name, canManage }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  if (!canManage || !monitor) return null
  return (
    <>
      <Button type="button" variant="outline" size="icon-sm" data-slot="baseline-open"
        className={cn(MON_ACT, 'pointer-coarse:size-10', MON_ACT_TONE.edit)}
        onClick={() => setOpen(true)} title={t('baseline.open')} aria-label={t('baseline.open')}>
        <Activity size={13} aria-hidden="true" />
      </Button>
      {open && (
        <Suspense fallback={null}>
          <BaselineDialog type={type} monitorId={monitor.id} monitorName={name || monitor.name}
            onClose={() => setOpen(false)} />
        </Suspense>
      )}
    </>
  )
}
