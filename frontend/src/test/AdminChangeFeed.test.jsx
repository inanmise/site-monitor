import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import AdminChangeFeed from '../components/admin/AdminChangeFeed.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ admin: { historyFeed: vi.fn() } }),
}))
import { api } from '../api/client'

/**
 * Çapraz yönetim değişiklik akışı (2026-11-13): sunucunun izinli alanları çip olur, alan/gün/kişi süzgeçleri isteğe
 * gider, satırda alan rozeti, fark taşımayan olayda JSON ayrıntı açılımda anlık görüntü; 403 → yetki yok; hata → yeniden dene.
 */
const ITEMS = [
  { id: 1, at: '2026-11-12T10:00:00', actor: 'admin', area: 'teams', action: 'UPDATE', event_type: 'TEAM_UPDATE',
    resource_type: 'TEAM', resource_id: '7', name: 'Takım A', team_id: 7, team_name: 'Takım A',
    changes: '{"email":{"from":"a@example.com","to":"b@example.com"}}', detail: null },
  { id: 2, at: '2026-11-12T09:00:00', actor: 'mudur', area: 'forwards', action: 'CREATE', event_type: 'ALERT_FORWARD_CREATE',
    resource_type: 'TEAM', resource_id: '8', name: 'Takım B', team_id: 8, team_name: 'Takım B',
    changes: null, detail: '{"scope_kind":"ALL","recipient_kind":"TEAM"}' },
]
const RES = { success: true, items: ITEMS, total: 2, page: 0, size: 25, total_pages: 1, truncated: false, hidden: 1,
  days: 30, areas: ['teams', 'contacts', 'groups', 'forwards'], selected_areas: ['teams', 'contacts', 'groups', 'forwards'] }

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState(null, '', '/?tab=admin&g_tab=changes')
  api.admin.historyFeed.mockResolvedValue(RES)
})

