import { useEffect, useState } from 'react'
import { ArrowLeft, ChartNoAxesGantt } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/shadcn/card'
import RelatedActivityPanel from '../related/RelatedActivityPanel.jsx'
import { TypeBadge } from '../ActivityParts.jsx'
import { monitorRefOf } from '../activityModel.js'

/**
 * YER TUTUCU (fe2 dalı) — gerçek ayrıntı sayfası fe1'indir (`feat/act-fe1`); entegratör fe1'in dosyasını alır. Burada
 * yalnız rota / geri / zaman çizelgesi bağlantısı ve "aynı anda ne oldu?" panelinin yerleşimi sınanabilsin diye var.
 * Sözleşme (props): `{ id, onBack, onOpenActivity(id), onOpenTimeline(type, id) }`. Kanca: `data-slot="act-detail-page"`.
 */
export default function ActivityDetailPage({ id, onBack, onOpenActivity, onOpenTimeline }) {
  const t = useT()
  const [row, setRow] = useState(null)
  useEffect(() => {
    let alive = true
    Promise.resolve().then(() => api.getActivityDetail(id))
      .then((res) => { if (alive && res?.success) setRow(res.data || null) })
      .catch(() => { /* yer tutucu: temel bilgi yoksa başlık yalnız tür */ })
    return () => { alive = false }
  }, [id])
  const ref = monitorRefOf(row)
  return (
    <div data-slot="act-detail-page" data-id={id} className="flex min-w-0 flex-col gap-4">
      <Card className="min-w-0 gap-3 py-4 shadow-xs">
        <CardHeader className="flex min-w-0 flex-col gap-2 px-4">
          <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
            <Button type="button" variant="ghost" size="sm" onClick={onBack} className="-ml-2 pointer-coarse:h-10" data-slot="act-detail-back">
              <ArrowLeft aria-hidden="true" /> {t('act.backToList')}
            </Button>
            {ref && (
              <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => onOpenTimeline(ref.type, ref.id)}>
                <ChartNoAxesGantt aria-hidden="true" /> {t('act.openTimeline')}
              </Button>
            )}
          </div>
          <CardTitle role="heading" aria-level={2} className="flex min-w-0 flex-wrap items-center gap-2 text-lg font-semibold">
            {row?.monitor_type && <TypeBadge type={row.monitor_type} />}
            <span className="min-w-0 [overflow-wrap:anywhere]">{row?.monitor_name || row?.target || t('act.openDetail')}</span>
          </CardTitle>
        </CardHeader>
        <CardContent className="px-4" />
      </Card>
      <RelatedActivityPanel activityId={id} onOpenActivity={onOpenActivity} />
    </div>
  )
}
