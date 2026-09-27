import { useState, useEffect } from 'react'
import { Users, UsersRound, Mail, BellRing, SlidersHorizontal, AlertTriangle, CheckCircle2 } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { TONE_CLASS } from './ToneBadge.jsx'
import { KpiCard } from './HealthUi.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Yönetim Paneli özet şeridi (2026-09-20): sekmelerin üstünde sayaçlar + sağlık uyarıları. Uyarı çipi tıklanınca
 * ilgili sekme SÜZGEÇLİ açılır (örn. "3 hiç girmemiş" → Kullanıcılar, g_dormant=never). Yönetici sekmeleri tek tek
 * gezmeden neyin eksik olduğunu bir bakışta görür. Sunucu kapsamlı kullanıcı için yalnız görüş alanını sayar.
 *
 * Yerleşim (2026-09-26, mweb): sayaçlar telefonda 2'li, küçük tablette 3'lü ızgara, geniş ekranda tek satır akış;
 * uyarılar küçük, saran, tıklanabilir rozet-düğmeler (shadcn Button, ton = uyarı/ağır). Eskiden tablet/telefonda her
 * çip kendi satırına düşüp ekranı yiyordu.
 */
const SEVERE = new Set(['SINGLE_ADMIN', 'NO_ADMIN', 'TEAM_NO_EMAIL', 'GROUP_NO_EMAILS'])
/**
 * Kullanıcı ETKİNLİĞİ bilgileri (hiç girmemiş / uzun süredir girmemiş / kilitli) bu şeritte gösterilmez — Kullanıcılar
 * sekmesinin özet kutucuklarında (tıklanınca süzen) yaşar (2026-09-27, kullanıcı isteği: "1 kullanıcı hiç giriş yapmamış
 * bilgisini Kullanıcılar sekmesinde verelim"). Şerit yalnız yapısal sorunları taşır (tek yönetici, e-postasız takım…).
 */
export const USER_TAB_WARNINGS = new Set(['USER_NEVER_LOGGED_IN', 'USER_DORMANT', 'USER_LOCKED'])
const PHONE_WARN_LIMIT = 2

