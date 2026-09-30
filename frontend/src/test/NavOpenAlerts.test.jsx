import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { withSidebar } from './helpers/sidebar.jsx'
import Nav from '../components/Nav.jsx'

/**
 * İzleme menüsü — aktif alarm rozetleri (2026-09-30, kullanıcı isteği).
 *
 * Kullanıcı takımının açık alarmlarını menüde, izleme türünün yanında sayı olarak görmek; tıklayınca o türün
 * alarmlarına gitmek; üzerine gelince özet kartı görmek istiyor. Kapsam ve sayı sunucudan (`/api/me/open-alerts`).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    me: { openAlerts: vi.fn(), inbox: vi.fn().mockResolvedValue({ success: true, data: [] }) },
    admin: { getTeams: vi.fn().mockResolvedValue({ success: true, data: [] }) },
  }),
}))
import { api } from '../api/client'

const SUMMARY = {
  success: true,
  data: {
    visible: true, total: 5, sampled: false,
    tabs: {
      http: { count: 2, unacked: 1, levels: { critical: 1, high: 0, warning: 1, other: 0 }, items: [
        { id: 300, domain: 'https://a.example.com', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', created_at: '2026-09-30T13:45:29', team_name: 'SY', acknowledged: false },
        { id: 301, domain: 'https://b.example.com', alert_type: 'HTTP_SSL', alert_level: 'WARNING', created_at: '2026-09-30T12:00:00', team_name: 'SY', acknowledged: true },
      ] },
      scripted: { count: 3, unacked: 3, levels: { critical: 0, high: 0, warning: 3, other: 0 }, items: [
        { id: 414, domain: 'OCPA - Response Time Anomalisi', alert_type: 'SCRIPTED_FAIL', alert_level: 'WARNING', created_at: '2026-09-30T13:45:29', team_name: 'SY', acknowledged: false },
      ] },
      ping: { count: 0, unacked: 0, levels: {}, items: [] },
    },
  },
}

const PROPS = { activeTab: 'dashboard', onTabChange: vi.fn(), username: 'u', onLogout: vi.fn() }

describe('İzleme menüsü — aktif alarm rozetleri', () => {
  beforeEach(() => { vi.clearAllMocks(); try { localStorage.clear() } catch { /* yok */ } })

  it('sekme başına sayı rozeti çizilir (HTTP 2 kritik tonda, Sentetik 3 uyarı tonda); sıfır olan türde rozet yok', async () => {
    api.me.openAlerts.mockResolvedValue(SUMMARY)
    const { container } = render(withSidebar(<Nav {...PROPS} />))
    await waitFor(() => expect(api.me.openAlerts).toHaveBeenCalled())
    // İzleme bölümünü aç
    fireEvent.click(container.querySelector('[data-nav-section-trigger="monitoring"]'))
    const http = await screen.findByRole('button', { name: /HTTP \/ Website: 2 (aktif alarm|active alerts)/i })
    expect(http).toHaveAttribute('data-level', 'critical')
    const scripted = screen.getByRole('button', { name: /(Sentetik İzleme|Synthetic Monitoring): 3 (aktif alarm|active alerts)/i })
    expect(scripted).toHaveAttribute('data-level', 'warning')
    expect(container.querySelector('[data-slot="nav-alert-badge"][data-tab="ping"]')).toBeNull()
    expect(container.querySelector('[data-slot="nav-alert-badge"][data-tab="port"]')).toBeNull()
    // Rozet iç içe düğme DEĞİL: satır düğmesinin içinde bir düğme yok
    expect(container.querySelector('[data-tour="nav-tab-http"] button')).toBeNull()
  })

  it('rozete tıklayınca Alarm Geçmişi açık görünümü o kategoriye süzülmüş açılır (sekme satırı tetiklenmez)', async () => {
    api.me.openAlerts.mockResolvedValue(SUMMARY)
    const { container } = render(withSidebar(<Nav {...PROPS} />))
    await waitFor(() => expect(api.me.openAlerts).toHaveBeenCalled())
    fireEvent.click(container.querySelector('[data-nav-section-trigger="monitoring"]'))
    fireEvent.click(await screen.findByRole('button', { name: /(Sentetik İzleme|Synthetic Monitoring): 3 (aktif alarm|active alerts)/i }))
    expect(PROPS.onTabChange).toHaveBeenCalledWith('alerthistory', { view: 'open', src: 'scripted' })
    expect(PROPS.onTabChange).not.toHaveBeenCalledWith('scripted')
  })

  it('bölüm kapalıyken başlıkta toplam (5) görünür; açılınca toplam kalkar, sekme rozetleri konuşur', async () => {
    api.me.openAlerts.mockResolvedValue(SUMMARY)
    const { container } = render(withSidebar(<Nav {...PROPS} />))
    await waitFor(() => expect(api.me.openAlerts).toHaveBeenCalled())
    const trigger = container.querySelector('[data-nav-section-trigger="monitoring"]')
    await waitFor(() => expect(trigger.querySelector('[data-slot="nav-section-alert-count"]')).not.toBeNull())
    expect(trigger.querySelector('[data-slot="nav-section-alert-count"]').textContent).toBe('5')
    fireEvent.click(trigger)
    await waitFor(() => expect(trigger.querySelector('[data-slot="nav-section-alert-count"]')).toBeNull())
  })

  it('sunucu görünürlük vermezse (alerts.read izni yok) hiçbir rozet çizilmez', async () => {
    api.me.openAlerts.mockResolvedValue({ success: true, data: { ...SUMMARY.data, visible: false } })
    const { container } = render(withSidebar(<Nav {...PROPS} />))
    await waitFor(() => expect(api.me.openAlerts).toHaveBeenCalled())
    fireEvent.click(container.querySelector('[data-nav-section-trigger="monitoring"]'))
    await new Promise((r) => setTimeout(r, 20))
    expect(container.querySelector('[data-slot="nav-alert-badge"]')).toBeNull()
    expect(container.querySelector('[data-slot="nav-section-alert-count"]')).toBeNull()
  })

  it('özet kartı: seviye kırılımı, sahiplenilmemiş sayısı, en yeni alarmlar; alarma tıklayınca o alarm açılır', async () => {
    api.me.openAlerts.mockResolvedValue(SUMMARY)
    const { container } = render(withSidebar(<Nav {...PROPS} />))
    await waitFor(() => expect(api.me.openAlerts).toHaveBeenCalled())
    fireEvent.click(container.querySelector('[data-nav-section-trigger="monitoring"]'))
    const badge = await screen.findByRole('button', { name: /HTTP \/ Website: 2 (aktif alarm|active alerts)/i })
    // Radix HoverCard: pointer enter/move ile açılır (openDelay 250ms)
    fireEvent.pointerEnter(badge)
    fireEvent.pointerMove(badge)
    const peek = await waitFor(() => {
      const el = document.querySelector('[data-slot="nav-alert-peek"][data-tab="http"]')
      if (!el) throw new Error('peek yok')
      return el
    }, { timeout: 2000 })
    expect(peek.querySelector('[data-slot="nav-alert-levels"]').textContent).toMatch(/(KRİTİK|CRITICAL) · 1/)
    expect(peek.querySelector('[data-slot="nav-alert-unacked"]').textContent).toMatch(/1/)
    const rows = peek.querySelectorAll('[data-slot="nav-alert-item"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].textContent).toMatch(/a\.example\.com/)
    fireEvent.click(rows[0])
    expect(PROPS.onTabChange).toHaveBeenCalledWith('alerthistory', { view: 'open', src: 'http', alert: '300' })
  })
})
