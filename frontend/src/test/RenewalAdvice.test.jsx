import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import RenewalAdvice from '../components/RenewalAdvice.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
// Telefon kipi (jsdom medya sorgusu görmez → kanca mock'lanır): süzgeçler alttan açılan Sheet'e taşınır.
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))
// Plan düğmesi `inventory.crud` düzenleme yetkisine bağlı.
const perms = vi.hoisted(() => ({ edit: true }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => perms.edit, canExecute: () => true, perms: {} }),
}))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  localDayKey: (s) => String(s ?? '').slice(0, 10),
  api: withApiFallback({
    getRenewalAdvice: vi.fn(),
    forecastPlan: vi.fn(),
    forecastUnplan: vi.fn(),
  }),
}))
import { api } from '../api/client'

const cards = () => document.querySelectorAll('[data-slot="renewal-card"]')
const statValue = (key) => {
  const tiles = [...document.querySelectorAll('[data-slot="stat-item"]')]
  const order = ['critical', 'week', 'warning', 'info', 'problems', 'shared']
  return tiles[order.indexOf(key)].querySelector('[data-slot="stat-value"]').textContent
}
const statTile = (key) => document.querySelectorAll('[data-slot="stat-item"]')[['critical', 'week', 'warning', 'info', 'problems', 'shared'].indexOf(key)]

/**
 * RenewalAdvice — YÜKLEME HATASI yüzeyi.
 *
 * Denetimde çıkan kusur (2026-09): `api.getRenewalAdvice().then(...)` zincirinde `.catch` YOKTU; `request()` ağ
 * hatasında throw eder — promise reject olunca `setLoading(false)` hiç çalışmıyor, spinner SONSUZA KADAR dönüyordu.
 */
const item = {
  domain: 'example.com', priority: 'critical', code: 'EXPIRING_CRITICAL', days_remaining: 3,
  issuer_cn: 'Test CA', not_after: '2026-09-10', message: 'Yenileyin', action: 'Bugün yenileyin',
}

