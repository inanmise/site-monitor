import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { channel } from './helpers/techErrorFixtures.js'

// Kanalın raporlarını Excel'e aktarma (C14, 2026-10-18): düğme değer dışa aktarımını (E23) tek kanal + tüm durumlar +
// JSON ile ister (yıl süzgeci seçiliyse o yıl), Excel yazıcısını TEMBEL yükler, iki sayfa yazar ve indirir; hata sunucu
// iletisiyle (ya da açıklayıcı yedek metinle) tostta söylenir, yazıcı çağrılmaz.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), template: vi.fn(), exportValues: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
const writer = vi.hoisted(() => ({ buildXlsx: vi.fn(() => new Uint8Array([80, 75, 3, 4])), download: vi.fn() }))
vi.mock('../utils/xlsxWriter.js', () => ({ buildXlsx: writer.buildXlsx, XLSX_MIME: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }))
// İndirme proje yardımcısıyla (inceleme 2026-10-18: bağlantı DOM'da, adres 60 sn — Safari / iPad); diğer CSV yardımcıları gerçek
vi.mock('../utils/csvExport.js', async (orig) => ({ ...(await orig()), downloadBlob: writer.download }))
// Tembel modülün kendisi: gerçek uygulamaya devreder; bir testte tarayıcı hatası (ör. yüklenemeyen parça) taklit edilir
const chunk = vi.hoisted(() => ({ fail: null }))
vi.mock('../components/techerror/channelExport.js', async () => {
  const actual = await vi.importActual('../components/techerror/channelExport.js')
  return { ...actual, exportChannelXlsx: (...a) => { if (chunk.fail) throw chunk.fail; return actual.exportChannelXlsx(...a) } }
})
import { api } from '../api/client'
import { ApiError } from '../utils/errorMessages.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const COLUMNS = ['team_id', 'team_name', 'channel_id', 'channel_name', 'year', 'month', 'report_id', 'report_status',
  'sent_at', 'changed_after_send', 'approval_state', 'field_id', 'field_label', 'field_type', 'value', 'exempt', 'computed',
  'calc_status']
const row = (year, month, id, field, label, type, value, extra = {}) => {
  const o = { team_id: 1, team_name: 'Takım A', channel_id: 10, channel_name: 'Mobil', year, month, report_id: id, report_status: 'sent',
    sent_at: '2026-10-02T08:00:00Z', changed_after_send: false, approval_state: null, field_id: field, field_label: label, field_type: type,
    value, exempt: false, computed: false, calc_status: null, ...extra }
  return COLUMNS.map((c) => o[c])
}
const exportData = () => ({
  success: true,
  data: {
    columns: COLUMNS,
    rows: [
      row(2025, 12, 101, 'total-tx', 'Toplam işlem adedi', 'number', 1000),
      row(2026, 9, 102, 'total-tx', 'Toplam işlem adedi', 'number', 2000),
      row(2026, 9, 102, 'error-rate', 'Teknik hata oranı', 'percent', 1.475, { computed: true }),
    ],
    reports: [
      { channel_id: 10, year: 2025, month: 12, report_id: 101, report_status: 'sent', sent_at: '2026-01-05T08:00:00Z', changed_after_send: false },
      { channel_id: 10, year: 2026, month: 9, report_id: 102, report_status: 'sent', sent_at: '2026-10-02T08:00:00Z', changed_after_send: false },
    ],
    notes: ['…'],
  },
})

const reportsList = () => [
  { id: 102, year: 2026, month: 9, status: 'sent', title: 'Mobil — Eylül 2026', updated_at: '2026-10-01T07:00:00Z', last_sent_at: '2026-10-02T08:00:00Z' },
  { id: 101, year: 2025, month: 12, status: 'sent', title: 'Mobil — Aralık 2025', updated_at: '2026-01-04T07:00:00Z', last_sent_at: '2026-01-05T08:00:00Z' },
]

const xlsxButton = () => screen.findByRole('button', { name: "Excel'e aktar" })

beforeEach(() => {
  vi.clearAllMocks()
  chunk.fail = null
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10')
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().reports.mockResolvedValue({ success: true, data: { channel: channel(10, 'Mobil'), reports: reportsList(), default_period: { year: 2026, month: 9 } } })
  te().overview.mockResolvedValue({ success: true, data: { period: { year: 2026, month: 9 }, teams: [], channels: [channel(10, 'Mobil')], can_create: true } })
  te().exportValues.mockResolvedValue(exportData())
})

