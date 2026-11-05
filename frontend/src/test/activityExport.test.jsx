import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { EN } from '../i18n/en.js'
import { TR } from '../i18n/tr.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ exportActivity: vi.fn() }),
}))
const downloads = []
vi.mock('../utils/csvExport.js', async (orig) => ({
  ...(await orig()),
  downloadBlob: vi.fn((name, blob) => { downloads.push({ name, blob }); return true }),
}))
import { api } from '../api/client'
import ActivityExportMenu from '../components/activity/ActivityExportMenu.jsx'
import { EXCEL_COLUMNS, excelRow, excelSheet, istanbulTime, unwrapExportJson } from '../components/activity/activityExport.js'

/** Aktivite tam dışa aktarımı (2026-11-05, E) — Excel modeli + menü (CSV sunucudan, Excel JSON'dan). */
const tr = (key, ...args) => {
  let s = TR[key] ?? key
  args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a)) })
  return s
}
const ROW = {
  id: 1, activity_time: '2026-11-05T08:00:00', monitor_type: 'HTTP', monitor_name: 'web', target: 'https://x.com', team_name: 'Ödeme',
  action: 'SCHEDULED_CHECK', result_status: 'TIMEOUT', result_summary: 'zaman aşımı', error_message: '=HYPERLINK("x")',
  failure_reason: 'HTTP_STATUS', http_status: 503, response_ms: 1200, actor: 'scheduler', request_id: 'r-1', alert_event_id: 77,
}

beforeEach(() => { vi.clearAllMocks(); downloads.length = 0; localStorage.setItem('site-monitor-lang', 'en') })

describe('activityExport modeli', () => {
  it('satır → hücreler: İstanbul zamanı, yerelleşmiş eylem / durum / neden, sayılar sayı; formül metni değer kalır', () => {
    const cells = excelRow(ROW, tr, 'tr')
    expect(cells).toHaveLength(EXCEL_COLUMNS.length)
    expect(cells[0]).toBe('05.11.2026 11:00:00')
    expect(cells[5]).toBe(TR['act.ac.SCHEDULED_CHECK'])
    expect(cells[6]).toBe(TR['act.st.TIMEOUT'])
    expect(cells[8]).toBe('=HYPERLINK("x")')   // xlsxWriter formül yazmaz (inline string)
    expect(cells[9]).toBe('HTTP 503 döndü')
    expect(cells[10]).toBe(503)
    expect(cells[14]).toBe(77)
    const sheet = excelSheet([ROW], tr, 'tr')
    expect(sheet.rows[0]).toEqual(EXCEL_COLUMNS.map(([k]) => TR['act.ex.col.' + k]))
    expect(sheet.freezeHeader).toBe(true)
    expect(istanbulTime('bozuk')).toBe('bozuk')
  })

  it('JSON gövdesi: be2 biçimi (sütun adları + satır dizileri, İstanbul yerel zaman); nesne satırları da; tanınmayan → null', () => {
    const body = unwrapExportJson({ success: true, data: {
      columns: ['time', 'monitor_type', 'monitor_name', 'http_status', 'alert_event_id', 'id'], time_zone: 'Europe/Istanbul',
      rows: [['2026-11-05 11:00:00', 'HTTP', 'web', 503, 77, 1]], row_count: 1, max_rows: 20000, truncated: true } })
    expect(body.truncated).toBe(true)
    expect(body.maxRows).toBe(20000)
    expect(body.rows[0]).toEqual({ time_local: '2026-11-05 11:00:00', monitor_type: 'HTTP', monitor_name: 'web', http_status: 503, alert_event_id: 77, id: 1 })
    // Sunucunun yerel zamanı yeniden dönüştürülmez (3 saat kayma olmaz)
    expect(excelRow(body.rows[0], tr, 'tr')[0]).toBe('2026-11-05 11:00:00')
    expect(unwrapExportJson({ success: true, data: [ROW], truncated: true })).toMatchObject({ rows: [ROW], truncated: true })
    expect(unwrapExportJson({ success: true, rows: [ROW] }).truncated).toBe(false)
    expect(unwrapExportJson({ success: true, data: {} })).toBeNull()
  })
})

describe('ActivityExportMenu', () => {
  const open = async () => pressMenuTrigger(await screen.findByRole('button', { name: new RegExp(EN['act.ex.button']) }))

  it('CSV: süzgeçlerle sunucudan; tavan aşıldıysa onTruncated(csv, 50 000)', async () => {
    api.exportActivity.mockResolvedValue({ success: true, name: 'a.csv', truncated: true, maxRows: 50000 })
    const onTruncated = vi.fn()
    render(<ActivityExportMenu params={{ type: 'HTTP', status: 'ERROR,TIMEOUT' }} onTruncated={onTruncated} />)
    await open()
    fireEvent.click(await screen.findByRole('menuitem', { name: /CSV/ }))
    await waitFor(() => expect(api.exportActivity).toHaveBeenCalledWith({ type: 'HTTP', status: 'ERROR,TIMEOUT' }, 'csv'))
    await waitFor(() => expect(onTruncated).toHaveBeenCalledWith('csv', 50000))
  })

  it('Excel: JSON okunur, .xlsx indirilir; hata sunucunun metniyle (429), indirme yok', async () => {
    api.exportActivity.mockResolvedValueOnce({ success: true, data: { columns: ['time', 'monitor_type', 'monitor_name'],
      rows: [['2026-11-05 11:00:00', 'HTTP', 'web']], row_count: 1, max_rows: 20000, truncated: false } })
    render(<ActivityExportMenu params={{ type: '' }} onTruncated={vi.fn()} />)
    await open()
    fireEvent.click(await screen.findByRole('menuitem', { name: /Excel/ }))
    await waitFor(() => expect(downloads).toHaveLength(1))
    expect(api.exportActivity).toHaveBeenCalledWith({ type: '' }, 'json')
    expect(downloads[0].name).toMatch(/^aktivite-\d{8}-\d{4}\.xlsx$/)
    expect(downloads[0].blob.size).toBeGreaterThan(200)
    api.exportActivity.mockResolvedValueOnce({ success: false, status: 429, code: 'RATE_LIMITED',
      error: 'Too many exports in a short time — wait 40 seconds and try again.' })
    await open()
    fireEvent.click(await screen.findByRole('menuitem', { name: /Excel/ }))
    expect(await screen.findByText('Too many exports in a short time — wait 40 seconds and try again.')).toBeInTheDocument()
    expect(downloads).toHaveLength(1)
  })
})
