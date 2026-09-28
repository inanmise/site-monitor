import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * Envanter çekmecesi "Kontroller" sekmesi (2026-09-28): sertifika penceresinin ZENGİN Kontrol Geçmişi (CertCheckHistory) —
 * eski düz 4 sütunlu `upt-rt-*` satırlar kalktı. Korunan çekmece davranışları: adres çubuğuna YAZMAZ (tablo sayfasının kendi
 * parametreleri var), canlı yenileme YOK, aralık ön ayarları 1/7/30/90 (varsayılan 7), sayfa boyutu tercihi `inventory-checks`.
 * Tel biçimi GERÇEK (CertificateCheck, snake_case, UTC `Z`siz); zamanlar `Date.now()`'dan türer.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', async (importOriginal) => {
  const real = await importOriginal()
  return {
    ...real,
    api: withApiFallback({
      monitoring: {
        getCheckHistory: vi.fn(),
        getCheckHistoryCsvUrl: vi.fn(() => '/api/monitoring/uptime/a.example.com/ssl-history?format=csv'),
        getSslResponseSeries: vi.fn(),
      },
    }),
  }
})
vi.mock('../components/history/ChangeHistoryTab.jsx', () => ({ default: () => <div>CHANGES-TAB</div> }))
import { api } from '../api/client'
import InventoryDrawer from '../components/inventory/InventoryDrawer.jsx'

const H = 3_600_000
const D = 24 * H
const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const inDays = (d) => new Date(Date.now() + d * D).toISOString().slice(0, 19)
const PKIX = 'PKIX path building failed: unable to find valid certification path to requested target (a.example.com:443)'
const check = (id, hoursAgo, over = {}) => ({
  id, domain: 'a.example.com', checked_at: iso(hoursAgo * H), status: 'valid', warning: false, days_remaining: 120,
  not_before: iso(245 * D), not_after: inDays(120), subject: 'a.example.com', issuer: 'Example Trust Ltd', issuer_cn: 'Example TLS RSA CA 2026',
  serial_number: '0A1B2C', fingerprint: 'AA'.repeat(32), chain_status: 'VALID', revocation_status: 'VALID', trust_status: 'TRUSTED',
  deployment_status: 'UNKNOWN', tls_version: 'TLSv1.3', cipher_suite: 'TLS_AES_256_GCM_SHA384', response_ms: 412, error: null, error_class: null,
  maintenance: false, ...over,
})
const ITEMS = [
  check(3, 1),
  check(2, 2, { status: 'error', days_remaining: null, not_after: null, issuer: null, issuer_cn: null, fingerprint: null, serial_number: null, error: PKIX, error_class: 'CERT' }),
  check(1, 3),
]
const envelope = (over = {}) => ({ success: true, data: {
  items: ITEMS, page: 0, size: 50, total: ITEMS.length, counts: { total: 24, fail: 1 }, buckets: [], alerts: [],
  range: { from: iso(7 * D), to: iso(0) }, ...over,
} })
const SERIES = { success: true, data: { bucket: 'hour', from: iso(7 * D), to: iso(0), down_total: 1, series: [
  { ts: iso(3 * H), count: 1, down: 0, days: 120 }, { ts: iso(2 * H), count: 1, down: 1, days: null }, { ts: iso(1 * H), count: 1, down: 0, days: 120 },
] } }

const REC = { id: 1, domain: 'a.example.com', port: 443, active: true, team_id: 5, team_name: 'Takım A', tier: 1, cert_status: 'valid',
  cert_days_remaining: 120, cert_checked_at: iso(H), can_manage: true }

const mainCalls = () => api.monitoring.getCheckHistory.mock.calls.filter(([, , p]) => !(p?.status === 'fail' && p?.size === 1))

async function openChecks() {
  render(<InventoryDrawer record={REC} records={[REC]} canManage onClose={vi.fn()} onEdit={vi.fn()} onCheckNow={vi.fn()}
    onDelete={vi.fn()} onNavigate={vi.fn()} />)
  const dlg = await screen.findByRole('dialog', { name: 'a.example.com' })
  pressMenuTrigger(within(dlg).getByRole('tab', { name: /^(Checks|Kontroller)$/ }))
  await within(dlg).findByText(PKIX, {}, { timeout: 10_000 })   // CertCheckHistory tembel yüklenir (soğuk dönüşüm)
  return dlg
}

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState(null, '', '/?tab=inventory')
  api.monitoring.getCheckHistory.mockImplementation((kind, id, p = {}) => Promise.resolve(p.status === 'fail'
    ? envelope({ items: [ITEMS[1]], total: 1 }) : envelope()))
  api.monitoring.getSslResponseSeries.mockResolvedValue(SERIES)
})

