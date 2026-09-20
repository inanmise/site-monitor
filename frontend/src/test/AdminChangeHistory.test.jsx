import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
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
 * Yönetim Paneli "Değişiklik Geçmişi" v2 (2026-09-20): KAPALI başlar (bedava denetim sorgusu yok); tablo + sunucu
 * sayfalama + olay türü çipleri; satırda özet fark, açılınca tam fark; bilinmeyen eylem ham adıyla; süzgeç.
 */
const ITEMS = [
  { id: 1, at: '2026-09-20T10:00:00', actor: 'admin', action: 'UPDATE', event_type: 'TEAM_UPDATE', resource_id: '7',
    name: 'Takım A', team_id: 7, team_name: 'Takım A', changes: '{"leaderId":{"from":3,"to":5},"email":{"from":"a@example.com","to":"b@example.com"},"active":{"from":true,"to":false}}' },
  { id: 2, at: '2026-09-19T10:00:00', actor: 'admin', action: 'WEEKLY_NOTIFICATIONS', event_type: 'TEAM_WEEKLY_NOTIFICATIONS',
    resource_id: '7', name: 'Takım A', team_id: 7, team_name: 'Takım A', changes: null },
  { id: 3, at: '2026-09-18T10:00:00', actor: 'admin', action: 'FROBNICATE', event_type: 'TEAM_FROBNICATE',
    resource_id: '9', name: null, changes: '{"name":"x","teamId":9}' },
]
const RES = { success: true, items: ITEMS, total: 60, page: 0, size: 25, total_pages: 3, truncated: false, hidden: 0,
  types: ['TEAM_CREATE', 'TEAM_UPDATE', 'TEAM_DELETE'] }

describe('AdminChangeHistory v2', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.history.mockResolvedValue(RES)
  })

  it('kapalı başlar; açılınca sayfa 0 / 25 ile yüklenir; tablo satırında özet fark, açılınca tam tablo', async () => {
    render(<AdminChangeHistory resource="TEAM" />)
    expect(api.admin.history).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /Geçmişi göster|Show history/i }))
    await waitFor(() => expect(api.admin.history).toHaveBeenCalledWith('TEAM', null, { page: 0, size: 25, types: [] }))
    // özet: ilk iki alan + "+1"
    // "düzenledi" hem süzgeç çipinde hem satırda: satırı .aud-row sınıfından seç.
    await screen.findAllByText(/düzenledi|edited/)
    const rows = await screen.findAllByRole('row')
    const row = rows.find(r => /düzenledi|edited/.test(r.textContent) && r.classList.contains('aud-row'))
    expect(row.textContent).toMatch(/Lider|Leader/)
    expect(row.textContent).toContain('+1')
    expect(document.querySelector('.audit-diff-table')).toBeNull()   // satır kapalıyken tam tablo yok
    fireEvent.click(row)
    expect(await screen.findByText('b@example.com')).toBeInTheDocument()   // tam fark tablosu açıldı
    // bilinmeyen eylem ham adıyla; adı olmayan kayıt kimliğiyle; anlık görüntü özeti alan adları
    expect(screen.getByText(/frobnicate/)).toBeInTheDocument()
    expect(screen.getByText('#9')).toBeInTheDocument()
    // sayfalama: 60 kayıt / 25 → 3 sayfa
    expect(screen.getByText(/60/)).toBeInTheDocument()
  })

  it('olay türü çipi seçince types ile yeniden sorgular ve sayfa 1\\u0027e döner; "Tümü" temizler', async () => {
    render(<AdminChangeHistory resource="TEAM" />)
    fireEvent.click(screen.getByRole('button', { name: /Geçmişi göster|Show history/i }))
    const group = await screen.findByRole('group', { name: /Olay türü|Event type/ })
    fireEvent.click(within(group).getByRole('button', { name: /sildi|deleted/ }))
    await waitFor(() => expect(api.admin.history).toHaveBeenLastCalledWith('TEAM', null, { page: 0, size: 25, types: ['TEAM_DELETE'] }))
    fireEvent.click(within(group).getByRole('button', { name: /^Tümü$|^All$/ }))
    await waitFor(() => expect(api.admin.history).toHaveBeenLastCalledWith('TEAM', null, { page: 0, size: 25, types: [] }))
  })

  it('sayfa değişince page ile sorgular', async () => {
    render(<AdminChangeHistory resource="USER" />)
    fireEvent.click(screen.getByRole('button', { name: /Geçmişi göster|Show history/i }))
    await screen.findAllByText(/düzenledi|edited/)
    const next = screen.getAllByRole('button').find(b => /sonraki|next/i.test(b.getAttribute('aria-label') || b.title || b.textContent))
    fireEvent.click(next)
    await waitFor(() => expect(api.admin.history).toHaveBeenLastCalledWith('USER', null, { page: 1, size: 25, types: [] }))
  })

  it('satır süzgeci verilince bölüm kendiliğinden açılır ve resourceId ile sorgular; temizle → tümü', async () => {
    const clear = vi.fn()
    const { rerender } = render(<AdminChangeHistory resource="USER" filter={{ id: 42, name: 'ali' }} onClearFilter={clear} />)
    await waitFor(() => expect(api.admin.history).toHaveBeenCalledWith('USER', 42, { page: 0, size: 25, types: [] }))
    fireEvent.click(await screen.findByRole('button', { name: /Tümünü göster|Show everything/i }))
    expect(clear).toHaveBeenCalled()
    rerender(<AdminChangeHistory resource="USER" filter={null} onClearFilter={clear} />)
    await waitFor(() => expect(api.admin.history).toHaveBeenCalledWith('USER', null, { page: 0, size: 25, types: [] }))
  })

  it('canView=false → hiçbir şey çizilmez; yükleme hatası görünür', async () => {
    const { container } = render(<AdminChangeHistory resource="USER" canView={false} />)
    expect(container.querySelector('[data-testid="admin-history"]')).toBeNull()
    expect(api.admin.history).not.toHaveBeenCalled()
    api.admin.history.mockResolvedValue({ success: false, error: 'yetki yok' })
    render(<AdminChangeHistory resource="ESCALATION_CONTACT" />)
    fireEvent.click(screen.getByRole('button', { name: /Geçmişi göster|Show history/i }))
    expect(await screen.findByText(/yetki yok/)).toBeInTheDocument()
  })
})
