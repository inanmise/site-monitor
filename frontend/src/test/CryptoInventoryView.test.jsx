import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { EN } from '../i18n/en.js'   // varsayılan arayüz dili İngilizce (2026-10-02)

/**
 * Kripto envanteri / PQC hazırlık görünümü (2026-10-10): KPI + grafik + takım + liste aynı süzgeç durumunu paylaşır;
 * süzgeçler URL'de (`ci_*`); dışa aktarım tembel modülden (mock) süzülmüş + sıralı listeyle çağrılır ve denetim izi
 * istenir; hata → yeniden dene (fresh); dar kapta kart görünümü.
 */
const width = vi.hoisted(() => ({ value: 0 }))
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, width.value] }))

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const crypto = vi.hoisted(() => ({ get: vi.fn(), auditExport: vi.fn() }))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? '').slice(0, 10),
  formatDateSec: (s) => String(s ?? ''),
  api: withApiFallback({ cryptoInventory: crypto }),
}))
const exporter = vi.hoisted(() => ({
  exportCryptoXlsx: vi.fn(() => 3), exportCryptoPdf: vi.fn(async () => 3), exportCryptoCsv: vi.fn(() => 3),
}))
vi.mock('../components/cryptoinv/cryptoInventoryExport.js', () => exporter)

import CryptoInventoryView from '../components/cryptoinv/CryptoInventoryView.jsx'

const P = (score, band, extra = {}) => ({ score, band, exposure: 40, strength: 20, renewal: 15, hndl: 5, ...extra })
const ROWS = [
  { rank: 1, domain: 'odeme.example.com', source: 'NETWORK', port: 443, team_id: 1, team_name: 'Takım A', tier: 1, owner: 'Ödeme',
    key_algorithm: 'RSA', key_size: 2048, key_bucket: 'RSA_2048', key_family: 'RSA', signature_algorithm: 'SHA1withRSA', sig_hash: 'SHA1',
    remnants: ['SHA1_LEAF'], weak_intermediates: [], pqc: 'VULNERABLE', category: 'BROKEN', action: 'replace', migrate_by: '2026-10-10',
    not_after: '2026-11-01T00:00:00', days_remaining: 22, priority: P(95, 'P1'), data_source: 'CHECK' },
  { rank: 2, domain: 'portal.example.com', source: 'NETWORK', port: 8443, team_id: 2, team_name: 'Takım B', tier: 2,
    key_algorithm: 'RSA', key_size: 2048, key_bucket: 'RSA_2048', key_family: 'RSA', signature_algorithm: 'SHA256withRSA', sig_hash: 'SHA256',
    remnants: [], pqc: 'VULNERABLE', category: 'LEGACY', action: 'renew', migrate_by: '2027-01-01', not_after: '2027-01-01T00:00:00',
    days_remaining: 83, priority: P(65, 'P2'), data_source: 'CHECK' },
  { rank: 3, domain: 'keystore-app', source: 'MANUAL', manual_version: 2, team_id: 1, team_name: 'Takım A', tier: 3,
    key_algorithm: 'EC', key_size: 256, key_bucket: 'EC_P256', key_family: 'EC', signature_algorithm: 'SHA256withECDSA', sig_hash: 'SHA256',
    remnants: [], pqc: 'VULNERABLE', category: 'MODERN', action: 'pqc_plan', migrate_by: '2027-06-01', not_after: '2027-06-01T00:00:00',
    days_remaining: 234, priority: P(30, 'P3', { hndl: 0 }), data_source: 'UPLOAD' },
]
const DATA = {
  generated_at: '2026-10-10T08:00:00', data_as_of: '2026-10-10T07:55:00', oldest_check: '2026-10-09T07:00:00', scope: { all: true },
  summary: { total: 3, checked: 3, unchecked: 0, network: 2, manual: 1,
    by_pqc: { VULNERABLE: 3, HYBRID: 0, PQC: 0, UNKNOWN: 0 }, by_category: { BROKEN: 1, LEGACY: 1, MODERN: 1, PQC_READY: 0, UNKNOWN: 0 },
    by_band: { P1: 1, P2: 1, P3: 1, P4: 0, DONE: 0 },
    remnants: { md5_leaf: 0, sha1_leaf: 1, md5_intermediate: 0, sha1_intermediate: 0, sha1_root: 2, chains_examined: 2, affected: 1 },
    vulnerable_expiring_90d: 2, legacy_reissue: 0 },
  algorithms: [{ bucket: 'RSA_2048', family: 'RSA', count: 2, share: 66.7 }, { bucket: 'EC_P256', family: 'EC', count: 1, share: 33.3 }],
  signatures: [{ hash: 'SHA1', count: 1, share: 33.3, weak: true }, { hash: 'SHA256', count: 2, share: 66.7, weak: false }],
  signature_algorithms: [{ label: 'SHA256withRSA', hash: 'SHA256', count: 1 }],
  teams: [
    { team_id: 1, team_name: 'Takım A', total: 2, vulnerable: 2, remnants: 1, by_category: { BROKEN: 1, LEGACY: 0, MODERN: 1, PQC_READY: 0, UNKNOWN: 0 },
      by_band: { P1: 1, P2: 0, P3: 1, P4: 0, DONE: 0 }, top_score: 95, next_migrate_by: '2026-10-10' },
    { team_id: 2, team_name: 'Takım B', total: 1, vulnerable: 1, remnants: 0, by_category: { BROKEN: 0, LEGACY: 1, MODERN: 0, PQC_READY: 0, UNKNOWN: 0 },
      by_band: { P1: 0, P2: 1, P3: 0, P4: 0, DONE: 0 }, top_score: 65, next_migrate_by: '2027-01-01' },
  ],
  unowned: { total: 0 },
  rows: ROWS,
  rule: { exposure: { 1: 40, 2: 25, 3: 10, 4: 5, none: 15 }, strength: { BROKEN: 30, LEGACY: 20, UNKNOWN: 15, MODERN: 10, PQC_READY: 0 },
    renewal: [{ max_days: 30, points: 20 }], hndl: { external: 5, no_pfs: 5 }, bands: [{ band: 'P1', min: 70 }], max: 100 },
  thresholds: { rsa_2030_min_bits: 3072, sunset: '2030-12-31', kex_observable: false },
}