describe("kanal görünümü — Excel'e aktar", () => {
  it('tüm yıllar: tek kanal + takım + tüm durumlar + JSON ister; iki sayfa yazar ve <kanal>-<yıl aralığı>-degerler.xlsx indirir', async () => {
    render(<TechErrorReportsPage />)
    const btn = await xlsxButton()
    expect(btn).toHaveAttribute('data-slot', 'te-channel-xlsx')
    fireEvent.click(btn)
    await waitFor(() => expect(writer.download).toHaveBeenCalledTimes(1))
    expect(te().exportValues).toHaveBeenCalledWith({ channel: 10, team: 1, status: 'all', format: 'json' })
    const sheets = writer.buildXlsx.mock.calls[0][0]
    expect(sheets.map((s) => s.name)).toEqual(['Değerler', 'Raporlar'])
    expect(sheets[0].rows[0]).toEqual(['Alan', 'Tür', 'Aralık 2025', 'Eylül 2026'])
    expect(sheets[0].rows[1]).toEqual(['Toplam işlem adedi', 'Sayı', 1000, 2000])
    expect(sheets[0].rows[2]).toEqual(['Teknik hata oranı', 'Yüzde', null, 1.475])
    expect(sheets[1].rows.map((r) => r[0])).toEqual(['Dönem', 'Aralık 2025', 'Eylül 2026'])
    const [name, blob] = writer.download.mock.calls[0]
    expect(blob).toBeInstanceOf(Blob)
    expect(name).toBe('mobil-2025-2026-degerler.xlsx')
    expect(await screen.findByText('Excel dosyası indirildi: mobil-2025-2026-degerler.xlsx')).toBeInTheDocument()
  })

  it('yıl süzgeci seçiliyse yalnız o yıl istenir ve dosya adında o yıl yazar', async () => {
    render(<TechErrorReportsPage />)
    await xlsxButton()
    fireEvent.change(screen.getByLabelText('Yıl'), { target: { value: '2025' } })
    fireEvent.click(await xlsxButton())
    await waitFor(() => expect(writer.download).toHaveBeenCalledTimes(1))
    expect(te().exportValues).toHaveBeenCalledWith({ channel: 10, team: 1, status: 'all', format: 'json', year: 2025 })
    expect(writer.download.mock.calls[0][0]).toBe('mobil-2025-degerler.xlsx')
  })

  it('sunucu reddi (429) iletisiyle tostta söylenir; ağ hatası açıklayıcı yedek metinle; yazıcı çağrılmaz', async () => {
    te().exportValues.mockResolvedValueOnce({ success: false, status: 429, code: 'RATE_LIMITED',
      error: 'Dışa aktarım sınırına ulaşıldı: kişi başına dakikada en çok 6 dışa aktarım. 40 saniye sonra yeniden deneyin.' })
    render(<TechErrorReportsPage />)
    fireEvent.click(await xlsxButton())
    expect(await screen.findByText(/Dışa aktarım sınırına ulaşıldı/)).toBeInTheDocument()
    te().exportValues.mockRejectedValueOnce(new TypeError(''))
    fireEvent.click(await xlsxButton())
    expect(await screen.findByText(/Excel dosyası hazırlanamadı: raporların değerleri okunamadı/)).toBeInTheDocument()
    expect(writer.buildXlsx).not.toHaveBeenCalled()
    expect(writer.download).not.toHaveBeenCalled()
  })

  it('istemci hatası ham metinle görünmez: ApiError kendi açıklayıcı iletisiyle, başka bir tarayıcı hatası (TypeError) çevrilmiş yedek metinle', async () => {
    te().exportValues.mockRejectedValueOnce(new ApiError('Sunucuya ulaşılamadı. Bağlantınızı kontrol edip yeniden deneyin.', { status: 0, code: 'NETWORK_ERROR' }))
    render(<TechErrorReportsPage />)
    fireEvent.click(await xlsxButton())
    expect(await screen.findByText('Sunucuya ulaşılamadı. Bağlantınızı kontrol edip yeniden deneyin.')).toBeInTheDocument()
    te().exportValues.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    fireEvent.click(await xlsxButton())
    expect(await screen.findByText(/Excel dosyası hazırlanamadı: raporların değerleri okunamadı/)).toBeInTheDocument()
    expect(screen.queryByText(/Failed to fetch/)).not.toBeInTheDocument()
    expect(writer.buildXlsx).not.toHaveBeenCalled()
  })

  it('dosya tarayıcıda kurulamazsa (yazıcı hatası / yüklenemeyen parça) çevrilmiş metin; tarayıcı metni yalnız Teknik ayrıntı künyesinde; "değerler okunamadı" denmez', async () => {
    chunk.fail = new TypeError('Failed to fetch dynamically imported module: /assets/channelExport-abc.js')
    render(<TechErrorReportsPage />)
    fireEvent.click(await xlsxButton())
    expect(await screen.findByText(/Excel dosyası tarayıcınızda oluşturulamadı \(değerler okundu, dosya kurulamadı\)/)).toBeInTheDocument()
    expect(screen.queryByText(/değerleri okunamadı/)).not.toBeInTheDocument()
    expect(screen.queryByText(/dynamically imported module/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Teknik ayrıntı/ }))
    expect(await screen.findByText(/XLSX_BUILD_FAILED \(TypeError: Failed to fetch dynamically imported module/)).toBeInTheDocument()
    expect(writer.download).not.toHaveBeenCalled()

    chunk.fail = null
    writer.buildXlsx.mockImplementationOnce(() => { throw new RangeError('Invalid array length') })
    fireEvent.click(await xlsxButton())
    await waitFor(() => expect(writer.buildXlsx).toHaveBeenCalledTimes(1))
    // Aynı ileti tek toastta birleşir (×2); açık künye yeni hatanın kodunu gösterir — tarayıcı metni YALNIZ künyede
    await waitFor(() => expect(screen.getByText(/XLSX_BUILD_FAILED \(RangeError: Invalid array length\)/)).toBeInTheDocument())
    for (const el of screen.queryAllByText(/Invalid array length/)) expect(el.closest('[data-slot="error-details"]')).not.toBeNull()
    expect(writer.download).not.toHaveBeenCalled()
    // Düğme yeniden kullanılabilir (meşgul bayrağı bırakıldı)
    expect(await xlsxButton()).not.toBeDisabled()
  })

  it('açıklama: liste süzgeçleri uygulanmaz — tüm yıllarda "bütün yıllardaki bütün raporları", yıl seçiliyse "<yıl> yılının bütün raporları"; durum / arama süzgeci isteğe girmez', async () => {
    render(<TechErrorReportsPage />)
    const btn = await xlsxButton()
    expect(btn).toHaveAccessibleDescription(/bütün yıllardaki bütün raporları — liste süzgeçleri \(durum, arama\) uygulanmaz/)
    fireEvent.change(screen.getByLabelText('Yıl'), { target: { value: '2025' } })
    fireEvent.change(screen.getByLabelText('Durum'), { target: { value: 'draft' } })
    const again = await xlsxButton()
    expect(again).toHaveAccessibleDescription(/^2025 yılının bütün raporları — liste süzgeçleri \(durum, arama\) uygulanmaz/)
    fireEvent.click(again)
    await waitFor(() => expect(te().exportValues).toHaveBeenCalledTimes(1))
    expect(te().exportValues).toHaveBeenCalledWith({ channel: 10, team: 1, status: 'all', format: 'json', year: 2025 })
  })

  it('dışa aktarılacak değer yoksa bilgi tostu; dosya üretilmez', async () => {
    te().exportValues.mockResolvedValueOnce({ success: true, data: { columns: COLUMNS, rows: [], reports: [], notes: [] } })
    render(<TechErrorReportsPage />)
    fireEvent.click(await xlsxButton())
    expect(await screen.findByText(/Dışa aktarılacak değer yok/)).toBeInTheDocument()
    expect(writer.download).not.toHaveBeenCalled()
  })

  it('istek sürerken düğme kilitli: art arda tıklama tek istek', async () => {
    let resolve
    te().exportValues.mockReturnValueOnce(new Promise((r) => { resolve = r }))
    render(<TechErrorReportsPage />)
    const btn = await xlsxButton()
    fireEvent.click(btn)
    fireEvent.click(btn)
    const busy = await screen.findByRole('button', { name: 'Excel hazırlanıyor…' })
    expect(busy).toBeDisabled()
    expect(busy).toHaveAttribute('aria-busy', 'true')
    resolve(exportData())
    await waitFor(() => expect(writer.download).toHaveBeenCalledTimes(1))
    expect(te().exportValues).toHaveBeenCalledTimes(1)
  })
})
