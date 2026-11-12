import { createRoot } from 'react-dom/client'
import { HealthTiles } from '../../src/components/admin/AdminOverviewStrip.jsx'
import { LangProvider } from '../../src/i18n/index.jsx'
import '../../src/styles/globals.css'   // uygulamayla aynı kaskat: shadcn jetonları + Tailwind
import '../../src/App.css'

/**
 * Yönetim sağlık kutucukları (AdminOverviewStrip → HealthTiles) — izole harness (yalnız Playwright). Gerçek bileşen
 * sabit sinyallerle mount edilir (fetch yok); jsdom düzen yapmadığı için mweb (taşma / telefonda 2 + "+N" / 2 sütun /
 * ≥40 px dokunma / süzgeçli derin-bağlantı) ancak burada ölçülür. Uzun cümle etiketler sarar, kesilmez.
 */
const SIGNALS = [
  { code: 'SINGLE_ADMIN',    count: 1, tab: 'users',        params: { g_role: 'ADMIN' }, severe: true },
  { code: 'ESCALATION_GAP',  count: 2, tab: 'contacts',     params: {},                  severe: true },
  { code: 'TEAM_NO_EMAIL',   count: 1, tab: 'teams',        params: {},                  severe: true },
  { code: 'TEAM_NO_LEADER',  count: 3, tab: 'teams',        params: {},                  severe: false },
  { code: 'GROUP_NO_EMAILS', count: 1, tab: 'notifyGroups', params: {},                  severe: false },
  { code: 'CONTACT_INACTIVE',count: 2, tab: 'contacts',     params: {},                  severe: false },
  { code: 'USER_ATTENTION',  count: 6, tab: 'users',        params: { g_status: 'locked' }, severe: false },
]

createRoot(document.getElementById('host')).render(
  <LangProvider>
    <div style={{ padding: 16 }}>
      <HealthTiles signals={SIGNALS} onJump={(tab, params) => { window.__jump = { tab, params } }} />
    </div>
  </LangProvider>,
)
