import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'

/**
 * L-N1 (2026-10-22, PKG-3 isteği): pasif (devre dışı) nöbetçi 7/24 arama kartında ve alarm ayrıntısında "Şu anki nöbetçi"
 * olarak tel: düğmesiyle gösteriliyordu. Sunucu artık `active:false` + `phone:null` gönderir: "Pasif hesap — aranmaz"
 * rozeti, arama bağlantısı yok. Alan yoksa (eski sunucu) bugünkü davranış.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({ noc: { callSheet: vi.fn() }, oncall: { current: vi.fn() } }),
}))
import { api } from '../api/client'
import NocCallDialog from '../components/noc/console/NocCallDialog.jsx'
import AlertOnCall from '../components/admin/alerts/AlertOnCall.jsx'

const ROW = { id: 500, alert_type: 'HTTP_DOWN', family: 'http', level: 'CRITICAL', domain: 'checkout.example.com',
  created_at: '2026-10-22T08:00:00', resolved: false, team_id: 3, team_name: 'Takım A', monitor: { type: 'HTTP', tab: 'http', id: 70, name: 'Ödeme' },
  channels: {}, noc: null, call_count: 0, can_call: true }
const sheet = (oncall) => ({ success: true, data: { alert_id: 500, team_id: 3, team_name: 'Takım A', call_list_defined: true,
  call_list: [{ name: 'Kişi A', title: null, phone: '0500 000 00 00', position: 1 }], oncall } })
const Fact = ({ label, children }) => <div data-fact={label}>{children}</div>

beforeEach(() => vi.clearAllMocks())

describe('7/24 arama kartı — pasif nöbetçi', () => {
  it('active:false → "Pasif hesap — aranmaz" rozeti, tel: bağlantısı YOK', async () => {
    api.noc.callSheet.mockResolvedValue(sheet({ name: 'Eski Nöbetçi', title: null, phone: null, active: false, until: '2026-10-23T08:00:00' }))
    render(<NocCallDialog row={ROW} open onClose={() => {}} />)
    const oncall = await waitFor(() => { const el = document.querySelector('[data-slot="noc-sheet-oncall"]'); if (!el) throw new Error('yok'); return el })
    expect(oncall.querySelector('[data-slot="noc-sheet-passive"]')).not.toBeNull()
    expect(oncall.querySelector('a[href^="tel:"]')).toBeNull()
    expect(screen.getByText(/pasif hesap — aranmaz|passive account — do not call/i)).toBeInTheDocument()
  })

  it('alan yoksa bugünkü davranış: telefon bağlantısı var, rozet yok', async () => {
    api.noc.callSheet.mockResolvedValue(sheet({ name: 'Nöbetçi', title: null, phone: '0500 111 22 33', until: '2026-10-23T08:00:00' }))
    render(<NocCallDialog row={ROW} open onClose={() => {}} />)
    const oncall = await waitFor(() => { const el = document.querySelector('[data-slot="noc-sheet-oncall"]'); if (!el) throw new Error('yok'); return el })
    expect(oncall.querySelector('a[href^="tel:"]')).not.toBeNull()
    expect(oncall.querySelector('[data-slot="noc-sheet-passive"]')).toBeNull()
  })
})

describe('Alarm ayrıntısı — pasif nöbetçi', () => {
  it('active:false olan nöbetçi rozet taşır; aktif olan taşımaz', async () => {
    api.oncall.current.mockResolvedValue({ success: true, data: [
      { team_id: 1, user_id: 'u1', name: 'Pasif Kişi', until: '2026-10-23T08:00:00', active: false },
      { team_id: 2, user_id: 'u2', name: 'Aktif Kişi', until: '2026-10-23T08:00:00' },
    ] })
    render(<AlertOnCall alert={{ id: 1, resolved: false, owner_team_ids: [1, 2] }} Fact={Fact} />)
    await screen.findByText('Pasif Kişi')
    const items = [...document.querySelectorAll('[data-slot="alert-oncall"] li')]
    expect(items[0].querySelector('[data-slot="alert-oncall-passive"]')).not.toBeNull()
    expect(items[1].querySelector('[data-slot="alert-oncall-passive"]')).toBeNull()
  })
})
