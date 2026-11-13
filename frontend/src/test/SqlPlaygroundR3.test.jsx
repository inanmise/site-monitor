import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))
vi.mock('../contexts/PermissionsProvider.jsx', async (orig) => {
  const m = await orig()
  return {
    ...m,
    usePermissions: () => ({
      perms: {}, scope: null, loaded: true, failed: false, refresh: () => {},
      canView: () => true, canExecute: () => true, canEdit: () => true,
    }),
  }
})

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
import ResultChart from '../components/admin/sql/ResultChart.jsx'
import { OWNER_KEY } from '../utils/personalStorage.js'

/**
 * SQL Playground — 3. tur tarama düzeltmeleri (2026-11-13), bileşen davranışları. Her test, düzeltmesi geri alınırsa düşer.
 */
function editor() { return screen.getByRole('textbox', { name: /SQL query|SQL sorgusu/ }) }
async function typeSql(sql) {
  const ed = await screen.findByRole('textbox', { name: /SQL query|SQL sorgusu/ })
  fireEvent.change(ed, { target: { value: sql } })
  return ed
}
/** Düzenleyicide [a, b) seçer ve seçim olayını tetikler (gerçek kullanıcının fareyle seçmesi gibi onCaret çağrılır). */
function selectRange(ed, a, b) {
  ed.setSelectionRange(a, b)
  fireEvent.select(ed)
}
const resultRegion = () => screen.findByRole('region', { name: /^(Results|Sonuçlar)$/ })
const runButton = () => within(document.querySelector('[data-slot="page-header"]') ?? document.body)
  .getByRole('button', { name: /^(Run|Çalıştır|Run selection|Seçimi çalıştır)/ })

