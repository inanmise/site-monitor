import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act, fillGroupAndTags } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * Veritabanı Sorgu İzleme sayfası — r177 (2026-10-31) davranışları: UNKNOWN "değerlendirilemedi" (kart / ayrıntı),
 * mail kolon kipi (yeni izlemede SELECTED; eski kayıt FIRST_N, değişmezse gönderilmez), maliyet OFF, bölüm çubuğunda hata
 * sayısı, COUNT tuzağı + yeni kural varsayılanı, deneme kolonundan kural / maske, hassas kolon önerisi, kayıt uyarıları,
 * yazabilen hesap reddi, "Bu şablonla izleme oluştur", geriye dönük deneme paneli. Yapı taşları (kural oluşturucu,
 * zamanlama, sorgu düzenleyici …) DbQueryMonitorPage.test.jsx ile aynı ince çiftlerle değiştirilir.
 */
const toastMock = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), dismiss: vi.fn() }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))

const blocks = vi.hoisted(() => ({ props: {} }))
vi.mock('../components/dbquery/buildingBlocks.jsx', () => {
  const remember = (name) => (props) => { blocks.props[name] = props; return null }
  return {
    RuleBuilder: (p) => {
      blocks.props.RuleBuilder = p
      return <div data-field="rules" data-slot="t-rule-builder" />
    },
    ScheduleEditor: (p) => { blocks.props.ScheduleEditor = p; return <div data-slot="t-schedule" /> },
    SqlEditor: (p) => {
      blocks.props.SqlEditor = p
      return (
        <div data-field="sql_text">
          <textarea aria-label="sql" value={p.value} onChange={(e) => p.onChange(e.target.value)} />
          {p.fieldError && <p data-slot="t-sql-error">{p.fieldError}</p>}
        </div>
      )
    },
    ConnectionPicker: (p) => {
      blocks.props.ConnectionPicker = p
      return (
        <div data-field="profile_id" data-slot="t-conn-picker">
          <button type="button" onClick={() => p.onChange(3, { id: 3, name: 'Sipariş DB', db_type: 'POSTGRESQL' })}>pick-connection</button>
          {p.error && <p data-slot="t-conn-error">{p.error}</p>}
        </div>
      )
    },
    ConnectionsManager: remember('ConnectionsManager'),
    TemplatePicker: () => null,
    SaveTemplateDialog: remember('SaveTemplateDialog'),
    TemplatesManager: remember('TemplatesManager'),
    RuleResultsList: ({ results }) => <ul data-slot="t-rule-results">{(results || []).map((r) => <li key={r.id}>{r.label}</li>)}</ul>,
    DbQueryAlertRows: () => null,
  }
})

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      getDbQueryMonitors: vi.fn(), createDbQueryMonitor: vi.fn(), updateDbQueryMonitor: vi.fn(), deleteDbQueryMonitor: vi.fn(),
      triggerDbQueryCheck: vi.fn(), testDbQuery: vi.fn(), dbQueryPlanCheck: vi.fn(), dbConnections: vi.fn(), dbQueryBacktest: vi.fn(),
      listGroups: vi.fn(() => Promise.resolve({ success: true, data: [{ name: 'Kurumsal' }] })),
      listTags: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults: vi.fn(),
      getCheckHistory: vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  }),
}))
import { api } from '../api/client'
import DbQueryMonitorPage from '../components/DbQueryMonitorPage.jsx'

const PROFILE = { id: 3, name: 'Sipariş DB', db_type: 'POSTGRESQL', host: 'db.example.com', port: 5432, database_name: 'orders' }
const row = (over = {}) => ({
  id: 1, name: 'Sipariş kuyruğu', active: true, team_id: 5, team_name: 'SY-A', group_name: 'Kurumsal', tags: 'prod',
  notification_group_id: null, noc_notify: false, noc_group_ids: [], alert_level: 'WARNING', notify_email: true, notify_webhook: true,
  confirm_attempts: 1, confirm_interval_seconds: 30, recovery_checks: 1, recovery_interval_seconds: 60,
  profile_id: 3, profile: PROFILE, purpose: 'Bekleyen sipariş sayısını izler', sql_text: 'SELECT count(*) AS bekleyen\nFROM orders',
  query_timeout_seconds: 30, max_rows: 1000, rule_logic: 'ANY', slow_query_ms: null,
  rules: [{ id: 'r1', label: 'Bekleyen', kind: 'ROW_COUNT', operator: 'GT', warning: 10 }],
  schedule_mode: 'INTERVAL', interval_seconds: 300, cron_expression: '', windows: [], blackouts: [], skip_holidays: false, skip_dates: [],
  next_run_at: '2026-10-31T10:05:00', mail_include_rows: true, mail_columns: [], masked_columns: [], mail_subject_template: null,
  status: 'OK', breach_level: 'NONE', ok: true, duration_ms: 240, row_count: 1, row_count_capped: false, metric_value: 12, slow: false,
  error: null, failure_reason: null, last_check: '2026-10-31T10:00:00',
  rule_results: [{ id: 'r1', label: 'Bekleyen', kind: 'ROW_COUNT', actual: 12, expected: '> 10', level: 'NONE', passed: true, evaluated: true }],
  can_check: true, can_edit: true, can_delete: true,
  ...over,
})
const props = { systemRole: 'ADMIN', teamId: 5, teamName: 'SY-A', myTeams: [{ id: 5, name: 'SY-A' }], globalAdmin: true }
const titles = () => [...document.querySelectorAll('.upt-grid [data-monitor-open]')].map((b) => b.textContent)
const formDialog = () => screen.getAllByRole('dialog').at(-1)
const fieldOf = (key) => document.querySelector(`[data-field="${key}"]`)

