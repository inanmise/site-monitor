import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import RenewalAdvice from '../components/RenewalAdvice.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateOnly: (s) => s ?? '',
  localDayKey: (s) => String(s ?? '').slice(0, 10),
  api: withApiFallback({
    getRenewalAdvice: vi.fn(),
  }),
}))
import { api } from '../api/client'

/**
 * RenewalAdvice — YÜKLEME HATASI yüzeyi.
 *
 * Bu bileşenin hiç test dosyası yoktu. Denetimde çıkan kusur: `api.getRenewalAdvice().then(...)`
 * zincirinde `.catch` YOKTU ve çağrıya `timeoutMs` da verilmiyordu (varsayılan 0 = timeout yok).
 * `request()` ağ hatasında `{success:false}` DÖNDÜRMEZ, `throw` eder — dolayısıyla promise
 * reject olunca `setLoading(false)` hiç çalışmıyor, "Yenileme Önerileri" sekmesinde spinner
 * SONSUZA KADAR dönüyordu. Hata mesajı yoktu ve sekme değiştirip geri gelmeden düzelmiyordu.
 */
const item = {
  domain: 'example.com', priority: 'critical', days_remaining: 3,
  issuer: 'Test CA', not_after: '2026-09-10', advice: 'Yenileyin',
}

describe('RenewalAdvice — yükleme hatası', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.getRenewalAdvice.mockResolvedValue({ success: true, data: [item] })
  })

  it('başarılı yanıtta öneri kartını çizer (temel akış korunuyor)', async () => {
    render(<RenewalAdvice />)
    await waitFor(() => expect(api.getRenewalAdvice).toHaveBeenCalled())
    expect(await screen.findByText('example.com')).toBeInTheDocument()
  })

  it('api REJECT ederse hata bandı çizilir; spinner SONSUZA KADAR dönmez', async () => {
    api.getRenewalAdvice.mockRejectedValue(new Error('Failed to fetch'))

    render(<RenewalAdvice />)

    expect(await screen.findByText(/izleme listesi yüklenemedi|could not load the monitor list/i))
      .toBeInTheDocument()
    expect(screen.getByText(/failed to fetch/i)).toBeInTheDocument()
    // Spinner metni kalkmalı — kalıcı yükleme durumu bu hatanın ta kendisiydi.
    expect(screen.queryByText(/yükleniyor|loading/i)).not.toBeInTheDocument()
  })

  it('{success:false} dönerse hata bandı çizilir, "her şey yolunda" GÖRÜNMEZ', async () => {
    // Hata bandı boş durumun ÖNÜNDE olmalı: aksi halde yükleme hatası
    // "yenilenecek sertifika yok" gibi okunur ve kullanıcı yanlış rahatlar.
    api.getRenewalAdvice.mockResolvedValue({ success: false, error: '500 Sunucu hatası' })

    render(<RenewalAdvice />)

    expect(await screen.findByText(/izleme listesi yüklenemedi|could not load the monitor list/i))
      .toBeInTheDocument()
    expect(screen.getByText(/500 sunucu hatası/i)).toBeInTheDocument()
  })

  it('gerçekten boş liste dönerse "her şey yolunda" gösterilir (hata bandı DEĞİL)', async () => {
    api.getRenewalAdvice.mockResolvedValue({ success: true, data: [] })

    render(<RenewalAdvice />)

    await waitFor(() => expect(api.getRenewalAdvice).toHaveBeenCalled())
    expect(screen.queryByText(/izleme listesi yüklenemedi|could not load the monitor list/i))
      .not.toBeInTheDocument()
  })
})

/** QA 2026-09-12 ISSUE-002: sunucu mesajı yalnız Türkçe; kart `code` + gün sayısıyla arayüz dilinde çevrilir. */
describe('RenewalAdvice — mesaj/eylem arayüz dilinde', () => {
  it('EXPIRING_INFO kodu 41 gün: İngilizce arayüzde İngilizce metin; bilinmeyen kodda sunucu metni kalır', async () => {
    api.getRenewalAdvice.mockResolvedValue({ success: true, data: [
      { domain: 'a.example.com', code: 'EXPIRING_INFO', priority: 'info', days_remaining: 41, not_after: '2026-10-23T23:59:59', message: 'Sertifika 41 gün içinde bitiyor.', action: 'Sertifika yenileme takviminizi güncelleyin.' },
      { domain: 'b.example.com', code: 'SOMETHING_NEW', priority: 'info', days_remaining: 5, not_after: '2026-10-23T23:59:59', message: 'sunucu metni', action: 'sunucu eylemi' },
    ] })
    render(<RenewalAdvice />)
    expect(await screen.findByText(/Certificate expires in 41 days\.|Sertifika 41 gün içinde bitiyor\./)).toBeInTheDocument()
    expect(screen.getByText(/Update your renewal calendar\.|Sertifika yenileme takviminizi güncelleyin\./)).toBeInTheDocument()
    expect(screen.getByText('sunucu metni')).toBeInTheDocument()
    expect(screen.getByText('sunucu eylemi')).toBeInTheDocument()
  })
})

