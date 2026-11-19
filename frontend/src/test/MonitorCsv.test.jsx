import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

vi.mock('../api/client', () => ({
  api: {
    monitoring: {
      csvExport: vi.fn(),
      csvImport: vi.fn(),
    },
  },
}))

import { api } from '../api/client'
import MonitorCsvImportDialog from '../components/monitoring/csv/MonitorCsvImportDialog.jsx'
import MonitorCsvMenu from '../components/monitoring/csv/MonitorCsvMenu.jsx'

const LONG_NAME = 'Kurumsal İnternet Şubesi Müşteri Girişi Sağlık Kontrolü (Ana Veri Merkezi / İstanbul) — çok uzun ad'
const LONG_TEAM = 'Dijital Kanallar Uygulama Geliştirme ve Operasyon Takımı (Bölge Müdürlüğü)'

const previewData = (over = {}) => ({
  type: 'http', dry_run: true, columns: ['name', 'team'], budget_exceeded: false,
  counts: { create: 1, update: 1, unchanged: 1, error: 1 },
  rows: [
    { row: 2, name: LONG_NAME, team: LONG_TEAM, action: 'create', changed: [], secrets: ['basic_auth_pass', 'env:TOKEN'], status: null },
    { row: 3, name: 'Güncellenen', team: 'Takım B', action: 'update', changed: ['interval_seconds', 'timeout_ms'], secrets: ['custom_headers'], status: null },
    { row: 4, name: 'Aynı', team: 'Takım B', action: 'unchanged', changed: [], secrets: [], status: null },
    { row: 5, name: 'Kötü', team: 'Takım B', action: 'error', changed: [], secrets: [], status: null,
      message: 'tam sayı olmalı', field: 'interval_seconds' },
  ],
  limits: { max_rows: 2000, max_bytes: 5242880 },
  ...over,
})

const resultData = () => ({
  ...previewData(),
  dry_run: false,
  rows: [
    { ...previewData().rows[0], status: 'done' },
    { ...previewData().rows[1], status: 'done' },
    { ...previewData().rows[2], status: 'skipped', message: 'Değişiklik yok — kayıt olduğu gibi bırakıldı.' },
    { ...previewData().rows[3], status: 'failed' },
  ],
})

const csvFile = (name = 'http-izlemeler.csv', text = 'name,team\nA,T\n', type = 'text/csv') => new File([text], name, { type })

const slot = (s, root = document) => root.querySelector(`[data-slot="${s}"]`)
const slots = (s, root = document) => [...root.querySelectorAll(`[data-slot="${s}"]`)]

async function choose(file) {
  const input = await waitFor(() => { const i = slot('mcsv-file'); expect(i).not.toBeNull(); return i })
  fireEvent.change(input, { target: { files: [file] } })
}

function renderDialog(props = {}) {
  const onClose = vi.fn()
  render(<MonitorCsvImportDialog open type="http" typeLabel="HTTP İzleme" onClose={onClose} {...props} />)
  return { onClose }
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yok */ }
})

