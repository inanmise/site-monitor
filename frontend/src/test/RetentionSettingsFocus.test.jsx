import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor } from './test-utils.jsx'
import RetentionSettings from '../components/admin/RetentionSettings.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ admin: { getRetentionOverview: vi.fn(), getRetentionRuns: vi.fn(), getRetentionChanges: vi.fn() } }),
}))
import { api } from '../api/client'

/**
 * Veri Saklama derin bağlantısı (2026-11-13): Sistem Sağlığı → Veritabanı büyümesi "Veri Saklama'da aç" →
 * `?ret_focus=<politika id>` politikanın sınıfını ve satırını açar ve satırı vurgular; bilinmeyen kimlik yok sayılır.
 */
const policy = (id, table, dataClass) => ({
  id, table, data_class: dataClass, mode: 'AGE', setting_key: `site.monitor.${id}.retention-days`, configurable: true, deletes: true,
  days: 90, default_days: 90, min_days: 7, rows: 1000, bytes: 1024, oldest_at: '2026-08-01T00:00:00', newest_at: '2026-11-01T00:00:00',
  purgeable: 0, rationale: '', rule: 'checked_at < ?', time_column: 'checked_at',
})
const overview = {
  success: true,
  data: {
    hold_active: false, hold_key: 'site.monitor.retention.hold-enabled', totals: { rows: 2000, bytes: 2048, purgeable: 0, policies: 2, tables: 2 },
    last_run: null, approvals: {},
    policies: [policy('series-port', 'port_checks', 'OPERATIONAL'), policy('activity-log', 'activity_log', 'PERSONAL')],
  },
}

beforeEach(() => {
  vi.clearAllMocks()
  api.admin.getRetentionOverview.mockResolvedValue(overview)
})
afterEach(() => { window.history.replaceState({}, '', '/') })

const row = (id) => document.querySelector(`[data-slot="policy-row"][data-policy-id="${id}"]`)

describe('RetentionSettings ret_focus', () => {
  it('politikanın sınıfı ve satırı açılır, satır vurgulanır ve odak alır', async () => {
    window.history.replaceState({}, '', '/?tab=settings&sec=retention&ret_focus=series-port')
    render(<RetentionSettings />)
    await waitFor(() => expect(row('series-port')).not.toBeNull())
    const el = row('series-port')
    expect(el).toHaveAttribute('data-focused', 'true')
    expect(el).toHaveAttribute('data-open', 'true')
    await waitFor(() => expect(document.activeElement).toBe(el))
    // Öbür sınıf kapalı kalır
    expect(row('activity-log')).toBeNull()
  })

  it('bilinmeyen kimlik yok sayılır (hiçbir sınıf açılmaz)', async () => {
    window.history.replaceState({}, '', '/?tab=settings&sec=retention&ret_focus=no-such-policy')
    render(<RetentionSettings />)
    await waitFor(() => expect(api.admin.getRetentionOverview).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 20))
    expect(document.querySelector('[data-slot="policy-row"]')).toBeNull()
  })
})
