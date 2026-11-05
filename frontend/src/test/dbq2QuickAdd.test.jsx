import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act, fillGroupAndTags } from './test-utils.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

/**
 * dbq2 B1 + B4 (2026-11-05): "Hızlı ekle" sihirbazı — sayfa başlığından ve boş durumdan açılır; adımlar Bağlantı → Şablon
 * ya da sorgu → Dene → Kural → Zamanlama ve bildirim → Özet; her adım kendi doğrulamasıyla ilerler, geri gidilir, değerler
 * kaybolmaz; Kaydet mevcut create ucu; sunucu alan hatası ilgili adıma döner; sihirbazda alanı olmayan hata → bant +
 * "Gelişmiş görünümde düzelt"; "Gelişmiş görünüm" değerleri uzun forma taşır; "Birden çok bağlantı için oluştur" sıralı,
 * ilk hatada durmaz. Yapı taşları (kural / zamanlama / düzenleyici / bağlantı seçici) burada sahte — kendi testlerinde.
 */
const toastMock = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), dismiss: vi.fn() }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))

const blocks = vi.hoisted(() => ({ props: {} }))
vi.mock('../components/dbquery/buildingBlocks.jsx', () => {
  const remember = (name) => (props) => { blocks.props[name] = props; return null }
  return {
    RuleBuilder: (p) => {
      blocks.props.RuleBuilder = p
      return (
        <div data-slot="t-rule-builder" data-count={(p.rules || []).length}>
          {Object.entries(p.fieldErrors || {}).map(([k, v]) => <p key={k} data-err={k}>{v}</p>)}
          <button type="button" onClick={() => p.onChange([...(p.rules || []), { id: 'rX', kind: 'BROKEN' }])}>add-broken-rule</button>
        </div>
      )
    },
    ScheduleEditor: (p) => { blocks.props.ScheduleEditor = p; return <div data-slot="t-schedule" data-interval={p.value?.interval_seconds} /> },
    SqlEditor: (p) => {
      blocks.props.SqlEditor = p
      return (
        <div data-field="sql_text" data-slot="t-sql">
          <textarea aria-label="sql" value={p.value} onChange={(e) => p.onChange(e.target.value)} />
          <button type="button" onClick={() => p.onGuardChange?.({ status: 'rejected', ok: false, violations: [] })}>guard-reject</button>
          <button type="button" onClick={() => p.onGuardChange?.({ status: 'ok', ok: true, violations: [] })}>guard-ok</button>
          {p.fieldError && <p data-slot="t-sql-error">{p.fieldError}</p>}
        </div>
      )
    },
    ConnectionPicker: (p) => {
      blocks.props.ConnectionPicker = p
      return (
        <div data-field="profile_id" data-slot="t-conn-picker" data-team={String(p.teamId)}>
          <button type="button" onClick={() => p.onChange(3, { id: 3, name: 'Sipariş DB', db_type: 'MSSQL' })}>pick-connection</button>
          <button type="button" onClick={() => p.onChange(4, { id: 4, name: 'Sipariş DB Yedek', db_type: 'MSSQL' })}>pick-connection-4</button>
          {p.error && <p data-slot="t-conn-error">{p.error}</p>}
        </div>
      )
    },
    ConnectionsManager: remember('ConnectionsManager'),
    TemplatePicker: (p) => { blocks.props.TemplatePicker = p; return <button type="button" onClick={() => p.onPick({ name: 'Takım şablonu', sql_text: 'SELECT 7', purpose: 'Takım amacı', rules: [], rule_logic: 'ANY' })}>pick-team-template</button> },
    SaveTemplateDialog: remember('SaveTemplateDialog'),
    TemplatesManager: remember('TemplatesManager'),
    RuleResultsList: () => null,
    DbQueryAlertRows: () => null,
  }
})

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      getDbQueryMonitors: vi.fn(), createDbQueryMonitor: vi.fn(), updateDbQueryMonitor: vi.fn(), testDbQuery: vi.fn(), dbConnections: vi.fn(),
      triggerDbQueryCheck: vi.fn(() => Promise.resolve({ success: true, data: {} })),
      listGroups: vi.fn(() => Promise.resolve({ success: true, data: [{ name: 'Kurumsal' }] })),
      listTags: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults: vi.fn(() => Promise.resolve({ success: true, data: { dbquery: { interval_seconds: 600, max_rows: 1000 } } })),
    },
    admin: { getTeams: vi.fn() },
  }),
}))
import { api } from '../api/client'
import DbQueryMonitorPage from '../components/DbQueryMonitorPage.jsx'

