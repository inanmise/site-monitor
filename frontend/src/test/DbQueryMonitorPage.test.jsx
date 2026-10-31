import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act, fillGroupAndTags } from './test-utils.jsx'

/**
 * Veritabanı Sorgu İzleme sayfası (11. tür, 2026-10-31) — davranış sözleşmesi.
 *
 * <p>Yapı taşları (kural oluşturucu, zamanlama, sorgu düzenleyici, bağlantı seçici …) ayrı ajanın dosyasıdır; burada
 * props sözleşmelerine (buildingBlocks.jsx başı) uyan ince test çiftleriyle değiştirilir: sayfanın onlara HANGİ veriyi ve
 * HANGİ alan hatasını verdiği sınanır. Ana kural: SELECT sonucunun satırları sayfanın hiçbir yerinde görünmez.
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
        <div data-field="rules" data-slot="t-rule-builder">
          {Object.entries(p.fieldErrors || {}).map(([k, v]) => <p key={k} data-err={k}>{v}</p>)}
          <span data-slot="t-rule-columns">{(p.columns || []).map((c) => c.name).join(',')}</span>
        </div>
      )
    },
    ScheduleEditor: (p) => {
      blocks.props.ScheduleEditor = p
      return <div data-slot="t-schedule">{Object.entries(p.fieldErrors || {}).map(([k, v]) => <p key={k} data-err={k}>{v}</p>)}</div>
    },
    SqlEditor: (p) => {
      blocks.props.SqlEditor = p
      return (
        <div data-field="sql_text">
          <textarea aria-label="sql" value={p.value} onChange={(e) => p.onChange(e.target.value)} />
          {p.fieldError && <p data-slot="t-sql-error">{p.fieldError}</p>}
          {(p.violations || []).map((v, i) => <p key={i} data-slot="t-sql-violation">{v.code}</p>)}
        </div>
      )
    },
    ConnectionPicker: (p) => {
      blocks.props.ConnectionPicker = p
      return (
        <div data-field="profile_id" data-slot="t-conn-picker" data-team={String(p.teamId)}>
          <button type="button" onClick={() => p.onChange(3, { id: 3, name: 'Sipariş DB', db_type: 'MSSQL' })}>pick-connection</button>
          {p.error && <p data-slot="t-conn-error">{p.error}</p>}
        </div>
      )
    },
    ConnectionsManager: remember('ConnectionsManager'),
    TemplatePicker: (p) => {
      blocks.props.TemplatePicker = p
      return <button type="button" onClick={() => p.onPick({ name: 'Bekleyen', sql_text: 'SELECT 2', purpose: 'Şablon amacı', rules: [{ id: 'r9' }], rule_logic: 'ALL' })}>pick-template</button>
    },
    SaveTemplateDialog: remember('SaveTemplateDialog'),
    TemplatesManager: remember('TemplatesManager'),
    RuleResultsList: ({ results }) => <ul data-slot="t-rule-results">{(results || []).map((r) => <li key={r.id}>{r.label}:{String(r.actual)}</li>)}</ul>,
    DbQueryAlertRows: () => null,
  }
})

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      getDbQueryMonitors: vi.fn(), createDbQueryMonitor: vi.fn(), updateDbQueryMonitor: vi.fn(), deleteDbQueryMonitor: vi.fn(),
      triggerDbQueryCheck: vi.fn(), testDbQuery: vi.fn(), dbQueryPlanCheck: vi.fn(), dbConnections: vi.fn(),
      listGroups: vi.fn(() => Promise.resolve({ success: true, data: [{ name: 'Kurumsal' }] })),
      listTags: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults: vi.fn(() => Promise.resolve({ success: true, data: { dbquery: { max_timeout_seconds: 60, max_rows_cap: 5000 } } })),
      getCheckHistory: vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  }),
}))
import { api } from '../api/client'
import DbQueryMonitorPage from '../components/DbQueryMonitorPage.jsx'

const PROFILE = { id: 3, name: 'Sipariş DB (PostgreSQL üretim)', db_type: 'POSTGRESQL', host: 'db.example.com', port: 5432, database_name: 'orders' }
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
  api.monitoring.dbConnections.mockResolvedValue({ success: true, data: [{ id: 3, health: { state: 'ok', last_ok_at: '2026-10-31T09:59:00' } }] })
  api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: { items: [], counts: { total: 0, fail: 0 }, total: 0, page: 0, size: 50 } })
})
afterEach(() => window.history.replaceState({}, '', '/'))

describe('DbQueryMonitorPage — liste', () => {
  it('kart: ad, bağlantı rozeti (tür + ad), amaç, ölçüler; durum eşlemesi ve sorunlu-önce sıra', async () => {
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [
      row({ id: 1, name: 'A sağlıklı' }),
      row({ id: 2, name: 'B uyarı', status: 'BREACH', breach_level: 'WARNING' }),
      row({ id: 3, name: 'C kritik', status: 'BREACH', breach_level: 'CRITICAL' }),
      row({ id: 4, name: 'D hata', status: 'FAIL', error: 'ORA-12541: TNS:no listener', failure_reason: 'DB_UNREACHABLE' }),
      row({ id: 5, name: 'E hiç çalışmadı', status: null, last_check: null, duration_ms: null, row_count: null, metric_value: null }),
      row({ id: 6, name: 'F yavaş', slow: true }),
    ] })
    const { container } = render(<DbQueryMonitorPage {...props} />)
    await waitFor(() => expect(titles()).toHaveLength(6))
    // Sorunlu önce (kırmızı → sarı), sonra aynı grupta ada göre
    expect(titles()).toEqual(['C kritik', 'D hata', 'B uyarı', 'F yavaş', 'A sağlıklı', 'E hiç çalışmadı'])
    const card = (name) => [...container.querySelectorAll('.upt-grid > [data-slot="card"]')].find((c) => c.textContent.includes(name))
    expect(card('A sağlıklı')).toHaveAttribute('data-status', 'up')
    expect(card('B uyarı')).toHaveAttribute('data-status', 'warn')
    expect(card('C kritik')).toHaveAttribute('data-status', 'down')
    expect(card('D hata')).toHaveAttribute('data-status', 'down')
    expect(card('F yavaş')).toHaveAttribute('data-status', 'warn')
    // Bilinmeyen asla "sağlıklı" değil
    expect(card('E hiç çalışmadı')).toHaveAttribute('data-status', 'unknown')
    expect(card('E hiç çalışmadı').querySelector('[data-slot="dbq-pending"]')).not.toBeNull()
    expect(card('C kritik').querySelector('[data-slot="dbq-level"]')).toHaveAttribute('data-level', 'CRITICAL')
    const conn = card('A sağlıklı').querySelector('[data-slot="dbq-connection"]')
    expect(conn.textContent).toContain('PostgreSQL')
    expect(conn.textContent).toContain('Sipariş DB (PostgreSQL üretim)')
    expect(card('A sağlıklı').querySelector('[data-slot="dbq-purpose"]').textContent).toBe('Bekleyen sipariş sayısını izler')
    expect(card('A sağlıklı').querySelector('[data-slot="dbq-value-value"]').textContent).toBe('12')
    expect(card('D hata').querySelector('[data-slot="dbq-error"]').textContent).toContain('ORA-12541')
  })

  it('bağlantısı okunamayan satır uyarı rozetiyle çizilir (sessizce boş değil)', async () => {
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [row({ profile: null, profile_missing: true })] })
    render(<DbQueryMonitorPage {...props} />)
    const conn = await waitFor(() => { const el = document.querySelector('[data-slot="dbq-connection"]'); expect(el).not.toBeNull(); return el })
    expect(conn).toHaveAttribute('data-missing', 'true')
    expect(conn.textContent).toMatch(/unreadable or deleted/)
  })

  it('satır sayısı tavanına takılmışsa "≥ N"', async () => {
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [row({ row_count: 1000, row_count_capped: true })] })
    render(<DbQueryMonitorPage {...props} />)
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-rows-value"]')?.textContent).toBe('≥ 1,000'))
  })

  it('liste okunamazsa "izleme yok" DEMEZ — hata bandı + yeniden dene', async () => {
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: false, error: 'Sunucu yanıt vermedi' })
    render(<DbQueryMonitorPage {...props} />)
    expect(await screen.findByText('Sunucu yanıt vermedi')).toBeInTheDocument()
    expect(screen.queryByText(/No database query monitors yet/)).toBeNull()
    expect(screen.getByRole('button', { name: /retry|tekrar dene/i })).toBeInTheDocument()
  })

  it('boş liste: boş durum (yazabilen için "ilkini ekleyin")', async () => {
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [] })
    render(<DbQueryMonitorPage {...props} />)
    expect(await screen.findByText(/No database query monitors yet — add the first one/)).toBeInTheDocument()
  })

  it('arama ad, amaç ve bağlantı adında (Türkçe katlama)', async () => {
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [
      row({ id: 1, name: 'Ödeme hataları', purpose: 'Başarısız ödemeleri sayar' }),
      row({ id: 2, name: 'Mutabakat', purpose: 'Gece dosyası', profile: { ...PROFILE, name: 'MUTABAKAT-MSSQL-01', db_type: 'MSSQL' } }),
    ] })
    render(<DbQueryMonitorPage {...props} />)
    await waitFor(() => expect(titles()).toHaveLength(2))
    const search = screen.getByRole('textbox', { name: /search name, purpose or connection/i })
    fireEvent.change(search, { target: { value: 'odeme' } })
    await waitFor(() => expect(titles()).toEqual(['Ödeme hataları']))
    fireEvent.change(search, { target: { value: 'mssql-01' } })
    await waitFor(() => expect(titles()).toEqual(['Mutabakat']))
    fireEvent.change(search, { target: { value: 'gece dosyası' } })
    await waitFor(() => expect(titles()).toEqual(['Mutabakat']))
  })

  it('"Şimdi kontrol et" meşgul (429 DBQUERY_BUSY) → anlamlı tost, kart bozulmaz', async () => {
    api.monitoring.triggerDbQueryCheck.mockResolvedValue({ success: false, code: 'DBQUERY_BUSY', error: 'busy' })
    render(<DbQueryMonitorPage {...props} />)
    fireEvent.click(await screen.findByRole('button', { name: /^Sipariş kuyruğu — check now$/i }))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith(expect.stringMatching(/connection is busy/)))
  })

  it('başlıkta Bağlantılar ve Şablonlar yöneticileri açılır (takım listesiyle)', async () => {
    render(<DbQueryMonitorPage {...props} />)
    fireEvent.click(await screen.findByRole('button', { name: /^Connections$/ }))
    await waitFor(() => expect(blocks.props.ConnectionsManager?.open).toBe(true))
    expect(blocks.props.ConnectionsManager.teams).toEqual([{ id: 5, name: 'SY-A' }])
    fireEvent.click(screen.getByRole('button', { name: /^Templates$/ }))
    await waitFor(() => expect(blocks.props.TemplatesManager?.open).toBe(true))
  })

  it('yöneticinin kendi takımında izleme yoksa bağlantı yöneticisi izlemesi olan takımla açılır (canlı denemede bulundu)', async () => {
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 9, name: 'General' }, { id: 5, name: 'SY-A' }] })
    render(<DbQueryMonitorPage {...props} teamId={9} teamName="General" myTeams={[{ id: 9, name: 'General' }]} />)
    await waitFor(() => expect(titles().length).toBeGreaterThan(0))
    fireEvent.click(await screen.findByRole('button', { name: /^Connections$/ }))
    await waitFor(() => expect(blocks.props.ConnectionsManager?.open).toBe(true))
    expect(blocks.props.ConnectionsManager.teamId).toBe(5)
  })
})

describe('DbQueryMonitorPage — form', () => {
  // Takım üyesi (tek takım): takım alanı sabit, Kaydet etkin — yönetici için takım seçilene dek Kaydet kapalı.
  async function openNew() {
    render(<DbQueryMonitorPage {...props} systemRole="USER" />)
    await waitFor(() => expect(titles()).toHaveLength(1))
    fireEvent.click(screen.getByRole('button', { name: /^New Monitor$/ }))
    return formDialog()
  }

  it('boş kayıt: hatalar ALANIN ALTINDA (ad, takım, grup, etiket, amaç, bağlantı, sorgu) — tost yok, istek yok', async () => {
    await openNew()
    fireEvent.click(within(formDialog()).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(fieldOf('name').textContent).toMatch(/required/i))
    expect(fieldOf('purpose').textContent).toMatch(/5–2000/)
    expect(document.querySelector('[data-slot="t-conn-error"]').textContent).toMatch(/Pick a connection/)
    expect(document.querySelector('[data-slot="t-sql-error"]').textContent).toMatch(/Write the query/)
    expect(api.monitoring.createDbQueryMonitor).not.toHaveBeenCalled()
    expect(toastMock.error).not.toHaveBeenCalled()
  })

  async function fillValid() {
    const dlg = formDialog()
    fireEvent.change(within(dlg).getByLabelText(/^Name/), { target: { value: 'Yeni sorgu' } })
    await fillGroupAndTags({ group: 'Kurumsal', tag: 'prod', root: dlg })
    fireEvent.change(within(dlg).getByLabelText(/^What is this alert for\?/), { target: { value: 'Bekleyen sipariş sayısı' } })
    fireEvent.click(within(dlg).getByRole('button', { name: 'pick-connection' }))
    fireEvent.change(within(dlg).getByLabelText('sql'), { target: { value: 'SELECT count(*) FROM orders' } })
  }

  it('bağlantı seçici formun takımıyla çağrılır; seçilen bağlantının türü sorgu düzenleyicisine geçer', async () => {
    await openNew()
    await fillValid()
    await waitFor(() => expect(blocks.props.ConnectionPicker.teamId).toBe(5))
    expect(blocks.props.SqlEditor.dbType).toBe('MSSQL')
  })

  it('sunucu doğrulama hataları alanların altına dağıtılır (fields + kural alt alanı + cron) — tost yok', async () => {
    api.monitoring.createDbQueryMonitor.mockResolvedValue({ success: false, code: 'VALIDATION_FAILED', error: 'Doğrulama', field: 'name',
      fields: { cron_expression: 'Cron ifadesi geçersiz', 'rules[0].warning': 'Eşik sayı olmalı', name: 'Bu ad takımda zaten var' } })
    await openNew()
    await fillValid()
    fireEvent.click(within(formDialog()).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(fieldOf('name').textContent).toContain('Bu ad takımda zaten var'))
    expect(document.querySelector('[data-slot="t-rule-builder"] [data-err="rules[0].warning"]').textContent).toBe('Eşik sayı olmalı')
    expect(document.querySelector('[data-slot="t-schedule"] [data-err="cron_expression"]').textContent).toBe('Cron ifadesi geçersiz')
    expect(toastMock.error).not.toHaveBeenCalled()
    // Gövde: standart alanlar camelCase (paylaşılan yardımcılar), türe özgü alanlar snake_case (api §1)
    const body = api.monitoring.createDbQueryMonitor.mock.calls[0][0]
    expect(body).toMatchObject({ name: 'Yeni sorgu', teamId: 5, groupName: 'Kurumsal', profile_id: 3, sql_text: 'SELECT count(*) FROM orders',
      purpose: 'Bekleyen sipariş sayısı', schedule_mode: 'INTERVAL', nocNotify: false })
    expect(body).not.toHaveProperty('team_id')
  })

  it('sorgu reddi (DBQUERY_SQL_REJECTED) → ihlaller sorgu düzenleyicisine, ileti alanın altına', async () => {
    api.monitoring.createDbQueryMonitor.mockResolvedValue({ success: false, code: 'DBQUERY_SQL_REJECTED', field: 'sql_text',
      error: 'Sorgu yalnız SELECT olmalı', violations: [{ code: 'SQL_NOT_SELECT', message: 'SELECT değil', line: 1, column: 1 }] })
    await openNew()
    await fillValid()
    fireEvent.click(within(formDialog()).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="t-sql-error"]')?.textContent).toBe('Sorgu yalnız SELECT olmalı'))
    expect(document.querySelector('[data-slot="t-sql-violation"]').textContent).toBe('SQL_NOT_SELECT')
  })

  it('"Sorguyu dene": durum, süre, kolon ADLARI ve kural sonucu — SATIR VERİSİ ASLA görünmez', async () => {
    api.monitoring.testDbQuery.mockResolvedValue({ success: true, data: {
      status: 'BREACH', breach_level: 'WARNING', duration_ms: 320, row_count: 3, row_count_capped: false, slow: false, metric_value: 3,
      columns: [{ name: 'musteri_no', type: 'VARCHAR' }, { name: 'tutar', type: 'NUMERIC' }],
      rule_results: [{ id: 'r1', label: 'Bekleyen', actual: 3, level: 'WARNING', passed: false, evaluated: true }],
      guard: { ok: true, violations: [], tables: ['orders'] },
      // Sözleşme dışı bir sunucu satır gönderse bile ekranda çizilmez
      rows: [['GIZLI-MUSTERI-4455', '9999']],
    } })
    await openNew()
    await fillValid()
    fireEvent.click(within(formDialog()).getByRole('button', { name: /^Try the query$/ }))
    const panel = await waitFor(() => { const el = document.querySelector('[data-slot="dbq-test"]'); expect(el).not.toBeNull(); return el })
    expect(panel).toHaveAttribute('data-status', 'BREACH')
    expect(panel.querySelector('[data-slot="dbq-test-columns"]').textContent).toContain('musteri_no')
    expect(panel.querySelector('[data-slot="dbq-test-norows"]')).not.toBeNull()
    expect(document.body.textContent).not.toContain('GIZLI-MUSTERI-4455')
    // Kolon adları kural oluşturucuya öneri olarak geçer
    await waitFor(() => expect(document.querySelector('[data-slot="t-rule-columns"]').textContent).toBe('musteri_no,tutar'))
    const body = api.monitoring.testDbQuery.mock.calls[0][0]
    expect(body).toMatchObject({ profile_id: 3, sql_text: 'SELECT count(*) FROM orders' })
  })

  it('"Sorguyu dene" bağlantı / sorgu yokken kilitli ve nedenini söyler', async () => {
    await openNew()
    const btn = within(formDialog()).getByRole('button', { name: /^Try the query$/ })
    expect(btn).toBeDisabled()
    expect(formDialog().textContent).toMatch(/Pick a connection first/)
  })

  it('"Sorguyu dene" bekleme (429) → panelde kaç saniye sonra denenebileceği', async () => {
    api.monitoring.testDbQuery.mockResolvedValue({ success: false, code: 'RATE_LIMITED', error: 'Çok sık denendi', retry_after_seconds: 8 })
    await openNew()
    await fillValid()
    fireEvent.click(within(formDialog()).getByRole('button', { name: /^Try the query$/ }))
    const panel = await waitFor(() => { const el = document.querySelector('[data-slot="dbq-test"]'); expect(el).not.toBeNull(); return el })
    // Bekleme hata değil (bilgi tonu); sunucu iletisi varsa yalnız o (ikinci bekleme cümlesi eklenmez — DÜŞÜK 5)
    expect(panel).toHaveAttribute('data-status', 'wait')
    expect(panel.textContent).toContain('Çok sık denendi')
    expect(panel.textContent).not.toMatch(/try again in 8 s/)
  })

  it('şablondan başla: dolu sorgu üzerine onay ister, onayla sorgu + kural + birleşim gelir; dolu amaç korunur', async () => {
    await openNew()
    await fillValid()
    fireEvent.click(within(formDialog()).getByRole('button', { name: 'pick-template' }))
    fireEvent.click(await screen.findByRole('button', { name: /^Apply template$/ }))
    await waitFor(() => expect(blocks.props.SqlEditor.value).toBe('SELECT 2'))
    expect(blocks.props.RuleBuilder.rules).toEqual([{ id: 'r9' }])
    expect(blocks.props.RuleBuilder.logic).toBe('ALL')
    expect(within(formDialog()).getByLabelText(/^What is this alert for\?/)).toHaveValue('Bekleyen sipariş sayısı')
  })

  it('kaydedilmemiş değişiklikle İptal → onay; "Düzenlemeye devam et" formu açık bırakır', async () => {
    await openNew()
    fireEvent.change(within(formDialog()).getByLabelText(/^Name/), { target: { value: 'Yarım' } })
    fireEvent.click(within(formDialog()).getByRole('button', { name: /^Cancel$/ }))
    fireEvent.click(await screen.findByRole('button', { name: /^Keep editing$/ }))
    await waitFor(() => expect(within(formDialog()).getByLabelText(/^Name/)).toHaveValue('Yarım'))
  })
})

describe('DbQueryMonitorPage — düzenleme', () => {
  async function openEdit(over = {}) {
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [row(over)] })
    render(<DbQueryMonitorPage {...props} />)
    fireEvent.click(await screen.findByRole('button', { name: /^Sipariş kuyruğu — edit$/i }))
    return formDialog()
  }

  it('yalnız değişen alan gönderilir (amaç değişti → gövde { purpose })', async () => {
    api.monitoring.updateDbQueryMonitor.mockResolvedValue({ success: true, data: row({ purpose: 'Yeni amaç metni' }) })
    const dlg = await openEdit()
    fireEvent.change(within(dlg).getByLabelText(/^What is this alert for\?/), { target: { value: 'Yeni amaç metni' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(api.monitoring.updateDbQueryMonitor).toHaveBeenCalledTimes(1))
    expect(api.monitoring.updateDbQueryMonitor.mock.calls[0]).toEqual([1, { purpose: 'Yeni amaç metni' }])
    // Amaç kontrolü etkilemez → kayıt sonrası kontrol BAŞLAMAZ
    await act(async () => {})
    expect(api.monitoring.triggerDbQueryCheck).not.toHaveBeenCalled()
  })

  it('sorgu değişti → bağlantı kümesiyle birlikte gönderilir ve kayıttan sonra taze kontrol başlar', async () => {
    const saved = row({ sql_text: 'SELECT 1' })
    api.monitoring.updateDbQueryMonitor.mockResolvedValue({ success: true, data: saved })
    api.monitoring.triggerDbQueryCheck.mockResolvedValue({ success: true, data: saved })
    const dlg = await openEdit()
    fireEvent.change(within(dlg).getByLabelText('sql'), { target: { value: 'SELECT 1' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(api.monitoring.updateDbQueryMonitor).toHaveBeenCalledTimes(1))
    expect(api.monitoring.updateDbQueryMonitor.mock.calls[0][1]).toEqual({ profile_id: 3, sql_text: 'SELECT 1' })
    await waitFor(() => expect(api.monitoring.triggerDbQueryCheck).toHaveBeenCalledWith(1))
  })

  it('hiçbir şey değişmediyse istek GİTMEZ, "değişiklik yok" denir ve form kapanır', async () => {
    const dlg = await openEdit()
    fireEvent.click(within(dlg).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(toastMock.info).toHaveBeenCalledWith(expect.stringMatching(/No changes/)))
    expect(api.monitoring.updateDbQueryMonitor).not.toHaveBeenCalled()
  })

  it('paylaşımla düzenlenen kayıt (routing_locked): bağlantı değiştirilemez, seçici yerine kilit notu', async () => {
    const dlg = await openEdit({ routing_locked: true, shared_access: true })
    expect(dlg.querySelector('[data-slot="dbq-connection-locked"]').textContent).toContain('Sipariş DB (PostgreSQL üretim)')
    expect(dlg.querySelector('[data-slot="t-conn-picker"]')).toBeNull()
  })

  it('takım değişince grup adı da gönderilir (yeni takımın grup listesine kaydolsun — DÜŞÜK 4)', async () => {
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }, { id: 6, name: 'SY-B' }] })
    api.monitoring.updateDbQueryMonitor.mockResolvedValue({ success: true, data: row({ team_id: 6 }) })
    const dlg = await openEdit()
    await act(async () => {})
    fireEvent.mouseDown(within(fieldOf('team_id')).getByRole('combobox'))
    fireEvent.mouseDown([...document.querySelectorAll('[role="option"]')].find((o) => o.textContent.includes('SY-B')))
    fireEvent.click(within(dlg).getByRole('button', { name: 'pick-connection' }))
    fireEvent.click(within(dlg).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(api.monitoring.updateDbQueryMonitor).toHaveBeenCalledTimes(1))
    expect(api.monitoring.updateDbQueryMonitor.mock.calls[0][1]).toMatchObject({ teamId: 6, groupName: 'Kurumsal' })
  })
})

describe('DbQueryMonitorPage — ayrıntı', () => {
  it('?monitor= derin bağlantısı Özet sekmesini açar: amaç, satır numaralı sorgu, kural sonuçları, sonraki çalışma', async () => {
    window.history.replaceState({}, '', '/?tab=dbquery&monitor=1')
    render(<DbQueryMonitorPage {...props} />)
    const dlg = await waitFor(() => { const d = screen.getAllByRole('dialog').find((x) => x.textContent.includes('Sipariş kuyruğu')); expect(d).toBeTruthy(); return d })
    expect(dlg.querySelector('[data-slot="dbq-detail-purpose"]').textContent).toContain('Bekleyen sipariş sayısını izler')
    const lines = dlg.querySelectorAll('[data-slot="dbq-sql-line"]')
    expect(lines).toHaveLength(2)
    expect(lines[1].textContent).toContain('FROM orders')
    expect(dlg.querySelector('[data-slot="t-rule-results"]').textContent).toContain('Bekleyen:12')
    await waitFor(() => expect(dlg.querySelector('[data-slot="dbq-health"]')).toHaveAttribute('data-state', 'ok'))
  })

  it('bağlantı sağlığı okunamazsa "okunamadı" (asla "bağlantı yok" ya da yeşil değil)', async () => {
    api.monitoring.dbConnections.mockResolvedValue({ success: false, status: 403, error: 'forbidden' })
    window.history.replaceState({}, '', '/?tab=dbquery&monitor=1')
    render(<DbQueryMonitorPage {...props} />)
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-health"]')).toHaveAttribute('data-state', 'unreadable'))
    expect(document.querySelector('[data-slot="dbq-health"]').textContent).toMatch(/could not be read/)
  })

  it('tanınmayan sağlık durumu "Bilinmiyor" (asla "Denenmedi" ya da yeşil değil — DÜŞÜK 12); saatler İstanbul (16); sorgu bloğu klavyeyle kaydırılır (10b)', async () => {
    api.monitoring.dbConnections.mockResolvedValue({ success: true, data: [{ id: 3, health: { state: 'bambaşka' } }] })
    window.history.replaceState({}, '', '/?tab=dbquery&monitor=1')
    render(<DbQueryMonitorPage {...props} />)
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-health"]')).toHaveAttribute('data-state', 'unknown'))
    expect(document.querySelector('[data-slot="dbq-health"]').textContent).toContain('Unknown')
    expect(document.querySelector('[data-slot="dbq-detail-tz"]').textContent).toMatch(/Istanbul/)
    // Son çalışma da İstanbul saatiyle (10:00 UTC → 13:00)
    expect(document.querySelector('[data-slot="dbq-detail-run"]').textContent).toMatch(/13:00:00/)
    const sql = document.querySelector('[data-slot="dbq-detail-sql-scroll"]')
    expect(sql).toHaveAttribute('role', 'region')
    expect(sql).toHaveAttribute('tabindex', '0')
  })

  it('FAIL satırı: hata metni + neden açıklaması ve ne yapılacağı', async () => {
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [row({ status: 'FAIL', error: 'password authentication failed', failure_reason: 'DB_AUTH' })] })
    window.history.replaceState({}, '', '/?tab=dbquery&monitor=1')
    render(<DbQueryMonitorPage {...props} />)
    const err = await waitFor(() => { const el = document.querySelector('[data-slot="dbq-detail-error"]'); expect(el).not.toBeNull(); return el })
    expect(err.textContent).toContain('Authentication failed')
    expect(err.textContent).toContain('password authentication failed')
    expect(err.textContent).toMatch(/user name and password/)
  })

  it('Kontrol geçmişi: "yalnız sorunlu" anahtarı geçmiş isteğine only=problem ekler', async () => {
    window.history.replaceState({}, '', '/?tab=dbquery&monitor=1&mtab=control')
    render(<DbQueryMonitorPage {...props} />)
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())
    expect(api.monitoring.getCheckHistory.mock.calls[0][0]).toBe('dbquery')
    fireEvent.click(await screen.findByRole('switch', { name: /Only problem checks/ }))
    await waitFor(() => expect(api.monitoring.getCheckHistory.mock.calls.some((c) => c[2]?.only === 'problem')).toBe(true))
  })
})

describe('DbQueryMonitorPage — inceleme düzeltmeleri (2026-10-31)', () => {
  async function openNewAs(over = {}) {
    render(<DbQueryMonitorPage {...props} systemRole="USER" {...over} />)
    await waitFor(() => expect(titles().length).toBeGreaterThan(0))
    fireEvent.click(screen.getByRole('button', { name: /^New Monitor$/ }))
    return formDialog()
  }
  async function fillValid() {
    const dlg = formDialog()
    fireEvent.change(within(dlg).getByLabelText(/^Name/), { target: { value: 'Yeni sorgu' } })
    await fillGroupAndTags({ group: 'Kurumsal', tag: 'prod', root: dlg })
    fireEvent.change(within(dlg).getByLabelText(/^What is this alert for\?/), { target: { value: 'Bekleyen sipariş sayısı' } })
    fireEvent.click(within(dlg).getByRole('button', { name: 'pick-connection' }))
    fireEvent.change(within(dlg).getByLabelText('sql'), { target: { value: 'SELECT count(*) FROM orders' } })
  }

  it('K1: 409 DUPLICATE_RECORD (ad tüm takımlarda tekil) → sunucu metni AD alanının altında, tost yok', async () => {
    api.monitoring.createDbQueryMonitor.mockResolvedValue({ success: false, code: 'DUPLICATE_RECORD', field: 'name',
      error: 'Bu ad başka bir veritabanı sorgu izlemesinde kullanılıyor — alarmlar adla eşlendiği için ad tüm takımlarda tekil olmalı.' })
    await openNewAs()
    await fillValid()
    fireEvent.click(within(formDialog()).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(fieldOf('name').textContent).toContain('ad tüm takımlarda tekil olmalı'))
    expect(toastMock.error).not.toHaveBeenCalled()
  })

  it('K3 / DÜŞÜK 5: deneme bekleme (429) bilgi tonunda, YALNIZ sunucu metni (ikinci "… sonra" cümlesi yok)', async () => {
    const msg = 'Saatlik deneme sınırına ulaşıldı (60/saat) — 12 dk sonra yeniden deneyin.'
    api.monitoring.testDbQuery.mockResolvedValue({ success: false, code: 'RATE_LIMITED', error: msg, retry_after_seconds: 720 })
    await openNewAs()
    await fillValid()
    fireEvent.click(within(formDialog()).getByRole('button', { name: /^Try the query$/ }))
    const panel = await waitFor(() => { const el = document.querySelector('[data-slot="dbq-test"]'); expect(el).not.toBeNull(); return el })
    expect(panel).toHaveAttribute('data-status', 'wait')
    expect(panel.textContent).toContain('Please wait')
    expect(panel.textContent).toContain(msg)
    expect(panel.textContent).not.toMatch(/try again in 720 s/)
  })

  it('DÜŞÜK 6: maliyet denetimi bekleme (429) "Maliyet okunamadı" DEĞİL — bilgi tonu + sunucu metni', async () => {
    api.monitoring.dbQueryPlanCheck.mockResolvedValue({ success: false, code: 'RATE_LIMITED', error: 'Çok sık deneme; 5 sn sonra yeniden deneyin.', retry_after_seconds: 5 })
    await openNewAs()
    await fillValid()
    fireEvent.click(within(formDialog()).getByRole('button', { name: /^Check the cost$/ }))
    const plan = await waitFor(() => { const el = document.querySelector('[data-slot="dbq-test-plan"]'); expect(el).not.toBeNull(); return el })
    expect(plan).toHaveAttribute('data-verdict', 'WAIT')
    expect(plan.textContent).toContain('Çok sık deneme; 5 sn sonra yeniden deneyin.')
    expect(plan.textContent).not.toMatch(/could not be read/)
  })

  it('O3: maliyet sürerken deneme başlarsa maliyet sonucu YİNE gelir ve düğme kilitli kalmaz', async () => {
    let resolvePlan
    api.monitoring.dbQueryPlanCheck.mockImplementation(() => new Promise((r) => { resolvePlan = r }))
    api.monitoring.testDbQuery.mockResolvedValue({ success: true, data: { status: 'OK', duration_ms: 10, row_count: 1, columns: [] } })
    await openNewAs()
    await fillValid()
    fireEvent.click(within(formDialog()).getByRole('button', { name: /^Check the cost$/ }))
    fireEvent.click(within(formDialog()).getByRole('button', { name: /^Try the query$/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-test"]')).toHaveAttribute('data-status', 'OK'))
    await act(async () => { resolvePlan({ success: true, data: { supported: true, verdict: 'OK', cost: 12 } }) })
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-test-plan"]')).toHaveAttribute('data-verdict', 'OK'))
    expect(within(formDialog()).getByRole('button', { name: /^Check the cost$/ })).not.toBeDisabled()
  })

  it('DÜŞÜK 2: uçuştaki deneme bağlantı değişince forma DÜŞMEZ (eski bağlantının kolonları öneri olmaz)', async () => {
    let resolveTest
    api.monitoring.testDbQuery.mockImplementation(() => new Promise((r) => { resolveTest = r }))
    await openNewAs()
    await fillValid()
    fireEvent.click(within(formDialog()).getByRole('button', { name: /^Try the query$/ }))
    await act(async () => { blocks.props.ConnectionPicker.onChange(4, { id: 4, name: 'Diğer DB', db_type: 'POSTGRESQL' }) })
    await act(async () => { resolveTest({ success: true, data: { status: 'OK', columns: [{ name: 'eski_kolon', type: 'INT' }] } }) })
    expect(document.querySelector('[data-slot="dbq-test"]')).toBeNull()
    expect(document.querySelector('[data-slot="t-rule-columns"]').textContent).toBe('')
  })

  it('DÜŞÜK 13: yalnız DEĞİŞEN kuralın sunucu hatası silinir; sıra kayarsa hepsi', async () => {
    api.monitoring.createDbQueryMonitor.mockResolvedValue({ success: false, code: 'VALIDATION_FAILED', error: 'Doğrulama',
      fields: { 'rules[0].warning': 'Birinci kural', 'rules[1].critical': 'İkinci kural' } })
    await openNewAs()
    await fillValid()
    const two = [{ id: 'a', kind: 'ROW_COUNT', warning: 1 }, { id: 'b', kind: 'ROW_COUNT', critical: 2 }]
    await act(async () => { blocks.props.RuleBuilder.onChange(two) })
    fireEvent.click(within(formDialog()).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(document.querySelector('[data-err="rules[1].critical"]')).not.toBeNull())
    await act(async () => { blocks.props.RuleBuilder.onChange([two[0], { ...two[1], critical: 5 }]) })
    expect(document.querySelector('[data-err="rules[1].critical"]')).toBeNull()
    expect(document.querySelector('[data-err="rules[0].warning"]').textContent).toBe('Birinci kural')
    await act(async () => { blocks.props.RuleBuilder.onChange([{ ...two[1], critical: 5 }]) })
    expect(document.querySelector('[data-err="rules[0].warning"]')).toBeNull()
  })

  it('DÜŞÜK 14: sorgu boşken kurulmuş kurallar şablonla değişecekse de onay sorulur', async () => {
    await openNewAs()
    await act(async () => { blocks.props.RuleBuilder.onChange([{ id: 'mine', kind: 'ROW_COUNT', warning: 3 }]) })
    fireEvent.click(within(formDialog()).getByRole('button', { name: 'pick-template' }))
    fireEvent.click(await screen.findByRole('button', { name: /^Keep editing$/ }))
    expect(blocks.props.RuleBuilder.rules).toEqual([{ id: 'mine', kind: 'ROW_COUNT', warning: 3 }])
  })

  it('DÜŞÜK 9: mail ipuçları ayarlı sınırlardan; zamanlamaya alt sınır + tatil yetkisi geçer; mail kolon kutuları etiketli (10a)', async () => {
    api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { dbquery: { max_timeout_seconds: 60, max_rows_cap: 5000,
      mail_max_rows: 30, mail_max_columns: 12, min_interval_seconds: 300 } } })
    await openNewAs({ globalAdmin: false })
    await waitFor(() => expect(fieldOf('mail_columns').textContent).toContain('12'))
    expect(formDialog().textContent).toMatch(/Up to 30 rows/)
    expect(blocks.props.ScheduleEditor.minIntervalSeconds).toBe(300)
    expect(blocks.props.ScheduleEditor.canEditHolidays).toBe(false)
    expect(within(formDialog()).getByLabelText('Email columns')).toBeInTheDocument()
    expect(within(formDialog()).getByLabelText('Masked columns')).toBeInTheDocument()
  })

  it('DÜŞÜK 5: "Şimdi kontrol et" bekleme — sunucu iletisi TEK başına (arkasına "… sonra" eklenmez)', async () => {
    api.monitoring.triggerDbQueryCheck.mockResolvedValue({ success: false, code: 'RATE_LIMITED', error: 'Çok sık kontrol; 7 sn sonra yeniden deneyin.', retry_after_seconds: 7 })
    render(<DbQueryMonitorPage {...props} />)
    fireEvent.click(await screen.findByRole('button', { name: /^Sipariş kuyruğu — check now$/i }))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith('Çok sık kontrol; 7 sn sonra yeniden deneyin.'))
  })

  it('DÜŞÜK 16: karttaki "sonraki çalışma" alt satırı İstanbul saati olduğunu söyler', async () => {
    render(<DbQueryMonitorPage {...props} />)
    await waitFor(() => expect(titles()).toHaveLength(1))
    const next = document.querySelector('[data-slot="dbq-metric"][data-metric="next"]')
    expect(next.textContent).toMatch(/13:05 \(Istanbul\)/)
  })

  it('O6: toplu işlem çubuğunda takım değiştir YOK — yerinde bilgi satırı (bağlantı seçimi gerekir)', async () => {
    render(<DbQueryMonitorPage {...props} />)
    await waitFor(() => expect(titles()).toHaveLength(1))
    fireEvent.click(screen.getByRole('checkbox', { name: /Sipariş kuyruğu/ }))
    const bar = await waitFor(() => { const b = document.querySelector('[data-slot="bulk-action-bar"]'); expect(b).not.toBeNull(); return b })
    expect(within(bar).queryByRole('combobox', { name: 'Team' })).toBeNull()
    expect(bar.querySelector('[data-slot="bulk-team-note"]').textContent).toMatch(/changed in bulk/i)
  })

  it('DÜŞÜK 1: "yalnız sorunlu" açıkken yerleşik "Hatalı" süzgeci çizilmez (sunucu onu yok sayar)', async () => {
    window.history.replaceState({}, '', '/?tab=dbquery&monitor=1&mtab=control')
    render(<DbQueryMonitorPage {...props} />)
    await waitFor(() => expect(document.querySelector('[data-slot="hist-tiles"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="hist-tiles"]')).toHaveAttribute('role', 'group')
    fireEvent.click(await screen.findByRole('switch', { name: /Only problem checks/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="hist-tiles"]')).not.toHaveAttribute('role', 'group'))
    const last = api.monitoring.getCheckHistory.mock.calls.at(-1)[2]
    expect(last).toMatchObject({ only: 'problem', page: 0 })
  })
})

describe('DbQueryMonitorPage — paylaşımla erişim (O5 / K2)', () => {
  const grantProps = { systemRole: 'USER', teamId: 5, teamName: 'SY-A', globalAdmin: false,
    myTeams: [{ id: 5, name: 'SY-A' }, { id: 8, name: 'Paylaşan Takım', grant: true }] }

  it('düzenleme: bağlantı, sorgu, kurallar, mail içeriği ve sınırlar kilitli + not; deneme / maliyet / şablon düğmeleri yok', async () => {
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [row({ team_id: 8, team_name: 'Paylaşan Takım', routing_locked: true, shared_access: true })] })
    render(<DbQueryMonitorPage {...grantProps} />)
    fireEvent.click(await screen.findByRole('button', { name: /^Sipariş kuyruğu — edit$/i }))
    const dlg = formDialog()
    expect(dlg.querySelector('[data-slot="dbq-grant-locked-banner"]')).not.toBeNull()
    expect(dlg.querySelector('[data-slot="dbq-connection-locked"]')).not.toBeNull()
    expect(blocks.props.SqlEditor.disabled).toBe(true)
    expect(blocks.props.RuleBuilder.disabled).toBe(true)
    expect(within(dlg).queryByRole('button', { name: /^Try the query$/ })).toBeNull()
    expect(within(dlg).queryByRole('button', { name: /^Check the cost$/ })).toBeNull()
    expect(within(dlg).queryByRole('button', { name: 'pick-template' })).toBeNull()
    expect(dlg.querySelector('[data-slot="dbq-save-template"]')).toBeNull()
    expect(fieldOf('slow_query_ms').querySelector('input')).toBeDisabled()
    expect(fieldOf('query_timeout_seconds').querySelector('input')).toBeDisabled()
    expect(fieldOf('max_rows').querySelector('input')).toBeDisabled()
    expect(fieldOf('mail_subject_template').querySelector('input')).toBeDisabled()
    expect(dlg.querySelectorAll('[data-slot="dbq-grant-locked"]').length).toBeGreaterThanOrEqual(4)
    // Ad / amaç / zamanlama açık
    expect(within(dlg).getByLabelText(/^Name/)).not.toBeDisabled()
    expect(blocks.props.ScheduleEditor.disabled).toBeFalsy()
  })

  it('yeni izleme: paylaşım takımı seçenekte YOK; bağlantı / şablon yöneticisinde de yok', async () => {
    render(<DbQueryMonitorPage {...grantProps} />)
    await waitFor(() => expect(titles()).toHaveLength(1))
    fireEvent.click(screen.getByRole('button', { name: /^Connections$/ }))
    await waitFor(() => expect(blocks.props.ConnectionsManager?.open).toBe(true))
    expect(blocks.props.ConnectionsManager.teams).toEqual([{ id: 5, name: 'SY-A' }])
    fireEvent.click(screen.getByRole('button', { name: /^New Monitor$/ }))
    const teamField = fieldOf('team_id')
    fireEvent.mouseDown(within(teamField).getByRole('combobox'))
    const opts = [...document.querySelectorAll('[role="option"]')].map((o) => o.textContent)
    expect(opts.some((o) => o.includes('SY-A'))).toBe(true)
    expect(opts.some((o) => o.includes('Paylaşan Takım'))).toBe(false)
  })

  it('paylaşımlı kaydın kopyası paylaşan takıma GİTMEZ: takım kendi varsayılanına, bağlantı boşa döner', async () => {
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [row({ team_id: 8, team_name: 'Paylaşan Takım', routing_locked: true, shared_access: true })] })
    render(<DbQueryMonitorPage {...grantProps} />)
    fireEvent.click(await screen.findByRole('button', { name: /^Sipariş kuyruğu — duplicate$/i }))
    await waitFor(() => expect(blocks.props.ConnectionPicker).toBeTruthy())
    expect(blocks.props.ConnectionPicker.teamId).toBe(5)
    expect(blocks.props.ConnectionPicker.value).toBeNull()
  })

  it('sunucu 403 DBQUERY_GRANT_TEAM → metin TAKIM alanının altında', async () => {
    api.monitoring.createDbQueryMonitor.mockResolvedValue({ success: false, code: 'DBQUERY_GRANT_TEAM',
      error: 'Paylaşımla erişilen takımda veritabanı sorgu izlemesi oluşturulamaz — bağlantılar takımın kendi belgesidir.' })
    render(<DbQueryMonitorPage {...props} systemRole="USER" />)
    await waitFor(() => expect(titles()).toHaveLength(1))
    fireEvent.click(screen.getByRole('button', { name: /^New Monitor$/ }))
    const dlg = formDialog()
    fireEvent.change(within(dlg).getByLabelText(/^Name/), { target: { value: 'Yeni sorgu' } })
    await fillGroupAndTags({ group: 'Kurumsal', tag: 'prod', root: dlg })
    fireEvent.change(within(dlg).getByLabelText(/^What is this alert for\?/), { target: { value: 'Bekleyen sipariş sayısı' } })
    fireEvent.click(within(dlg).getByRole('button', { name: 'pick-connection' }))
    fireEvent.change(within(dlg).getByLabelText('sql'), { target: { value: 'SELECT 1' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(fieldOf('team_id').textContent).toContain('bağlantılar takımın kendi belgesidir'))
  })
})
