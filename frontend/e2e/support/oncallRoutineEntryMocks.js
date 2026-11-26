// Nöbet Rutinleri giriş noktaları (2026-11-26) — `oncallRoutineMocks.js`'e EK sahte uçlar (o dosyaya dokunulmaz; başka bir
// ajan genişletiyor). Kayıt sırası önemli: Playwright son kaydedilen route'u ÖNCE dener — bu modül `mockOncallRoutines`'tan
// SONRA bağlanırsa `/api/oncall-routines/me` yanıtı buradaki zengin gövde olur, diğer rutin uçları ana sahte sunucuda kalır.
//
//  - `/api/oncall-routines/me` (menü rozeti + Kişisel Panom "Nöbet rutinim" kutusu): sunucu sözleşmesinin TAM satırı
//    (`OnCallRoutineController.me`: workday, pending, problems_open, responsible_me, responsible_name, next_reminder,
//    handover_pending_ack). Rozet sunucu kuralıyla tutarlı: sayı = nöbetçisi olduğum takımlarda bekleyen zorunlu görev (4),
//    seviye = geciken var → critical.
//  - `/api/admin/oncall-routines/settings` (GET / PUT) ve `/overview` (Ayarlar → Nöbet rutinleri): durumlu; PUT gövdeleri
//    kaydedilir, yanıt birleşmiş değerleri döner.
import {
  OR_TEAM_ID, OR_TEAM_NAME, OR_OTHER_TEAM_ID, OR_OTHER_TEAM_NAME, OR_TODAY, OR_NOW, OR_SELIN,
} from './oncallRoutineMocks.js'

/** Rutini tamamlanmış ikinci takım (gerçek uzunlukta AD adı). */
export const ORE_DONE_TEAM_ID = 43
export const ORE_DONE_TEAM_NAME = 'Kurumsal Web ve Mobil Bankacılık Uygulama Destek Takımı (Gündüz Vardiyası / İkinci Hat)'
/** Bugün iş günü olmayan takım (vardiyalı ekip). */
export const ORE_OFF_TEAM_ID = 46
export const ORE_OFF_TEAM_NAME = 'Gece Operasyonları ve Hafta Sonu Nöbet Ekibi (Vardiyalı Çalışma Düzeni / Üçüncü Hat)'
/** Ayarlar özetinde duraklatılmış rutinli takım. */
export const ORE_PAUSED_TEAM_ID = 44
export const ORE_PAUSED_TEAM_NAME = 'Altyapı Otomasyonu ve Gözlemlenebilirlik Platformu Takımı (Kapasite Planlama)'

/** Menü rozeti — takım 41'de bekleyen 4 zorunlu görev, 2'si gecikmiş. */
export const ORE_BADGE = Object.freeze({ count: 4, level: 'critical' })
/** Takım 41'in bugünkü sayıları (kutu ve rozet bunlardan okunur). */
export const ORE_MY_DAY = Object.freeze({ required: 6, resolved: 2, pending: 4, overdue: 2, problems_open: 2 })
/** Saklama süresi (gün) — ayar sayfası notu. */
export const ORE_RETENTION_DAYS = 400

const clone = (x) => JSON.parse(JSON.stringify(x))

/** `/api/oncall-routines/me` gövdesi (sunucu sözleşmesinin tam satırları). */
export function routineMeBody() {
  return {
    generated_at: OR_NOW,
    badge: { ...ORE_BADGE },
    teams: [
      {
        team_id: OR_TEAM_ID, team_name: OR_TEAM_NAME, date: OR_TODAY, workday: true, status: 'OPEN',
        ...ORE_MY_DAY, responsible_me: true, responsible_name: null, next_reminder: '16:30', handover_pending_ack: true,
      },
      {
        team_id: ORE_DONE_TEAM_ID, team_name: ORE_DONE_TEAM_NAME, date: OR_TODAY, workday: true, status: 'COMPLETED',
        required: 6, resolved: 6, pending: 0, overdue: 0, problems_open: 0,
        responsible_me: false, responsible_name: OR_SELIN.name, next_reminder: null, handover_pending_ack: false,
      },
      {
        team_id: ORE_OFF_TEAM_ID, team_name: ORE_OFF_TEAM_NAME, date: OR_TODAY, workday: false, status: 'NOT_WORKDAY',
        required: 0, resolved: 0, pending: 0, overdue: 0, problems_open: 0,
        responsible_me: false, responsible_name: null, next_reminder: null, handover_pending_ack: false,
      },
    ],
  }
}

