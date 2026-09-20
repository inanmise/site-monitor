import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useUrlQuerySync, readUrlParam } from '../../hooks/useUrlQuerySync.js'
import AlertThresholds from './AlertThresholds'
import EscalationContacts from './EscalationContacts'
import NotificationGroups from './NotificationGroups'
import TeamManager from './TeamManager'
import UserManager from './UserManager'
import AdminOverviewStrip from './AdminOverviewStrip.jsx'

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
      // adminOnly: false — takimin her uyesi kendi takiminin alici listesini yonetir (K2).
      { id: 'notifyGroups', labelKey: 'admin.tabNotifyGroups', adminOnly: false },
    ],
  },
  {
    groupKey: 'admin.groupOrg',
    tabs: [
      { id: 'teams', labelKey: 'admin.tabTeams', adminOnly: false },
      { id: 'users', labelKey: 'admin.tabUsers', adminOnly: false },
    ],
  },
]

export default function AdminPanel({ systemRole, ownTeamId, myTeamIds, currentUsername }) {
  const t = useT()
  const isAdmin = systemRole === 'ADMIN'
  const defaultTab = isAdmin ? 'thresholds' : 'contacts'
  // Alt sekme URL'de (`g_tab`): yenileme/derin bağlantı ilk sekmeye düşmesin (2026-09-20). Bilinmeyen
  // ya da yetkisiz sekme adı varsayılana iner (adminOnly sekmeyi TEAM_ADMIN URL'den açamaz).
  const [activeTab, setActiveTab] = useState(() => {
    const want = readUrlParam('g_tab', null)
    const known = TAB_GROUPS.flatMap(g => g.tabs).find(tb => tb.id === want)
    return known && (!known.adminOnly || isAdmin) ? want : defaultTab
  })
  useUrlQuerySync({ g_tab: activeTab === defaultTab ? null : activeTab })
  const [teams, setTeams] = useState([])

  function loadTeams() {
    api.admin.getTeams().then((res) => { if (res?.success) setTeams(res.data) })
  }

  useEffect(() => { loadTeams() }, [])

  /** Özet şeridinden sekmeye SÜZGEÇLİ atla: g_* paramları URL'e yazılır, sonra sekme mount olup okur (2026-09-20). */
  function jump(id, params) {
    const known = TAB_GROUPS.flatMap(g => g.tabs).find(tb => tb.id === id)
    if (!known || (known.adminOnly && !isAdmin)) return
    try {
      const url = new URL(window.location.href)
      for (const k of Array.from(url.searchParams.keys())) if (k.startsWith('g_') && k !== 'g_tab') url.searchParams.delete(k)
      for (const [k, v] of Object.entries(params || {})) if (v != null && v !== '') url.searchParams.set(k, String(v))
      const qs = url.searchParams.toString()
      window.history.replaceState(window.history.state, '', url.pathname + (qs ? `?${qs}` : '') + url.hash)
    } catch { /* en iyi çaba */ }
    // Aynı sekmedeysek bileşen yeniden mount olsun ki süzgeci URL'den okusun.
    if (id === activeTab) { setRemountKey(k => k + 1); return }
    setActiveTab(id)
  }
  const [remountKey, setRemountKey] = useState(0)

  function handleTabChange(id) {
    if (id === activeTab) return
    // Önceki sekmenin süzgeçleri (g_q, g_role …) yeni sekmeye SIZMASIN: yalnız g_tab kalır.
    try {
      const url = new URL(window.location.href)
      let changed = false
      for (const k of Array.from(url.searchParams.keys())) {
        if (k.startsWith('g_') && k !== 'g_tab') { url.searchParams.delete(k); changed = true }
      }
      if (changed) {
        const qs = url.searchParams.toString()
        window.history.replaceState(window.history.state, '', url.pathname + (qs ? `?${qs}` : '') + url.hash)
      }
    } catch { /* en iyi çaba */ }
    setActiveTab(id)
  }

  return (
    <div className="admin-panel">
      <AdminOverviewStrip isAdmin={isAdmin} onJump={jump} refreshKey={activeTab} />
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

      <div className="admin-content" key={remountKey}>
        {activeTab === 'thresholds' && isAdmin && <AlertThresholds />}
        {activeTab === 'contacts'   && <EscalationContacts teams={teams} systemRole={systemRole} />}
        {activeTab === 'notifyGroups' && <NotificationGroups teams={teams} systemRole={systemRole} />}
        {activeTab === 'teams'      && <TeamManager systemRole={systemRole} ownTeamId={ownTeamId} myTeamIds={myTeamIds} onTeamsChange={loadTeams} />}
        {activeTab === 'users'      && <UserManager systemRole={systemRole} ownTeamId={ownTeamId} currentUsername={currentUsername} teams={teams} />}
      </div>
    </div>
  )
}
