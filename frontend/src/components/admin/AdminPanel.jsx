import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import InventoryManager from './InventoryManager'
import AlertThresholds from './AlertThresholds'
import EscalationContacts from './EscalationContacts'
import AlertHistory from './AlertHistory'
import TeamManager from './TeamManager'
import UserManager from './UserManager'
import SystemHealth from './SystemHealth'

export default function AdminPanel({ onInventoryChange, systemRole }) {
  const t = useT()
  const isAdmin = systemRole === 'ADMIN'
  const [activeTab, setActiveTab] = useState('inventory')
  const [teams, setTeams] = useState([])

  useEffect(() => {
    if (isAdmin) {
      api.admin.getTeams().then((res) => { if (res?.success) setTeams(res.data) })
    }
  }, [isAdmin])

  const tabs = [
    { id: 'inventory',  labelKey: 'admin.tabInventory',  adminOnly: false },
    { id: 'contacts',   labelKey: 'admin.tabContacts',   adminOnly: false },
    { id: 'alerts',     labelKey: 'admin.tabAlerts',     adminOnly: true  },
    { id: 'thresholds', labelKey: 'admin.tabThresholds', adminOnly: true  },
    { id: 'teams',      labelKey: 'admin.tabTeams',      adminOnly: true  },
    { id: 'users',      labelKey: 'admin.tabUsers',      adminOnly: true  },
    { id: 'system',     labelKey: 'admin.tabSystem',     adminOnly: true  },
  ].filter(tab => !tab.adminOnly || isAdmin)

  return (
    <div className="admin-panel">
      <div className="admin-tabs">
        {tabs.map((tab) => (
          <button
            key={tab.id}
            className={`admin-tab-btn${activeTab === tab.id ? ' active' : ''}`}
            onClick={() => setActiveTab(tab.id)}
          >
            {t(tab.labelKey)}
          </button>
        ))}
      </div>
      <div className="admin-content">
        {activeTab === 'inventory'  && <InventoryManager onInventoryChange={onInventoryChange} teams={teams} isAdmin={isAdmin} />}
        {activeTab === 'contacts'   && <EscalationContacts teams={teams} isAdmin={isAdmin} />}
        {activeTab === 'alerts'     && isAdmin && <AlertHistory />}
        {activeTab === 'thresholds' && isAdmin && <AlertThresholds />}
        {activeTab === 'teams'      && isAdmin && <TeamManager />}
        {activeTab === 'users'      && isAdmin && <UserManager teams={teams} />}
        {activeTab === 'system'     && isAdmin && <SystemHealth />}
      </div>
    </div>
  )
}