const userProps = { systemRole: 'USER', teamId: 5, teamName: 'SY-A', myTeams: [{ id: 5, name: 'SY-A' }], globalAdmin: false }
const CONNS = [
  { id: 3, team_id: 5, name: 'Sipariş DB', db_type: 'MSSQL', host: 'sql01', port: 1433, database_name: 'Sales', health: { state: 'ok' } },
  { id: 4, team_id: 5, name: 'Sipariş DB Yedek', db_type: 'MSSQL', host: 'sql02', port: 1433, database_name: 'Sales', health: { state: 'ok' } },
  { id: 5, team_id: 5, name: 'Rapor PG', db_type: 'POSTGRESQL', host: 'pg01', port: 5432, database_name: 'rpt', health: { state: 'untested' } },
]
const wiz = () => document.querySelector('[data-slot="dbq-wiz"]')
const step = () => wiz()?.getAttribute('data-step')
const next = () => fireEvent.click(document.querySelector('[data-slot="dbq-wiz-next"]'))

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  for (const k of Object.keys(blocks.props)) delete blocks.props[k]
  window.history.replaceState({}, '', '/')
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
  api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [] })
  api.monitoring.dbConnections.mockResolvedValue({ success: true, data: CONNS })
  api.monitoring.testDbQuery.mockResolvedValue({ success: true, data: {
    status: 'OK', duration_ms: 120, row_count: 1, columns: [{ name: 'blocked_sessions', type: 'int' }], rule_results: [],
  } })
})
afterEach(() => window.history.replaceState({}, '', '/'))

async function openWizard(props = userProps) {
  render(<DbQueryMonitorPage {...props} />)
  // Boş liste: boş durumda da "Hızlı ekle" var
  const emptyBtn = await screen.findByRole('button', { name: 'Hızlı ekle' }, { timeout: 3000 })
  expect(document.querySelector('[data-slot="dbq-quick-add-empty"]')).not.toBeNull()
  fireEvent.click(emptyBtn.closest('[data-slot="dbq-quick-add-empty"]') ? emptyBtn : document.querySelector('[data-slot="dbq-quick-add"]'))
  await waitFor(() => expect(wiz()).not.toBeNull(), { timeout: 3000 })
}

/** Bağlantı → sorgu (DBA şablonu) → dene → kural → zamanlama → özet. */
async function walkToSummary() {
  next()
  expect(step()).toBe('connection')
  expect(document.querySelector('[data-slot="t-conn-error"]').textContent).toBe('Bir bağlantı seçin.')
  fireEvent.click(screen.getByRole('button', { name: 'pick-connection' }))
  expect(document.querySelector('[data-slot="t-conn-error"]')).toBeNull()
  next()
  expect(step()).toBe('query')
  // sorgu boşken hızlı başlangıç; DBA şablonu bağlantının türünde (SQL Server) SQL ile gelir
  const qs = wiz().querySelector('[data-slot="dbq-wiz-quickstart"]')
  fireEvent.click(within(qs).getByRole('button', { name: 'Bloklanan / kilit bekleyen oturumlar' }))
  await waitFor(() => expect(screen.getByLabelText('sql').value).toContain('sys.dm_exec_requests WITH (NOLOCK)'))
  expect(blocks.props.SqlEditor.fix).toMatchObject({ rowLimit: 1000 })
  expect(blocks.props.SqlEditor.dbType).toBe('MSSQL')
  next()
  expect(step()).toBe('test')
  fireEvent.click(document.querySelector('[data-slot="dbq-wiz-test"]'))
  await waitFor(() => expect(api.monitoring.testDbQuery).toHaveBeenCalledTimes(1))
  expect(api.monitoring.testDbQuery.mock.calls[0][0]).toMatchObject({ profile_id: 3, sql_text: expect.stringContaining('blocked_sessions') })
  await waitFor(() => expect(document.querySelector('[data-slot="dbq-test"]')).not.toBeNull())
  next()
  expect(step()).toBe('rules')
  expect(blocks.props.RuleBuilder.rules).toHaveLength(1)
  expect(blocks.props.RuleBuilder.columns.map((c) => c.name)).toEqual(['blocked_sessions'])
  next()
  expect(step()).toBe('schedule')
  // DBA şablonunun önerilen aralığı (5 dk) zamanlamaya uygulandı
  expect(blocks.props.ScheduleEditor.value.interval_seconds).toBe(300)
  next()
  expect(step()).toBe('summary')
}

