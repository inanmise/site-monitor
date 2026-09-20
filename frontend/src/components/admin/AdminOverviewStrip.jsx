import { useState, useEffect } from 'react'
import { Users, UsersRound, Mail, BellRing, SlidersHorizontal, AlertTriangle, CheckCircle2 } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'

/**
 * Yönetim Paneli özet şeridi (2026-09-20): sekmelerin üstünde sayaçlar + sağlık uyarıları. Uyarı çipi tıklanınca
 * ilgili sekme SÜZGEÇLİ açılır (örn. "3 hiç girmemiş" → Kullanıcılar, g_dormant=never). Yönetici sekmeleri tek tek
 * gezmeden neyin eksik olduğunu bir bakışta görür. Sunucu kapsamlı kullanıcı için yalnız görüş alanını sayar.
 */
export default function AdminOverviewStrip({ isAdmin, onJump, refreshKey = 0 }) {
  const t = useT()
  const [data, setData] = useState(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    let alive = true
    api.admin.overview?.().then(r => { if (!alive) return; if (r?.success) { setData(r.data); setError(false) } else setError(true) })
      .catch(() => { if (alive) setError(true) })
    return () => { alive = false }
  }, [refreshKey])

  if (error || !data) return null
  const c = data.counts || {}
  const warnings = data.warnings || []

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

  const kpi = (icon, label, value, tab, sub) => (
    <button type="button" className="aov-kpi" onClick={() => onJump?.(tab, {})} title={label}>
      <span className="aov-kpi-icon">{icon}</span>
      <span className="aov-kpi-val">{value ?? '—'}</span>
      <span className="aov-kpi-lbl">{label}{sub ? <span className="aov-kpi-sub"> · {sub}</span> : null}</span>
    </button>
  )

  return (
    <div className="aov" data-testid="admin-overview">
      <div className="aov-kpis">
        {kpi(<UsersRound size={15} />, t('aov.teams'), c.teams, 'teams', t('aov.active', c.teams_active ?? 0))}
        {kpi(<Users size={15} />, t('aov.users'), c.users, 'users', t('aov.usersSub', c.users_active ?? 0, c.admins ?? 0))}
        {kpi(<Mail size={15} />, t('aov.contacts'), c.contacts, 'contacts', t('aov.withWebhook', c.contacts_webhook ?? 0))}
        {kpi(<BellRing size={15} />, t('aov.groups'), c.groups, 'notifyGroups')}
        {isAdmin && c.threshold_default && kpi(<SlidersHorizontal size={15} />, t('aov.thresholds'),
          `${c.threshold_default.warning}/${c.threshold_default.high}/${c.threshold_default.critical}`, 'thresholds',
          c.threshold_tiers > 0 ? t('aov.tierRows', c.threshold_tiers) : null)}
      </div>
      <div className="aov-warnings">
        {warnings.length === 0 ? (
          <span className="aov-ok"><CheckCircle2 size={14} /> {t('aov.allClear')}</span>
        ) : warnings.map(w => {
          const [tab, params] = jumpFor(w)
          const severe = w.code === 'SINGLE_ADMIN' || w.code === 'NO_ADMIN' || w.code === 'TEAM_NO_EMAIL' || w.code === 'GROUP_NO_EMAILS'
          return (
            <button type="button" key={w.code} className={`aov-warn${severe ? ' aov-warn--severe' : ''}`} onClick={() => onJump?.(tab, params)}>
              <AlertTriangle size={13} /> {t(`aov.w.${w.code}`, w.count)}
            </button>
          )
        })}
      </div>
    </div>
  )
}
