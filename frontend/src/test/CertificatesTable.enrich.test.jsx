import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import CertificatesTable from '../components/CertificatesTable.jsx'

// Tüm Sertifikalar zenginleştirmesi (2026-09-13): tazeleme sinyali, facet'li durum menüsü, başlıktan sıralama,
// URL derin bağlantı, satır seçimi + toplu işlem, satır menüsü, paylaşılan sertifika süzgeci, bayat rozeti,
// boş durum eylemi, ön ayar kaydet/uygula.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    getCertificatesPaginated: vi.fn(),
    certExportUrl: (params, cols) => `/api/certificates/export.csv?${new URLSearchParams({ ...params, cols: cols.join(',') })}`,
    checkDomain: vi.fn(),
    admin: { bulkInventory: vi.fn(), getTeams: vi.fn() },
  }),
  formatDate: (s) => s || 'N/A',
}))

import { api } from '../api/client'

const FACETS = { levels: { expired: 1, critical: 2, high: 0, warning: 0, valid: 5, error: 0 }, windows: { expired: 1, 7: 2, 30: 2, 60: 3, 90: 3 },
  teams: [{ id: 5, name: 'Takım A', count: 6 }], no_team: 2, insecure: 1, tiers: { 1: 3 }, nonstd_port: 0, all: 8 }

function paged(data, extra = {}) {
  return { success: true, data, pagination: { current_page: 1, total: data.length, total_pages: 1 }, facets: FACETS, shared: {}, ...extra }
}
const cert = (over) => ({
  domain: 'x.example.com', issuer_cn: 'Example CA', subject: 'CN=x', not_before: '2026-01-01T00:00:00', not_after: '2027-01-01T00:00:00',
  days_remaining: 200, warning: false, status: 'valid', alert_level: 'valid', checked_at: new Date(Date.now() - 60_000).toISOString().slice(0, 19),
  chain_status: 'VALID', trust_status: 'TRUSTED', revocation_status: 'VALID', san: ['x.example.com'], ...over,
})
const rows = () => [...document.querySelectorAll('tr[data-domain]')].map((r) => r.dataset.domain)
const lastQuery = () => api.getCertificatesPaginated.mock.calls.at(-1)[0]

