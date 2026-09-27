import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  api: withApiFallback({
    admin: {
      sqlListTables: vi.fn(),
      sqlSamples: vi.fn(),
      sqlHistory: vi.fn(),
      sqlListColumns: vi.fn(),
      sqlExecute: vi.fn(),
      sqlTableDetails: vi.fn(),
      sqlRelations: vi.fn(),
      getDatabaseInfo: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import SqlPlayground from '../components/admin/SqlPlayground.jsx'

/**
 * SQL Playground — 2026-09-27 shadcn yeniden tasarımı. Rol/ad ve data-slot ile sorgulanır (legacy sınıf yok).
 * Kapsam: PageHeader (salt-okunur çipi, DB sürümü, tablo sayısı, Çalıştır + kısayol), şema gezgini (arama, sorgu
 * yazma, kolon açma + FK rozeti, kolonu imlece ekleme), sonuç (NULL rozeti, sıralama, süzgeç, sayfalama, CSV/TSV,
 * satır ayrıntısı + gezinti), hata bandı (veritabanı mesajı + konum), koruma reddi, 1000 satır uyarısı, örnek/geçmiş
 * seçicileri, telefon sekmeleri + kartlar, tablo ayrıntısı ve diyagram açılışı.
 */
const ROWS = Array.from({ length: 120 }, (_, i) => ({ id: i + 1, name: `host-${String(i + 1).padStart(3, '0')}.example.com`, note: i === 0 ? null : `not ${i}` }))

function editor() { return screen.getByRole('textbox', { name: /SQL query|SQL sorgusu/ }) }
async function typeAndRun(sql) {
  const ed = await screen.findByRole('textbox', { name: /SQL query|SQL sorgusu/ })
  fireEvent.change(ed, { target: { value: sql } })
  fireEvent.keyDown(ed, { key: 'Enter', ctrlKey: true })
  await waitFor(() => expect(api.admin.sqlExecute).toHaveBeenCalledWith(sql))
}

describe('SqlPlayground', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mobile.on = false
    api.admin.sqlListTables.mockResolvedValue({ success: true, data: [
      { table_name: 'alerts', live_rows: 1234, first_seen_at: '2026-01-01', last_change_at: '2026-09-01T10:00:00' },
      { table_name: 'teams', live_rows: 4 },
    ] })
    api.admin.sqlSamples.mockResolvedValue({ success: true, data: [{ label: 'Açık alarmlar', sql: 'SELECT * FROM alerts WHERE resolved = false' }] })
    api.admin.sqlHistory.mockResolvedValue({ success: true, data: [
      { id: 7, sql_text: 'SELECT name FROM teams', row_count: 4, duration_ms: 12, success: true, executed_at: '2026-09-26T09:00:00' },
      { id: 6, sql_text: 'SELECT * FROM nope', row_count: null, duration_ms: 3, success: false, error_message: 'relation "nope" does not exist', executed_at: '2026-09-26T08:00:00' },
    ] })
    api.admin.sqlListColumns.mockResolvedValue({ success: true, data: [
      { column_name: 'id', data_type: 'bigint', is_nullable: 'NO' },
      { column_name: 'team_id', data_type: 'bigint', is_nullable: 'YES' },
    ] })
    api.admin.sqlRelations.mockResolvedValue({ success: true, data: { tables: ['alerts', 'teams'], edges: [{ from: 'alerts', column: 'team_id', to: 'teams', inferred: true }] } })
    api.admin.sqlExecute.mockResolvedValue({ success: true, ok: true, rowCount: 120, durationMs: 34, rows: ROWS, executedSql: 'SELECT * FROM (SELECT 1) AS _capped LIMIT 1000' })
    api.admin.getDatabaseInfo.mockResolvedValue({ success: true, data: { version: 'PostgreSQL 16.2', database: 'sitemonitor', size: '120 MB' } })
  })
  afterEach(() => { vi.restoreAllMocks() })

  it('başlık: shadcn PageHeader — salt-okunur çipi, DB sürümü, tablo sayısı; Çalıştır kısayolu gösterir ve Ctrl+Enter çalıştırır', async () => {
    render(<SqlPlayground />)
    const header = document.querySelector('[data-slot="page-header"]')
    expect(within(header).getByRole('heading', { name: 'SQL Playground' })).toBeInTheDocument()
    expect(await within(header).findByText(/PostgreSQL 16\.2 · sitemonitor · 120 MB/)).toBeInTheDocument()
    expect(within(header).getByText(/2 tables|2 tablo/)).toBeInTheDocument()
    expect(within(header).getByRole('button', { name: /Read-only — show the rules|kuralları göster/ })).toBeInTheDocument()
    const runBtn = within(header).getByRole('button', { name: /^(Run|Çalıştır)/ })
    expect(runBtn).toBeDisabled()
    expect(runBtn.querySelector('[data-slot="kbd-group"]')).not.toBeNull()
    expect(runBtn).toHaveAttribute('aria-keyshortcuts', 'Control+Enter')
    await typeAndRun('SELECT 1')
    expect(await screen.findByText(/120 rows|120 satır/)).toBeInTheDocument()
  })

  it('şema gezgini: arama süzer; tablo adı sorguyu yazar; kolonlar açılır (FK rozeti ilişkiden), kolon imlece eklenir', async () => {
    render(<SqlPlayground />)
    const list = await screen.findByRole('list', { name: /^(Tables|Tablolar)$/ })
    await within(list).findByText('alerts')
    fireEvent.change(screen.getByRole('textbox', { name: /Search tables or columns|Tablo ya da kolon ara/ }), { target: { value: 'tea' } })
    expect(within(list).queryByText('alerts')).toBeNull()
    fireEvent.change(screen.getByRole('textbox', { name: /Search tables or columns|Tablo ya da kolon ara/ }), { target: { value: '' } })

    fireEvent.click(within(list).getByRole('button', { name: /Query the alerts table|alerts tablosunu sorgula/ }))
    expect(editor().value).toBe('SELECT *\nFROM alerts\nLIMIT 100;')

    const toggle = within(list).getByRole('button', { name: /alerts — (show or hide columns|kolonları göster)/ })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(toggle)
    await waitFor(() => expect(api.admin.sqlListColumns).toHaveBeenCalledWith('alerts'))
    const cols = await screen.findByRole('list', { name: /Columns of alerts|alerts kolonları/ })
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    const fkBtn = within(cols).getByRole('button', { name: /alerts\.team_id/ })
    expect(within(fkBtn).getByText('FK')).toBeInTheDocument()
    expect(within(fkBtn).getByText(/→ teams/)).toBeInTheDocument()
    fireEvent.change(editor(), { target: { value: 'SELECT ' } })
    fireEvent.click(fkBtn)
    await waitFor(() => expect(editor().value).toBe('SELECT team_id'))
  })

  it('sonuç: shadcn Table, NULL rozeti, sıralama (aria-sort), süzgeç, sayfalama, satıra tıkla → ayrıntı (önceki/sonraki)', async () => {
    render(<SqlPlayground />)
    await typeAndRun('SELECT * FROM hosts')
    const result = await screen.findByRole('region', { name: /^(Results|Sonuçlar)$/ })
    const table = result.querySelector('[data-slot="sql-result-table"]')
    expect(table).not.toBeNull()
    expect(within(table).getAllByText('NULL')[0].closest('[data-slot="badge"]')).not.toBeNull()
    expect(within(result).getByText(/1–50 of 120|1–50 \/ 120/)).toBeInTheDocument()

    const idHead = within(table).getByRole('button', { name: /Sort by id|id kolonuna göre/ })
    fireEvent.click(idHead)
    fireEvent.click(idHead)
    expect(idHead.closest('th')).toHaveAttribute('aria-sort', 'descending')
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('host-120')

    fireEvent.change(within(result).getByRole('textbox', { name: /Filter results|Sonuçlarda süz/ }), { target: { value: 'host-007' } })
    await waitFor(() => expect(within(table).getAllByRole('row')).toHaveLength(2))
    expect(within(result).getByText(/1 matching|1 eşleşme/)).toBeInTheDocument()

    fireEvent.click(within(table).getAllByRole('row')[1])
    const dlg = await screen.findByRole('dialog', { name: /Row #7|Satır #7/ })
    expect(within(dlg).getByText('host-007.example.com')).toBeInTheDocument()
    expect(within(dlg).queryByRole('button', { name: /Next row|Sonraki satır/ })).toBeNull()   // tek satır: gezinti yok
    fireEvent.click(within(dlg).getAllByRole('button', { name: /^(Close|Kapat)$/ })[0])
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    // Gezinti GÖRÜNÜM sırasını izler (id azalan): 120 → 119
    fireEvent.change(within(result).getByRole('textbox', { name: /Filter results|Sonuçlarda süz/ }), { target: { value: '' } })
    const first = await waitFor(() => { const r = within(table).getAllByRole('row')[1]; expect(r).toHaveTextContent('host-120'); return r })
    fireEvent.keyDown(first, { key: 'Enter' })
    const dlg2 = await screen.findByRole('dialog', { name: /Row #120|Satır #120/ })
    expect(within(dlg2).getByText('1 / 120')).toBeInTheDocument()
    expect(within(dlg2).getByRole('button', { name: /Previous row|Önceki satır/ })).toBeDisabled()
    fireEvent.click(within(dlg2).getByRole('button', { name: /Next row|Sonraki satır/ }))
    expect(await screen.findByRole('dialog', { name: /Row #119|Satır #119/ })).toBeInTheDocument()
  })

  it('dışa aktarım: CSV indirme (görünür sütunlar, ortak kaçış) ve TSV panoya kopyalama', async () => {
    const created = []
    URL.createObjectURL = vi.fn((blob) => { created.push(blob); return 'blob:x' })
    URL.revokeObjectURL = vi.fn()
    const write = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    render(<SqlPlayground />)
    await typeAndRun('SELECT * FROM hosts')
    const result = await screen.findByRole('region', { name: /^(Results|Sonuçlar)$/ })

    pressMenuTrigger(within(result).getByRole('button', { name: /Columns 3\/3|Kolonlar 3\/3/ }))
    fireEvent.click(await screen.findByRole('menuitemcheckbox', { name: 'note' }))
    expect(within(result).getByRole('button', { name: /Columns 2\/3|Kolonlar 2\/3/ })).toBeInTheDocument()

    pressMenuTrigger(within(result).getByRole('button', { name: /^(Export|Dışa aktar)$/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Download CSV|CSV indir/ }))
    expect(created).toHaveLength(1)
    // Blob.text() BOM'u yutar; BOM + kaçış modelde sınanır (sqlPlaygroundModel.test.js) — burada başlık ve sütunlar
    const raw = await created[0].text()
    const csv = raw.charCodeAt(0) === 0xfeff ? raw.slice(1) : raw
    expect(created[0].type).toMatch(/^text\/csv/)
    expect(csv.split('\r\n')[0]).toBe('id,name')
    expect(csv).not.toContain('note')

    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    pressMenuTrigger(within(result).getByRole('button', { name: /^(Export|Dışa aktar)$/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Copy as a table|Tablo olarak kopyala/ }))
    await waitFor(() => expect(write).toHaveBeenCalled())
    expect(write.mock.calls[0][0].split('\n')[0]).toBe('id\tname')
    expect(write.mock.calls[0][0].split('\n')).toHaveLength(121)
  })

  it('veritabanı hatası: danger AlertBanner (role=alert) — PostgreSQL mesajı, ipucu, konum; ham metin teknik ayrıntıda', async () => {
    const sql = 'SELECT fail FROM t'
    const executedSql = `SELECT * FROM (${sql}) AS _capped LIMIT 1000`
    api.admin.sqlExecute.mockResolvedValue({ success: true, ok: false, rows: [], rowCount: 0, durationMs: 5, executedSql,
      error: `StatementCallback; bad SQL grammar [${executedSql}]; ERROR: column "fail" does not exist\n  Hint: Check it.\n  Position: ${executedSql.indexOf('fail') + 1}` })
    render(<SqlPlayground />)
    await typeAndRun(sql)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-slot', 'alert')
    expect(alert).toHaveAttribute('data-tone', 'danger')
    expect(alert).toHaveTextContent(/couldn’t parse the query|çözümleyemedi/)
    expect(alert).toHaveTextContent('column "fail" does not exist')
    expect(alert).toHaveTextContent(/Hint: Check it\.|İpucu: Check it\./)
    expect(alert).toHaveTextContent(/line 1, column 8|satır 1, sütun 8/)
    expect(within(alert).getByRole('button', { name: /Show in editor|Düzenleyicide göster/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Technical details|Teknik ayrıntı/ })).toBeInTheDocument()
  })

  it('koruma reddi (success:false): "izin verilmedi" + salt-okunur kural metni', async () => {
    api.admin.sqlExecute.mockResolvedValue({ success: false, error: 'Sadece SELECT veya WITH ile başlayan sorgular çalıştırılabilir' })
    render(<SqlPlayground />)
    await typeAndRun('DELETE FROM teams')
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/Query not allowed|Sorguya izin verilmedi/)
    expect(alert).toHaveTextContent('Sadece SELECT veya WITH')
    expect(screen.getByText(/Read-only rules|Salt okunur kurallar/)).toBeInTheDocument()
  })

  it('1000 satır tavanı: uyarı bandı', async () => {
    api.admin.sqlExecute.mockResolvedValue({ success: true, ok: true, rowCount: 1000, durationMs: 90,
      rows: Array.from({ length: 1000 }, (_, i) => ({ id: i })), executedSql: 'x' })
    render(<SqlPlayground />)
    await typeAndRun('SELECT * FROM big')
    const warn = await screen.findByText(/Showing the first 1,000 rows|İlk 1[.,]000 satır/)
    expect(warn.closest('[data-slot="alert"]')).toHaveAttribute('data-tone', 'warning')
  })

  it('örnek ve geçmiş seçicileri: Popover + Command; seçim düzenleyiciye yazar, başarısız geçmiş işaretli', async () => {
    render(<SqlPlayground />)
    await screen.findByText('alerts')
    fireEvent.click(screen.getByRole('button', { name: /^(Sample Queries|Örnek Sorgular) \(1\)$/ }))
    fireEvent.click(await screen.findByRole('option', { name: /Açık alarmlar/ }))
    await waitFor(() => expect(editor().value).toBe('SELECT * FROM alerts WHERE resolved = false'))

    fireEvent.click(screen.getByRole('button', { name: /^(History|Geçmiş) \(2\)$/ }))
    const failed = await screen.findByRole('option', { name: /SELECT \* FROM nope/ })
    expect(failed).toHaveAttribute('data-state', 'failed')
    expect(failed).toHaveTextContent(/failed|başarısız/)
    fireEvent.click(screen.getByRole('option', { name: /SELECT name FROM teams/ }))
    await waitFor(() => expect(editor().value).toBe('SELECT name FROM teams'))
  })

  it('telefon: Düzenleyici / Sonuçlar / Şema sekmeleri; çalıştırınca Sonuçlar sekmesine geçer, satırlar kart', async () => {
    mobile.on = true
    render(<SqlPlayground />)
    expect(await screen.findByRole('tab', { name: /Editor|Düzenleyici/ })).toHaveAttribute('aria-selected', 'true')
    await typeAndRun('SELECT * FROM hosts')
    await waitFor(() => expect(screen.getByRole('tab', { name: /Results|Sonuçlar/ })).toHaveAttribute('aria-selected', 'true'))
    const cards = document.querySelector('[data-slot="sql-result-cards"]')
    expect(cards).not.toBeNull()
    expect(within(cards).getAllByRole('button')).toHaveLength(10)   // telefon ön ayarı: 10 / sayfa
    fireEvent.click(within(cards).getAllByRole('button')[0])
    expect(await screen.findByRole('dialog', { name: /Row #1|Satır #1/ })).toBeInTheDocument()
  })

  it('tablo ayrıntısı gezginden açılır (Tabs); "Diyagramda göster" ilişki diyagramını o tabloda açar', async () => {
    api.admin.sqlTableDetails.mockResolvedValue({ success: true, data: {
      columns: [{ column_name: 'id', data_type: 'bigint', is_nullable: 'NO', is_pk: true }], constraints: [], indexes: [], triggers: [],
    } })
    render(<SqlPlayground />)
    const list = await screen.findByRole('list', { name: /^(Tables|Tablolar)$/ })
    fireEvent.click(within(list).getByRole('button', { name: /alerts — (Table details|Tablo detayları)/ }))
    const dlg = await screen.findByRole('dialog', { name: /alerts — (schema details|şema detayları)/ })
    expect(await within(dlg).findByRole('tab', { name: /Columns \(1\)|Kolonlar \(1\)/ })).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: /Show in diagram|Diyagramda göster/ }))
    const diag = await screen.findByRole('dialog', { name: /Table relationships|Tablo ilişkileri/ })
    await waitFor(() => expect(within(diag).getByText('alerts', { selector: '[data-slot="diagram-focus"] span' })).toBeInTheDocument())
  })

  it('başlıktaki Diyagram düğmesi ilişki penceresini açar (ilişkiler açılışta bir kez yüklenir)', async () => {
    render(<SqlPlayground />)
    await screen.findByText('alerts')
    fireEvent.click(within(document.querySelector('[data-slot="page-header"]')).getByRole('button', { name: /^(Diagram|Diyagram)$/ }))
    const diag = await screen.findByRole('dialog', { name: /Table relationships|Tablo ilişkileri/ })
    expect(diag.querySelectorAll('[data-slot="diagram-node"]').length).toBe(2)
    expect(api.admin.sqlRelations).toHaveBeenCalledTimes(1)
    await act(async () => {})
  })
})