describe('Hızlı ekle sihirbazı (B1)', () => {
  it('baştan sona: adım doğrulaması, şablon, deneme, önerilen ad; Kaydet create ucuna aynı gövdeyi gönderir', async () => {
    api.monitoring.createDbQueryMonitor.mockResolvedValue({ success: true, data: { id: 77, name: 'Bloklanan / kilit bekleyen oturumlar', active: true } })
    await openWizard()
    expect(document.querySelector('[data-slot="dbq-wiz-steps"] [aria-current="step"]').getAttribute('data-step')).toBe('connection')
    await walkToSummary()
    const dlg = wiz().closest('[role="dialog"]')
    expect(within(dlg).getByLabelText(/^Ad(\s*\*)?$/)).toHaveValue('Bloklanan / kilit bekleyen oturumlar')
    expect(within(dlg).getByLabelText(/^Bu alarm ne için\?/).value).toContain('Kilit bekleyen')
    // grup + etiket zorunlu: boş kayıtta Özet alanlarının altında hata, istek gitmez
    fireEvent.click(document.querySelector('[data-slot="dbq-wiz-save"]'))
    await waitFor(() => expect(wiz().querySelector('[data-field="group_name"]').textContent).toContain('Grup'))
    expect(api.monitoring.createDbQueryMonitor).not.toHaveBeenCalled()
    await fillGroupAndTags({ group: 'Kurumsal', tag: 'dba', root: dlg })
    fireEvent.click(document.querySelector('[data-slot="dbq-wiz-save"]'))
    await waitFor(() => expect(api.monitoring.createDbQueryMonitor).toHaveBeenCalledTimes(1))
    const body = api.monitoring.createDbQueryMonitor.mock.calls[0][0]
    expect(body).toMatchObject({
      name: 'Bloklanan / kilit bekleyen oturumlar', teamId: 5, profile_id: 3, groupName: 'Kurumsal', tags: 'dba',
      schedule_mode: 'INTERVAL', interval_seconds: 300, rule_logic: 'ANY', mail_columns_mode: 'SELECTED',
    })
    expect(body.sql_text).toContain('WITH (NOLOCK)')
    expect(body.rules[0]).toMatchObject({ kind: 'COLUMN_VALUE', column: 'blocked_sessions' })
    await waitFor(() => expect(wiz()).toBeNull())
    expect(toastMock.success).toHaveBeenCalledWith('Veritabanı sorgu izlemesi kaydedildi')
  })

  it('geri gidilir, değerler kaybolmaz; reddedilen sorgu ilerletmez; geçersiz kural ilerletmez', async () => {
    await openWizard()
    fireEvent.click(screen.getByRole('button', { name: 'pick-connection' }))
    next()
    fireEvent.change(screen.getByLabelText('sql'), { target: { value: 'SELECT 1 FROM t' } })
    fireEvent.click(screen.getByRole('button', { name: 'guard-reject' }))
    next()
    expect(step()).toBe('query')
    expect(document.querySelector('[data-slot="t-sql-error"]').textContent).toContain('güvenlik denetiminden geçmedi')
    fireEvent.click(screen.getByRole('button', { name: 'guard-ok' }))
    next()
    expect(step()).toBe('test')
    fireEvent.click(document.querySelector('[data-slot="dbq-wiz-back"]'))
    expect(step()).toBe('query')
    expect(screen.getByLabelText('sql')).toHaveValue('SELECT 1 FROM t')
    // göstergeden ileri atlama (ziyaret edilmiş adıma)
    fireEvent.click(within(document.querySelector('[data-slot="dbq-wiz-steps"]')).getByRole('button', { name: 'Adım 3: Dene' }))
    expect(step()).toBe('test')
    next()
    fireEvent.click(screen.getByRole('button', { name: 'add-broken-rule' }))
    next()
    expect(step()).toBe('rules')
    expect(document.querySelectorAll('[data-slot="t-rule-builder"] [data-err]').length).toBeGreaterThan(0)
  })

  it('sunucu alan hatası ilgili adıma döner; gösterge hatalı adımları sayar', async () => {
    api.monitoring.createDbQueryMonitor.mockResolvedValue({ success: false, code: 'VALIDATION_FAILED', error: 'Doğrulama',
      fields: { sql_text: 'Sorgu bu türde reddedildi', name: 'Bu ad başka bir izlemede kullanılıyor' } })
    await openWizard()
    await walkToSummary()
    const dlg = wiz().closest('[role="dialog"]')
    await fillGroupAndTags({ group: 'Kurumsal', tag: 'dba', root: dlg })
    fireEvent.click(document.querySelector('[data-slot="dbq-wiz-save"]'))
    await waitFor(() => expect(step()).toBe('query'))
    expect(document.querySelector('[data-slot="t-sql-error"]').textContent).toBe('Sorgu bu türde reddedildi')
    const steps = document.querySelector('[data-slot="dbq-wiz-steps"]')
    expect(steps.querySelector('li[data-step="query"] > button').getAttribute('data-errors')).toBe('1')
    expect(steps.querySelector('li[data-step="summary"] > button').getAttribute('data-errors')).toBe('1')
  })

  it('sihirbazda alanı olmayan hata → bant + "Gelişmiş görünümde düzelt" → değerler uzun forma taşınır', async () => {
    api.monitoring.createDbQueryMonitor.mockResolvedValue({ success: false, code: 'VALIDATION_FAILED', error: 'Doğrulama',
      fields: { max_rows: 'En çok 5000 satır olabilir' } })
    await openWizard()
    await walkToSummary()
    const dlg = wiz().closest('[role="dialog"]')
    await fillGroupAndTags({ group: 'Kurumsal', tag: 'dba', root: dlg })
    fireEvent.click(document.querySelector('[data-slot="dbq-wiz-save"]'))
    const banner = await waitFor(() => { const b = document.querySelector('[data-slot="dbq-wiz-banner"]'); if (!b) throw new Error('yok'); return b })
    expect(banner.textContent).toContain('En çok 5000 satır')
    fireEvent.click(screen.getByRole('button', { name: 'Gelişmiş görünümde düzelt' }))
    await waitFor(() => expect(wiz()).toBeNull())
    const form = await screen.findByRole('dialog', { name: /Yeni veritabanı sorgu izlemesi/ })
    expect(within(form).getByLabelText('sql').value).toContain('blocked_sessions')
    expect(within(form).getByLabelText(/^Ad/)).toHaveValue('Bloklanan / kilit bekleyen oturumlar')
    expect(blocks.props.ConnectionPicker.value).toBe(3)
  })

  it('"Gelişmiş görünüm" yarı yolda: değerler taşınır ve uzun form kapatılırken onay ister (taslak kaybolmaz)', async () => {
    await openWizard()
    fireEvent.click(screen.getByRole('button', { name: 'pick-connection' }))
    next()
    fireEvent.change(screen.getByLabelText('sql'), { target: { value: 'SELECT 42 AS x' } })
    fireEvent.click(document.querySelector('[data-slot="dbq-wiz-advanced"]'))
    await waitFor(() => expect(wiz()).toBeNull())
    const form = await screen.findByRole('dialog', { name: /Yeni veritabanı sorgu izlemesi/ })
    expect(within(form).getByLabelText('sql')).toHaveValue('SELECT 42 AS x')
    fireEvent.click(within(form).getByRole('button', { name: 'İptal' }))
    expect(await screen.findByText('Değişiklikleri at')).toBeInTheDocument()
  })

  it('değişiklikle kapatma onay ister; "Düzenlemeye devam et" sihirbazı açık tutar', async () => {
    await openWizard()
    fireEvent.click(screen.getByRole('button', { name: 'pick-connection' }))
    const dlg = wiz().closest('[role="dialog"]')
    fireEvent.click(within(dlg).getByRole('button', { name: /Kapat/ }))
    fireEvent.click(await screen.findByRole('button', { name: 'Düzenlemeye devam et' }))
    await act(async () => {})
    expect(wiz()).not.toBeNull()
  })

  it('başlıktan da açılır (izleme varken); salt okunur kullanıcıda giriş noktası yok', async () => {
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [{ id: 1, name: 'A', team_id: 5, profile: { db_type: 'MSSQL' }, rules: [], can_edit: true }] })
    const { unmount } = render(<DbQueryMonitorPage {...userProps} />)
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-quick-add"]')).not.toBeNull())
    fireEvent.click(document.querySelector('[data-slot="dbq-quick-add"]'))
    await waitFor(() => expect(wiz()).not.toBeNull())
    unmount()
    render(<DbQueryMonitorPage {...userProps} systemRole="AUDIT" />)
    await waitFor(() => expect(document.querySelector('.upt-page')).not.toBeNull())
    await act(async () => {})
    expect(document.querySelector('[data-slot="dbq-quick-add"]')).toBeNull()
  })
})

