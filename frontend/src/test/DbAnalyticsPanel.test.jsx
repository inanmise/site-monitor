import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, within, waitFor, act } from './test-utils.jsx'
import DbAnalyticsPanel from '../components/admin/DbAnalyticsPanel.jsx'

const { perms, apiMock } = vi.hoisted(() => ({
  perms: { exec: false },
  apiMock: { admin: { sqlTableDetails: vi.fn() } },
}))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: apiMock,
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => false, canExecute: () => perms.exec, perms: {} }),
}))

/**
 * Sistem Sağlığı → Veritabanı Analitiği (2026-09-26 shadcn, 2026-09-28 zenginleştirme). Sunucu sözleşmesi
 * DbAnalyticsService.getOverview: summary / connections (+ states, uzun süren iş sinyalleri) / db_stats /
 * table_sizes (+ ölü satır, tarama, bakım) / top_tables / top_sql / slowest_sql / recent_queries / failed /
 * top_users / series / truncated / row_limit / generated_at. Fixture GERÇEK tel biçiminde (snake_case); gerçek
 * kişi/kurum adı yok. Tarihler yalnız GÖSTERİLİR (kayan pencereye karşı ölçülmez) → sabit olabilir.
 */
const SQL_A = 'SELECT id, domain FROM certificate_inventory WHERE active = true ORDER BY expiry_date'
const SQL_B = 'UPDATE http_monitor SET last_status = $1 WHERE id = $2'
const tables = Array.from({ length: 30 }, (_, i) => ({
  table_name: i === 7 ? 'audit_log' : `table_${String(i).padStart(2, '0')}`,
  row_count: 1000 - i * 10,
  table_size: `${300 - i} MB`, total_size: `${400 - i} MB`,
  table_size_bytes: (300 - i) * 1_048_576, total_size_bytes: (400 - i) * 1_048_576,
}))
const DATA = (over = {}) => ({
  truncated: false, row_limit: 50000,
  summary: { queries: 1284, failed: 7, success_rate: 99.5, avg_ms: 42, max_ms: 3810, active_connections: 14, db_size: '1843 MB', table_count: 30, pgss: true, days: 7, ...(over.summary || {}) },
  connections: { active: 14, max: 100, db_size: '1843 MB', response_ms: 3 },
  table_sizes: tables,
  top_tables: [{ table_name: 'audit_log', reads: 5_000, writes: 900, row_count: 930 }],
  top_sql: [{ sql: SQL_A, calls: 98000, avg_ms: 1.2, max_ms: 40, total_ms: 118000, rows: 5000 }, { sql: SQL_B, calls: 84000, avg_ms: 4.3, max_ms: 160, total_ms: 99000, rows: 800 }],
  slowest_sql: [{ sql: SQL_B, calls: 40, avg_ms: 3810, max_ms: 5200, total_ms: 150000, rows: 12 }],
  recent_queries: [{ time: '2026-09-26T08:00:00', username: 'admin', sql: SQL_A, duration_ms: 12, success: true, rows: 3 }],
  failed: [{ time: '2026-09-26T07:00:00', username: 'ops.user', sql: SQL_B, error: 'ERROR: canceling statement due to statement timeout' }],
  top_users: [{ username: 'admin', queries: 400, avg_ms: 30, failed: 0, last: '2026-09-26T08:00:00' }],
  series: [{ ts: '2026-09-25T21:00:00', count: 120, avg_ms: 38, failed: 1 }],
  // summary yukarıda varsayılanlarla BİRLEŞTİRİLDİ — düz yayılım onu ezmesin
  ...Object.fromEntries(Object.entries(over).filter(([k]) => k !== 'summary')),
})
/** Yeni sunucu alanları (2026-09-28). */
const RICH = (over = {}) => DATA({
  generated_at: '2026-09-26T09:15:30',
  summary: { p95_ms: 180, user_count: 4 },
  connections: {
    active: 16, max: 100, db_size: '1843 MB', response_ms: 3,
    states: [{ state: 'idle', count: 10 }, { state: 'active', count: 3 }, { state: 'idle in transaction', count: 2 }, { state: 'unknown', count: 1 }],
    idle_in_tx: 2, long_queries: 1, lock_waits: 0, longest_query_s: 75, longest_xact_s: 400, long_threshold_s: 60,
  },
  db_stats: { cache_hit_pct: 97.5, blks_hit: 975, blks_read: 25, xact_commit: 95, xact_rollback: 5, rollback_pct: 5, deadlocks: 0, temp_files: 2, temp_bytes: 2048, temp_size: '2048 bytes', stats_reset: '2026-09-01T00:00:00' },
  table_sizes: tables.map((r, i) => ({
    ...r, schema_name: 'public', dead_rows: i === 3 ? 30_000 : 10, dead_pct: i === 3 ? 25 : 0.1,
    index_size: '10 MB', index_size_bytes: 10_485_760, seq_scan: 5, idx_scan: 95, idx_scan_pct: 95, reads: 100 + i, writes: 3,
    last_vacuum: i === 3 ? null : '2026-09-20T02:00:00', last_analyze: '2026-09-20T02:00:00', mod_since_analyze: 12,
  })),
  series: [
    { ts: '2026-09-24T21:00:00', count: 0, avg_ms: 0, failed: 0 },
    { ts: '2026-09-25T21:00:00', count: 120, avg_ms: 38, failed: 1 },
    { ts: '2026-09-26T21:00:00', count: 60, avg_ms: 420, failed: 6 },
  ],
  ...over,
})

