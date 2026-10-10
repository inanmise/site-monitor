import { useMemo } from 'react'
import { Atom, Fingerprint, KeyRound, ListFilter } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatPercent } from '../../i18n/dateLocale.js'
import { ProgressBar } from '../ui/Progress.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { ChartContainer, ChartTooltip, ChartTooltipContent, Cell, Pie, PieChart } from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'
import { BUCKET_TONE, HASH_TONE, PQC_STATES, bucketLabel, hashLabel, readiness } from './cryptoInventoryModel.js'

/**
 * Dağılım kartları (2026-10-10): PQC hazırlık halkası + göstergesi, algoritma × boy çubukları, imza özeti çubukları +
 * SHA-1/MD5 kalıntı paneli. Her satır bir DÜĞMEdir: geçiş listesini o değere süzer (`aria-pressed`). Grafik görsel
 * özettir; aynı sayılar yanındaki listede metin olarak durur (ekran okuyucu listeyi okur).
 */

/** Ton → ProgressBar tonu / dolgu değişkeni (bilinmeyen gri). */
const BAR = {
  bad: { tone: 'crit' }, warn: { tone: 'warn' }, ok: { tone: undefined }, pqc: { tone: 'ok' },
  muted: { tone: undefined, wrap: '[--pg-fill:var(--muted-foreground)]' },
}

const PQC_COLOR = {
  VULNERABLE: 'var(--chart-3)', HYBRID: 'var(--chart-5)', PQC: 'var(--chart-2)', UNKNOWN: 'var(--muted-foreground)',
}

function CardShell({ slot, icon: Icon, title, description, children, className }) {
  return (
    <Card data-slot={slot} className={cn('min-w-0 gap-3 py-4 shadow-xs', className)}>
      <CardHeader className="gap-1 px-4 sm:px-5">
        <CardTitle role="heading" aria-level={3} className="flex items-center gap-2 text-base">
          <Icon aria-hidden="true" className="size-4 shrink-0 text-primary" />{title}
        </CardTitle>
        {description && <CardDescription>{description}</CardDescription>}
      </CardHeader>
      <CardContent className="min-w-0 px-2 sm:px-3">{children}</CardContent>
    </Card>
  )
}

/** Süzgeç satırı: etiket · çubuk · sayı · pay — düğme, seçiliyse basılı. */
function FilterRow({ label, mono, count, share, max, tone, active, onClick, ariaLabel, slot, dataKey }) {
  const bar = BAR[tone] || BAR.ok
  return (
    <li className="min-w-0">
      <Button type="button" variant="ghost" aria-pressed={active} aria-label={ariaLabel} onClick={onClick}
        data-slot={slot} data-key={dataKey}
        className={cn('grid h-auto min-h-10 w-full grid-cols-[minmax(0,8.5rem)_minmax(3rem,1fr)_auto_3.25rem] items-center gap-x-2.5 px-2 py-1.5 text-left text-sm font-normal whitespace-normal sm:min-h-9',
          active && 'bg-accent font-semibold')}>
        <span className={cn('min-w-0 leading-tight break-words', mono && 'font-medium')} title={label}>{label}</span>
        <span className={cn('min-w-0', bar.wrap)}><ProgressBar value={count} max={max} size="sm" tone={bar.tone} decorative /></span>
        <span className="font-semibold tabular-nums">{count}</span>
        <span className="text-right text-xs text-muted-foreground tabular-nums">{formatPercent(share)}</span>
      </Button>
    </li>
  )
}

export function PqcReadinessCard({ summary, active, onPick }) {
  const t = useT()
  const r = readiness(summary)
  const by = summary?.by_pqc || {}
  const config = useMemo(() => Object.fromEntries(PQC_STATES.map((k) => [k, { label: t(`cinv.pqc.${k}`), color: PQC_COLOR[k] }])), [t])
  const data = PQC_STATES.map((k) => ({ key: k, count: Number(by[k]) || 0 })).filter((d) => d.count > 0)
  return (
    <CardShell slot="cinv-pqc" icon={Atom} title={t('cinv.pqcTitle')} description={t('cinv.pqcDesc')}>
      {r.total === 0 ? <StatusBlock tone="neutral" title={t('cinv.noData')} className="py-6 md:py-6" /> : (
        <div className="@container min-w-0">
          <div className="flex min-w-0 flex-col items-center gap-3 px-2 @md:flex-row @md:items-center @md:gap-5">
            <div aria-hidden="true" className="relative size-44 shrink-0">
              <ChartContainer config={config} className="aspect-square size-44">
                <PieChart>
                  <ChartTooltip cursor={false} content={<ChartTooltipContent nameKey="key" hideLabel />} />
                  <Pie data={data} dataKey="count" nameKey="key" innerRadius={58} outerRadius={84} paddingAngle={data.length > 1 ? 2 : 0}
                    stroke="var(--card)" strokeWidth={2} isAnimationActive={false}>
                    {data.map((d) => <Cell key={d.key} fill={`var(--color-${d.key})`} />)}
                  </Pie>
                </PieChart>
              </ChartContainer>
              <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center text-center">
                <span className="text-2xl leading-none font-extrabold tabular-nums">{formatPercent(r.vulnerablePct)}</span>
                <span className="mt-1 max-w-24 text-[0.7rem] leading-tight text-muted-foreground">{t('cinv.pqcCenter')}</span>
              </div>
            </div>
            <ul className="m-0 flex w-full min-w-0 list-none flex-col gap-0.5 p-0">
              {PQC_STATES.map((k) => {
                const n = Number(by[k]) || 0
                return (
                  <li key={k} className="min-w-0">
                    <Button type="button" variant="ghost" aria-pressed={active === k} onClick={() => onPick(k)} data-slot="cinv-pqc-row" data-key={k}
                      aria-label={t('cinv.filterBy', t(`cinv.pqc.${k}`), n)}
                      className={cn('grid h-auto min-h-10 w-full grid-cols-[auto_minmax(0,1fr)_auto_3.25rem] items-center gap-x-2.5 px-2 py-1.5 text-left text-sm font-normal whitespace-normal sm:min-h-9',
                        active === k && 'bg-accent font-semibold', n === 0 && 'text-muted-foreground')}>
                      <span aria-hidden="true" className="size-3 rounded-sm" style={{ background: PQC_COLOR[k] }} />
                      <span className="min-w-0 leading-tight">{t(`cinv.pqc.${k}`)}</span>
                      <span className="font-semibold tabular-nums">{n}</span>
                      <span className="text-right text-xs text-muted-foreground tabular-nums">{formatPercent(r.total ? Math.round((1000 * n) / r.total) / 10 : 0)}</span>
                    </Button>
                  </li>
                )
              })}
            </ul>
          </div>
        </div>
      )}
      <p className="mx-2 mt-3 mb-0 text-xs text-muted-foreground">{t('cinv.kexNote')}</p>
    </CardShell>
  )
}

