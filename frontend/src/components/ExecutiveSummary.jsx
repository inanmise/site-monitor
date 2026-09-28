import { useCallback, useState } from 'react'
import { formatPercent } from '../i18n/dateLocale.js'
import { ArrowUpRight, Gauge, HeartPulse, Minus, Siren, TrendingDown, TrendingUp, Users } from 'lucide-react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { navigateTo } from '../utils/navigate.js'
import TeamBadge from './ui/TeamBadge.jsx'
import { ProgressBar } from './ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'

/**
 * İstatistik sayfası operasyon özeti (2026-09-12, zenginleştirme #20; 2026-09-28 shadcn + mobil web yeniden tasarımı):
 * üç GEZİNME kutucuğu (filo sağlığı → Pano, açık alarm + 30 gün deltası → Uyarılar, SLA ihlali → Uptime) + takım
 * karşılaştırması (sağlık %, en kötü üstte; satır tabloyu o takıma süzer). Veri /api/stats/executive.
 *
 * "30 gün altı" kutucuğu 2026-09-28'de kalktı: aynı sayı artık sayfanın üstündeki KPI kutucuklarında (30/14/7 gün) ve
 * TABLOYU süzüyor — iki ayrı "30 gün altı" (biri süzgeç, biri gezinme) aynı ekranda kafa karıştırıyordu; Yenileme/Vade
 * yolu "Yaklaşan bitişler" kartında.
 *
 * İlk yüklemede iskelet (yerleşim zıplamaz); uç başarısızsa bölüm hiç çizilmez (süs — sayfanın geri kalanı çalışır).
 * Kutucuklarda SOL RENK ŞERİDİ YOK (kullanıcı kuralı 2026-09-26) — durum sayının rengi + `data-tone`.
 */
const TONE_INK = { ok: 'text-success', warn: 'text-amber-600 dark:text-amber-400', bad: 'text-destructive' }
const BAR_TONE = { ok: 'ok', warn: 'warn', bad: 'crit' }

