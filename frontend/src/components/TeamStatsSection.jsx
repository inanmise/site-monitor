import { Users } from 'lucide-react'
import { useT } from '../i18n/index.jsx'

const STATUSES = [
  { key: 'valid',    countKey: 'valid_count',    domainsKey: 'valid_domains'    },
  { key: 'warning',  countKey: 'warning_count',  domainsKey: 'warning_domains'  },
  { key: 'high',     countKey: 'high_count',     domainsKey: 'high_domains'     },
  { key: 'critical', countKey: 'critical_count', domainsKey: 'critical_domains' },
  { key: 'expired',  countKey: 'expired',        domainsKey: 'expired_domains'  },
]

function TierRow({ label, stats, teamName, onStatClick, t }) {
  function click(domainsKey, statusLabel) {
    const domains = stats?.[domainsKey] ?? []
    if (!onStatClick || !domains.length) return
    onStatClick(new Set(domains), `${teamName} — ${label} — ${statusLabel}`)
  }
  return (
    <tr className="ts-grid-row">
      <td className="ts-grid-tier-cell">{label}</td>
      {STATUSES.map(({ key, countKey, domainsKey }) => {
        const count = stats?.[countKey] ?? 0
        return (
          <td
            key={key}
            className={`ts-grid-val-cell ts-cell-${key}${count > 0 ? ' ts-cell-active' : ' ts-cell-zero'}`}
            onClick={() => count > 0 && click(domainsKey, t(`ts.${key}`))}
          >{count}</td>
        )
      })}
    </tr>
  )
}

function TeamCard({ team, onStatClick }) {
  const t = useT()
  const total = (team.sy_t1_stats?.total_certificates ?? 0) + (team.sy_t2_stats?.total_certificates ?? 0)
  return (
    <div className={`ts-team-card${total === 0 ? ' ts-zero' : ''}`}>
      <div className="ts-team-card-header">
        <Users size={13} className="ts-team-icon" />
        <span className="ts-team-name">{team.team_name}</span>
        <span className="ts-team-grand-total">{total}</span>
      </div>
      <table className="ts-grid-table">
        <thead>
          <tr>
            <th className="ts-grid-tier-hdr"></th>
            {STATUSES.map(({ key }) => (
              <th key={key} className={`ts-grid-hdr ts-hdr-${key}`}>{t(`ts.${key}`)}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          <TierRow label="T1" stats={team.sy_t1_stats} teamName={team.team_name} onStatClick={onStatClick} t={t} />
          <TierRow label="T2" stats={team.sy_t2_stats} teamName={team.team_name} onStatClick={onStatClick} t={t} />
        </tbody>
      </table>
    </div>
  )
}

function AdminView({ teams, onStatClick }) {
  const t = useT()
  return (
    <div className="ts-root">
      <div className="ts-section-frame">
        <div className="ts-section-header">
          <Users size={15} className="ts-section-icon" />
          <span>{t('ts.teamSectionTitle')}</span>
          <span className="ts-section-badge">{teams.length}</span>
        </div>
        <div className="ts-admin-grid">
          {teams.map((team) => (
            <TeamCard key={team.team_id} team={team} onStatClick={onStatClick} />
          ))}
        </div>
      </div>
    </div>
  )
}

function PersonalView({ data, onStatClick }) {
  const t = useT()
  const team = {
    team_id:     data.team_id,
    team_name:   data.team_name,
    sy_t1_stats: data.sy_t1_stats,
    sy_t2_stats: data.sy_t2_stats,
  }
  return (
    <div className="ts-root">
      <div className="ts-title">{t('ts.title')}{data.team_name ? ` — ${data.team_name}` : ''}</div>
      <TeamCard team={team} onStatClick={onStatClick} />
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
