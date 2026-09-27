import { Hourglass, Rocket, RotateCcw, SkipForward, Timer, Undo2 } from 'lucide-react'
import { formatPercent } from '../../../i18n/dateLocale.js'
import { useT } from '../../../i18n/index.jsx'
import { KpiCard } from '../HealthUi.jsx'
import { EnvBadge, fmtAgo, fmtDur } from './DeployBadges.jsx'
import { Skeleton } from '@/components/shadcn/skeleton'

/**
 * Sürüm & Dağıtım göstergeleri (2026-09-27 yeniden tasarım) — Sistem Sağlığı KPI ızgarasıyla aynı `KpiCard`
 * (bilgi kutusu, tıklanmaz). Değerler SEÇİLİ ortamın zaman çizelgesinden: sunucu özeti (30 gün, 7 gün yeniden
 * başlatma, yayın→canlı, atlanan sürüm) + istemci türetimi (`deployStats`: 90 gün aralık ve geri alma oranı —
 * değişiklik hata oranının vekili). Izgara KAP genişliğine göre: 2 → 3 → 6 sütun (`@container/deploy`).
 * Test kancaları: `data-slot="deploy-stats"`, kutularda `data-kpi`.
 */
export default function DeployKpis({ summary, stats, env, loading }) {
  const t = useT()
  if (loading) {
    return (
      <div data-slot="deploy-stats" aria-busy="true" className="grid grid-cols-2 gap-2 @xl/deploy:grid-cols-3 @5xl/deploy:grid-cols-6 sm:gap-3">
        {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-[118px] rounded-xl motion-reduce:animate-none" />)}
      </div>
    )
  }
  const s = summary || {}
  const st = stats || {}
  const dash = '—'
  const items = [
    { key: 'last30', icon: Rocket, label: t('deploy.last30'), value: s.deploymentsLast30d ?? dash,
      sub: st.deployments != null ? t('deploy.kpi.in90', st.deployments) : null },
    { key: 'gap', icon: Timer, label: t('deploy.kpi.gap'),
      value: st.avgGapSeconds != null ? fmtDur(st.avgGapSeconds, t) : dash,
      sub: st.lastAt ? t('deploy.kpi.lastDeploy', fmtAgo(st.lastAt)) : null },
    { key: 'rollbackRate', icon: Undo2, label: t('deploy.kpi.rollbackRate'),
      value: st.rollbackRate != null ? formatPercent(Math.round(st.rollbackRate * 100)) : dash,
      sub: st.deployments ? t('deploy.kpi.rollbackOf', st.rollbacks, st.deployments) : null,
      tone: st.rollbacks > 0 ? 'warn' : undefined, title: t('deploy.kpi.rollbackHint') },
    { key: 'restarts7', icon: RotateCcw, label: t('deploy.restarts7'), value: s.restartsLast7d ?? dash,
      sub: s.rollbacks != null ? t('deploy.kpi.rollbacksAll', s.rollbacks) : null },
    { key: 'avgLag', icon: Hourglass, label: t('deploy.avgLag'),
      value: s.avgReleaseLagSeconds != null ? fmtDur(s.avgReleaseLagSeconds, t) : dash,
      sub: null },
    { key: 'skipped', icon: SkipForward, label: t('deploy.skipped'), value: s.skippedReleases ?? dash,
      sub: t('deploy.kpi.skippedSub') },
  ]
  return (
    <section className="flex min-w-0 flex-col gap-2" aria-label={t('deploy.kpi.label')}>
      {env && (
        <p className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
          {t('deploy.kpi.scope')} <EnvBadge env={env} />
        </p>
      )}
      <div data-slot="deploy-stats" className="grid grid-cols-2 gap-2 @xl/deploy:grid-cols-3 @5xl/deploy:grid-cols-6 sm:gap-3">
        {items.map((it) => (
          <KpiCard key={it.key} kpiKey={it.key} icon={it.icon} label={it.label} value={it.value} sub={it.sub}
            tone={it.tone} title={it.title} />
        ))}
      </div>
    </section>
  )
}
