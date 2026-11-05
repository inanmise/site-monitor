import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, waitFor } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { EN } from '../i18n/en.js'

/**
 * Alarm iletimi — dar kap (telefon / tablet, kap < 860 px): tablo yerine kartlar; kartta sahip → alıcı akışı, kapsam,
 * durum, not, ekleyen ve İşlemler menüsü (Düzenle / Duraklat / Kaldır). jsdom yerleşim ölçmez: genişlik kancası 400 px.
 */
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, 400], useElementWidthState: () => [400, () => {}] }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ alertForwards: { list: vi.fn(), update: vi.fn(), remove: vi.fn() } }),
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
}))
import { api } from '../api/client'
import AlertForwardManager from '../components/admin/forwards/AlertForwardManager.jsx'

const OWNER = 'Ödeme Sistemleri ve Kart Operasyonları Uygulama Geliştirme Ekibi (Teknoloji / Ödeme Platformları)'
const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=admin&g_tab=forwards')
  api.alertForwards.list.mockResolvedValue({ success: true, data: {
    items: [{ id: 1, version: 1, owner_team_id: 10, owner_team_name: OWNER, scope_kind: 'TYPES', scope_types: ['dbquery'], scope_monitors: [],
      recipient_kind: 'TEAM', recipient_team_id: 20, recipient_team_name: LONG_TEAM, recipient_team_active: true, recipient_members: [],
      recipient_address_count: 1, enabled: true, expires_on: '2099-01-01', state: 'active', note: 'Gece vardiyası',
      created_at: '2026-11-01T08:00:00', created_by: 'yonetici', can_edit: true }],
    incoming: [], manageable_teams: [{ id: 10, name: OWNER }], can_grant: false } })
})

describe('AlertForwardManager — dar kap', () => {
  it('kartlar: akış (sahip → alıcı), kapsam, durum, bitiş, not, ekleyen ve İşlemler menüsü', async () => {
    render(<AlertForwardManager />)
    const list = await waitFor(() => { const l = document.querySelector('[data-slot="fwd-list"]'); expect(l).not.toBeNull(); return l })
    expect(list).toHaveAttribute('data-layout', 'cards')
    expect(document.querySelector('[data-slot="fwd-table"]')).toBeNull()
    const card = list.querySelector('[data-slot="fwd-row"]')
    const flow = card.querySelector('[data-slot="fwd-flow"]')
    expect(flow).toHaveTextContent(OWNER)
    expect(flow).toHaveTextContent(LONG_TEAM)
    expect(card.querySelector('[data-slot="fwd-scope-chip"]')).toHaveTextContent(EN['nav.dbquery'])
    // Bitiş SAF gün: "yyyy-MM-dd" yerel gün olarak (formatDay) — UTC gece yarısından saat dilimine kaydırılmaz
    expect(card.querySelector('[data-slot="fwd-expiry"]')).toHaveTextContent('until 1 Jan 2099')
    expect(card).toHaveTextContent('Gece vardiyası')
    expect(card.querySelector('[data-slot="fwd-meta"]')).toHaveTextContent('Added by yonetici')
    expect(card.innerHTML).not.toContain('truncate')
    pressMenuTrigger(within(card).getByRole('button', { name: new RegExp(EN['fwd.actions']) }))
    const items = await screen.findAllByRole('menuitem')
    expect(items.map((i) => i.textContent)).toEqual([EN['fwd.edit'], EN['fwd.pause'], EN['fwd.remove.ok']])
  })
})
