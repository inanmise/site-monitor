import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within, waitFor } from './test-utils.jsx'
import DbAnalyticsPanel from '../components/admin/DbAnalyticsPanel.jsx'

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
}))

/**
 * Sistem Sağlığı → Veritabanı Analitiği (2026-09-26 yeniden tasarım). Sunucu sözleşmesi
 * DbAnalyticsService.getOverview: summary / connections / table_sizes / top_tables / top_sql /
 * slowest_sql / recent_queries / failed / top_users / series / truncated / row_limit.
 * Fixture GERÇEK tel biçiminde (snake_case); gerçek kişi/kurum adı yok.
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
  ...over,
})

const renderPanel = (props = {}) => {
  const onRefresh = vi.fn(), onDaysChange = vi.fn()
  const utils = render(<DbAnalyticsPanel data={DATA()} days={7} onRefresh={onRefresh} onDaysChange={onDaysChange} {...props} />)
  return { ...utils, onRefresh, onDaysChange }
}
const bodyRows = (id) => [...screen.getByTestId(id).querySelectorAll('[data-slot="table-body"] > tr')]
const kpi = (key) => document.querySelector(`[data-kpi="${key}"]`)

describe('DbAnalyticsPanel', () => {
  beforeEach(() => { try { localStorage.clear() } catch { /* */ } })

  it('KPI kartları fixture değerlerini basar; bağlantı doluluğu ve başarı oranı rozetle tonlanır (sol şerit YOK)', () => {
    renderPanel()
    expect(kpi('size').textContent).toContain('1843 MB')
    expect(kpi('size').textContent).toMatch(/30 (tablo|tables)/)
    expect(kpi('connections').textContent).toContain('14 / 100')
    const usage = within(kpi('connections')).getByText(/%14 dolu|14% in use/)
    expect(usage).toHaveAttribute('data-tone', 'success')
    expect(kpi('queries').textContent).toMatch(/1[.,]284/)
    expect(within(kpi('queries')).getByText(/%99\.5 başarılı|99\.5% succeeded/)).toHaveAttribute('data-tone', 'success')
    expect(kpi('slowest').textContent).toMatch(/3[.,]810 ms/)
    expect(kpi('failed').textContent).toContain('7')
    for (const el of document.querySelectorAll('[data-kpi]')) {
      expect(el.className).not.toMatch(/border-l-|before:|shadow-\[inset/)
    }
    expect(screen.getByTestId('db-source')).toHaveAttribute('data-pgss', 'true')
    expect(screen.queryByText(/pg_stat_statements etkin değil|pg_stat_statements isn't enabled/)).toBeNull()
  })

  it('pg_stat_statements yoksa kaynak rozeti ve açıklayıcı bant görünür; en yavaşlar Playground sütunlarıyla çizilir', () => {
    renderPanel({ data: DATA({ summary: { pgss: false }, slowest_sql: [{ sql: SQL_B, duration_ms: 950, username: 'admin', time: '2026-09-26T07:00:00', rows: 2 }] }) })
    expect(screen.getByTestId('db-source')).toHaveAttribute('data-pgss', 'false')
    expect(screen.getByText(/pg_stat_statements etkin değil|pg_stat_statements isn't enabled/)).toBeInTheDocument()
    fireEvent.click(kpi('slowest'))
    const sql = screen.getByTestId('db-sql')
    expect(within(sql).getByRole('button', { name: /Süre \(ms\)|Duration \(ms\)/ })).toBeInTheDocument()
    expect(within(sql).queryByRole('button', { name: /^Calls|^Çağrı/ })).toBeNull()
  })

  it('tablolar: varsayılan toplam boyuta göre azalan, 25 satırlık sayfa + sayfa çubuğu; arama daraltır; başlık sıralaması yön değiştirir', () => {
    renderPanel()
    const rows = () => bodyRows('db-tables')
    expect(rows()).toHaveLength(25)
    expect(rows()[0].textContent).toContain('table_00')
    expect(within(screen.getByTestId('db-tables')).getByRole('navigation', { name: /Sayfalama|Pagination/ })).toBeInTheDocument()
    // kullanım (reads) yalnız top_tables'taki tabloya eklenir
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

  it('KPI tıklaması ilgili sekmeyi açar: Başarısız → hata listesi (hata metni görünür)', () => {
    renderPanel()
    const trigger = document.querySelector('[data-tab="failed"]')
    expect(trigger).toHaveAttribute('data-state', 'inactive')
    fireEvent.click(kpi('failed'))
    expect(trigger).toHaveAttribute('data-state', 'active')
    expect(bodyRows('db-failed')[0].textContent).toContain('canceling statement due to statement timeout')
  })

  it('SQL ayrıntısı: satırın düğmesi (adı SQL\'i taşır) pencereyi açar — tam SQL, kopyala düğmesi, ölçüler', () => {
    renderPanel()
    fireEvent.mouseDown(document.querySelector('[data-tab="sql"]'))
    const sql = screen.getByTestId('db-sql')
    fireEvent.click(within(sql).getByRole('button', { name: /(Ayrıntı|Details) — SELECT id, domain/ }))
    const dlg = screen.getByRole('dialog')
    expect(within(dlg).getByTestId('db-sql-full').textContent).toBe(SQL_A)
    expect(within(dlg).getByRole('button', { name: /^(SQL'i kopyala|Copy SQL)$/ })).toBeInTheDocument()
    expect(dlg.textContent).toMatch(/98[.,]000/)
  })

  it('bağlantı KPI\'ı ayrıntı penceresini açar (kullanım çubuğu + yanıt süresi)', () => {
    renderPanel()
    fireEvent.click(kpi('connections'))
    const dlg = screen.getByRole('dialog')
    expect(within(dlg).getByRole('progressbar')).toBeInTheDocument()
    expect(within(dlg).getByTestId('db-conn-detail').textContent).toContain('3 ms')
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
    const btn = screen.getByRole('button', { name: /Yenileniyor|Refreshing/ })
    expect(btn).toBeDisabled()
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
    expect(screen.getByText(/Bu pencerede sorgu yok|No queries in this window/)).toBeInTheDocument()
  })
})