beforeEach(() => {
  vi.clearAllMocks()
  for (const k of Object.keys(blocks.props)) delete blocks.props[k]
  window.history.replaceState({}, '', '/')
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
  api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [row()] })
  api.monitoring.dbConnections.mockResolvedValue({ success: true, data: [{ id: 3, health: { state: 'ok' } }] })
  api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { dbquery: { max_timeout_seconds: 60, max_rows_cap: 5000 } } })
  api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: { items: [], counts: { total: 0, fail: 0 }, total: 0, page: 0, size: 50 } })
})
afterEach(() => window.history.replaceState({}, '', '/'))

async function openNewAs(over = {}) {
  render(<DbQueryMonitorPage {...props} systemRole="USER" {...over} />)
  await waitFor(() => expect(titles().length).toBeGreaterThan(0))
  fireEvent.click(screen.getByRole('button', { name: /^New Monitor$/ }))
  return formDialog()
}
async function fillValid(sql = 'SELECT count(*) AS bekleyen FROM orders') {
  const dlg = formDialog()
  fireEvent.change(within(dlg).getByLabelText(/^Name/), { target: { value: 'Yeni sorgu' } })
  await fillGroupAndTags({ group: 'Kurumsal', tag: 'prod', root: dlg })
  fireEvent.change(within(dlg).getByLabelText(/^What is this alert for\?/), { target: { value: 'Bekleyen sipariş sayısı' } })
  fireEvent.click(within(dlg).getByRole('button', { name: 'pick-connection' }))
  fireEvent.change(within(dlg).getByLabelText('sql'), { target: { value: sql } })
}
async function openEdit(over = {}) {
  api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [row(over)] })
  render(<DbQueryMonitorPage {...props} />)
  fireEvent.click(await screen.findByRole('button', { name: /^Sipariş kuyruğu — edit$/i }))
  return formDialog()
}
async function runTest(data) {
  api.monitoring.testDbQuery.mockResolvedValue({ success: true, data })
  fireEvent.click(within(formDialog()).getByRole('button', { name: /^Try the query$/ }))
  await waitFor(() => expect(document.querySelector('[data-slot="dbq-test"]')).not.toBeNull())
}

describe('DbQueryMonitorPage — değerlendirilemedi (2.A)', () => {
  it('UNKNOWN: kart nötr "Not evaluated" + ilk notun metni (yeşil değil); ayrıntıda açıklama bandı', async () => {
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [row({ status: 'UNKNOWN', unknown_runs: 4,
      rule_results: [{ id: 'r1', label: 'Bekleyen', kind: 'COLUMN_VALUE', evaluated: false, note: 'column_missing', level: 'NONE' }] })] })
    render(<DbQueryMonitorPage {...props} />)
    await waitFor(() => expect(titles()).toHaveLength(1))
    const card = document.querySelector('.upt-grid [data-slot="card"]')
    expect(card.getAttribute('data-status')).toBe('unknown')
    expect(card.textContent).toContain('Not evaluated')
    const note = card.querySelector('[data-slot="dbq-unevaluated"]')
    expect(note.textContent).toContain('At least one rule could not be evaluated')
    expect(note.textContent).toContain('(4 runs in a row)')
    fireEvent.click(card.querySelector('[data-monitor-open]'))
    const banner = await waitFor(() => { const b = document.querySelector('[data-slot="dbq-detail-unevaluated"]'); if (!b) throw new Error('yok'); return b })
    expect(banner.textContent).toContain('Bekleyen')
    expect(banner.querySelector('[data-slot="dbq-detail-unknown-runs"]').textContent).toContain('4')
  })

  it('maliyet denetimi kapalıyken (OFF) "Maliyeti denetle" çizilmez, nedeni yazılır', async () => {
    api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { dbquery: { plan_check_mode: 'OFF' } } })
    await openNewAs()
    await fillValid()
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-plan-off"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="dbq-plan-run"]')).toBeNull()
  })
})

