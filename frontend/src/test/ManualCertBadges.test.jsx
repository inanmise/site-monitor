import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'

/**
 * "Manuel" rozeti ve "Yüklenen dosya" kontrol yolu (2026-10-06): YALNIZ `cert_source === 'MANUAL'` satırlarında — Genel
 * Bakış kartı, Tüm Sertifikalar tablosu, Envanter tablosu / kartları / detayı. Ağ satırları birebir aynı kalır (rozet yok,
 * "Doğrudan"/"Proxy", ağ eylemleri — tanılama, kopyala — yerinde).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ getCertificatesPaginated: vi.fn(), admin: { getInventoryByDomain: vi.fn(async () => ({ success: true, data: null })) } }),
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
}))
vi.mock('react-markdown', () => ({ default: ({ children }) => <div>{children}</div> }))
vi.mock('remark-gfm', () => ({ default: () => {} }))

import { api } from '../api/client'
import CertificateCard from '../components/CertificateCard.jsx'
import CertificatesTable from '../components/CertificatesTable.jsx'
import InventoryTable, { rowMenuItems } from '../components/inventory/InventoryTable.jsx'
import InventoryCardList from '../components/inventory/InventoryCardList.jsx'
import { InventoryDetails } from '../components/inventory/InventoryDetails.jsx'

const cert = (over = {}) => ({
  domain: 'net.example.test', subject: 'CN=net.example.test', issuer_cn: 'Example CA', issuer: 'Example CA',
  not_after: '2027-01-01T00:00:00', checked_at: '2026-10-01T00:00:00', status: 'valid', warning: false, days_remaining: 90,
  team_id: 1, team_name: 'Takım A', via: 'direct', ...over,
})
const manual = (over = {}) => cert({ domain: 'keystore.example.test', cert_source: 'MANUAL', manual_version: 3,
  manual_uploaded_at: '2026-09-30T08:00:00', via: 'upload', ...over })

describe('Genel Bakış kartı', () => {
  it('manuel kart: rozet (sürüm açıklaması dokununca), "Yüklenen dosya", "Yeniden değerlendir"', async () => {
    render(<CertificateCard cert={manual()} onClick={() => {}} onCheckNow={() => {}} />)
    const badge = document.querySelector('[data-slot="manual-cert-badge"]')
    expect(badge).toHaveTextContent(/Uploaded|Manuel/)
    expect(badge).toHaveAttribute('data-version', '3')
    fireEvent.click(badge.closest('button'))
    expect(await screen.findByRole('tooltip')).toHaveTextContent(/Uploaded from a file · version 3/)
    expect(document.querySelector('[data-slot="cert-via"]')).toHaveAttribute('data-via', 'upload')
    expect(document.querySelector('[data-slot="cert-via"]')).toHaveTextContent(/Uploaded file|Yüklenen dosya/)
    expect(screen.getByRole('button', { name: /keystore\.example\.test — (Re-evaluate|Yeniden değerlendir)/ })).toBeInTheDocument()
  })

  it('ağ kartı DEĞİŞMEZ: rozet yok, "Doğrudan", "Şimdi kontrol et"', () => {
    render(<CertificateCard cert={cert()} onClick={() => {}} onCheckNow={() => {}} />)
    expect(document.querySelector('[data-slot="manual-cert-badge"]')).toBeNull()
    expect(document.querySelector('[data-slot="cert-via"]')).not.toHaveAttribute('data-via')
    expect(document.querySelector('[data-slot="cert-via"]')).toHaveTextContent(/^(Direct|Doğrudan)$/)
    expect(screen.getByRole('button', { name: /net\.example\.test — (Check Now|Şimdi Kontrol Et)/i })).toBeInTheDocument()
  })
})

describe('Tüm Sertifikalar tablosu', () => {
  beforeEach(() => { vi.clearAllMocks(); localStorage.clear(); window.history.replaceState(null, '', '/') })

  it('alan adı hücresinde rozet yalnız manuel satırda', async () => {
    api.getCertificatesPaginated.mockResolvedValue({ success: true, data: [manual(), cert()], pagination: { current_page: 1, total: 2, total_pages: 1 } })
    render(<CertificatesTable onRowClick={() => {}} />)
    await screen.findByText('keystore.example.test')
    expect(document.querySelector('tr[data-domain="keystore.example.test"] [data-slot="manual-cert-badge"]')).toBeTruthy()
    expect(document.querySelector('tr[data-domain="net.example.test"] [data-slot="manual-cert-badge"]')).toBeNull()
  })
})

describe('Envanter', () => {
  const rows = [
    { id: 1, domain: 'keystore.example.test', port: 443, team_id: 1, team_name: 'Takım A', active: true, cert_source: 'MANUAL', manual_version: 2, cert_status: 'valid', cert_days_remaining: 50 },
    { id: 2, domain: 'net.example.test', port: 443, team_id: 1, team_name: 'Takım A', active: true, cert_status: 'valid', cert_days_remaining: 50 },
  ]
  const common = { canManage: true, isAdmin: true, teamsCount: 2, selected: new Set(), onToggle: () => {}, onShow: () => {}, onInline: () => {} }

  it('tablo ve kart listesi: rozet yalnız manuel satırda', () => {
    const { unmount } = render(<InventoryTable rows={rows} cols={['domain']} sort="domain|asc" onSort={() => {}} density="comfortable"
      onToggleAll={() => {}} allOnPage={false} statusFilter="active" {...common} />)
    expect(document.querySelector('[data-inv-row="keystore.example.test"] [data-slot="manual-cert-badge"]')).toBeTruthy()
    expect(document.querySelector('[data-inv-row="net.example.test"] [data-slot="manual-cert-badge"]')).toBeNull()
    unmount()
    render(<InventoryCardList rows={rows} {...common} />)
    expect(document.querySelector('[data-inv-card="keystore.example.test"] [data-slot="manual-cert-badge"]')).toBeTruthy()
    expect(document.querySelector('[data-inv-card="net.example.test"] [data-slot="manual-cert-badge"]')).toBeNull()
  })

  it('satır menüsü: manuel kayıtta tanılama ve kopyala yok; ağ kaydında aynı kalır', () => {
    const t = (k) => k
    const args = { t, ro: false, isAdmin: true, canManage: true, canEditRow: () => true, teamsCount: 2, onShow: () => {} }
    const visible = (r) => rowMenuItems({ r, ...args }).filter((i) => !i.hidden).map((i) => i.label)
    expect(visible(rows[1])).toEqual(['inv.show', 'inv.checkNow', 'inv.diagnose', 'inv.edit', 'mon.duplicate', 'inv.transfer', 'inv.delete'])
    expect(visible(rows[0])).toEqual(['inv.show', 'inv.checkNow', 'inv.edit', 'inv.transfer', 'inv.delete'])
  })

  it('detay: manuel kayıtta rozet + "Kaynak: yüklenen dosya · sürüm"; port / TLS / sıklık / zaman aşımı ve "siteyi aç" yok', () => {
    const { unmount } = render(<InventoryDetails record={{ ...rows[0], tls_mode: 'browser', check_interval_hours: 6 }} />)
    const summary = document.querySelector('[data-slot="inv-summary"]')
    expect(within(summary).getByText(/Uploaded|Manuel/)).toBeInTheDocument()
    expect(screen.getByText(/Uploaded file · version 2|Yüklenen dosya · sürüm 2/)).toBeInTheDocument()
    expect(screen.queryByText(/^(TLS mode|TLS modu|TLS Modu)$/i)).toBeNull()
    expect(screen.queryByRole('link', { name: /Open site|Siteyi aç/i })).toBeNull()
    unmount()
    render(<InventoryDetails record={{ ...rows[1] }} />)
    expect(document.querySelector('[data-slot="manual-cert-badge"]')).toBeNull()
    expect(screen.queryByText(/Uploaded file · version/)).toBeNull()
    expect(screen.getByRole('link', { name: /Open site|Siteyi aç|Open the site/i })).toBeInTheDocument()
  })
})