describe('Envanter çekmecesi — Kontroller sekmesi = zengin sertifika geçmişi', () => {
  it('sertifika kutucukları + eğilim + sütunlu satırlar; eski düz satırlar yok; istek uptime-ssl · alan adı · 7 gün', async () => {
    const dlg = await openChecks()
    expect(dlg.querySelector('[data-slot="cert-hist-insights"]')).not.toBeNull()
    expect(dlg.querySelectorAll('[data-slot="hist-tile"]')).toHaveLength(6)
    expect(within(dlg).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Time', 'Status', 'Days left', 'Certificate', 'Details'])
    expect([...dlg.querySelectorAll('[data-slot="cert-hist-status"]')].map((b) => b.getAttribute('data-status'))).toEqual(['ok', 'fail', 'ok'])
    expect(dlg.querySelector('[class*="upt-rt-"]')).toBeNull()
    expect(dlg.querySelector('[data-grid]')).toBeNull()
    expect(mainCalls()[0]).toEqual(['uptime-ssl', 'a.example.com', expect.objectContaining({ days: 7, page: 0 })])
    await waitFor(() => expect(api.monitoring.getSslResponseSeries).toHaveBeenCalledWith('a.example.com', { days: 7 }))
    // Aralık ön ayarları çekmecede aynı: 1 / 7 / 30 / 90 gün + özel; 7 seçili
    expect(within(dlg).getByRole('button', { name: 'Last 7 days' })).toHaveAttribute('aria-pressed', 'true')
    expect(within(dlg).getByRole('button', { name: 'Last 90 days' })).toBeInTheDocument()
  })

  it('satır ayrıntısı çekmecede de açılır (tam hata + kopyala)', async () => {
    const dlg = await openChecks()
    const toggles = within(dlg).getAllByRole('button', { name: / — Details$/ })
    fireEvent.click(toggles[1])
    const region = await within(dlg).findByRole('region', { name: /Details of the check at/ })
    expect(within(region).getByText(PKIX)).toBeInTheDocument()
    expect(within(region).getByRole('button', { name: / — Copy error message$/ })).toBeInTheDocument()
  })

  it('adres çubuğuna YAZMAZ (süzgeç + aralık değişse de) ve canlı yenileme yok', async () => {
    const dlg = await openChecks()
    const before = window.location.search
    fireEvent.click(dlg.querySelectorAll('[data-slot="hist-tile"]')[1])          // Başarısız süzgeci
    await waitFor(() => expect(mainCalls().at(-1)[2]).toMatchObject({ status: 'fail' }))
    fireEvent.click(within(dlg).getByRole('button', { name: 'Last 30 days' }))
    await waitFor(() => expect(mainCalls().at(-1)[2]).toMatchObject({ days: 30 }))
    // useUrlQuerySync yazımı 300 ms debounce'lu — beklemeden okumak yazımı hiç görmezdi (ısırma M15 bunu yakaladı)
    await new Promise((r) => setTimeout(r, 500))
    expect(window.location.search).toBe(before)
    for (const k of ['range', 'hst', 'hfrom', 'hto']) expect(new URLSearchParams(window.location.search).has(k)).toBe(false)
    expect(dlg.querySelector('[data-slot="hist-live"]')).toBeNull()
  })

  it('boş aralıkta açıklama çekmecede olmayan "başlıktaki Çalıştır"ı anmaz; aralığı genişletmeyi önerir', async () => {
    api.monitoring.getCheckHistory.mockImplementation(() => Promise.resolve(envelope({ items: [], total: 0, counts: { total: 0, fail: 0 } })))
    api.monitoring.getSslResponseSeries.mockResolvedValue({ success: true, data: { bucket: 'hour', series: [], down_total: 0 } })
    render(<InventoryDrawer record={REC} records={[REC]} canManage onClose={vi.fn()} onEdit={vi.fn()} onCheckNow={vi.fn()}
      onDelete={vi.fn()} onNavigate={vi.fn()} />)
    const dlg = await screen.findByRole('dialog', { name: 'a.example.com' })
    pressMenuTrigger(within(dlg).getByRole('tab', { name: /^(Checks|Kontroller)$/ }))
    await within(dlg).findByText('No certificate checks in this range', {}, { timeout: 10_000 })
    expect(within(dlg).getByText('This certificate wasn’t checked during the selected range. Try a wider range.')).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Show the last 90 days' }))
    await waitFor(() => expect(mainCalls().at(-1)[2]).toMatchObject({ days: 90 }))
  })
})
