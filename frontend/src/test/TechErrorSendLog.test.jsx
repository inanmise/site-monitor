import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel } from './helpers/techErrorFixtures.js'

// R18 / E22 (2026-10-18): kanal gönderim günlüğü sekmesi (`te_tab=sendlog`) — tablo (jsdom kap genişliği 0 → tablo),
// alıcılar TAM, durum rozeti + kayıtlı durum metni, tür / durum süzgeci (sayfa başa döner), sunucu sayfalaması ve derin
// bağlantı (`te_slp`), yalnız paylaşımla okuyana sayılar, okuma hatası "kayıt yok" değil (tehlike kutusu + yeniden dene),
// boş durum, sekmeden çıkınca günlük anahtarları adresten silinir.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: { reports: vi.fn(), sendLog: vi.fn(), template: vi.fn(), imageUrl: (id) => `/api/tech-error-reports/images/${id}` },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors

const LONG_TO = Array.from({ length: 12 }, (_, i) => `cok.uzun.bir.adres.${i}@dijital-kanallar-uygulama-gelistirme.example.com`)

function row(id, extra = {}) {
  return {
    id, at: '2026-10-13T08:00:00Z', kind: 'report', trigger: 'TECH_ERROR_REPORT', role: 'TEAM', status: 'sent', status_text: 'SENT',
    report_id: 100, year: 2026, month: 9, to: ['dijital@example.com'], cc: [], to_count: 1, cc_count: 0, ...extra,
  }
}

function page(rows, extra = {}) {
  return { success: true, data: { rows, total: rows.length, page: 0, size: 25, total_pages: 1, kind: 'all', status: 'all', recipients_hidden: false, ...extra } }
}

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=sendlog')
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().reports.mockResolvedValue({ success: true, data: { channel: channel(10, 'Mobil'), reports: [], default_period: { year: 2026, month: 9 } } })
})

