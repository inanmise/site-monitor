import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

/**
 * Alarm Geçmişi ↔ 7/24 iletimi (2026-10-04): "7/24'e gidenler" süzgeci (araç çubuğu anahtarı → sunucuya `noc=sent`, çip,
 * URL `noc`), satır/kart/detay rozeti "7/24 · hh:mm" (alarmı gören HERKES — operatör olmayan üye de), bildirim günlüğünde
 * 7/24 e-postasının ayrı işareti ("7/24 ekibine iletildi · hh:mm") ve bölüm başlığındaki özet.
 */
let MOBILE = false
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => MOBILE }))

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({
    admin: {
      getAlerts: vi.fn(), getAlert: vi.fn(), getTeams: vi.fn(), getAlertNotifications: vi.fn(), getAlertPushDeliveries: vi.fn(),
      getAlertsCsvUrl: vi.fn(() => '/api/admin/alerts/export'),
    },
    nocCalls: { list: vi.fn(), contacts: vi.fn() },
  }),
}))
import { api } from '../api/client'
import AlertHistory from '../components/admin/AlertHistory.jsx'
import { NotifLogCard, isNocLog, nocDelivered } from '../components/admin/alerts/AlertNotifications.jsx'
import { toIso, clockTime } from '../components/admin/alerts/nocCallModel.js'
import { FILTER_DEFAULTS, filtersFromUrl, listParams, activeAlertFilters } from '../components/admin/alerts/alertHistoryModel.js'

const ago = (m) => toIso(Date.now() - m * 60_000)
const SENT_AT = ago(40)
const OPEN = {
  id: 50, domain: 'a.example.com', alert_type: 'PING_DOWN', alert_level: 'CRITICAL', resolved: false, acknowledged: false,
  created_at: ago(45), team_id: 1, noc_sent_at: SENT_AT, noc_via_storm: false,
}
const PLAIN = { ...OPEN, id: 51, domain: 'b.example.com', noc_sent_at: null }
const listCalls = () => api.admin.getAlerts.mock.calls.map(([p]) => p).filter((p) => p?.size !== 1)

beforeEach(() => {
  vi.clearAllMocks()
  MOBILE = false
  window.history.replaceState({}, '', '/?tab=alerthistory')
  api.admin.getAlerts.mockImplementation(async (p) => {
    if (p?.size === 1) return { success: true, data: [], total: 0, level_counts: {} }
    const data = p?.noc === 'sent' ? [OPEN] : [OPEN, PLAIN]
    return { success: true, data, total: data.length, page: 0, size: 20, noc_can_write: false }
  })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [] })
  api.admin.getAlertPushDeliveries.mockResolvedValue({ success: true, data: [] })
  api.nocCalls.list.mockResolvedValue({ success: true, data: [] })
})
afterEach(() => window.history.replaceState({}, '', '/'))

const card = (id) => document.querySelector(`[data-alert-card][data-alert-id="${id}"]`)

