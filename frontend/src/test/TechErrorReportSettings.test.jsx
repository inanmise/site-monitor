import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

// Ayarlar → Platform → Teknik hata raporu (2026-10-17, S6): gören takımlar (boş = herkes), salt okunur müdür, kanal özeti.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: { techErrorSettings: vi.fn(), saveTechErrorSettings: vi.fn() },
    teams: { directory: vi.fn() },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportSettings from '../components/admin/TechErrorReportSettings.jsx'

const settings = (extra = {}) => ({ success: true, data: {
  team_ids: [], read_only: false, period: { year: 2026, month: 9 },
  channels: [
    { id: 10, name: 'Mobil', active: true, team_id: 1, team_name: 'Dijital Kanallar', team_visible: true, executive_count: 2, approver_count: 1,
      team_email_set: true, period_report: { id: 100, status: 'draft', last_send_status: null, approval: { state: 'pending' } } },
    { id: 20, name: 'POS', active: false, team_id: 2, team_name: 'Ödeme Sistemleri', team_visible: false, executive_count: 0, approver_count: 0,
      team_email_set: false, period_report: null },
  ],
  ...extra,
} })

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.teams.directory.mockResolvedValue({ success: true, data: [{ id: 1, name: 'Dijital Kanallar', active: true }, { id: 2, name: 'Ödeme Sistemleri', active: true }] })
})

describe('TechErrorReportSettings', () => {
  it('yükler: "herkes görür" notu, kanal kartları (yönetici alıcı / PO / dönem durumu / onay / kapalı takım), kayıt çubuğu değişiklik olmadan sessiz', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings())
    render(<TechErrorReportSettings />)
    expect(await screen.findByText('Seçim yok — modülü herkes görür.')).toBeInTheDocument()
    const cards = document.querySelectorAll('[data-slot="te-settings-channel"]')
    expect(cards).toHaveLength(2)
    const mobil = within(cards[0])
    expect(mobil.getByText('Mobil')).toBeInTheDocument()
    expect(mobil.getByText('Yönetici alıcı: 2')).toBeInTheDocument()
    expect(mobil.getByText('PO onaylayıcı: 1')).toBeInTheDocument()
    expect(mobil.getByText('Onay bekliyor')).toBeInTheDocument()
    expect(cards[0]).toHaveAttribute('data-flow', 'approval')
    const pos = within(cards[1])
    expect(pos.getByText('Onay akışı yok')).toBeInTheDocument()
    expect(pos.getByText('PO yok')).toBeInTheDocument()
    expect(pos.getByText('Modül bu takıma kapalı')).toBeInTheDocument()
    expect(pos.getByText('Dönem raporu yok')).toBeInTheDocument()
    expect(cards[1]).toHaveAttribute('data-flow', 'team')
    const bar = document.querySelector('[data-slot="settings-save-bar"]')
    expect(bar).not.toBeNull()
    expect(bar).not.toHaveAttribute('data-dirty')
    expect(api.admin.saveTechErrorSettings).not.toHaveBeenCalled()
  })

  it('sunucu salt okunur (kapsamlı müdür): seçici kilitli, kayıt çubuğu yok, "yalnız global yöneticiler" notu; sunucu hatası yeniden dene', async () => {
    api.admin.techErrorSettings.mockResolvedValueOnce(settings({ team_ids: [1], read_only: true }))
    render(<TechErrorReportSettings />)
    expect(await screen.findByText(/yalnız global yöneticiler/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="settings-save-bar"]')).toBeNull()
    expect(screen.queryByText('Seçim yok — modülü herkes görür.')).toBeNull()
    expect(screen.getByRole('combobox', { name: 'Modülü gören takımlar' })).toBeDisabled()

    api.admin.techErrorSettings.mockResolvedValueOnce({ success: false, error: 'Veritabanı yok' }).mockResolvedValueOnce(settings())
    render(<TechErrorReportSettings />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Veritabanı yok')
    fireEvent.click(screen.getByRole('button', { name: /Tekrar dene|Yeniden dene/ }))
    await waitFor(() => expect(api.admin.techErrorSettings).toHaveBeenCalledTimes(3))
  })

  it('kaydet: seçilen takımlar gönderilir, yanıt listeyi günceller', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings({ team_ids: [1, 2] }))
    api.admin.saveTechErrorSettings.mockResolvedValue({ success: true, data: { team_ids: [2] } })
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    // Seçiciyi aç, seçili takımı listeden kaldır (ui/MultiTeamSelect: Popover + çoklu listbox)
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Modülü gören takımlar' }))
    const opt = (await screen.findAllByText('Dijital Kanallar')).map((el) => el.closest('[role="option"]')).find(Boolean)
    fireEvent.mouseDown(opt)
    const bar = document.querySelector('[data-slot="settings-save-bar"]')
    await waitFor(() => expect(bar).toHaveAttribute('data-dirty', 'true'))
    fireEvent.click(within(bar).getByRole('button', { name: /Kaydet/ }))
    await waitFor(() => expect(api.admin.saveTechErrorSettings).toHaveBeenCalledWith({ team_ids: [2] }))
    await waitFor(() => expect(bar).not.toHaveAttribute('data-dirty'))
  })
})
