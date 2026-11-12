import { useMemo } from 'react'
import { useT } from '../../../i18n/index.jsx'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import ToneBadge from '../ToneBadge.jsx'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { buildThresholdMatrix } from './thresholdMatrixModel.js'

const MATRIX_TABLE_MIN_PX = 600

/**
 * Eşik KARŞILAŞTIRMA matrisi (2026-11-12, zenginleştirme #3) — Varsayılan + Tier 1..4 yan yana: kritik/yüksek/uyarı gün
 * + yeniden-uyarı + ETKİ (sertifika sayısı). Kendi eşiği olan tier'ın FARKLI alanları vurgulu (özel); eşiği olmayan tier
 * varsayılanı DEVRALIR (soluk, "dev." ipucu). Salt OKUR — düzenleme alttaki kartlarda; bu matris bir-bakışta karşılaştırma.
 * mweb: kap genişliğine göre tablo (≥ 600 px) ya da kart. Test kancaları: data-slot="threshold-matrix|tm-row|tm-cell",
 * data-tier, data-override, data-inherited.
 */
export default function ThresholdMatrix({ rows, impact }) {
  const t = useT()
  const [ref, width] = useElementWidth()
  const { rows: mrows, unclassified } = useMemo(() => buildThresholdMatrix(rows, impact), [rows, impact])
  const cards = width > 0 && width < MATRIX_TABLE_MIN_PX

  const label = (r) => (r.tier == null ? t('thr.mtx.defaultRow') : t(`inv.tier${r.tier}`))
  const impactText = (r) => (r.impact == null ? '—' : t('thr.mtx.certs', r.impact))

  return (
    <section data-slot="threshold-matrix" className="rounded-xl border bg-card p-3 shadow-xs sm:p-4">
      <div className="mb-2 flex items-center gap-1.5 text-[11px] font-bold tracking-widest text-muted-foreground uppercase">
        {t('thr.mtx.title')}
      </div>
      <p className="mb-3 text-xs text-muted-foreground">{t('thr.mtx.hint')}</p>

      <div ref={ref}>
        {cards ? (
          <ul className="list-none space-y-2 p-0">
            {mrows.map((r) => (
              <li key={r.tier ?? 'default'} data-slot="tm-row" data-tier={r.tier ?? 'default'} data-inherited={r.inherited ? 'true' : undefined}
                className="rounded-md border bg-background p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <ToneBadge tone={r.tier == null ? 'neutral' : r.inherited ? 'muted' : 'info'} className="font-semibold">{label(r)}</ToneBadge>
                  {r.inherited && <span className="text-xs text-muted-foreground">{t('thr.mtx.inheritedTag')}</span>}
                  <span className="ms-auto text-xs text-muted-foreground">{t('thr.mtx.impact')}: {impactText(r)}</span>
                </div>
                <div className="mt-2 grid grid-cols-3 gap-2 text-center">
                  {(['critical', 'high', 'warning']).map((k) => (
                    <div key={k} className="rounded-sm bg-muted/40 px-2 py-1.5">
                      <span className="block text-[0.7rem] text-muted-foreground">{t(`thr.mtx.col.${k}`)}</span>
                      <DayCell value={r.days[k]} override={r.override[k]} inherited={r.inherited} t={t} level={k} />
                    </div>
                  ))}
                </div>
                <div className="mt-1.5 text-xs text-muted-foreground">
                  {t('thr.mtx.col.reAlert')}: <ReAlertCell hours={r.reAlert} override={r.override.reAlert} inherited={r.inherited} t={t} />
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <div className="overflow-x-auto">
            <Table className="text-[0.9em]">
              <TableHeader className="bg-muted/50">
                <TableRow className="hover:bg-transparent">
                  <TableHead className="h-9 font-semibold">{t('thr.mtx.col.tier')}</TableHead>
                  <TableHead className="h-9 text-center font-semibold">{t('thr.mtx.col.critical')}</TableHead>
                  <TableHead className="h-9 text-center font-semibold">{t('thr.mtx.col.high')}</TableHead>
                  <TableHead className="h-9 text-center font-semibold">{t('thr.mtx.col.warning')}</TableHead>
                  <TableHead className="h-9 text-center font-semibold">{t('thr.mtx.col.reAlert')}</TableHead>
                  <TableHead className="h-9 text-center font-semibold">{t('thr.mtx.col.impact')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {mrows.map((r) => (
                  <TableRow key={r.tier ?? 'default'} data-slot="tm-row" data-tier={r.tier ?? 'default'} data-inherited={r.inherited ? 'true' : undefined}>
                    <TableCell className="whitespace-normal">
                      <ToneBadge tone={r.tier == null ? 'neutral' : r.inherited ? 'muted' : 'info'} className="font-semibold">{label(r)}</ToneBadge>
                      {r.inherited && <span className="ms-1.5 text-xs text-muted-foreground">{t('thr.mtx.inheritedTag')}</span>}
                    </TableCell>
                    <TableCell className="text-center"><DayCell value={r.days.critical} override={r.override.critical} inherited={r.inherited} t={t} level="critical" /></TableCell>
                    <TableCell className="text-center"><DayCell value={r.days.high} override={r.override.high} inherited={r.inherited} t={t} level="high" /></TableCell>
                    <TableCell className="text-center"><DayCell value={r.days.warning} override={r.override.warning} inherited={r.inherited} t={t} level="warning" /></TableCell>
                    <TableCell className="text-center"><ReAlertCell hours={r.reAlert} override={r.override.reAlert} inherited={r.inherited} t={t} /></TableCell>
                    <TableCell className="text-center tabular-nums text-muted-foreground">{impactText(r)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </div>

      {unclassified != null && (
        <p data-slot="tm-unclassified" className="mt-3 text-xs text-muted-foreground">{t('thr.mtx.unclassified', unclassified)}</p>
      )}
    </section>
  )
}

/** Gün hücresi: özel (override) → kalın + vurgu + nokta; devralınan → soluk ("dev." ipucu). */
function DayCell({ value, override, inherited, t, level }) {
  return (
    <span data-slot="tm-cell" data-level={level} data-override={override ? 'true' : undefined}
      title={inherited ? t('thr.mtx.inherited') : override ? t('thr.mtx.override') : undefined}
      className={cn('inline-flex items-center justify-center gap-1 tabular-nums',
        override ? 'font-bold text-primary' : inherited ? 'text-muted-foreground' : 'text-foreground')}>
      {override && <span aria-hidden="true" className="size-1.5 rounded-full bg-primary" />}
      {t('thr.mtx.days', value)}
    </span>
  )
}

function ReAlertCell({ hours, override, inherited, t }) {
  return (
    <span className={cn('tabular-nums', override ? 'font-bold text-primary' : inherited ? 'text-muted-foreground' : 'text-foreground')}
      title={inherited ? t('thr.mtx.inherited') : undefined}>
      {t('thr.mtx.hours', hours)}
    </span>
  )
}
