import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, waitFor } from './test-utils.jsx'

// Alarm ayrıntısı (AlertDetailBody) → DBQUERY_* alarmında "Alarm anındaki sorgu sonucu" bölümü; diğer türlerde bölüm yok
// ve satır isteği atılmaz.
const { apiMock } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
      return t[prop]
    },
  })
  return { apiMock: deep({ incidents: {}, admin: {}, monitoring: {} }) }
})
vi.mock('../api/client', () => ({ api: apiMock, formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => s ?? '' }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: {}, canView: () => true, canEdit: () => true, canExecute: () => false, refresh: () => {} }),
  PermissionsProvider: ({ children }) => children,
}))
import { api } from '../api/client'
import { AlertDetailBody } from '../components/admin/alerts/AlertDetail.jsx'

const BASE = { id: 501, domain: 'Bekleyen siparişler', alert_level: 'CRITICAL', resolved: false, acknowledged: false,
  created_at: '2026-10-31T06:00:00', team_id: 1, team_name: 'Takım A', message: 'Satır sayısı 120 (> 100)' }
const NOW = Date.parse('2026-10-31T07:00:00Z')

beforeEach(() => {
  vi.clearAllMocks()
  api.incidents.byAlert.mockResolvedValue({ success: true, data: [] })
  api.monitoring.dbQueryAlertRows.mockResolvedValue({ success: true, data: {
    captured_at: '2026-10-31T06:00:00', columns: ['SIPARIS_NO'], rows: [['10001']], masked: [], row_count: 1, row_count_capped: false, truncated: false, hidden: false,
  } })
})

describe('AlertDetailBody — veritabanı sorgu alarmı satırları', () => {
  it('DBQUERY_THRESHOLD alarmında bölüm çizilir ve satırlar gelir', async () => {
    render(<AlertDetailBody alert={{ ...BASE, alert_type: 'DBQUERY_THRESHOLD' }} teamName="Takım A" nowMs={NOW} />)
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-alert-rows"][data-state="ok"]')).not.toBeNull())
    expect(api.monitoring.dbQueryAlertRows).toHaveBeenCalledWith(501)
    expect(document.querySelectorAll('[data-slot="dbq-alert-row"]')).toHaveLength(1)
  })

  it('başka türde (PING_DOWN) bölüm yok, istek yok', async () => {
    render(<AlertDetailBody alert={{ ...BASE, alert_type: 'PING_DOWN' }} teamName="Takım A" nowMs={NOW} />)
    await waitFor(() => expect(api.incidents.byAlert).toHaveBeenCalled())
    expect(document.querySelector('[data-slot="dbq-alert-rows"]')).toBeNull()
    expect(api.monitoring.dbQueryAlertRows).not.toHaveBeenCalled()
  })
})