export function KeyDistributionCard({ algorithms, active, onPick }) {
  const t = useT()
  const list = algorithms || []
  const max = Math.max(1, ...list.map((a) => a.count))
  return (
    <CardShell slot="cinv-keys" icon={KeyRound} title={t('cinv.keyTitle')} description={t('cinv.keyDesc')}>
      {list.length === 0 ? <StatusBlock tone="neutral" title={t('cinv.noData')} className="py-6 md:py-6" /> : (
        <ul className="m-0 flex min-w-0 list-none flex-col gap-0.5 p-0">
          {list.map((a) => (
            <FilterRow key={a.bucket} slot="cinv-key-row" dataKey={a.bucket} label={bucketLabel(a.bucket, t)} mono count={a.count}
              share={a.share} max={max} tone={BUCKET_TONE[a.bucket]} active={active === a.bucket} onClick={() => onPick(a.bucket)}
              ariaLabel={t('cinv.filterBy', bucketLabel(a.bucket, t), a.count)} />
          ))}
        </ul>
      )}
    </CardShell>
  )
}

export function SignatureCard({ signatures, remnants, activeHash, remnantOn, onPickHash, onRemnants, className }) {
  const t = useT()
  const list = signatures || []
  const max = Math.max(1, ...list.map((s) => s.count))
  const rem = remnants || {}
  const items = [
    ['SHA1_LEAF', rem.sha1_leaf], ['MD5_LEAF', rem.md5_leaf], ['SHA1_INTERMEDIATE', rem.sha1_intermediate], ['MD5_INTERMEDIATE', rem.md5_intermediate],
  ]
  const affected = Number(rem.affected) || 0
  return (
    <CardShell slot="cinv-sig" icon={Fingerprint} title={t('cinv.sigTitle')} description={t('cinv.sigDesc')} className={className}>
      {/* Geniş kapta (kart iki sütuna yayılınca) çubuklar solda, kalıntı paneli sağda */}
      <div className="@container min-w-0">
        <div className="grid min-w-0 grid-cols-1 gap-3 @2xl:grid-cols-2 @2xl:items-start">
          {list.length === 0 ? <StatusBlock tone="neutral" title={t('cinv.noData')} className="py-6 md:py-6" /> : (
            <ul className="m-0 flex min-w-0 list-none flex-col gap-0.5 p-0">
              {list.map((s) => (
                <FilterRow key={s.hash} slot="cinv-hash-row" dataKey={s.hash} label={hashLabel(s.hash, t)} count={s.count} share={s.share}
                  max={max} tone={HASH_TONE[s.hash]} active={activeHash === s.hash} onClick={() => onPickHash(s.hash)}
                  ariaLabel={t('cinv.filterBy', hashLabel(s.hash, t), s.count)} />
              ))}
            </ul>
          )}
          <div data-slot="cinv-remnants" data-affected={affected}
            className={cn('mx-2 rounded-lg border p-3', affected > 0 ? 'border-destructive/40 bg-destructive/5' : 'bg-muted/30')}>
            <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
              <span className="text-sm font-semibold">{t('cinv.remTitle', affected)}</span>
              <Button type="button" variant="outline" size="sm" aria-pressed={remnantOn} onClick={onRemnants} disabled={affected === 0 && !remnantOn}
                className="min-h-10 sm:min-h-8">
                <ListFilter aria-hidden="true" />{remnantOn ? t('cinv.remHide') : t('cinv.remShow')}
              </Button>
            </div>
            <dl className="m-0 grid grid-cols-2 gap-x-3 gap-y-1.5 text-sm">
              {items.map(([k, v]) => (
                <div key={k} className="flex min-w-0 flex-col">
                  <dt className="text-xs leading-tight text-muted-foreground">{t(`cinv.rem.${k}`)}</dt>
                  <dd className={cn('m-0 font-semibold tabular-nums', (Number(v) || 0) > 0 && 'text-destructive')}>{Number(v) || 0}</dd>
                </div>
              ))}
            </dl>
            <p className="mt-2 mb-0 text-xs text-muted-foreground">{t('cinv.remRootNote', Number(rem.sha1_root) || 0, Number(rem.chains_examined) || 0)}</p>
          </div>
        </div>
      </div>
    </CardShell>
  )
}
