import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

const confirmMock = vi.fn(() => Promise.resolve(true))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: {
      listPlatforms: vi.fn(),
      createPlatform: vi.fn(() => Promise.resolve({ success: true, data: { code: 'K8S_PROD', name: 'K8s Prod' } })),
      updatePlatform: vi.fn(() => Promise.resolve({ success: true, data: { code: 'IIS', name: 'IIS' } })),
      deletePlatform: vi.fn(() => Promise.resolve({ success: true, data: { deleted: true } })),
    },
  }),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))

import { api } from '../api/client'
import PlatformSettings from '../components/admin/PlatformSettings.jsx'

/** Ayarlar → Platformlar (2026-09-22): liste + kullanım, ekleme (kod büyük harf), düzenlemede kod kilitli, kullanımdaki silinemez, pasife alma. */
describe('PlatformSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.listPlatforms.mockResolvedValue({ success: true, data: [
      { id: 1, code: 'IIS', name: 'IIS (Windows)', description: 'IIS', active: true, usage: 4 },
      { id: 2, code: 'LINUX', name: 'Linux sunucu', description: null, active: false, usage: 0 },
    ] })
  })

  it('listeyi kullanım sayısı ve durumla çizer; Sil kullanımdakinde pasif', async () => {
    render(<PlatformSettings />)
    await waitFor(() => expect(screen.getByTestId('plat-table')).toBeInTheDocument())
    expect(api.admin.listPlatforms).toHaveBeenCalledWith(true)
    expect(screen.getByText('IIS (Windows)')).toBeInTheDocument()
    expect(screen.getByText(/4 kayıt|4 records/)).toBeInTheDocument()
    const rows = screen.getByTestId('plat-table').querySelectorAll('tbody tr')
    expect(rows[0].querySelector('[data-slot="button"][data-variant="destructive"]')).toBeDisabled()
    expect(rows[1].querySelector('[data-slot="button"][data-variant="destructive"]')).not.toBeDisabled()
    expect(rows[1].classList.contains('mon-row-inactive')).toBe(true)
  })

  it('ekleme: kod büyük harfe çevrilir, create çağrılır, liste yenilenir; ad boşsa create ÇAĞRILMAZ', async () => {
    render(<PlatformSettings />)
    await waitFor(() => expect(screen.getByTestId('plat-table')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /Platform ekle|Add platform/ }))
    expect(api.admin.createPlatform).not.toHaveBeenCalled()
    fireEvent.change(screen.getByPlaceholderText('OPENSHIFT_PROD'), { target: { value: 'k8s_prod' } })
    fireEvent.change(screen.getByPlaceholderText(/OpenShift Prod/), { target: { value: 'K8s Prod' } })
    fireEvent.click(screen.getByRole('button', { name: /Platform ekle|Add platform/ }))
    await waitFor(() => expect(api.admin.createPlatform).toHaveBeenCalledWith({ code: 'K8S_PROD', name: 'K8s Prod', description: '' }))
    await waitFor(() => expect(api.admin.listPlatforms).toHaveBeenCalledTimes(2))
  })

  it('düzenle: kod kilitli, ad/açıklama update ile; pasife al update({active:false}); sil onaydan sonra delete', async () => {
    render(<PlatformSettings />)
    await waitFor(() => expect(screen.getByTestId('plat-table')).toBeInTheDocument())
    const rows = screen.getByTestId('plat-table').querySelectorAll('tbody tr')
    fireEvent.click(rows[0].querySelector('button[title="Düzenle"], button[title="Edit"]'))
    expect(screen.getByPlaceholderText('OPENSHIFT_PROD')).toBeDisabled()
    fireEvent.change(screen.getByPlaceholderText(/OpenShift Prod/), { target: { value: 'IIS 10' } })
    fireEvent.click(screen.getByRole('button', { name: /^Kaydet$|^Save$/ }))
    await waitFor(() => expect(api.admin.updatePlatform).toHaveBeenCalledWith(1, { name: 'IIS 10', description: 'IIS' }))
    // Kayıttan sonra liste yenilendi → satırları yeniden sorgula (eski düğüm referansı ayrılmış olabilir)
    await waitFor(() => expect(api.admin.listPlatforms).toHaveBeenCalledTimes(2))
    const rows2 = screen.getByTestId('plat-table').querySelectorAll('tbody tr')
    fireEvent.click(rows2[0].querySelector('button[title="Pasife al"], button[title="Deactivate"]'))
    await waitFor(() => expect(api.admin.updatePlatform).toHaveBeenCalledWith(1, { active: false }))
    await waitFor(() => expect(api.admin.listPlatforms).toHaveBeenCalledTimes(3))
    fireEvent.click(screen.getByTestId('plat-table').querySelectorAll('tbody tr')[1].querySelector('[data-slot="button"][data-variant="destructive"]'))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    await waitFor(() => expect(api.admin.deletePlatform).toHaveBeenCalledWith(2))
  })
})