describe('AdminChangeFeed', () => {
  it('varsayılan süzgeçlerle yükler; satırlarda alan rozeti; yalnız sunucunun alanları çip; toplam ve gizli notu', async () => {
    render(<AdminChangeFeed />)
    await waitFor(() => expect(api.admin.historyFeed).toHaveBeenCalledWith({ areas: [], actor: '', days: 30, page: 0, size: 25 }))
    await screen.findByText('Takım A')
    const rows = document.querySelectorAll('[data-slot="cf-row"]')
    expect([...rows].map(r => r.getAttribute('data-area'))).toEqual(['teams', 'forwards'])
    expect([...document.querySelectorAll('[data-slot="cf-area"]')].map(c => c.getAttribute('data-area')))
      .toEqual(['teams', 'contacts', 'groups', 'forwards'])
    expect(document.querySelector('[data-slot="cf-total"]').textContent).toMatch(/2/)
    expect(screen.getByText(/takımı belirlenemediği|could not be determined/)).toBeInTheDocument()
  })

  it('alan çipi ve gün süzgeci isteğe gider; URL\'e yazılır', async () => {
    render(<AdminChangeFeed />)
    await screen.findByText('Takım A')
    fireEvent.click(document.querySelector('[data-slot="cf-area"][data-area="groups"]'))
    await waitFor(() => expect(api.admin.historyFeed).toHaveBeenLastCalledWith(expect.objectContaining({ areas: ['groups'] })))
    fireEvent.change(document.querySelector('[data-slot="cf-days"]'), { target: { value: '7' } })
    await waitFor(() => expect(api.admin.historyFeed).toHaveBeenLastCalledWith(expect.objectContaining({ areas: ['groups'], days: 7 })))
    await waitFor(() => {
      expect(window.location.search).toContain('g_ca=groups')
      expect(window.location.search).toContain('g_cd=7')
    })
  })

  it('kişi süzgeci gecikmeli (debounce) uygulanır', async () => {
    render(<AdminChangeFeed />)
    await screen.findByText('Takım A')
    fireEvent.change(document.querySelector('[data-slot="cf-actor"]'), { target: { value: ' ayse ' } })
    await waitFor(() => expect(api.admin.historyFeed).toHaveBeenLastCalledWith(expect.objectContaining({ actor: 'ayse' })), { timeout: 2000 })
  })

  it('fark taşımayan olay açılınca JSON ayrıntı anlık görüntü olarak gösterilir', async () => {
    render(<AdminChangeFeed />)
    await screen.findByText('Takım B')
    const row = [...document.querySelectorAll('[data-slot="cf-row"]')].find(r => r.getAttribute('data-area') === 'forwards')
    fireEvent.click(row)
    const det = await waitFor(() => {
      const el = document.querySelector('[data-slot="cf-detail"]')
      expect(el).toBeTruthy()
      return el
    })
    // 2026-11-13 hata taraması: ham alan adı / kod DEĞİL — okunur etiket ve çevrilmiş değer
    expect(det.textContent).not.toContain('scope_kind')
    expect(det.textContent).toMatch(/Kapsam|Scope/)
    expect(det.textContent).toMatch(/Tüm izlemeler|All monitors/)
    expect(det.textContent).toMatch(/Takımın tamamı|Whole team/)
  })

  it('2026-11-13 hata taraması: paylaşım / nöbet / webhook testi / grup alanları ve kodları çevrilir; iletim güncellemesinin iç farkı fark olarak gösterilir', async () => {
    api.admin.historyFeed.mockResolvedValue({ ...RES, items: [
      { id: 10, at: '2026-11-12T08:00:00', actor: 'admin', area: 'sharing', action: 'UPDATE', event_type: 'TEAM_ACCESS_GRANT_UPDATE',
        resource_type: 'TEAM', resource_id: '7', name: 'Takım A', team_id: 7, team_name: 'Takım A', changes: null,
        detail: '{"grant_id":5,"grantee_type":"USER","level_before":"VIEW","level":"EDIT"}' },
      { id: 11, at: '2026-11-12T07:00:00', actor: 'admin', area: 'oncall', action: 'SCHEDULE_SAVE', event_type: 'ONCALL_SCHEDULE_SAVE',
        resource_type: 'TEAM', resource_id: '7', name: 'Takım A', team_id: 7, team_name: 'Takım A', changes: null,
        detail: '{"rotation":"WEEKLY","members":3}' },
      { id: 12, at: '2026-11-12T06:00:00', actor: 'admin', area: 'groups', action: 'UPDATE', event_type: 'NOTIFICATION_GROUP_UPDATE',
        resource_type: 'NOTIFICATION_GROUP', resource_id: '4', name: 'Nöbet', team_id: 7, team_name: 'Takım A',
        changes: '{"isDefault":{"from":false,"to":true}}', detail: null },
      { id: 13, at: '2026-11-12T05:00:00', actor: 'admin', area: 'forwards', action: 'UPDATE', event_type: 'ALERT_FORWARD_UPDATE',
        resource_type: 'TEAM', resource_id: '7', name: 'Takım A', team_id: 7, team_name: 'Takım A', changes: null,
        detail: '{"forward_id":3,"changes":{"scope_kind":{"from":"ALL","to":"TYPES"}}}' },
      { id: 14, at: '2026-11-12T04:00:00', actor: 'admin', area: 'users', action: 'PUSH_SNOOZE_CLEAR', event_type: 'PUSH_SNOOZE_CLEAR',
        resource_type: 'USER', resource_id: '9', name: 'ayse', team_id: null, team_name: null, changes: null,
        detail: '{"username":"ayse","had_snooze":true}' },
    ] })
    render(<AdminChangeFeed />)
    await screen.findByText('Nöbet')
    const rowOf = (area) => [...document.querySelectorAll('[data-slot="cf-row"]')].find(r => r.getAttribute('data-area') === area)
    const open = async (area) => {
      fireEvent.click(rowOf(area))
      return waitFor(() => {
        const el = document.querySelector('[data-slot="cf-detail"]')
        expect(el).toBeTruthy()
        return el
      })
    }
    // Satır çipleri: ham alan adı yok
    const rowsText = [...document.querySelectorAll('[data-slot="cf-row"]')].map(r => r.textContent).join(' ')
    for (const raw of ['grant_id', 'grantee_type', 'level_before', 'isDefault', 'push snooze clear']) expect(rowsText).not.toContain(raw)
    expect(rowsText).toMatch(/Önceki erişim düzeyi|Previous access level/)
    expect(rowsText).toMatch(/push susturmasını kaldırdı|cleared the push snooze of/)
    // Grup farkı: alan etiketi "Varsayılan" (ng.f.isDefault)
    expect(rowOf('groups').querySelector('[data-chip="isDefault"]').textContent).toMatch(/Varsayılan|Default/)
    // İletim güncellemesi: iç fark (ALL → TYPES) FARK olarak, değer çevrilmiş
    const chip = rowOf('forwards').querySelector('[data-chip="scope_kind"]')
    expect(chip).toBeTruthy()
    expect(chip.textContent).toMatch(/Tüm izlemeler|All monitors/)
    // Paylaşım ayrıntısı: kod değerleri çevrilir
    const share = await open('sharing')
    expect(share.textContent).not.toContain('USER')
    expect(share.textContent).not.toContain('EDIT')
    expect(share.textContent).toMatch(/Kişi|Person/)
    expect(share.textContent).toMatch(/Düzenleme|Edit/)
    // Nöbet ayrıntısı: süre çevrilir
    fireEvent.click(rowOf('sharing'))
    const onc = await open('oncall')
    expect(onc.textContent).not.toContain('WEEKLY')
    expect(onc.textContent).not.toContain('rotation')
    expect(onc.textContent).toMatch(/Haftalık|Weekly/)
  })

  it('URL\'deki süzgeçlerle açılır (derin bağlantı)', async () => {
    window.history.replaceState(null, '', '/?tab=admin&g_tab=changes&g_ca=oncall,teams&g_cd=90&g_cu=mudur')
    render(<AdminChangeFeed />)
    await waitFor(() => expect(api.admin.historyFeed).toHaveBeenCalledWith({ areas: ['teams', 'oncall'], actor: 'mudur', days: 90, page: 0, size: 25 }))
  })

  it('403 → yetki yok bloğu, süzgeç yok', async () => {
    api.admin.historyFeed.mockResolvedValue({ success: false, status: 403, error: 'yok' })
    render(<AdminChangeFeed />)
    await screen.findByText(/görme yetkiniz yok|cannot view the change feed/i)
    expect(document.querySelector('[data-slot="cf-days"]')).toBeNull()
  })

  it('okuma hatası → hata bandı; "Yeniden dene" yeniden okur, "değişiklik yok" DEMEZ', async () => {
    api.admin.historyFeed.mockResolvedValueOnce({ success: false, status: 500, error: 'Sunucu hatası oluştu' })
    render(<AdminChangeFeed />)
    await screen.findByText('Sunucu hatası oluştu')
    expect(screen.queryByText(/yönetim değişikliği yok|No administration changes/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Yeniden dene|Try again/ }))
    await screen.findByText('Takım A')
  })
})
