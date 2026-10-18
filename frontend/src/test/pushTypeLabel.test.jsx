import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { familyLabel, PUSH_TYPE_ALIASES } from '../utils/pushPrefs.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, 0] }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => true, canExecute: () => true, perms: {} }),
}))
vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }) => <div>{children}</div>,
  BarChart: ({ children }) => <div>{children}</div>,
  Bar: () => null, XAxis: () => null, YAxis: () => null, CartesianGrid: () => null, Tooltip: () => null, Legend: () => null,
}))
const { pushLog } = vi.hoisted(() => ({
  pushLog: { search: vi.fn(), summary: vi.fn(), export: vi.fn(), detail: vi.fn(), requeue: vi.fn() },
}))
vi.mock('../api/client', () => ({
  formatDate: (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : ''),
  api: withApiFallback({ me: { getPushHistory: vi.fn() }, admin: { pushLog } }),
}))
import { api } from '../api/client'
import PushHistorySection from '../components/myactivity/PushHistorySection.jsx'
import PushLogView from '../components/admin/PushLogView.jsx'

/**
 * Push türü etiketi (inceleme 2026-10-18): "Push bildirimlerim"de teknik hata raporu push'u ham "TECH_ERROR_REPORT" rozetiyle
 * görünüyordu (`userpush.type.tech_error_report` aranıyordu — yok; olsa da adı "error" içerdiği için hata iletisi kapısına
 * takılırdı). Aile olmayan türler `PUSH_TYPE_ALIASES` ile adı "error" içermeyen anahtarlara gider; bilinmeyen tür "Diğer
 * bildirim" olur — ham tür hiçbir yerde (Push bildirimlerim, yönetici push günlüğü) görünmez.
 */
const tOf = (dict) => (key) => (Object.prototype.hasOwnProperty.call(dict, key) ? dict[key] : key)
/** Sunucunun push satırlarına yazdığı türler (UserPushService / takım bildirimleri / haftalık / fırtına / teknik hata). */
const SERVER_TYPES = ['cert', 'domain', 'http', 'ping', 'port', 'dns', 'keyword', 'page', 'pagespeed', 'scripted',
  'CERTIFICATE', 'CERT', 'DOMAIN', 'SCRIPTED', 'STORM', 'WEEKLY_REPORT', 'TECH_ERROR_REPORT']

describe('familyLabel — push türü etiketi', () => {
  it('her sunucu türü TR ve EN etiketli; ham tür / anahtar asla dönmez', () => {
    for (const dict of [TR, EN]) {
      const t = tOf(dict)
      for (const type of SERVER_TYPES) {
        const label = familyLabel(type, t)
        expect(label, `${type}`).not.toBe(type)
        expect(label, `${type}`).not.toMatch(/^userpush\.type\./)
        expect(label, `${type}`).not.toMatch(/_/)
        expect(label.trim().length).toBeGreaterThan(1)
      }
    }
    expect(familyLabel('TECH_ERROR_REPORT', tOf(EN))).toBe('Channel technical error reports')
    expect(familyLabel('TECH_ERROR_REPORT', tOf(TR))).toBe('Kanal teknik hata raporları')
    expect(familyLabel('WEEKLY_REPORT', tOf(TR))).toBe('Haftalık rapor')
    expect(familyLabel('STORM', tOf(EN))).toBe('Alert storm')
    expect(familyLabel('CERTIFICATE', tOf(EN))).toBe(EN['userpush.type.cert'])
    expect(familyLabel('http', tOf(EN))).toBe('HTTP')
  })

  it('bilinmeyen tür "Diğer bildirim" / "Other notification"; boş tür boş', () => {
    expect(familyLabel('SOME_NEW_TYPE', tOf(EN))).toBe('Other notification')
    expect(familyLabel('some_new_type', tOf(TR))).toBe('Diğer bildirim')
    expect(familyLabel('', tOf(EN))).toBe('')
    expect(familyLabel(null, tOf(EN))).toBe('')
  })

  it('takma ad anahtarlarının adı "error" içermez (hata iletisi kapısı) ve iki dilde var', () => {
    for (const target of Object.values(PUSH_TYPE_ALIASES)) {
      const key = `userpush.type.${target}`
      expect(key).not.toMatch(/error|Error|failed|Failed|err\./)
      expect(TR[key], key).toBeTruthy()
      expect(EN[key], key).toBeTruthy()
    }
  })
})

