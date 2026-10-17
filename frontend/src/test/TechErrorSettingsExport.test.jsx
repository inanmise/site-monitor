import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

// Ayarlar → Teknik hata raporu — kanal özetini dışa aktarma (2026-10-17, E22): "Dışa aktar" → CSV / Excel (.xlsx), SÜZÜLMÜŞ +
// SIRALANMIŞ listenin TÜMÜ (görünen sayfa değil), başlık satırı + ekrandaki metinler; bilinmeyen değer "Bilinmiyor".
const dl = vi.hoisted(() => ({ csv: [], blob: [] }))
vi.mock('../utils/csvExport.js', async (orig) => ({
  ...(await orig()),
  downloadCsv: (name, csv) => { dl.csv.push({ name, csv }) },
  downloadBlob: (name, blob) => { dl.blob.push({ name, blob }) },
}))
const xl = vi.hoisted(() => ({ sheets: [] }))
vi.mock('../utils/xlsxWriter.js', async (orig) => {
  const real = await orig()
  return { ...real, buildXlsx: (sheets) => { xl.sheets.push(sheets); return real.buildXlsx(sheets) } }
})
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: { techErrorSettings: vi.fn(), saveTechErrorSettings: vi.fn() },
    teams: { directory: vi.fn() },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportSettings from '../components/admin/TechErrorReportSettings.jsx'

const ch = (id, name, extra = {}) => ({
  id, name, active: true, team_id: 1, team_name: 'Dijital Kanallar', team_visible: true, executive_count: 0, approver_count: 0,
  team_email_set: true, period_report: null, ...extra,
})
// 30 kanal: panel ön ayarı sayfada 25 gösterir — dışa aktarma TÜMÜNÜ yazar
const MANY = Array.from({ length: 30 }, (_, i) => ch(100 + i, `Kanal ${String(i + 1).padStart(2, '0')}`,
  { period_report: { id: 900 + i, status: 'draft', approval: { state: 'none' } } }))
const CHANNELS = [
  ch(10, 'Mobil', { executive_count: 2, approver_count: 1, period_report: { id: 100, status: 'sent', last_send_status: null, approval: { state: 'approved' } } }),
  ch(20, 'POS', { active: false, team_id: 2, team_name: 'Ödeme Sistemleri', team_visible: false, team_email_set: false }),
  // Eski sunucu / kısmi veri: alanlar yok → "Bilinmiyor"
  { id: 30, name: 'ATM', active: true, team_id: 2, team_name: 'Ödeme Sistemleri', period_report: { id: 300, status: 'draft' } },
  ...MANY,
]
const HEADER = ['Kanal', 'Takım', 'Aktif', 'Modül takıma açık', 'Takım e-postası', 'Yönetici alıcı sayısı', 'PO onaylayıcı sayısı',
  'Dönem raporu durumu', 'Onay durumu']

/** CSV metnini satırlara böler (BOM'u atar; bu verilerde hücrelerde ayırıcı / tırnak yok). */
const csvLines = (csv) => (csv.charCodeAt(0) === 0xfeff ? csv.slice(1) : csv).trim().split('\r\n').map((l) => l.split(','))

async function openExport() {
  const trigger = await screen.findByRole('button', { name: /Dışa aktar/ })
  expect(trigger).toHaveAttribute('data-slot', 'tes-export')
  pressMenuTrigger(trigger)
}

beforeEach(() => {
  vi.clearAllMocks()
  dl.csv = []; dl.blob = []; xl.sheets = []
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.teams.directory.mockResolvedValue({ success: true, data: [] })
  api.admin.techErrorSettings.mockResolvedValue({ success: true, data: {
    team_ids: [], read_only: false, period: { year: 2026, month: 9 }, channels: CHANNELS } })
})

