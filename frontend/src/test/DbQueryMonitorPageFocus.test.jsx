import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, fireEvent, waitFor, within, act } from './test-utils.jsx'

/**
 * Veritabanı Sorgu İzleme sayfası — ODAK GÖRÜNÜMLERİ (2026-11-05, dbq2 C3): "Şu an ihlalde" hızlı süzgeci (`dq=breach`) ve
 * bağlantıya göre gruplu liste (`sort=conn`). İkisi de URL'de; grup başlığında bağlantı adı + durum özeti + okunabilirse
 * bağlantı sağlığı. Yapı taşları ince çiftlerle değiştirilir (sayfa testinin düzeni).
 */
const toastMock = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), dismiss: vi.fn() }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
vi.mock('../components/dbquery/buildingBlocks.jsx', () => {
  const none = () => null
  return {
    RuleBuilder: none, ScheduleEditor: none, SqlEditor: none, ConnectionPicker: none, ConnectionsManager: none, TemplatePicker: none,
    SaveTemplateDialog: none, TemplatesManager: none, DbQueryAlertRows: none,
    RuleResultsList: ({ results }) => <ul data-slot="t-rule-results">{(results || []).map((r) => <li key={r.id}>{r.label}</li>)}</ul>,
  }
})

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      getDbQueryMonitors: vi.fn(), dbConnections: vi.fn(), listGroups: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      listTags: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults: vi.fn(() => Promise.resolve({ success: true, data: { dbquery: {} } })),
    },
    admin: { getTeams: vi.fn() },
  }),
}))
import { api } from '../api/client'
import DbQueryMonitorPage from '../components/DbQueryMonitorPage.jsx'

const P1 = { id: 3, name: 'Sipariş DB (PostgreSQL üretim — Kurumsal Sipariş Yönetim Sistemi okuma kopyası)', db_type: 'POSTGRESQL' }
const P2 = { id: 4, name: 'MUTABAKAT-MSSQL-01', db_type: 'MSSQL' }
const row = (id, over = {}) => ({
  id, name: `İzleme ${id}`, active: true, team_id: 5, team_name: 'SY-A', group_name: 'Kurumsal', tags: 'prod', status: 'OK', breach_level: 'NONE',
  profile_id: P1.id, profile: P1, purpose: 'Amaç metni', sql_text: 'SELECT 1', rules: [], rule_logic: 'ANY', schedule_mode: 'INTERVAL', interval_seconds: 300,
  metric_value: 1, row_count: 1, duration_ms: 100, last_check: '2026-11-04T10:00:00', can_check: true, can_edit: true, can_delete: true, ...over,
})
const LIST = [
  row(1, { name: 'A temiz' }),
  row(2, { name: 'B eşik', status: 'BREACH', breach_level: 'WARNING' }),
  row(3, { name: 'C hata', status: 'FAIL', profile_id: P2.id, profile: P2 }),
  row(4, { name: 'D alarm açık', active_alarm: true, profile_id: P2.id, profile: P2 }),
  row(5, { name: 'E duraklatılmış', active: false, status: 'BREACH', profile_id: P2.id, profile: P2 }),
  row(6, { name: 'F bilinmiyor', status: 'UNKNOWN' }),
]
const props = { systemRole: 'ADMIN', teamId: 5, teamName: 'SY-A', myTeams: [{ id: 5, name: 'SY-A' }], globalAdmin: true }
const titles = () => [...document.querySelectorAll('.upt-grid [data-monitor-open]')].map((b) => b.textContent)
const param = (k) => new URLSearchParams(window.location.search).get(k)
const flushUrl = () => act(async () => { await new Promise((r) => setTimeout(r, 400)) })

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=dbquery')
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
  api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: LIST })
  api.monitoring.dbConnections.mockResolvedValue({ success: true, data: [{ id: 3, health: { state: 'ok' } }, { id: 4, health: { state: 'failing' } }] })
})
afterEach(() => window.history.replaceState({}, '', '/'))

