import { useCallback, useEffect, useRef, useState } from 'react'
import { ArrowRight, ChevronRight, ShieldCheck, TrendingDown } from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { GRADE_TONE, distribution } from './tlsGradeModel.js'

/** Dağılım çubuğu dolgu rengi (yalnız görsel; sayı ve harf metinde de var). */
const BAR_FILL = {
  'A+': 'bg-success', A: 'bg-success/70', B: 'bg-warning', C: 'bg-orange-500', D: 'bg-destructive/70', F: 'bg-destructive',
}
const PERIODS = [7, 30, 90]

/**
 * Tüm Sertifikalar — "TLS notu dağılımı" şeridi (2026-10-10): A+ … F sayıları (tablonun facet'inden — süzgeçlerle
 * tutarlı, ek istek yok), tıklayınca o nota süzer (yeniden tıklama süzgeci kaldırır), oransal çubuk ve "Son düşüşler"
 * penceresi (`/api/tls-grade/drops`: kapsamdaki düşüşler + TLS profili tarama kapsaması).
 *
 * <p>Mobil: çipler sarar, "Son düşüşler" telefonda tam genişlik; pencere telefonda tam ekrana yakın. Test kancaları:
 * `data-slot="tls-grade-overview"`, `tls-grade-chip` (+ `data-grade`, `aria-pressed`), `tls-grade-drops-open`,
 * `tls-grade-drop-row`.
 *
 * @param facets   tablo yanıtının `facets` nesnesi (`grades`)
 * @param value    seçili not süzgeci ('' = yok)
 * @param onSelect (grade|'') → tablo süzgeci
 * @param onOpenCert alan adı → sertifika penceresi (Sağlık sekmesi)
 */
export default function TlsGradeOverview({ facets, value = '', onSelect, onOpenCert }) {
  const t = useT()
  const [dropsOpen, setDropsOpen] = useState(false)
  const dist = distribution(facets)
  const total = dist.reduce((s, d) => s + d.count, 0)
  const none = Number(facets?.grades?.none) || 0
  if (!facets?.grades) return null

  return (
    <Card data-slot="tls-grade-overview" className="mb-2.5 gap-0 rounded-lg py-0 shadow-none">
      <div className="flex flex-col gap-2.5 p-3 sm:p-4">
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex min-w-0 items-center gap-2">
            <ShieldCheck aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
            <h3 className="m-0 text-sm font-semibold">{t('tlsg.ov.title')}</h3>
            <span className="text-xs text-muted-foreground">{t('tlsg.ov.total', total)}</span>
          </div>
          <Button type="button" variant="outline" size="sm" data-slot="tls-grade-drops-open"
            className="w-full sm:w-auto max-md:h-10 pointer-coarse:h-10" onClick={() => setDropsOpen(true)}>
            <TrendingDown aria-hidden="true" /> {t('tlsg.ov.drops')}
          </Button>
        </div>

        {/* Oransal BİLEŞİM çubuğu (ilerleme değil) — süs; aynı bilgi çiplerde metin olarak var. Dilimler sayıyla orantılı
            büyür (flex-grow), ince ayraçla ayrılır. */}
        {total > 0 && (
          <div aria-hidden="true" data-slot="tls-grade-bar" className="flex h-2 w-full gap-px overflow-hidden rounded-full bg-muted">
            {dist.filter((d) => d.count > 0).map((d) => (
              <span key={d.grade} data-grade={d.grade} className={cn('h-full min-w-1 basis-0', BAR_FILL[d.grade])} style={{ flexGrow: d.count }} />
            ))}
          </div>
        )}

        <div role="group" aria-label={t('tlsg.ov.filterGroup')} className="flex flex-wrap gap-1.5">
          {dist.map((d) => {
            const active = value === d.grade
            return (
              <Button key={d.grade} type="button" variant="outline" size="sm" data-slot="tls-grade-chip" data-grade={d.grade}
                aria-pressed={active} aria-label={t('tlsg.ov.chipAria', d.grade, d.count)}
                disabled={d.count === 0 && !active}
                onClick={() => onSelect?.(active ? '' : d.grade)}
                className={cn('h-8 gap-1.5 rounded-full px-2.5 max-md:h-10 pointer-coarse:h-10', active && 'border-primary ring-2 ring-primary/30')}>
                <span className={cn('inline-flex min-w-7 justify-center rounded-md border px-1 text-xs font-extrabold', GRADE_TONE[d.grade])}>
                  {d.grade}
                </span>
                <span className="text-xs tabular-nums">{d.count}</span>
              </Button>
            )
          })}
          {none > 0 && (
            <Button type="button" variant="outline" size="sm" data-slot="tls-grade-chip" data-grade="none"
              aria-pressed={value === 'none'} aria-label={t('tlsg.ov.chipNoneAria', none)}
              onClick={() => onSelect?.(value === 'none' ? '' : 'none')}
              className={cn('h-8 gap-1.5 rounded-full px-2.5 text-xs text-muted-foreground max-md:h-10 pointer-coarse:h-10',
                value === 'none' && 'border-primary ring-2 ring-primary/30')}>
              {t('tlsg.filterNone')} <span className="tabular-nums">{none}</span>
            </Button>
          )}
        </div>
      </div>
      {dropsOpen && <TlsGradeDropsDialog open onClose={() => setDropsOpen(false)} onOpenCert={onOpenCert} />}
    </Card>
  )
}

