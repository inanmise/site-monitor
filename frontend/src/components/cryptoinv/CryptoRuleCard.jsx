import { useState } from 'react'
import { BookOpen } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import CollapsibleSection from '../ui/CollapsibleSection.jsx'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { CategoryBadge, BandBadge } from './CryptoBadges.jsx'
import { CATEGORIES } from './cryptoInventoryModel.js'

/**
 * "Sınıflandırma ve öncelik nasıl hesaplanır" (2026-10-10) — denetçiye/düzenleyiciye yöntemi gösterir. Puan tablosu
 * SUNUCUDAN (`rule`, PqcMigrationPriority sabitlerinden üretilir) çizilir; burada ikinci bir kopya yok. Varsayılan kapalı.
 */
export default function CryptoRuleCard({ rule, thresholds }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const r = rule || {}
  const rows = [
    ...Object.entries(r.exposure || {}).map(([k, v]) => ['exposure', k === 'none' ? t('cinv.tierNone') : t(`cinv.tier.${k}`), v]),
    ...Object.entries(r.strength || {}).map(([k, v]) => ['strength', t(`cinv.cat.${k}`), v]),
    ...(r.renewal || []).map((s) => ['renewal', t('cinv.rule.renewalCase', s.max_days), s.points]),
    ...(r.hndl ? [['hndl', t('cinv.rule.hndlExternal'), r.hndl.external], ['hndl', t('cinv.rule.hndlNoPfs'), r.hndl.no_pfs]] : []),
  ]
  return (
    <CollapsibleSection data-slot="cinv-rules" open={open} onOpenChange={setOpen} icon={BookOpen} label={t('cinv.rulesTitle')}
      hint={t('cinv.rulesHint')} contentClassName="pt-2.5">
      <div className="flex min-w-0 flex-col gap-4 rounded-[10px] border bg-card p-3 sm:p-4">
        <section className="min-w-0">
          <h4 className="mt-0 mb-2 text-sm font-semibold">{t('cinv.rulesCatTitle')}</h4>
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {CATEGORIES.map((c) => (
              <li key={c} className="flex min-w-0 flex-col gap-1 sm:flex-row sm:items-start sm:gap-3">
                <span className="shrink-0 sm:w-44"><CategoryBadge category={c} /></span>
                <span className="min-w-0 text-sm text-muted-foreground">{t(`cinv.catDesc.${c}`, thresholds?.rsa_2030_min_bits ?? 3072)}</span>
              </li>
            ))}
          </ul>
        </section>
        <section className="min-w-0">
          <h4 className="mt-0 mb-1 text-sm font-semibold">{t('cinv.rulesScoreTitle', r.max ?? 100)}</h4>
          <p className="mt-0 mb-2 text-sm text-muted-foreground">{t('cinv.rulesScoreIntro')}</p>
          <div className="overflow-hidden rounded-lg border">
            <Table className="text-[0.88em]" data-slot="cinv-rule-table">
              <TableHeader className="bg-muted/50">
                <TableRow>
                  <TableHead>{t('cinv.xl.ruleComponent')}</TableHead>
                  <TableHead>{t('cinv.xl.ruleCase')}</TableHead>
                  <TableHead className="text-right">{t('cinv.xl.rulePoints')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map(([comp, label, pts], i) => (
                  <TableRow key={`${comp}-${i}`}>
                    <TableCell className="whitespace-normal">{t(`cinv.rule.${comp}`)}</TableCell>
                    <TableCell className="whitespace-normal">{label}</TableCell>
                    <TableCell className="text-right font-semibold tabular-nums">{pts}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <div className="mt-3 flex flex-wrap items-center gap-2 text-sm">
            <span className="text-muted-foreground">{t('cinv.rulesBands')}</span>
            {(r.bands || []).map((b) => (
              <span key={b.band} className="inline-flex items-center gap-1"><BandBadge band={b.band} /><span className="tabular-nums">≥ {b.min}</span></span>
            ))}
            <span className="inline-flex items-center gap-1"><BandBadge band="DONE" /><span>{t('cinv.rulesDone')}</span></span>
          </div>
        </section>
        <p className="m-0 text-xs text-muted-foreground">{t('cinv.rulesSources', thresholds?.sunset ?? '2030-12-31')}</p>
      </div>
    </CollapsibleSection>
  )
}
