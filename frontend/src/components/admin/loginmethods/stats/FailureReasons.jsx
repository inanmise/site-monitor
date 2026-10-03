import { ShieldCheck } from 'lucide-react'
import { useT, useDateLocale } from '../../../../i18n/index.jsx'
import StatusBlock from '../../../ui/StatusBlock.jsx'
import { ProgressBar } from '../../../ui/Progress.jsx'
import { Card } from '@/components/shadcn/card'
import { CHANNELS, channelLabel, fmtNum, fmtPct, reasonLabel } from './loginStatsModel.js'
import { InfoHint } from './StatsParts.jsx'

/**
 * Başarısızlık nedenleri (2026-10-03): neden etiketi (`lm.stats.reason.<KOD>`, sözlükte yoksa ham kod) + adet + başarısız
 * denemeler içindeki payı (ui/Progress) + kanal kırılımı. Bilinmeyen kullanıcı adları ayrı satırdır (kanala yazılmaz);
 * kod teslim hataları deneme değil, sistem hatası olarak ayrıca belirtilir.
 * Test kancaları: `data-slot="lm-reasons"`, satır `lm-reason` (`data-reason`), boşta `lm-reasons-empty`.
 */
export default function FailureReasons({ reasons, totals }) {
  const t = useT()
  const locale = useDateLocale()
  const list = Array.isArray(reasons) ? reasons : []
  const failed = Number(totals?.failed) || list.reduce((s, r) => s + (Number(r.count) || 0), 0)
  const delivery = Number(totals?.delivery_failures) || 0
  return (
    <Card data-slot="lm-reasons" className="min-w-0 gap-3 px-3.5 py-4 shadow-xs sm:px-5">
      <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
        <h4 className="m-0 text-sm font-semibold">{t('lm.stats.reasons.title')}</h4>
        <InfoHint text={t('lm.stats.reasons.def')} label={t('lm.stats.defLabel', t('lm.stats.reasons.title'))} />
      </div>
      {list.length === 0 ? (
        <div data-slot="lm-reasons-empty"><StatusBlock tone="success" icon={ShieldCheck} title={t('lm.stats.reasons.empty')} /></div>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-3 p-0">
          {list.map((r) => {
            const count = Number(r.count) || 0
            const share = failed > 0 ? count / failed : 0
            const by = r.channels || {}
            const parts = [...CHANNELS, 'UNKNOWN'].filter((ch) => by[ch] > 0)
            return (
              <li key={r.reason} data-slot="lm-reason" data-reason={r.reason} className="flex min-w-0 flex-col gap-1">
                <div className="flex min-w-0 items-baseline justify-between gap-2 text-sm">
                  <span className="min-w-0 [overflow-wrap:anywhere]">{reasonLabel(r.reason, t)}</span>
                  <span className="shrink-0 tabular-nums">
                    <span className="font-semibold">{fmtNum(count, locale)}</span>
                    <span className="text-xs text-muted-foreground"> · {fmtPct(share, locale, 0)}</span>
                  </span>
                </div>
                <ProgressBar value={Math.round(share * 1000) / 10} size="sm" decorative tone="crit" />
                {parts.length > 0 && (
                  <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
                    {parts.map((ch) => `${channelLabel(ch, t)} ${fmtNum(by[ch], locale)}`).join(' · ')}
                  </span>
                )}
              </li>
            )
          })}
        </ul>
      )}
      {delivery > 0 && (
        <p data-slot="lm-reasons-delivery" className="m-0 border-t pt-2 text-xs text-muted-foreground">
          {t('lm.stats.deliveryNote', fmtNum(delivery, locale))}
        </p>
      )}
    </Card>
  )
}
