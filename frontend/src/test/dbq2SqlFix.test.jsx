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
import SqlEditor, { SQL_CHECK_BUSY_RETRIES } from '../components/dbquery/SqlEditor.jsx'

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

describe('SqlEditor — sunucu "kısa süre bekle" (429, be1)', () => {
  it('canlı denetim 429 DBQUERY_BUSY: hata DEĞİL — nötr "beklemede" notu (asla yeşil / ret değil), bekleme sonrası kendiliğinden yeniden dener', async () => {
    api.monitoring.dbQuerySqlCheck
      .mockResolvedValueOnce({ success: false, status: 429, code: 'DBQUERY_BUSY', error: 'Meşgul', retry_after_seconds: 1 })
      .mockResolvedValue({ success: true, data: { ok: true, violations: [], tables: ['orders'] } })
    const states = []
    function Spy() {
      const [v, setV] = useState('SELECT id FROM orders')
      return <SqlEditor value={v} onChange={setV} dbType="POSTGRESQL" onGuardChange={(g) => states.push(g.status)} />
    }
    render(<Spy />)
    const note = await waitFor(() => { const n = document.querySelector('[data-slot="dbq-sql-busy"]'); if (!n) throw new Error('yok'); return n }, { timeout: 2000 })
    expect(note.textContent).toContain('1 sn')
    expect(guard().getAttribute('data-status')).toBe('checking')
    expect(document.querySelector('[data-slot="dbq-sql-unknown"]')).toBeNull()
    expect(document.querySelector('[data-slot="dbq-sql-ok"]')).toBeNull()
    await waitFor(() => expect(guard().getAttribute('data-status')).toBe('ok'), { timeout: 4000 })
    expect(document.querySelector('[data-slot="dbq-sql-busy"]')).toBeNull()
    expect(api.monitoring.dbQuerySqlCheck).toHaveBeenCalledTimes(2)
    expect(states).not.toContain('unknown')
    expect(states).not.toContain('rejected')
  })

  it('üst üste 429 sınırsız denenmez: SQL_CHECK_BUSY_RETRIES sonra "denetlenemedi" + elle yeniden dene', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      api.monitoring.dbQuerySqlCheck.mockResolvedValue({ success: false, status: 429, code: 'RATE_LIMITED', error: 'Dakikalık sınır', retry_after_seconds: 1 })
      render(<Harness initial="SELECT id FROM orders" dbType="POSTGRESQL" fix={null} />)
      for (let i = 0; i < 12; i++) await vi.advanceTimersByTimeAsync(1500)
      await waitFor(() => expect(guard().getAttribute('data-status')).toBe('unknown'))
      expect(api.monitoring.dbQuerySqlCheck).toHaveBeenCalledTimes(SQL_CHECK_BUSY_RETRIES + 1)
      expect(document.querySelector('[data-slot="dbq-sql-recheck"]')).not.toBeNull()
      await vi.advanceTimersByTimeAsync(5000)
      expect(api.monitoring.dbQuerySqlCheck).toHaveBeenCalledTimes(SQL_CHECK_BUSY_RETRIES + 1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('düzeltme önerileri 429: bilgi notu + kendiliğinden yeniden istek ("otomatik düzeltme yok" DEĞİL); db_type her zaman gider', async () => {
    api.monitoring.dbQuerySqlCheck.mockResolvedValue({ success: true, data: { ok: true, violations: [], tables: ['orders'] } })
    api.monitoring.dbQuerySqlFix
      .mockResolvedValueOnce({ success: false, status: 429, code: 'RATE_LIMITED', error: 'Dakikalık sınır', retry_after_seconds: 1 })
      .mockResolvedValue({ success: true, data: { fixes: [{ code: 'ADD_ROW_LIMIT', sql: 'SELECT id FROM orders\nLIMIT 1000', changed: ['LIMIT 1000'] }],
        skipped: [{ code: 'ADD_NOLOCK', reason: 'NOT_MSSQL' }] } })
    render(<Harness initial="SELECT id FROM orders" dbType="POSTGRESQL" />)
    fireEvent.click(await screen.findByRole('button', { name: /Düzeltme önerileri/ }, { timeout: 2000 }))
    const busy = await waitFor(() => { const b = document.querySelector('[data-slot="dbq-fix-busy"]'); if (!b) throw new Error('yok'); return b })
    expect(busy.textContent).toContain('1 sn')
    expect(document.querySelector('[data-slot="dbq-fix"]').getAttribute('data-state')).toBe('busy')
    expect(document.querySelector('[data-slot="dbq-fix-none"]')).toBeNull()
    expect(document.querySelector('[data-slot="dbq-fix-error"]')).toBeNull()
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-fix-item"][data-code="ADD_ROW_LIMIT"]')).not.toBeNull(), { timeout: 4000 })
    expect(api.monitoring.dbQuerySqlFix).toHaveBeenCalledTimes(2)
    for (const call of api.monitoring.dbQuerySqlFix.mock.calls) expect(call[0].db_type).toBe('POSTGRESQL')
  })

  it('yeni önerilmeme nedenleri CHECK_TIMEOUT / SINGLE_ROW okunur metinle', async () => {
    api.monitoring.dbQuerySqlCheck.mockResolvedValue({ success: true, data: { ok: true, violations: [], tables: ['orders'] } })
    api.monitoring.dbQuerySqlFix.mockResolvedValue({ success: true, data: { fixes: [], skipped: [
      { code: 'ADD_ROW_LIMIT', reason: 'SINGLE_ROW' }, { code: 'ADD_NOLOCK', reason: 'CHECK_TIMEOUT' },
    ] } })
    render(<Harness initial="SELECT id FROM orders" dbType="POSTGRESQL" />)
    fireEvent.click(await screen.findByRole('button', { name: /Düzeltme önerileri/ }, { timeout: 2000 }))
    const sk = await waitFor(() => { const e = document.querySelector('[data-slot="dbq-fix-skipped"]'); if (!e) throw new Error('yok'); return e })
    expect(sk.querySelector('[data-reason="SINGLE_ROW"]').textContent).toContain('tek satır')
    expect(sk.querySelector('[data-reason="CHECK_TIMEOUT"]').textContent).toContain('zaman sınırına')
  })
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
    // istemci önerisi sunucunun denetiminden geçmedi — uyarı her zaman (O3)
    expect(cols.querySelector('[data-slot="dbq-fix-warning"][data-code="COLUMNS_FROM_TEST"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="dbq-fix-diff-stats"]').textContent).toMatch(/1 satır (eklendi|çıkarıldı)/)
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