/** Ayar sayfasının bildiği takımlar (modül görünürlüğü seçicisinin seçenekleri). */
const SETTINGS_TEAMS = [
  { id: OR_TEAM_ID, name: OR_TEAM_NAME },
  { id: OR_OTHER_TEAM_ID, name: OR_OTHER_TEAM_NAME },
  { id: ORE_DONE_TEAM_ID, name: ORE_DONE_TEAM_NAME },
  { id: ORE_PAUSED_TEAM_ID, name: ORE_PAUSED_TEAM_NAME },
  { id: ORE_OFF_TEAM_ID, name: ORE_OFF_TEAM_NAME },
]

/** Kayıtlı değerler: üç takımda açık, hatırlatma + devir açık, "tamamlandı" bildirimi kapalı. */
export const ORE_SETTINGS_VALUES = Object.freeze({
  team_ids: [OR_TEAM_ID, ORE_DONE_TEAM_ID, ORE_PAUSED_TEAM_ID, ORE_OFF_TEAM_ID],
  push_reminders: true, completion_notice: false, handover_notice: true,
})

/** `/api/admin/oncall-routines/overview` satırları: aktif (açık gün), kurulmamış + modül kapalı, tamamlanmış, duraklatılmış. */
export function adminOverviewRows() {
  return [
    { team_id: OR_TEAM_ID, team_name: OR_TEAM_NAME, module_visible: true, configured: true, enabled: true, tasks_active: 21,
      today_status: 'OPEN', last30: { workdays: 21, completed: 17, partial: 2, incomplete: 1, missed: 1 } },
    { team_id: OR_OTHER_TEAM_ID, team_name: OR_OTHER_TEAM_NAME, module_visible: false, configured: false, enabled: false, tasks_active: 0,
      today_status: 'NONE', last30: { workdays: 0, completed: 0, partial: 0, incomplete: 0, missed: 0 } },
    { team_id: ORE_DONE_TEAM_ID, team_name: ORE_DONE_TEAM_NAME, module_visible: true, configured: true, enabled: true, tasks_active: 14,
      today_status: 'COMPLETED', last30: { workdays: 22, completed: 22, partial: 0, incomplete: 0, missed: 0 } },
    { team_id: ORE_PAUSED_TEAM_ID, team_name: ORE_PAUSED_TEAM_NAME, module_visible: true, configured: true, enabled: false, tasks_active: 9,
      today_status: 'PAUSED', last30: { workdays: 0, completed: 0, partial: 0, incomplete: 0, missed: 0 } },
  ]
}

const fulfill = (route, body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

/**
 * Ek sahte uçları bağlar. `mockApi` ve `mockOncallRoutines`'tan SONRA çağrılmalı (yoksa `/me` ana sahte sunucudan gelir).
 * @returns {{ meCalls: number, settingsGets: number, overviewGets: number, puts: object[], values: object }}
 */
export async function mockOncallRoutineEntry(page, { me = routineMeBody, overview = adminOverviewRows } = {}) {
  const st = { meCalls: 0, settingsGets: 0, overviewGets: 0, puts: [], values: clone(ORE_SETTINGS_VALUES) }

  await page.route((u) => new URL(u).pathname === '/api/oncall-routines/me', (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    st.meCalls += 1
    return fulfill(route, { success: true, data: me(), timestamp: OR_NOW })
  })

  const settingsData = () => ({
    values: clone(st.values), retention_days: ORE_RETENTION_DAYS, read_only: false, teams: clone(SETTINGS_TEAMS),
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/admin/oncall-routines/'), (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    const method = req.method()
    if (p === '/api/admin/oncall-routines/settings' && method === 'GET') {
      st.settingsGets += 1
      return fulfill(route, { success: true, data: settingsData() })
    }
    if (p === '/api/admin/oncall-routines/settings' && method === 'PUT') {
      let body = {}
      try { body = JSON.parse(req.postData() || '{}') } catch { /* boş gövde */ }
      st.puts.push(body)
      st.values = { ...st.values, ...body }
      return fulfill(route, { success: true, data: settingsData() })
    }
    if (p === '/api/admin/oncall-routines/overview' && method === 'GET') {
      st.overviewGets += 1
      return fulfill(route, { success: true, data: overview() })
    }
    return fulfill(route, { success: false, code: 'NOT_FOUND', error: `mock: unknown admin routine endpoint ${method} ${p}` }, 404)
  })
  return st
}