describe('DbQueryMonitorPage — "Şu an ihlalde" (C3)', () => {
  it('sayaç (eşik + hata + açık alarm, duraklatılmış hariç); basınca süzer ve URL dq=breach; bırakınca geri', async () => {
    render(<DbQueryMonitorPage {...props} />)
    await waitFor(() => expect(titles()).toHaveLength(6))
    const btn = document.querySelector('[data-slot="dbq-focus-breach"]')
    expect(btn).toHaveAttribute('aria-pressed', 'false')
    expect(btn.querySelector('[data-slot="dbq-focus-breach-count"]')).toHaveTextContent('3')
    fireEvent.click(btn)
    expect(btn).toHaveAttribute('aria-pressed', 'true')
    expect(titles().sort()).toEqual(['B eşik', 'C hata', 'D alarm açık'])
    await flushUrl()
    expect(param('dq')).toBe('breach')
    fireEvent.click(btn)
    expect(titles()).toHaveLength(6)
    await flushUrl()
    expect(param('dq')).toBeNull()
  })

  it('URL\'den (Panom bağlantısı) açılır; ihlal yoksa "Şu an ihlalde izleme yok" + süzgeci kaldır', async () => {
    window.history.replaceState({}, '', '/?tab=dbquery&dq=breach')
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [row(1), row(6, { status: null })] })
    render(<DbQueryMonitorPage {...props} />)
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-focus-empty"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="dbq-focus-breach"]')).toHaveAttribute('aria-pressed', 'true')
    expect(titles()).toEqual([])
    fireEvent.click(within(document.querySelector('[data-slot="dbq-focus-empty"]')).getByRole('button', { name: /Süzgeci kaldır|Clear the filter/ }))
    expect(titles()).toHaveLength(2)
  })
})

describe('DbQueryMonitorPage — bağlantıya göre gruplu liste (C3)', () => {
  it('gruplar en kötüden: başlıkta bağlantı adı (tam), tür, durum özeti, bağlantı sağlığı; kartlar grubun içinde; URL sort=conn', async () => {
    render(<DbQueryMonitorPage {...props} />)
    await waitFor(() => expect(titles()).toHaveLength(6))
    fireEvent.click(document.querySelector('[data-slot="dbq-group-conn"]'))
    const groups = document.querySelectorAll('[data-slot="dbq-conn-group"]')
    expect(groups).toHaveLength(2)
    expect(groups[0].getAttribute('data-key')).toBe('p:4')
    const head0 = groups[0].querySelector('[data-slot="dbq-conn-group-head"]')
    expect(head0.getAttribute('data-worst')).toBe('fail')
    expect(head0).toHaveTextContent(P2.name)
    expect(head0).toHaveTextContent('SQL Server')
    const kinds0 = [...head0.querySelectorAll('[data-slot="dbq-conn-group-count"]')].map((b) => b.getAttribute('data-kind'))
    expect(kinds0).toEqual(['total', 'fail', 'alarm', 'paused'])
    const head1 = groups[1].querySelector('[data-slot="dbq-conn-group-head"]')
    expect(head1).toHaveTextContent(P1.name)
    // Grup içinde varsayılan kart sırası (sorunlu önce, sonra ad)
    expect([...groups[1].querySelectorAll('[data-monitor-open]')].map((b) => b.textContent)).toEqual(['B eşik', 'A temiz', 'F bilinmiyor'])
    await waitFor(() => expect(head0.querySelector('[data-slot="dbq-conn-group-health"]')).toHaveAttribute('data-state', 'failing'))
    expect(head1.querySelector('[data-slot="dbq-conn-group-health"]')).toHaveAttribute('data-state', 'ok')
    expect(api.monitoring.dbConnections).toHaveBeenCalledTimes(1)
    expect(api.monitoring.dbConnections).toHaveBeenCalledWith('5')
    await flushUrl()
    expect(param('sort')).toBe('conn')
  })

  it('bağlantı listesi okunamazsa sağlık "okunamadı" (asla sağlıklı değil); gruplu + ihlal süzgeci birlikte', async () => {
    window.history.replaceState({}, '', '/?tab=dbquery&sort=conn&dq=breach')
    api.monitoring.dbConnections.mockResolvedValue({ success: false, status: 403, error: 'yok' })
    render(<DbQueryMonitorPage {...props} />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="dbq-conn-group"]')).toHaveLength(2))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-conn-group-health"]')).toHaveAttribute('data-state', 'unreadable'))
    expect(titles().sort()).toEqual(['B eşik', 'C hata', 'D alarm açık'])
    // Başlık özeti TÜM süzülmüş listeden (gruptaki ihlal sayısı)
    const g1 = document.querySelectorAll('[data-slot="dbq-conn-group"]')[1]
    expect(g1.querySelector('[data-kind="total"]')).toHaveTextContent('1')
  })

  it('gruplu görünüm kapalıyken bağlantı listesi OKUNMAZ (istek yok)', async () => {
    render(<DbQueryMonitorPage {...props} />)
    await waitFor(() => expect(titles()).toHaveLength(6))
    expect(document.querySelector('[data-slot="dbq-conn-groups"]')).toBeNull()
    expect(api.monitoring.dbConnections).not.toHaveBeenCalled()
  })
})
