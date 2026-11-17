import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, fireEvent, waitFor } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * Performans (2026-11-17): Eskalasyon kişileri — arama kutusu. Her tuş vuruşu kapsam matrisini (CoveragePanel, takım ×
 * seviye) yeniden çiziyordu. Matris artık memo (işleyicisi kararlı), liste süzme ertelenir; sonuç yine güncellenir.
 */
const renders = vi.hoisted(() => ({ coverageKpis: 0 }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  api: withApiFallback({ admin: { getContacts: vi.fn(), getUsers: vi.fn(), teamStats: vi.fn() } }),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: vi.fn() }), DialogProvider: ({ children }) => children }))
vi.mock('../components/ui/Toast.jsx', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }), ToastProvider: ({ children }) => children,
}))
// Kapsam matrisinin KPI şeridi (matris açıkken her çiziminde çizilir) — sayaç
vi.mock('../components/MonitorStatsBar.jsx', async () => {
  const React = await import('react')
  return { default: () => { renders.coverageKpis += 1; return React.createElement('div', { 'data-slot': 'test-cov-kpis' }) } }
})
import { api } from '../api/client'
import EscalationContacts from '../components/admin/EscalationContacts.jsx'

const TEAMS = [{ id: 5, name: 'SY-A' }, { id: 6, name: 'SY-B' }]
const CONTACTS = [
  { id: 1, user_id: 7, name: 'Ali Veli', email: 'ali@x.com', role: 'PO', min_alert_level: 'WARNING', active: true, team_id: 5 },
  { id: 2, user_id: 8, name: 'Ayşe Yılmaz', email: 'ayse@x.com', role: 'MANAGER', min_alert_level: 'CRITICAL', active: true, team_id: 5 },
]
const names = () => [...document.querySelectorAll('[data-contact-id]')].map((r) => r.getAttribute('data-contact-id'))

beforeEach(() => {
  vi.clearAllMocks()
  renders.coverageKpis = 0
  window.history.replaceState(null, '', '/')
  api.admin.getContacts.mockResolvedValue({ success: true, data: CONTACTS })
  api.admin.getUsers.mockResolvedValue({ success: true, data: [{ id: 7, active: true }, { id: 8, active: true }] })
  api.admin.teamStats.mockResolvedValue({ success: true, data: { 5: { monitors: 3 }, 6: { monitors: 2 } } })
})

describe('Eskalasyon kişileri — arama matrisi yeniden çizmez', () => {
  it('yazarken kapsam matrisi çizilmez; liste süzülür', async () => {
    render(<LangProvider><EscalationContacts systemRole="ADMIN" teams={TEAMS} /></LangProvider>)
    await waitFor(() => expect(names()).toEqual(['1', '2']))
    await waitFor(() => expect(document.querySelector('[data-slot="test-cov-kpis"]')).not.toBeNull())   // boşluk → matris açık
    await new Promise((r) => setTimeout(r, 50))
    const before = renders.coverageKpis
    const box = document.querySelector('input[type="search"]') || document.querySelector('input')
    let text = ''
    for (const ch of 'ayşe') {
      text += ch
      fireEvent.change(box, { target: { value: text } })
    }
    await waitFor(() => expect(names()).toEqual(['2']))
    expect(renders.coverageKpis).toBe(before)        // eskiden her tuşta +1
  })
})