const at = new Date(Date.now() - 60_000).toISOString().slice(0, 19)

describe('ham tür ekranda görünmez', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/?tab=health&view=push')
  })

  it('Push bildirimlerim: teknik hata raporu, haftalık rapor ve fırtına satırının rozeti etiketle', async () => {
    const rows = [
      { id: 3, at, trigger: 'TECH_ERROR_SENT', status: 'SENT', kind: 'sent', own: true, team_decision: false, visible: true, scope: 'own',
        alert_level: 'WARNING', monitor_type: 'TECH_ERROR_REPORT', lang: 'en', target: 'Mobil September 2026', message: '[Technical error report] sent' },
      { id: 2, at, trigger: 'WEEKLY_REPORT', status: 'SENT', kind: 'sent', own: true, team_decision: false, visible: true, scope: 'own',
        alert_level: 'WARNING', monitor_type: 'WEEKLY_REPORT', lang: 'en', target: 'Hafta 41', message: 'weekly' },
      { id: 1, at, trigger: 'STORM', status: 'SENT', kind: 'sent', own: true, team_decision: false, visible: true, scope: 'own',
        alert_level: 'HIGH', monitor_type: 'STORM', lang: 'en', target: 'Takım A', message: 'storm' },
    ]
    api.me.getPushHistory.mockResolvedValue({ success: true, data: rows, total: rows.length, page: 0, size: 25, days: 7, filter: 'all',
      kpis: { sent: 3, pending: 0, not_sent: 0, not_sent_by_reason: {}, summarized: 0, team_decisions: 0, team_decisions_by_reason: {} } })
    render(<PushHistorySection />)
    const table = await screen.findByTestId('ph-table')
    const badges = [...table.querySelectorAll('[data-slot="badge"]')].map((b) => b.textContent)
    expect(badges).toEqual(expect.arrayContaining(['Channel technical error reports', 'Weekly report', 'Alert storm']))
    expect(document.body.textContent).not.toMatch(/TECH_ERROR_REPORT|WEEKLY_REPORT|STORM/)
  })

  it('yönetici push günlüğü: tablo, kırılım paneli ve TypeBadge etiketle (ham tür yok)', async () => {
    const rows = [
      { id: 7, trigger: 'TECH_ERROR_SEND_FAILED', monitor_type: 'TECH_ERROR_REPORT', monitor_name: 'Mobil Eylül 2026', username: 'u1',
        display_name: 'Bir', status: 'SENT', kind: 'SENT', at: '2026-10-18T11:30:00', alert_level: 'WARNING' },
      { id: 6, trigger: 'OPEN', monitor_type: 'CERTIFICATE', monitor_name: 'shop.example.com', username: 'u2', display_name: 'İki',
        status: 'SENT', kind: 'SENT', at: '2026-10-18T11:20:00', alert_level: 'HIGH' },
    ]
    pushLog.summary.mockResolvedValue({ success: true, data: { granularity: 'day', kpi: { total: 2, sent: 2, failed: 0, blocked: 0, success_rate: 100 },
      timeline: [], teams: [], levels: [], top_users: [],
      top_monitors: [{ monitor_type: 'TECH_ERROR_REPORT', monitor_id: null, monitor_name: 'Mobil Eylül 2026', total: 1, failed: 0 }] } })
    pushLog.search.mockResolvedValue({ success: true, data: rows, total: rows.length })
    render(<PushLogView onBack={() => {}} />)
    const table = await screen.findByTestId('sml-table')
    expect(within(table).getByText('Channel technical error reports')).toBeInTheDocument()
    expect(within(table).getByText(EN['userpush.type.cert'])).toBeInTheDocument()
    await screen.findAllByText('Channel technical error reports')
    expect(document.body.textContent).not.toMatch(/TECH_ERROR_REPORT|CERTIFICATE/)
  })
})