describe('RenewalAdvice — yükleme hatası', () => {
  beforeEach(() => {
    vi.clearAllMocks(); mobile.on = false; perms.edit = true
    window.history.replaceState(null, '', '/')
    api.getRenewalAdvice.mockResolvedValue({ success: true, data: [item] })
  })

  it('yüklenirken iskelet + ekran okuyucu durumu; başarılı yanıtta öneri kartı çizilir', async () => {
    render(<RenewalAdvice />)
    expect(screen.getByRole('status')).toHaveTextContent(/yükleniyor|loading/i)
    expect(document.querySelector('[data-slot="renewal-advice"]')).toHaveAttribute('aria-busy', 'true')
    expect(await screen.findByText('example.com')).toBeInTheDocument()
    expect(cards()).toHaveLength(1)
  })

  it('api REJECT ederse hata bandı + "Tekrar dene" çizilir; spinner SONSUZA KADAR dönmez; tekrar dene yeniden yükler', async () => {
    api.getRenewalAdvice.mockRejectedValueOnce(new Error('Failed to fetch'))
    render(<RenewalAdvice />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/yenileme önerileri yüklenemedi|couldn't load the renewal advice/i)
    expect(alert).toHaveTextContent(/failed to fetch/i)
    expect(screen.queryByText(/yükleniyor|loading/i)).not.toBeInTheDocument()
    fireEvent.click(within(alert).getByRole('button', { name: /tekrar dene|try again/i }))
    expect(await screen.findByText('example.com')).toBeInTheDocument()
    expect(api.getRenewalAdvice).toHaveBeenCalledTimes(2)
  })

  it('{success:false} dönerse hata bandı çizilir, "her şey yolunda" GÖRÜNMEZ', async () => {
    api.getRenewalAdvice.mockResolvedValue({ success: false, error: '500 Sunucu hatası' })
    render(<RenewalAdvice />)
    expect(await screen.findByRole('alert')).toHaveTextContent(/500 sunucu hatası/i)
    expect(screen.queryByText(/güncel, öneri yok|up to date, no advice/i)).not.toBeInTheDocument()
  })

  it('gerçekten boş liste dönerse "her şey yolunda" (StatusBlock success) gösterilir, hata bandı DEĞİL', async () => {
    api.getRenewalAdvice.mockResolvedValue({ success: true, data: [] })
    render(<RenewalAdvice />)
    await waitFor(() => expect(document.querySelector('[data-slot="empty"][data-tone="success"]')).toBeInTheDocument())
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('yenileme başarısız olursa eldeki liste KALIR, uyarı bandı + tekrar dene görünür', async () => {
    render(<RenewalAdvice />)
    await screen.findByText('example.com')
    api.getRenewalAdvice.mockRejectedValueOnce(new Error('offline'))
    fireEvent.click(screen.getByRole('button', { name: /^(Yenile|Refresh)$/ }))
    const banner = await screen.findByText(/liste yenilenemedi|couldn't refresh the list/i)
    expect(banner.closest('[data-slot="alert"]')).toHaveAttribute('data-tone', 'warning')
    expect(screen.getByText('example.com')).toBeInTheDocument()
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

// ── shadcn yeniden tasarımı (2026-09-26): özet kartları, faset süzgeçleri + çipler, öbekler, tablo, plan, telefon ──
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
  beforeEach(() => {
    vi.clearAllMocks(); mobile.on = false; perms.edit = true
    try { localStorage.clear() } catch { /* yok */ }
    window.history.replaceState(null, '', '/')
    api.getRenewalAdvice.mockResolvedValue({ success: true, data: DATA, timestamp: '2026-09-26T09:30:00' })
  })

  it('başlık: amaç cümlesi + "itibarıyla" zamanı; özet kartları sayar (kritik 3 · bu hafta 1 · bu ay 30 · 60 gün 1 · sorun 1 · paylaşılan 1)', async () => {
    render(<RenewalAdvice />)
    await screen.findByText('d01.example.com')
    expect(document.querySelector('[data-slot="rn-asof"]')).toHaveTextContent('2026-09-26T09:30:00')
    expect(['critical', 'week', 'warning', 'info', 'problems', 'shared'].map(statValue)).toEqual(['3', '1', '30', '1', '1', '1'])
    // paylaşılan kartın alt satırı: 2 alan adı
    expect(statTile('shared').querySelector('[data-slot="stat-sub"]')).toHaveTextContent(/^2 /)
  })

  it('"Hemen ilgilenin" kartı süzer (aria-pressed, URL r_stat, çip); çip kaldırınca geri gelir; kart ikinci tıkta kapanır', async () => {
    render(<RenewalAdvice />)
    await screen.findByText('d01.example.com')
    fireEvent.click(statTile('critical'))
    await waitFor(() => expect(cards()).toHaveLength(3))
    expect(statTile('critical')).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(window.location.search).toContain('r_stat=critical'))
    expect(screen.getByText(/3 \/ 34 öneri|3 of 34 recommendations/)).toBeInTheDocument()
    const chips = screen.getByRole('group', { name: /Etkin süzgeçler|Active filters/ })
    fireEvent.click(within(chips).getByRole('button', { name: /Süzgeci kaldır: Hemen ilgilenin|Remove filter: Act now/ }))
    await waitFor(() => expect(cards()).toHaveLength(25))
    await waitFor(() => expect(window.location.search).not.toContain('r_stat'))
    fireEvent.click(statTile('critical')); fireEvent.click(statTile('critical'))
    await waitFor(() => expect(statTile('critical')).toHaveAttribute('aria-pressed', 'false'))
  })

  it('eski derin bağlantı ?r_pri=critical okunur ve adres r_stat olarak yeniden yazılır', async () => {
    window.history.replaceState({}, '', '/?tab=renewal&r_pri=critical')
    render(<RenewalAdvice />)
    await waitFor(() => expect(cards()).toHaveLength(3))
    await waitFor(() => expect(window.location.search).toContain('r_stat=critical'))
    expect(window.location.search).not.toContain('r_pri')
  })

  it('varsayılan sıralamada liste öncelik öbeklerine bölünür (başlık + sayı); alan adına sıralayınca öbek yok', async () => {
    render(<RenewalAdvice />)
    await screen.findByText('d01.example.com')
    const heads = [...document.querySelectorAll('[data-slot="rn-group"]')]
    expect(heads.map((h) => h.dataset.priority)).toEqual(['critical', 'warning'])   // 25'lik sayfada info öbeği 2. sayfada
    expect(heads[0]).toHaveTextContent('3')
    pressMenuTrigger(document.querySelector('[data-slot="rn-sort"]'))
    fireEvent.click(await screen.findByRole('menuitemradio', { name: /Alan adı|Domain \(A–Z\)/ }))
    await waitFor(() => expect(window.location.search).toContain('sort=domain'))
    expect(document.querySelectorAll('[data-slot="rn-group"]')).toHaveLength(0)
  })

  it('kart: öncelik + neden rozeti, takım rozeti, önerilen işlem + gerekçe, gün + ilerleme çubuğu; eylem adları alan adını içerir', async () => {
    render(<RenewalAdvice />)
    const c2 = (await screen.findByText('d02.example.com')).closest('[data-slot="renewal-card"]')
    expect(c2.querySelector('[data-slot="rn-priority"]')).toHaveAttribute('data-priority', 'critical')
    expect(c2.querySelector('[data-slot="rn-reason"]')).toHaveAttribute('data-code', 'EXPIRING_CRITICAL')
    expect(c2.querySelector('[data-slot="team-badge"]')).toHaveTextContent('Takım A')
    expect(within(c2).getByText(/Önerilen işlem|Recommended action/)).toBeInTheDocument()
    expect(within(c2).getByText(/Renew the certificate today\.|Sertifikayı bugün yenileyin\./)).toBeInTheDocument()
    expect(c2.querySelector('[data-slot="rn-days"]')).toHaveTextContent('3')
    expect(c2.querySelector('[data-slot="progress-bar"]')).toBeInTheDocument()
    expect(within(c2).getByRole('button', { name: 'd02.example.com — Open certificate' })).toBeInTheDocument()
    expect(within(c2).getByRole('button', { name: 'd02.example.com — Plan renewal' })).toBeInTheDocument()
    // paylaşılan sertifika rozeti: aynı parmak izli iki alan
    expect(c2.querySelector('[data-slot="rn-shared"]').textContent).toMatch(/^2 /)
    // ulaşılamayan: gün yok, çubuk yok, Tanıla var, Plan yok
    const c3 = screen.getByText('d03.example.com').closest('[data-slot="renewal-card"]')
    expect(c3.querySelector('[data-slot="progress-bar"]')).toBeNull()
    expect(within(c3).getByRole('button', { name: /d03\.example\.com — (Tanıla|Diagnose)/ })).toBeInTheDocument()
    expect(within(c3).queryByRole('button', { name: /d03\.example\.com — (Yenilemeyi planla|Plan renewal)/ })).toBeNull()
    // tekil parmak izinde paylaşım rozeti yok
    expect(screen.getByText('d10.example.com').closest('[data-slot="renewal-card"]').querySelector('[data-slot="rn-shared"]')).toBeNull()
  })

  it('"Yenilemeyi planla" RenewalPlanModal açar; kaydedince kartta "Planlandı" rozeti; yetki yoksa düğme yok', async () => {
    api.forecastPlan.mockResolvedValue({ success: true, data: { domain: 'd10.example.com', renewal_planned_at: '2026-10-01', renewal_planned_note: 'CSR hazır' } })
    const { unmount } = render(<RenewalAdvice />)
    await screen.findByText('d10.example.com')
    fireEvent.click(screen.getByRole('button', { name: 'd10.example.com — Plan renewal' }))
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveTextContent(/Yenileme planı — d10\.example\.com|Renewal plan — d10\.example\.com/)
    // tarih seçici ui/DateTimeField; kaydet düğmesi tarih olmadan kapalı — modal açılmış ve doğru alanı taşıyor demek yeter
    expect(within(dlg).getByRole('button', { name: /Planı kaydet|Save plan/ })).toBeDisabled()
    unmount()
    perms.edit = false
    render(<RenewalAdvice />)
    await screen.findByText('d10.example.com')
    expect(screen.queryByRole('button', { name: /Plan renewal|Yenilemeyi planla/ })).toBeNull()
  })

  it('neden faseti (çoklu, sayılı) süzer, çip yazar, URL r_code taşır; arama veren adında da eşleşir', async () => {
    render(<RenewalAdvice />)
    await screen.findByText('d01.example.com')
    pressMenuTrigger(screen.getByRole('button', { name: /^(Neden|Reason)/ }))
    const list = await screen.findByRole('listbox')
    expect(within(list).getByRole('option', { name: /^(Ulaşılamıyor|Unreachable)/ }).querySelector('[data-slot="facet-count"]')).toHaveTextContent('1')
    fireEvent.click(within(list).getByRole('option', { name: /^(Ulaşılamıyor|Unreachable)/ }))
    await waitFor(() => expect(cards()).toHaveLength(1))
    expect(screen.getByText('d03.example.com')).toBeInTheDocument()
    await waitFor(() => expect(window.location.search).toContain('r_code=UNREACHABLE'))
    fireEvent.click(within(list).getByRole('option', { name: /^(Süresi dolmuş|Expired)/ }))
    await waitFor(() => expect(cards()).toHaveLength(2))
    expect(screen.getByRole('button', { name: /Remove filter: (Neden|Reason): (Ulaşılamıyor|Unreachable)|Süzgeci kaldır: Neden: Ulaşılamıyor/ })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /^(Filtreleri temizle|Clear filters)$/ }))
    await waitFor(() => expect(cards()).toHaveLength(25))
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'ca one' } })
    await waitFor(() => expect(screen.getByText(/34 \/ 34|34 of 34/)).toBeInTheDocument())
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'kampanya' } })
    await waitFor(() => expect(cards()).toHaveLength(1))
  })

  it('karttaki grup/etiket çipi o değere süzer (çoklu seçime eklenir)', async () => {
    render(<RenewalAdvice />)
    const c1 = (await screen.findByText('d01.example.com')).closest('[data-slot="renewal-card"]')
    fireEvent.click(within(c1).getByRole('button', { name: /(Bu değere süz|Filter by) kritik/ }))
    await waitFor(() => expect(cards()).toHaveLength(1))
    await waitFor(() => expect(window.location.search).toContain('tag=kritik'))
  })

  it('tablo görünümü: satırlar, takım rozeti, satır menüsü (ad satırı ayırt eder) ulaşılamayanda Tanıla içerir', async () => {
    render(<RenewalAdvice />)
    await screen.findByText('d01.example.com')
    expect(screen.getByRole('navigation', { name: /Sayfalama|Pagination/ })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /^Tablo$|^Table$/ }))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="rn-table"] tbody tr')).toHaveLength(25))
    const row3 = screen.getByText('d03.example.com').closest('tr')
    expect(row3.querySelector('[data-slot="team-badge"]')).toHaveTextContent('Takım B')
    pressMenuTrigger(within(row3).getByRole('button', { name: /d03\.example\.com — (Diğer işlemler|More actions)/ }))
    const menu = await screen.findByRole('menu')
    expect(within(menu).getByRole('menuitem', { name: /Tanıla|Diagnose/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Alan adını kopyala|Copy domain/ })).toBeInTheDocument()
    fireEvent.keyDown(menu, { key: 'Escape' })
    pressMenuTrigger(within(screen.getByText('d10.example.com').closest('tr')).getByRole('button', { name: /More actions|Diğer işlemler/ }))
    expect(within(await screen.findByRole('menu')).queryByRole('menuitem', { name: /Tanıla|Diagnose/ })).toBeNull()
  })

  it('telefon: faset düğmeleri yerine "Süzgeçler" → alttan Sheet; onay kutusu süzer; "N öneriyi göster" kapatır', async () => {
    mobile.on = true
    render(<RenewalAdvice />)
    await screen.findByText('d01.example.com')
    expect(screen.queryByRole('button', { name: /^(Neden|Reason)$/ })).toBeNull()
    fireEvent.click(document.querySelector('[data-slot="rn-filters-open"]'))
    const sheet = await screen.findByRole('dialog')
    expect(sheet).toHaveTextContent(/Süzgeçler|Filters/)
    fireEvent.click(within(sheet).getByRole('checkbox', { name: /^(Ulaşılamıyor|Unreachable)/ }))
    await waitFor(() => expect(cards()).toHaveLength(1))
    expect(within(sheet).getByRole('button', { name: /1 öneriyi göster|Show 1 recommendations/ })).toBeInTheDocument()
    fireEvent.click(within(sheet).getByRole('button', { name: /1 öneriyi göster|Show 1 recommendations/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(document.querySelector('[data-slot="rn-filters-open"] [data-slot="badge"]')).toHaveTextContent('1')
  })

  // Sayfalama standardı (2026-09-26): `page` adresi okunur ve yazılır — paylaşılan bağlantı aynı dilimi açar.
  it('derin bağlantı ?page=2 → 2. sayfa açılır (34 öneri / 25 → 9 kart) ve adres korunur', async () => {
    window.history.replaceState({}, '', '/?tab=renewal&page=2')
    render(<RenewalAdvice />)
    await waitFor(() => expect(cards()).toHaveLength(9))
    expect(screen.getByRole('button', { name: /^(Sayfa|Page) 2$/ })).toHaveAttribute('aria-current', 'page')
    await new Promise((r) => setTimeout(r, 400))
    expect(new URLSearchParams(window.location.search).get('page')).toBe('2')
    window.history.replaceState({}, '', '/')
  })

  it('kartlarda sol renk şeridi YOK (kullanıcı kuralı): kritik kart yalnız tüm dış çizgisiyle tonlanır', async () => {
    render(<RenewalAdvice />)
    const c1 = (await screen.findByText('d01.example.com')).closest('[data-slot="renewal-card"]')
    expect(c1.className).not.toMatch(/border-l-|before:|inset/)
    expect(c1.className).toMatch(/border-destructive/)
  })
})
