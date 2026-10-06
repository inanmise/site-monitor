import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * "Dosyadan sertifika ekle" giriş noktaları (2026-10-06): Pano / Envanter "Domain Ekle" bölünmüş düğmesi (ana düğme
 * bugünkü akış, ok menüsünde AYRI seçenek) ve Envanter boş durumu → `navigateTo('manualcerts', { mc_upload: '1' })`.
 */
const navigate = vi.hoisted(() => vi.fn())
vi.mock('../utils/navigate.js', () => ({ navigateTo: navigate, default: navigate, APPLY_VIEW_EVENT: 'sm:apply-view', applyTabView: vi.fn() }))

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: {
      getInventory: vi.fn().mockResolvedValue({ success: true, data: [] }),
      getTeams: vi.fn().mockResolvedValue({ success: true, data: [{ id: 1, name: 'Takım A' }] }),
      listPlatforms: vi.fn().mockResolvedValue({ success: true, data: [] }),
    },
  }),
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: { 'inventory.crud': { edit: true } }, canView: () => true, canEdit: () => true, canExecute: () => true, refresh: () => {} }),
}))

import AddCertSplitButton from '../components/manualcert/AddCertSplitButton.jsx'
import InventoryManager from '../components/admin/InventoryManager.jsx'

describe('AddCertSplitButton', () => {
  it('ana düğme bugünkü "Domain Ekle"; ok menüsü iki ayrı seçenek sunar', async () => {
    const onAddDomain = vi.fn(); const onAddFromFile = vi.fn()
    render(<AddCertSplitButton slot="dash-add-domain" onAddDomain={onAddDomain} onAddFromFile={onAddFromFile} />)
    const main = document.querySelector('[data-slot="dash-add-domain"]')
    expect(main).toHaveAccessibleName(/Add Domain|Domain Ekle/)
    fireEvent.click(main)
    expect(onAddDomain).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('group', { name: /Add Domain options|Domain Ekle seçenekleri/ })).toBeInTheDocument()
    pressMenuTrigger(screen.getByRole('button', { name: /More ways to add|Diğer ekleme seçenekleri/ }))
    expect(await screen.findByRole('menuitem', { name: /Add domain \(over the network\)|ağ üzerinden/ })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('menuitem', { name: /Add certificate from file|Dosyadan sertifika ekle/ }))
    expect(onAddFromFile).toHaveBeenCalledTimes(1)
  })
})

describe('Envanter giriş noktaları', () => {
  beforeEach(() => { navigate.mockClear(); window.history.replaceState(null, '', '/?tab=domains') })

  it('başlık ok menüsü ve boş durum "Dosyadan sertifika ekle" → Manuel Sertifikalar, sihirbaz açık', async () => {
    render(<InventoryManager systemRole="TEAM_ADMIN" teams={[{ id: 1, name: 'Takım A' }]} />)
    const empty = await screen.findByRole('button', { name: /^(Add certificate from file|Dosyadan sertifika ekle)$/ })
    fireEvent.click(empty)
    expect(navigate).toHaveBeenLastCalledWith('manualcerts', { mc_upload: '1' })
    pressMenuTrigger(screen.getByRole('button', { name: /More ways to add|Diğer ekleme seçenekleri/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Add certificate from file|Dosyadan sertifika ekle/ }))
    expect(navigate).toHaveBeenCalledTimes(2)
  })
})
