import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'

/**
 * SQL Playground çizim maliyeti (2026-11-17 performans turu).
 *  - Şema gezgini (yüzlerce tablo) editöre yazarken yeniden çizilmez: props sabit + memo.
 *  - Sorgu çalışırken sayfa 100 ms'de bir yeniden çizilmez: geçen süreyi ResultsPanel'deki yaprak bileşen sayar.
 *  - İmleç hareketi seçim değişmedikçe sayfayı çizdirmez.
 * Düzeltmeden önce: her tuşta gezgin 1 kez, çalışma sırasında sayfa saniyede ~10 kez çiziliyordu.
 */
const explorer = vi.hoisted(() => ({ renders: 0 }))
vi.mock('../components/admin/sql/SchemaExplorer.jsx', async () => {
  const React = await import('react')
  function CountingExplorer() {
    explorer.renders += 1
    return React.createElement('div', { 'data-testid': 'explorer' })
  }
  return { default: React.memo(CountingExplorer) }
})

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
      sqlRelations: vi.fn(),
      getDatabaseInfo: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import SqlPlayground from '../components/admin/SqlPlayground.jsx'
import { RunningElapsed } from '../components/admin/sql/ResultsPanel.jsx'
import { formatCompact, relativeFrom } from '../components/admin/sql/sqlUtils.js'

function editor() { return screen.getByRole('textbox', { name: /SQL query|SQL sorgusu/ }) }

describe('SQL Playground çizim maliyeti', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    explorer.renders = 0
    api.admin.sqlListTables.mockResolvedValue({ success: true, data: Array.from({ length: 180 }, (_, i) => ({ table_name: `t_${i}`, live_rows: i * 1000 })) })
    api.admin.sqlSamples.mockResolvedValue({ success: true, data: [] })
    api.admin.sqlHistory.mockResolvedValue({ success: true, data: [] })
    api.admin.sqlListColumns.mockResolvedValue({ success: true, data: [] })
    api.admin.sqlRelations.mockResolvedValue({ success: true, data: { tables: [], edges: [] } })
    api.admin.getDatabaseInfo.mockResolvedValue({ success: true, data: { version: 'PostgreSQL 16.2' } })
  })
  afterEach(() => { vi.restoreAllMocks() })

  it('editöre yazmak şema gezginini yeniden çizdirmez', async () => {
    render(<SqlPlayground />)
    const ed = await screen.findByRole('textbox', { name: /SQL query|SQL sorgusu/ })
    await waitFor(() => expect(api.admin.sqlRelations).toHaveBeenCalled())
    await waitFor(() => expect(screen.getAllByTestId('explorer').length).toBeGreaterThan(0))
    const before = explorer.renders
    for (const v of ['S', 'SE', 'SEL', 'SELE', 'SELEC', 'SELECT', 'SELECT 1']) {
      fireEvent.change(ed, { target: { value: v } })
    }
    await act(async () => { await new Promise((r) => setTimeout(r, 20)) })
    expect(explorer.renders).toBe(before)
  })

  it('sorgu çalışırken sayfa her 100 ms yeniden çizilmez; geçen süre yine güncellenir', async () => {
    let release
    api.admin.sqlExecute.mockImplementation(() => new Promise((res) => { release = () => res({ success: true, ok: true, rowCount: 0, durationMs: 5, rows: [] }) }))
    render(<SqlPlayground />)
    const ed = await screen.findByRole('textbox', { name: /SQL query|SQL sorgusu/ })
    await waitFor(() => expect(api.admin.sqlRelations).toHaveBeenCalled())
    fireEvent.change(ed, { target: { value: 'SELECT pg_sleep(1)' } })
    fireEvent.keyDown(ed, { key: 'Enter', ctrlKey: true })
    await waitFor(() => expect(api.admin.sqlExecute).toHaveBeenCalled())
    await act(async () => { await new Promise((r) => setTimeout(r, 50)) })
    const atStart = explorer.renders
    const label = () => document.body.textContent.match(/(?:Running…|Çalışıyor…) ([\d.,]+ m?s)/)?.[1]
    const first = label()
    expect(first).toBeTruthy()
    await act(async () => { await new Promise((r) => setTimeout(r, 650)) })
    // Eskiden her tik SqlPlayground'u (ve satır içi props yüzünden gezgini) çiziyordu: 650 ms'de ~6 gezgin çizimi.
    expect(explorer.renders).toBe(atStart)
    expect(editor()).toBe(ed)
    expect(label()).not.toBe(first)
    await act(async () => { release() })
  })

  it('RunningElapsed kendi zamanlayıcısıyla sayar, başlangıç değişince sıfırdan başlar', async () => {
    const t0 = Date.now()
    const seen = []
    const { rerender } = render(<RunningElapsed startedAt={t0} render={(ms) => { seen.push(ms); return <span data-testid="el">{ms}</span> }} />)
    await act(async () => { await new Promise((r) => setTimeout(r, 260)) })
    const last = seen.at(-1)
    expect(last).toBeGreaterThanOrEqual(200)
    rerender(<RunningElapsed startedAt={Date.now()} render={(ms) => { seen.push(ms); return <span data-testid="el">{ms}</span> }} />)
    await act(async () => { await new Promise((r) => setTimeout(r, 20)) })
    expect(seen.at(-1)).toBeLessThan(last)
  })

  it('Intl biçimleyicileri yerel ayara göre önbellekten gelir (aynı sonuç)', () => {
    const spy = vi.spyOn(Intl, 'NumberFormat')
    const a = formatCompact(1234, 'tr-TR')
    const b = formatCompact(5678, 'tr-TR')
    expect(a).toBe(new Intl.NumberFormat('tr-TR', { notation: 'compact', maximumFractionDigits: 1 }).format(1234))
    expect(b).toBeTruthy()
    // İki çağrı + beklenen değeri üreten tek çağrı: önbellek yoksa 3'ten fazla kurulurdu (ilk çağrı önbelleği doldurmuş olabilir).
    expect(spy.mock.calls.length).toBeLessThanOrEqual(2)
    const r1 = relativeFrom('2026-01-01T00:00:00Z', 'en-GB', Date.parse('2026-01-01T00:05:00Z'))
    expect(r1).toMatch(/5 min/)
  })
})
