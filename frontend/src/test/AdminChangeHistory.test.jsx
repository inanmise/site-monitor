import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import AdminChangeHistory from '../components/admin/AdminChangeHistory.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    admin: { history: vi.fn() },
  }),
}))
import { api } from '../api/client'

/**
 * Yönetim Paneli "Değişiklik Geçmişi" (2026-09-20): KAPALI başlar (bedava denetim sorgusu yok), açılınca
 * kaynağa göre yüklenir; satır süzgeci bölümü kendiliğinden açar; kullanıcı geçmişi admin dışına kapalı.
 */
const ITEMS = [
  { id: 1, at: '2026-09-20T10:00:00', actor: 'admin', action: 'UPDATE', event_type: 'TEAM_UPDATE', resource_id: '7',
    name: 'Takım A', team_id: 7, team_name: 'Takım A', changes: '{"leaderId":{"from":3,"to":5}}' },
  { id: 2, at: '2026-09-19T10:00:00', actor: 'admin', action: 'WEEKLY_NOTIFICATIONS', event_type: 'TEAM_WEEKLY_NOTIFICATIONS',
    resource_id: '7', name: 'Takım A', team_id: 7, team_name: 'Takım A', changes: null },
  { id: 3, at: '2026-09-18T10:00:00', actor: 'admin', action: 'FROBNICATE', event_type: 'TEAM_FROBNICATE',
    resource_id: '9', name: null, changes: null },
]

describe('AdminChangeHistory', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.history.mockResolvedValue({ success: true, items: ITEMS, truncated: false, hidden: 0 })
  })

  it('kapalı başlar: sorgu atılmaz; "Geçmişi göster" ile kaynağa göre yüklenir', async () => {
    render(<AdminChangeHistory resource="TEAM" />)
    expect(api.admin.history).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /Geçmişi göster|Show history/i }))
    await waitFor(() => expect(api.admin.history).toHaveBeenCalledWith('TEAM', null))
    // Etiketler: bilinen eylem çevrilir, alan adı çevrilir, bilinmeyen eylem HAM adıyla düşer (kaybolmaz)
    expect(await screen.findByText(/düzenledi|edited/)).toBeInTheDocument()
    expect(screen.getByText(/Lider|Leader/)).toBeInTheDocument()
    expect(screen.getByText(/haftalık e-postayı değiştirdi|changed weekly emails/)).toBeInTheDocument()
    expect(screen.getByText(/frobnicate/)).toBeInTheDocument()
    expect(screen.getByText(/#9/)).toBeInTheDocument()   // adı olmayan kayıt kimliğiyle
  })

  it('satır süzgeci verilince bölüm kendiliğinden açılır ve resourceId ile sorgular; temizle → tümü', async () => {
    const clear = vi.fn()
    const { rerender } = render(<AdminChangeHistory resource="USER" filter={{ id: 42, name: 'ali' }} onClearFilter={clear} />)
    await waitFor(() => expect(api.admin.history).toHaveBeenCalledWith('USER', 42))
    fireEvent.click(await screen.findByRole('button', { name: /Tümünü göster|Show everything/i }))
    expect(clear).toHaveBeenCalled()
    rerender(<AdminChangeHistory resource="USER" filter={null} onClearFilter={clear} />)
    await waitFor(() => expect(api.admin.history).toHaveBeenCalledWith('USER', null))
  })

  it('canView=false → hiçbir şey çizilmez, sorgu yok', () => {
    const { container } = render(<AdminChangeHistory resource="USER" canView={false} />)
    expect(container.querySelector('[data-testid="admin-history"]')).toBeNull()
    expect(api.admin.history).not.toHaveBeenCalled()
  })

  it('yükleme hatası görünür', async () => {
    api.admin.history.mockResolvedValue({ success: false, error: 'yetki yok' })
    render(<AdminChangeHistory resource="ESCALATION_CONTACT" />)
    fireEvent.click(screen.getByRole('button', { name: /Geçmişi göster|Show history/i }))
    expect(await screen.findByText(/yetki yok/)).toBeInTheDocument()
  })
})