describe('Ayarlar → Teknik hata raporu — dışa aktarma', () => {
  it('CSV: başlık + süzülmüş/sıralanmış listenin TÜMÜ (sayfa değil), ekrandaki metinler, bilinmeyen "Bilinmiyor"', async () => {
    render(<TechErrorReportSettings />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="tes-channel"]')).toHaveLength(25))   // sayfada 25
    await openExport()
    fireEvent.click(await screen.findByRole('menuitem', { name: 'CSV (.csv)' }))
    expect(dl.csv).toHaveLength(1)
    expect(dl.csv[0].name).toBe('teknik-hata-raporu-kanallar-2026-09.csv')
    expect(dl.csv[0].csv.charCodeAt(0)).toBe(0xfeff)
    const lines = csvLines(dl.csv[0].csv)
    expect(lines[0]).toEqual(HEADER)
    expect(lines).toHaveLength(1 + 33)
    // Varsayılan sıra (aktifler önce, ad A→Z): ATM, Kanal 01…30, Mobil, sonra pasif POS
    expect(lines[1]).toEqual(['ATM', 'Ödeme Sistemleri', 'Evet', 'Bilinmiyor', 'Bilinmiyor', 'Bilinmiyor', 'Bilinmiyor', 'Taslak', 'Bilinmiyor'])
    expect(lines[2][0]).toBe('Kanal 01')
    expect(lines[32]).toEqual(['Mobil', 'Dijital Kanallar', 'Evet', 'Evet', 'Tanımlı', '2', '1', 'Gönderildi', 'Onaylandı'])
    expect(lines[33]).toEqual(['POS', 'Ödeme Sistemleri', 'Hayır', 'Hayır', 'Eksik — rapor gönderilemez', '0', '0', 'Dönem raporu yok', '—'])
  })

  it('süzgeç uygulanınca yalnız süzülenler yazılır (arama + durum)', async () => {
    render(<TechErrorReportSettings />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="tes-channel"]')).toHaveLength(25))
    fireEvent.change(screen.getByRole('searchbox', { name: 'Kanal ya da takım ara' }), { target: { value: 'ödeme' } })
    await waitFor(() => expect(document.querySelectorAll('[data-slot="tes-channel"]')).toHaveLength(2))
    await openExport()
    fireEvent.click(await screen.findByRole('menuitem', { name: 'CSV (.csv)' }))
    expect(csvLines(dl.csv[0].csv).map((l) => l[0])).toEqual(['Kanal', 'ATM', 'POS'])
    fireEvent.change(screen.getByLabelText('Durum'), { target: { value: 'inactive' } })
    await waitFor(() => expect(document.querySelectorAll('[data-slot="tes-channel"]')).toHaveLength(1))
    await openExport()
    fireEvent.click(await screen.findByRole('menuitem', { name: 'CSV (.csv)' }))
    expect(csvLines(dl.csv[1].csv).map((l) => l[0])).toEqual(['Kanal', 'POS'])
  })

  it('Excel: tek sayfa, başlık satırı + aynı satırlar; sayılar sayı hücresi; dosya .xlsx olarak indirilir', async () => {
    render(<TechErrorReportSettings />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="tes-channel"]')).toHaveLength(25))
    await openExport()
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Excel (.xlsx)' }))
    await waitFor(() => expect(dl.blob).toHaveLength(1))
    expect(dl.blob[0].name).toBe('teknik-hata-raporu-kanallar-2026-09.xlsx')
    expect(dl.blob[0].blob.type).toBe('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')
    expect(xl.sheets).toHaveLength(1)
    const [sheet] = xl.sheets[0]
    expect(sheet.name).toBe('Kanallar')
    expect(sheet.freezeHeader).toBe(true)
    expect(sheet.autoFilter).toBe(true)
    expect(sheet.rows[0]).toEqual(HEADER)
    expect(sheet.rows).toHaveLength(34)
    expect(sheet.rows[1][5]).toBe('Bilinmiyor')
    expect(sheet.rows.find((r) => r[0] === 'Mobil').slice(5, 7)).toEqual([2, 1])
  })

  it('eşleşen kanal yoksa "Dışa aktar" kapalı', async () => {
    render(<TechErrorReportSettings />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="tes-channel"]')).toHaveLength(25))
    fireEvent.change(screen.getByRole('searchbox', { name: 'Kanal ya da takım ara' }), { target: { value: 'hiçbiri' } })
    await waitFor(() => expect(document.querySelectorAll('[data-slot="tes-channel"]')).toHaveLength(0))
    expect(screen.getByRole('button', { name: /Dışa aktar/ })).toBeDisabled()
  })
})
