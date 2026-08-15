import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * HAFTALIK RAPOR ONAY AKIŞI — uygulamanın en büyük dosyası (1600+ satır, 13 yazma ucu) ve buraya
 * kadar SIFIR testi vardı. Odak, iş-kritik durum makinesi: onaya gönder / onayla / yeniden aç.
 * Bunlar müdüre mail atan, raporu kilitleyen ve geri alınması zor eylemler:
 *  - onay ve yeniden açma ONAY DİYALOĞU istemeli (yanlış tıkla onaylanmamalı),
 *  - iptalde uca istek GİTMEMELİ,
 *  - sunucu reddinde ekran "başarılı" dememeli.
 *
 * Sayfa ağır bağımlılıklı olduğu için editör/markdown/KPI şeritleri mock'lanıyor — hedef bu
 * testte GÖRSEL değil, AKIŞ.
 */
const confirmMock = vi.fn(() => Promise.resolve(true))
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }

vi.mock('@uiw/react-md-editor', () => ({
  default: ({ value }) => <textarea readOnly value={value ?? ''} />,
  commands: { bold: {}, italic: {}, group: () => ({}) },
}))
vi.mock('react-markdown', () => ({ default: ({ children }) => <div>{children}</div> }))
vi.mock('remark-gfm', () => ({ default: () => {} }))
vi.mock('../components/WeeklyKpiStrip.jsx', () => ({ default: () => <div data-testid="kpi" /> }))
vi.mock('../components/WeeklySummaryBrief.jsx', () => ({ default: () => <div data-testid="brief" /> }))
vi.mock('../components/WeeklyMonitoringStrip.jsx', () => ({ default: () => <div data-testid="mon" /> }))
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: confirmMock }),
  DialogProvider: ({ children }) => children,
}))
vi.mock('../components/ui/Toast.jsx', () => ({
  useToast: () => toastMock,
  ToastProvider: ({ children }) => children,
}))

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: {
    weeklyReports: {
      years: vi.fn(), list: vi.fn(), get: vi.fn(), lock: vi.fn(), unlock: vi.fn(),
      save: vi.fn(), submit: vi.fn(), approve: vi.fn(), reject: vi.fn(), reopen: vi.fn(),
      resend: vi.fn(), remove: vi.fn(), kpis: vi.fn(), monitoringStats: vi.fn(),
      mails: vi.fn(), uploadImage: vi.fn(), transfer: vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  },
}))

import { api } from '../api/client'
import WeeklyReportsPage from '../components/WeeklyReportsPage.jsx'

const REPORT = {
  id: 10, team_id: 5, team_name: 'SY-A', report_year: 2026, week_no: 32,
  week_label: '2026-W32', status: 'PENDING_APPROVAL', version: 3,
  content_json: '{}', updated_at: '2026-08-10T10:00:00',
}

const renderPage = () =>
  render(<LangProvider><WeeklyReportsPage systemRole="ADMIN" teamId={5} teamName="SY-A" /></LangProvider>)

/** Liste satırına tıklayıp rapor detayını açar. Satır, hafta ETİKETİNİ değil formatWeekRange ile
 *  üretilen tarih aralığını bastığı için satır tbody'den seçiliyor (metne bağlanmak kırılgan olurdu). */
async function openReport() {
  renderPage()
  await waitFor(() => expect(api.weeklyReports.list).toHaveBeenCalled())
  const row = await waitFor(() => {
    const tr = document.querySelector('tbody tr')
    if (!tr) throw new Error('rapor satırı henüz yok')
    return tr
  })
  fireEvent.click(row)
  await waitFor(() => expect(api.weeklyReports.get).toHaveBeenCalledWith(10))
}

describe('WeeklyReportsPage — onay akışı', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirmMock.mockResolvedValue(true)
    api.weeklyReports.years.mockResolvedValue({ success: true, data: [2026] })
    api.weeklyReports.list.mockResolvedValue({ success: true, data: [REPORT] })
    // Uç, raporu SARMALAYARAK döner: { report, images, manager_contact_missing, lock_holder }
    api.weeklyReports.get.mockResolvedValue({ success: true, data: { report: REPORT, images: [] } })
    api.weeklyReports.lock.mockResolvedValue({ success: true, data: { acquired: true } })
    api.weeklyReports.unlock.mockResolvedValue({ success: true })
    api.weeklyReports.kpis.mockResolvedValue({ success: true, data: null })
    api.weeklyReports.monitoringStats.mockResolvedValue({ success: true, data: null })
    api.weeklyReports.mails.mockResolvedValue({ success: true, data: [] })
    api.weeklyReports.approve.mockResolvedValue({ success: true })
    api.weeklyReports.reopen.mockResolvedValue({ success: true })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
  })

  it('ONAYLA onay diyaloğu ister; İPTAL edilirse approve ucu ÇAĞRILMAZ', async () => {
    confirmMock.mockResolvedValue(false)
    await openReport()

    // Eylem çubuğu üstte ve altta render ediliyor → ilk düğme yeterli.
    const btn = (await screen.findAllByRole('button', { name: /^Approve$|^Onayla$/i }))[0]
    fireEvent.click(btn)

    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.weeklyReports.approve).not.toHaveBeenCalled()
  })

  it('onaylandığında DOĞRU rapor id ile approve edilir ve detay yeniden yüklenir', async () => {
    await openReport()
    const before = api.weeklyReports.get.mock.calls.length

    fireEvent.click((await screen.findAllByRole('button', { name: /^Approve$|^Onayla$/i }))[0])

    await waitFor(() => expect(api.weeklyReports.approve).toHaveBeenCalledWith(10))
    await waitFor(() => expect(api.weeklyReports.get.mock.calls.length).toBeGreaterThan(before))
    expect(toastMock.success).toHaveBeenCalled()
  })

  it('approve sunucuda reddedilirse HATA bildirilir, başarı mesajı verilmez', async () => {
    api.weeklyReports.approve.mockResolvedValue({ success: false, error: 'MANAGER_CONTACT_MISSING' })
    await openReport()

    fireEvent.click((await screen.findAllByRole('button', { name: /^Approve$|^Onayla$/i }))[0])

    await waitFor(() => expect(toastMock.error).toHaveBeenCalled())
    expect(toastMock.success).not.toHaveBeenCalled()
  })

  it('ONAYLI rapor REVİZE edilirken onay ister; iptalde reopen ÇAĞRILMAZ', async () => {
    api.weeklyReports.get.mockResolvedValue({ success: true,
      data: { report: { ...REPORT, status: 'APPROVED' }, images: [] } })
    api.weeklyReports.list.mockResolvedValue({ success: true, data: [{ ...REPORT, status: 'APPROVED' }] })
    confirmMock.mockResolvedValue(false)
    await openReport()

    // wr.reopen etiketi 'Revise' / 'Revize Et' (anahtar adı yanıltıcı).
    const btn = (await screen.findAllByRole('button', { name: /revise|revize/i }))[0]
    fireEvent.click(btn)

    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.weeklyReports.reopen).not.toHaveBeenCalled()
  })
})
