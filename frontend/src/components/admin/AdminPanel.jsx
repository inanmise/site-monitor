import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import AlertThresholds from './AlertThresholds'
import EscalationContacts from './EscalationContacts'
import TeamManager from './TeamManager'
import UserManager from './UserManager'
import PermissionMatrix from './PermissionMatrix'

const TAB_GROUPS = [
  {
    groupKey: 'admin.groupCert',
    tabs: [
      { id: 'thresholds', labelKey: 'admin.tabThresholds', adminOnly: true },
    ],
  },
  {
    groupKey: 'admin.groupNotify',
    tabs: [
      { id: 'contacts', labelKey: 'admin.tabContacts', adminOnly: false },
    ],
  },
  {
    groupKey: 'admin.groupOrg',
    tabs: [
      { id: 'teams', labelKey: 'admin.tabTeams', adminOnly: false },
      { id: 'users', labelKey: 'admin.tabUsers', adminOnly: false },
    ],
  },
  {
    groupKey: 'admin.groupSecurity',
    tabs: [
      { id: 'permissions', labelKey: 'admin.tabPermissions', adminOnly: true },
    ],
  },
]

export default function AdminPanel({ systemRole, ownTeamId }) {
  const t = useT()
  const isAdmin = systemRole === 'ADMIN'
  const [activeTab, setActiveTab] = useState(isAdmin ? 'thresholds' : 'contacts')
  const [teams, setTeams] = useState([])

  function loadTeams() {
    api.admin.getTeams().then((res) => { if (res?.success) setTeams(res.data) })
  }

  useEffect(() => { loadTeams() }, [])

  function handleTabChange(id) {
    setActiveTab(id)
  }

  return (
    <div className="admin-panel">
      <div className="admin-tabs">
        {TAB_GROUPS.map((group) => {
          const visibleTabs = group.tabs.filter((tab) => !tab.adminOnly || isAdmin)
          if (visibleTabs.length === 0) return null
          return (
            <div key={group.groupKey} className="admin-tab-group">
              <span className="admin-tab-group-label">{t(group.groupKey)}</span>
              <div className="admin-tab-group-tabs">
                {visibleTabs.map((tab) => (
                  <button
                    key={tab.id}
                    className={`admin-tab-btn${activeTab === tab.id ? ' active' : ''}`}
                    onClick={() => handleTabChange(tab.id)}
                  >
                    {t(tab.labelKey)}
                  </button>
                ))}
              </div>
            </div>
          )
        })}
      </div>

      <div className="admin-content">
        {activeTab === 'thresholds' && isAdmin && <AlertThresholds />}
        {activeTab === 'contacts'   && <EscalationContacts teams={teams} systemRole={systemRole} />}
        {activeTab === 'teams'      && <TeamManager systemRole={systemRole} ownTeamId={ownTeamId} onTeamsChange={loadTeams} />}
        {activeTab === 'users'      && <UserManager systemRole={systemRole} ownTeamId={ownTeamId} teams={teams} />}
        {activeTab === 'permissions' && isAdmin && <PermissionMatrix />}
      </div>
    </div>
  )
}