describe('CSV içe aktarım penceresi', () => {
  it('dosya → önizleme: sayılar, satır kartları, değişen sütunlar, sırlar, hata; "Uygula (2)"', async () => {
    api.monitoring.csvImport.mockResolvedValue({ success: true, data: previewData() })
    renderDialog()
    await choose(csvFile())
    await waitFor(() => expect(slot('mcsv-dialog').getAttribute('data-phase')).toBe('preview'))
    expect(api.monitoring.csvImport).toHaveBeenCalledWith('http', 'name,team\nA,T\n', expect.objectContaining({ dryRun: true }))
    const tiles = Object.fromEntries(slots('mcsv-tile').map((b) => [b.getAttribute('data-key'), b.textContent]))
    expect(tiles.create).toMatch(/^1/)
    expect(tiles.update).toMatch(/^1/)
    expect(tiles.unchanged).toMatch(/^1/)
    expect(tiles.error).toMatch(/^1/)
    const rows = slots('mcsv-row')
    expect(rows.map((r) => r.getAttribute('data-action'))).toEqual(['create', 'update', 'unchanged', 'error'])
    expect(rows[0].textContent).toContain(LONG_NAME)
    expect(rows[0].textContent).toContain(LONG_TEAM)
    expect(slot('mcsv-row-secrets', rows[0]).textContent).toMatch(/Basic auth parolası/)
    expect(slot('mcsv-row-secrets', rows[0]).textContent).toMatch(/TOKEN/)
    expect(slot('mcsv-changed', rows[1]).textContent).toContain('interval_seconds, timeout_ms')
    expect(slot('mcsv-row-message', rows[3]).textContent).toContain('interval_seconds')
    expect(slot('mcsv-secrets').textContent).toMatch(/2 izlemenin/)
    expect(slot('mcsv-apply').textContent).toMatch(/\(2\)/)
    expect(slot('mcsv-apply').disabled).toBe(false)
    // Telefonda da kart listesi: tablo yok, adlar kesilmez
    const dlg = slot('mcsv-dialog')
    expect(dlg.querySelector('table')).toBeNull()
    expect(dlg.querySelector('.truncate')).toBeNull()
  })

  it('kutucuk süzgeci: "Hatalı" yalnız hatalı satırı gösterir, yeniden basınca hepsi', async () => {
    api.monitoring.csvImport.mockResolvedValue({ success: true, data: previewData() })
    renderDialog()
    await choose(csvFile())
    await waitFor(() => expect(slots('mcsv-row').length).toBe(4))
    const err = slots('mcsv-tile').find((b) => b.getAttribute('data-key') === 'error')
    fireEvent.click(err)
    expect(err.getAttribute('aria-pressed')).toBe('true')
    expect(slots('mcsv-row').map((r) => r.getAttribute('data-row'))).toEqual(['5'])
    fireEvent.click(err)
    expect(slots('mcsv-row').length).toBe(4)
  })

  it('uygula → sonuç: uygulanan / atlanan / uygulanamayan; kapatınca onClose(true)', async () => {
    api.monitoring.csvImport
      .mockResolvedValueOnce({ success: true, data: previewData() })
      .mockResolvedValueOnce({ success: true, data: resultData() })
    const { onClose } = renderDialog()
    await choose(csvFile())
    await waitFor(() => expect(slot('mcsv-apply')).not.toBeNull())
    fireEvent.click(slot('mcsv-apply'))
    await waitFor(() => expect(slot('mcsv-dialog').getAttribute('data-phase')).toBe('result'))
    expect(api.monitoring.csvImport).toHaveBeenLastCalledWith('http', 'name,team\nA,T\n', expect.objectContaining({ dryRun: false }))
    expect(slots('mcsv-row').map((r) => r.getAttribute('data-status'))).toEqual(['done', 'done', 'skipped', 'failed'])
    const tiles = Object.fromEntries(slots('mcsv-tile').map((b) => [b.getAttribute('data-key'), b.textContent]))
    expect(tiles.done).toMatch(/^2/)
    expect(tiles.done).toContain('1 yeni · 1 güncellendi')
    expect(tiles.failed).toMatch(/^1/)
    expect(slots('mcsv-row')[0].textContent).toContain('Oluşturuldu')
    fireEvent.click(slot('mcsv-close'))
    expect(onClose).toHaveBeenCalledWith(true)
  })

  it('istek hatası önizlemeyi silmez: hata şeritte, satırlar ve "Uygula" yerinde', async () => {
    api.monitoring.csvImport
      .mockResolvedValueOnce({ success: true, data: previewData() })
      .mockResolvedValueOnce({ success: false, status: 429, code: 'RATE_LIMITED', error: 'Bir içe aktarma zaten sürüyor. Bitmesini bekleyip yeniden deneyin.' })
    const { onClose } = renderDialog()
    await choose(csvFile())
    await waitFor(() => expect(slot('mcsv-apply')).not.toBeNull())
    fireEvent.click(slot('mcsv-apply'))
    await waitFor(() => expect(slot('mcsv-error')).not.toBeNull())
    expect(slot('mcsv-error').textContent).toContain('zaten sürüyor')
    expect(slot('mcsv-dialog').getAttribute('data-phase')).toBe('preview')
    expect(slots('mcsv-row').length).toBe(4)
    expect(slot('mcsv-apply').disabled).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Vazgeç' }))
    expect(onClose).toHaveBeenCalledWith(false)
  })

  it('yanıtsız gerçek koşum: "sonuç bilinmiyor" — başarı denmez, kapanışta liste yenilenir', async () => {
    api.monitoring.csvImport
      .mockResolvedValueOnce({ success: true, data: previewData() })
      .mockResolvedValueOnce({ success: false, status: 0, code: 'REQUEST_TIMEOUT', error: 'zaman aşımı' })
    const { onClose } = renderDialog()
    await choose(csvFile())
    await waitFor(() => expect(slot('mcsv-apply')).not.toBeNull())
    fireEvent.click(slot('mcsv-apply'))
    await waitFor(() => expect(slot('mcsv-error')).not.toBeNull())
    expect(slot('mcsv-error').textContent).toMatch(/Sonuç bilinmiyor/)
    expect(slot('mcsv-dialog').getAttribute('data-phase')).toBe('preview')
    fireEvent.click(screen.getByRole('button', { name: 'Vazgeç' }))
    expect(onClose).toHaveBeenCalledWith(true)
  })

  it('uygulanacak satır yoksa "Uygula" kapalı ve neden yazılı', async () => {
    const rows = previewData().rows.filter((r) => r.action === 'unchanged' || r.action === 'error')
    api.monitoring.csvImport.mockResolvedValue({ success: true, data: previewData({ rows }) })
    renderDialog()
    await choose(csvFile())
    await waitFor(() => expect(slot('mcsv-apply')).not.toBeNull())
    expect(slot('mcsv-apply').disabled).toBe(true)
    expect(slot('mcsv-nothing')).not.toBeNull()
  })

  it('dosya düzeyi hata (başlık): sunucu metni şeritte, önizleme yok', async () => {
    api.monitoring.csvImport.mockResolvedValue({ success: false, status: 400, code: 'VALIDATION_FAILED', field: 'csv',
      reason: 'unknown_column', error: 'Bilinmeyen sütun: password (satır 1). Bu türün dışa aktarılmış başlıklarını kullanın.' })
    renderDialog()
    await choose(csvFile())
    await waitFor(() => expect(slot('mcsv-error')).not.toBeNull())
    expect(slot('mcsv-error').textContent).toContain('Bilinmeyen sütun: password')
    expect(slot('mcsv-dialog').getAttribute('data-phase')).toBe('pick')
  })

  it('CSV olmayan / boş dosya sunucuya gitmez; eski sunucu (404) "bu sürüm bilmiyor"', async () => {
    renderDialog()
    await choose(csvFile('logo.png', 'x', 'image/png'))
    await waitFor(() => expect(slot('mcsv-error')).not.toBeNull())
    expect(slot('mcsv-error').textContent).toMatch(/CSV dosyası görünmüyor/)
    expect(api.monitoring.csvImport).not.toHaveBeenCalled()
    api.monitoring.csvImport.mockResolvedValue({ success: false, status: 404, code: 'NOT_FOUND', error: 'x' })
    await choose(csvFile())
    await waitFor(() => expect(slot('mcsv-error').textContent).toMatch(/sunucu sürümü/))
  })

  it('"Başka dosya seç" seçim evresine döner', async () => {
    api.monitoring.csvImport.mockResolvedValue({ success: true, data: previewData() })
    renderDialog()
    await choose(csvFile())
    await waitFor(() => expect(slot('mcsv-pick-another')).not.toBeNull())
    fireEvent.click(slot('mcsv-pick-another'))
    expect(slot('mcsv-dialog').getAttribute('data-phase')).toBe('pick')
    expect(slot('mcsv-file')).not.toBeNull()
  })
})

