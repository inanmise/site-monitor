import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useState } from 'react'
import { render, screen, waitFor, fireEvent, act } from './test-utils.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

// SQL düzenleyicisi (Veritabanı Sorgu İzleme): canlı güvenlik denetimi (400 ms, iptal, sıra numarası), "denetlenemedi"
// asla "güvenli" değil, ihlal listesi satıra götürür, sunucu ihlalleri değer değişene dek önceliklidir, Tab girintisi,
// Esc+Tab çıkışı, karakter sayacı, MSSQL NOLOCK ipucu.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ api: withApiFallback({ monitoring: { dbQuerySqlCheck: vi.fn() } }) }))
import { api } from '../api/client'
import SqlEditor from '../components/dbquery/SqlEditor.jsx'

function Harness({ initial = '', dbType = 'POSTGRESQL', violations, onGuard, disabled }) {
  const [v, setV] = useState(initial)
  return <SqlEditor value={v} onChange={setV} dbType={dbType} violations={violations} onGuardChange={onGuard} disabled={disabled} />
}
const ta = () => document.querySelector('[data-slot="dbq-sql-editor"] textarea')
const guard = () => document.querySelector('[data-slot="dbq-sql-guard"]')

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.monitoring.dbQuerySqlCheck.mockResolvedValue({ success: true, data: { ok: true, violations: [], tables: ['public.orders'] } })
})

