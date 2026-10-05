import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
// İzin anlık görüntüsü — testler `perm.run`'ı değiştirir (diagnostics.run / execute). Envanter okuma açık.
const perm = vi.hoisted(() => ({ run: true }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    canView: () => true, canEdit: () => false, canExecute: (r) => r === 'diagnostics.run' && perm.run, perms: {}, refresh: () => {},
  }),
}))

const FAILED = {
  id: 9, checked_at: '2026-10-05T08:00:00', status: 'error', days_remaining: null, response_ms: 412,
  error: 'PKIX path building failed: unable to find valid certification path to requested target',
  error_class: 'TLS_TRUST', error_stage: 'TLS', resolved_ips: '192.0.2.10',
}
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  formatTime: (s) => String(s ?? ''),
  api: withApiFallback({
    getHistory: vi.fn().mockResolvedValue({ success: true, data: [{ domain: 'own.example.test', status: 'error', days_remaining: null, port: 443 }] }),
    checkDomainPreview: vi.fn().mockResolvedValue({ success: true, data: null }),
    getDomainAlerts: vi.fn().mockResolvedValue({ success: true, data: [] }),
    monitoring: {
      getCheckHistory: vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '/csv'),
    },
    admin: {
      getNotes: vi.fn().mockResolvedValue({ success: true, data: [] }),
      runDiagnostics: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import CertificateModal from '../components/CertificateModal.jsx'

/**
 * Sertifika penceresi → "Tanıla" kapısı (2026-10-05): rol (ADMIN / TEAM_ADMIN) değil `diagnostics.run` (execute) izni;
 * başka takımın (salt okunur) kaydında ve önizlemede yok. Kontrol Geçmişi'ndeki başarısız satırın hata panelinde
 * "Bu kontrolü tanıla" aynı kapıyla aynı DiagnosticsModal'ı açar.
 */
const diagBtn = (dlg) => dlg.querySelector('[data-slot="cert-diagnose"]')
const openModal = (extra = {}) => render(
  <CertificateModal domain="own.example.test" onClose={() => {}} currentUser="user1" currentUserRole="USER" {...extra} />,
)

describe('CertificateModal — Tanıla izin kapısı', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    perm.run = true
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [FAILED], page: 0, size: 50, total: 1, counts: { total: 1, fail: 1 },
      range: { from: '2026-10-01T00:00:00', to: '2026-10-05T23:59:59' }, retention_days: 180, buckets: [], alerts: [],
    } })
    api.admin.runDiagnostics.mockResolvedValue({ success: false, error: 'tanı ucu yanıt vermedi' })
  })

  it('USER + diagnostics.run: başlıkta Tanıla var → DiagnosticsModal alan adıyla açılır', async () => {
    openModal()
    const dlg = await screen.findByRole('dialog')
    const btn = await waitFor(() => { const b = diagBtn(dlg); if (!b) throw new Error('düğme yok'); return b })
    fireEvent.click(btn)
    await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenCalledWith('own.example.test', 443))
  })

  it('ADMIN ama izin YOK: Tanıla yok', async () => {
    perm.run = false
    openModal({ currentUserRole: 'ADMIN' })
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(dlg.querySelector('[data-slot="cert-modal-status"]')).not.toBeNull())
    expect(diagBtn(dlg)).toBeNull()
  })

  it('başka takımın kaydı (readOnly) ve önizleme: izin olsa da Tanıla yok', async () => {
    const r1 = openModal({ readOnly: true, readOnlyTeam: { id: 9, name: 'Takım B' } })
    const d1 = await screen.findByRole('dialog')
    await waitFor(() => expect(d1.querySelector('[data-slot="cert-modal-status"]')).not.toBeNull())
    expect(diagBtn(d1)).toBeNull()
    r1.unmount()
    openModal({ previewMode: true })
    const d2 = await screen.findByRole('dialog')
    expect(diagBtn(d2)).toBeNull()
  })

  it('Kontrol Geçmişi: başarısız satırın panelinde "Bu kontrolü tanıla" → DiagnosticsModal; izin yoksa düğme yok', async () => {
    const r = openModal({ initialTab: 'history' })
    const dlg = await screen.findByRole('dialog')
    const toggle = await waitFor(() => { const t = dlg.querySelector('[data-slot="cert-hist-toggle"]'); if (!t) throw new Error('satır yok'); return t })
    fireEvent.click(toggle)
    const panel = await waitFor(() => { const p = dlg.querySelector('[data-slot="chkfail-panel"]'); if (!p) throw new Error('panel yok'); return p })
    fireEvent.click(within(panel).getByRole('button', { name: /(Diagnose this check|Bu kontrolü tanıla)/ }))
    await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenCalledWith('own.example.test', 443))
    r.unmount()

    perm.run = false
    openModal({ initialTab: 'history' })
    const dlg2 = await screen.findByRole('dialog')
    const toggle2 = await waitFor(() => { const t = dlg2.querySelector('[data-slot="cert-hist-toggle"]'); if (!t) throw new Error('satır yok'); return t })
    fireEvent.click(toggle2)
    const panel2 = await waitFor(() => { const p = dlg2.querySelector('[data-slot="chkfail-panel"]'); if (!p) throw new Error('panel yok'); return p })
    expect(within(panel2).queryByRole('button', { name: /(Diagnose this check|Bu kontrolü tanıla)/ })).toBeNull()
  })
})