describe('SqlPlayground — 3. tur düzeltmeleri', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    sessionStorage.clear()
    localStorage.setItem(OWNER_KEY, 'admin')
    mobile.on = false
    api.admin.sqlListTables.mockResolvedValue({ success: true, data: [{ table_name: 'alerts', live_rows: 10 }, { table_name: 'teams', live_rows: 4 }] })
    api.admin.sqlSamples.mockResolvedValue({ success: true, data: [] })
    api.admin.sqlHistory.mockResolvedValue({ success: true, data: [] })
    api.admin.sqlListColumns.mockResolvedValue({ success: true, data: [{ column_name: 'id', data_type: 'bigint' }, { column_name: 'name', data_type: 'text' }] })
    api.admin.sqlRelations.mockResolvedValue({ success: true, data: { tables: [], edges: [] } })
    api.admin.getDatabaseInfo.mockResolvedValue({ success: false })
    api.admin.sqlDictionary.mockResolvedValue({ success: false, status: 404 })
    api.admin.sqlExecute.mockResolvedValue({ success: true, ok: true, rowCount: 1, durationMs: 3, rows: [{ id: 1 }], masked_columns: [] })
  })
  afterEach(() => { vi.restoreAllMocks() })

  it('2 · kapanan sekmenin seçimi başka sekmede çalışmaz (çalıştır etiketi ve gönderilen SQL etkin sekmenin)', async () => {
    render(<SqlPlayground />)
    await typeSql('SELECT 1')
    fireEvent.click(document.querySelector('[data-slot="sql-tab-add"]'))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="sql-tab"]')).toHaveLength(2))
    const ed2 = await typeSql('SELECT 2 FROM teams')
    selectRange(ed2, 0, 8)   // "SELECT 2"
    await waitFor(() => expect(runButton()).toHaveAccessibleName(/^(Run selection|Seçimi çalıştır)/))

    fireEvent.click(screen.getByRole('button', { name: /Close tab: Query 2|Sekmeyi kapat: Sorgu 2/ }))
    const dlg = await screen.findByRole('dialog', { name: /Close this tab?|Sekme kapatılsın mı/ })
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Close|Kapat)$/ }))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="sql-tab"]')).toHaveLength(1))
    await waitFor(() => expect(editor().value).toBe('SELECT 1'))

    expect(runButton()).not.toHaveAccessibleName(/^(Run selection|Seçimi çalıştır)/)
    fireEvent.click(runButton())
    await waitFor(() => expect(api.admin.sqlExecute).toHaveBeenCalledTimes(1))
    expect(api.admin.sqlExecute.mock.calls[0][0]).toBe('SELECT 1')
  })

  it('2 · çoğaltılan sekmede de eski seçim geçersiz', async () => {
    render(<SqlPlayground />)
    const ed = await typeSql('SELECT 10, 20')
    selectRange(ed, 0, 9)
    await waitFor(() => expect(runButton()).toHaveAccessibleName(/^(Run selection|Seçimi çalıştır)/))
    // sekme menüsünden çoğalt → yeni etkin sekme
    pressMenuTrigger(screen.getByRole('button', { name: /Tab actions|Sekme işlemleri/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /^(Duplicate|Çoğalt)$/ }))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="sql-tab"]')).toHaveLength(2))
    await waitFor(() => expect(runButton()).not.toHaveAccessibleName(/^(Run selection|Seçimi çalıştır)/))
  })

  it('4 · öneri listesi gövdeye portal ile sabit konumda; ↓ ile etkin öğe listenin görünen kısmına kayar', async () => {
    // jsdom yerleşim yapmaz: satır yüksekliği 32 px, liste 64 px gibi davranır
    const optTop = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetTop')
    const optH = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight')
    const clientH = Object.getOwnPropertyDescriptor(Element.prototype, 'clientHeight')
    Object.defineProperty(HTMLElement.prototype, 'offsetTop', { configurable: true, get() {
      if (this.getAttribute?.('role') !== 'option') return 0
      return [...this.parentElement.children].indexOf(this) * 32
    } })
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, get() { return this.getAttribute?.('role') === 'option' ? 32 : 0 } })
    Object.defineProperty(Element.prototype, 'clientHeight', { configurable: true, get() { return this.getAttribute?.('role') === 'listbox' ? 64 : 0 } })
    try {
      api.admin.sqlListTables.mockResolvedValue({ success: true, data: Array.from({ length: 30 }, (_, i) => ({ table_name: `tbl_${String(i).padStart(2, '0')}` })) })
      render(<SqlPlayground />)
      await screen.findByText('tbl_00')
      const ed = editor()
      fireEvent.change(ed, { target: { value: 'SELECT * FROM tbl', selectionStart: 17, selectionEnd: 17 } })
      const lb = await screen.findByRole('listbox', { name: /Suggestions|Öneriler/ })
      expect(lb.parentElement).toBe(document.body)
      expect(lb.className).toMatch(/\bfixed\b/)
      expect(document.querySelector('[data-slot="sql-editor"]').contains(lb)).toBe(false)
      for (let i = 0; i < 12; i++) fireEvent.keyDown(ed, { key: 'ArrowDown' })
      await waitFor(() => expect(ed.getAttribute('aria-activedescendant')).toBe(within(lb).getAllByRole('option')[12].id))
      // etkin öğe (12 × 32 = 384 .. 416) görünen pencerede: scrollTop = 416 − 64
      expect(lb.scrollTop).toBe(352)
      fireEvent.keyDown(ed, { key: 'ArrowUp' })
      fireEvent.keyDown(ed, { key: 'ArrowUp' })
      fireEvent.keyDown(ed, { key: 'ArrowUp' })
      await waitFor(() => expect(lb.scrollTop).toBe(9 * 32))
    } finally {
      if (optTop) Object.defineProperty(HTMLElement.prototype, 'offsetTop', optTop)
      if (optH) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', optH)
      if (clientH) Object.defineProperty(Element.prototype, 'clientHeight', clientH)
    }
  })

  it('5 · panelden düzenleme: çakışma → son sürümü yükle → kaydet SQL göndermez (başka oturumun SQL değişikliği korunur)', async () => {
    const row = { id: 9, title: 'Takım listesi', description: '', tags: [], sql_text: 'SELECT * FROM teams', shared: false, owner_name: 'admin', mine: true, can_edit: true, version: 1 }
    api.admin.sqlSavedList.mockResolvedValue({ success: true, total: 1, data: [row] })
    api.admin.sqlSavedUpdate
      .mockResolvedValueOnce({ success: false, status: 409, code: 'CONCURRENT_MODIFICATION', error: 'changed' })
      .mockResolvedValueOnce({ success: true, data: { ...row, title: 'Takımlar (yeni)', sql_text: 'SELECT id FROM teams WHERE active', version: 3 } })
    api.admin.sqlSavedGet.mockResolvedValue({ success: true, data: { ...row, sql_text: 'SELECT id FROM teams WHERE active', version: 2 } })
    render(<SqlPlayground />)
    await screen.findByText('alerts')
    // Kayıt bir sekmede açık (v1, 'SELECT * FROM teams')
    fireEvent.click(document.querySelector('[data-slot="sql-saved-open"]'))
    const first = await waitFor(() => { const i = document.querySelector('[data-slot="sql-saved-item"]'); expect(i).not.toBeNull(); return i })
    fireEvent.click(within(first).getByRole('button', { name: /^(Open|Aç)$/ }))
    await waitFor(() => expect(editor().value).toBe('SELECT * FROM teams'))
    fireEvent.click(document.querySelector('[data-slot="sql-saved-open"]'))
    const item = await waitFor(() => { const i = document.querySelector('[data-slot="sql-saved-item"]'); expect(i).not.toBeNull(); return i })
    fireEvent.click(within(item).getByRole('button', { name: /^(Edit|Düzenle)$/ }))
    const dlg = await screen.findByRole('dialog', { name: /Edit saved query|Kayıtlı sorguyu düzenle/ })
    fireEvent.change(within(dlg).getByRole('textbox', { name: /^(Title|Başlık)/ }), { target: { value: 'Takımlar (yeni)' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /Save changes|Değişiklikleri kaydet/ }))
    await waitFor(() => expect(api.admin.sqlSavedUpdate).toHaveBeenCalledTimes(1))
    expect(api.admin.sqlSavedUpdate.mock.calls[0]).toEqual([9, { title: 'Takımlar (yeni)', version: 1 }])
    const banner = await waitFor(() => { const b = dlg.querySelector('[data-slot="sql-save-banner"]'); expect(b).not.toBeNull(); return b })
    fireEvent.click(within(banner).getByRole('button', { name: /Load latest version|Güncel sürümü yükle|Son sürümü yükle/ }))
    await waitFor(() => expect(api.admin.sqlSavedGet).toHaveBeenCalledWith(9))
    await within(dlg).findByText(/latest version was loaded|Güncel sürüm yüklendi/)
    fireEvent.click(within(dlg).getByRole('button', { name: /Save changes|Değişiklikleri kaydet/ }))
    await waitFor(() => expect(api.admin.sqlSavedUpdate).toHaveBeenCalledTimes(2))
    expect(api.admin.sqlSavedUpdate.mock.calls[1]).toEqual([9, { title: 'Takımlar (yeni)', version: 2 }])
    expect(JSON.stringify(api.admin.sqlSavedUpdate.mock.calls)).not.toContain('sql_text')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: /Edit saved query|Kayıtlı sorguyu düzenle/ })).toBeNull())

    // Açık sekmenin sürümü İLERLEMEDİ (kayıttaki SQL başka oturumca değişti): sekmeden kaydet v1 gönderir → 409, ezmez
    api.admin.sqlSavedUpdate.mockResolvedValueOnce({ success: false, status: 409, code: 'CONCURRENT_MODIFICATION', error: 'changed' })
    fireEvent.click(document.querySelector('[data-slot="sql-save"]'))
    const dlg2 = await screen.findByRole('dialog', { name: /Edit saved query|Kayıtlı sorguyu düzenle/ })
    fireEvent.click(within(dlg2).getByRole('button', { name: /Save changes|Değişiklikleri kaydet/ }))
    await waitFor(() => expect(api.admin.sqlSavedUpdate).toHaveBeenCalledTimes(3))
    expect(api.admin.sqlSavedUpdate.mock.calls[2][1].version).toBe(1)
  })

  it('5 · panelden düzenleme, SQL değişmemişse açık sekmenin sürümü ilerler (gereksiz 409 yok)', async () => {
    const row = { id: 8, title: 'Alarmlar', description: '', tags: [], sql_text: 'SELECT * FROM alerts', shared: false, owner_name: 'admin', mine: true, can_edit: true, version: 4 }
    api.admin.sqlSavedList.mockResolvedValue({ success: true, total: 1, data: [row] })
    api.admin.sqlSavedUpdate.mockResolvedValueOnce({ success: true, data: { ...row, title: 'Alarmlar (yeni)', version: 5 } })
    render(<SqlPlayground />)
    await screen.findByText('alerts')
    fireEvent.click(document.querySelector('[data-slot="sql-saved-open"]'))
    const first = await waitFor(() => { const i = document.querySelector('[data-slot="sql-saved-item"]'); expect(i).not.toBeNull(); return i })
    fireEvent.click(within(first).getByRole('button', { name: /^(Open|Aç)$/ }))
    await waitFor(() => expect(editor().value).toBe('SELECT * FROM alerts'))
    fireEvent.click(document.querySelector('[data-slot="sql-saved-open"]'))
    const item = await waitFor(() => { const i = document.querySelector('[data-slot="sql-saved-item"]'); expect(i).not.toBeNull(); return i })
    fireEvent.click(within(item).getByRole('button', { name: /^(Edit|Düzenle)$/ }))
    const dlg = await screen.findByRole('dialog', { name: /Edit saved query|Kayıtlı sorguyu düzenle/ })
    fireEvent.change(within(dlg).getByRole('textbox', { name: /^(Title|Başlık)/ }), { target: { value: 'Alarmlar (yeni)' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /Save changes|Değişiklikleri kaydet/ }))
    await waitFor(() => expect(api.admin.sqlSavedUpdate).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: /Edit saved query|Kayıtlı sorguyu düzenle/ })).toBeNull())
    expect(document.querySelector('[data-slot="sql-tab"][data-active="true"]')).toHaveTextContent('Alarmlar (yeni)')

    // kayıtlı sorgular paneli hâlâ açık (sayfa aria-hidden) — düzenleyiciye doğrudan yazılır
    fireEvent.change(document.querySelector('[data-slot="sql-editor"] textarea'), { target: { value: 'SELECT * FROM alerts WHERE resolved = false' } })
    api.admin.sqlSavedGet.mockResolvedValueOnce({ success: true, data: { ...row, title: 'Alarmlar (yeni)', version: 5 } })
    api.admin.sqlSavedUpdate.mockResolvedValueOnce({ success: true, data: { ...row, sql_text: 'SELECT * FROM alerts WHERE resolved = false', version: 6 } })
    fireEvent.click(document.querySelector('[data-slot="sql-save"]'))
    const dlg2 = await screen.findByRole('dialog', { name: /Edit saved query|Kayıtlı sorguyu düzenle/ })
    fireEvent.click(within(dlg2).getByRole('button', { name: /Save changes|Değişiklikleri kaydet/ }))
    await waitFor(() => expect(api.admin.sqlSavedUpdate).toHaveBeenCalledTimes(2))
    expect(api.admin.sqlSavedUpdate.mock.calls[1][1]).toEqual({ sql_text: 'SELECT * FROM alerts WHERE resolved = false', version: 5 })
  })

  it('6 · düzenleyiciden kaydet sekmenin sürümünü gönderir (başka yerde kaydedilen yeni sürüm 409 ile korunur)', async () => {
    window.history.replaceState({}, '', '/?tab=sqlplayground&sq=21')
    api.admin.sqlSavedGet.mockResolvedValueOnce({ success: true, data: { id: 21, title: 'Bağlı', sql_text: 'SELECT 21', version: 3, mine: true } })
    render(<SqlPlayground />)
    await waitFor(() => expect(editor().value).toBe('SELECT 21'))
    await typeSql('SELECT 21, 22')
    // başka bir sekme / oturum bu arada v5'i kaydetti
    api.admin.sqlSavedGet.mockResolvedValueOnce({ success: true, data: { id: 21, title: 'Bağlı', description: '', tags: [], shared: false, sql_text: 'SELECT 21 /* v5 */', version: 5, mine: true } })
    api.admin.sqlSavedUpdate.mockResolvedValueOnce({ success: false, status: 409, code: 'CONCURRENT_MODIFICATION', error: 'changed' })
    fireEvent.click(document.querySelector('[data-slot="sql-save"]'))
    const dlg = await screen.findByRole('dialog', { name: /Edit saved query|Kayıtlı sorguyu düzenle/ })
    fireEvent.click(within(dlg).getByRole('button', { name: /Save changes|Değişiklikleri kaydet/ }))
    await waitFor(() => expect(api.admin.sqlSavedUpdate).toHaveBeenCalledTimes(1))
    expect(api.admin.sqlSavedUpdate.mock.calls[0][1].version).toBe(3)
    expect(await within(dlg).findByText(/changed while you were editing|siz düzenlerken değişti/)).toBeInTheDocument()
  })

  it('6 · kaydı okurken geçici hata (503) bağı koparmaz, hata söyler; yalnız 404 koparır', async () => {
    window.history.replaceState({}, '', '/?tab=sqlplayground&sq=31')
    api.admin.sqlSavedGet.mockResolvedValueOnce({ success: true, data: { id: 31, title: 'Bağlı', sql_text: 'SELECT 31', version: 1, mine: true } })
    render(<SqlPlayground />)
    await waitFor(() => expect(editor().value).toBe('SELECT 31'))
    api.admin.sqlSavedGet.mockResolvedValueOnce({ success: false, status: 503, code: 'DB_UNAVAILABLE', error: 'Veritabanına şu an ulaşılamıyor; birkaç saniye sonra yeniden deneyin.' })
    const saveBtn = document.querySelector('[data-slot="sql-save"]')
    expect(saveBtn).toHaveTextContent(/^(Save|Kaydet)$/)
    fireEvent.click(saveBtn)
    expect(await screen.findByText(/Veritabanına şu an ulaşılamıyor/)).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: /Save query|Sorguyu kaydet|Edit saved query|Kayıtlı sorguyu düzenle/ })).toBeNull()
    expect(document.querySelector('[data-slot="sql-save"]')).toHaveTextContent(/^(Save|Kaydet)$/)   // bağ korundu
    expect(document.querySelector('[data-slot="sql-tab"][data-active="true"]').querySelector('svg.lucide-bookmark')).not.toBeNull()

    api.admin.sqlSavedGet.mockResolvedValueOnce({ success: false, status: 404, code: 'NOT_FOUND', error: 'x' })
    fireEvent.click(document.querySelector('[data-slot="sql-save"]'))
    expect(await screen.findByRole('dialog', { name: /Save query|Sorguyu kaydet/ })).toBeInTheDocument()
    expect(await screen.findByText(/no longer exists|artık yok/)).toBeInTheDocument()
  })

  it('11 · şema araması Türkçe katlamayla (İ / ı, büyük harf açıklama) eşleşir', async () => {
    api.admin.sqlDictionary.mockResolvedValue({ success: true, tables: {
      teams: { description: 'İŞLEM SAHİBİ TAKIMLAR — önem: HIGH / CRITICAL', columns: {} },
      alerts: { description: 'Alarm kayıtları', columns: { id: 'Alarm numarası' } },
    } })
    render(<SqlPlayground />)
    const list = await screen.findByRole('list', { name: /^(Tables|Tablolar)$/ })
    await within(list).findByText('alerts')
    const search = screen.getByRole('textbox', { name: /Search tables or columns|Tablo ya da kolon ara/ })
    fireEvent.change(search, { target: { value: 'islem sahibi' } })
    await waitFor(() => expect(within(list).queryByText('alerts')).toBeNull())
    expect(within(list).getByText('teams')).toBeInTheDocument()
    fireEvent.change(search, { target: { value: 'critical' } })
    expect(within(list).getByText('teams')).toBeInTheDocument()
    fireEvent.change(search, { target: { value: 'numarası' } })   // sözlükteki kolon açıklaması
    await waitFor(() => expect(within(list).queryByText('teams')).toBeNull())
    expect(within(list).getByText('alerts')).toBeInTheDocument()
  })

  it('12 · grafik: bir seride seçili kolon öbür seride seçilemez (yinelenen seri yok)', async () => {
    const rows = Array.from({ length: 4 }, (_, i) => ({ team: `T${i}`, n: i + 1, m: i * 2 }))
    render(<ResultChart rows={rows} masked={[]} />)
    const y1 = await screen.findByRole('combobox', { name: /Series 1|Seri 1/ })
    const y2 = screen.getByRole('combobox', { name: /Series 2|Seri 2/ })
    expect(y1).toHaveValue('n')
    expect(within(y2).getByRole('option', { name: 'n' })).toBeDisabled()
    expect(within(y2).getByRole('option', { name: 'm' })).not.toBeDisabled()
    fireEvent.change(y2, { target: { value: 'm' } })
    expect(within(y1).getByRole('option', { name: 'm' })).toBeDisabled()
  })

  it('arka uç eşgüdümü · bellek sınırı: kısmi sonuç bandı + kısaltılan hücre notu; satır nedeni 1000 satır bandı; diziler okunur', async () => {
    api.admin.sqlExecute.mockResolvedValueOnce({
      success: true, ok: true, rowCount: 2, durationMs: 5, masked_columns: [],
      rows: [{ id: 1, tags: ['a', 'b'], doc: '{"k":1}' }, { id: 2, tags: [], doc: null }],
      truncated: true, truncated_reason: 'bytes', truncated_cells: 3,
    })
    render(<SqlPlayground />)
    await typeSql('SELECT id, tags, doc FROM t')
    fireEvent.keyDown(editor(), { key: 'Enter', ctrlKey: true })
    const region = await resultRegion()
    const partial = await waitFor(() => { const p = region.querySelector('[data-slot="sql-result-partial"]'); expect(p).not.toBeNull(); return p })
    expect(partial).toHaveTextContent(/Partial result — showing the first 2 rows|Sonuç kısmi — ilk 2 satır gösteriliyor/)
    expect(region.querySelector('[data-slot="sql-cells-cut"]')).toHaveTextContent(/3/)
    expect(within(region).queryByText(/Showing the first 1[.,]?000 rows|İlk 1[.,]?000 satır gösteriliyor/)).toBeNull()
    expect(within(region).getByText('["a","b"]')).toBeInTheDocument()

    api.admin.sqlExecute.mockResolvedValueOnce({
      success: true, ok: true, rowCount: 1, durationMs: 5, masked_columns: [], rows: [{ id: 1 }], truncated: true, truncated_reason: 'rows', truncated_cells: 0,
    })
    fireEvent.keyDown(editor(), { key: 'Enter', ctrlKey: true })
    await waitFor(() => expect(region.querySelector('[data-slot="sql-result-partial"]')).toBeNull())
    expect(await within(region).findByText(/Showing the first 1[.,]?000 rows|İlk 1[.,]?000 satır gösteriliyor/)).toBeInTheDocument()
    expect(region.querySelector('[data-slot="sql-cells-cut"]')).toBeNull()
  })

  it('1 · sonuç gövdesi her yerleşimde kaydırılabilir (grafik / karşılaştırma panelden uzun olabilir)', async () => {
    render(<SqlPlayground />)
    await typeSql('SELECT 1')
    fireEvent.keyDown(editor(), { key: 'Enter', ctrlKey: true })
    const region = await resultRegion()
    const body = region.querySelector('[data-slot="sql-result-body"]')
    expect(body).not.toBeNull()
    expect(body.className).toMatch(/\boverflow-y-auto\b/)
  })
})
