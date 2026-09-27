import { useCallback, useState } from 'react'
import { formatPercent } from '../i18n/dateLocale.js'
import { HeartPulse, CalendarClock, Siren, Gauge, TrendingUp, TrendingDown, Minus } from 'lucide-react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { navigateTo } from '../utils/navigate.js'
import TeamBadge from './ui/TeamBadge.jsx'
import { ProgressBar } from './ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

/**
 * İstatistik sayfası yönetici özeti (2026-09-12, zenginleştirme #20): dört KPI + takım karşılaştırma
 * çubuğu (sağlık % — en kötü üstte) + "son 30 gün / önceki 30 gün" alarm deltası. Veri /api/stats/executive.
 */
// Ton (ok|warn|bad) → değer ve simge rengi. KPI kutucuğunda SOL RENK ŞERİDİ YOK (kullanıcı kuralı 2026-09-26; eski
// .exs-kpi border-left'i) — durum sayının rengi + `data-tone`.
const TONE_INK = { ok: 'text-success', warn: 'text-amber-600 dark:text-amber-400', bad: 'text-destructive' }
const BAR_TONE = { ok: 'ok', warn: 'warn', bad: 'crit' }

export default function ExecutiveSummary({ onOpenTeam }) {
  const t = useT()
  const [data, setData] = useState(null)
  const load = useCallback(async () => {
    try { const r = await api.getExecutiveStats(); if (r?.success && r.data) setData(r.data) } catch { /* özet süs */ }
  }, [])
  useVisibleInterval(load, 300_000, true)
  if (!data) return null

  const c = data.certs || {}, a = data.alerts || {}, s = data.sla || {}
  const teams = data.teams || []
  const health = c.health_pct
  const tone = (v, warn, bad, invert = false) => v == null ? '' : invert
    ? (v >= warn ? 'ok' : v >= bad ? 'warn' : 'bad')
    : (v === 0 ? 'ok' : v <= warn ? 'warn' : 'bad')
  const Delta = ({ n }) => n == null ? null : (
    <span data-slot="exs-delta" className={cn('inline-flex items-center gap-0.5 font-semibold text-muted-foreground', n > 0 && 'text-destructive', n < 0 && 'text-success')}
      title={t('exs.deltaTip', a.opened_last30 ?? 0, a.opened_prev30 ?? 0)}>
      {n > 0 ? <TrendingUp size={12} aria-hidden="true" /> : n < 0 ? <TrendingDown size={12} aria-hidden="true" /> : <Minus size={12} aria-hidden="true" />} {n > 0 ? `+${n}` : n} {t('exs.vsPrev')}
    </span>
  )
  // KPI kutucuğu — shadcn Button (outline): tıklayınca ilgili sayfaya gider.
  const Kpi = ({ tone: kt, icon: Icon, label, value, sub, onClick }) => (
    <Button type="button" variant="outline" data-slot="exs-kpi" data-tone={kt || undefined} onClick={onClick}
      className="h-auto flex-col items-start gap-0.5 rounded-[10px] px-3.5 py-3 text-left font-normal whitespace-normal shadow-none hover:border-primary">
      <Icon size={18} aria-hidden="true" className={cn('text-muted-foreground', TONE_INK[kt])} />
      <span className="text-[.74em] font-bold tracking-[.05em] text-muted-foreground uppercase">{label}</span>
      <b className={cn('text-[1.6em] leading-tight tabular-nums', TONE_INK[kt])}>{value}</b>
      <span className="flex flex-wrap items-center gap-1.5 text-[.78em] text-muted-foreground">{sub}</span>
    </Button>
  )

  return (
    <section data-slot="exs" className="mb-4 flex flex-col gap-3" aria-label={t('exs.title')}>
      <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2 xl:grid-cols-4">
        <Kpi tone={tone(health, 95, 90, true)} icon={HeartPulse} label={t('exs.health')} value={formatPercent(health)}
          sub={t('exs.healthSub', c.ok ?? 0, c.total ?? 0)} onClick={() => navigateTo('dashboard')} />
        <Kpi tone={tone(c.under30 ?? 0, 5, 15)} icon={CalendarClock} label={t('exs.under30')} value={c.under30 ?? 0}
          sub={t('exs.under30Sub', c.under7 ?? 0, c.expired ?? 0)} onClick={() => navigateTo('renewal')} />
        <Kpi tone={tone(a.open ?? 0, 3, 10)} icon={Siren} label={t('exs.alerts')} value={a.open ?? 0}
          sub={<>{t('exs.alertsSub', a.critical ?? 0)} <Delta n={a.delta} /></>} onClick={() => navigateTo('warnings')} />
        <Kpi tone={tone(s.breaches ?? 0, 2, 5)} icon={Gauge} label={t('exs.sla', s.target_pct ?? 99.9)} value={s.breaches ?? 0}
          sub={t('exs.slaSub', s.monitors ?? 0, data.window_days ?? 30)} onClick={() => navigateTo('uptime')} />
      </div>

      {teams.length > 1 && (
        <Card data-slot="exs-teams" className="gap-1.5 rounded-[10px] px-3.5 py-2.5 shadow-none">
          <div className="text-[.74em] font-bold tracking-[.05em] text-muted-foreground uppercase">{t('exs.teams')}</div>
          <div className="flex flex-col gap-1">
            {teams.map((tm) => {
              const h = tm.health_pct
              const ht = h == null ? null : h >= 95 ? 'ok' : h >= 90 ? 'warn' : 'bad'
              return (
                // Takım satırı — shadcn Button (ghost); telefonda ad + yüzde üstte, çubuk ve sayaçlar alt satırda
                <Button type="button" variant="ghost" key={tm.team_id ?? 'none'} data-slot="exs-team-row" data-tone={ht || undefined}
                  onClick={() => tm.team_id != null && onOpenTeam?.(tm.team_id)}
                  title={t('exs.teamTip', tm.ok, tm.under30, tm.expired, tm.error, tm.open_alerts)}
                  className="grid h-auto w-full grid-cols-[minmax(0,1fr)_56px] items-center gap-x-2.5 gap-y-1 rounded-lg px-1.5 py-1 text-left font-normal whitespace-normal sm:grid-cols-[minmax(140px,1fr)_2fr_56px_auto]">
                  <span className="min-w-0 truncate">{tm.team_name ? <TeamBadge teamId={tm.team_id} teamName={tm.team_name} as="span" /> : <em>{t('exs.noTeam')}</em>}</span>
                  <span className="col-span-2 row-start-2 w-full sm:col-span-1 sm:row-start-auto">
                    <ProgressBar value={h ?? 0} max={100} size="sm" decorative tone={BAR_TONE[ht]} />
                  </span>
                  <span className="col-start-2 row-start-1 text-right font-bold tabular-nums sm:col-start-auto sm:row-start-auto">{formatPercent(h)}</span>
                  <span className="col-span-2 text-[.78em] whitespace-nowrap text-muted-foreground sm:col-span-1">
                    {tm.total} · {tm.under30 > 0 && <b className="text-amber-600 dark:text-amber-400">{tm.under30}↓30g</b>} {tm.open_alerts > 0 && <b className="text-destructive">{tm.open_alerts}⚠</b>}
                  </span>
                </Button>
              )
            })}
          </div>
        </Card>
      )}
    </section>
  )
}