describe('DbQueryMonitorPage — mail kolon kipi (2.B)', () => {
  it('yeni izleme: mailde YALNIZ seçilen kolonlar (SELECTED) gövdede', async () => {
    api.monitoring.createDbQueryMonitor.mockResolvedValue({ success: true, data: row() })
    await openNewAs()
    await fillValid()
    expect(fieldOf('mail_columns_mode').textContent).toContain('No column chosen yet')
    fireEvent.click(within(formDialog()).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(api.monitoring.createDbQueryMonitor).toHaveBeenCalled())
    expect(api.monitoring.createDbQueryMonitor.mock.calls[0][0].mail_columns_mode).toBe('SELECTED')
  })

  it('kayıtlı izleme (alan yok = eski kayıt): ilk N olarak açılır, değişmezse gönderilmez', async () => {
    api.monitoring.updateDbQueryMonitor.mockResolvedValue({ success: true, data: row() })
    const dlg = await openEdit()
    expect(within(fieldOf('mail_columns_mode')).getByRole('button', { name: /^The first \d+ columns$/ })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.change(within(dlg).getByLabelText(/^What is this alert for\?/), { target: { value: 'Yeni amaç metni' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(api.monitoring.updateDbQueryMonitor).toHaveBeenCalledTimes(1))
    expect(api.monitoring.updateDbQueryMonitor.mock.calls[0][1]).toEqual({ purpose: 'Yeni amaç metni' })
  })

  it('kip değişimi mail kümesini birlikte gönderir', async () => {
    api.monitoring.updateDbQueryMonitor.mockResolvedValue({ success: true, data: row() })
    const dlg = await openEdit({ mail_columns_mode: 'FIRST_N', mail_columns: ['adet'] })
    fireEvent.click(within(fieldOf('mail_columns_mode')).getByRole('button', { name: 'Only the columns I choose' }))
    fireEvent.click(within(dlg).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(api.monitoring.updateDbQueryMonitor).toHaveBeenCalledTimes(1))
    expect(api.monitoring.updateDbQueryMonitor.mock.calls[0][1]).toEqual({
      mail_include_rows: true, mail_columns_mode: 'SELECTED', mail_columns: ['adet'], masked_columns: [],
    })
  })
})

describe('DbQueryMonitorPage — kurulum kolaylığı (2.C)', () => {
  it('bölüm çubuğu hata sayısını gösterir (boş kayıtta Temel / Bağlantı / Sorgu)', async () => {
    await openNewAs()
    fireEvent.click(within(formDialog()).getByRole('button', { name: /^Save$/ }))
    const nav = await waitFor(() => {
      const n = document.querySelector('[data-slot="dbq-form-nav"]')
      expect(n.querySelector('[data-section="basic"]').getAttribute('data-errors')).toBe('4')
      return n
    })
    expect(nav.querySelector('[data-section="connection"]').getAttribute('data-errors')).toBe('1')
    expect(nav.querySelector('[data-section="query"]').getAttribute('data-errors')).toBe('1')
    expect(nav.querySelector('[data-section="schedule"]').getAttribute('data-errors')).toBeNull()
    expect(within(nav).getByRole('button', { name: 'Basics — 4 invalid fields' })).toBeInTheDocument()
  })

  it('COUNT tuzağı: tek satır özet + satır sayısı kuralı → tuzak + kolon; yeni kural varsayılanı kolon değeri', async () => {
    await openEdit({ sql_text: 'SELECT count(*) AS bekleyen FROM orders' })
    await runTest({ status: 'OK', row_count: 1, row_count_capped: false, columns: [{ name: 'bekleyen', type: 'int8' }], rule_results: [] })
    await waitFor(() => expect([...blocks.props.RuleBuilder.trapIndexes]).toEqual([0]))
    expect(blocks.props.RuleBuilder.trapColumn).toBe('bekleyen')
    expect(blocks.props.RuleBuilder.newRuleFactory()).toMatchObject({ kind: 'COLUMN_VALUE', column: 'bekleyen' })
  })

  it('sunucu uyarısı ROW_COUNT_ON_AGGREGATE tuzağı işaretler (istemci kanıtı olmasa da); panelde uyarı bandı', async () => {
    await openEdit({ sql_text: 'WITH x AS (SELECT 1) SELECT count(*) FROM x' })
    await runTest({ status: 'OK', row_count: 1, columns: [{ name: 'count', type: 'int8' }], rule_results: [],
      warnings: [{ code: 'ROW_COUNT_ON_AGGREGATE', rule_id: 'r1' }] })
    await waitFor(() => expect([...blocks.props.RuleBuilder.trapIndexes]).toEqual([0]))
    expect(document.querySelector('[data-slot="dbq-test-warning"][data-code="ROW_COUNT_ON_AGGREGATE"]')).not.toBeNull()
  })

  it('deneme kolonundan tek tıkla kural; hassas kolon önerisi tek tıkla maskeye', async () => {
    await openNewAs()
    await fillValid('SELECT musteri_tckn, son_islem FROM orders')
    await runTest({ status: 'OK', row_count: 3, columns: [{ name: 'musteri_tckn', type: 'varchar' }, { name: 'son_islem', type: 'timestamp' }],
      rule_results: [], suggested_masks: ['musteri_tckn'] })
    pressMenuTrigger(screen.getByRole('button', { name: 'Actions for the son_islem column' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /freshness rule/ }))
    await waitFor(() => expect(blocks.props.RuleBuilder.rules).toHaveLength(1))
    expect(blocks.props.RuleBuilder.rules[0]).toMatchObject({ kind: 'FRESHNESS', column: 'son_islem', aggregate: 'MAX' })
    expect(toastMock.success).toHaveBeenCalledWith(expect.stringContaining('son_islem'))
    const sugg = document.querySelector('[data-slot="dbq-suggested-masks"]')
    expect(sugg).not.toBeNull()
    fireEvent.click(within(sugg).getByRole('button', { name: 'Mask the musteri_tckn column' }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-suggested-masks"]')).toBeNull())
    expect(document.querySelector('[data-slot="dbq-col"] [data-slot="dbq-col-masked"]')).not.toBeNull()
  })

  it('şablon yöneticisinden "Bu şablonla izleme oluştur": yeni form sorgu / amaç / kurallar ile açılır, yönetici kapanır', async () => {
    render(<DbQueryMonitorPage {...props} systemRole="USER" />)
    await waitFor(() => expect(titles()).toHaveLength(1))
    fireEvent.click(screen.getByRole('button', { name: /^Templates$/ }))
    await waitFor(() => expect(blocks.props.TemplatesManager?.open).toBe(true))
    act(() => blocks.props.TemplatesManager.onCreateMonitor({ team_id: 5, sql_text: 'SELECT 9', purpose: 'Şablon amacı',
      rules: [{ id: 'rx', kind: 'ROW_COUNT' }], rule_logic: 'ALL' }))
    await waitFor(() => expect(blocks.props.SqlEditor?.value).toBe('SELECT 9'))
    expect(blocks.props.RuleBuilder.rules).toEqual([{ id: 'rx', kind: 'ROW_COUNT' }])
    expect(blocks.props.RuleBuilder.logic).toBe('ALL')
    expect(within(formDialog()).getByLabelText(/^What is this alert for\?/)).toHaveValue('Şablon amacı')
  })
})

describe('DbQueryMonitorPage — uyarılar, hesap yetkisi, geriye dönük deneme', () => {
  it('kayıt uyarıları (engellemez) bilgi tostunda', async () => {
    api.monitoring.createDbQueryMonitor.mockResolvedValueOnce({ success: true, data: row(), warnings: [{ code: 'SELECT_STAR' }] })
    await openNewAs()
    await fillValid('SELECT * FROM orders')
    fireEvent.click(within(formDialog()).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(toastMock.info).toHaveBeenCalledWith(expect.stringContaining('The query uses SELECT *')))
  })

  it('DB_ACCOUNT_WRITABLE → metin bağlantı seçicinin altında', async () => {
    api.monitoring.createDbQueryMonitor.mockResolvedValueOnce({ success: false, code: 'DB_ACCOUNT_WRITABLE',
      error: 'Bağlantı hesabı yazabiliyor (yönetici ayarı engelliyor).' })
    await openNewAs()
    await fillValid()
    fireEvent.click(within(formDialog()).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="t-conn-error"]')?.textContent).toContain('yazabiliyor'))
  })

  it('geriye dönük deneme paneli yalnız kayıtlı izlemede; paylaşımla düzenlenen kayıtta yok', async () => {
    const { unmount } = render(<DbQueryMonitorPage {...props} systemRole="USER" />)
    await waitFor(() => expect(titles()).toHaveLength(1))
    fireEvent.click(screen.getByRole('button', { name: /^New Monitor$/ }))
    expect(document.querySelector('[data-slot="dbq-backtest"]')).toBeNull()
    unmount()
    const first = await openEdit()
    expect(within(first).getByRole('button', { name: 'Run a backtest' })).toBeInTheDocument()
  })

  it('paylaşımla düzenlenen kayıtta geriye dönük deneme çizilmez', async () => {
    await openEdit({ routing_locked: true, shared_access: true })
    expect(document.querySelector('[data-slot="dbq-backtest"]')).toBeNull()
  })
})
