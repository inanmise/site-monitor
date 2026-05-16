import { useState } from 'react'
import { useT } from '../../i18n/index.jsx'
import InventoryManager from './InventoryManager'
import AlertThresholds from './AlertThresholds'
import EscalationContacts from './EscalationContacts'
import AlertHistory from './AlertHistory'

export default function AdminPanel({ onInventoryChange }) {
  const t = useT()
  const [activeTab, setActiveTab] = useState('inventory')

  const tabs = [
    { id: 'inventory',  labelKey: 'admin.tabInventory' },
    { id: 'alerts',     labelKey: 'admin.tabAlerts' },
    { id: 'contacts',   labelKey: 'admin.tabContacts' },
    { id: 'thresholds', labelKey: 'admin.tabThresholds' },
  ]

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
        {activeTab === 'inventory' && <InventoryManager onInventoryChange={onInventoryChange} />}
        {activeTab === 'alerts' && <AlertHistory />}
        {activeTab === 'contacts' && <EscalationContacts />}
        {activeTab === 'thresholds' && <AlertThresholds />}
      </div>
    </div>
  )
}
