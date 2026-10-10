import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from './test-utils.jsx'
import CertificatesTable from '../components/CertificatesTable.jsx'
import { readView } from '../components/certtable/certTableModel.js'

// api istemcisini mock'la — component mount'ta getCertificatesPaginated çağırır.
// Durum rozeti `data-status` (valid/critical/error) dilden BAĞIMSIZ → i18n metnine bağlanmadan doğrulanır.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({ getCertificatesPaginated: vi.fn() }),
  formatDate: (s) => s || 'N/A',
}))

import { api } from '../api/client'

function paged(data) {
  return { success: true, data, pagination: { current_page: 1, total: data.length, total_pages: 1 } }
}

const cert = (over) => ({
  domain: 'x.com', issuer_cn: 'Test CA', subject: 'CN=x', not_after: '2027-01-01T00:00:00',
  days_remaining: 200, warning: false, status: 'valid', checked_at: '2026-07-01T00:00:00', ...over,
})

describe('CertificatesTable', () => {
  beforeEach(() => vi.clearAllMocks())

  it('sertifika satırlarını alan adı + duruma göre doğru CSS sınıfıyla gösterir', async () => {
    api.getCertificatesPaginated.mockResolvedValue(paged([
      // Hüküm SUNUCUDAN gelir (alert_level). Tablo eskiden kendi sabit 30 gün merdivenini
      // kullanıyordu: days=10 burada "Kritik", kartta "Yüksek" görünüyordu. Artık iki ekran
      // aynı hükmü okuyor; days=10 varsayılan eşiklerde (kritik<=7, yüksek<=15) YÜKSEK'tir.
      cert({ domain: 'valid.com', days_remaining: 200, warning: false, status: 'valid', alert_level: 'valid' }),
      cert({ domain: 'crit.com', days_remaining: 3, warning: true, status: 'warning', alert_level: 'critical' }),
      cert({ domain: 'err.com', days_remaining: null, warning: true, status: 'error', alert_level: 'error' }),
    ]))

    const { container } = render(<CertificatesTable onRowClick={() => {}} />)

    await screen.findByText('valid.com')
    expect(screen.getByText('crit.com')).toBeInTheDocument()
    expect(screen.getByText('err.com')).toBeInTheDocument()

    // alert_level=critical → KRİTİK sınıfı (valid/warning değil)
    expect(container.querySelector('tr[data-domain="crit.com"] [data-slot="cert-level"][data-status="critical"]')).not.toBeNull()
    expect(container.querySelector('tr[data-domain="valid.com"] [data-slot="cert-level"][data-status="valid"]')).not.toBeNull()
    expect(container.querySelector('tr[data-domain="err.com"] [data-slot="cert-level"][data-status="error"]')).not.toBeNull()
  })

  // ── Org geneli görünürlük (2026-09-26): "Takımlarım | Tüm takımlar" + salt okunur yabancı satır ──
  describe('org geneli görünürlük', () => {
    const scopeGroup = () => screen.queryByRole('group', { name: /görünürlük kapsamı|visible teams/i })
    const withScope = (rows) => ({ ...paged(rows), scope: 'mine', visible_to_all: true })
    const foreignRow = cert({ domain: 'foreign.example.com', team_id: 9, team_name: 'Takım B', can_manage: false })
    const ownRow = cert({ domain: 'own.example.com', team_id: 5, team_name: 'Takım A', can_manage: true })
    beforeEach(() => { localStorage.clear(); window.history.replaceState(null, '', '/') })

    it('anahtar yalnız visible_to_all ile çizilir; "Tüm takımlar" isteğe scope=all, adrese c_scope=all yazar ve hatırlanır', async () => {
      api.getCertificatesPaginated.mockImplementation((q) => Promise.resolve({ ...paged(q.scope === 'all' ? [ownRow, foreignRow] : [ownRow]), scope: q.scope || 'mine', visible_to_all: true }))
      const { unmount } = render(<CertificatesTable onRowClick={() => {}} />)
      await screen.findByText('own.example.com')
      expect(api.getCertificatesPaginated.mock.calls.at(-1)[0].scope).toBeUndefined()
      fireEvent.click(screen.getByRole('button', { name: /tüm takımlar|all teams/i }))
      await screen.findByText('foreign.example.com')
      expect(api.getCertificatesPaginated.mock.calls.at(-1)[0].scope).toBe('all')
      await waitFor(() => expect(new URLSearchParams(window.location.search).get('c_scope')).toBe('all'))
      expect(readView()?.scope).toBe('all')   // kayıtlı görünüm (certTableModel VIEW_KEY) kapsamı hatırlar
      unmount()
      window.history.replaceState(null, '', '/')
      render(<CertificatesTable onRowClick={() => {}} />)
      await screen.findByText('foreign.example.com')
      expect(api.getCertificatesPaginated.mock.calls.at(-1)[0].scope).toBe('all')
    })

    it('ayar kapalı → anahtar yok', async () => {
      api.getCertificatesPaginated.mockResolvedValue({ ...paged([ownRow]), scope: 'mine', visible_to_all: false })
      render(<CertificatesTable onRowClick={() => {}} />)
      await screen.findByText('own.example.com')
      expect(scopeGroup()).toBeNull()
    })

    it('yabancı satır salt okunur: rozet var, seçim kutusu yok, tıklayınca onOpenReadOnly SATIRLA çağrılır (onRowClick değil); tümünü seç onu atlar', async () => {
      api.getCertificatesPaginated.mockResolvedValue({ ...withScope([ownRow, foreignRow]), scope: 'all' })
      const onRowClick = vi.fn(); const onOpenReadOnly = vi.fn()
      const { container } = render(<CertificatesTable onRowClick={onRowClick} onOpenReadOnly={onOpenReadOnly} canManage />)
      await screen.findByText('foreign.example.com')
      const foreign = container.querySelector('tr[data-domain="foreign.example.com"]')
      expect(foreign).toHaveAttribute('data-readonly', 'true')
      expect(foreign.querySelector('[data-slot="read-only-badge"]')).not.toBeNull()
      expect(foreign.querySelector('[role="checkbox"]')).toBeNull()
      const own = container.querySelector('tr[data-domain="own.example.com"]')
      expect(own.querySelector('[role="checkbox"]')).not.toBeNull()
      fireEvent.click(foreign)
      expect(onOpenReadOnly).toHaveBeenCalledWith(expect.objectContaining({ domain: 'foreign.example.com', team_name: 'Takım B' }), undefined)
      expect(onRowClick).not.toHaveBeenCalled()
      fireEvent.click(screen.getByRole('checkbox', { name: /tümünü seç|select all/i }))
      expect(await screen.findByText(/^1 .*(seçili|selected)/i)).toBeInTheDocument()
    })
  })

  it('satıra tıklayınca onRowClick alan adıyla çağrılır', async () => {
    const onRowClick = vi.fn()
    api.getCertificatesPaginated.mockResolvedValue(paged([cert({ domain: 'click.com' })]))

    const { container } = render(<CertificatesTable onRowClick={onRowClick} />)
    await screen.findByText('click.com')

    fireEvent.click(container.querySelector('tr[data-domain="click.com"]'))
    expect(onRowClick).toHaveBeenCalledWith('click.com')
  })

  // Envanterde 3284c40e ile düzeltilen bugun kardeş yüzeydeki hâli: süzgeç sonuç vermeyince tablo
  // TAMAMEN kaldırılıyordu, süzgeç satırı da onunla gidiyor ve kullanıcı yazdığı metni ne görebiliyor
  // ne temizleyebiliyordu (tek çıkış "Sıfırla" ile TÜM süzgeçleri kaybetmek).
  it('kolon süzgeci sonuç vermeyince tablo + süzgeç satırı ekranda kalır, satır içi "eşleşme yok" çıkar', async () => {
    localStorage.setItem('certtable-view', JSON.stringify({ colFilters: true }))
    try {
      api.getCertificatesPaginated.mockResolvedValue(paged([]))

      const { container } = render(<CertificatesTable onRowClick={() => {}} />)

      await waitFor(() => expect(container.querySelector('table[data-slot="table"]')).not.toBeNull())
      expect(container.querySelector('[data-testid="ct-filter-row"]')).not.toBeNull()
      expect(container.querySelector('[data-slot="table-empty-row"]')).not.toBeNull()
    } finally {
      localStorage.removeItem('certtable-view')
    }
  })

  it('boş veri → hiç sertifika satırı çizilmez (boş durum)', async () => {
    api.getCertificatesPaginated.mockResolvedValue(paged([]))

    const { container } = render(<CertificatesTable onRowClick={() => {}} />)

    await waitFor(() => expect(api.getCertificatesPaginated).toHaveBeenCalled())
    await waitFor(() => expect(container.querySelectorAll('tr[data-domain]')).toHaveLength(0))
  })
  // ── Denetim 5. tur, bulgu 20: tablo ile kart AYNI hükmü okur ───────────────
  //
  // Tablo sabit `days >= 0 && days <= 30` kullanıyordu. days<0 (SÜRESİ DOLMUŞ) bu koşula
  // takılmadığı için satır "Uyarı" görünüyordu ve tabloda "Süresi doldu" durumu HİÇ yoktu.

  it('süresi DOLMUŞ sertifika "Süresi doldu" olarak gösterilir (eskiden "Uyarı" görünüyordu)', async () => {
    api.getCertificatesPaginated.mockResolvedValue(paged([
      cert({ domain: 'expired.com', days_remaining: -5, warning: true, status: 'valid', alert_level: 'expired' }),
    ]))
    const { container } = render(<CertificatesTable onRowClick={() => {}} />)
    await screen.findByText('expired.com')

    expect(container.querySelector('tr[data-domain="expired.com"] [data-slot="cert-level"][data-status="critical"]')).not.toBeNull()
    // Satirin KENDI hucresinde yazmali (sutun basligi/filtre metniyle karistirma).
    const row = container.querySelector('tr[data-domain="expired.com"]')
    expect(row.textContent).toMatch(/Süresi doldu|Expired/i)
  })

  it('sunucu hükmü YOKSA satır çökmez; süre bilgisinden makul bir duruma düşer', async () => {
    api.getCertificatesPaginated.mockResolvedValue(paged([
      cert({ domain: 'eski.com', days_remaining: -1, warning: true, status: 'valid' }),   // alert_level YOK
    ]))
    const { container } = render(<CertificatesTable onRowClick={() => {}} />)
    await screen.findByText('eski.com')
    expect(container.querySelector('tr[data-domain="eski.com"] [data-slot="cert-level"][data-status="critical"]')).not.toBeNull()
  })

  it('alan adı süzgeci tuş başına değil, 300 ms sessizlikten sonra TEK istek atar (fetch yarışı yok)', async () => {
    api.getCertificatesPaginated.mockResolvedValue(paged([cert({ domain: 'a.com' })]))
    render(<CertificatesTable onRowClick={() => {}} />)
    await screen.findByText('a.com')
    api.getCertificatesPaginated.mockClear()

    const input = screen.getByPlaceholderText(/domain/i)
    fireEvent.change(input, { target: { value: 'b' } })
    fireEvent.change(input, { target: { value: 'ba' } })
    fireEvent.change(input, { target: { value: 'ban' } })

    await waitFor(() => expect(api.getCertificatesPaginated).toHaveBeenCalledWith(
      expect.objectContaining({ filter_domain: 'ban' })))
    // Ara tuş vuruşları ("b", "ba") sunucuya HİÇ gitmedi.
    expect(api.getCertificatesPaginated.mock.calls.map(c => c[0].filter_domain)).toEqual(['ban'])
  })
})
describe('CertificatesTable — sütun seçici + kayıtlı görünüm (2026-09-12, #10)', () => {
  it('varsayılan 8 sütun; "Takım" açılınca başlık gelir ve tercih localStorage\'a yazılır; yeniden render tercihten okur', async () => {
    try { localStorage.removeItem('certtable-view') } catch { /* yok */ }
    api.getCertificatesPaginated.mockResolvedValue(paged([cert({ domain: 'col.example.com', team_name: 'Takım A', team_id: 1, public_key_algorithm: 'RSA', public_key_size: 2048 })]))
    const { unmount } = render(<CertificatesTable onRowClick={() => {}} />)
    await waitFor(() => expect(document.querySelector('tr[data-domain="col.example.com"]')).toBeTruthy())
    // Veri sütunları (seçim + işlem sütunları hariç): varsayılan 8 — Konu kapalı, Güven açık (2026-09-13),
    // TLS notu açık (2026-10-10)
    const dataCols = () => [...document.querySelectorAll('thead th[data-col]')].filter((th) => !['select', 'actions'].includes(th.dataset.col)).length
    expect(dataCols()).toBe(8)
    expect(document.querySelector('thead th[data-col="grade"]')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /Sütunlar|Columns/ }))
    fireEvent.click(screen.getByLabelText(/^(Takım|Team)$/))
    expect(dataCols()).toBe(9)
    expect(JSON.parse(localStorage.getItem('certtable-view')).cols).toContain('team')
    unmount()
    render(<CertificatesTable onRowClick={() => {}} />)
    await waitFor(() => expect(document.querySelector('tr[data-domain="col.example.com"]')).toBeTruthy())
    expect(dataCols()).toBe(9)
    expect(document.body.textContent).toContain('Takım A')
    try { localStorage.removeItem('certtable-view') } catch { /* yok */ }
  })
})
