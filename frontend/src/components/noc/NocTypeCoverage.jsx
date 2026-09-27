import { ProgressBar } from '../ui/Progress.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { coverageTone, NOC_TYPES } from './nocModel.js'
import { NocTypeTag, typeLabel } from './nocUi.jsx'

/**
 * Tür bazında 7/24 kapsamı (Kapsam sayfası, 2026-09-27): her tür için "kapsanan / aktif" + ProgressBar (tam → yeşil,
 * yarıdan fazla → amber, altı → kırmızı). Yalnız izlemesi olan türler çizilir. Yönetici bir türü kapattıysa satırda
 * "Tür kapalı" rozeti — o türde "bildir" açık olsa da e-posta gitmez. Duraklatılmışlar orana katılmaz (ayrı not).
 * Çubuk süs (`decorative`): aynı değer yanında METİN olarak duruyor. Test kancası: `data-slot="noc-type-coverage"`,
 * satır `data-type`.
 */
export default function NocTypeCoverage({ byType = {}, disabledTypes = [], t }) {
  const rows = NOC_TYPES.filter((k) => (byType[k]?.total || 0) + (byType[k]?.paused || 0) > 0)
  if (!rows.length) return null
  return (
    <Card data-slot="noc-type-coverage" className="gap-3 py-4">
      <CardHeader className="gap-1 px-4 sm:px-5">
        <CardTitle role="heading" aria-level={3} className="text-base">{t('noc.byTypeTitle')}</CardTitle>
        <CardDescription>{t('noc.byTypeDesc')}</CardDescription>
      </CardHeader>
      <CardContent className="px-4 sm:px-5">
        <ul className="grid list-none grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-5">
          {rows.map((k) => {
            const { total = 0, covered = 0, paused = 0 } = byType[k] || {}
            const off = disabledTypes.includes(k)
            return (
              <li key={k} data-type={k} className="flex min-w-0 flex-col gap-1.5">
                <div className="flex min-w-0 items-center justify-between gap-2">
                  <NocTypeTag type={k} t={t} className="text-foreground" />
                  <span className="shrink-0 text-xs font-semibold tabular-nums" data-slot="noc-type-ratio">
                    {t('noc.typeRatio', covered, total)}
                  </span>
                </div>
                <ProgressBar value={covered} max={total || 1} size="md" decorative tone={off ? 'crit' : coverageTone(covered, total)} />
                {(off || paused > 0) && (
                  <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                    {off && <ToneBadge tone="danger" title={t('noc.typeOffHint', typeLabel(t, k))}>{t('noc.typeOffBadge')}</ToneBadge>}
                    {paused > 0 && <span>{t('noc.typePaused', paused)}</span>}
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      </CardContent>
    </Card>
  )
}
