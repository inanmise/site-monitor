import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    getCertificateHealth: vi.fn(),
    login: vi.fn(),
    getPublicStats: vi.fn(async () => ({ success: true, data: {} })),
  }),
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
}))
import { api } from '../api/client'
import WeeklyCompletionBoard from '../components/WeeklyCompletionBoard.jsx'
import CertHealthPanel from '../components/CertHealthPanel.jsx'
import InventoryTable from '../components/inventory/InventoryTable.jsx'
import Login from '../pages/Login.jsx'

/**
 * 2026-09-27 regresyon taraması, FRONTEND B/10 (a11y A2–A5) — satırdaki kontrolün erişilebilir adı HEDEFİ taşır:
 * ekran okuyucu "⏸", adsız kopyala düğmesi, yalnız "T2"/"—" ya da adsız bir uyarı penceresi duymamalı.
 * (A1 — kart "Bağlantıyı kopyala" — CopyLinkButton.test.jsx + copyLinkTargetName.test.js'te.)
 */
describe('a11y — satır kontrollerinin adı hedefi içerir', () => {
  beforeEach(() => { vi.clearAllMocks(); localStorage.clear() })

  it('A2 · Takım tamamlama panosu: hatırlatma kapalı işareti "⏸" değil, "<takım> — Bu takımın hatırlatması kapalı"', () => {
    const data = { year: 2026, weeks: 2, current_week: 2, total_missing: 0, teams: [
      { team_id: 1, team_name: 'Takım A', reminder: false, approved: 2, missing: 0,
        cells: [{ week: 1, status: 'APPROVED', report_id: 1 }, { week: 2, status: 'APPROVED', report_id: 2 }] },
    ] }
    render(<WeeklyCompletionBoard year={2026} data={data} onPick={() => {}} />)
    fireEvent.click(document.querySelector('[data-slot="collapsible-trigger"]'))
    expect(screen.getByRole('button', { name: 'Takım A — Reminder is off for this team' })).toBeInTheDocument()
    expect(document.querySelector('[data-slot="wrc-noremind"]')).toHaveAttribute('aria-hidden', 'true')
  })

  it('A3 · Sağlık listesi: cipher kopyala düğmesi adlı (şifre takımı + eylem), eskiden ADSIZDI', async () => {
    const cipher = 'TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384'
    api.getCertificateHealth.mockResolvedValue({ success: true, data: {
      domain: 'a.example.com', port: 443, ok_count: 0, evaluated_count: 1, rows: [
        { key: 'cipher', group: 'transport', status: 'WARN', value_key: 'cipherAcceptable', value_args: [cipher],
          action_key: 'preferAead', action_args: [], evidence: { cipher_suite: cipher } },
      ] } })
    const { container } = render(<CertHealthPanel domain="a.example.com" />)
    await waitFor(() => expect(container.querySelector('.hlth-cipher')).not.toBeNull())
    const copy = within(container.querySelector('.hlth-cipher')).getByRole('button')
    expect(copy).toHaveAccessibleName(`${cipher} — Copy cipher suite`)
  })

  it('A4 · Envanter tablosu: satır içi kademe düğmesi yalnız "T2"/"—" değil, alan adı + kademe', () => {
    const rows = [
      { id: 1, domain: 'a.example.com', port: 443, active: true, team_id: 5, tier: 2 },
      { id: 2, domain: 'b.example.com', port: 443, active: true, team_id: 5, tier: null },
    ]
    render(<InventoryTable rows={rows} cols={['domain', 'tier']} sort="domain|asc" onSort={() => {}} density="comfortable"
      canManage isAdmin teamsCount={1} selected={new Set()} onToggle={() => {}} onToggleAll={() => {}} allOnPage={false}
      onShow={() => {}} onInline={() => {}} statusFilter="active" />)
    expect(screen.getByRole('button', { name: 'a.example.com — Tier: T2' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'b.example.com — Tier: Unclassified' })).toBeInTheDocument()
  })

  it('A5 · Giriş: "başka yerde aktif oturum" uyarı penceresi başlığıyla ADLANDIRILIR ve açıklaması bağlıdır', async () => {
    api.login.mockResolvedValueOnce({ success: false, error_code: 'ACTIVE_SESSION_EXISTS' })
    render(<Login onLogin={() => {}} />)
    fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'admin' } })
    fireEvent.change(document.getElementById('lp-pass'), { target: { value: 'pw' } })
    fireEvent.submit(document.querySelector('form'))
    const dlg = await screen.findByRole('alertdialog', { name: 'Active Session Elsewhere' })
    expect(dlg).toHaveAccessibleDescription(/signed in elsewhere/)
  })
})
