import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useUrlQuerySync, readUrlParam } from '../../hooks/useUrlQuerySync.js'
import AlertThresholds from './AlertThresholds'
import EscalationContacts from './EscalationContacts'
import NotificationGroups from './NotificationGroups'
import RecipientSimulator from './RecipientSimulator.jsx'
import TeamManager from './TeamManager'
import UserManager from './UserManager'
import AdminOverviewStrip from './AdminOverviewStrip.jsx'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'

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
      // "Kim bilgilendirilir?" (2026-09-27, ayrı sekme): kurulan kişi + grup zincirinin DOĞRULAMASI — bu yüzden
      // ikisinin ARDINDAN (kur → doğrula). Görünürlük kişilerle aynı; takım kapsamını sunucu uygular.
      { id: 'whoNotified', labelKey: 'admin.tabWhoNotified', adminOnly: false },
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

export default function AdminPanel({ systemRole, ownTeamId, myTeamIds, currentUsername, globalAdmin = false }) {
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

  // Görünür gruplar (adminOnly süzgeci) — hem sekme şeridi hem telefon seçicisi aynı listeden çizer.
  const groups = TAB_GROUPS
    .map((g) => ({ ...g, tabs: g.tabs.filter((tab) => !tab.adminOnly || isAdmin) }))
    .filter((g) => g.tabs.length > 0)

  // Panel ve şerit tam içerik genişliğinde (kullanıcı 2026-09-26: "sayfanın yalnız bir kısmını kullanıyor"):
  // özet şeridi (KpiCard ızgarası), sekme şeridi ve içerik aynı genişlikte tek blok okunur.
  return (
    <div className="mt-1 w-full min-w-0">
      <AdminOverviewStrip isAdmin={isAdmin} onJump={jump} refreshKey={activeTab} />
      <Tabs value={activeTab} onValueChange={handleTabChange} className="w-full gap-4 sm:gap-5">
        <GroupedTabStrip groups={groups} t={t} />

        <TabsContent value={activeTab} key={remountKey} className="min-h-[300px] min-w-0">
          {activeTab === 'thresholds' && isAdmin && <AlertThresholds />}
          {activeTab === 'contacts'   && <EscalationContacts teams={teams} systemRole={systemRole} onOpenSimulator={(params) => jump('whoNotified', params)} />}
          {activeTab === 'notifyGroups' && <NotificationGroups teams={teams} systemRole={systemRole} />}
          {activeTab === 'whoNotified' && (
            <RecipientSimulator teams={teams} isAdmin={isAdmin} onNavigate={jump}
              defaultTeamId={!isAdmin && teams.length === 1 ? teams[0].id : ''} />
          )}
          {activeTab === 'teams'      && <TeamManager systemRole={systemRole} ownTeamId={ownTeamId} myTeamIds={myTeamIds} onTeamsChange={loadTeams} globalAdmin={globalAdmin} currentUsername={currentUsername} />}
          {activeTab === 'users'      && <UserManager systemRole={systemRole} ownTeamId={ownTeamId} currentUsername={currentUsername} teams={teams} globalAdmin={globalAdmin} />}
        </TabsContent>
      </Tabs>
    </div>
  )
}

/**
 * Gruplu sekme şeridi — TEK shadcn `TabsList` (role="tablist"; Radix ok tuşları gruplar ARASINDA da gezinir,
 * `aria-selected`, `g_tab` URL anahtarı ve adminOnly süzgeci değişmedi), tam içerik genişliğinde (2026-09-26,
 * kullanıcı: "sayfanın yalnız bir kısmını kullanıyor"). Her grup bir sütun: küçük büyük harf etiket
 * (`aria-hidden` — sekme listesinde yalnız sekmeler bulunur) + parçalı "hap" kabı (`bg-muted p-[3px]`,
 * ui/SegmentedControl ile aynı dil); etkin sekme dolgulu (`bg-background shadow-sm`, birincil renk, kalın).
 *
 * <p>Genişlik dağılımı sabit kırılma noktası DEĞİL, içerikten: gruplar sekme sayısıyla orantılı büyür
 * (`flexGrow`), doğal genişliklerinin altına inmez (`basis-auto`, sekmeler `flex-auto`) ve sığmayınca
 * SATIR ATLAR (`flex-wrap`) — 1024 px'te (kenar çubuğu açık, ~670 px) tek satır, tablette (~416 px) ve
 * telefonda her grup tam genişlik bir satır. Yatay taşma yok, kaydırma yok; telefonda ayrı seçim kutusu
 * (eski NativeSelect) YOK — dokunma hedefi 40 px (`min-h-10`), md+ 32 px.
 */
function GroupedTabStrip({ groups, t }) {
  return (
    <TabsList className="flex h-auto w-full flex-wrap items-stretch justify-start gap-x-4 gap-y-2.5 rounded-none bg-transparent p-0 group-data-[orientation=horizontal]/tabs:h-auto">
      {groups.map((group) => (
        <div key={group.groupKey} data-slot="admin-tab-group" data-group={group.groupKey}
          style={{ flexGrow: group.tabs.length }} className="flex min-w-0 basis-auto flex-col gap-1">
          <span aria-hidden="true"
            className="px-1 text-[0.67em] font-bold tracking-wider whitespace-nowrap text-muted-foreground/80 uppercase">
            {t(group.groupKey)}
          </span>
          <div className="flex flex-wrap gap-1 rounded-lg bg-muted p-[3px]">
            {group.tabs.map((tab) => (
              <TabsTrigger key={tab.id} value={tab.id} data-tab={tab.id}
                className="h-auto min-h-10 flex-auto px-3 py-1 md:min-h-8 data-[state=active]:font-semibold data-[state=active]:text-primary dark:data-[state=active]:bg-input/50 dark:data-[state=active]:text-primary">
                {t(tab.labelKey)}
              </TabsTrigger>
            ))}
          </div>
        </div>
      ))}
    </TabsList>
  )
}
