import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, waitFor, within } from './test-utils.jsx'
import { channel } from './helpers/techErrorFixtures.js'

// R18 / E22: gönderim günlüğü dar kapta (telefon / tablet, < 760 px) KART düzeninde — tablo yok; kartta zaman, durum,
// tür / rol / dönem, alıcılar (sarılır; sığmayanlar "+N adres daha") ve sebep metni (ham durum metni yok); paylaşımla okuyana
// sayılar. Genişlik kancası 400 px.
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, 400], useElementWidthState: () => [400, () => {}] }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: { reports: vi.fn(), sendLog: vi.fn(), imageUrl: (id) => `/api/tech-error-reports/images/${id}` },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const LONG = Array.from({ length: 8 }, (_, i) => `cok.uzun.bir.adres.${i}@dijital-kanallar-uygulama-gelistirme.example.com`)

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=sendlog')
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().reports.mockResolvedValue({ success: true, data: { channel: channel(10, 'Mobil'), reports: [], default_period: { year: 2026, month: 9 } } })
})

describe('gönderim günlüğü — dar kap (kartlar)', () => {
  it('kartlar çizilir, tablo yok; uzun alıcı listesi sarılır, sığmayanlar sayıyla; sebep metni', async () => {
    te().sendLog.mockResolvedValue({ success: true, data: {
      rows: [{ id: 1, at: '2026-10-13T08:00:00Z', kind: 'report', trigger: 'TECH_ERROR_REPORT', role: 'EXECUTIVE', status: 'failed',
        reason: 'SMTP_FAILED', report_id: 100, year: 2026, month: 9, to: LONG.slice(0, 3), to_more: 5, cc: ['takim@example.com'], cc_more: 0,
        to_count: 8, cc_count: 1 }],
      total: 1, page: 0, size: 25, total_pages: 1, recipients_hidden: false } })
    render(<TechErrorReportsPage />)
    const log = await waitFor(() => { const el = document.querySelector('[data-slot="te-sendlog"]'); if (!el) throw new Error('log'); return el })
    await waitFor(() => expect(log.querySelectorAll('[data-slot="te-sendlog-row"]')).toHaveLength(1))
    expect(log).toHaveAttribute('data-layout', 'cards')
    expect(log.querySelector('[data-slot="te-sendlog-table"]')).toBeNull()
    expect(log.querySelector('[data-slot="te-sendlog-cards"]')).not.toBeNull()
    const card = log.querySelector('[data-slot="te-sendlog-row"]')
    expect(within(card).getByText('Gönderilemedi')).toBeInTheDocument()
    expect(within(card).getByText(/Yöneticiler \(executive\) · Eylül 2026/)).toBeInTheDocument()
    const to = card.querySelector('[data-slot="te-sendlog-recipients"] p')
    expect(to.textContent).toContain(LONG.slice(0, 3).join(', '))
    expect(to.className).toContain('[overflow-wrap:anywhere]')
    expect(card.querySelector('[data-slot="te-sendlog-to-more"]')).toHaveTextContent('+5 adres daha')
    expect(card.querySelector('[data-slot="te-sendlog-cc"]')).toHaveTextContent('takim@example.com')
    expect(card.querySelector('[data-slot="te-sendlog-reason"]')).toHaveTextContent('Posta sunucusu iletiyi kabul etmedi')
    expect(card.textContent).not.toMatch(/554|FAILED/)
  })

  it('paylaşımla okuyan: kartta yalnız sayılar', async () => {
    te().sendLog.mockResolvedValue({ success: true, data: {
      rows: [{ id: 1, at: '2026-10-13T08:00:00Z', kind: 'approval', role: 'APPROVER', status: 'sent', reason: null,
        to: null, cc: null, to_count: 2, cc_count: 0 }],
      total: 1, page: 0, size: 25, total_pages: 1, recipients_hidden: true } })
    render(<TechErrorReportsPage />)
    const card = await waitFor(() => { const el = document.querySelector('[data-slot="te-sendlog-row"]'); if (!el) throw new Error('row'); return el })
    expect(card.querySelector('[data-slot="te-sendlog-counts"]')).toHaveTextContent('2 alıcı · 0 bilgi (CC)')
    expect(card.querySelector('[data-slot="te-sendlog-recipients"]')).toBeNull()
    expect(document.querySelector('[data-slot="te-sendlog-hidden"]')).not.toBeNull()
  })
})
