import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen } from './test-utils.jsx'

vi.mock('../utils/accountInactive.js', async (importOriginal) => {
  const mod = await importOriginal()
  return { ...mod, assignLocation: vi.fn() }
})

import { api } from '../api/client.js'
import { assignLocation } from '../utils/accountInactive.js'
import {
  MAINTENANCE_EVENT, MAINTENANCE_STORAGE_KEY, lastWindow, resetMaintenanceSignal,
} from '../utils/systemMaintenance.js'
import MaintenanceStatusNote from '../components/maintenance/MaintenanceStatusNote.jsx'

/**
 * Sistem Bakım Modu — istemci (2026-10-02): 401 MAINTENANCE (oturum bakımda kesildi) → pencere sinyali, YÖNLENDİRME YOK,
 * eşzamanlı 401'ler tek sinyal, oturum bayrağı silinir, öteki sekmelere duyuru; sıradan 401 eskisi gibi. Durum Sayfası notu.
 */
const BODY = {
  success: false, code: 'MAINTENANCE', error_code: 'MAINTENANCE', error: 'Planned maintenance is in progress',
  maintenance: { state: 'active', start_at: '2026-10-02T19:00:00Z', end_at: '2026-10-02T20:00:00Z' },
}
function mockFetch(body, status) {
  global.fetch = vi.fn().mockResolvedValue({ status, ok: status < 400, json: () => Promise.resolve(body) })
}

describe('api/client — 401 MAINTENANCE', () => {
  let events, off
  beforeEach(() => {
    resetMaintenanceSignal()
    vi.mocked(assignLocation).mockClear()
    sessionStorage.setItem('sm.session.active', '1')
    localStorage.removeItem(MAINTENANCE_STORAGE_KEY)
    events = 0
    const h = () => { events++ }
    window.addEventListener(MAINTENANCE_EVENT, h)
    off = () => window.removeEventListener(MAINTENANCE_EVENT, h)
  })
  afterEach(() => { off(); sessionStorage.clear() })

  it('bakım sinyali: yönlendirme YOK, oturum bayrağı silinir, tek sinyal, öteki sekmelere duyuru, pencere bilgisi saklanır', async () => {
    mockFetch(BODY, 401)
    const rs = await Promise.all([api.sessionPing('dashboard'), api.getCertificates(), api.me.inbox()])
    expect(rs).toEqual([null, null, null])
    expect(events).toBe(1)
    expect(assignLocation).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('sm.session.active')).toBeNull()
    expect(localStorage.getItem(MAINTENANCE_STORAGE_KEY)).toBeTruthy()
    expect(lastWindow().end_at).toBe('2026-10-02T20:00:00Z')
  })

  it('sıradan 401 (bakım değil) eskisi gibi /?session=expired', async () => {
    mockFetch({ success: false, error: 'Session superseded' }, 401)
    await api.getCertificates()
    expect(assignLocation).toHaveBeenCalledWith('/?session=expired')
    expect(events).toBe(0)
  })
})

describe('Durum Sayfası — sistem bakımı notu', () => {
  it('planlı bakım duyurusu: "Planlı sistem bakımı" + pencere; bakım yokken çizilmez', async () => {
    const { rerender } = render(<MaintenanceStatusNote note={{ state: 'announced', start_at: '2026-10-02T19:00:00Z',
      end_at: '2026-10-02T20:00:00Z', message_en: 'DB upgrade' }} />)
    const note = await screen.findByText((_, el) => el?.getAttribute?.('data-slot') === 'sp-system-maintenance')
    expect(note).toHaveAttribute('data-state', 'announced')
    expect(note).toHaveTextContent(/Planned system maintenance/)
    expect(note).toHaveTextContent(/02\.10\.2026 22:00 – 23:00 \(Istanbul time\)/)
    expect(note).toHaveTextContent(/DB upgrade/)
    rerender(<MaintenanceStatusNote note={{ state: 'none' }} />)
    expect(document.querySelector('[data-slot="sp-system-maintenance"]')).toBeNull()
  })

  it('bakım tamamlandı (ended, 2026-10-02): başarı tonunda "Planlı bakım tamamlandı (başlangıç – gerçek bitiş)"', async () => {
    render(<MaintenanceStatusNote note={{ state: 'ended', start_at: '2026-10-02T19:00:00Z', end_at: '2026-10-02T19:40:00Z',
      planned_end_at: '2026-10-02T20:00:00Z', message_en: 'DB upgrade' }} />)
    const note = await screen.findByText((_, el) => el?.getAttribute?.('data-slot') === 'sp-system-maintenance')
    expect(note).toHaveAttribute('data-state', 'ended')
    expect(note.querySelector('[data-slot="alert"]')).toHaveAttribute('data-tone', 'success')
    expect(note).toHaveTextContent(/Planned maintenance completed \(02\.10\.2026 22:00 – 22:40, Istanbul time\)/)
    expect(note).toHaveTextContent(/SiteMonitor is available again\. · DB upgrade/)
  })
})