// ── Yeniden tasarım (2026-09-18): özet şeridi, süzgeçler, tablo görünümü, paylaşılan sertifika, sayfalama ──
describe('RenewalAdvice — yeniden tasarım', () => {
  const mk = (i, extra = {}) => ({
    domain: `d${String(i).padStart(2, '0')}.example.com`, code: 'EXPIRING_WARNING', priority: 'warning', days_remaining: 10 + i,
    not_after: '2026-10-10T00:00:00', team_id: 1, team_name: 'Takım A', tier: 2, group_name: 'Ödeme', tags: 'prod', issuer_cn: 'CA One', fingerprint: 'F' + i, ...extra,
  })
  const DATA = [
    mk(1, { code: 'EXPIRED', priority: 'critical', days_remaining: -2, fingerprint: 'SHARED', tags: 'prod, kritik' }),
    mk(2, { code: 'EXPIRING_CRITICAL', priority: 'critical', days_remaining: 3, fingerprint: 'SHARED' }),
    mk(3, { code: 'UNREACHABLE', priority: 'critical', days_remaining: null, team_id: 9, team_name: 'Takım B', group_name: 'Kampanya', tags: 'edge' }),
    mk(4, { code: 'EXPIRING_INFO', priority: 'info', days_remaining: 45 }),
    ...Array.from({ length: 30 }, (_, k) => mk(10 + k)),
  ]
  beforeEach(() => { vi.clearAllMocks(); try { localStorage.clear() } catch { /* yok */ }; window.history.replaceState(null, '', '/'); api.getRenewalAdvice.mockResolvedValue({ success: true, data: DATA }) })

  it('özet şeridi sayar; KRİTİK kartına tıklayınca liste süzülür ve URL r_pri taşır; "Filtreleri temizle" geri alır', async () => {
    render(<RenewalAdvice />)
    await screen.findByText('d01.example.com')
    const kpi = (k) => document.querySelector(`[data-kpi="${k}"] .rn-kpi-num`).textContent
    expect([kpi('critical'), kpi('warning'), kpi('info')]).toEqual(['3', '30', '1'])
    fireEvent.click(document.querySelector('[data-kpi="critical"]'))
    await waitFor(() => expect(document.querySelectorAll('.renewal-card')).toHaveLength(3))
    await waitFor(() => expect(window.location.search).toContain('r_pri=critical'))
    expect(screen.getByText(/3 \/ 34 öneri|3 of 34 recommendations/)).toBeInTheDocument()
    fireEvent.click(screen.getByText(/Filtreleri temizle|Clear filters/))
    await waitFor(() => expect(window.location.search).not.toContain('r_pri'))
  })

  it('paylaşılan sertifika rozeti: aynı parmak izli iki alan "2 alan … paylaşıyor" der; tekil olanda rozet yok', async () => {
    render(<RenewalAdvice />)
    await screen.findByText('d01.example.com')
    const c1 = screen.getByText('d01.example.com').closest('.renewal-card')
    expect(c1.querySelector('.rn-shared').textContent).toMatch(/^2 /)
    const c10 = screen.getByText('d10.example.com').closest('.renewal-card')   // tekil parmak izi, ilk sayfada
    expect(c10.querySelector('.rn-shared')).toBeNull()
  })

  it('sayfalama: 34 öneri → 25 kart + sayfalama çubuğu; tablo görünümünde satırlar ve takım rozeti; ulaşılamayanda Tanıla', async () => {
    render(<RenewalAdvice />)
    await screen.findByText('d01.example.com')
    expect(document.querySelectorAll('.renewal-card')).toHaveLength(25)
    expect(screen.getByRole('navigation', { name: /Sayfalama|Pagination/ })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /^Tablo$|^Table$/ }))
    await waitFor(() => expect(document.querySelectorAll('.rn-table tbody tr')).toHaveLength(25))
    const row3 = screen.getByText('d03.example.com').closest('tr')
    expect(row3.querySelector('[data-slot="team-badge"]').textContent).toContain('Takım B')
    expect(within(row3).getByRole('button', { name: /Tanıla|Diagnose/ })).toBeInTheDocument()
    expect(within(screen.getByText('d10.example.com').closest('tr')).queryByRole('button', { name: /Tanıla|Diagnose/ })).toBeNull()
  })

  it('neden / takım / etiket süzgeçleri ve arama (veren adında da eşleşir)', async () => {
    render(<RenewalAdvice />)
    await screen.findByText('d01.example.com')
    const bar = document.querySelector('.rn-toolbar')
    const open = (re) => fireEvent.mouseDown([...bar.querySelectorAll('button[role="combobox"]')].find((b) => re.test(b.textContent)))
    const pick = (re) => fireEvent.mouseDown([...document.querySelectorAll('[role="option"]')].find((o) => re.test(o.textContent)))
    open(/Tüm nedenler|All reasons/); pick(/Ulaşılamıyor|Unreachable/)
    await waitFor(() => expect(document.querySelectorAll('.renewal-card')).toHaveLength(1))
    expect(screen.getByText('d03.example.com')).toBeInTheDocument()
    fireEvent.click(screen.getByText(/Filtreleri temizle|Clear filters/))
    open(/Tüm etiketler|All tags/); pick(/^kritik$/)
    await waitFor(() => expect(document.querySelectorAll('.renewal-card')).toHaveLength(1))
    expect(screen.getByText('d01.example.com')).toBeInTheDocument()
    fireEvent.click(screen.getByText(/Filtreleri temizle|Clear filters/))
    fireEvent.change(bar.querySelector('.rn-search'), { target: { value: 'ca one' } })
    await waitFor(() => expect(screen.getByText(/34 \/ 34|34 of 34/)).toBeInTheDocument())
  })
})
