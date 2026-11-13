import { useMemo } from 'react'
import { ShieldAlert } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { TLS_GRADES } from '../tlsgrade/tlsGradeCodes.js'
import { GRADE_TONE } from '../tlsgrade/tlsGradeModel.js'
import { SEC_FLAGS } from './statsModel.js'
import { securityCounts, statsNum } from './statsInsights.js'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

/**
 * Güvenlik duruşu (2026-11-13, İstatistikler #5): TLS yapılandırma notu dağılımı (A+ … F) + kırık zincir, güvenilmeyen CA,
 * iptal, kurulum uyuşmazlığı ve zayıf anahtar sayıları — sertifika satırlarındaki SUNUCU hükümlerinden (ikinci kural yok;
 * zayıf anahtar `keySizeStatus` aynası). Her çip tabloyu süzer (`st_sec`; aynı çipe tekrar basmak kaldırır). Notsuz
 * sertifikalar (elle yüklenen, hiç kontrol edilmemiş, son kontrolü başarısız) AYRI çip — asla "iyi" sayılmaz.
 * Test kancaları: `data-slot="stats-security"`, çip `stats-sec-chip` + `data-sec` + `aria-pressed`.
 */
export default function SecurityPostureCard({ certs, sec, onSec }) {
  const t = useT()
  const sc = useMemo(() => securityCounts(certs), [certs])
  const cp = { sec, onSec, t }
  return (
    <Card data-slot="stats-security" className="min-w-0 gap-3 py-4 shadow-xs">
      <CardHeader className="gap-1 px-4 sm:px-6">
        <CardTitle role="heading" aria-level={3} className="flex items-center gap-2 text-base"><ShieldAlert aria-hidden="true" className="size-4 text-primary" />{t('stx.secTitle')}</CardTitle>
        <CardDescription>{t('stx.secDesc', statsNum(sc.graded), statsNum(sc.total))}</CardDescription>
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-3 px-4 sm:px-6">
        <div className="flex min-w-0 flex-col gap-1.5">
          <span className="text-xs font-semibold text-muted-foreground">{t('stx.secGrades')}</span>
          <div role="group" aria-label={t('stx.secGrades')} className="flex min-w-0 flex-wrap gap-2">
            {TLS_GRADES.map((g) => <Chip {...cp} key={g} value={`g:${g}`} label={g} count={sc.grades[g]} tone={GRADE_TONE[g]} />)}
            <Chip {...cp} value="g:none" label="?" count={sc.grades.none} tone="border-border bg-muted text-muted-foreground" sub={t('stx.secUngraded')} />
          </div>
        </div>
        <div className="flex min-w-0 flex-col gap-1.5">
          <span className="text-xs font-semibold text-muted-foreground">{t('stx.secFlags')}</span>
          <div role="group" aria-label={t('stx.secFlags')} className="flex min-w-0 flex-wrap gap-2">
            {SEC_FLAGS.map((f) => (
              <Chip {...cp} key={f} value={f} label={t(`stx.sec.${f}`)} count={sc.flags[f]}
                tone={sc.flags[f] > 0 ? 'border-destructive/40 bg-destructive/10 text-destructive' : 'border-border bg-muted text-muted-foreground'} />
            ))}
          </div>
        </div>
        <p data-slot="stats-sec-summary" className="m-0 text-xs text-muted-foreground">
          {sc.flagged > 0 ? t('stx.secFlagged', statsNum(sc.flagged)) : t('stx.secNoFlags')}
          {sc.ungraded > 0 && ` · ${t('stx.secUngradedNote', statsNum(sc.ungraded))}`}
        </p>
      </CardContent>
    </Card>
  )
}

function Chip({ sec, onSec, t, value, label, count, tone, sub }) {
  const pressed = sec === value
  return (
    <Button type="button" variant="outline" data-slot="stats-sec-chip" data-sec={value} aria-pressed={pressed}
      disabled={count === 0 && !pressed} onClick={() => onSec(pressed ? null : value)}
      aria-label={t('stx.secChipName', label, statsNum(count))}
      className={cn('h-auto min-h-10 gap-2 rounded-lg px-3 py-1.5 font-normal whitespace-normal', pressed && 'border-primary ring-2 ring-primary/30')}>
      <span className={cn('inline-flex min-w-8 items-center justify-center rounded-md border px-1.5 py-0.5 text-xs font-bold', tone)}>{label}</span>
      <b className="tabular-nums">{statsNum(count)}</b>
      {sub && <span className="text-xs text-muted-foreground">{sub}</span>}
    </Button>
  )
}