export default function AdminOverviewStrip({ isAdmin, onJump, refreshKey = 0 }) {
  const t = useT()
  const [data, setData] = useState(null)
  const [error, setError] = useState(false)
  // Telefonda uyarıların yalnız ilk ikisi (ağırlar önce) + "+N daha" — davranış farkı → useIsMobile.
  const phone = useIsMobile()
  const [showAll, setShowAll] = useState(false)

  useEffect(() => {
    let alive = true
    api.admin.overview?.().then(r => { if (!alive) return; if (r?.success) { setData(r.data); setError(false) } else setError(true) })
      .catch(() => { if (alive) setError(true) })
    return () => { alive = false }
  }, [refreshKey])

  if (error || !data) return null
  const c = data.counts || {}
  const warnings = (data.warnings || []).filter((w) => !USER_TAB_WARNINGS.has(w.code))

  // Uyarı kodu → sekme + süzgeç (URL g_* anahtarları; sekme mount olunca readUrlParam ile okur).
  const jumpFor = (w) => {
    switch (w.code) {
      case 'USER_NEVER_LOGGED_IN': return ['users', { g_dormant: 'never' }]
      case 'USER_DORMANT': return ['users', { g_dormant: String(data.dormant_days || 90) }]
      case 'USER_LOCKED': return ['users', {}]
      case 'SINGLE_ADMIN': case 'NO_ADMIN': return ['users', { g_role: 'ADMIN' }]
      case 'CONTACT_INACTIVE': return ['contacts', {}]
      case 'GROUP_NO_EMAILS': return ['notifyGroups', {}]
      default: return [w.tab || 'teams', {}]
    }
  }

  // Kart: Sistem Sağlığı / Kullanıcılar ile AYNI KpiCard (ikon kutusu, büyük sayı, etiket, alt satır); tıklanınca sekme.
  // 2026-09-26 kullanıcı isteği: kartlar sayfanın tamamına yayılsın — ızgara tam genişlik, auto-fit ile eşit paylaşır.
  const kpi = (Icon, label, value, tab, sub) => (
    <KpiCard key={tab} kpiKey={tab} icon={Icon} value={value ?? '—'} label={label} sub={sub}
      onClick={() => onJump?.(tab, {})} />
  )

  const isSevere = (w) => SEVERE.has(w.code)
  // Ağır uyarılar önce; telefonda ilk PHONE_WARN_LIMIT görünür, kalanı "+N daha" ile açılır (ekranı yemesin).
  const sorted = [...warnings].sort((a, b) => Number(isSevere(b)) - Number(isSevere(a)))
  const limit = phone && !showAll ? PHONE_WARN_LIMIT : sorted.length
  const visible = sorted.slice(0, limit)
  const hidden = sorted.length - visible.length

  return (
    <div className="mb-4 flex flex-col gap-3" data-testid="admin-overview">
      {/* Tam genişlik: telefonda 2, tablette 3, geniş ekranda kart sayısı kadar eşit sütun (sağda boşluk kalmaz) */}
      <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-3 lg:grid-cols-[repeat(auto-fit,minmax(min(200px,100%),1fr))] lg:gap-3">
        {kpi(UsersRound, t('aov.teams'), c.teams, 'teams', t('aov.active', c.teams_active ?? 0))}
        {kpi(Users, t('aov.users'), c.users, 'users', t('aov.usersSub', c.users_active ?? 0, c.admins ?? 0))}
        {kpi(Mail, t('aov.contacts'), c.contacts, 'contacts', t('aov.withWebhook', c.contacts_webhook ?? 0))}
        {kpi(BellRing, t('aov.groups'), c.groups, 'notifyGroups')}
        {isAdmin && c.threshold_default && kpi(SlidersHorizontal, t('aov.thresholds'),
          `${c.threshold_default.warning}/${c.threshold_default.high}/${c.threshold_default.critical}`, 'thresholds',
          c.threshold_tiers > 0 ? t('aov.tierRows', c.threshold_tiers) : null)}
      </div>
      {/* Sağlık uyarıları: tam genişlik, hafif zeminli şerit; çipler sarar */}
      <div className="flex flex-wrap items-center gap-1.5 rounded-xl border bg-muted/30 px-3 py-2" data-slot="overview-warnings">
        {warnings.length === 0 ? (
          <span className="inline-flex items-center gap-1.5 text-sm text-success">
            <CheckCircle2 aria-hidden="true" className="size-3.5" /> {t('aov.allClear')}
          </span>
        ) : visible.map(w => {
          const [tab, params] = jumpFor(w)
          const severe = isSevere(w)
          return (
            <Button type="button" key={w.code} variant="outline" size="xs" data-warning={w.code}
              data-tone={severe ? 'danger' : 'warning'} onClick={() => onJump?.(tab, params)}
              className={cn('h-auto min-h-8 max-w-full rounded-full px-2.5 py-1 text-left font-semibold whitespace-normal sm:min-h-7',
                severe
                  ? cn(TONE_CLASS.danger, 'border-destructive/40 hover:bg-destructive/20 hover:text-destructive')
                  : cn(TONE_CLASS.warning, 'border-amber-500/40 hover:bg-amber-500/25 hover:text-amber-800 dark:hover:text-amber-200'))}>
              <AlertTriangle aria-hidden="true" className="size-3.5 shrink-0" /> {t(`aov.w.${w.code}`, w.count)}
            </Button>
          )
        })}
        {hidden > 0 && (
          <Button type="button" variant="ghost" size="xs" className="h-8 rounded-full px-2.5 font-semibold"
            aria-expanded={false} onClick={() => setShowAll(true)}>
            {t('aov.moreWarnings', hidden)}
          </Button>
        )}
      </div>
    </div>
  )
}
