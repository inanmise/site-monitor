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

/**
 * Genel Bakış kartı zengin görünümü (2026-09-19; 2026-09-27 yeniden tasarım): sağlık + açık alarm kutuları, bulgu
 * çipleri, erişilebilirlik kutusu, değişim/paylaşım/bakım/sorumlu çipleri; plan çipi kahraman panelde. Tıklamalar kart
 * onClick'ini AÇMAZ; kompakt (extra yok) = zengin bölüm yok.
 */
describe('CertificateCard — zengin görünüm', () => {
  it('extra verilmezse zengin blok yok (kompakt = temel bilgiler)', () => {
    render(<CertificateCard cert={CERT} onClick={() => {}} />)
    expect(document.querySelector('[data-slot="cert-extras"]')).toBeNull()
  })

  it('tüm bölümler: sağlık 12/14 · 2 bulgu (zincir kırmızı), açık alarm KRİTİK ·2, erişilebilirlik %91.7 + grafik, değişim + Onayla, plan gecikti, paylaşım 3 · 5 SAN, bakımda, 2 sorumlu', () => {
    const onClick = vi.fn(), onOpenHealth = vi.fn(), onConfirm = vi.fn(), onPlan = vi.fn(), nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    render(<CertificateCard cert={CERT} onClick={onClick} extra={EXTRA} onOpenHealth={onOpenHealth} onConfirmRenewal={onConfirm} onPlanRenewal={onPlan} />)
    const health = document.querySelector('[data-slot="cert-extras-health"]')
    expect(health).toHaveAttribute('data-tone', 'bad')                        // kritik bulgu (zincir) → kırmızı kutu
    expect(health.textContent).toMatch(/12\/14/)
    expect(screen.getByText(/^2 findings$|^2 bulgu$/)).toBeInTheDocument()
    fireEvent.click(health)
    expect(onOpenHealth).toHaveBeenCalledWith('a.example.com')
    const chain = screen.getByRole('button', { name: /Zincir kırık$|Broken chain$/ })
    expect(chain).toHaveAttribute('data-tone', 'bad')
    fireEvent.click(chain)
    expect(onOpenHealth).toHaveBeenCalledTimes(2)
    expect(onClick).not.toHaveBeenCalled()                                   // kart detayı açılmadı
    const alerts = screen.getByRole('button', { name: /2 açık alarm|2 open alerts/ })
    expect(alerts).toHaveAttribute('data-tone', 'bad')
    expect(alerts.textContent).toMatch(/Critical|Kritik/)
    fireEvent.click(alerts)
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'alerthistory', params: { incident: 41 } })
    expect(screen.getByText('91.7%')).toBeInTheDocument()   // EN render: yüzde sonda (QA ISSUE-004); TR'de '%91.7'
    expect(document.querySelector('[data-slot="cert-extras-spark"] svg')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Onayla$|Confirm$/ }))
    expect(onConfirm).toHaveBeenCalledWith('a.example.com')
    fireEvent.click(screen.getByRole('button', { name: /Plan gecikti|Plan overdue/ }))
    expect(onPlan).toHaveBeenCalledWith(CERT, EXTRA.renewal)
    expect(screen.getByText(/^3 alan adında ortak · 5 SAN$|^Shared across 3 domains · 5 SANs$/)).toBeInTheDocument()
    expect(screen.getByText(/Bakımda|In maintenance/)).toBeInTheDocument()
    expect(screen.getByText(/2 sorumlu|2 contacts/)).toBeInTheDocument()
    expect(onClick).not.toHaveBeenCalled()
    window.removeEventListener('sm:navigate', nav)
  })

  it('"şu an" şeridi: ayakta · ms + son alarm (çözüldü) gri; erişilemiyor + açık alarm kırmızı; tıklama Durum İzleme (?q=) ve kartı açmaz; kompaktta da çizilir', () => {
    const onClick = vi.fn(), nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    const { unmount } = render(<CertificateCard cert={CERT} onClick={onClick}
      live={{ uptime: { last_status: 'up', last_ms: 210, last_at: new Date(Date.now() - 5 * 60000).toISOString().slice(0, 19) }, alert: { id: 3, level: 'HIGH', type: 'HTTP_DOWN', resolved: true, at: '2026-09-10T10:00:00', resolved_at: new Date(Date.now() - 3 * 86400000).toISOString().slice(0, 19) } }} />)
    const strip = document.querySelector('[data-cert-live]')
    expect(strip).toHaveAttribute('data-tone', 'ok')
    expect(strip.textContent).toMatch(/Ayakta|Up/); expect(strip.textContent).toContain('210ms'); expect(strip.textContent).toMatch(/3g ✓|3d ✓/); expect(strip.querySelector('[data-bell="off"]')).not.toBeNull()   // çözülmüş alarm: sessiz zil + yaş
    expect(strip.title).toMatch(/5dk önce|5m ago/)
    fireEvent.click(strip)
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'uptime', params: { q: 'a.example.com' } })
    expect(onClick).not.toHaveBeenCalled()
    expect(document.querySelector('[data-slot="cert-extras"]')).toBeNull()   // extra yok = kompakt, şerit yine var
    unmount()
    render(<CertificateCard cert={CERT} onClick={onClick} live={{ uptime: { last_status: 'down', last_ms: null, last_at: '2026-09-19T11:00:00' }, alert: { id: 4, level: 'CRITICAL', type: 'ACCESSIBILITY', resolved: false, at: '2026-09-19T11:05:00' } }} />)
    const bad = document.querySelector('[data-cert-live]')
    expect(bad).toHaveAttribute('data-tone', 'bad')
    expect(bad.textContent).toMatch(/ERİŞİLEMİYOR|DOWN/)
    // Açık alarm = zil ikonu (erişilebilir adı "alarm AÇIK") + kısa seviye; uzun metin footer'da kesiliyordu (2026-09-20).
    expect(bad.querySelector('[data-bell="open"][aria-label]').getAttribute('aria-label')).toMatch(/alarm AÇIK|alert OPEN/)
    expect(bad.textContent).toMatch(/KRİTİK|CRITICAL/)
    expect(bad.textContent).not.toMatch(/AÇIK · CRITICAL|OPEN · CRITICAL/)
    window.removeEventListener('sm:navigate', nav)
  })

  it('zengin görünümde erişilebilirlik kutusu "şu an" şeridinin yerini alır (aynı bilgi iki kez çizilmez)', () => {
    const live = { uptime: EXTRA.uptime, alert: { id: 4, level: 'CRITICAL', type: 'ACCESSIBILITY', resolved: false, at: '2026-09-19T11:05:00' } }
    render(<CertificateCard cert={CERT} onClick={() => {}} extra={EXTRA} live={live} />)
    expect(document.querySelector('[data-slot="cert-extras-uptime"]')).not.toBeNull()
    expect(document.querySelector('[data-cert-live]')).toBeNull()
  })

  it('temiz sağlık + plan yok ve 30 gün altı → "Yenileme planla" kısayolu; açık alarm yok kutusu; kontak yoksa uyarı; pin uyuşmazlığında Onayla yok', () => {
    const onPlan = vi.fn()
    const extra = { health: { ok: 14, evaluated: 14, failed: [] }, change: { mismatch: true }, contacts: { missing: true }, shared: { count: 0, san_count: 4 } }
    render(<CertificateCard cert={CERT} onClick={() => {}} extra={extra} onPlanRenewal={onPlan} onConfirmRenewal={() => {}} />)
    expect(document.querySelector('[data-slot="cert-extras-health"]')).toHaveAttribute('data-tone', 'ok')
    expect(screen.getByText(/All checks passed|Tüm kontroller temiz/)).toBeInTheDocument()
    const none = document.querySelector('[data-slot="cert-extras-alerts"]')
    expect(none.tagName).toBe('DIV')                                        // açık alarm yok → salt bilgi kutusu
    expect(none.textContent).toMatch(/Nothing open|Açık alarm yok/)
    fireEvent.click(screen.getByRole('button', { name: /Yenileme planla|Plan renewal/ }))
    expect(onPlan).toHaveBeenCalledWith(CERT, null)
    expect(screen.getByText(/Pin uyuşmazlığı|Pin mismatch/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Onayla$|Confirm$/ })).toBeNull()
    expect(screen.getByText(/Sorumlu kişi yok|No contacts/)).toBeInTheDocument()
    expect(screen.getByText(/4 SAN/)).toBeInTheDocument()
  })

  it('kartın gerekçe çipi aynı kusuru gösteriyorsa sağlık bulgusu çipi TEKRAR çizilmez (tek "Broken chain")', () => {
    const cert = { ...CERT, chain_status: 'BROKEN' }
    render(<CertificateCard cert={cert} onClick={() => {}} extra={{ health: { ok: 12, evaluated: 14, failed: ['chain', 'protocol'] } }} onOpenHealth={() => {}} />)
    const chains = screen.getAllByRole('button', { name: /Broken chain$/ })
    expect(chains).toHaveLength(1)
    expect(chains[0]).toHaveAttribute('data-slot', 'hint-trigger')          // gerekçe çipi (açıklamalı)
    expect(screen.getByRole('button', { name: /Outdated TLS$/ })).toBeInTheDocument()
  })

  it('erişilebilirlik satırı Durum İzleme (?q=) açar; "sorumlu kişi" çipi onEditContacts ile formu kontak bölümünde açar; kart onClick tetiklenmez', () => {
    const onClick = vi.fn(), onEditContacts = vi.fn(), nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    const { unmount } = render(<CertificateCard cert={CERT} onClick={onClick} extra={{ ...EXTRA, contacts: { missing: true } }} onEditContacts={onEditContacts} />)
    fireEvent.click(document.querySelector('[data-slot="cert-extras-uptime"]'))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'uptime', params: { q: 'a.example.com' } })
    fireEvent.click(screen.getByText(/Sorumlu kişi yok|No contacts/).closest('button'))
    expect(onEditContacts).toHaveBeenCalledWith('a.example.com')
    expect(onClick).not.toHaveBeenCalled()
    unmount()
    // yetkisiz (onEditContacts yok) → çip düğme değil, salt bilgi
    render(<CertificateCard cert={CERT} onClick={onClick} extra={{ ...EXTRA, contacts: { missing: true } }} />)
    expect(screen.getByText(/Sorumlu kişi yok|No contacts/).closest('button')).toBeNull()
    window.removeEventListener('sm:navigate', nav)
  })

  it('düzenleme yetkisi yokken sorumlu listesi dokununca açılan açıklamada (yalnız-hover bilgi yok)', () => {
    render(<CertificateCard cert={CERT} onClick={() => {}} extra={{ contacts: EXTRA.contacts }} />)
    fireEvent.click(screen.getByRole('button', { name: /2 contacts$|2 sorumlu$/ }))
    expect(screen.getByRole('tooltip').textContent).toMatch(/dev@example\.com[\s\S]*ops@example\.com/)
  })
})