/** "Son düşüşler" penceresi — dönem seçici (7/30/90 gün), kapsama özeti, düşüş satırları. */
export function TlsGradeDropsDialog({ open, onClose, onOpenCert }) {
  const t = useT()
  const [days, setDays] = useState(30)
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const seq = useRef(0)

  const load = useCallback(async (d) => {
    const s = ++seq.current
    setError(null)
    setData(null)
    try {
      const res = await api.getTlsGradeDrops(d)
      if (s !== seq.current) return
      if (res?.success) setData(res.data)
      else setError(res?.error || t('tlsg.ov.loadError'))
    } catch (e) {
      if (s === seq.current) setError(e?.message || t('tlsg.ov.loadError'))
    }
  }, [t])

  useEffect(() => { if (open) load(days) }, [open, days, load])

  const rows = Array.isArray(data?.rows) ? data.rows : []
  const cov = data?.coverage

  return (
    <ModalShell open={open} onClose={onClose} title={t('tlsg.ov.dropsTitle')} icon={TrendingDown} size="lg" scrollBody>
      <div data-slot="tls-grade-drops" className="flex min-w-0 flex-col gap-3">
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
          <p className="m-0 text-sm text-muted-foreground">{t('tlsg.ov.dropsDesc', days)}</p>
          <SegmentedControl value={days} onChange={setDays} ariaLabel={t('tlsg.ov.period')}
            options={PERIODS.map((p) => ({ value: p, label: t('tlsg.ov.periodDays', p) }))} />
        </div>
        {cov && (
          <p data-slot="tls-grade-coverage" className="m-0 rounded-lg bg-muted/60 px-3 py-2 text-xs leading-relaxed">
            {t('tlsg.ov.coverage', cov.ok + cov.partial, cov.endpoints, cov.failed, cov.pending)}
            {cov.latest_probe_at ? ` · ${t('tlsg.ov.latestProbe', formatDateSec(cov.latest_probe_at))}` : ''}
          </p>
        )}
        {error ? (
          <AlertBanner tone="danger" title={t('tlsg.ov.dropsTitle')}>{error}</AlertBanner>
        ) : !data ? (
          <LoadingBlock label={t('tlsg.sec.loading')} />
        ) : rows.length === 0 ? (
          <StatusBlock tone="success" icon={ShieldCheck} title={t('tlsg.ov.dropsEmpty', days)} />
        ) : (
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {rows.map((r, i) => (
              <li key={`${r.domain}-${r.at}-${i}`} data-slot="tls-grade-drop-row" data-domain={r.domain}
                className="flex min-w-0 flex-col gap-2 rounded-lg border p-2.5 sm:flex-row sm:items-center">
                <div className="min-w-0 flex-1">
                  <p className="m-0 font-mono text-sm font-semibold break-all">{r.domain}</p>
                  <div className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                    <Badge variant="outline" className={cn('font-bold', GRADE_TONE[r.from])}>{r.from}</Badge>
                    <ArrowRight aria-hidden="true" className="size-3.5" />
                    <Badge variant="outline" className={cn('font-bold', GRADE_TONE[r.to])}>{r.to}</Badge>
                    <span>{formatDateSec(r.at)}</span>
                    {r.team_name && <span>· {r.team_name}</span>}
                    {r.recovered
                      ? <Badge variant="outline" className="border-success/30 bg-success/10 text-success">{t('tlsg.ov.recovered')}</Badge>
                      : r.current && <span>· {t('tlsg.ov.current', r.current)}</span>}
                  </div>
                  {Array.isArray(r.reasons) && r.reasons.length > 0 && (
                    <p className="m-0 mt-1 text-xs [overflow-wrap:anywhere]">
                      {r.reasons.slice(0, 3).map((c) => t(`tlsg.reason.${c}.title`)).join(' · ')}
                    </p>
                  )}
                </div>
                {onOpenCert && (
                  <Button type="button" variant="ghost" size="sm" className="self-start sm:self-center max-md:h-10 pointer-coarse:h-10"
                    aria-label={t('a11y.rowAction', r.domain, t('tlsg.ov.openCert'))}
                    onClick={() => { onClose?.(); onOpenCert(r.domain) }}>
                    {t('tlsg.ov.openCert')} <ChevronRight aria-hidden="true" />
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </ModalShell>
  )
}
