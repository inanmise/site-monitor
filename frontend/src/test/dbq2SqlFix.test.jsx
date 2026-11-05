import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useState } from 'react'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

// dbq2 B2 (2026-11-05): tek tıkla güvenli düzeltme — ret bandında "Düzelt" (SQL Server NOLOCK), temiz sorguda "Düzeltme
// önerileri" (satır sınırı, SELECT * yerine kolonlar); önizleme (önce / sonra farkı) + Uygula; satır sayısı kuralı uyarısı;
// eski sunucu (404) → "desteklemiyor" (istemci önerisi yine çalışır); `fix` verilmezse / salt okunurda hiçbir şey yok.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ api: withApiFallback({ monitoring: { dbQuerySqlCheck: vi.fn(), dbQuerySqlFix: vi.fn() } }) }))
import { api } from '../api/client'
import SqlEditor from '../components/dbquery/SqlEditor.jsx'

const NOLOCK_SQL = 'SELECT COUNT(*) AS bekleyen\nFROM dbo.Orders\nWHERE status = 0'
const FIXED_SQL = 'SELECT COUNT(*) AS bekleyen\nFROM dbo.Orders WITH (NOLOCK)\nWHERE status = 0'

function Harness({ initial, dbType = 'MSSQL', fix = { columns: [], rowLimit: 1000, rules: [] }, disabled, onValue }) {
  const [v, setV] = useState(initial)
  return <SqlEditor value={v} onChange={(x) => { setV(x); onValue?.(x) }} dbType={dbType} fix={fix} disabled={disabled} />
}
const guard = () => document.querySelector('[data-slot="dbq-sql-guard"]')

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
})

