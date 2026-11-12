import { createRoot } from 'react-dom/client'
import CoveragePanel from '../../src/components/admin/contacts/CoveragePanel.jsx'
import { LangProvider } from '../../src/i18n/index.jsx'
import '../../src/styles/globals.css'   // uygulamayla aynı kaskat: shadcn jetonları + Tailwind
import '../../src/App.css'

/**
 * Kişiler → eskalasyon KAPSAM matrisi paneli — izole harness (yalnız Playwright, ürüne girmez). Gerçek CoveragePanel
 * mount edilir; jsdom düzen yapmadığı için mweb (taşma / kap-genişliği tablo↔kart / dokunma hedefi) ancak burada ölçülür.
 * Veri sabit: 1=ok (WARNING+CRITICAL), 2=partial (yalnız CRITICAL), 3=gap (varlık var kişi yok), 4=noAssets. Uzun takım
 * adı bilinçli (ad kesilmez kuralı). Hızlı ekle → window.__quickAdd.
 */
const TEAMS = [
  { id: 1, name: 'Teknoloji Servis Yönetimi — Çok Uzun Takım Adı Örneği Bölümü' },
  { id: 2, name: 'Uygulama Geliştirme Ekibi' },
  { id: 3, name: 'Ağ ve Güvenlik Operasyonları Ekibi' },
  { id: 4, name: 'Arşiv (Pasif İzlemeler)' },
]
const CONTACTS = [
  { id: 1, team_id: 1, min_alert_level: 'WARNING', active: true, user_id: 11 },
  { id: 2, team_id: 1, min_alert_level: 'CRITICAL', active: true, user_id: 12 },
  { id: 3, team_id: 2, min_alert_level: 'CRITICAL', active: true, user_id: 13 },
]
const STATS = { 1: { monitors: 5 }, 2: { monitors: 3 }, 3: { monitors: 2 }, 4: {} }

createRoot(document.getElementById('host')).render(
  <LangProvider>
    <div style={{ padding: 16 }}>
      <CoveragePanel contacts={CONTACTS} teams={TEAMS} inactiveIds={new Set()} stats={STATS}
        canManage onQuickAdd={(id) => { window.__quickAdd = id }} />
    </div>
  </LangProvider>,
)