describe('7/24 rozeti + süzgeç', () => {
  it('7/24\'e iletilen alarmın kartında "24/7 · hh:mm"; iletilmeyende rozet yok (operatör olmayan izleyicide de)', async () => {
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(card(50)).not.toBeNull())
    const badge = card(50).querySelector('[data-slot="alert-noc-sent"]')
    expect(badge).not.toBeNull()
    expect(badge.textContent).toContain(`24/7 · ${clockTime(SENT_AT, 'en-GB')}`)
    expect(card(51).querySelector('[data-slot="alert-noc-sent"]')).toBeNull()
  })

  it('"Sent to 24/7" anahtarı sunucuya noc=sent gönderir, çip + URL; çip × ile kalkar', async () => {
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(card(50)).not.toBeNull())
    const toggle = document.querySelector('[data-slot="alert-noc-filter"]')
    expect(toggle).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(toggle)
    await waitFor(() => expect(listCalls().at(-1)).toMatchObject({ noc: 'sent' }))
    await waitFor(() => expect(card(51)).toBeNull())
    expect(document.querySelector('[data-slot="alert-noc-filter"]')).toHaveAttribute('aria-pressed', 'true')
    const chip = document.querySelector('[data-slot="active-filters"] [data-filter="noc"]')
    expect(chip).toHaveTextContent('Sent to 24/7')
    await waitFor(() => expect(window.location.search).toMatch(/noc=sent/))
    fireEvent.click(within(chip).getByRole('button', { name: /Sent to 24\/7/ }))
    await waitFor(() => expect(listCalls().at(-1).noc).toBeUndefined())
  })

  it('derin bağlantı ?noc=sent açılışta süzgeci uygular; model: yalnız "sent" geçerli', async () => {
    window.history.replaceState({}, '', '/?tab=alerthistory&noc=sent')
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(listCalls()[0]).toMatchObject({ noc: 'sent' }))
    const r = (qs) => { const p = new URLSearchParams(qs); return (k, d) => p.get(k) ?? d }
    expect(filtersFromUrl(r('noc=x')).noc).toBe('')
    expect(listParams({ tab: 'open', filters: { ...FILTER_DEFAULTS, noc: 'sent' }, page: 0, pageSize: 20 })).toMatchObject({ noc: 'sent' })
    expect(activeAlertFilters({ ...FILTER_DEFAULTS, noc: 'sent' }, 'open').map((f) => f.key)).toEqual(['noc'])
  })
})

describe('bildirim günlüğünde 7/24 işareti', () => {
  const NOC_LOG = {
    id: 7, trigger: 'NOC_OPEN', recipient_name: '7/24 İzleme Ekibi', recipient_role: 'NOC', recipient_email: 'NOC Ana (2 adres)',
    email_status: 'SENT', sent_at: SENT_AT, subject: '[7/24] a.example.com', message: 'x',
  }
  it('7/24 e-postası kartında "Sent to the 24/7 team · hh:mm" ve kendi tetik etiketi; atlanan 7/24 satırında işaret yok', () => {
    render(<NotifLogCard log={NOC_LOG} alertLevel="CRITICAL" />)
    const mark = document.querySelector('[data-slot="notif-noc-delivered"]')
    expect(mark).toHaveTextContent(`Sent to the 24/7 team · ${clockTime(SENT_AT, 'en-GB')}`)
    expect(document.querySelector('[data-notif-card="noc"]')).not.toBeNull()
    expect(screen.getByText('24/7 opening')).toBeInTheDocument()
  })
  it('atlanan / başarısız 7/24 satırı "iletildi" sayılmaz; yardımcılar', () => {
    render(<NotifLogCard log={{ ...NOC_LOG, email_status: 'SKIPPED: sistem bakımı' }} alertLevel="CRITICAL" />)
    expect(document.querySelector('[data-slot="notif-noc-delivered"]')).toBeNull()
    expect(isNocLog(NOC_LOG)).toBe(true)
    expect(isNocLog({ recipient_role: 'MANAGER' })).toBe(false)
    expect(nocDelivered('SENT_VIA_STORM')).toBe(true)
    expect(nocDelivered('QUEUED_RETRY: 421')).toBe(true)
    expect(nocDelivered('FAILED: x')).toBe(false)
  })

  it('alarm detayı: başlıkta uzun 7/24 rozeti, bildirimler bölüm başlığında "Sent to 24/7" özeti', async () => {
    api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [NOC_LOG] })
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(card(50)).not.toBeNull())
    fireEvent.click(within(card(50)).getAllByRole('button', { name: /a\.example\.com/ })[0])
    const detail = await waitFor(() => {
      const el = document.querySelector('[data-slot="alert-detail"][data-alert-id="50"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(detail.querySelector('[data-slot="alert-noc-sent"]').textContent).toMatch(/Sent to the 24\/7 team/)
    await waitFor(() => expect(detail.querySelector('[data-slot="notif-noc-summary"]')).toHaveTextContent('Sent to 24/7'))
  })
})
