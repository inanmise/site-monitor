import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import Login from '../pages/Login.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    login: vi.fn(),
    getPublicStats: vi.fn(async () => ({ success: true, data: { monitored_targets: 1, availability_pct: 99.9 } })),
    getSystemMaintenanceStatus: vi.fn(),
  }),
}))

import { api } from '../api/client'

/**
 * Giriş sayfası — Sistem Bakım Modu kartı (2026-10-02): public uçtan (oturumsuz) yaklaşan / süren bakım; giriş 403
 * MAINTENANCE → hata satırı + kart; `?session=maintenance` (prop) → "oturumunuz sonlandırıldı" kipi.
 */
const status = (state, extra = {}) => ({ success: true, data: { state, start_at: '2026-10-02T19:00:00Z', end_at: '2026-10-02T20:00:00Z',
  message_tr: 'Veritabanı yükseltmesi', message_en: 'Database upgrade', contact: 'IT desk · 1234', server_now: '2026-10-02T12:00:00Z', ...extra } })

describe('Login — sistem bakımı kartı', () => {
  beforeEach(() => { vi.clearAllMocks(); localStorage.clear() })

  it('yaklaşan bakım (duyuru): "Planlı bakım: 02.10.2026 22:00 – 23:00" + mesaj + iletişim', async () => {
    api.getSystemMaintenanceStatus.mockResolvedValue(status('announced'))
    render(<Login onLogin={() => {}} />)
    await waitFor(() => expect(document.querySelector('[data-slot="login-maintenance"]')).not.toBeNull())
    const card = document.querySelector('[data-slot="login-maintenance"]')
    expect(card).toHaveAttribute('data-state', 'announced')
    expect(card).toHaveTextContent(/Planned maintenance: 02\.10\.2026 22:00 – 23:00 \(Istanbul time\)/)
    expect(card).toHaveTextContent(/Database upgrade/)
    expect(card).toHaveTextContent(/IT desk · 1234/)
  })

  it('süren bakım: "Sistem bakımda" + yalnız sistem yöneticileri; bakım yoksa kart YOK', async () => {
    api.getSystemMaintenanceStatus.mockResolvedValue(status('active'))
    const { unmount } = render(<Login onLogin={() => {}} />)
    await waitFor(() => expect(document.querySelector('[data-slot="login-maintenance"]')).toHaveAttribute('data-state', 'active'))
    expect(document.querySelector('[data-slot="login-maintenance"]')).toHaveTextContent(/only system administrators can sign in/)
    unmount()
    api.getSystemMaintenanceStatus.mockResolvedValue({ success: true, data: { state: 'none', server_now: 'x' } })
    render(<Login onLogin={() => {}} />)
    await waitFor(() => expect(api.getSystemMaintenanceStatus).toHaveBeenCalledTimes(2))
    expect(document.querySelector('[data-slot="login-maintenance"]')).toBeNull()
  })

  it('giriş 403 MAINTENANCE → bakım hata satırı (data-code) + kart pencereyle; onLogin çağrılmaz', async () => {
    api.getSystemMaintenanceStatus.mockResolvedValue({ success: true, data: { state: 'none' } })
    api.login.mockResolvedValueOnce({ success: false, code: 'MAINTENANCE', error_code: 'MAINTENANCE', error: 'x',
      maintenance: status('active').data })
    const onLogin = vi.fn()
    render(<Login onLogin={onLogin} />)
    fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'user1' } })
    fireEvent.change(document.getElementById('lp-pass'), { target: { value: 'pw' } })
    fireEvent.submit(document.querySelector('form'))
    await waitFor(() => expect(document.querySelector('[data-code="MAINTENANCE"]')).not.toBeNull())
    expect(document.querySelector('[data-code="MAINTENANCE"]')).toHaveTextContent(/under maintenance/)
    expect(document.querySelector('[data-slot="login-maintenance"]')).toHaveAttribute('data-state', 'active')
    expect(onLogin).not.toHaveBeenCalled()
  })

  it('?session=maintenance (prop): "oturumunuz bakım nedeniyle sonlandırıldı"; bakım bittiyse "yeniden giriş yapabilirsiniz"', async () => {
    api.getSystemMaintenanceStatus.mockResolvedValue({ success: true, data: { state: 'none' } })
    render(<Login onLogin={() => {}} maintenanceEnded />)
    const card = document.querySelector('[data-slot="login-maintenance"]')
    expect(card).toHaveAttribute('data-state', 'ended')
    expect(card).toHaveTextContent(/closed for system maintenance/)
    await waitFor(() => expect(document.querySelector('[data-slot="login-maintenance"]')).toHaveTextContent(/Maintenance is over/))
  })

  // "Bakım tamamlandı" (2026-10-02, kullanıcı isteği): sunucu bildirim süresince `ended` → yeşil "giriş yapabilirsiniz" kartı
  it('bakım tamamlandı (ended): başarı kartı "giriş yapabilirsiniz" + gerçekleşen pencere; yalnız-yönetici cümlesi YOK', async () => {
    api.getSystemMaintenanceStatus.mockResolvedValue(status('ended', { end_at: '2026-10-02T19:40:00Z',
      planned_end_at: '2026-10-02T20:00:00Z' }))
    render(<Login onLogin={() => {}} />)
    await waitFor(() => expect(document.querySelector('[data-slot="login-maintenance"]')).toHaveAttribute('data-state', 'completed'))
    const card = document.querySelector('[data-slot="login-maintenance"]')
    expect(card).toHaveTextContent(/Planned maintenance is complete; you can sign in\./)
    expect(card.querySelector('[data-slot="login-maintenance-window"]'))
      .toHaveTextContent('Maintenance window: 02.10.2026 22:00 – 22:40 (Istanbul time).')
    expect(card.className).toMatch(/bg-green-50/)
    expect(card).not.toHaveTextContent(/only system administrators/)
  })

  it('?session=maintenance + bakım tamamlandı → "oturumunuz kapatıldı" yerine başarı kartı', async () => {
    api.getSystemMaintenanceStatus.mockResolvedValue(status('ended', { end_at: '2026-10-02T19:40:00Z' }))
    render(<Login onLogin={() => {}} maintenanceEnded />)
    await waitFor(() => expect(document.querySelector('[data-slot="login-maintenance"]')).toHaveAttribute('data-state', 'completed'))
    expect(document.querySelector('[data-slot="login-maintenance"]')).toHaveTextContent(/Planned maintenance is complete/)
    expect(document.querySelector('[data-slot="login-maintenance"]')).not.toHaveTextContent(/closed for system maintenance/)
  })
})