export default function ExecutiveSummary({ onOpenTeam }) {
  const t = useT()
  // undefined = yükleniyor · null = uç yanıt vermedi (bölüm çizilmez) · nesne = veri
  const [data, setData] = useState(undefined)
  const load = useCallback(async () => {
    try {
      const r = await api.getExecutiveStats()
      if (r?.success && r.data) setData(r.data)
      else setData((cur) => cur ?? null)
    } catch { setData((cur) => cur ?? null) /* özet süs — sayfa onsuz da çalışır */ }
  }, [])
  useVisibleInterval(load, 300_000, true)
  if (data === undefined) {
    return (
      <div data-slot="exs-skeleton" aria-busy="true" className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <span role="status" className="sr-only">{t('stv.loading')}</span>
        {[0, 1, 2].map((i) => <Skeleton key={i} className="h-28 rounded-xl" />)}
      </div>
    )
  }
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
  // Gezinme kutucuğu — shadcn Button (outline): tıklayınca ilgili sayfaya gider (köşedeki ok bunu söyler).
  const Kpi = ({ tone: kt, icon: Icon, label, value, sub, extra, onClick }) => (
    <Button type="button" variant="outline" data-slot="exs-kpi" data-tone={kt || undefined} onClick={onClick}
      className="group relative h-auto min-h-28 flex-col items-start justify-start gap-1 rounded-xl bg-card px-4 py-3.5 text-left font-normal whitespace-normal shadow-xs hover:border-primary hover:bg-card">
      <span className="flex w-full items-center gap-2">
        <Icon size={18} aria-hidden="true" className={cn('shrink-0 text-muted-foreground', TONE_INK[kt])} />
        <span className="min-w-0 flex-1 text-xs font-semibold text-muted-foreground">{label}</span>
        <ArrowUpRight aria-hidden="true" className="size-4 shrink-0 text-muted-foreground transition-colors group-hover:text-primary motion-reduce:transition-none" />
      </span>
      <b className={cn('text-2xl leading-tight font-extrabold tabular-nums', TONE_INK[kt])}>{value}</b>
      {extra}
      <span className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">{sub}</span>
    </Button>
  )

  return (
    <section data-slot="exs" className="flex min-w-0 flex-col gap-3" aria-label={t('exs.title')}>
      <div className="flex min-w-0 flex-col gap-0.5">
        <h2 data-slot="exs-title" className="m-0 text-base font-semibold">{t('exs.title')}</h2>
        <p className="m-0 text-sm text-muted-foreground">{t('stv.opsDesc')}</p>
      </div>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <Kpi tone={tone(health, 95, 90, true)} icon={HeartPulse} label={t('exs.health')} value={formatPercent(health)}
          extra={<span className="block w-full py-1"><ProgressBar value={health ?? 0} max={100} size="sm" decorative tone={BAR_TONE[tone(health, 95, 90, true)]} /></span>}
          sub={t('exs.healthSub', c.ok ?? 0, c.total ?? 0)} onClick={() => navigateTo('dashboard')} />
        <Kpi tone={tone(a.open ?? 0, 3, 10)} icon={Siren} label={t('exs.alerts')} value={a.open ?? 0}
          sub={<>{t('exs.alertsSub', a.critical ?? 0)} <Delta n={a.delta} /></>} onClick={() => navigateTo('warnings')} />
        <Kpi tone={tone(s.breaches ?? 0, 2, 5)} icon={Gauge} label={t('exs.sla', s.target_pct ?? 99.9)} value={s.breaches ?? 0}
          sub={t('exs.slaSub', s.monitors ?? 0, data.window_days ?? 30)} onClick={() => navigateTo('uptime')} />
      </div>

      {teams.length > 1 && (
        <Card data-slot="exs-teams" className="min-w-0 gap-2 py-3.5 shadow-xs">
          <CardHeader className="gap-1 px-4">
            <CardTitle role="heading" aria-level={3} className="flex items-center gap-2 text-sm"><Users aria-hidden="true" className="size-4 text-primary" />{t('stv.teamHealth')}</CardTitle>
            <CardDescription className="text-xs">{t('exs.teams')}</CardDescription>
          </CardHeader>
          <CardContent className="flex min-w-0 flex-col gap-0.5 px-2 sm:px-3">
            {teams.map((tm) => {
              const h = tm.health_pct
              const ht = h == null ? null : h >= 95 ? 'ok' : h >= 90 ? 'warn' : 'bad'
              return (
                // Takım satırı — shadcn Button (ghost); telefonda ad + yüzde üstte, çubuk ve sayaçlar alt satırda
                <Button type="button" variant="ghost" key={tm.team_id ?? 'none'} data-slot="exs-team-row" data-tone={ht || undefined}
                  onClick={() => tm.team_id != null && onOpenTeam?.(tm.team_id)}
                  title={t('exs.teamTip', tm.ok, tm.under30, tm.expired, tm.error, tm.open_alerts)}
                  className="grid h-auto min-h-10 w-full grid-cols-[minmax(0,1fr)_56px] items-center gap-x-3 gap-y-1 rounded-lg px-2 py-1.5 text-left font-normal whitespace-normal sm:grid-cols-[minmax(8rem,1fr)_minmax(0,2fr)_3.5rem_minmax(0,15rem)]">
                  <span className="min-w-0 truncate">{tm.team_name ? <TeamBadge teamId={tm.team_id} teamName={tm.team_name} as="span" /> : <em>{t('exs.noTeam')}</em>}</span>
                  <span className="col-span-2 row-start-2 w-full sm:col-span-1 sm:row-start-auto">
                    <ProgressBar value={h ?? 0} max={100} size="sm" decorative tone={BAR_TONE[ht]} />
                  </span>
                  <span className={cn('col-start-2 row-start-1 text-right font-bold tabular-nums sm:col-start-auto sm:row-start-auto', TONE_INK[ht])}>{formatPercent(h)}</span>
                  <span className="col-span-2 flex flex-wrap items-center gap-x-2 text-xs whitespace-nowrap text-muted-foreground sm:col-span-1">
                    <span className="tabular-nums">{t('stv.teamCerts', tm.total ?? 0)}</span>
                    {tm.under30 > 0 && <b className="text-amber-600 tabular-nums dark:text-amber-400">{t('stv.teamUnder30', tm.under30)}</b>}
                    {tm.open_alerts > 0 && <b className="text-destructive tabular-nums">{t('stv.teamAlerts', tm.open_alerts)}</b>}
                  </span>
                </Button>
              )
            })}
          </CardContent>
        </Card>
      )}
    </section>
  )
}
