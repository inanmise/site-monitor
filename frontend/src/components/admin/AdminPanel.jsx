import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import InventoryManager from './InventoryManager'
import AlertThresholds from './AlertThresholds'
import EscalationContacts from './EscalationContacts'
import AlertHistory from './AlertHistory'
import TeamManager from './TeamManager'
import UserManager from './UserManager'

const TAB_GROUPS = [
  {
    groupKey: 'admin.groupCert',
    tabs: [
      { id: 'inventory',  labelKey: 'admin.tabInventory',  adminOnly: false },
      { id: 'thresholds', labelKey: 'admin.tabThresholds', adminOnly: true  },
    ],
  },
  {
    groupKey: 'admin.groupNotify',
    tabs: [
      { id: 'contacts', labelKey: 'admin.tabContacts', adminOnly: false },
      { id: 'alerts',   labelKey: 'admin.tabAlerts',   adminOnly: true  },
    ],
  },
  {
    groupKey: 'admin.groupOrg',
    tabs: [
      { id: 'teams', labelKey: 'admin.tabTeams', adminOnly: true },
      { id: 'users', labelKey: 'admin.tabUsers', adminOnly: true },
    ],
  },
]

export default function AdminPanel({ onInventoryChange, systemRole }) {
  const t = useT()
  const isAdmin = systemRole === 'ADMIN'
  const [activeTab, setActiveTab] = useState('inventory')
  const [teams, setTeams] = useState([])

  function loadTeams() {
    api.admin.getTeams().then((res) => { if (res?.success) setTeams(res.data) })
  }

  useEffect(() => { if (isAdmin) loadTeams() }, [isAdmin])

  function handleTabChange(id) {
    setActiveTab(id)
    if (id === 'inventory' && isAdmin) loadTeams()
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
        {activeTab === 'inventory'  && <InventoryManager onInventoryChange={onInventoryChange} teams={teams} isAdmin={isAdmin} />}
        {activeTab === 'thresholds' && isAdmin && <AlertThresholds />}
        {activeTab === 'contacts'   && <EscalationContacts teams={teams} isAdmin={isAdmin} />}
        {activeTab === 'alerts'     && isAdmin && <AlertHistory />}
        {activeTab === 'teams'      && isAdmin && <TeamManager onTeamsChange={loadTeams} />}
        {activeTab === 'users'      && isAdmin && <UserManager teams={teams} />}
      </div>
    </div>
  )
}
