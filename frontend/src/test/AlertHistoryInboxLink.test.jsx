import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor } from './test-utils.jsx'

/** Kart listesinde alan adı birden çok yerde geçer (başlık + mesaj) — kart SAYISIYLA ölçeriz. */
const cardsWith = (re) => [...document.querySelectorAll('.alert-card')].filter((c) => re.test(c.textContent))

/**
 * Bildirim kutusu → Alarm Geçmişi derin bağlantısı (2026-09-16).
 *
 * İki kusur pinli:
 *  1) Bildirim "Uyarılar" (sertifika) sayfasına gidiyordu — artık alerthistory + tip/arama/olay id.
 *  2) Sayfa AÇIKKEN ikinci bildirime tıklayınca URL değişiyor ama liste eski alarmda kalıyordu
 *     (bileşen mount'ta kaldığı için süzgeçler yeniden okunmuyordu).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({
    admin: {
      getAlerts: vi.fn(),
      getTeams: vi.fn().mockResolvedValue({ success: true, data: [] }),
      getAlertsCsvUrl: vi.fn(() => '/api/admin/alerts/export'),
    },
  }),
}))
import { api } from '../api/client'
import AlertHistory from '../components/admin/AlertHistory.jsx'

const alert = (id, type, domain) => ({
  id, alert_type: type, alert_level: 'CRITICAL', domain, resolved: false,
  created_at: '2026-09-15T10:00:00', message: `${type} · ${domain}`,
})
const PING = alert(87, 'PING_DOWN', 'ping.example.com')
const KEYWORD = alert(78, 'KEYWORD_SSL', 'keyword.example.com')

const setUrl = (qs) => window.history.replaceState({}, '', `/?${qs}`)
const navigate = (params) => window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'alerthistory', params } }))

describe('AlertHistory — bildirim derin bağlantısı', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getAlerts.mockImplementation(async (params) => {
      const all = [PING, KEYWORD]
      const hit = params?.alertType ? all.filter((a) => a.alert_type === params.alertType) : all
      return { success: true, data: hit, total: hit.length, page: 0, size: 50 }
    })
  })
  afterEach(() => setUrl('tab=alerthistory'))

  it('mount: ?alert=<id> kartı vurgular, süzgeç sunucuya gider', async () => {
    setUrl('tab=alerthistory&type=KEYWORD_SSL&q=keyword.example.com&alert=78')
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(cardsWith(/keyword\.example\.com/).length).toBe(1))
    await waitFor(() => expect(document.querySelector('.alert-card.alh-card-linked')).not.toBeNull())
    expect(api.admin.getAlerts.mock.calls[0][0]).toMatchObject({ alertType: 'KEYWORD_SSL', q: 'keyword.example.com' })
    // param tüketilir: sekme dönüşünde eski alarm yeniden vurgulanmasın
    await waitFor(() => expect(window.location.search).not.toContain('alert=78'))
  })

  it('AÇIKKEN ikinci bildirim: sm:navigate paramları uygulanır, yeni alarm vurgulanır (eski kart kalmaz)', async () => {
    setUrl('tab=alerthistory&type=KEYWORD_SSL&q=keyword.example.com&alert=78')
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(document.querySelector('.alert-card.alh-card-linked')).not.toBeNull())
    expect(cardsWith(/keyword\.example\.com/).length).toBe(1)

    navigate({ alert: 87, type: 'PING_DOWN', q: 'ping.example.com' })

    await waitFor(() => expect(cardsWith(/ping\.example\.com/).length).toBe(1))
    expect(cardsWith(/keyword\.example\.com/)).toHaveLength(0)   // ESKİ kart ekranda kalmaz (asıl kusur)
    await waitFor(() => {
      const linked = document.querySelector('.alert-card.alh-card-linked')
      expect(linked).not.toBeNull()
      expect(linked.textContent).toMatch(/ping.example.com/)
    })
    const last = api.admin.getAlerts.mock.calls.at(-1)[0]
    expect(last).toMatchObject({ alertType: 'PING_DOWN', q: 'ping.example.com', resolved: 'false' })
  })

  it('çözülen bildirim: view=closed kapalı sekmeye geçirir', async () => {
    setUrl('tab=alerthistory')
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())

    navigate({ alert: 78, type: 'KEYWORD_SSL', q: 'keyword.example.com', view: 'closed' })

    await waitFor(() => expect(api.admin.getAlerts.mock.calls.at(-1)[0]).toMatchObject({ resolved: 'true', alertType: 'KEYWORD_SSL' }))
  })
})
