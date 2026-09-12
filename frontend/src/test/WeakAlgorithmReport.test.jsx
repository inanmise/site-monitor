import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

/**
 * Zayıf Algoritma Raporu — zengin sürüm (2026-09-12). Sözleşme:
 *   • BOŞ rapor artık boş sayfa DEĞİL: tarama özeti + "temiz" bandı + kural kataloğu çizilir.
 *   • Dolu rapor: sertifika / TLS / zincir bulguları ayrı bölümlerde, eylem düğmeleri satırda.
 *   • Eylemler doğru ucu çağırır (kontrol et / bildir / istisna kaydet-kaldır); CSV dışa aktarma
 *     <a href> yerine window.open ile (same-origin cookie).
 *   • manage yetkisi yoksa bildir/istisna düğmeleri HİÇ çizilmez (kontrol et kalır).
 */
const perm = vi.hoisted(() => ({ canEdit: true }))
const confirmMock = vi.fn(() => Promise.resolve(true))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => perm.canEdit, canExecute: () => true, perms: {} }),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: confirmMock }),
  DialogProvider: ({ children }) => children,
}))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  api: withApiFallback({
    refreshCertificateHealth: vi.fn().mockResolvedValue({ success: true }),
    admin: {
      getWeakAlgorithms: vi.fn(),
      weakAlgorithmsExportUrl: vi.fn(() => '/api/admin/audit/weak-algorithms/export'),
      setWeakAlgorithmException: vi.fn().mockResolvedValue({ success: true }),
      clearWeakAlgorithmException: vi.fn().mockResolvedValue({ success: true }),
      notifyWeakAlgorithm: vi.fn().mockResolvedValue({ success: true, data: { email: 'SENT', email_to: 'takim-a@example.com', push: { queued: 2 } } }),
      getTeams: vi.fn().mockResolvedValue({ success: true, data: [{ id: 1, name: 'Takım A' }] }),
    },
  }),
}))

import { api } from '../api/client'
import WeakAlgorithmReport from '../components/admin/WeakAlgorithmReport.jsx'

const RULES = ['sig.md5', 'sig.sha1', 'key.rsa1024', 'key.rsa2048', 'key.ec192', 'key.ec256', 'tls.legacy', 'cipher.weak',
  'cipher.cbc', 'pfs.none', 'chain.broken', 'trust.untrusted', 'revocation.revoked', 'revocation.nourl', 'intermediate.expiring']
  .map(key => ({ key, severity: 'HIGH', matched: 0 }))

function series(n = 30) { return Array.from({ length: n }, (_, i) => ({ day: `2026-08-${String(i + 1).padStart(2, '0')}`, weak: i === 5 ? 1 : 0 })) }

const EMPTY = {
  success: true, data: [], total: 0, critical: 0, high: 0, excepted: 0,
  scan: { active_domains: 212, checked: 210, checked_24h: 205, never_checked: 2, error: 3, latest_checked_at: '2026-09-12T10:00:00', generated_at: '2026-09-12T10:05:00' },
  rules: RULES,
  distribution: { signature: [{ label: 'SHA256withRSA', count: 180 }, { label: 'SHA256withECDSA', count: 30 }], key: [{ label: 'RSA 2048', count: 150 }], tls: [{ label: 'TLSv1.3', count: 200 }], cipher_tier: [{ label: 'STRONG', count: 210 }] },
  outlook: { year: 2030, sunset: '2030-12-31', rsa_min_bits: 3072, affected: 1,
    summary: { checked: 210, rsa_fleet: 150, pct_of_checked: 1, pct_of_rsa: 1, reissue: 1, renew: 0, unknown: 0, days_to_sunset: 1571, by_team: [{ label: 'Takım A', count: 1 }] },
    rows: [{ domain: 'rsa2048.example.com', public_key_algorithm: 'RSA', public_key_size: 2048, team_id: 1, team_name: 'Takım A', reason: 'key.rsa3072',
      action: 'reissue', renewal_by: '2030-12-31', target: 'RSA 3072 / ECDSA P-256', not_after: '2031-06-01T00:00:00', days_remaining: 1700 }] },
  tls: { total: 0, rows: [] }, chain: { total: 0, rows: [] },
  teams: { rows: [{ team_id: 1, team_name: 'Takım A', total: 200, weak: 0, tls: 0, chain: 0 }], unowned: { total: 12, weak: 0, tls: 0, chain: 0 } },
  trend: { days: 30, series: series(), detected: [], resolved: [] },
  exceptions: [],
}