describe('Sunucu alan hataları ekranda görünür (O4 / D8 / D12)', () => {
  it('bildirim seviyesi hatası zamanlama adımının BANDINDA metin olarak (adım göstergesi sayar)', async () => {
    api.monitoring.createDbQueryMonitor.mockResolvedValue({ success: false, status: 400, code: 'VALIDATION_FAILED', error: 'Doğrulama',
      fields: { alertLevel: 'Bu alarm seviyesi bu izleme türünde kullanılamaz.' } })
    await openWizard()
    await walkToSummary()
    const dlg = wiz().closest('[role="dialog"]')
    await fillGroupAndTags({ group: 'Kurumsal', tag: 'dba', root: dlg })
    fireEvent.click(document.querySelector('[data-slot="dbq-wiz-save"]'))
    await waitFor(() => expect(step()).toBe('schedule'))
    const band = document.querySelector('[data-slot="dbq-wiz-step-errors"]')
    expect(band.textContent).toContain('Bu alarm seviyesi bu izleme türünde kullanılamaz.')
    expect(document.querySelector('[data-slot="dbq-wiz-steps"] li[data-step="schedule"] > button').getAttribute('data-errors')).toBe('1')
  })

  it('deneme ucunun alan hatası ilgili adıma döner (D12)', async () => {
    api.monitoring.testDbQuery.mockResolvedValue({ success: false, status: 400, code: 'VALIDATION_FAILED', error: 'Doğrulama',
      fields: { sql_text: 'Sorgu en çok 20000 karakter olabilir.' } })
    await openWizard()
    fireEvent.click(screen.getByRole('button', { name: 'pick-connection' }))
    next()
    fireEvent.change(screen.getByLabelText('sql'), { target: { value: 'SELECT 1 AS x' } })
    next()
    expect(step()).toBe('test')
    fireEvent.click(document.querySelector('[data-slot="dbq-wiz-test"]'))
    await waitFor(() => expect(step()).toBe('query'))
    expect(document.querySelector('[data-slot="t-sql-error"]').textContent).toBe('Sorgu en çok 20000 karakter olabilir.')
  })

  it('"Gelişmiş görünümde düzelt": sihirbazdaki alan hatası uzun formda da alanın altında (D8)', async () => {
    api.monitoring.createDbQueryMonitor.mockResolvedValue({ success: false, status: 400, code: 'VALIDATION_FAILED', error: 'Doğrulama',
      fields: { max_rows: 'En çok 5000 satır olabilir' } })
    await openWizard()
    await walkToSummary()
    const dlg = wiz().closest('[role="dialog"]')
    await fillGroupAndTags({ group: 'Kurumsal', tag: 'dba', root: dlg })
    fireEvent.click(document.querySelector('[data-slot="dbq-wiz-save"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-wiz-banner"]')).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: 'Gelişmiş görünümde düzelt' }))
    const form = await screen.findByRole('dialog', { name: /Yeni veritabanı sorgu izlemesi/ })
    await waitFor(() => expect(form.querySelector('[data-field="max_rows"]').textContent).toContain('En çok 5000 satır olabilir'))
  })
})