describe('SqlEditor — canlı güvenlik denetimi', () => {
  it('400 ms sonra denetler; yalnız sunucu ok:true derse yeşil + okunan tablolar', async () => {
    const onGuard = vi.fn()
    render(<Harness onGuard={onGuard} />)
    fireEvent.change(ta(), { target: { value: 'SELECT 1 FROM orders' } })
    expect(guard().getAttribute('data-status')).toBe('checking')
    expect(api.monitoring.dbQuerySqlCheck).not.toHaveBeenCalled()
    await waitFor(() => expect(guard().getAttribute('data-status')).toBe('ok'), { timeout: 2000 })
    expect(api.monitoring.dbQuerySqlCheck).toHaveBeenCalledTimes(1)
    expect(api.monitoring.dbQuerySqlCheck.mock.calls[0][0]).toBe('SELECT 1 FROM orders')
    expect(api.monitoring.dbQuerySqlCheck.mock.calls[0][1]).toBe('POSTGRESQL')
    expect(screen.getByText(/Güvenlik denetiminden geçti/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="dbq-sql-tables"]').textContent).toContain('public.orders')
    expect(onGuard).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'ok', ok: true, tables: ['public.orders'] }))
  })

  it('hızlı yazımda tek istek (debounce); eski istek iptal edilir', async () => {
    render(<Harness />)
    fireEvent.change(ta(), { target: { value: 'SELECT 1' } })
    fireEvent.change(ta(), { target: { value: 'SELECT 12' } })
    fireEvent.change(ta(), { target: { value: 'SELECT 123' } })
    await waitFor(() => expect(api.monitoring.dbQuerySqlCheck).toHaveBeenCalledTimes(1), { timeout: 2000 })
    expect(api.monitoring.dbQuerySqlCheck.mock.calls[0][0]).toBe('SELECT 123')
  })

  it('geç gelen eski yanıt düşürülür (sıra numarası)', async () => {
    let resolveFirst
    api.monitoring.dbQuerySqlCheck
      .mockImplementationOnce(() => new Promise((r) => { resolveFirst = r }))
      .mockResolvedValueOnce({ success: true, data: { ok: true, violations: [], tables: [] } })
    render(<Harness />)
    fireEvent.change(ta(), { target: { value: 'SELECT a' } })
    await waitFor(() => expect(api.monitoring.dbQuerySqlCheck).toHaveBeenCalledTimes(1), { timeout: 2000 })
    const firstSignal = api.monitoring.dbQuerySqlCheck.mock.calls[0][2].signal
    fireEvent.change(ta(), { target: { value: 'SELECT b' } })
    expect(firstSignal.aborted).toBe(true)
    await waitFor(() => expect(guard().getAttribute('data-status')).toBe('ok'), { timeout: 2000 })
    await act(async () => { resolveFirst({ success: true, data: { ok: false, violations: [{ code: 'SQL_NOT_SELECT', message: 'eski' }], tables: [] } }) })
    expect(guard().getAttribute('data-status')).toBe('ok')
  })

  it('ağ hatası → "denetlenemedi" (nötr), asla güvenli değil', async () => {
    api.monitoring.dbQuerySqlCheck.mockRejectedValueOnce(new Error('Sunucuya ulaşılamadı.'))
    const onGuard = vi.fn()
    render(<Harness onGuard={onGuard} />)
    fireEvent.change(ta(), { target: { value: 'SELECT 1' } })
    await waitFor(() => expect(guard().getAttribute('data-status')).toBe('unknown'), { timeout: 2000 })
    expect(document.querySelector('[data-slot="dbq-sql-ok"]')).toBeNull()
    expect(document.querySelector('[data-slot="dbq-sql-unknown"]').textContent).toContain('Sunucuya ulaşılamadı.')
    expect(onGuard).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'unknown', ok: null }))
  })

  it('ihlaller: başlık + sunucu iletisi + satır:sütun; tıklayınca imleç o konuma gider; satır cetvelinde işaret', async () => {
    api.monitoring.dbQuerySqlCheck.mockResolvedValue({ success: true, data: {
      ok: false, tables: [], violations: [{ code: 'SQL_FORBIDDEN_KEYWORD', token: 'DELETE', message: 'DELETE kullanılamaz', line: 2, column: 3 }],
    } })
    render(<Harness />)
    fireEvent.change(ta(), { target: { value: 'SELECT 1\n; DELETE FROM x' } })
    await waitFor(() => expect(guard().getAttribute('data-status')).toBe('rejected'), { timeout: 2000 })
    const v = document.querySelector('[data-slot="dbq-sql-violation"]')
    expect(v.getAttribute('data-code')).toBe('SQL_FORBIDDEN_KEYWORD')
    expect(v.textContent).toContain('İzin verilmeyen komut')
    expect(v.textContent).toContain('DELETE kullanılamaz')
    expect(v.textContent).toContain('Satır 2, sütun 3')
    expect(document.querySelector('[data-slot="dbq-sql-gutter"] [data-line="2"]').getAttribute('data-bad')).toBe('true')
    fireEvent.click(v.querySelector('button'))
    expect(document.activeElement).toBe(ta())
    expect(ta().selectionStart).toBe('SELECT 1\n'.length + 2)
  })

  it('bilinmeyen ihlal kodu genel metne düşer (ham anahtar basılmaz)', async () => {
    api.monitoring.dbQuerySqlCheck.mockResolvedValue({ success: true, data: { ok: false, tables: [], violations: [{ code: 'SQL_YENI_KURAL', message: 'x' }] } })
    render(<Harness />)
    fireEvent.change(ta(), { target: { value: 'SELECT 1' } })
    await waitFor(() => expect(guard().getAttribute('data-status')).toBe('rejected'), { timeout: 2000 })
    expect(guard().textContent).toContain('Sorgu kurala uymuyor')
    expect(guard().textContent).not.toContain('dbq.guard.')
  })

  it('sunucunun kayıttaki ihlalleri değer değişene dek gösterilir; düzenleyince canlı denetim devralır', async () => {
    const served = [{ code: 'SQL_MSSQL_NOLOCK_REQUIRED', token: 'dbo.Orders', message: 'dbo.Orders için WITH (NOLOCK) yok', line: 1, column: 15 }]
    render(<Harness initial="SELECT * FROM dbo.Orders" dbType="MSSQL" violations={served} />)
    expect(guard().getAttribute('data-status')).toBe('rejected')
    expect(guard().textContent).toContain('dbo.Orders için WITH (NOLOCK) yok')
    fireEvent.change(ta(), { target: { value: 'SELECT * FROM dbo.Orders WITH (NOLOCK)' } })
    await waitFor(() => expect(guard().getAttribute('data-status')).toBe('ok'), { timeout: 2000 })
  })

  it('boş sorgu istek atmaz; devre dışıyken denetim yok', async () => {
    render(<Harness disabled initial="SELECT 1" />)
    await new Promise((r) => setTimeout(r, 500))
    expect(api.monitoring.dbQuerySqlCheck).not.toHaveBeenCalled()
    expect(ta()).toBeDisabled()
  })
})

