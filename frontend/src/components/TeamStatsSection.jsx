import { BarChart3, CheckCircle, AlertTriangle, XCircle } from 'lucide-react'
import { useT } from '../i18n/index.jsx'

function StatCard({ title, stats, teamName, role, onStatClick }) {
  const t = useT()
  const total   = stats?.total_certificates ?? 0
  const valid   = stats?.valid_count        ?? 0
  const warning = stats?.warning_count      ?? 0
  const error   = stats?.error_count        ?? 0

  function handleClick(status) {
    if (!onStatClick) return
    const domains = stats?.[`${status}_domains`] ?? []
    if (domains.length === 0) return
    onStatClick(new Set(domains), `${teamName} — ${role} — ${t(`ts.${status}`)}`)
  }

  return (
    <div className="ts-card">
      <div className="ts-card-title">{title}</div>
      <div className="ts-row">
        <span className="ts-row-label">{t('ts.total')}</span>
        <span className="ts-row-val ts-val-total">{total}</span>
      </div>
      <div className="ts-row ts-row-clickable" onClick={() => handleClick('valid')}>
        <span className="ts-row-label">{t('ts.valid')}</span>
        <span className="ts-row-val ts-val-valid">{valid}</span>
      </div>
      <div className="ts-row ts-row-clickable" onClick={() => handleClick('warning')}>
        <span className="ts-row-label">{t('ts.warning')}</span>
        <span className="ts-row-val ts-val-warning">{warning}</span>
      </div>
      <div className="ts-row ts-row-clickable" onClick={() => handleClick('error')}>
        <span className="ts-row-label">{t('ts.error')}</span>
        <span className="ts-row-val ts-val-error">{error}</span>
      </div>
    </div>
  )
}

function PersonalView({ data, onStatClick }) {
  const t = useT()
  return (
    <div className="ts-root">
      <div className="ts-title">{t('ts.title')}{data.team_name ? ` — ${data.team_name}` : ''}</div>
      <div className="ts-cards">
        <StatCard title={t('ts.syRole')} stats={data.sy_stats}
          teamName={data.team_name} role={t('ts.syRole')} onStatClick={onStatClick} />
        <StatCard title={t('ts.ugRole')} stats={data.ug_stats}
          teamName={data.team_name} role={t('ts.ugRole')} onStatClick={onStatClick} />
      </div>
    </div>
  )
}

function RoleChips({ stats, teamName, role, onStatClick }) {
  const t = useT()
  const total   = stats?.total_certificates ?? 0
  const valid   = stats?.valid_count        ?? 0
  const warning = stats?.warning_count      ?? 0
  const error   = stats?.error_count        ?? 0

  function handleClick(status) {
    if (!onStatClick) return
    const domains = stats?.[`${status}_domains`] ?? []
    if (domains.length === 0) return
    onStatClick(new Set(domains), `${teamName} — ${role} — ${t(`ts.${status}`)}`)
  }

  return (
    <div className="ts-stat-chips">
      <span className="ts-chip ts-chip-total">
        <BarChart3 size={11} />{total} {t('ts.total')}
      </span>
      <button className="ts-chip ts-chip-valid ts-chip-btn" onClick={() => handleClick('valid')}
        disabled={valid === 0}>
        <CheckCircle size={11} />{valid}
      </button>
      <button className="ts-chip ts-chip-warning ts-chip-btn" onClick={() => handleClick('warning')}
        disabled={warning === 0}>
        <AlertTriangle size={11} />{warning}
      </button>
      <button className="ts-chip ts-chip-error ts-chip-btn" onClick={() => handleClick('error')}
        disabled={error === 0}>
        <XCircle size={11} />{error}
      </button>
    </div>
  )
}

function AdminView({ teams, onStatClick }) {
  const t = useT()
  return (
    <div className="ts-root">
      <div className="ts-title">{t('ts.allTeams')}</div>
      <div className="ts-admin-grid">
        {teams.map((team) => {
          const syTotal = team.sy_stats?.total_certificates ?? 0
          const ugTotal = team.ug_stats?.total_certificates ?? 0
          const isEmpty = syTotal === 0 && ugTotal === 0
          return (
            <div key={team.team_id} className={`ts-team-card${isEmpty ? ' ts-zero' : ''}`}>
              <div className="ts-team-card-header">{team.team_name}</div>
              <div className="ts-role-section">
                <div className="ts-role-label">{t('ts.syRole')}</div>
                <RoleChips stats={team.sy_stats} teamName={team.team_name}
                  role={t('ts.syRole')} onStatClick={onStatClick} />
              </div>
              <div className="ts-role-divider" />
              <div className="ts-role-section">
                <div className="ts-role-label">{t('ts.ugRole')}</div>
                <RoleChips stats={team.ug_stats} teamName={team.team_name}
                  role={t('ts.ugRole')} onStatClick={onStatClick} />
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

export default function TeamStatsSection({ data, visible, onStatClick }) {
  if (!visible || !data || Object.keys(data).length === 0) return null

  if (data.mode === 'all_teams') {
    return <AdminView teams={data.teams ?? []} onStatClick={onStatClick} />
  }
  if (data.mode === 'personal') {
    return <PersonalView data={data} onStatClick={onStatClick} />
  }
  return null
}