describe('SqlEditor — güvenli düzeltme', () => {
  it('NOLOCK reddinde "Düzelt" → önizleme farkı → Uygula sorguyu değiştirir (kaydetmez)', async () => {
    api.monitoring.dbQuerySqlCheck.mockImplementation(async (sql) => (sql.includes('NOLOCK')
      ? { success: true, data: { ok: true, violations: [], tables: ['dbo.Orders'] } }
      : { success: true, data: { ok: false, violations: [{ code: 'SQL_MSSQL_NOLOCK_REQUIRED', token: 'dbo.Orders', line: 2, column: 6, message: 'NOLOCK gerekli' }], tables: [] } }))
    api.monitoring.dbQuerySqlFix.mockResolvedValue({ success: true, data: { fixes: [
      { code: 'ADD_NOLOCK', sql: FIXED_SQL, changed: ['dbo.Orders'], note: null },
    ] } })
    const onValue = vi.fn()
    render(<Harness initial={NOLOCK_SQL} onValue={onValue} />)
    const fixBtn = await screen.findByRole('button', { name: 'Düzelt' }, { timeout: 2000 })
    expect(guard().getAttribute('data-status')).toBe('rejected')
    fireEvent.click(fixBtn)
    const item = await waitFor(() => { const i = document.querySelector('[data-slot="dbq-fix-item"][data-code="ADD_NOLOCK"]'); if (!i) throw new Error('yok'); return i }, { timeout: 3000 })
    expect(api.monitoring.dbQuerySqlFix).toHaveBeenCalledWith({ sql: NOLOCK_SQL, db_type: 'MSSQL', row_limit: 1000 })
    expect(item.querySelector('[data-slot="dbq-fix-changed"]').textContent).toContain('dbo.Orders')
    const diff = item.querySelector('[data-slot="dbq-fix-diff"]')
    expect([...diff.querySelectorAll('[data-type="del"]')].map((d) => d.textContent)).toEqual(['−Çıkan satır: FROM dbo.Orders'])
    expect([...diff.querySelectorAll('[data-type="add"]')].map((d) => d.textContent)).toEqual(['+Eklenen satır: FROM dbo.Orders WITH (NOLOCK)'])
    fireEvent.click(within(item).getByRole('button', { name: /Uygula/ }))
    expect(onValue).toHaveBeenCalledWith(FIXED_SQL)
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-fix"]')).toBeNull())
    await waitFor(() => expect(guard().getAttribute('data-status')).toBe('ok'), { timeout: 2000 })
    expect(document.querySelector('[data-slot="dbq-sql-fix-applied"]').textContent).toContain('WITH (NOLOCK)')
  })

  it('temiz sorgu: "Düzeltme önerileri" — satır sınırı + satır sayısı kuralı uyarısı + SELECT * yerine kolonlar', async () => {
    api.monitoring.dbQuerySqlCheck.mockResolvedValue({ success: true, data: { ok: true, violations: [], tables: ['orders'] } })
    api.monitoring.dbQuerySqlFix.mockResolvedValue({ success: true, data: { fixes: [
      { code: 'ADD_ROW_LIMIT', sql: 'SELECT * FROM orders WHERE status = 0\nLIMIT 1000', changed: ['LIMIT 1000'] },
    ] } })
    const onValue = vi.fn()
    render(<Harness initial="SELECT * FROM orders WHERE status = 0" dbType="POSTGRESQL" onValue={onValue}
      fix={{ columns: [{ name: 'id', type: 'int4' }, { name: 'status', type: 'int4' }], rowLimit: 1000, rules: [{ kind: 'ROW_COUNT' }] }} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Düzeltme önerileri' }, { timeout: 2000 }))
    const limit = await waitFor(() => { const i = document.querySelector('[data-slot="dbq-fix-item"][data-code="ADD_ROW_LIMIT"]'); if (!i) throw new Error('yok'); return i })
    expect(limit.querySelector('[data-slot="dbq-fix-warning"][data-code="ROW_COUNT_AFFECTED"]')).not.toBeNull()
    const cols = document.querySelector('[data-slot="dbq-fix-item"][data-code="SELECT_COLUMNS"]')
    expect(cols).not.toBeNull()
    fireEvent.click(within(cols).getByRole('button', { name: /Uygula/ }))
    expect(onValue).toHaveBeenCalledWith('SELECT id, status FROM orders WHERE status = 0')
  })

  it('eski sunucu (404): "desteklemiyor"; istemci önerisi yine sunulur; öneri yoksa "otomatik düzeltme yok"', async () => {
    api.monitoring.dbQuerySqlCheck.mockResolvedValue({ success: true, data: { ok: true, violations: [], tables: ['orders'] } })
    api.monitoring.dbQuerySqlFix.mockResolvedValue({ success: false, status: 404, code: 'NOT_FOUND', error: 'Bulunamadı' })
    render(<Harness initial="SELECT * FROM orders" dbType="POSTGRESQL" fix={{ columns: [{ name: 'id' }], rowLimit: 1000, rules: [] }} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Düzeltme önerileri' }, { timeout: 2000 }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-fix-unsupported"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="dbq-fix-item"][data-code="SELECT_COLUMNS"]')).not.toBeNull()
  })

  it('sunucu okunamadı: hata + tekrar dene; öneri yok → "otomatik düzeltme yok"', async () => {
    api.monitoring.dbQuerySqlCheck.mockResolvedValue({ success: true, data: { ok: true, violations: [], tables: ['t'] } })
    api.monitoring.dbQuerySqlFix.mockResolvedValueOnce({ success: false, status: 503, error: 'Sunucu meşgul' })
      .mockResolvedValueOnce({ success: true, data: { fixes: [] } })
    render(<Harness initial="SELECT id FROM t" dbType="POSTGRESQL" />)
    fireEvent.click(await screen.findByRole('button', { name: 'Düzeltme önerileri' }, { timeout: 2000 }))
    const err = await waitFor(() => { const e = document.querySelector('[data-slot="dbq-fix-error"]'); if (!e) throw new Error('yok'); return e })
    expect(err.textContent).toContain('Sunucu meşgul')
    fireEvent.click(screen.getByRole('button', { name: 'Tekrar dene' }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-fix-none"]')).not.toBeNull())
  })

  it('başka ret kodunda "Düzelt" yok; tek satırlık özette öneri düğmesi yok; `fix` yoksa / salt okunurda hiç yok', async () => {
    api.monitoring.dbQuerySqlCheck.mockResolvedValue({ success: true, data: { ok: false, violations: [{ code: 'SQL_NOT_SELECT', line: 1, column: 1 }], tables: [] } })
    const { unmount } = render(<Harness initial="DELETE FROM t" dbType="POSTGRESQL" />)
    await waitFor(() => expect(guard().getAttribute('data-status')).toBe('rejected'), { timeout: 2000 })
    expect(screen.queryByRole('button', { name: 'Düzelt' })).toBeNull()
    unmount()
    api.monitoring.dbQuerySqlCheck.mockResolvedValue({ success: true, data: { ok: true, violations: [], tables: ['t'] } })
    const r2 = render(<Harness initial="SELECT COUNT(*) FROM t" dbType="POSTGRESQL" />)
    await waitFor(() => expect(guard().getAttribute('data-status')).toBe('ok'), { timeout: 2000 })
    expect(screen.queryByRole('button', { name: 'Düzeltme önerileri' })).toBeNull()
    r2.unmount()
    const r3 = render(<Harness initial="SELECT id FROM t" dbType="POSTGRESQL" fix={null} />)
    await waitFor(() => expect(guard().getAttribute('data-status')).toBe('ok'), { timeout: 2000 })
    expect(screen.queryByRole('button', { name: 'Düzeltme önerileri' })).toBeNull()
    r3.unmount()
    render(<Harness initial="SELECT id FROM t" dbType="POSTGRESQL" disabled />)
    expect(screen.queryByRole('button', { name: 'Düzeltme önerileri' })).toBeNull()
    expect(api.monitoring.dbQuerySqlFix).not.toHaveBeenCalled()
  })
})