describe('SqlEditor — düzenleme', () => {
  it('Tab iki boşluk girintiler; çok satırlı seçimde her satır; Shift+Tab azaltır', () => {
    render(<Harness initial={'SELECT a\nFROM t'} />)
    const el = ta()
    el.setSelectionRange(0, 0)
    fireEvent.keyDown(el, { key: 'Tab' })
    expect(el.value).toBe('  SELECT a\nFROM t')
    el.setSelectionRange(0, el.value.length)
    fireEvent.keyDown(el, { key: 'Tab' })
    expect(el.value).toBe('    SELECT a\n  FROM t')
    el.setSelectionRange(0, el.value.length)
    fireEvent.keyDown(el, { key: 'Tab', shiftKey: true })
    expect(el.value).toBe('  SELECT a\nFROM t')
  })

  it('Esc sonrası Tab engellenmez (alandan çıkılır)', () => {
    render(<Harness initial="SELECT 1" />)
    const el = ta()
    fireEvent.keyDown(el, { key: 'Escape' })
    const ev = fireEvent.keyDown(el, { key: 'Tab' })
    expect(ev).toBe(true)   // varsayılan engellenmedi
    expect(el.value).toBe('SELECT 1')
  })

  it('Enter bulunulan satırın girintisini korur', () => {
    render(<Harness initial={'  SELECT a'} />)
    const el = ta()
    el.setSelectionRange(el.value.length, el.value.length)
    fireEvent.keyDown(el, { key: 'Enter' })
    expect(el.value).toBe('  SELECT a\n  ')
  })

  it('karakter sayacı ve 10.000 sınırı aşımı', () => {
    render(<Harness initial={'S'.repeat(10_001)} />)
    const c = document.querySelector('[data-slot="dbq-sql-count"]')
    expect(c.getAttribute('data-over')).toBe('true')
    expect(c.textContent).toContain('10.001')
    expect(screen.getByText(/en çok 10\.000 karakter olabilir/)).toBeInTheDocument()
  })

  it('satır cetveli satır sayısını izler; MSSQL\'de NOLOCK ipucu, PostgreSQL\'de yok', () => {
    const { rerender } = render(<SqlEditor value={'a\nb\nc'} onChange={() => {}} dbType="MSSQL" />)
    expect(document.querySelectorAll('[data-slot="dbq-sql-gutter"] [data-line]')).toHaveLength(3)
    expect(document.querySelector('[data-slot="dbq-sql-nolock-example"]').textContent).toContain('WITH (NOLOCK)')
    rerender(<SqlEditor value="a" onChange={() => {}} dbType="POSTGRESQL" />)
    expect(document.querySelector('[data-slot="dbq-sql-nolock-example"]')).toBeNull()
    expect(document.querySelector('[data-field="sql_text"]')).not.toBeNull()
  })

  it('alan hatası düzenleyicinin altında', () => {
    render(<SqlEditor value="" onChange={() => {}} dbType="ORACLE" fieldError="Sorgu zorunludur" />)
    expect(screen.getByText('Sorgu zorunludur')).toBeInTheDocument()
    expect(ta()).toHaveAttribute('aria-invalid', 'true')
  })
})