describe('kanal gönderim günlüğü', () => {
  it('derin bağlantıyla açılır: tablo, alıcılar tam (uzun liste sarılır), Bilgi, rozetler, rol / dönem, kayıtlı durum metni', async () => {
    te().sendLog.mockResolvedValue(page([
      row(3, { kind: 'test', trigger: 'TECH_ERROR_REPORT_TEST', role: 'SELF', to: ['ben@example.com'] }),
      row(2, { status: 'failed', status_text: 'FAILED: 550 alıcı reddedildi', to: LONG_TO, cc: ['ek@example.com'], to_count: 12, cc_count: 1 }),
      row(1, { kind: 'approval', trigger: 'TECH_ERROR_APPROVAL', role: 'APPROVER', status: 'queued', status_text: 'QUEUED_RETRY: 421', year: null, month: null }),
    ], { total: 3 }))
    render(<TechErrorReportsPage />)
    const log = await waitFor(() => { const el = document.querySelector('[data-slot="te-sendlog"]'); if (!el) throw new Error('log'); return el })
    await waitFor(() => expect(log.querySelectorAll('[data-slot="te-sendlog-row"]')).toHaveLength(3))
    expect(te().sendLog).toHaveBeenCalledWith('10', { page: 0, size: 25, kind: 'all', status: 'all' })
    expect(log).toHaveAttribute('data-layout', 'table')
    expect(log.querySelector('[data-slot="te-sendlog-table"]')).not.toBeNull()
    expect(screen.getByRole('tab', { name: 'Gönderim günlüğü' })).toHaveAttribute('data-state', 'active')
    const rows = log.querySelectorAll('[data-slot="te-sendlog-row"]')
    expect(rows[0]).toHaveAttribute('data-kind', 'test')
    expect(within(rows[0]).getByText('Test postası')).toBeInTheDocument()
    expect(within(rows[0]).getByText(/Kendine test · Eylül 2026/)).toBeInTheDocument()
    expect(rows[1]).toHaveAttribute('data-status', 'failed')
    expect(within(rows[1]).getByText('Gönderilemedi')).toBeInTheDocument()
    // Uzun alıcı listesi kırpılmadan, hepsi yazılır
    const recipients = within(rows[1]).getByText(LONG_TO[11], { exact: false })
    expect(recipients.textContent).toContain(LONG_TO.join(', '))
    expect(recipients.className).toContain('[overflow-wrap:anywhere]')
    expect(rows[1].querySelector('[data-slot="te-sendlog-cc"]')).toHaveTextContent('ek@example.com')
    expect(within(rows[1]).getByText('Kayıtlı durum: FAILED: 550 alıcı reddedildi')).toBeInTheDocument()
    expect(within(rows[2]).getByText('Kuyrukta')).toBeInTheDocument()
    expect(within(rows[2]).getByText('Onaylayıcı (PO)')).toBeInTheDocument()     // dönem bilinmiyor → yalnız rol
    expect(rows[0].querySelector('[data-slot="te-sendlog-status-text"]')).toBeNull()  // SENT ayrıca yazılmaz
  })

  it('süzgeçler: tür ve durum değişince istek yeni süzgeçle, sayfa başa; adres anahtarları yazılır; temizle', async () => {
    te().sendLog.mockResolvedValue(page([row(1)]))
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(te().sendLog).toHaveBeenCalledTimes(1))
    fireEvent.change(screen.getByLabelText('Tür'), { target: { value: 'test' } })
    await waitFor(() => expect(te().sendLog).toHaveBeenLastCalledWith('10', { page: 0, size: 25, kind: 'test', status: 'all' }))
    fireEvent.change(screen.getByLabelText('Durum'), { target: { value: 'failed' } })
    await waitFor(() => expect(te().sendLog).toHaveBeenLastCalledWith('10', { page: 0, size: 25, kind: 'test', status: 'failed' }))
    await waitFor(() => expect(window.location.search).toContain('te_slk=test'))
    expect(window.location.search).toContain('te_sls=failed')
    fireEvent.click(screen.getByRole('button', { name: 'Süzgeçleri temizle' }))
    await waitFor(() => expect(te().sendLog).toHaveBeenLastCalledWith('10', { page: 0, size: 25, kind: 'all', status: 'all' }))
  })

  it('sunucu sayfalaması: te_slp=2 derin bağlantısı API sayfa 1; çubukta sonraki sayfa isteği', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=sendlog&te_slp=2')
    const rows = Array.from({ length: 25 }, (_, i) => row(100 - i))
    te().sendLog.mockImplementation(async (_id, { page: p }) => page(rows, { total: 60, page: p, total_pages: 3 }))
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(te().sendLog).toHaveBeenCalledWith('10', { page: 1, size: 25, kind: 'all', status: 'all' }))
    expect(te().sendLog).not.toHaveBeenCalledWith('10', expect.objectContaining({ page: 0 }))
    await screen.findByText(/26–50/)
    fireEvent.click(screen.getByRole('button', { name: 'Sonraki' }))
    await waitFor(() => expect(te().sendLog).toHaveBeenLastCalledWith('10', { page: 2, size: 25, kind: 'all', status: 'all' }))
  })

  it('yalnız paylaşımla okuyan: adres yok, sayılar + bilgi şeridi; durum metni yok', async () => {
    te().sendLog.mockResolvedValue(page([row(1, { to: null, cc: null, status_text: null, to_count: 3, cc_count: 2, status: 'skipped' })], { recipients_hidden: true }))
    render(<TechErrorReportsPage />)
    expect(await screen.findByText('Alıcı adresleri gizli')).toBeInTheDocument()
    const r = await waitFor(() => { const el = document.querySelector('[data-slot="te-sendlog-row"]'); if (!el) throw new Error('row'); return el })
    expect(r.querySelector('[data-slot="te-sendlog-counts"]')).toHaveTextContent('3 alıcı · 2 bilgi (CC)')
    expect(r.querySelector('[data-slot="te-sendlog-recipients"]')).toBeNull()
    expect(r.querySelector('[data-slot="te-sendlog-status-text"]')).toBeNull()
    expect(within(r).getByText('Atlandı')).toBeInTheDocument()
  })

  it('okuma hatası "kayıt yok" değildir: tehlike kutusu + yeniden dene; yeniden denemede satırlar gelir; sonra boş günlük boş durumu', async () => {
    te().sendLog
      .mockResolvedValueOnce({ success: false, status: 503, error: 'Sunucu şu anda yanıt vermiyor; birkaç dakika sonra deneyin.' })
      .mockResolvedValueOnce(page([row(1)]))
    render(<TechErrorReportsPage />)
    expect(await screen.findByText('Gönderim günlüğü okunamadı')).toBeInTheDocument()
    expect(screen.queryByText('Henüz gönderim yok')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Tekrar dene' }))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="te-sendlog-row"]')).toHaveLength(1))
    expect(screen.queryByText('Gönderim günlüğü okunamadı')).toBeNull()
    // Yenileme hatası: satırlar kalır, üstte uyarı
    te().sendLog.mockResolvedValueOnce({ success: false, status: 503, error: 'Sunucu şu anda yanıt vermiyor; birkaç dakika sonra deneyin.' })
    fireEvent.click(screen.getByRole('button', { name: 'Yenile' }))
    expect(await screen.findByText('Günlük yenilenemedi; son okunan sayfayı görüyorsunuz')).toBeInTheDocument()
    expect(document.querySelectorAll('[data-slot="te-sendlog-row"]')).toHaveLength(1)
  })

  it('boş günlük: boş durum metni; süzgeçliyken "süzgece uyan yok"', async () => {
    te().sendLog.mockResolvedValue(page([], { total: 0 }))
    render(<TechErrorReportsPage />)
    expect(await screen.findByText('Henüz gönderim yok')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Durum'), { target: { value: 'queued' } })
    expect(await screen.findByText('Süzgece uyan gönderim yok')).toBeInTheDocument()
  })

  it('sekme geçişi: Raporlar sekmesinden günlüğe — te_tab=sendlog; günlükten raporlara dönünce günlük anahtarları silinir', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10')
    te().sendLog.mockResolvedValue(page([row(1)]))
    render(<TechErrorReportsPage />)
    const tab = await screen.findByRole('tab', { name: 'Gönderim günlüğü' })
    expect(te().sendLog).not.toHaveBeenCalled()
    fireEvent.mouseDown(tab, { button: 0 })
    fireEvent.click(tab)
    await waitFor(() => expect(te().sendLog).toHaveBeenCalled())
    await waitFor(() => expect(window.location.search).toContain('te_tab=sendlog'))
    fireEvent.change(screen.getByLabelText('Tür'), { target: { value: 'approval' } })
    await waitFor(() => expect(window.location.search).toContain('te_slk=approval'))
    const reportsTab = screen.getByRole('tab', { name: 'Raporlar' })
    fireEvent.mouseDown(reportsTab, { button: 0 })
    fireEvent.click(reportsTab)
    await waitFor(() => expect(window.location.search).not.toContain('te_tab='))
    await waitFor(() => expect(window.location.search).not.toContain('te_slk'))
  })
})
