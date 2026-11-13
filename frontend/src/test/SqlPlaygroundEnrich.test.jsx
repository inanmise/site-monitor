import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))
const perm = vi.hoisted(() => ({ monitoringEdit: true }))
vi.mock('../contexts/PermissionsProvider.jsx', async (orig) => {
  const m = await orig()
  return {
    ...m,
    usePermissions: () => ({
      perms: {}, scope: null, loaded: true, failed: false, refresh: () => {},
      canView: () => true, canExecute: () => true,
      canEdit: (r) => (r === 'monitoring.crud' ? perm.monitoringEdit : true),
    }),
  }
})
const nav = vi.hoisted(() => ({ navigateTo: vi.fn() }))
vi.mock('../utils/navigate.js', () => ({ navigateTo: nav.navigateTo, default: nav.navigateTo, applyTabView: vi.fn(), APPLY_VIEW_EVENT: 'sm:apply-view' }))

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  api: withApiFallback({
    admin: {
      sqlListTables: vi.fn(), sqlSamples: vi.fn(), sqlHistory: vi.fn(), sqlListColumns: vi.fn(), sqlExecute: vi.fn(),
      sqlTableDetails: vi.fn(), sqlRelations: vi.fn(), getDatabaseInfo: vi.fn(),
      sqlCancel: vi.fn(), sqlExplain: vi.fn(), sqlExport: vi.fn(), sqlHistoryPage: vi.fn(), sqlSavedList: vi.fn(),
      sqlSavedGet: vi.fn(), sqlSavedCreate: vi.fn(), sqlSavedUpdate: vi.fn(), sqlSavedDelete: vi.fn(), sqlDictionary: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import SqlPlayground from '../components/admin/SqlPlayground.jsx'
import { DBQ_DRAFT_KEY } from '../utils/sqlDbQueryDraft.js'
import { OWNER_KEY, personalKey } from '../utils/personalStorage.js'
import { TABS_KEY } from '../components/admin/sql/sqlTabs.js'

/**
 * SQL Playground zenginleştirme (2026-11-13) — bileşen davranışları: parametre formu (B4), durdur (A2), maskeli kolon +
 * hassas kolon reddi (A1), Excel + sunucuda tam dışa aktarım (A3 / C3), plan (C1), grafik (C2), karşılaştırma (C4),
 * geçmiş paneli (A4), kayıtlı sorgular + bağlantı (D1), kategorili örnekler (D2), sözlük (D3), izlemeye aktarım (D4),
 * sekmeler + taslak (B3), otomatik tamamlama + biçimlendir (B1 / B2). Eski sunucu davranışları (404 / 405 / alan yok).
 */
const ROWS = [
  { id: 1, team: 'A', n: 3, secret_hash: '••••' },
  { id: 2, team: 'B', n: 5, secret_hash: '••••' },
]

function editor() { return screen.getByRole('textbox', { name: /SQL query|SQL sorgusu/ }) }
async function typeSql(sql) {
  const ed = await screen.findByRole('textbox', { name: /SQL query|SQL sorgusu/ })
  fireEvent.change(ed, { target: { value: sql } })
  return ed
}
function ctrlEnter() { fireEvent.keyDown(editor(), { key: 'Enter', ctrlKey: true }) }
function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}
async function openMore() {
  const card = document.querySelector('[data-slot="sql-editor-card"]')
  pressMenuTrigger(within(card).getByRole('button', { name: /^(More|Diğer)/ }))
  return screen.findByRole('menu')
}
const resultRegion = () => screen.findByRole('region', { name: /^(Results|Sonuçlar)$/ })

describe('SqlPlayground zenginleştirme', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    sessionStorage.clear()
    localStorage.setItem(OWNER_KEY, 'admin')
    mobile.on = false
    perm.monitoringEdit = true
    api.admin.sqlListTables.mockResolvedValue({ success: true, data: [{ table_name: 'alerts', live_rows: 10 }, { table_name: 'teams', live_rows: 4 }] })
    api.admin.sqlSamples.mockResolvedValue({ success: true, data: [] })
    api.admin.sqlHistory.mockResolvedValue({ success: true, data: [] })
    api.admin.sqlListColumns.mockResolvedValue({ success: true, data: [{ column_name: 'id', data_type: 'bigint' }, { column_name: 'name', data_type: 'text' }] })
    api.admin.sqlRelations.mockResolvedValue({ success: true, data: { tables: [], edges: [] } })
    api.admin.getDatabaseInfo.mockResolvedValue({ success: false })
    api.admin.sqlDictionary.mockResolvedValue({ success: false, status: 404 })
    api.admin.sqlExecute.mockResolvedValue({ success: true, ok: true, rowCount: 2, durationMs: 4, rows: ROWS, masked_columns: [] })
  })
  afterEach(() => { vi.restoreAllMocks() })

  it('B4 parametreler: form adları sorgudan okur; eksik değerle çalışmaz; doldurulunca türleriyle gider; sunucu alan hatası alanın altında', async () => {
    render(<SqlPlayground />)
    await typeSql('SELECT * FROM alerts WHERE id = :id AND d > :start_date')
    const form = await screen.findByRole('region', { name: /^(Parameters|Parametreler)$/ })
    expect([...form.querySelectorAll('[data-param]')].map((r) => r.getAttribute('data-param'))).toEqual(['id', 'start_date'])
    expect(within(form).getByRole('combobox', { name: /Type of parameter :id|:id parametresinin türü/ })).toHaveValue('number')
    expect(within(form).getByRole('combobox', { name: /Type of parameter :start_date|:start_date parametresinin türü/ })).toHaveValue('date')

    ctrlEnter()
    expect(await within(form).findAllByText(/Enter a value for this parameter|Bu parametreye bir değer girin/)).toHaveLength(2)
    expect(api.admin.sqlExecute).not.toHaveBeenCalled()

    fireEvent.change(form.querySelector('[data-param="id"] input'), { target: { value: '5' } })
    fireEvent.change(form.querySelector('[data-param="start_date"] input'), { target: { value: '2026-11-13' } })
    await waitFor(() => expect(within(form).queryAllByText(/Enter a value for this parameter|Bu parametreye bir değer girin/)).toHaveLength(0))

    api.admin.sqlExecute.mockResolvedValueOnce({ success: false, code: 'SQL_PARAM_INVALID', field: 'params.id', error: 'The ":id" parameter must be a number.' })
    ctrlEnter()
    await waitFor(() => expect(api.admin.sqlExecute).toHaveBeenCalledTimes(1))
    const [sql, opts] = api.admin.sqlExecute.mock.calls[0]
    expect(sql).toBe('SELECT * FROM alerts WHERE id = :id AND d > :start_date')
    expect(opts.runId).toMatch(/^[A-Za-z0-9-]{8,64}$/)
    expect(opts.params).toEqual({ id: { value: '5', type: 'number' }, start_date: { value: '2026-11-13', type: 'date' } })
    expect(await within(form).findByText('The ":id" parameter must be a number.')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="sql-error-param"]')).not.toBeNull()
  })

  it('A2 durdur: çalışırken Çalıştır → Durdur; /cancel aynı run_id ile; iptal yanıtı "durduruldu" görünümü', async () => {
    const d = deferred()
    api.admin.sqlExecute.mockReturnValueOnce(d.promise)
    api.admin.sqlCancel.mockResolvedValue({ success: true, cancelled: true })
    render(<SqlPlayground />)
    await typeSql('SELECT pg_sleep(10)')
    ctrlEnter()
    const stopBtn = await waitFor(() => { const b = document.querySelector('[data-slot="sql-stop"]'); expect(b).not.toBeNull(); return b })
    expect(stopBtn).toHaveAccessibleName(/^(Stop|Durdur)$/)
    fireEvent.click(stopBtn)
    await waitFor(() => expect(api.admin.sqlCancel).toHaveBeenCalledTimes(1))
    expect(api.admin.sqlCancel.mock.calls[0][0]).toBe(api.admin.sqlExecute.mock.calls[0][1].runId)
    expect(await screen.findByText(/Stop request sent|Durdurma isteği gönderildi/)).toBeInTheDocument()
    await act(async () => { d.resolve({ success: true, ok: false, cancelled: true, error: 'Sorgu durduruldu', rows: [] }) })
    const region = await resultRegion()
    expect(await within(region).findByText(/The query was stopped|Sorgu durduruldu/, { selector: '[data-slot="sql-result-cancelled"] *' })).toBeInTheDocument()
    expect(region.querySelector('[data-slot="sql-cancelled-badge"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="sql-stop"]')).toBeNull()
  })

  it('A2 eski sunucu: /cancel 404 → "bu sürüm durduramaz", Durdur yeniden basılabilir', async () => {
    const d = deferred()
    api.admin.sqlExecute.mockReturnValueOnce(d.promise)
    api.admin.sqlCancel.mockResolvedValue({ success: false, status: 404, code: 'NOT_FOUND', error: 'not found' })
    render(<SqlPlayground />)
    await typeSql('SELECT 1')
    ctrlEnter()
    const stopBtn = await waitFor(() => { const b = document.querySelector('[data-slot="sql-stop"]'); expect(b).not.toBeNull(); return b })
    fireEvent.click(stopBtn)
    expect(await screen.findByText(/can’t stop queries|sorguları durduramıyor/)).toBeInTheDocument()
    await waitFor(() => expect(document.querySelector('[data-slot="sql-stop"]')).not.toBeDisabled())
    await act(async () => { d.resolve({ success: true, ok: true, rows: [], rowCount: 0 }) })
  })

  it('A1 maskeli kolonlar: başlıkta "maskeli", hücre kilit + sr metni, rozet + not; hassas kolon reddi ayrı bant', async () => {
    api.admin.sqlExecute.mockResolvedValueOnce({ success: true, ok: true, rowCount: 2, durationMs: 4, rows: ROWS, masked_columns: ['secret_hash'] })
    render(<SqlPlayground />)
    await typeSql('SELECT * FROM app_users')
    ctrlEnter()
    const region = await resultRegion()
    await waitFor(() => expect(region.querySelector('[data-slot="sql-masked-badge"]')).not.toBeNull())
    expect(region.querySelector('[data-slot="sql-masked-badge"]')).toHaveTextContent(/1 masked column|1 maskeli kolon/)
    expect(region.querySelector('[data-slot="sql-masked-note"]')).toHaveTextContent('secret_hash')
    expect(region.querySelectorAll('[data-slot="sql-masked-cell"]')).toHaveLength(2)
    expect(within(region).getAllByText(/^(masked value|maskeli değer)$/)).toHaveLength(2)
    expect(region.querySelector('[data-slot="sql-masked-head"]')).not.toBeNull()

    api.admin.sqlExecute.mockResolvedValueOnce({ success: false, code: 'SQL_SENSITIVE_COLUMN', error: 'app_users.password_hash cannot be used in WHERE' })
    await typeSql('SELECT id FROM app_users WHERE password_hash = \'x\'')
    ctrlEnter()
    const sens = await waitFor(() => { const e = document.querySelector('[data-slot="sql-error-sensitive"]'); expect(e).not.toBeNull(); return e })
    expect(sens).toHaveTextContent('app_users.password_hash cannot be used in WHERE')
  })

  it('A3 Excel: ekran sonucu .xlsx olarak utils/csvExport.downloadBlob ile iner (DOM bağlantısı)', async () => {
    const created = []
    URL.createObjectURL = vi.fn((blob) => { created.push(blob); return 'blob:x' })
    URL.revokeObjectURL = vi.fn()
    render(<SqlPlayground />)
    await typeSql('SELECT * FROM t')
    ctrlEnter()
    const region = await resultRegion()
    await within(region).findByText(/2 rows|2 satır/)
    pressMenuTrigger(within(region).getByRole('button', { name: /^(Export|Dışa aktar)$/ }))
    fireEvent.click(await waitFor(() => { const m = document.querySelector('[data-slot="sql-export-xlsx"]'); expect(m).not.toBeNull(); return m }))
    await waitFor(() => expect(created).toHaveLength(1))
    expect(created[0].type).toMatch(/spreadsheetml/)
    const head = new Uint8Array(await created[0].arrayBuffer()).slice(0, 2)
    expect(String.fromCharCode(...head)).toBe('PK')
  })

  it('C3 tam dışa aktarım: CSV sunucuya gider (aynı sorgu + parametre), Excel JSON\'dan; kesilme notu; SQL_CANCELLED bilgi; 404 "bu sürümde yok"', async () => {
    const created = []
    URL.createObjectURL = vi.fn((blob) => { created.push(blob); return 'blob:y' })
    URL.revokeObjectURL = vi.fn()
    api.admin.sqlExport.mockResolvedValueOnce({ success: true, name: 'sql-x.csv', rows: 50000, truncated: true, maxRows: 50000, masked: [] })
    render(<SqlPlayground />)
    await typeSql('SELECT * FROM big')
    ctrlEnter()
    const region = await resultRegion()
    await within(region).findByText(/2 rows|2 satır/)

    pressMenuTrigger(within(region).getByRole('button', { name: /^(Export|Dışa aktar)$/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Full CSV|Tam CSV/ }))
    await waitFor(() => expect(api.admin.sqlExport).toHaveBeenCalledTimes(1))
    expect(api.admin.sqlExport.mock.calls[0][0]).toBe('SELECT * FROM big')
    expect(api.admin.sqlExport.mock.calls[0][1]).toMatchObject({ format: 'csv' })
    expect(await screen.findByText(/Only the first 50,000 rows|Sınır nedeniyle yalnız ilk 50[.,]000/)).toBeInTheDocument()

    api.admin.sqlExport.mockResolvedValueOnce({ success: true, json: { success: true, data: { columns: ['id', 'n'], rows: [[1, 2], [3, 4]], row_count: 2 } }, rows: 2, truncated: false })
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    pressMenuTrigger(within(region).getByRole('button', { name: /^(Export|Dışa aktar)$/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Full Excel|Tam Excel/ }))
    await waitFor(() => expect(created).toHaveLength(1))
    expect(api.admin.sqlExport.mock.calls[1][1]).toMatchObject({ format: 'json' })
    expect(await screen.findByText(/Excel downloaded \(2 rows\)|Excel indirildi \(2 satır\)/)).toBeInTheDocument()

    api.admin.sqlExport.mockResolvedValueOnce({ success: false, status: 409, code: 'SQL_CANCELLED', error: 'stopped' })
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    pressMenuTrigger(within(region).getByRole('button', { name: /^(Export|Dışa aktar)$/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Full CSV|Tam CSV/ }))
    expect(await screen.findByText(/The full export was stopped|Tam dışa aktarım durduruldu/)).toBeInTheDocument()

    api.admin.sqlExport.mockResolvedValueOnce({ success: false, status: 404, code: 'NOT_FOUND', error: 'x' })
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    pressMenuTrigger(within(region).getByRole('button', { name: /^(Export|Dışa aktar)$/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Full CSV|Tam CSV/ }))
    expect(await screen.findByText(/doesn’t support full exports|tam dışa aktarımı desteklemiyor/)).toBeInTheDocument()
  })

  it('İnceleme DÜŞÜK 5: aynı adlı kolonlar — ekranda not; tam Excel’de başlıklar ayrışır, ikinci kolonun değeri kaybolmaz', async () => {
    const created = []
    URL.createObjectURL = vi.fn((blob) => { created.push(blob); return 'blob:z' })
    URL.revokeObjectURL = vi.fn()
    api.admin.sqlExecute.mockResolvedValueOnce({ success: true, ok: true, rowCount: 1, rows: [{ id: 1, name: 'a' }], masked_columns: [] })
    api.admin.sqlExport.mockResolvedValueOnce({ success: true, rows: 1, truncated: false,
      json: { success: true, data: { columns: ['id', 'name', 'id'], rows: [[1, 'a', 7001]], row_count: 1 } } })
    render(<SqlPlayground />)
    await typeSql('SELECT a.id, a.name, b.id FROM a JOIN b ON b.a_id = a.id')
    ctrlEnter()
    const region = await resultRegion()
    const note = await waitFor(() => { const n = region.querySelector('[data-slot="sql-dup-note"]'); expect(n).not.toBeNull(); return n })
    expect(note).toHaveTextContent(/Columns with the same name: id|Aynı adlı kolonlar: id/)
    pressMenuTrigger(within(region).getByRole('button', { name: /^(Export|Dışa aktar)$/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Full Excel|Tam Excel/ }))
    await waitFor(() => expect(created).toHaveLength(1))
    const { unzipSync, strFromU8 } = await import('fflate')
    const files = unzipSync(new Uint8Array(await created[0].arrayBuffer()))
    const sheet = strFromU8(files[Object.keys(files).find((k) => k.endsWith('worksheets/sheet1.xml'))])
    expect(sheet).toContain('id_2')
    expect(sheet).toContain('7001')
    expect(await screen.findByText(/id → id_2/)).toBeInTheDocument()

    // Ayrı adlar → not yok
    api.admin.sqlExecute.mockResolvedValueOnce({ success: true, ok: true, rowCount: 1, rows: [{ a_id: 1, b_id: 2 }], masked_columns: [] })
    await typeSql('SELECT a.id AS a_id, b.id AS b_id FROM a, b')
    ctrlEnter()
    await waitFor(() => expect(api.admin.sqlExecute).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(document.querySelector('[data-slot="sql-dup-note"]')).toBeNull())
  })

  it('İnceleme D: yalnız gün değerli kolon "date", saatli "datetime" etiketi alır (İngilizce kısaltma, TİME olmaz)', async () => {
    api.admin.sqlExecute.mockResolvedValueOnce({ success: true, ok: true, rowCount: 2, masked_columns: [],
      rows: [{ day: '2026-11-13', at: '2026-11-13T09:30:00.123' }, { day: '2026-11-14', at: '2026-11-14T06:00:00Z' }] })
    render(<SqlPlayground />)
    await typeSql('SELECT day, at FROM t')
    ctrlEnter()
    const region = await resultRegion()
    const dayHead = await within(region).findByRole('button', { name: /Sort by day|day kolonuna göre/ })
    const atHead = within(region).getByRole('button', { name: /Sort by at|at kolonuna göre/ })
    expect(dayHead.querySelector('[lang="en"]')).toHaveTextContent(/^date$/)
    expect(atHead.querySelector('[lang="en"]')).toHaveTextContent(/^datetime$/)
  })

  it('İnceleme A: düzenleyici araçları telefonda iki sütunlu ızgarada; örnek seçici etiketi kırpılmaz', async () => {
    mobile.on = true
    render(<SqlPlayground />)
    await screen.findByRole('textbox', { name: /SQL query|SQL sorgusu/ })
    const actions = document.querySelector('[data-slot="sql-editor-actions"]')
    expect(actions.className.split(/\s+/)).toContain('grid-cols-2')
    const picker = within(actions).getByRole('button', { name: /^(Sample Queries|Örnek Sorgular)/ })
    expect(picker.querySelector('span').className.split(/\s+/)).not.toContain('truncate')
    // İnceleme B: açılır pencere görünüm alanına sığar (Radix'in kalan yüksekliği sınır, liste içeride kayar)
    fireEvent.click(picker)
    const pop = await waitFor(() => { const p = document.querySelector('[data-slot="sql-samples"]'); expect(p).not.toBeNull(); return p })
    expect(pop.className).toContain('max-h-(--radix-popover-content-available-height)')
  })

  it('C1 plan: menüden EXPLAIN → özet + ağaç + sıralı tarama uyarısı; 200 + ok:false hata; 409 SQL_EXPLAIN_UNSUPPORTED nötr', async () => {
    api.admin.sqlExplain.mockResolvedValueOnce({ success: true, ok: true, analyzed: false, plan: [{ Plan: {
      'Node Type': 'Seq Scan', 'Relation Name': 'alerts', 'Total Cost': 12.5, 'Plan Rows': 40, 'Startup Cost': 0,
    } }], summary: { total_cost: 12.5, plan_rows: 40, nodes: 1, seq_scans: [{ table: 'alerts', rows: 40 }], top_nodes: [] } })
    render(<SqlPlayground />)
    await typeSql('SELECT * FROM alerts')
    const menu = await openMore()
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Show plan \(EXPLAIN\)|Planı göster/ }))
    await waitFor(() => expect(api.admin.sqlExplain).toHaveBeenCalledTimes(1))
    expect(api.admin.sqlExplain.mock.calls[0][1]).toMatchObject({ analyze: false })
    const plan = await waitFor(() => { const p = document.querySelector('[data-slot="sql-plan"]'); expect(p).not.toBeNull(); return p })
    expect(plan.querySelector('[data-slot="sql-plan-kind"]')).toHaveTextContent(/Estimated plan|Tahmini plan/)
    expect(plan.querySelector('[data-slot="sql-plan-seq"]')).toHaveTextContent('alerts')
    expect(plan.querySelector('[data-slot="sql-plan-tree"]')).toHaveTextContent('Seq Scan')

    api.admin.sqlExplain.mockResolvedValueOnce({ success: true, ok: false, error: 'ERROR: relation "nope" does not exist' })
    fireEvent.click(plan.querySelector('[data-slot="sql-plan-run"]'))
    expect(await screen.findByText('ERROR: relation "nope" does not exist')).toBeInTheDocument()

    api.admin.sqlExplain.mockResolvedValueOnce({ success: false, status: 409, code: 'SQL_EXPLAIN_UNSUPPORTED', error: 'Only PostgreSQL.' })
    fireEvent.click(await screen.findByRole('button', { name: /Show plan \(EXPLAIN\)|Planı göster/ }))
    const un = await waitFor(() => { const u = document.querySelector('[data-slot="sql-plan-unsupported"]'); expect(u).not.toBeNull(); return u })
    expect(un).toHaveTextContent('Only PostgreSQL.')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('C1 durdurulan plan isteği (200 + ok:false + cancelled) "durduruldu" der, hata bandı değil; eski sunucu 404 "bu sürümde yok"', async () => {
    api.admin.sqlExplain.mockResolvedValueOnce({ success: true, ok: false, cancelled: true, error: 'Sorgu durduruldu' })
    render(<SqlPlayground />)
    await typeSql('SELECT * FROM alerts')
    let menu = await openMore()
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Show plan \(EXPLAIN\)|Planı göster/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="sql-plan-cancelled"]')).not.toBeNull())
    expect(screen.queryByRole('alert')).toBeNull()

    api.admin.sqlExplain.mockResolvedValueOnce({ success: false, status: 404, code: 'NOT_FOUND', error: 'x' })
    menu = await openMore()
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Show plan \(EXPLAIN\)|Planı göster/ }))
    const un = await waitFor(() => { const u = document.querySelector('[data-slot="sql-plan-unsupported"]'); expect(u).not.toBeNull(); return u })
    expect(un).toHaveTextContent(/doesn’t support query plans|sorgu planlarını desteklemiyor/)
  })

  it('C1 ANALYZE önce onay ister; vazgeçince istek gitmez', async () => {
    render(<SqlPlayground />)
    await typeSql('SELECT * FROM alerts')
    const menu = await openMore()
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Measure plan \(ANALYZE\)|Planı ölç/ }))
    const dlg = await screen.findByRole('dialog', { name: /Run the query?|Sorgu çalıştırılsın mı/ })
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Cancel|Vazgeç|İptal)$/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.admin.sqlExplain).not.toHaveBeenCalled()
  })

  it('C2 grafik ve C4 karşılaştırma: görünüm seçicisi; iki çalıştırma sonra değişen / eklenen satırlar', async () => {
    api.admin.sqlExecute
      .mockResolvedValueOnce({ success: true, ok: true, rowCount: 2, rows: [{ id: 1, team: 'A', n: 3 }, { id: 2, team: 'B', n: 5 }], masked_columns: [] })
      .mockResolvedValueOnce({ success: true, ok: true, rowCount: 2, rows: [{ id: 1, team: 'A', n: 4 }, { id: 3, team: 'C', n: 1 }], masked_columns: [] })
    render(<SqlPlayground />)
    await typeSql('SELECT id, team, n FROM t')
    ctrlEnter()
    const region = await resultRegion()
    await within(region).findByText(/2 rows|2 satır/)
    const viewGroup = within(region).getByRole('group', { name: /Result view|Sonuç görünümü/ })
    fireEvent.click(within(viewGroup).getByRole('button', { name: /^(Chart|Grafik)$/ }))
    const chart = await waitFor(() => { const c = document.querySelector('[data-slot="sql-chart"]'); expect(c).not.toBeNull(); return c }, { timeout: 8000 })
    expect(within(chart).getByRole('group', { name: /Chart type|Grafik türü/ })).toBeInTheDocument()

    fireEvent.click(within(viewGroup).getByRole('button', { name: /^(Compare|Karşılaştır)$/ }))
    expect(document.querySelector('[data-slot="sql-compare-empty"]')).not.toBeNull()

    ctrlEnter()
    await waitFor(() => expect(api.admin.sqlExecute).toHaveBeenCalledTimes(2))
    fireEvent.click(within(viewGroup).getByRole('button', { name: /^(Compare|Karşılaştır)$/ }))
    const counts = await waitFor(() => { const c = document.querySelector('[data-slot="sql-compare-counts"]'); expect(c).not.toBeNull(); return c })
    expect(counts).toHaveTextContent(/\+1 added|\+1 eklendi/)
    expect(counts).toHaveTextContent(/−1 removed|−1 silindi/)
    expect(counts).toHaveTextContent(/1 changed|1 değişti/)
  })

  it('A4 geçmiş paneli: sayfalı arama + durum; eski sunucu (total yok) not + yerel süzgeç; satırdan yeni sekme', async () => {
    api.admin.sqlHistoryPage.mockResolvedValue({ success: true, total: 30, page: 0, size: 25, slow_ms: 5000, data: [
      { id: 9, sql_text: 'SELECT slow()', success: true, row_count: 1, duration_ms: 7000, executed_at: '2026-11-13T09:00:00', executed_by: 'ali' },
      { id: 8, sql_text: 'SELECT bad', success: false, error_message: 'boom', executed_at: '2026-11-13T08:00:00' },
    ] })
    render(<SqlPlayground />)
    await screen.findByText('alerts')
    const menu = await openMore()
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Full history|Tüm geçmiş/ }))
    const panel = await waitFor(() => { const p = document.querySelector('[data-slot="sql-history-panel"]'); expect(p).not.toBeNull(); return p })
    const list = await within(panel).findByRole('list', { name: /Past queries|Geçmiş sorgular/ })
    const items = list.querySelectorAll('[data-slot="sql-history-item"]')
    expect([...items].map((i) => i.getAttribute('data-state'))).toEqual(['slow', 'error'])
    // Standart sunucu sayfalaması (useServerPagination, pencere ön ayarı 10) + PaginationBar
    const pager = panel.querySelector('[data-slot="sql-history-pager"] [data-slot="pagination-bar"]')
    expect(pager).toHaveTextContent(/1–10 of 30|1–10 \/ 30/)
    expect(api.admin.sqlHistoryPage.mock.calls[0][0]).toBe('scope=mine&page=0&size=10')
    fireEvent.click(within(pager).getByRole('button', { name: /^(Next|Sonraki)/ }))
    await waitFor(() => expect(api.admin.sqlHistoryPage.mock.calls.at(-1)[0]).toBe('scope=mine&page=1&size=10'))

    fireEvent.click(within(within(panel).getByRole('group', { name: /Filter by status|Duruma göre süz/ })).getByRole('button', { name: /^(Failed|Başarısız)$/ }))
    // Süzgeç değişince sayfa 1'e döner (render sırasında; mount'ta sıfırlama yok)
    await waitFor(() => expect(api.admin.sqlHistoryPage.mock.calls.at(-1)[0]).toBe('status=error&scope=mine&page=0&size=10'))

    fireEvent.click(within(items[0]).getByRole('button', { name: /Open in a new tab|Yeni sekmede aç/ }))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="sql-tab"]')).toHaveLength(2))
    expect(editor().value).toBe('SELECT slow()')
  })

  it('A4 eski sunucu: sayfalı yanıt yok → not + kapsam / tarih gizli', async () => {
    api.admin.sqlHistoryPage.mockResolvedValue({ success: true, data: [
      { id: 1, sql_text: 'SELECT a', success: true }, { id: 2, sql_text: 'SELECT b', success: false },
    ] })
    render(<SqlPlayground />)
    await screen.findByText('alerts')
    const menu = await openMore()
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Full history|Tüm geçmiş/ }))
    const panel = await waitFor(() => { const p = document.querySelector('[data-slot="sql-history-panel"]'); expect(p).not.toBeNull(); return p })
    expect(await within(panel).findByText(/only your last 50 queries|yalnız son 50 sorgunuzu/)).toBeInTheDocument()
    expect(within(panel).queryByRole('group', { name: /^(Scope|Kapsam)$/ })).toBeNull()
    expect(panel.querySelectorAll('[data-slot="sql-history-item"]')).toHaveLength(2)
  })

  it('D1 kaydet: yeni kayıt gövdesi (parametre TÜRLERİ, değer yok); sonra yalnız değişen alanlar + version; 409 çakışma bandı', async () => {
    api.admin.sqlSavedCreate.mockResolvedValue({ success: true, data: { id: 41, title: 'Açık alarmlar', sql_text: 'SELECT :id', version: 0, mine: true } })
    render(<SqlPlayground />)
    await typeSql('SELECT :id')
    const form = await screen.findByRole('region', { name: /^(Parameters|Parametreler)$/ })
    fireEvent.change(form.querySelector('[data-param="id"] input'), { target: { value: '99' } })
    fireEvent.click(document.querySelector('[data-slot="sql-save"]'))
    const dlg = await screen.findByRole('dialog', { name: /Save query|Sorguyu kaydet/ })
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Save|Kaydet)$/ }))
    expect(await within(dlg).findByText(/Enter a title|Bir başlık girin/)).toBeInTheDocument()
    expect(api.admin.sqlSavedCreate).not.toHaveBeenCalled()
    fireEvent.change(within(dlg).getByRole('textbox', { name: /^(Title|Başlık)/ }), { target: { value: 'Açık alarmlar' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Save|Kaydet)$/ }))
    await waitFor(() => expect(api.admin.sqlSavedCreate).toHaveBeenCalledTimes(1))
    expect(api.admin.sqlSavedCreate.mock.calls[0][0]).toEqual({ title: 'Açık alarmlar', description: '', tags: [], shared: false, sql_text: 'SELECT :id', params: { id: 'number' } })
    expect(JSON.stringify(api.admin.sqlSavedCreate.mock.calls[0][0])).not.toContain('99')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: /Save query|Sorguyu kaydet/ })).toBeNull())
    expect(document.querySelector('[data-slot="sql-tab"][data-active="true"]')).toHaveTextContent('Açık alarmlar')

    // Güncelle: sunucudan taze kayıt okunur; değişmeyen başlık gönderilmez, SQL değişti → sql_text + params + version
    api.admin.sqlSavedGet.mockResolvedValue({ success: true, data: { id: 41, title: 'Açık alarmlar', description: '', tags: [], shared: false, sql_text: 'SELECT :id', version: 3, mine: true } })
    api.admin.sqlSavedUpdate.mockResolvedValueOnce({ success: false, status: 409, code: 'CONCURRENT_MODIFICATION', error: 'changed' })
    await typeSql('SELECT :id, 2')
    fireEvent.click(document.querySelector('[data-slot="sql-save"]'))
    const dlg2 = await screen.findByRole('dialog', { name: /Edit saved query|Kayıtlı sorguyu düzenle/ })
    fireEvent.click(within(dlg2).getByRole('button', { name: /Save changes|Değişiklikleri kaydet/ }))
    await waitFor(() => expect(api.admin.sqlSavedUpdate).toHaveBeenCalledTimes(1))
    expect(api.admin.sqlSavedUpdate.mock.calls[0]).toEqual([41, { sql_text: 'SELECT :id, 2', params: { id: 'number' }, version: 3 }])
    const banner = await waitFor(() => { const b = dlg2.querySelector('[data-slot="sql-save-banner"]'); expect(b).not.toBeNull(); return b })
    expect(banner).toHaveTextContent(/changed while you were editing|siz düzenlerken değişti/)
    expect(within(banner).getByRole('button', { name: /Load latest version|Son sürümü yükle/ })).toBeInTheDocument()
  })

  it('D1 kayıtlı sorgular paneli: liste, yeni sekmede açma, silme onayı; liste 404 → "bu sürümde yok" ve düğmeler gizlenir', async () => {
    api.admin.sqlSavedList.mockResolvedValueOnce({ success: true, total: 1, data: [
      { id: 7, title: 'Takım listesi', description: 'tüm takımlar', tags: ['ekip'], sql_text: 'SELECT * FROM teams', shared: true, owner_name: 'Ali', mine: false, can_edit: false, version: 1 },
    ] })
    render(<SqlPlayground />)
    await screen.findByText('alerts')
    fireEvent.click(document.querySelector('[data-slot="sql-saved-open"]'))
    const panel = await waitFor(() => { const p = document.querySelector('[data-slot="sql-saved-panel"]'); expect(p).not.toBeNull(); return p })
    const item = await waitFor(() => { const i = panel.querySelector('[data-slot="sql-saved-item"]'); expect(i).not.toBeNull(); return i })
    expect(item).toHaveTextContent('Takım listesi')
    expect(within(item).queryByRole('button', { name: /^(Delete|Sil)$/ })).toBeNull()   // sahibi değil
    fireEvent.click(within(item).getByRole('button', { name: /^(Open|Aç)$/ }))
    await waitFor(() => expect(editor().value).toBe('SELECT * FROM teams'))
    expect(document.querySelector('[data-slot="sql-tab"][data-active="true"]')).toHaveTextContent('Takım listesi')

    api.admin.sqlSavedList.mockResolvedValueOnce({ success: false, status: 404, code: 'NOT_FOUND', error: 'x' })
    fireEvent.click(document.querySelector('[data-slot="sql-saved-open"]'))
    expect(await screen.findByText(/doesn’t support saved queries|kayıtlı sorguları desteklemiyor/)).toBeInTheDocument()
    await waitFor(() => expect(document.querySelector('[data-slot="sql-saved-open"]')).toBeNull())
    expect(document.querySelector('[data-slot="sql-save"]')).toBeNull()
    expect(api.admin.sqlSavedList).toHaveBeenCalledTimes(2)   // tek okuma (yeniden okuma döngüsü yok)
  })

  it('D1 bağlantı: ?sq=<id> kayıtlı sorguyu yeni sekmede açar ve adresten siler; bulunamazsa uyarı', async () => {
    window.history.replaceState({}, '', '/?tab=sqlplayground&sq=12')
    api.admin.sqlSavedGet.mockResolvedValueOnce({ success: true, data: { id: 12, title: 'Bağlantılı', sql_text: 'SELECT 12', params: { x: { type: 'number' } }, version: 2, mine: true } })
    const { unmount } = render(<SqlPlayground />)
    await waitFor(() => expect(editor().value).toBe('SELECT 12'))
    expect(api.admin.sqlSavedGet).toHaveBeenCalledWith('12')
    expect(window.location.search).not.toContain('sq=')
    unmount()

    window.history.replaceState({}, '', '/?tab=sqlplayground&sq=13')
    api.admin.sqlSavedGet.mockResolvedValueOnce({ success: false, status: 404, code: 'NOT_FOUND', error: 'x' })
    render(<SqlPlayground />)
    expect(await screen.findByText(/wasn’t found or isn’t shared|bulunamadı ya da sizinle paylaşılmamış/)).toBeInTheDocument()
  })

  it('D2 örnekler kategoriye göre gruplanır, açıklama altında; eski sunucu tek grup', async () => {
    api.admin.sqlSamples.mockResolvedValueOnce({ success: true, data: [
      { label: 'Açık alarmlar', sql: 'SELECT 1', category: 'alerts', description: 'Şu an açık olan alarmlar' },
      { label: 'Takımlar', sql: 'SELECT 2', category: 'teams', description: 'Takım listesi' },
      { label: 'Bilinmeyen', sql: 'SELECT 3', category: 'zzz', category_label: 'Sunucu etiketi' },
    ] })
    render(<SqlPlayground />)
    await screen.findByText('alerts')
    fireEvent.click(screen.getByRole('button', { name: /^(Sample Queries|Örnek Sorgular) \(3\)$/ }))
    await screen.findByRole('option', { name: /Açık alarmlar/ })
    const groups = [...document.querySelectorAll('[data-slot="sql-sample-group"]')].map((g) => g.getAttribute('data-category'))
    expect(groups).toEqual(['alerts', 'teams', 'zzz'])
    expect(screen.getByText(/^(Alerts|Alarmlar)$/)).toBeInTheDocument()
    expect(screen.getByText('Sunucu etiketi')).toBeInTheDocument()
    expect(screen.getByText('Şu an açık olan alarmlar').closest('[data-slot="sql-sample-desc"]')).not.toBeNull()
  })

  it('D3 sözlük: gezginde tablo / kolon açıklaması, tablo ayrıntısında açıklama satırı', async () => {
    api.admin.sqlDictionary.mockResolvedValueOnce({ success: true, tables: { alerts: { description: 'Alarm kayıtları', columns: { id: 'Alarm numarası' } } } })
    api.admin.sqlTableDetails.mockResolvedValue({ success: true, data: { columns: [{ column_name: 'id', data_type: 'bigint', is_nullable: 'NO', is_pk: true }], constraints: [], indexes: [], triggers: [] } })
    render(<SqlPlayground />)
    const list = await screen.findByRole('list', { name: /^(Tables|Tablolar)$/ })
    expect(await within(list).findByText('Alarm kayıtları')).toBeInTheDocument()
    fireEvent.click(within(list).getByRole('button', { name: /alerts — (show or hide columns|kolonları göster)/ }))
    expect(await within(list).findByText('Alarm numarası')).toBeInTheDocument()
    fireEvent.click(within(list).getByRole('button', { name: /alerts — (Table details|Tablo detayları)/ }))
    const dlg = await screen.findByRole('dialog', { name: /alerts — (schema details|şema detayları)/ })
    expect(await waitFor(() => { const d = dlg.querySelector('[data-slot="sql-td-dict"]'); expect(d).not.toBeNull(); return d })).toHaveTextContent('Alarm kayıtları')
  })

  it('D4 izleme oluştur: taslak oturum deposuna (kişisel) yazılır ve dbquery açılır; parametreli sorgu aktarılmaz; izin yoksa öğe yok', async () => {
    const { unmount } = render(<SqlPlayground />)
    await typeSql('SELECT count(*) FROM alerts')
    let menu = await openMore()
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Create a database query monitor|Veritabanı sorgu izlemesi oluştur/ }))
    await waitFor(() => expect(nav.navigateTo).toHaveBeenCalledWith('dbquery'))
    expect(sessionStorage.getItem(personalKey(DBQ_DRAFT_KEY, 'admin'))).toContain('SELECT count(*) FROM alerts')

    await typeSql('SELECT * FROM alerts WHERE id = :id')
    nav.navigateTo.mockClear()
    menu = await openMore()
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Create a database query monitor|Veritabanı sorgu izlemesi oluştur/ }))
    expect(await screen.findByText(/with :name parameters can’t be turned|:ad parametreli sorgu izlemeye/)).toBeInTheDocument()
    expect(nav.navigateTo).not.toHaveBeenCalled()
    unmount()

    perm.monitoringEdit = false
    render(<SqlPlayground />)
    await typeSql('SELECT 1')
    menu = await openMore()
    expect(within(menu).queryByRole('menuitem', { name: /Create a database query monitor|Veritabanı sorgu izlemesi oluştur/ })).toBeNull()
  })

  it('B3 sekmeler: ekle / kapat (onaylı) / yeniden adlandır; taslak kişisel anahtara yazılır ve yeniden açılınca gelir', async () => {
    const { unmount } = render(<SqlPlayground />)
    await typeSql('SELECT 1')
    fireEvent.click(document.querySelector('[data-slot="sql-tab-add"]'))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="sql-tab"]')).toHaveLength(2))
    expect(editor().value).toBe('')
    await typeSql('SELECT 2')
    await waitFor(() => expect(localStorage.getItem(personalKey(TABS_KEY, 'admin'))).toContain('SELECT 2'), { timeout: 2000 })

    // ilk sekmeye dön → kendi metni
    fireEvent.mouseDown(screen.getByRole('tab', { name: /Query 1|Sorgu 1/ }), { button: 0 })
    await waitFor(() => expect(editor().value).toBe('SELECT 1'))

    // kapat: metin var → onay
    fireEvent.click(screen.getByRole('button', { name: /Close tab: Query 2|Sekmeyi kapat: Sorgu 2/ }))
    const dlg = await screen.findByRole('dialog', { name: /Close this tab?|Sekme kapatılsın mı/ })
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Close|Kapat)$/ }))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="sql-tab"]')).toHaveLength(1))

    // yeniden adlandır
    pressMenuTrigger(screen.getByRole('button', { name: /Tab actions|Sekme işlemleri/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /^(Rename|Yeniden adlandır)$/ }))
    const prompt = await screen.findByRole('dialog', { name: /Rename tab|Sekmeyi yeniden adlandır/ })
    fireEvent.change(within(prompt).getByRole('textbox'), { target: { value: 'Rapor sorgusu' } })
    fireEvent.click(within(prompt).getByRole('button', { name: /^(Save|Kaydet)$/ }))
    await waitFor(() => expect(screen.getByRole('tab', { name: /Rapor sorgusu/ })).toBeInTheDocument())
    await waitFor(() => expect(localStorage.getItem(personalKey(TABS_KEY, 'admin'))).toContain('Rapor sorgusu'), { timeout: 2000 })
    unmount()

    render(<SqlPlayground />)
    expect(await screen.findByRole('tab', { name: /Rapor sorgusu/ })).toBeInTheDocument()
    expect(editor().value).toBe('SELECT 1')
  })

  it('B1 tamamlama: yazınca listbox (aria-activedescendant), ok tuşu + Enter kabul eder; Escape kapatır', async () => {
    render(<SqlPlayground />)
    await screen.findByText('alerts')
    const ed = editor()
    fireEvent.change(ed, { target: { value: 'SELECT * FROM al', selectionStart: 16, selectionEnd: 16 } })
    const lb = await screen.findByRole('listbox', { name: /Suggestions|Öneriler/ })
    expect(ed).toHaveAttribute('aria-expanded', 'true')
    expect(ed.getAttribute('aria-activedescendant')).toBe(within(lb).getAllByRole('option')[0].id)
    expect(within(lb).getAllByRole('option')[0]).toHaveTextContent('alerts')
    fireEvent.keyDown(ed, { key: 'Enter' })
    await waitFor(() => expect(ed.value).toBe('SELECT * FROM alerts'))
    expect(screen.queryByRole('listbox', { name: /Suggestions|Öneriler/ })).toBeNull()

    fireEvent.change(ed, { target: { value: 'SELECT * FROM te', selectionStart: 16, selectionEnd: 16 } })
    await screen.findByRole('listbox', { name: /Suggestions|Öneriler/ })
    fireEvent.keyDown(ed, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('listbox', { name: /Suggestions|Öneriler/ })).toBeNull())
    expect(ed.value).toBe('SELECT * FROM te')
  })

  it('B2 biçimlendir: düğme ve Ctrl+Shift+F; zaten biçimliyse bilgi', async () => {
    render(<SqlPlayground />)
    await typeSql('select a, b from t where x = 1')
    fireEvent.click(document.querySelector('[data-slot="sql-format"]'))
    await waitFor(() => expect(editor().value).toBe('SELECT a,\n  b\nFROM t\nWHERE x = 1'))
    fireEvent.keyDown(editor(), { key: 'F', ctrlKey: true, shiftKey: true })
    expect(await screen.findByText(/already formatted|zaten biçimli/)).toBeInTheDocument()
  })
})