describe('CertificatesTable — zenginleştirme (2026-09-13)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    try { localStorage.clear() } catch { /* yok */ }
    window.history.replaceState({}, '', '/?tab=all')
    api.getCertificatesPaginated.mockResolvedValue(paged([cert({ domain: 'a.example.com' }), cert({ domain: 'b.example.com', days_remaining: 3, alert_level: 'critical' })]))
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('refreshKey değişince satırlar yerinde kalarak SESSİZ tazelenir (eskiden tablo bayat kalıyordu)', async () => {
    const { rerender } = render(<CertificatesTable onRowClick={() => {}} refreshKey="t1" />)
    await screen.findByText('a.example.com')
    expect(api.getCertificatesPaginated).toHaveBeenCalledTimes(1)
    api.getCertificatesPaginated.mockResolvedValue(paged([cert({ domain: 'a.example.com', days_remaining: 199 })]))
    rerender(<CertificatesTable onRowClick={() => {}} refreshKey="t2" />)
    await waitFor(() => expect(api.getCertificatesPaginated).toHaveBeenCalledTimes(2))
    // Yükleniyor bloğu çizilmez, eski satır tazeleme boyunca ekranda kalır
    expect(document.querySelector('.pg-block')).toBeNull()
    await waitFor(() => expect(rows()).toEqual(['a.example.com']))
  })

  it('durum menüsü facet sayaçlarını gösterir; seçim sunucuya filter_status olarak gider ve çip olur', async () => {
    render(<CertificatesTable onRowClick={() => {}} />)
    await screen.findByText('a.example.com')
    fireEvent.click(screen.getByTitle(/Duruma göre|Filter by status|süz/i))
    const menu = document.querySelector('.cf-menu')
    expect(menu).not.toBeNull()
    expect(within(menu).getAllByText('2').length).toBeGreaterThan(0)   // Kritik: 2
    fireEvent.click(within(menu).getByText(/Kritik|Critical/))
    await waitFor(() => expect(lastQuery().filter_status).toBe('critical'))
    expect(document.querySelector('.ct-chip').textContent).toMatch(/Kritik|Critical/)
    // Çipi kaldır → süzgeç düşer
    fireEvent.click(document.querySelector('.ct-chip'))
    await waitFor(() => expect(lastQuery().filter_status).toBeUndefined())
  })

  it('başlığa tıklayınca sıralanır (asc→desc), aria-sort güncellenir ve URL c_sort taşır', async () => {
    render(<CertificatesTable onRowClick={() => {}} />)
    await screen.findByText('a.example.com')
    const th = document.querySelector('th[data-col="days"]')
    fireEvent.click(within(th).getByRole('button'))
    await waitFor(() => expect(lastQuery()).toMatchObject({ sort_by: 'days_remaining', sort_dir: 'asc' }))
    expect(th.getAttribute('aria-sort')).toBe('ascending')
    fireEvent.click(within(th).getByRole('button'))
    await waitFor(() => expect(lastQuery()).toMatchObject({ sort_by: 'days_remaining', sort_dir: 'desc' }))
    await waitFor(() => expect(window.location.search).toContain('c_sort=days_remaining'))
  })

  it('URL derin bağlantısı (c_st, c_win, c_team, c_sec) mount\'ta sunucu süzgecine döner; ?domain= alan süzgeci olur', async () => {
    window.history.replaceState({}, '', '/?tab=all&c_st=expired&c_win=30&c_team=5&c_sec=1&domain=q.example.com')
    render(<CertificatesTable onRowClick={() => {}} />)
    await waitFor(() => expect(api.getCertificatesPaginated).toHaveBeenCalled())
    expect(lastQuery()).toMatchObject({ filter_status: 'expired', filter_window: '30', filter_team: '5', filter_insecure: 'true', filter_domain: 'q.example.com' })
    expect(document.querySelectorAll('.ct-chip:not(.ct-chip--clear)')).toHaveLength(5)
  })

  it('satır seçimi toplu çubuğu açar; "Şimdi kontrol et" seçili alanları /check ile koşturur ve tazeler', async () => {
    api.checkDomain.mockResolvedValue({ success: true, data: { status: 'valid' } })
    const onRefresh = vi.fn()
    render(<CertificatesTable onRowClick={() => {}} onRefresh={onRefresh} />)
    await screen.findByText('a.example.com')
    expect(document.querySelector('.ct-bulkbar')).toBeNull()
    fireEvent.click(within(document.querySelector('tr[data-domain="b.example.com"]')).getByLabelText(/Toplu işlem için seç|Select for bulk/))
    const bar = document.querySelector('.ct-bulkbar')
    expect(bar.textContent).toMatch(/1 seçili|1 selected/)
    fireEvent.click(within(bar).getByText(/Şimdi kontrol et|Check now/))
    await waitFor(() => expect(api.checkDomain).toHaveBeenCalledWith('b.example.com'))
    await waitFor(() => expect(onRefresh).toHaveBeenCalled())
    await waitFor(() => expect(document.querySelector('.ct-bulkbar')).toBeNull())
  })

  it('yönetici: toplu kademe atama alan adı listesiyle /inventory/bulk set-tier çağırır', async () => {
    api.admin.bulkInventory.mockResolvedValue({ success: true, data: { processed: 1, skipped: 0 } })
    render(<CertificatesTable onRowClick={() => {}} canManage />)
    await screen.findByText('a.example.com')
    fireEvent.click(within(document.querySelector('tr[data-domain="a.example.com"]')).getByLabelText(/Toplu işlem için seç|Select for bulk/))
    const bar = document.querySelector('.ct-bulkbar')
    const tierBtn = within(bar).getByText(/Kademe ata|Set tier/)
    expect(tierBtn).toBeDisabled()
    // SearchableSelect: tetiği aç, seçeneğe mousedown
    const field = tierBtn.closest('.bulkbar-field')
    fireEvent.mouseDown(field.querySelector('.ss-trigger'))   // SearchableSelect mousedown ile açılır
    fireEvent.mouseDown([...document.querySelectorAll('.ss-option')].find((o) => o.textContent.trim() === 'T2'))
    await waitFor(() => expect(tierBtn).not.toBeDisabled())
    fireEvent.click(tierBtn)
    await waitFor(() => expect(api.admin.bulkInventory).toHaveBeenCalledWith([], 'set-tier', { domains: ['a.example.com'], tier: 2 }))
  })

  it('satır menüsü: "Alarm geçmişi" onRowClick(domain, "alerts") ile açar; satır Enter ile de açılır', async () => {
    const onRowClick = vi.fn()
    render(<CertificatesTable onRowClick={onRowClick} onCheckNow={() => {}} />)
    await screen.findByText('a.example.com')
    const row = document.querySelector('tr[data-domain="a.example.com"]')
    fireEvent.keyDown(row, { key: 'Enter' })
    expect(onRowClick).toHaveBeenCalledWith('a.example.com')
    fireEvent.click(within(row).getByLabelText(/Satır işlemleri|Row actions/))
    fireEvent.click(screen.getByText(/Alarm geçmişi|Alert history/))
    expect(onRowClick).toHaveBeenLastCalledWith('a.example.com', 'alerts')
  })

  it('paylaşılan sertifika rozeti (×N) aynı parmak izini süzer; bayat satır "bayat" rozeti taşır', async () => {
    api.getCertificatesPaginated.mockResolvedValue(paged(
      [cert({ domain: 'a.example.com', fingerprint: 'FP1' }), cert({ domain: 'old.example.com', checked_at: '2026-01-01T00:00:00' })],
      { shared: { 'a.example.com': 3 } }))
    render(<CertificatesTable onRowClick={() => {}} />)
    await screen.findByText('a.example.com')
    expect(within(document.querySelector('tr[data-domain="old.example.com"]')).getByText(/bayat|stale/)).toBeInTheDocument()
    fireEvent.click(within(document.querySelector('tr[data-domain="a.example.com"]')).getByText('×3'))
    await waitFor(() => expect(lastQuery().filter_fp).toBe('FP1'))
  })

  it('süzgeçli boş sonuç: durum bloğu + "Sıfırla" eylemi süzgeçleri temizler', async () => {
    window.history.replaceState({}, '', '/?tab=all&c_st=error')
    api.getCertificatesPaginated.mockResolvedValue(paged([]))
    render(<CertificatesTable onRowClick={() => {}} />)
    await waitFor(() => expect(document.querySelector('.status-block')).not.toBeNull())
    fireEvent.click(within(document.querySelector('.status-block')).getByRole('button'))
    await waitFor(() => expect(lastQuery().filter_status).toBeUndefined())
  })

  it('ön ayar: adla kaydedilir (localStorage), sıfırlama sonrası uygulanınca süzgeçler geri gelir', async () => {
    window.history.replaceState({}, '', '/?tab=all&c_win=90')
    render(<CertificatesTable onRowClick={() => {}} />)
    await screen.findByText('a.example.com')
    fireEvent.click(screen.getByRole('button', { name: /Ön ayarlar|Presets/ }))
    fireEvent.change(screen.getByPlaceholderText(/Ön ayar adı|Preset name/), { target: { value: '90 gün' } })
    fireEvent.click(screen.getByText(/^Kaydet$|^Save$/))
    expect(JSON.parse(localStorage.getItem('certtable-presets'))[0]).toMatchObject({ name: '90 gün', filters: { window: '90' } })
    fireEvent.click(document.querySelector('.ct-chip--clear'))
    await waitFor(() => expect(lastQuery().filter_window).toBeUndefined())
    // Menü hâlâ açık olabilir (dış tıklama mousedown ister); kapalıysa aç
    if (!document.querySelector('.ct-preset-apply')) fireEvent.click(screen.getByRole('button', { name: /Ön ayarlar|Presets/ }))
    fireEvent.click(document.querySelector('.ct-preset-apply'))
    await waitFor(() => expect(lastQuery().filter_window).toBe('90'))
  })

  it('CSV bağlantısı süzgeç + görünür sütunları taşır', async () => {
    window.history.replaceState({}, '', '/?tab=all&c_st=valid')
    render(<CertificatesTable onRowClick={() => {}} />)
    await screen.findByText('a.example.com')
    const href = document.querySelector('a[download]').getAttribute('href')
    expect(href).toContain('filter_status=valid')
    expect(href).toContain('cols=domain%2Cissuer')
  })
})
