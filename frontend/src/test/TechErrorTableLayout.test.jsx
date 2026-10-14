import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { channel, overview, reportResponse, TEMPLATE } from './helpers/techErrorFixtures.js'

// Tablo bölümü tablo ↔ kart geçişi (inceleme 2026-10-14): kap genişliği eşiği geçince hücreler yeniden kurulur — yarım
// kalmış geçersiz yazımın istemci hatası kalkar (kaydı engellemez), odaklı hücreye odak döner, eşikte 24 px tampon
// görünümü titretmez. Genişlik kancası durum tutan bir taklitle değiştirilir (jsdom genişlik ölçmez).
const widthCtl = vi.hoisted(() => ({ value: 0, listeners: new Set() }))
vi.mock('../hooks/useElementWidth.js', async (orig) => {
  const React = await import('react')
  const real = await orig()
  return {
    ...real,
    useElementWidth: () => {
      const [w, setW] = React.useState(widthCtl.value)
      React.useEffect(() => {
        widthCtl.listeners.add(setW)
        return () => { widthCtl.listeners.delete(setW) }
      }, [])
      return [() => {}, w]
    },
  }
})

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), createChannel: vi.fn(), updateChannel: vi.fn(), deleteChannel: vi.fn(), template: vi.fn(),
      saveTemplate: vi.fn(), defaultTemplate: vi.fn(), reports: vi.fn(), createReport: vi.fn(), report: vi.fn(),
      saveReport: vi.fn(), refreshTemplate: vi.fn(), deleteReport: vi.fn(), uploadImage: vi.fn(), deleteImage: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`, send: vi.fn(), downloadPdf: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const TABLE_TEMPLATE = {
  ...TEMPLATE,
  sections: [
    TEMPLATE.sections[0],
    { id: 'sla', kind: 'table', title: 'SLA Erişilebilirlik Tablosu', columns: [
      { id: 'functions', label: 'Fonksiyon adedi', type: 'number' },
      { id: 'availability', label: 'Erişilebilirlik', type: 'percent' },
      { id: 'note', label: 'Not', type: 'text' },
    ], rows: [
      { id: 'pre', label: 'Grup öncesi satır' },
      { id: 'total', label: 'Toplam', group: true },
      { id: 'critical', label: 'SLA dahil ve kritik' },
    ] },
  ],
}
// tableMinWidth: max(640, 176 + 112 + 112 + 160) = 640 px; kenarlıkla sığma 642; karttan tabloya 642 + 24 = 666
const setWidth = (w) => act(() => { widthCtl.value = w; widthCtl.listeners.forEach((l) => l(w)) })
const layout = () => document.querySelector('[data-slot="te-table"] [data-layout]')?.getAttribute('data-layout')

beforeEach(() => {
  vi.clearAllMocks()
  widthCtl.value = 0
  widthCtl.listeners.clear()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().report.mockResolvedValue(reportResponse({ template: TABLE_TEMPLATE }))
})

describe('tablo ↔ kart geçişi', () => {
  it('geçişte yarım kalmış hücre hatası kalkar, odaklı hücreye odak döner, kayıt engellenmez', async () => {
    widthCtl.value = 1200
    te().saveReport.mockResolvedValue({ success: true, data: { version: 5, changed: true, values: { fields: {}, issues: {}, texts: {} } } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(layout()).toBe('table')
    // Geçerli bir düzenleme: taslak kirli, Kaydet açık (kaydın engellenmediği gerçekten sınanabilsin)
    fireEvent.change(screen.getByLabelText('Toplam — Not'), { target: { value: 'İyi' } })
    const cell = screen.getByLabelText('SLA dahil ve kritik — Fonksiyon adedi')
    cell.focus()
    fireEvent.change(cell, { target: { value: '12a' } })
    expect(await screen.findByText('Geçerli bir sayı yazın (ör. 1.250.000 ya da 0,42).')).toBeInTheDocument()
    setWidth(500)
    expect(layout()).toBe('cards')
    await waitFor(() => expect(screen.queryByText('Geçerli bir sayı yazın (ör. 1.250.000 ya da 0,42).')).toBeNull())
    const again = screen.getByLabelText('SLA dahil ve kritik — Fonksiyon adedi')
    expect(again).not.toBe(cell)
    await waitFor(() => expect(again).toHaveFocus())
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    // Geçişte kalkan istemci hatası kaydı engellemez: kayıt sunucuya gider, geçersiz yazım gönderilmez
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(te().saveReport.mock.calls[0][1].tables).toEqual({ sla: { total: { note: 'İyi' } } })
  })

  it('eşikte 24 px tampon: karta düşen görünüm eşiğin hemen üstünde titremez', async () => {
    widthCtl.value = 1200
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    setWidth(641)                      // kenarlık payı: 641 < 642 → kart (tablo 2 px kayardı)
    expect(layout()).toBe('cards')
    setWidth(660)                      // 642 ≤ 660 < 666 → kartta kalır
    expect(layout()).toBe('cards')
    setWidth(666)
    expect(layout()).toBe('table')
    setWidth(645)                      // tablodan karta eşik 642 → tabloda kalır
    expect(layout()).toBe('table')
  })

  it('içeriden başlama yalnız grup satırının ALTINDAKİ satırlarda', async () => {
    widthCtl.value = 1200
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(screen.getByRole('rowheader', { name: 'Grup öncesi satır' })).not.toHaveClass('ps-6')
    expect(screen.getByRole('rowheader', { name: 'Toplam' })).not.toHaveClass('ps-6')
    expect(screen.getByRole('rowheader', { name: 'SLA dahil ve kritik' })).toHaveClass('ps-6')
  })
})