const listRows = () => [...document.querySelectorAll('[data-slot="cinv-row"]')].map((r) => r.getAttribute('data-domain'))

async function mount() {
  render(<CryptoInventoryView />)
  await screen.findByTestId('crypto-inventory')
  await waitFor(() => expect(listRows().length).toBeGreaterThan(0))
}

describe('CryptoInventoryView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    width.value = 0
    window.history.replaceState({}, '', '/?tab=weakalgo&ci_view=crypto')
    crypto.get.mockResolvedValue({ success: true, data: DATA })
    crypto.auditExport.mockResolvedValue({ success: true })
  })

  it('künye, hüküm bandı, KPI, grafikler, takım tablosu ve öncelik sıralı liste', async () => {
    await mount()
    expect(crypto.get).toHaveBeenCalledWith(false)
    expect(screen.getByText(EN['cinv.scopeAll'])).toBeTruthy()
    expect(screen.getByText(/3 of 3 endpoints/)).toBeTruthy()
    expect(document.querySelectorAll('[data-slot="cinv-kpis"] [data-slot="stat-item"]').length).toBe(8)
    expect(document.querySelector('[data-slot="cinv-pqc"]')).toBeTruthy()
    expect([...document.querySelectorAll('[data-slot="cinv-key-row"]')].map((b) => b.getAttribute('data-key'))).toEqual(['RSA_2048', 'EC_P256'])
    expect(document.querySelector('[data-slot="cinv-remnants"]').getAttribute('data-affected')).toBe('1')
    expect([...document.querySelectorAll('[data-slot="cinv-team-row"]')].map((r) => r.getAttribute('data-team'))).toEqual(['1', '2'])
    expect(listRows()).toEqual(['odeme.example.com', 'portal.example.com', 'keystore-app'])
    expect(screen.getByText(EN['cinv.listCount'].replace('{0}', '3').replace('{1}', '3'))).toBeTruthy()
  })

  it('kategori süzgeci listeyi daraltır ve URL\'ye yazılır; temizle geri getirir', async () => {
    await mount()
    fireEvent.change(document.querySelector('select[data-slot="cinv-f-category"]'), { target: { value: 'LEGACY' } })
    await waitFor(() => expect(listRows()).toEqual(['portal.example.com']))
    await waitFor(() => expect(window.location.search).toContain('ci_cat=LEGACY'))
    fireEvent.click(document.querySelector('[data-slot="cinv-clear"]'))
    await waitFor(() => expect(listRows()).toHaveLength(3))
  })

  it('arama ve sıralama', async () => {
    await mount()
    fireEvent.change(screen.getByRole('searchbox', { name: EN['cinv.f.search'] }), { target: { value: 'portal' } })
    await waitFor(() => expect(listRows()).toEqual(['portal.example.com']))
    fireEvent.change(screen.getByRole('searchbox', { name: EN['cinv.f.search'] }), { target: { value: '' } })
    fireEvent.change(document.querySelector('select[data-slot="cinv-f-sort"]'), { target: { value: 'domain' } })
    await waitFor(() => expect(listRows()).toEqual(['keystore-app', 'odeme.example.com', 'portal.example.com']))
  })

  it('KPI kutucuğu süzgeçtir: "Bugün zayıf" → BROKEN, ikinci tık kaldırır', async () => {
    await mount()
    const tile = document.querySelector('[data-slot="cinv-kpis"] [data-slot="stat-item"][data-key="broken"]')
      || [...document.querySelectorAll('[data-slot="cinv-kpis"] [data-slot="stat-item"]')].find((b) => b.textContent.includes(EN['cinv.kpi.broken']))
    fireEvent.click(tile)
    await waitFor(() => expect(listRows()).toEqual(['odeme.example.com']))
    expect(tile.getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(tile)
    await waitFor(() => expect(listRows()).toHaveLength(3))
  })

  it('grafik satırı süzgeçtir (anahtar kovası) ve kaldırılabilir çip olur', async () => {
    await mount()
    fireEvent.click(document.querySelector('[data-slot="cinv-key-row"][data-key="EC_P256"]'))
    await waitFor(() => expect(listRows()).toEqual(['keystore-app']))
    const chip = document.querySelector('[data-chip="bucket"]')
    expect(chip.textContent).toContain('ECDSA P-256')
    fireEvent.click(chip)
    await waitFor(() => expect(listRows()).toHaveLength(3))
  })

  it('SHA-1/MD5 kalıntı paneli ve takım "Listele" düğmesi süzer', async () => {
    await mount()
    fireEvent.click(within(document.querySelector('[data-slot="cinv-remnants"]')).getByRole('button'))
    await waitFor(() => expect(listRows()).toEqual(['odeme.example.com']))
    fireEvent.click(document.querySelector('[data-slot="cinv-clear"]'))
    fireEvent.click(screen.getByRole('button', { name: EN['cinv.teamShow'].replace('{0}', 'Takım B') }))
    await waitFor(() => expect(listRows()).toEqual(['portal.example.com']))
    expect(window.location.search).not.toContain('ci_team=1')
  })

  it('öncelik puanı dokununca bileşen dökümünü açar', async () => {
    await mount()
    const chip = document.querySelector('[data-slot="cinv-row"][data-domain="odeme.example.com"] [data-slot="cinv-priority"]')
    fireEvent.click(chip)
    expect(await screen.findByText(EN['cinv.breakdownTitle'].replace('{0}', '95'))).toBeTruthy()
  })

  it('Excel dışa aktarımı: tembel modül süzülmüş + sıralı listeyle çağrılır, denetim izi istenir', async () => {
    await mount()
    fireEvent.change(document.querySelector('select[data-slot="cinv-f-team"]'), { target: { value: '1' } })
    await waitFor(() => expect(listRows()).toEqual(['odeme.example.com', 'keystore-app']))
    pressMenuTrigger(document.querySelector('[data-slot="cinv-export"]'))
    fireEvent.click(await screen.findByRole('menuitem', { name: EN['cinv.exportXlsx'] }))
    await waitFor(() => expect(exporter.exportCryptoXlsx).toHaveBeenCalledTimes(1))
    const [data, rows, filters] = exporter.exportCryptoXlsx.mock.calls[0]
    expect(data).toBe(DATA)
    expect(rows.map((r) => r.domain)).toEqual(['odeme.example.com', 'keystore-app'])
    expect(filters.team).toBe('1')
    await waitFor(() => expect(crypto.auditExport).toHaveBeenCalledWith({ format: 'xlsx', rows: 3, filtered: true }))
  })

  it('PDF ve CSV de aynı modülden', async () => {
    await mount()
    pressMenuTrigger(document.querySelector('[data-slot="cinv-export"]'))
    fireEvent.click(await screen.findByRole('menuitem', { name: EN['cinv.exportPdf'] }))
    await waitFor(() => expect(exporter.exportCryptoPdf).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(document.querySelector('[data-slot="cinv-export"]').disabled).toBe(false))
    pressMenuTrigger(document.querySelector('[data-slot="cinv-export"]'))
    fireEvent.click(await screen.findByRole('menuitem', { name: EN['cinv.exportCsv'] }))
    await waitFor(() => expect(exporter.exportCryptoCsv).toHaveBeenCalledTimes(1))
  })

  it('yükleme hatası: açıklayıcı ileti + yeniden dene (fresh)', async () => {
    crypto.get.mockResolvedValueOnce({ success: false, error: 'Sunucu bakımda — birkaç dakika sonra yeniden deneyin.' })
    render(<CryptoInventoryView />)
    expect(await screen.findByText('Sunucu bakımda — birkaç dakika sonra yeniden deneyin.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: EN['cinv.retry'] }))
    await waitFor(() => expect(crypto.get).toHaveBeenLastCalledWith(true))
    await waitFor(() => expect(listRows()).toHaveLength(3))
  })

  it('URL süzgeçleri açılışta uygulanır (paylaşılan bağlantı)', async () => {
    window.history.replaceState({}, '', '/?tab=weakalgo&ci_view=crypto&ci_src=MANUAL')
    await mount()
    expect(listRows()).toEqual(['keystore-app'])
  })

  it('dar kap (telefon): tablo yerine kart listesi', async () => {
    width.value = 380
    render(<CryptoInventoryView />)
    await screen.findByTestId('crypto-inventory')
    await waitFor(() => expect(document.querySelectorAll('[data-slot="cinv-card"]').length).toBe(3))
    expect(document.querySelector('[data-slot="cinv-row"]')).toBeNull()
    expect(document.querySelectorAll('li[data-slot="cinv-team-row"]').length).toBe(2)
  })

  it('boş kapsam: açıklayıcı boş durum, dışa aktarım kapalı', async () => {
    crypto.get.mockResolvedValue({ success: true, data: { ...DATA, summary: { total: 0, by_pqc: {} }, rows: [], teams: [], algorithms: [], signatures: [] } })
    render(<CryptoInventoryView />)
    expect(await screen.findByText(EN['cinv.emptyTitle'])).toBeTruthy()
    expect(document.querySelector('[data-slot="cinv-export"]').disabled).toBe(true)
  })
})