const RICH = {
  ...EMPTY,
  data: [{
    domain: 'sha1.example.com', severity: 'HIGH', signature_algorithm: 'SHA1withRSA', public_key_algorithm: 'RSA', public_key_size: 2048,
    weaknesses: ['Weak hash: SHA1withRSA'], rule_keys: ['sig.sha1'], not_after: '2027-01-01T00:00:00', days_remaining: 100, status: 'valid',
    owner: 'Sahip', team_id: 1, team_name: 'Takım A', team_email: 'takim-a@example.com',
  }, {
    domain: 'accepted.example.com', severity: 'CRITICAL', signature_algorithm: 'MD5withRSA', public_key_algorithm: 'RSA', public_key_size: 2048,
    weaknesses: ['Deprecated hash: MD5withRSA'], rule_keys: ['sig.md5'], status: 'valid', team_id: 1, team_name: 'Takım A',
    exception: { domain: 'accepted.example.com', reason: 'plan', until: '2026-12-31', expired: false },
  }],
  total: 2, critical: 1, high: 1, excepted: 1,
  tls: { total: 1, rows: [{ domain: 'legacy.example.com', severity: 'CRITICAL', tls_version: 'TLSv1', cipher_suite: 'TLS_RSA_WITH_RC4_128_SHA', findings: ['tls.legacy', 'cipher.weak', 'pfs.none'], team_id: 1, team_name: 'Takım A' }] },
  chain: { total: 1, rows: [{ domain: 'broken.example.com', severity: 'HIGH', issuer: 'Example CA', findings: ['chain.broken', 'revocation.nourl'], intermediate_days: 12, team_id: 1, team_name: 'Takım A' }] },
  teams: { rows: [{ team_id: 1, team_name: 'Takım A', total: 200, weak: 2, tls: 1, chain: 1 }], unowned: { total: 0, weak: 0, tls: 0, chain: 0 } },
  trend: { days: 30, series: series(), detected: [{ domain: 'sha1.example.com', day: '2026-08-20' }], resolved: [{ domain: 'gone.example.com', day: '2026-08-25' }] },
  exceptions: [{ domain: 'accepted.example.com', reason: 'plan', until: '2026-12-31', created_by: 'admin', created_at: '2026-09-01T10:00:00', expired: false }],
}

