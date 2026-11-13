import { useMemo } from 'react'
import { Landmark, Layers } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import { caDistribution, statsNum, statsPct, tierDistribution } from './statsInsights.js'
import { TierBadge } from './statsUi.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

/**
 * Sağlayıcı (CA) ve katman dağılımı (2026-11-13, İstatistikler #7). Satır tıklaması tabloyu süzer — sağlayıcı satırı
 * mevcut sağlayıcı süzgecini (`st_iss`) o sağlayıcıya, katman satırı katman süzgecini (`st_tier`) ayarlar; seçili satıra
 * yeniden basmak kaldırır. "Diğer" ve adı okunamayan (hatalı) sertifikalar tıklanmaz. Sağlayıcı adları kırpılmaz, sarar.
 * Test kancaları: `data-slot="stats-composition"`, `stats-ca-row` (+ `data-ca`), `stats-tier-row` (+ `data-tier`).
 */
export default function CompositionCard({ certs, issuers, onIssuer, tier, onTier }) {
  const t = useT()
  const ca = useMemo(() => caDistribution(certs, 8), [certs])
  const tiers = useMemo(() => tierDistribution(certs), [certs])
  const total = (certs || []).length
  const share = (n) => (total > 0 ? Math.round((n * 1000) / total) / 10 : null)
  const caSel = issuers?.length === 1 ? issuers[0] : null
  return (
    <Card data-slot="stats-composition" className="min-w-0 gap-3 py-4 shadow-xs">
      <CardHeader className="gap-1 px-4 sm:px-6">
        <CardTitle role="heading" aria-level={3} className="flex items-center gap-2 text-base"><Landmark aria-hidden="true" className="size-4 text-primary" />{t('stx.compTitle')}</CardTitle>
        <CardDescription>{t('stx.compDesc')}</CardDescription>
      </CardHeader>
      <CardContent className="min-w-0 px-4 @container sm:px-6">
        {/* Sütun sayısı KAP genişliğinden: kart yarım genişlikteyken iki dar sütun katman adını harf harf kırıyordu */}
        <div className="grid min-w-0 grid-cols-1 gap-4 @xl:grid-cols-2">
        <div className="flex min-w-0 flex-col gap-1">
          <h4 className="m-0 flex items-center gap-1.5 text-xs font-semibold text-muted-foreground"><Landmark aria-hidden="true" className="size-3.5" />{t('stx.compCa')}</h4>
          <ul className="m-0 flex min-w-0 list-none flex-col gap-0.5 p-0">
            {ca.rows.map((r) => (
              <li key={r.name} className="min-w-0">
                <Row slot="stats-ca-row" dataKey={{ 'data-ca': r.name }} pressed={caSel === r.name}
                  label={<span className="[overflow-wrap:anywhere]">{r.name}</span>} count={r.count} pct={share(r.count)}
                  name={t('stx.compFilter', r.name, statsNum(r.count))}
                  onClick={() => onIssuer(caSel === r.name ? [] : [r.name])} />
              </li>
            ))}
            {ca.other > 0 && (
              <li className="min-w-0"><Row label={t('stx.compOther', statsNum(ca.otherNames))} count={ca.other} pct={share(ca.other)} muted /></li>
            )}
            {ca.unknown > 0 && (
              <li className="min-w-0"><Row label={t('stx.compUnknown')} count={ca.unknown} pct={share(ca.unknown)} muted /></li>
            )}
          </ul>
        </div>
        <div className="flex min-w-0 flex-col gap-1">
          <h4 className="m-0 flex items-center gap-1.5 text-xs font-semibold text-muted-foreground"><Layers aria-hidden="true" className="size-3.5" />{t('stx.compTier')}</h4>
          <ul className="m-0 flex min-w-0 list-none flex-col gap-0.5 p-0">
            {tiers.map((r) => {
              const lbl = r.tier === 0 ? t('tier.descNone') : `T${r.tier} — ${t(`tier.desc${r.tier}`)}`
              return (
                <li key={r.tier} className="min-w-0">
                  <Row slot="stats-tier-row" dataKey={{ 'data-tier': r.tier }} pressed={tier === r.tier} disabled={r.count === 0 && tier !== r.tier}
                    label={(
                      <span className="inline-flex min-w-0 items-center gap-2">
                        {r.tier === 0 ? <Badge variant="outline" className="rounded px-1.5 text-[11px] font-extrabold">?</Badge> : <TierBadge tier={r.tier} />}
                        <span className="[overflow-wrap:anywhere]">{r.tier === 0 ? t('tier.descNone') : t(`tier.desc${r.tier}`)}</span>
                      </span>
                    )}
                    count={r.count} pct={share(r.count)} name={t('stx.compFilter', lbl, statsNum(r.count))}
                    onClick={() => onTier(tier === r.tier ? null : r.tier)} />
                </li>
              )
            })}
          </ul>
        </div>
        </div>
      </CardContent>
    </Card>
  )
}

function Row({ slot, dataKey, label, count, pct, name, onClick, pressed = false, disabled = false, muted = false }) {
  const body = (
    <span className="grid w-full min-w-0 grid-cols-[minmax(0,1fr)_auto_3.5rem] items-center gap-x-2.5 gap-y-1">
      <span className="min-w-0 text-left text-sm">{label}</span>
      <b className="text-right text-sm tabular-nums">{statsNum(count)}</b>
      <span className="text-right text-xs text-muted-foreground tabular-nums">{statsPct(pct)}</span>
      <span className="col-span-3"><ProgressBar value={pct ?? 0} max={100} size="sm" decorative className={cn(muted && 'opacity-50')} /></span>
    </span>
  )
  if (muted || !onClick) {
    return <div className="rounded-md px-2 py-1.5 text-muted-foreground">{body}</div>
  }
  return (
    <Button type="button" variant="ghost" data-slot={slot} {...dataKey} aria-pressed={pressed} aria-label={name} disabled={disabled}
      onClick={onClick}
      className={cn('h-auto min-h-10 w-full rounded-md px-2 py-1.5 font-normal whitespace-normal', pressed && 'bg-primary/10 ring-2 ring-primary/40')}>
      {body}
    </Button>
  )
}
