import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from './test-utils.jsx'
import CertRenewalGuide from '../components/CertRenewalGuide.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({ guideLinks: { list: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() } }),
}))

import { api } from '../api/client'

describe('CertRenewalGuide', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('renders the empty state when no links are returned', async () => {
    api.guideLinks.list.mockResolvedValueOnce({ success: true, data: [] })
    render(<CertRenewalGuide isAdmin={false} />)
    await waitFor(() => expect(api.guideLinks.list).toHaveBeenCalled())
    expect(document.body.textContent.length).toBeGreaterThan(0)
  })

  it('hides the add-link button for non-admin viewers', async () => {
    api.guideLinks.list.mockResolvedValueOnce({ success: true, data: [] })
    render(<CertRenewalGuide isAdmin={false} />)
    await waitFor(() => expect(api.guideLinks.list).toHaveBeenCalled())
    expect(screen.queryByRole('button', { name: /link ekle|add link/i })).toBeNull()
  })

  it('shows the add-link button for admins', async () => {
    api.guideLinks.list.mockResolvedValueOnce({ success: true, data: [] })
    render(<CertRenewalGuide isAdmin />)
    await waitFor(() => expect(screen.getByRole('button', { name: /link ekle|add link/i })).toBeDefined())
  })

  it('groups links by category and renders title + url', async () => {
    api.guideLinks.list.mockResolvedValueOnce({
      success: true,
      data: [
        { id: 1, category: 'Netscaler', title: 'Vserver',  url: 'https://wiki/ns-vserver' },
        { id: 2, category: 'Netscaler', title: 'Cert swap', url: 'https://wiki/ns-cert'   },
        { id: 3, category: 'WAF',       title: 'Cert swap', url: 'https://wiki/waf-cert'  },
      ],
    })
    render(<CertRenewalGuide isAdmin={false} />)
    await waitFor(() => expect(screen.getByText('Vserver')).toBeDefined())
    expect(screen.getByText('Netscaler')).toBeDefined()
    expect(screen.getByText('WAF')).toBeDefined()
    // URL is displayed openly under the title (this PR's fix)
    expect(screen.getByText('https://wiki/ns-vserver')).toBeDefined()
  })

  /**
   * Siralama alaninin CIFT YONLU tur-gidis-donusu.
   *
   * Bu ozellik iki yonden de oluydu ve hicbir test yakalamiyordu:
   *  - OKUMA: form `link.sortOrder` okuyordu; API yaniti SNAKE_CASE doner, yani deger
   *    DAIMA undefined'di ve duzenleme formu kayitli siralamayi hic gostermiyordu.
   *  - YAZMA: payload `sortOrder` (camelCase) gonderiyordu; uc @RequestBody GuideLink ile
   *    bagliyor ve Jackson SNAKE_CASE calisiyor, anahtar sessizce dusuyordu. Entity
   *    varsayilani 0 oldugu icin sunucudaki null-kontrolu de GECIYOR ve her duzenleme
   *    siralamayi 0'a ceviriyordu.
   *
   * Backend testi yalnizca YAZMA tarafini koruyabilir; okuma tarafinin kapisi burasi.
   */
  it('duzenleme formu kayitli siralamayi GOSTERIR (snake_case okunur)', async () => {
    api.guideLinks.list.mockResolvedValueOnce({ success: true, data: [
      { id: 1, category: 'WAF', title: 'Rehber', url: 'https://x.example.com', description: '', sort_order: 9 },
    ] })
    render(<CertRenewalGuide isAdmin />)
    await screen.findByText('Rehber')

    fireEvent.click(screen.getAllByTitle(/Edit|Düzenle/i)[0])

    expect(screen.getByDisplayValue('9')).toBeTruthy()
  })

  it('kaydetme yuku siralamayi SNAKE_CASE anahtarla gonderir', async () => {
    api.guideLinks.list.mockResolvedValue({ success: true, data: [
      { id: 1, category: 'WAF', title: 'Rehber', url: 'https://x.example.com', description: '', sort_order: 9 },
    ] })
    api.guideLinks.update.mockResolvedValueOnce({ success: true })
    render(<CertRenewalGuide isAdmin />)
    await screen.findByText('Rehber')

    fireEvent.click(screen.getAllByTitle(/Edit|Düzenle/i)[0])
    fireEvent.change(screen.getByDisplayValue('9'), { target: { value: '3' } })
    fireEvent.click(screen.getByRole('button', { name: /^Save$|^Kaydet$/i }))

    await waitFor(() => expect(api.guideLinks.update).toHaveBeenCalled())
    const payload = api.guideLinks.update.mock.calls[0][1]
    expect(payload.sort_order).toBe(3)
    // camelCase anahtar GONDERILMEZ: uc onu sessizce yok sayardi.
    expect(payload.sortOrder).toBeUndefined()
  })
})