const renderPanel = (props = {}) => {
  const onRefresh = vi.fn(), onDaysChange = vi.fn()
  const utils = render(<DbAnalyticsPanel data={DATA()} days={7} onRefresh={onRefresh} onDaysChange={onDaysChange} {...props} />)
  return { ...utils, onRefresh, onDaysChange }
}
const bodyRows = (id) => [...screen.getByTestId(id).querySelectorAll('[data-slot="table-body"] > tr')]
const kpi = (key) => document.querySelector(`[data-kpi="${key}"]`)
const setWidth = (w) => act(() => { window.innerWidth = w; window.dispatchEvent(new Event('resize')) })

describe('DbAnalyticsPanel', () => {
  beforeEach(() => {
    try { localStorage.clear() } catch { /* */ }
    perms.exec = false
    apiMock.admin.sqlTableDetails.mockReset()
    window.innerWidth = 1024
  })
  afterEach(() => { window.innerWidth = 1024 })

  it('KPI kartları fixture değerlerini basar; bağlantı doluluğu ve başarı oranı rozetle tonlanır (sol şerit YOK)', () => {
    renderPanel()
    expect(kpi('size').textContent).toContain('1843 MB')
    expect(kpi('size').textContent).toMatch(/30 (tablo|tables)/)
    expect(kpi('connections').textContent).toContain('14 / 100')
    const usage = within(kpi('connections')).getByText(/%14 dolu|14% in use/)
    expect(usage).toHaveAttribute('data-tone', 'success')
    expect(kpi('queries').textContent).toMatch(/1[.,]284/)
    expect(within(kpi('queries')).getByText(/%99,5 başarılı|99\.5% succeeded/)).toHaveAttribute('data-tone', 'success')   // TR yerel ondalık (ek-7)
    expect(kpi('slowest').textContent).toMatch(/3[.,]810 ms/)
    expect(kpi('failed').textContent).toContain('7')
    for (const el of document.querySelectorAll('[data-kpi]')) {
      expect(el.className).not.toMatch(/border-l-|before:|shadow-\[inset/)
    }
    expect(screen.getByTestId('db-source')).toHaveAttribute('data-pgss', 'true')
    expect(screen.queryByText(/pg_stat_statements etkin değil|pg_stat_statements isn't enabled/)).toBeNull()
  })

  it('yeni alanlar: önbellek isabeti, ölü satır, p95, kullanıcı sayısı ve "son güncelleme" sunucunun hesapladığı andan', () => {
    renderPanel({ data: RICH() })
    expect(kpi('cache').textContent).toMatch(/%97,5|97\.5%/)
    expect(within(kpi('cache')).getByText(/İzlenmeli|Worth watching/)).toHaveAttribute('data-tone', 'warning')
    expect(kpi('dead')).toHaveAttribute('data-tone', 'warning')
    expect(within(kpi('dead')).getByText(/Bakım önerilen: 1|Needs maintenance: 1/)).toHaveAttribute('data-tone', 'warning')
    expect(kpi('avg').textContent).toMatch(/p95 180 ms/)
    expect(kpi('queries').textContent).toMatch(/4 kullanıcı|Users: 4/)
    const stamp = screen.getByTestId('db-updated').textContent
    const expected = new Date('2026-09-26T09:15:30Z').toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
    const expectedEn = new Date('2026-09-26T09:15:30Z').toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
    expect([expected, expectedEn].some((s) => stamp.includes(s))).toBe(true)
  })

  it('okunamayan kaynak "Bilinmiyor" yazar — yeşil/sıfır DEĞİL (db_stats ve durum dağılımı yok)', () => {
    renderPanel()   // eski/yetkisiz: db_stats yok, connections.states yok, tablolarda ölü satır alanı yok
    expect(kpi('cache')).toHaveAttribute('data-tone', 'muted')
    expect(kpi('cache').textContent).toMatch(/Bilinmiyor|Unknown/)
    expect(kpi('cache').textContent).not.toMatch(/İyi|Good/)
    expect(kpi('dead')).toHaveAttribute('data-tone', 'muted')
    expect(kpi('dead').textContent).toMatch(/Bilinmiyor|Unknown/)
    expect(screen.getByTestId('db-states-unknown')).toBeInTheDocument()
    expect(screen.getByTestId('db-stats-unknown')).toBeInTheDocument()
    expect(screen.queryByTestId('db-signal-clear')).toBeNull()      // durum bilinmiyorken "her şey yolunda" DENMEZ
    expect(screen.getByTestId('db-stat-cache')).toHaveAttribute('data-unknown', 'true')
  })

  it('bağlantılar ve sağlık: durum dağılımı (metinli), uzun sorgu + işlem içinde boşta uyarısı, uygulama havuzu, rollback uyarısı', () => {
    renderPanel({ data: RICH(), appPool: { active: 3, idle: 7, total: 10, waiting: 1, max_size: 30 } })
    const health = screen.getByTestId('db-health')
    const states = within(health).getByTestId('db-states')
    expect(within(states).getByRole('img').getAttribute('aria-label')).toMatch(/Boşta 10|Idle 10/)
    expect(states.textContent).toMatch(/İşlem içinde boşta|Idle in transaction/)
    expect(states.textContent).toMatch(/Görünmüyor|Hidden/)                 // gizli oturumlar "boşta" sayılmaz
    expect(within(health).getByTestId('db-signal-long').textContent).toMatch(/60.*: 1 \(/)
    expect(within(health).getByTestId('db-signal-idletx').textContent).toMatch(/: 2 —/)
    expect(within(health).queryByTestId('db-signal-locks')).toBeNull()
    expect(within(health).getByTestId('db-app-pool').textContent).toMatch(/3 (aktif|active)/)
    expect(within(health).getByText(/^(1 bekleyen|1 waiting)$/)).toHaveAttribute('data-tone', 'warning')
    expect(within(health).getByTestId('db-stat-rollback').textContent).toMatch(/İzlenmeli|Worth watching/)
    expect(within(health).getByTestId('db-conn-detail').textContent).toContain('16')
    expect(within(health).getByTestId('db-stat-resp').textContent).toContain('3 ms')
  })

  it('bağlantı KPI\'ı sağlık kartına götürür ve odağı başlığına taşır', async () => {
    renderPanel({ data: RICH() })
    fireEvent.click(kpi('connections'))
    const heading = within(screen.getByTestId('db-health')).getByRole('heading', { name: /Bağlantılar ve sağlık|Connections and health/ })
    await waitFor(() => expect(document.activeElement).toBe(heading))
  })

  it('pg_stat_statements yoksa kaynak rozeti + açıklayıcı bant + katlanır etkinleştirme adımları (kopyalanır); en yavaşlar Playground sütunlarıyla', () => {
    renderPanel({ data: DATA({ summary: { pgss: false }, slowest_sql: [{ sql: SQL_B, duration_ms: 950, username: 'admin', time: '2026-09-26T07:00:00', rows: 2 }] }) })
    expect(screen.getByTestId('db-source')).toHaveAttribute('data-pgss', 'false')
    expect(screen.getByText(/pg_stat_statements etkin değil|pg_stat_statements isn't enabled/)).toBeInTheDocument()
    expect(screen.queryByText("shared_preload_libraries = 'pg_stat_statements'")).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Nasıl etkinleştirilir|How do I turn it on/ }))
    const steps = screen.getByTestId('db-pgss-steps')
    expect(steps.textContent).toContain("shared_preload_libraries = 'pg_stat_statements'")
    expect(within(steps).getByRole('button', { name: /CREATE EXTENSION IF NOT EXISTS pg_stat_statements/ })).toBeInTheDocument()
    // sorgu kutucuk grubu kaynak açıklaması pgss yokken gösterilmez
    expect(screen.queryByText(/Sorgu listeleri DB geneli|Query lists cover the whole database/)).toBeNull()
    fireEvent.click(kpi('slowest'))
    const sql = screen.getByTestId('db-sql')
    expect(within(sql).getByRole('button', { name: /Süre \(ms\)|Duration \(ms\)/ })).toBeInTheDocument()
    expect(within(sql).queryByRole('button', { name: /^Calls|^Çağrı/ })).toBeNull()
    expect(kpi('slowest').textContent).toContain('UPDATE http_monitor')   // Playground kipinde en yavaş sorgunun kendisi
  })

  it('tablolar: varsayılan toplam boyuta göre azalan, 25 satırlık sayfa + sayfa çubuğu; arama daraltır; başlık sıralaması yön değiştirir', () => {
    renderPanel()
    const rows = () => bodyRows('db-tables')
    expect(screen.getByTestId('db-tables')).toHaveAttribute('data-layout', 'table')
    expect(rows()).toHaveLength(25)
    expect(rows()[0].textContent).toContain('table_00')
    expect(within(screen.getByTestId('db-tables')).getByRole('navigation', { name: /Sayfalama|Pagination/ })).toBeInTheDocument()
    // eski tablonun bütün sütunları duruyor (Tablo Boyutu / Toplam Boyut / Okuma / Yazma) + yeni sağlık sütunları
    for (const name of [/^(Tablo Boyutu|Table Size)$/, /^(Toplam Boyut|Total Size)( [↑↓])?$/, /^(Okuma|Reads)$/, /^(Yazma|Writes)$/, /^(İndeks boyutu|Index size)$/, /^(Son VACUUM|Last VACUUM)$/]) {
      expect(within(screen.getByTestId('db-tables')).getByRole('button', { name })).toBeInTheDocument()
    }
    // kullanım (reads) eski sunucuda yalnız top_tables'taki tabloya eklenir
    fireEvent.change(within(screen.getByTestId('db-tables')).getByRole('searchbox', { name: /Tablo ara|Search tables/ }), { target: { value: 'audit' } })
    expect(rows()).toHaveLength(1)
    expect(rows()[0].textContent).toContain('audit_log')
    expect(rows()[0].textContent).toMatch(/5[.,]000/)
    fireEvent.change(within(screen.getByTestId('db-tables')).getByRole('searchbox', { name: /Tablo ara|Search tables/ }), { target: { value: 'nothing-like-this' } })
    expect(screen.getByText(/Aramayla eşleşen kayıt yok|Nothing matches your search/)).toBeInTheDocument()
    fireEvent.change(within(screen.getByTestId('db-tables')).getByRole('searchbox', { name: /Tablo ara|Search tables/ }), { target: { value: '' } })
    // Tablo adına göre sırala: ilk tık artan (metin), ikinci tık azalan
    const nameHead = within(screen.getByTestId('db-tables')).getByRole('button', { name: /^(Tablo|Table)( [↑↓])?$/ })
    fireEvent.click(nameHead)
    expect(nameHead.closest('th')).toHaveAttribute('aria-sort', 'ascending')
    expect(rows()[0].textContent).toContain('audit_log')
    fireEvent.click(nameHead)
    expect(nameHead.closest('th')).toHaveAttribute('aria-sort', 'descending')
    expect(rows()[0].textContent).toContain('table_29')
  })

  it('ölü satır KPI\'ı tabloları ölü satır oranına göre sıralar; tablo adı ayrıntıyı açar (boyut dağılımı + VACUUM önerisi)', () => {
    renderPanel({ data: RICH() })
    fireEvent.click(kpi('dead'))
    const head = within(screen.getByTestId('db-tables')).getByRole('button', { name: /^(Ölü satır|Dead rows)( [↑↓])?$/ })
    expect(head.closest('th')).toHaveAttribute('aria-sort', 'descending')
    const first = bodyRows('db-tables')[0]
    expect(first.textContent).toContain('table_03')
    fireEvent.click(within(first).getByRole('button', { name: /(Tablo ayrıntısı|Table details) — table_03/ }))
    const dlg = screen.getByRole('dialog')
    expect(within(dlg).getByTestId('db-size-breakdown').textContent).toMatch(/İndeksler|Indexes/)
    expect(dlg.textContent).toMatch(/VACUUM \(ANALYZE\)/)
    expect(dlg.textContent).toMatch(/Hiç|Never/)                           // son vacuum yok → "Hiç" (bilinmiyor değil)
    // Şema ayrıntısı yalnız SQL Playground yetkisiyle
    expect(within(dlg).queryByRole('button', { name: /Şema ayrıntısı|Schema details/ })).toBeNull()
  })

  it('SQL Playground yetkisi varsa tablo ayrıntısından şema penceresi (TableDetailsModal) açılır', async () => {
    perms.exec = true
    apiMock.admin.sqlTableDetails.mockResolvedValue({ success: true, data: { columns: [{ column_name: 'id', data_type: 'bigint', is_pk: true }], constraints: [] } })
    renderPanel({ data: RICH() })
    fireEvent.click(within(bodyRows('db-tables')[0]).getByRole('button', { name: /(Tablo ayrıntısı|Table details) — table_00/ }))
    fireEvent.click(screen.getByRole('button', { name: /Şema ayrıntısı|Schema details/ }))
    await waitFor(() => expect(apiMock.admin.sqlTableDetails).toHaveBeenCalledWith('table_00'))
    await waitFor(() => expect(screen.getByTestId('table-details')).toBeInTheDocument())
  })

  it('KPI tıklaması ilgili sekmeyi açar: Başarısız → hata listesi (hata metni + sınıf rozeti)', () => {
    renderPanel()
    const trigger = document.querySelector('[data-tab="failed"]')
    expect(trigger).toHaveAttribute('data-state', 'inactive')
    fireEvent.click(kpi('failed'))
    expect(trigger).toHaveAttribute('data-state', 'active')
    const row = bodyRows('db-failed')[0]
    expect(row.textContent).toContain('canceling statement due to statement timeout')
    expect(row.querySelector('[data-err-kind="timeout"]')).not.toBeNull()
  })

  it('ortalama KPI\'ı son sorguları süreye göre azalan açar', () => {
    renderPanel({ data: DATA({ recent_queries: [
      { time: '2026-09-26T08:00:00', username: 'admin', sql: 'SELECT 1', duration_ms: 12, success: true },
      { time: '2026-09-26T07:00:00', username: 'admin', sql: 'SELECT 2', duration_ms: 900, success: true },
    ] }) })
    fireEvent.click(kpi('avg'))
    expect(document.querySelector('[data-tab="sql"]')).toHaveAttribute('data-state', 'active')
    const head = within(screen.getByTestId('db-sql')).getByRole('button', { name: /^(Süre \(ms\)|Duration \(ms\))( [↑↓])?$/ })
    expect(head.closest('th')).toHaveAttribute('aria-sort', 'descending')
    expect(bodyRows('db-sql')[0].textContent).toContain('SELECT 2')
  })

  it('SQL ayrıntısı: satırın düğmesi (adı SQL\'i taşır) pencereyi açar — tam SQL (boyalı ama kayıpsız), kopyala, ölçüler', () => {
    renderPanel({ data: RICH({ top_sql: [{ sql: SQL_A, calls: 98000, avg_ms: 1.2, max_ms: 40, total_ms: 118000, rows: 5000, share_pct: 41.5, hit_pct: 99.9 }] }) })
    fireEvent.mouseDown(document.querySelector('[data-tab="sql"]'))
    const sql = screen.getByTestId('db-sql')
    fireEvent.click(within(sql).getByRole('button', { name: /(Ayrıntı|Details) — SELECT id, domain/ }))
    const dlg = screen.getByRole('dialog')
    const full = within(dlg).getByTestId('db-sql-full')
    expect(full.textContent).toBe(SQL_A)
    expect(full.querySelector('[data-token="keyword"]')).not.toBeNull()
    expect(within(dlg).getByRole('button', { name: /^(SQL'i kopyala|Copy SQL)$/ })).toBeInTheDocument()
    expect(dlg.textContent).toMatch(/98[.,]000/)
    expect(within(dlg).getByTestId('db-sql-metrics').textContent).toMatch(/%41[.,]5|41[.,]5%/)
    expect(dlg.textContent).toMatch(/birikimli|cumulative/)                 // pgss değeri pencereyle sınırlı değil
  })

  it('grafik: özet cümlesi, seri aç/kapa (süre kapalıyken süre grafiği yok), tablo görünümü; boş pencerede boş durum', () => {
    const { unmount } = renderPanel({ data: RICH() })
    expect(screen.getByTestId('db-trend-summary').textContent).toMatch(/180 sorgu|Queries: 180/)
    expect(screen.getByTestId('db-trend-duration')).toBeInTheDocument()
    const seriesGroup = document.querySelector('[data-slot="chart-series"]')
    const avg = within(seriesGroup).getByRole('button', { name: /^(Ort\. Süre|Avg\. Time)$/ })
    expect(avg).toHaveAttribute('data-state', 'on')
    fireEvent.click(avg)
    expect(avg).toHaveAttribute('data-state', 'off')
    expect(screen.queryByTestId('db-trend-duration')).toBeNull()
    const card = document.querySelector('[data-slot="db-trend-card"]')
    fireEvent.click(within(card).getByRole('button', { name: /^(Tablo|Table)$/ }))
    const tbl = screen.getByTestId('db-trend-table')
    expect(tbl.querySelectorAll('[data-slot="table-body"] > tr')).toHaveLength(3)
    expect(tbl.textContent).toMatch(/420/)
    expect(tbl.querySelectorAll('[data-slot="table-body"] > tr')[0].textContent).toContain('—')   // sorgusuz kova: süre uydurulmaz
    unmount()
    renderPanel({ data: RICH({ series: [{ ts: '2026-09-25T21:00:00', count: 0, avg_ms: 0, failed: 0 }] }) })
    expect(screen.getByText(/Bu pencerede sorgu yok|No queries in this window/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="chart-series"]')).toBeNull()
  })

  it('telefon/tablet (< 1024): listeler kart düzeninde, sıralama seçici + yön düğmesi; bölüm seçici sekmeyi değiştirir', async () => {
    window.innerWidth = 390
    renderPanel({ data: RICH() })
    const list = screen.getByTestId('db-tables')
    expect(list).toHaveAttribute('data-layout', 'cards')
    expect(list.querySelector('[data-slot="table-body"]')).toBeNull()
    const cards = list.querySelectorAll('[data-slot="db-card"]')
    expect(cards).toHaveLength(25)
    const sortSel = within(list).getByRole('combobox', { name: /Sırala|Sort by/ })
    fireEvent.change(sortSel, { target: { value: 'table_name' } })
    expect(list.querySelectorAll('[data-slot="db-card"]')[0].textContent).toContain('audit_log')
    fireEvent.click(within(list).getByRole('button', { name: /Artan sırada|Ascending/ }))
    expect(list.querySelectorAll('[data-slot="db-card"]')[0].textContent).toContain('table_29')
    // bölüm seçici (telefonda sekme listesinin yerine)
    fireEvent.change(screen.getByRole('combobox', { name: /^(Bölüm|Section)$/ }), { target: { value: 'failed' } })
    expect(document.querySelector('[data-tab="failed"]')).toHaveAttribute('data-state', 'active')
    const failed = screen.getByTestId('db-failed')
    expect(failed.querySelector('[data-slot="db-card"]').textContent).toMatch(/Zaman aşımı|Timed out/)
    // kartta ayrıntı düğmesi de satırı adıyla taşır
    fireEvent.click(within(failed).getByRole('button', { name: /(Ayrıntı|Details) — UPDATE http_monitor/ }))
    expect(within(screen.getByRole('dialog')).getByTestId('db-sql-full').textContent).toBe(SQL_B)
    // genişleyince tablo düzenine döner
    await setWidth(1280)
    expect(screen.getByTestId('db-failed')).toHaveAttribute('data-layout', 'table')
  })

  it('yenilemede sekme, arama, sıralama ve sayfa KORUNUR (yeni veri nesnesi gelse de)', () => {
    const onRefresh = vi.fn()
    const { rerender } = render(<DbAnalyticsPanel data={RICH()} days={7} onRefresh={onRefresh} onDaysChange={() => {}} />)
    // tablolar: ada göre artan + 2. sayfa
    fireEvent.click(within(screen.getByTestId('db-tables')).getByRole('button', { name: /^(Tablo|Table)( [↑↓])?$/ }))
    fireEvent.click(within(screen.getByTestId('db-tables')).getByRole('button', { name: /^(Sonraki|Next)$/ }))
    expect(bodyRows('db-tables')[0].textContent).toContain('table_25')
    // SQL sekmesi + "en yavaş" görünümü + arama
    fireEvent.mouseDown(document.querySelector('[data-tab="sql"]'))
    fireEvent.click(within(document.querySelector('[data-tab="sql"]').closest('[data-slot="tabs"]')).getByRole('button', { name: /En Yavaş Sorgular|Slowest Queries/ }))
    fireEvent.change(within(screen.getByTestId('db-sql')).getByRole('searchbox'), { target: { value: 'update' } })
    fireEvent.click(screen.getByRole('button', { name: /^(Yenile|Refresh)$/ }))
    expect(onRefresh).toHaveBeenCalledTimes(1)
    rerender(<DbAnalyticsPanel data={RICH({ summary: { queries: 2000 } })} days={7} onRefresh={onRefresh} onDaysChange={() => {}} />)
    expect(kpi('queries').textContent).toMatch(/2[.,]000/)
    expect(document.querySelector('[data-tab="sql"]')).toHaveAttribute('data-state', 'active')
    expect(within(screen.getByTestId('db-sql')).getByRole('searchbox')).toHaveValue('update')
    expect(within(document.querySelector('[data-tab="sql"]').closest('[data-slot="tabs"]')).getByRole('button', { name: /En Yavaş Sorgular|Slowest Queries/ })).toHaveAttribute('aria-pressed', 'true')
    // tablolar sekmesine dönünce sıralama ve sayfa yerinde
    fireEvent.mouseDown(document.querySelector('[data-tab="tables"]'))
    const nameHead = within(screen.getByTestId('db-tables')).getByRole('button', { name: /^(Tablo|Table)( [↑↓])?$/ })
    expect(nameHead.closest('th')).toHaveAttribute('aria-sort', 'ascending')
    expect(bodyRows('db-tables')[0].textContent).toContain('table_25')
  })

  it('hata: bant role=alert + "Tekrar dene" yenilemeyi çağırır; ilk yüklemede iskelet, tazelemede düğme meşgul', async () => {
    const { onRefresh, rerender } = renderPanel({ data: null, error: 'boom' })
    const alert = screen.getByRole('alert')
    expect(alert.textContent).toMatch(/Veritabanı analitiği yüklenemedi|Couldn't load the database analytics/)
    expect(alert.textContent).toContain('boom')
    fireEvent.click(within(alert).getByRole('button', { name: /Tekrar dene|Try again/ }))
    expect(onRefresh).toHaveBeenCalledTimes(1)
    rerender(<DbAnalyticsPanel data={null} loading days={7} onRefresh={onRefresh} />)
    expect(screen.getByTestId('db-skeleton')).toBeInTheDocument()
    expect(within(screen.getByTestId('db-skeleton')).getByRole('status')).toBeInTheDocument()
    const btn = screen.getByRole('button', { name: /Yenileniyor|Refreshing/ })
    // 2026-09-28c ek-5: meşgul İŞARETLİ ama KİLİTLİ DEĞİL — zaman aşımsız asılı istek düğmeyi kalıcı kilitliyordu
    // (SystemHealth.loadDbAnalytics dbSeq korumalı: yeni tur eskisini bayat yapar)
    expect(btn).toBeEnabled()
    expect(btn).toHaveAttribute('aria-busy', 'true')
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
  })

  it('pencere seçimi onDaysChange çağırır; kırpılmış pencere uyarı bandı gösterir', () => {
    const { onDaysChange } = renderPanel({ data: DATA({ truncated: true }) })
    expect(screen.getByText(/50[.,]000/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /^30 (gün|days)$/i }))
    expect(onDaysChange).toHaveBeenCalledWith(30)
  })

  it('boş dizi yanıtı (eski uç/test vekili) çökmez: sıfırlar ve boş durumlar', () => {
    renderPanel({ data: [] })
    expect(kpi('queries').textContent).toContain('0')
    // sorgu yokken süre ve başarı oranı ölçülmemiştir: "0 ms" / "%100 başarılı" yazılmaz
    expect(kpi('avg').textContent).not.toMatch(/\d ms/)
    expect(kpi('slowest').textContent).not.toMatch(/\d ms/)
    expect(kpi('queries').textContent).not.toMatch(/başarılı|succeeded/)
    expect(screen.getByText(/Bu pencerede sorgu yok|No queries in this window/)).toBeInTheDocument()
  })
})

/**
 * SQL metni maskesi (2026-09-28c, B2): global olmayan görüntüleyiciye sunucu `sql` / `error` / `username` alanlarını HİÇ
 * göndermez, hata yerine `error_kind` + `sql_state` ve `sql_masked: true` ekler (DbAnalyticsService.maskSqlText). Arayüz
 * boş / "—" yerine açık durum yazar; ayrıntı ve kopyala düğmeleri metin yokken çizilmez; hata sınıfı rozeti kalır.
 */
const stripSql = (rows) => rows.map((r) => {
  const { sql, error, username, ...rest } = r   // eslint-disable-line no-unused-vars
  return error ? { ...rest, error_kind: 'timeout', sql_state: '57014' } : rest
})
const SQL_MASKED = () => {
  const d = DATA()
  return { ...d, sql_masked: true, top_sql: stripSql(d.top_sql), slowest_sql: stripSql(d.slowest_sql),
    recent_queries: stripSql(d.recent_queries), failed: stripSql(d.failed), top_users: stripSql(d.top_users) }
}

describe('DbAnalyticsPanel — SQL metni maskesi', () => {
  beforeEach(() => { try { localStorage.clear() } catch { /* */ }; window.innerWidth = 1024 })
  afterEach(() => { window.innerWidth = 1024 })

  it('bant + SQL hücresi "yalnız yöneticiler" durumu; ayrıntı / kopyala düğmesi YOK; sayılar kalır', () => {
    renderPanel({ data: SQL_MASKED() })
    expect(screen.getByText(/yalnız global yöneticilere ve denetçilere gösterilir|shown only to global admins and auditors/)).toBeInTheDocument()
    fireEvent.mouseDown(document.querySelector('[data-tab="sql"]'))
    const sql = screen.getByTestId('db-sql')
    expect(sql.querySelectorAll('[data-slot="db-masked"]')).toHaveLength(2)
    expect(within(sql).queryByRole('button', { name: /(Ayrıntı|Details) — / })).toBeNull()
    expect(within(sql).queryByRole('button', { name: /SQL'i kopyala|Copy SQL/ })).toBeNull()
    expect(sql.textContent).toMatch(/98[.,]000/)
    expect(sql.textContent).not.toContain('certificate_inventory')
  })

  it('başarısız: hata iletisi yerine durum; sınıf rozeti ve SQLSTATE sunucudan (error_kind / sql_state); kullanıcı adı gizli', () => {
    renderPanel({ data: SQL_MASKED() })
    fireEvent.click(kpi('failed'))
    const row = bodyRows('db-failed')[0]
    expect(row.querySelector('[data-err-kind="timeout"]')).not.toBeNull()
    expect(row.textContent).toMatch(/SQLSTATE 57014/)
    expect(row.textContent).not.toContain('canceling statement')
    expect(row.textContent).not.toContain('ops.user')
    expect(row.querySelectorAll('[data-slot="db-masked"]').length).toBeGreaterThanOrEqual(2)   // SQL + hata (kullanıcı sütunu dar kapta gizli olabilir)
    fireEvent.mouseDown(document.querySelector('[data-tab="users"]'))
    const users = screen.getByTestId('db-users')
    expect(users.textContent).not.toContain('admin')
    expect(users.querySelectorAll('[data-slot="db-masked"]')).toHaveLength(1)
    expect(users.textContent).toContain('400')
  })

  it('telefon kartları: metin yoksa kopyala / ayrıntı düğmeleri çizilmez', () => {
    window.innerWidth = 390
    renderPanel({ data: SQL_MASKED() })
    fireEvent.mouseDown(document.querySelector('[data-tab="sql"]'))
    const cards = screen.getByTestId('db-sql')
    expect(cards).toHaveAttribute('data-layout', 'cards')
    expect(within(cards).queryByRole('button', { name: /SQL'i kopyala|Copy SQL/ })).toBeNull()
    expect(within(cards).queryByRole('button', { name: /(Ayrıntı|Details)/ })).toBeNull()
    expect(cards.querySelectorAll('[data-slot="db-masked"]').length).toBeGreaterThan(0)
  })

  it('pozitif kontrol: maskesiz yükte bant ve "gizli" durumu YOK, ayrıntı düğmesi var', () => {
    renderPanel()
    expect(screen.queryByText(/shown only to global admins and auditors/)).toBeNull()
    fireEvent.mouseDown(document.querySelector('[data-tab="sql"]'))
    expect(screen.getByTestId('db-sql').querySelectorAll('[data-slot="db-masked"]')).toHaveLength(0)
    expect(within(screen.getByTestId('db-sql')).getAllByRole('button', { name: /(Ayrıntı|Details) — / }).length).toBeGreaterThan(0)
  })
})
