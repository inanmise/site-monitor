import { createRoot } from 'react-dom/client'
import { api } from '../../src/api/client'
import NotificationGroups from '../../src/components/admin/NotificationGroups.jsx'
import { LangProvider } from '../../src/i18n/index.jsx'
import { ToastProvider } from '../../src/components/ui/Toast.jsx'
import { DialogProvider } from '../../src/components/ui/Dialog.jsx'
import '../../src/styles/globals.css'   // uygulamayla aynı kaskat: shadcn jetonları + Tailwind
import '../../src/App.css'

/**
 * Bildirim Grupları TEMİZLİK ekranı (2026-11-12, #7) — izole harness (yalnız Playwright). Gerçek NotificationGroups
 * mount edilir; ekran kendi verisini çektiği için singleton {@code api.notificationGroups} ağ ÇAĞIRMADAN sabit yanıtla
 * değiştirilir (bileşen çağrı anında okur). jsdom düzen yapmadığı için mweb (taşma / KPI şeridi / "kullanılmayan"
 * süzgeci / satır kullanım rozeti / ≥40 px dokunma / uzun takım adı kesilmez) ancak burada ölçülür.
 *
 * Veri: g10 kullanımda (3 izleme), g11 kullanılmıyor (0), g12 adressiz + kullanılmıyor (0), g13 pasif.
 * Alıcı önizlemesi (2026-11-13): g14 takım 2'nin varsayılanı, TÜM adresleri pasif hesaba ait (sessiz kayıp bandı);
 * g15 kısmi pasif. usageCounts={10:3, 14:1, 15:2} (kullanılmayan sayısı 2 kalır).
 */
const TEAMS = [
  { id: 1, name: 'Teknoloji Servis Yönetimi — Çok Uzun Takım Adı Örneği Bölümü ve Devamı' },
  { id: 2, name: 'Uygulama Geliştirme Ekibi' },
]
const GROUPS = [
  { id: 10, team_id: 1, name: 'SY Nöbet Listesi', emails: ['a@x', 'b@x'], inactive_emails: [], is_default: true, active: true, can_write: true },
  { id: 11, team_id: 1, name: 'Eski Ekip Dağıtımı', emails: ['c@x'], inactive_emails: [], is_default: false, active: true, can_write: true },
  { id: 12, team_id: 2, name: 'Adressiz Grup', emails: [], inactive_emails: [], is_default: false, active: true, can_write: true },
  { id: 13, team_id: 2, name: 'Arşivlenen Grup', emails: ['d@x'], inactive_emails: [], is_default: false, active: false, can_write: true },
  { id: 14, team_id: 2, name: 'UG Varsayılan Liste — Tümü Pasif Hesaplar', emails: ['eski.calisan.adi.soyadi@example.com.tr'],
    inactive_emails: ['eski.calisan.adi.soyadi@example.com.tr'], is_default: true, active: true, can_write: true },
  { id: 15, team_id: 1, name: 'Kısmi Pasif Dağıtım', emails: ['aktif.kisi@example.com', 'ayrilan.kisi.cok.uzun.adres.ornegi@example.com.tr'],
    inactive_emails: ['ayrilan.kisi.cok.uzun.adres.ornegi@example.com.tr'], is_default: false, active: true, can_write: true },
]

api.notificationGroups = {
  list: async () => ({ success: true, data: { groups: GROUPS, writable_team_ids: ['1', '2'], team_emails: { 1: 'sy@x', 2: 'ug@x' } } }),
  usageCounts: async () => ({ success: true, data: { counts: { 10: 3, 14: 1, 15: 2 } } }),
  history: async () => ({ success: true, data: { items: [], truncated: false, hidden: 0 } }),
  create: async () => ({ success: true, data: {} }),
  update: async () => ({ success: true, data: {} }),
  remove: async () => ({ success: true }),
  reassign: async () => ({ success: true, data: {} }),
  makeDefault: async () => ({ success: true, data: {} }),
}

createRoot(document.getElementById('host')).render(
  <LangProvider>
    <ToastProvider>
      <DialogProvider>
        <div style={{ padding: 16 }}>
          <NotificationGroups teams={TEAMS} systemRole="ADMIN" />
        </div>
      </DialogProvider>
    </ToastProvider>
  </LangProvider>,
)