describe('Birden çok bağlantı için oluştur (B4)', () => {
  it('Özet adımında: seçili bağlantı hazır gelir; türü farklı bağlantı uyarısı; sıralı oluşturma, ilk hatada durmaz', async () => {
    api.monitoring.createDbQueryMonitor
      .mockResolvedValueOnce({ success: true, data: { id: 101 } })
      .mockResolvedValueOnce({ success: false, code: 'DUPLICATE_RECORD', error: 'Bu ad başka bir izlemede kullanılıyor.' })
      .mockResolvedValueOnce({ success: false, code: 'DBQUERY_SQL_REJECTED', error: 'Sorgu PostgreSQL için güvenlik denetiminden geçmedi.' })
    await openWizard()
    await walkToSummary()
    const dlg = wiz().closest('[role="dialog"]')
    await fillGroupAndTags({ group: 'Kurumsal', tag: 'dba', root: dlg })
    fireEvent.click(within(dlg).getByRole('switch', { name: /Birden çok bağlantı için oluştur/ }))
    const list = await waitFor(() => { const l = document.querySelector('[data-slot="dbq-bulk-connections"]'); if (!l) throw new Error('yok'); return l })
    const box = (id) => list.querySelector(`[data-slot="dbq-bulk-connection"][data-id="${id}"] [role="checkbox"]`)
    expect(box(3)).toHaveAttribute('data-state', 'checked')
    fireEvent.click(box(4)); fireEvent.click(box(5))
    expect(list.querySelector('[data-id="5"] [data-slot="dbq-bulk-other-type"]')).not.toBeNull()
    const plan = [...document.querySelectorAll('[data-slot="dbq-bulk-plan-row"]')].map((r) => r.textContent)
    expect(plan).toEqual(['Bloklanan / kilit bekleyen oturumlar · Sipariş DB', 'Bloklanan / kilit bekleyen oturumlar · Sipariş DB Yedek',
      'Bloklanan / kilit bekleyen oturumlar · Rapor PG'])
    fireEvent.click(document.querySelector('[data-slot="dbq-wiz-save"]'))
    await waitFor(() => expect(api.monitoring.createDbQueryMonitor).toHaveBeenCalledTimes(3))
    expect(api.monitoring.createDbQueryMonitor.mock.calls.map((c) => [c[0].profile_id, c[0].name])).toEqual([
      [3, 'Bloklanan / kilit bekleyen oturumlar · Sipariş DB'], [4, 'Bloklanan / kilit bekleyen oturumlar · Sipariş DB Yedek'],
      [5, 'Bloklanan / kilit bekleyen oturumlar · Rapor PG'],
    ])
    const results = await waitFor(() => { const r = document.querySelector('[data-slot="dbq-bulk"][data-state="done"]'); if (!r) throw new Error('yok'); return r })
    expect([...results.querySelectorAll('[data-slot="dbq-bulk-result"]')].map((r) => r.getAttribute('data-status'))).toEqual(['ok', 'error', 'error'])
    expect(results.textContent).toContain('Bu ad başka bir izlemede kullanılıyor.')
    expect(results.querySelector('[data-slot="dbq-bulk-summary"]').textContent).toBe('1 izleme oluşturuldu · 2 reddedildi')
    expect(toastMock.info).toHaveBeenCalledWith('1 izleme oluşturuldu · 2 reddedildi')
    expect(document.querySelector('[data-slot="dbq-bulk-retry"]').textContent).toBe('2 bağlantı için yeniden dene')
    // başarısız satır varken kapatma ONAY ister (O1) — "Düzenlemeye devam et" açık tutar
    fireEvent.click(document.querySelector('[data-slot="dbq-wiz-done"]'))
    expect(await screen.findByText(/Kapatırsanız bu bağlantılar için girdiğiniz sorgu/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Düzenlemeye devam et' }))
    await act(async () => {})
    expect(wiz()).not.toBeNull()
    fireEvent.click(document.querySelector('[data-slot="dbq-wiz-done"]'))
    fireEvent.click(await screen.findByRole('button', { name: 'Değişiklikleri at' }))
    await waitFor(() => expect(wiz()).toBeNull())
  })

  it('"Başarısızları yeniden dene": yalnız reddedilenler seçili plana döner, alanlar açılır; hepsi başarılıysa onaysız kapanır (O1)', async () => {
    api.monitoring.createDbQueryMonitor
      .mockResolvedValueOnce({ success: true, data: { id: 101 } })
      .mockResolvedValueOnce({ success: false, status: 409, code: 'DUPLICATE_RECORD', error: 'Bu ad başka bir izlemede kullanılıyor.' })
      .mockResolvedValue({ success: true, data: { id: 102 } })
    await openWizard()
    await walkToSummary()
    const dlg = wiz().closest('[role="dialog"]')
    await fillGroupAndTags({ group: 'Kurumsal', tag: 'dba', root: dlg })
    fireEvent.click(within(dlg).getByRole('switch', { name: /Birden çok bağlantı için oluştur/ }))
    const list = await waitFor(() => { const l = document.querySelector('[data-slot="dbq-bulk-connections"]'); if (!l) throw new Error('yok'); return l })
    fireEvent.click(list.querySelector('[data-id="4"] [role="checkbox"]'))
    fireEvent.click(document.querySelector('[data-slot="dbq-wiz-save"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-bulk"][data-state="done"]')).not.toBeNull())
    expect(within(dlg).getByLabelText(/^Ad(\s*\*)?$/)).toBeDisabled()
    fireEvent.click(document.querySelector('[data-slot="dbq-bulk-retry"]'))
    // plan geri geldi: yalnız reddedilen bağlantı seçili; ad düzenlenebilir
    const list2 = await waitFor(() => { const l = document.querySelector('[data-slot="dbq-bulk-connections"]'); if (!l) throw new Error('yok'); return l })
    expect(list2.querySelector('[data-id="3"] [role="checkbox"]')).toHaveAttribute('data-state', 'unchecked')
    expect(list2.querySelector('[data-id="4"] [role="checkbox"]')).toHaveAttribute('data-state', 'checked')
    expect(within(dlg).getByLabelText(/^Ad(\s*\*)?$/)).not.toBeDisabled()
    fireEvent.change(within(dlg).getByLabelText(/^Ad(\s*\*)?$/), { target: { value: 'Kilit izleme' } })
    fireEvent.click(document.querySelector('[data-slot="dbq-wiz-save"]'))
    await waitFor(() => expect(api.monitoring.createDbQueryMonitor).toHaveBeenCalledTimes(3))
    expect(api.monitoring.createDbQueryMonitor.mock.calls[2][0]).toMatchObject({ profile_id: 4, name: 'Kilit izleme · Sipariş DB Yedek' })
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-bulk"][data-state="done"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="dbq-bulk-retry"]')).toBeNull()
    fireEvent.click(document.querySelector('[data-slot="dbq-wiz-done"]'))
    await waitFor(() => expect(wiz()).toBeNull())
  })

  it('ağ hatası "sonuç bilinmiyor": listede aynı ad varsa oluşturulmuş sayılır, yoksa bilinmiyor kalır ve yeniden denenmez (O2)', async () => {
    api.monitoring.getDbQueryMonitors.mockReset()
    api.monitoring.getDbQueryMonitors.mockResolvedValueOnce({ success: true, data: [] })
      .mockResolvedValue({ success: true, data: [{ id: 300, name: 'bloklanan / kilit bekleyen oturumlar · sipariş db' }] })
    api.monitoring.createDbQueryMonitor
      .mockRejectedValueOnce(Object.assign(new Error('Sunucuya ulaşılamadı'), { status: 0 }))
      .mockResolvedValueOnce({ success: false, status: 504, error: 'Ağ geçidi zaman aşımı' })
    await openWizard()
    await walkToSummary()
    const dlg = wiz().closest('[role="dialog"]')
    await fillGroupAndTags({ group: 'Kurumsal', tag: 'dba', root: dlg })
    fireEvent.click(within(dlg).getByRole('switch', { name: /Birden çok bağlantı için oluştur/ }))
    const list = await waitFor(() => { const l = document.querySelector('[data-slot="dbq-bulk-connections"]'); if (!l) throw new Error('yok'); return l })
    fireEvent.click(list.querySelector('[data-id="4"] [role="checkbox"]'))
    fireEvent.click(document.querySelector('[data-slot="dbq-wiz-save"]'))
    const done = await waitFor(() => { const r = document.querySelector('[data-slot="dbq-bulk"][data-state="done"]'); if (!r) throw new Error('yok'); return r })
    await waitFor(() => expect([...done.querySelectorAll('[data-slot="dbq-bulk-result"]')].map((r) => r.getAttribute('data-status'))).toEqual(['ok', 'unknown']))
    expect(done.textContent).toContain('yanıt gelmedi, listede bulundu')
    expect(done.querySelector('[data-slot="dbq-bulk-summary"]').textContent).toBe('1 izleme oluşturuldu · 1 sonucu bilinmiyor')
    expect(done.querySelector('[data-slot="dbq-bulk-unknown-note"]')).not.toBeNull()
    // bilinmeyen satır yeniden denenmez → yeniden dene düğmesi yok
    expect(document.querySelector('[data-slot="dbq-bulk-retry"]')).toBeNull()
  })

  it('bağlantı değişince toplu seçimde eski form bağlantısı çıkar, yenisi girer (O6)', async () => {
    await openWizard()
    await walkToSummary()
    const dlg = wiz().closest('[role="dialog"]')
    fireEvent.click(within(dlg).getByRole('switch', { name: /Birden çok bağlantı için oluştur/ }))
    let list = await waitFor(() => { const l = document.querySelector('[data-slot="dbq-bulk-connections"]'); if (!l) throw new Error('yok'); return l })
    expect(list.querySelector('[data-id="3"] [role="checkbox"]')).toHaveAttribute('data-state', 'checked')
    fireEvent.click(list.querySelector('[data-id="5"] [role="checkbox"]'))
    // Bağlantı adımına dön, başka bağlantı seç
    fireEvent.click(within(dlg).getByRole('button', { name: /Düzenle: Bağlantı/ }))
    expect(step()).toBe('connection')
    fireEvent.click(screen.getByRole('button', { name: 'pick-connection-4' }))
    fireEvent.click(within(dlg).getByRole('button', { name: /Adım 6: Özet/ }))
    expect(step()).toBe('summary')
    list = await waitFor(() => { const l = document.querySelector('[data-slot="dbq-bulk-connections"]'); if (!l) throw new Error('yok'); return l })
    expect(list.querySelector('[data-id="3"] [role="checkbox"]')).toHaveAttribute('data-state', 'unchecked')
    expect(list.querySelector('[data-id="4"] [role="checkbox"]')).toHaveAttribute('data-state', 'checked')
    // elle eklenen korunur
    expect(list.querySelector('[data-id="5"] [role="checkbox"]')).toHaveAttribute('data-state', 'checked')
  })

  it('kalıpta bağlantı adı yoksa yinelenen adlar gönderilmeden işaretlenir; Kaydet kapalı', async () => {
    await openWizard()
    await walkToSummary()
    const dlg = wiz().closest('[role="dialog"]')
    await fillGroupAndTags({ group: 'Kurumsal', tag: 'dba', root: dlg })
    fireEvent.click(within(dlg).getByRole('switch', { name: /Birden çok bağlantı için oluştur/ }))
    const list = await waitFor(() => { const l = document.querySelector('[data-slot="dbq-bulk-connections"]'); if (!l) throw new Error('yok'); return l })
    fireEvent.click(list.querySelector('[data-id="4"] [role="checkbox"]'))
    fireEvent.change(within(dlg).getByLabelText('Ad kalıbı'), { target: { value: '{ad}' } })
    expect(document.querySelector('[data-slot="dbq-bulk-pattern-warn"]')).not.toBeNull()
    expect([...document.querySelectorAll('[data-slot="dbq-bulk-plan-row"]')].map((r) => r.getAttribute('data-problem'))).toEqual(['duplicate', 'duplicate'])
    expect(document.querySelector('[data-slot="dbq-wiz-save"]')).toBeDisabled()
    expect(api.monitoring.createDbQueryMonitor).not.toHaveBeenCalled()
  })
})

describe('Uzun formdan toplu oluşturma (B4)', () => {
  it('"Birden çok bağlantı için…" doğrular, formun gövdesiyle pencere açar; başarı sonrası kapatınca form kapanır', async () => {
    api.monitoring.createDbQueryMonitor.mockResolvedValue({ success: true, data: { id: 200 } })
    render(<DbQueryMonitorPage {...userProps} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Yeni İzleme' }, { timeout: 3000 }))
    const dlg = await screen.findByRole('dialog', { name: /Yeni veritabanı sorgu izlemesi/ })
    fireEvent.click(within(dlg).getByRole('button', { name: /Birden çok bağlantı için/ }))
    // boş form → alan hataları, pencere açılmaz
    await waitFor(() => expect(dlg.querySelector('[data-field="name"]').textContent).toMatch(/zorunlu/))
    expect(document.querySelector('[data-slot="dbq-bulk"]')).toBeNull()
    fireEvent.change(within(dlg).getByLabelText(/^Ad/), { target: { value: 'Kuyruk' } })
    fireEvent.change(within(dlg).getByLabelText(/^Bu alarm ne için\?/), { target: { value: 'Kuyruk birikmesini izler' } })
    await fillGroupAndTags({ group: 'Kurumsal', tag: 'prod', root: dlg })
    fireEvent.change(within(dlg).getByLabelText('sql'), { target: { value: 'SELECT 1 AS x' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /Birden çok bağlantı için/ }))
    const bulk = await waitFor(() => { const b = document.querySelector('[data-slot="dbq-bulk"]'); if (!b) throw new Error('yok'); return b })
    await waitFor(() => expect(bulk.querySelector('[data-slot="dbq-bulk-connections"]')).not.toBeNull())
    fireEvent.click(bulk.querySelector('[data-id="3"] [role="checkbox"]'))
    fireEvent.click(bulk.querySelector('[data-id="4"] [role="checkbox"]'))
    fireEvent.click(document.querySelector('[data-slot="dbq-bulk-run"]'))
    await waitFor(() => expect(api.monitoring.createDbQueryMonitor).toHaveBeenCalledTimes(2))
    expect(api.monitoring.createDbQueryMonitor.mock.calls[0][0]).toMatchObject({ name: 'Kuyruk · Sipariş DB', profile_id: 3, sql_text: 'SELECT 1 AS x', groupName: 'Kurumsal' })
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-bulk"][data-state="done"]')).not.toBeNull())
    fireEvent.click(document.querySelector('[data-slot="dbq-bulk-close"]'))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: /Yeni veritabanı sorgu izlemesi/ })).toBeNull())
  })

  it('kısmi başarıda pencere kapanınca uzun form AÇIK kalır ve neden söylenir (O1)', async () => {
    api.monitoring.createDbQueryMonitor
      .mockResolvedValueOnce({ success: true, data: { id: 201 } })
      .mockResolvedValueOnce({ success: false, status: 400, code: 'DBQUERY_SQL_REJECTED', error: 'Sorgu bu türde reddedildi.' })
    render(<DbQueryMonitorPage {...userProps} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Yeni İzleme' }, { timeout: 3000 }))
    const dlg = await screen.findByRole('dialog', { name: /Yeni veritabanı sorgu izlemesi/ })
    fireEvent.change(within(dlg).getByLabelText(/^Ad/), { target: { value: 'Kuyruk' } })
    fireEvent.change(within(dlg).getByLabelText(/^Bu alarm ne için\?/), { target: { value: 'Kuyruk birikmesini izler' } })
    await fillGroupAndTags({ group: 'Kurumsal', tag: 'prod', root: dlg })
    fireEvent.change(within(dlg).getByLabelText('sql'), { target: { value: 'SELECT 1 AS x' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /Birden çok bağlantı için/ }))
    const bulk = await waitFor(() => { const b = document.querySelector('[data-slot="dbq-bulk"]'); if (!b) throw new Error('yok'); return b })
    await waitFor(() => expect(bulk.querySelector('[data-slot="dbq-bulk-connections"]')).not.toBeNull())
    fireEvent.click(bulk.querySelector('[data-id="3"] [role="checkbox"]'))
    fireEvent.click(bulk.querySelector('[data-id="5"] [role="checkbox"]'))
    fireEvent.click(document.querySelector('[data-slot="dbq-bulk-run"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-bulk"][data-state="done"]')).not.toBeNull())
    fireEvent.click(document.querySelector('[data-slot="dbq-bulk-close"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-bulk"]')).toBeNull())
    expect(screen.getByRole('dialog', { name: /Yeni veritabanı sorgu izlemesi/ })).toBeInTheDocument()
    expect(toastMock.info).toHaveBeenCalledWith(expect.stringContaining('form açık kaldı'))
  })
})
