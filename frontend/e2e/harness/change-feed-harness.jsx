import { createRoot } from 'react-dom/client'
import { api } from '../../src/api/client'
import AdminChangeFeed from '../../src/components/admin/AdminChangeFeed.jsx'
import { LangProvider } from '../../src/i18n/index.jsx'
import { ToastProvider } from '../../src/components/ui/Toast.jsx'
import '../../src/styles/globals.css'   // uygulamayla aynı kaskat: shadcn jetonları + Tailwind
import '../../src/App.css'

/**
 * Yönetim → Denetim → Değişiklik akışı (2026-11-13, zenginleştirme #2) — izole harness (yalnız Playwright). Gerçek
 * AdminChangeFeed mount edilir; ekran kendi verisini çektiği için singleton {@code api.admin.historyFeed} ağ ÇAĞIRMADAN
 * sabit yanıtla değiştirilir. Gerçek uzunlukta AD adları ve takım adları: taşma / kırpma / tablo ↔ kart / ≥ 40 px.
 */
const LONG_TEAM = 'Teknoloji Servis Yönetimi — Çok Uzun Takım Adı Örneği Bölümü ve Devamı'
const ITEMS = [
  { id: 1, at: '2026-11-12T10:00:00', actor: 'ayse.yilmaz', area: 'teams', action: 'UPDATE', event_type: 'TEAM_UPDATE',
    resource_type: 'TEAM', resource_id: '7', name: LONG_TEAM, team_id: 7, team_name: LONG_TEAM,
    changes: '{"email":{"from":"eski.adres.cok.uzun.ornek@example.com.tr","to":"yeni.adres.cok.uzun.ornek@example.com.tr"},"leaderId":{"from":3,"to":5}}', detail: null },
  { id: 2, at: '2026-11-12T09:00:00', actor: 'mehmet.demir', area: 'forwards', action: 'CREATE', event_type: 'ALERT_FORWARD_CREATE',
    resource_type: 'TEAM', resource_id: '8', name: 'Uygulama Geliştirme Ekibi', team_id: 8, team_name: 'Uygulama Geliştirme Ekibi',
    changes: null, detail: '{"scope_kind":"TYPES","scope_types":"http,keyword,ping","recipient_kind":"MEMBERS"}' },
  { id: 3, at: '2026-11-11T16:30:00', actor: 'system', area: 'groups', action: 'DEFAULT', event_type: 'NOTIFICATION_GROUP_DEFAULT',
    resource_type: 'NOTIFICATION_GROUP', resource_id: '14', name: 'UG Varsayılan Liste — Gece Nöbeti', team_id: 8, team_name: 'Uygulama Geliştirme Ekibi',
    changes: '{"isDefault":{"from":false,"to":true}}', detail: null },
  { id: 4, at: '2026-11-10T08:15:00', actor: 'ayse.yilmaz', area: 'oncall', action: 'SCHEDULE_SAVE', event_type: 'ONCALL_SCHEDULE_SAVE',
    resource_type: 'TEAM', resource_id: '7', name: LONG_TEAM, team_id: 7, team_name: LONG_TEAM,
    changes: null, detail: '{"enabled":true,"rotation":"WEEKLY","members":4,"notify_oncall":true}' },
]
const RES = { success: true, items: ITEMS, total: 4, page: 0, size: 25, total_pages: 1, truncated: false, hidden: 0, days: 30,
  areas: ['teams', 'users', 'contacts', 'thresholds', 'groups', 'forwards', 'sharing', 'oncall'] }

api.admin.historyFeed = async ({ areas = [] } = {}) => (
  areas.length ? { ...RES, items: ITEMS.filter(i => areas.includes(i.area)), total: ITEMS.filter(i => areas.includes(i.area)).length } : RES
)

createRoot(document.getElementById('host')).render(
  <LangProvider>
    <ToastProvider>
      <div style={{ padding: 16 }}>
        <AdminChangeFeed />
      </div>
    </ToastProvider>
  </LangProvider>,
)