describe('WeakAlgorithmReport — zengin rapor (2026-09-12)', () => {
  beforeEach(() => { perm.canEdit = true; vi.clearAllMocks(); confirmMock.mockResolvedValue(true) })

  it('BOŞ rapor: tarama özeti, "temiz" bandı, kural kataloğu ve 2030 görünümü çizilir — boş sayfa yok', async () => {
    api.admin.getWeakAlgorithms.mockResolvedValue(EMPTY)
    render(<WeakAlgorithmReport />)
    await screen.findByText(/Temiz: 210 alan tarandı|Clean: 210 domains scanned/)
    // Tarama KPI'ları
    expect(screen.getByText(/^(Aktif alan|Active domains)$/)).toBeInTheDocument()
    expect(screen.getByText('212')).toBeInTheDocument()
    expect(screen.getByText(/Hiç: 2 · Hatalı: 3|Never: 2 · Errors: 3/)).toBeInTheDocument()
    // Kural kataloğu kapalı başlar; açınca 15 kural, tümü "0"
    const rulesHead = screen.getByRole('button', { name: /Kural kataloğu|Rule catalogue/ })
    expect(rulesHead).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(rulesHead)
    expect(screen.getByText(/SHA-1 imza|SHA-1 signature/)).toBeInTheDocument()
    expect(screen.getByText(/MD2\/MD5 imza|MD2\/MD5 signature/)).toBeInTheDocument()
    // 2030 görünümü sayaç rozeti 1 ve alan listede
    fireEvent.click(screen.getByRole('button', { name: /2030/ }))
    expect(screen.getByText('rsa2048.example.com')).toBeInTheDocument()
    // Risk bandı sayılarla + beklenen eylem etiketi (2030 sonunu aşan sertifika → erken yeniden düzenle)
    expect(screen.getByText(/Risk: 1 alan|Risk: 1 domains/)).toBeInTheDocument()
    expect(screen.getByText(/Erken yeniden düzenle|Re-issue early/)).toBeInTheDocument()
    expect(screen.getByText(/Alan sahibinden beklenen|What the domain owner is expected to do/)).toBeInTheDocument()
    // Sahipsiz alan uyarısı (takım bölümü)
    fireEvent.click(screen.getByRole('button', { name: /Takım kırılımı|By team/ }))
    expect(screen.getByText(/Takımı olmayan 12 alan|12 domains have no team/)).toBeInTheDocument()
    // Dağılım çubukları
    fireEvent.click(screen.getByRole('button', { name: /Filo dağılımı|Fleet distribution/ }))
    expect(screen.getByText('SHA256withECDSA')).toBeInTheDocument()
  })

  it('DOLU rapor: sertifika / TLS / zincir bulguları ayrı bölümlerde; istisnalı satır çipli; trend tespit/temizlenme listeleri', async () => {
    api.admin.getWeakAlgorithms.mockResolvedValue(RICH)
    render(<WeakAlgorithmReport />)
    await screen.findByText(/Toplam 2 zayıf sertifika|2 weak certificates total/)
    expect(screen.getByText('sha1.example.com')).toBeInTheDocument()
    expect(screen.getByText('legacy.example.com')).toBeInTheDocument()
    expect(screen.getByText('TLS_RSA_WITH_RC4_128_SHA')).toBeInTheDocument()
    expect(screen.getByText('broken.example.com')).toBeInTheDocument()
    // Bulgu çipleri kural adıyla (anahtar değil)
    expect(screen.getByText(/TLS 1\.0 \/ 1\.1 \/ SSL/)).toBeInTheDocument()
    expect(screen.getByText(/Zincir kırık|Broken chain/)).toBeInTheDocument()
    // İstisna çipi
    expect(screen.getByText(/İstisna · 2026-12-31|Exception · 2026-12-31/)).toBeInTheDocument()
    // Trend
    fireEvent.click(screen.getByRole('button', { name: /Son 30 gün|Last 30 days/ }))
    expect(screen.getByText(/Tespit edilen \(1\)|Detected \(1\)/)).toBeInTheDocument()
    expect(screen.getByText(/gone\.example\.com/)).toBeInTheDocument()
    expect(document.querySelectorAll('.wa-trend-bar').length).toBe(30)
  })

  it('eylemler: "Şimdi kontrol et" sağlık tazeleme ucunu çağırır; "Takıma bildir" onay sonrası notify ucunu çağırır ve sonucu bildirir', async () => {
    api.admin.getWeakAlgorithms.mockResolvedValue(RICH)
    render(<WeakAlgorithmReport />)
    await screen.findByText('sha1.example.com')
    const row = screen.getByText('sha1.example.com').closest('tr')
    fireEvent.click(within(row).getByRole('button', { name: /Şimdi kontrol et|Check now/ }))
    await waitFor(() => expect(api.refreshCertificateHealth).toHaveBeenCalledWith('sha1.example.com'))
    await waitFor(() => expect(api.admin.getWeakAlgorithms).toHaveBeenCalledTimes(2))   // tazeleme sonrası yeniden yükleme

    fireEvent.click(within(row).getByRole('button', { name: /Takıma bildir|Notify team/ }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    await waitFor(() => expect(api.admin.notifyWeakAlgorithm).toHaveBeenCalledWith('sha1.example.com'))
    expect(await screen.findByText(/takim-a@example\.com.*2|2.*takim-a@example\.com/)).toBeInTheDocument()
  })

  it('istisna: modal bitiş tarihi + gerekçe ile POST; istisnalı satırda "kaldır" DELETE çağırır', async () => {
    api.admin.getWeakAlgorithms.mockResolvedValue(RICH)
    render(<WeakAlgorithmReport />)
    await screen.findByText('sha1.example.com')
    const row = screen.getByText('sha1.example.com').closest('tr')
    fireEvent.click(within(row).getByRole('button', { name: /^(İstisna|Exception)$/ }))
    const dlg = await screen.findByRole('dialog')
    const save = within(dlg).getByRole('button', { name: /İstisnayı kaydet|Save exception/ })
    expect(save).toBeDisabled()   // tarih zorunlu
    fireEvent.change(dlg.querySelector('input[type="date"]'), { target: { value: '2026-12-31' } })
    fireEvent.change(dlg.querySelector('textarea'), { target: { value: 'planlı yenileme' } })
    expect(save).toBeEnabled()
    fireEvent.click(save)
    await waitFor(() => expect(api.admin.setWeakAlgorithmException).toHaveBeenCalledWith('sha1.example.com', { reason: 'planlı yenileme', until: '2026-12-31' }))

    const accepted = screen.getByText('accepted.example.com').closest('tr')
    fireEvent.click(within(accepted).getByRole('button', { name: /İstisnayı kaldır|Remove exception/ }))
    await waitFor(() => expect(api.admin.clearWeakAlgorithmException).toHaveBeenCalledWith('accepted.example.com'))
  })

  it('CSV dışa aktarma window.open ile export ucunu açar', async () => {
    api.admin.getWeakAlgorithms.mockResolvedValue(EMPTY)
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    render(<WeakAlgorithmReport />)
    await screen.findByText(/Temiz:|Clean:/)
    fireEvent.click(screen.getByRole('button', { name: /CSV/ }))
    expect(open).toHaveBeenCalledWith('/api/admin/audit/weak-algorithms/export', '_blank')
    open.mockRestore()
  })

  it('manage yetkisi YOKSA bildir / istisna düğmeleri çizilmez, kontrol et kalır', async () => {
    perm.canEdit = false
    api.admin.getWeakAlgorithms.mockResolvedValue(RICH)
    render(<WeakAlgorithmReport />)
    await screen.findByText('sha1.example.com')
    const row = screen.getByText('sha1.example.com').closest('tr')
    expect(within(row).getByRole('button', { name: /Şimdi kontrol et|Check now/ })).toBeInTheDocument()
    expect(within(row).queryByRole('button', { name: /Takıma bildir|Notify team/ })).toBeNull()
    expect(within(row).queryByRole('button', { name: /^(İstisna|Exception)$/ })).toBeNull()
  })

  it('yükleme hatası: hata + "Yeniden dene" çizilir', async () => {
    api.admin.getWeakAlgorithms.mockResolvedValue({ success: false, error: 'boom' })
    render(<WeakAlgorithmReport />)
    expect(await screen.findByRole('alert')).toHaveTextContent('boom')
    fireEvent.click(screen.getByRole('button', { name: /Yeniden dene|Try again/ }))
    await waitFor(() => expect(api.admin.getWeakAlgorithms).toHaveBeenCalledTimes(2))
  })
})
