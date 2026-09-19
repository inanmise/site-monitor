import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import CertificateCard from '../components/CertificateCard.jsx'

vi.mock('../contexts/TeamDirectoryProvider.jsx', () => ({ useTeamDirectory: () => ({ byId: {}, open: () => {} }) }))

const CERT = { domain: 'a.example.com', days_remaining: 20, status: 'valid', not_before: '2026-08-01T00:00:00', not_after: '2026-10-09T00:00:00', issuer: 'CA' }
const EXTRA = {
  health: { ok: 12, evaluated: 14, failed: ['chain', 'protocol'] },
  alerts: { count: 2, level: 'CRITICAL', all_acked: false, first_id: 41, types: ['ACCESSIBILITY'] },
  uptime: { pct24: 91.7, checks24: 24, last_status: 'down', last_ms: 812, last_at: '2026-09-19T11:00:00', points: [100, 100, null, 50, 0] },
  change: { mismatch: false, changed_at: '2026-09-18T10:00:00', acked: false },
  renewal: { planned_at: '2026-09-10', by: 'Ali', note: 'CA ile görüşüldü', overdue: true, done: false },
  shared: { count: 3, domains: ['b.example.com', 'c.example.com', 'd.example.com'], san_count: 5 },
  maintenance: { active: true, until: '2026-09-19T14:00:00', name: 'Gece' },
  contacts: { app_dev: 'dev@example.com', iis_admin: null, svc_mgmt: 'ops@example.com', waf_admin: null, missing: false },
}

/** Genel Bakış kartı zengin görünümü (2026-09-19): 8 blok çizilir; tıklamalar kart onClick'ini AÇMAZ; kompakt (extra yok) = eski kart. */
describe('CertificateCard — zengin görünüm', () => {
  it('extra verilmezse zengin blok yok (kompakt = bugünkü kart)', () => {
    render(<CertificateCard cert={CERT} onClick={() => {}} />)
    expect(document.querySelector('.ccx')).toBeNull()
  })

  it('8 blok: sağlık (2 bulgu, zincir kırmızı), açık alarm KRİTİK ·2, erişilebilirlik %91.7 + sparkline, değişim + Onayla, plan gecikti, paylaşılan 3 · 5 SAN, bakımda, 2 sorumlu', () => {
    const onClick = vi.fn(), onOpenHealth = vi.fn(), onConfirm = vi.fn(), onPlan = vi.fn(), nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    render(<CertificateCard cert={CERT} onClick={onClick} extra={EXTRA} onOpenHealth={onOpenHealth} onConfirmRenewal={onConfirm} onPlanRenewal={onPlan} />)
    expect(screen.getByText(/2 sağlık bulgusu|2 health findings/)).toBeInTheDocument()
    const chain = screen.getByRole('button', { name: /^Zincir kırık$|^Broken chain$/ })
    expect(chain.className).toContain('is-bad')
    fireEvent.click(chain)
    expect(onOpenHealth).toHaveBeenCalledWith('a.example.com')
    expect(onClick).not.toHaveBeenCalled()                                   // kart detayı açılmadı
    fireEvent.click(screen.getByRole('button', { name: /2 açık alarm|2 open alerts/ }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'alerthistory', params: { incident: 41 } })
    expect(screen.getByText('%91.7')).toBeInTheDocument()
    expect(document.querySelector('.ccx-spark svg')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /^Onayla$|^Confirm$/ }))
    expect(onConfirm).toHaveBeenCalledWith('a.example.com')
    fireEvent.click(screen.getByRole('button', { name: /Plan gecikti|Plan overdue/ }))
    expect(onPlan).toHaveBeenCalledWith(CERT, EXTRA.renewal)
    expect(screen.getByText(/3 alan aynı sertifikayı paylaşıyor · 5 SAN|3 domains share this certificate · 5 SANs/)).toBeInTheDocument()
    expect(screen.getByText(/Bakımda|In maintenance/)).toBeInTheDocument()
    expect(screen.getByText(/2 sorumlu|2 contacts/)).toBeInTheDocument()
    expect(onClick).not.toHaveBeenCalled()
    window.removeEventListener('sm:navigate', nav)
  })

  it('temiz sağlık + plan yok ve 30 gün altı → "Yenileme planla" kısayolu; kontak yoksa uyarı; pin uyuşmazlığında Onayla yok', () => {
    const onPlan = vi.fn()
    const extra = { health: { ok: 14, evaluated: 14, failed: [] }, change: { mismatch: true }, contacts: { missing: true }, shared: { count: 0, san_count: 4 } }
    render(<CertificateCard cert={CERT} onClick={() => {}} extra={extra} onPlanRenewal={onPlan} onConfirmRenewal={() => {}} />)
    expect(screen.getByText(/Sağlık 14\/14 temiz|Health 14\/14 clean/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Yenileme planla|Plan renewal/ }))
    expect(onPlan).toHaveBeenCalledWith(CERT, null)
    expect(screen.getByText(/Pin uyuşmazlığı|Pin mismatch/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Onayla$|^Confirm$/ })).toBeNull()
    expect(screen.getByText(/Sorumlu kişi yok|No contacts/)).toBeInTheDocument()
    expect(screen.getByText(/4 SAN/)).toBeInTheDocument()
  })
})