describe('İçe / dışa aktar menüsü', () => {
  it('dışa aktar: türle çağırır, satır sayısını söyler', async () => {
    api.monitoring.csvExport.mockResolvedValue({ success: true, rows: 12, truncated: false, name: 'http-izlemeler.csv' })
    render(<MonitorCsvMenu type="http" canImport onImported={() => {}} />)
    pressMenuTrigger(slot('monitor-csv-menu'))
    const item = await waitFor(() => { const i = slot('monitor-csv-export'); expect(i).not.toBeNull(); return i })
    fireEvent.click(item)
    await waitFor(() => expect(api.monitoring.csvExport).toHaveBeenCalledWith('http'))
    expect(await screen.findByText('12 izleme CSV olarak indirildi.')).toBeTruthy()
  })

  it('indirme başlamadıysa başarı denmez; eski sunucu ayrı metin', async () => {
    api.monitoring.csvExport.mockResolvedValueOnce({ success: false, status: 0, code: 'DOWNLOAD_FAILED' })
    render(<MonitorCsvMenu type="ping" canImport={false} />)
    pressMenuTrigger(slot('monitor-csv-menu'))
    fireEvent.click(await waitFor(() => { const i = slot('monitor-csv-export'); expect(i).not.toBeNull(); return i }))
    expect(await screen.findByText(/indirmeyi başlatamadı/)).toBeTruthy()
  })

  it('yetki yoksa içe aktarım öğesi yok; CSV türü değilse menü hiç yok', async () => {
    const { unmount } = render(<MonitorCsvMenu type="ping" canImport={false} />)
    pressMenuTrigger(slot('monitor-csv-menu'))
    await waitFor(() => expect(slot('monitor-csv-export')).not.toBeNull())
    expect(slot('monitor-csv-import')).toBeNull()
    unmount()
    render(<MonitorCsvMenu type="uptime" canImport />)
    expect(slot('monitor-csv-menu')).toBeNull()
  })

  it('içe aktar: pencere tembel yüklenir ve açılır; gerçek koşum sonrası kapanınca liste yenilenir', async () => {
    const onImported = vi.fn()
    api.monitoring.csvImport
      .mockResolvedValueOnce({ success: true, data: previewData() })
      .mockResolvedValueOnce({ success: true, data: resultData() })
    render(<MonitorCsvMenu type="http" canImport onImported={onImported} />)
    pressMenuTrigger(slot('monitor-csv-menu'))
    fireEvent.click(await waitFor(() => { const i = slot('monitor-csv-import'); expect(i).not.toBeNull(); return i }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/CSV ile içe aktar — /)).toBeTruthy()
    await choose(csvFile())
    await waitFor(() => expect(slot('mcsv-apply')).not.toBeNull())
    fireEvent.click(slot('mcsv-apply'))
    await waitFor(() => expect(slot('mcsv-close')).not.toBeNull())
    fireEvent.click(slot('mcsv-close'))
    await waitFor(() => expect(onImported).toHaveBeenCalledTimes(1))
  })
})
