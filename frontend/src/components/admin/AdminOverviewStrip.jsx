import { useState, useEffect } from 'react'
import { Users, UsersRound, Mail, BellRing, SlidersHorizontal, AlertTriangle, CheckCircle2,
  ShieldX, ShieldAlert, UserX, ArrowRight, Activity } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { TONE_CLASS } from './ToneBadge.jsx'
import { KpiCard } from './HealthUi.jsx'
import { buildCoverage } from './contactsCoverageModel.js'
import { buildHealthSignals, escalationGapFrom } from './adminHealthModel.js'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Yönetim Paneli SAĞLIK PANOSU (2026-09-20; eyleme dönük yeniden tasarım 2026-11-12, çapraz zenginleştirme #2):
 * sekmelerin üstünde KPI sayaçları + yapısal sağlık kutucukları. Her kutucuk tıklanınca ilgili sekme SÜZGEÇLİ açılır
 * (örn. "Tek ADMIN" → Kullanıcılar g_role=ADMIN). Yönetici sekmeleri gezmeden neyin eksik olduğunu bir bakışta görür.
 *
 * Sinyaller `adminHealthModel.buildHealthSignals(overview, escalationGap)` ile üretilir; eskalasyon KAPSAM BOŞLUĞU
 * (varlık var ama kritik eskalasyon kişisi yok) #1'in test edilmiş `contactsCoverageModel.buildCoverage`'ı ile frontend'de
 * hesaplanır (overview'un yanına contacts/all + users + teams/stats çekilir; veri okunamazsa o kutucuk hiç çizilmez —
 * bilinmeyen ≠ eksik). Kullanıcı-etkinlik uyarıları (kilitli/atıl/hiç) TEK "Dikkat isteyen kullanıcılar" kutucuğunda
 * toplanır (şeridi çipe boğmadan; 2026-09-27 kararının ruhu) ve Kullanıcılar sekmesine süzgeçli gider — kırılım orada.
 *
 * Yerleşim (mweb): KPI sayaçları telefonda 2'li, tablette 3'lü, geniş ekranda akış; sağlık kutucukları telefonda tek
 * sütun, tablette 2'li; telefonda yalnız ilk {@link PHONE_WARN_LIMIT} (ağırlar önce) + "+N daha". Sunucu kapsamlı
 * kullanıcı için yalnız görüş alanını sayar. Test kancaları: `data-slot="overview-health"`, kutucuk `data-warning` + `data-tone`.
 */
const HEALTH_ICON = {
  NO_ADMIN: UserX, SINGLE_ADMIN: ShieldAlert, ESCALATION_GAP: ShieldX,
  TEAM_NO_EMAIL: Mail, TEAM_NO_LEADER: UsersRound, GROUP_NO_EMAILS: BellRing,
  CONTACT_INACTIVE: UserX, USER_ATTENTION: Users,
}
const PHONE_WARN_LIMIT = 2

export default function AdminOverviewStrip({ isAdmin, onJump, refreshKey = 0 }) {
  const t = useT()
  const [data, setData] = useState(null)
  const [gap, setGap] = useState(null)            // eskalasyon kapsam boşluğu sayısı (number) ya da null (bilinmiyor)
  const [error, setError] = useState(false)

  // Sayaçlar + uyarılar: sekme değişiminde (refreshKey) tazelenir — eskisi gibi tek çağrı.
  useEffect(() => {
    let alive = true
    api.admin.overview?.().then((r) => { if (!alive) return; if (r?.success) { setData(r.data); setError(false) } else { setError(true); setData(null) } })
      .catch(() => { if (alive) { setError(true); setData(null) } })
    return () => { alive = false }
  }, [refreshKey])

  // Eskalasyon kapsam boşluğu girdileri (ağır liste uçları) YALNIZ bir kez — sekme değişiminde tekrar çekilmez (maliyet).
  // #1'in test edilmiş buildCoverage'ı ile boşluk sayısı; biri okunamazsa escalationGapFrom null döner → kutucuk çizilmez.
  useEffect(() => {
    let alive = true
    Promise.allSettled([api.admin.getContacts?.(), api.admin.getUsers?.(), api.admin.teamStats?.()])
      .then(([co, us, st]) => {
        if (!alive) return
        const contactsOk = co.status === 'fulfilled' && !!co.value?.success
        const contacts = contactsOk ? (co.value.data || []) : []
        const users = us.status === 'fulfilled' && us.value?.success ? (us.value.data || []) : []
        const inactiveIds = new Set(users.filter((u) => u.active === false).map((u) => String(u.id)))
        const stats = st.status === 'fulfilled' && st.value?.success ? (st.value.data || {}) : {}
        const teams = Object.keys(stats).map((id) => ({ id: Number(id), name: '' }))
        setGap(escalationGapFrom(buildCoverage(contacts, teams, inactiveIds, stats), contactsOk))
      })
      .catch(() => { /* boşluk kutucuğu çizilmez; strip yine çalışır */ })
    return () => { alive = false }
  }, [])

  if (error || !data) return null
  const c = data.counts || {}
  const signals = buildHealthSignals(data, gap)

  const kpi = (Icon, label, value, tab, sub) => (
    <KpiCard key={tab} kpiKey={tab} icon={Icon} value={value ?? '—'} label={label} sub={sub} onClick={() => onJump?.(tab, {})} />
  )

  return (
    <div className="mb-4 flex flex-col gap-3" data-testid="admin-overview">
      {/* KPI sayaçları — tam genişlik: telefonda 2, tablette 3, geniş ekranda eşit sütun */}
      <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-3 lg:grid-cols-[repeat(auto-fit,minmax(min(200px,100%),1fr))] lg:gap-3">
        {kpi(UsersRound, t('aov.teams'), c.teams, 'teams', t('aov.active', c.teams_active ?? 0))}
        {kpi(Users, t('aov.users'), c.users, 'users', t('aov.usersSub', c.users_active ?? 0, c.admins ?? 0))}
        {kpi(Mail, t('aov.contacts'), c.contacts, 'contacts', t('aov.withWebhook', c.contacts_webhook ?? 0))}
        {kpi(BellRing, t('aov.groups'), c.groups, 'notifyGroups')}
        {isAdmin && c.threshold_default && kpi(SlidersHorizontal, t('aov.thresholds'),
          `${c.threshold_default.warning}/${c.threshold_default.high}/${c.threshold_default.critical}`, 'thresholds',
          c.threshold_tiers > 0 ? t('aov.tierRows', c.threshold_tiers) : null)}
      </div>

      <HealthTiles signals={signals} onJump={onJump} />
    </div>
  )
}

/**
 * Sağlık kutucukları (fetch'siz, saf sunum — strip ile e2e harness'ı AYNI bileşeni kullanır). Ağır (danger) önce,
 * uyarı (amber) sonra; telefonda ilk {@link PHONE_WARN_LIMIT} + "+N daha". Her kutucuk tıklanınca sekme + süzgeç.
 */
export function HealthTiles({ signals = [], onJump }) {
  const t = useT()
  const phone = useIsMobile()
  const [showAll, setShowAll] = useState(false)
  const limit = phone && !showAll ? PHONE_WARN_LIMIT : signals.length
  const visible = signals.slice(0, limit)
  const hidden = signals.length - visible.length

  return (
    <div className="rounded-xl border bg-muted/30 p-2.5 sm:p-3" data-slot="overview-health">
      <div className="mb-2 flex items-center gap-1.5 text-[11px] font-bold tracking-widest text-muted-foreground uppercase">
        <Activity aria-hidden="true" className="size-3.5" /> {t('aov.healthTitle')}
      </div>
      {signals.length === 0 ? (
        <span className="inline-flex items-center gap-1.5 text-sm text-success">
          <CheckCircle2 aria-hidden="true" className="size-3.5" /> {t('aov.allClear')}
        </span>
      ) : (
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          {visible.map((s) => <HealthTile key={s.code} s={s} t={t} onJump={onJump} />)}
          {hidden > 0 && (
            <Button type="button" variant="ghost" size="sm" aria-expanded={false} onClick={() => setShowAll(true)}
              className="h-auto min-h-10 justify-center rounded-lg font-semibold">
              {t('aov.moreWarnings', hidden)}
            </Button>
          )}
        </div>
      )}
    </div>
  )
}

/** Tek sağlık kutucuğu — ikon + açıklama (sayı gömülü) + ok; ton ağır/uyarı. Tıklanınca sekme + süzgeç. */
function HealthTile({ s, t, onJump }) {
  const Icon = HEALTH_ICON[s.code] || AlertTriangle
  return (
    <Button type="button" variant="outline" size="sm" data-warning={s.code} data-tone={s.severe ? 'danger' : 'warning'}
      onClick={() => onJump?.(s.tab, s.params)}
      className={cn('h-auto min-h-12 w-full justify-start gap-2.5 rounded-lg px-3 py-2 text-left whitespace-normal pointer-coarse:min-h-12',
        s.severe
          ? cn(TONE_CLASS.danger, 'border-destructive/40 hover:bg-destructive/15 hover:text-destructive')
          : cn(TONE_CLASS.warning, 'border-amber-500/40 hover:bg-amber-500/20 hover:text-amber-800 dark:hover:text-amber-200'))}>
      <Icon aria-hidden="true" className="size-4 shrink-0" />
      <span className="min-w-0 text-sm font-semibold [overflow-wrap:anywhere]">{t(`aov.w.${s.code}`, s.count)}</span>
      <ArrowRight aria-hidden="true" className="ms-auto size-4 shrink-0 opacity-60" />
    </Button>
  )
}
