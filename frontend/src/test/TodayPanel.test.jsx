import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({ me: { today: vi.fn() }, admin: { getTeams: vi.fn().mockResolvedValue({ success: true, data: [] }) } }),
}))
import { api } from '../api/client'
import TodayPanel from '../components/TodayPanel.jsx'

const EMPTY_MON = { flapping: { count: 0, items: [] }, slow: { count: 0, items: [] }, stale: { count: 0, paused: 0, items: [] }, domains: { count: 0, expired: 0, items: [] },
  notifications: { count: 0, email: 0, webhook: 0, push: 0, items: [] }, health: { count: 0, critical: 0, items: [] } }

/** "Sizin için — bugün" (2026-09-12, #3): kartlar, bağlantılar doğru sekmeye; hepsi sıfırsa yeşil tek satır.
 *  2026-09-19: istisna kartı kalktı; dört izleme kartı (kararsız / yavaşlayan / sessiz / alan adı kaydı) geldi. */
describe('TodayPanel', () => {
  beforeEach(() => { vi.clearAllMocks(); try { localStorage.clear() } catch { /* yok */ } })

  it('kartlar: sayılar, 30 gün altı alan tıklanınca onOpenDomain; "Tümünü gör" sm:navigate ile doğru sekmeye', async () => {
    api.me.today.mockResolvedValue({ success: true, data: {
      certs: { count: 2, expired: 1, items: [{ domain: 'exp.example.com', days: -3, team_id: 1, team_name: 'Takım A' }, { domain: 'soon.example.com', days: 12 }] },
      alerts: { count: 1, critical: 1, items: [{ id: 9, domain: 'down.example.com', type: 'HTTP_DOWN', level: 'CRITICAL', acknowledged: false }] },
      ...EMPTY_MON,
      weekly: { year: 2026, week: 37, count: 1, missing: 1, items: [{ team_id: 1, team_name: 'Takım A', status: 'DRAFT' }] },
    } })
    const onOpen = vi.fn(); const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    render(<TodayPanel onOpenDomain={onOpen} />)
    await screen.findByText(/4 konu ilgi bekliyor|4 items need attention/)
    // Varsayılan KAPALI: özet satırı görünür, kartlar açılınca gelir
    expect(document.querySelector('.today-grid')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Sizin için|For you/ }))
    expect(screen.getByText(/1 tanesi DOLMUŞ|1 already EXPIRED/)).toBeInTheDocument()
    expect(screen.getByText(/1 tanesi KRİTİK|1 CRITICAL/)).toBeInTheDocument()
    expect(screen.getByText(/^taslak$|^draft$/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'exp.example.com' }))
    expect(onOpen).toHaveBeenCalledWith('exp.example.com')
    // "Tümünü gör" artık POP-UP açar (2026-09-18): tam liste full=true ile çekilir; "Sayfaya git" eski geçişi yapar
    const goButtons = screen.getAllByRole('button', { name: /Tümünü gör|See all/ })
    fireEvent.click(goButtons[0])
    const modal = await screen.findByRole('dialog')
    await waitFor(() => expect(api.me.today).toHaveBeenLastCalledWith({ full: true }))
    expect(modal.textContent).toMatch(/\(2\)/)                       // başlıkta sayı
    expect(modal.querySelectorAll('.today-modal-row')).toHaveLength(2)
    expect(nav).not.toHaveBeenCalled()
    fireEvent.click(within(modal).getByRole('button', { name: /Sayfaya git|Open the page/ }))
    expect(nav.mock.calls[0][0].detail.tab).toBe('renewal')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    // Card satır içi bileşen → her render'da yeniden kurulur; eski düğme referansı kopuk, yeniden sorgula
    fireEvent.click(screen.getAllByRole('button', { name: /Tümünü gör|See all/ })[2])   // certs, alerts, weekly (izleme kartları 0 → düğme yok)
    const m2 = await screen.findByRole('dialog')
    fireEvent.click(within(m2).getByRole('button', { name: /Sayfaya git|Open the page/ }))
    expect(nav.mock.calls[1][0].detail.tab).toBe('weeklyreports')
    window.removeEventListener('sm:navigate', nav)
  })

  it('pop-up SAYFALI: 23 sertifika → 10 satır + sayfalama; arama kutusu daraltır; satıra tıklamak onOpenDomain', async () => {
    const many = Array.from({ length: 23 }, (_, i) => ({ domain: `c${String(i).padStart(2, '0')}.example.com`, days: i, team_id: 1, team_name: 'Takım A' }))
    api.me.today.mockImplementation((opts) => Promise.resolve({ success: true, data: {
      certs: { count: 23, expired: 0, items: opts?.full ? many : many.slice(0, 5) },
      alerts: { count: 0, items: [] }, ...EMPTY_MON, weekly: { count: 0, missing: 0, items: [] },
    } }))
    const onOpen = vi.fn()
    render(<TodayPanel onOpenDomain={onOpen} />)
    await screen.findByText(/23 konu ilgi bekliyor|23 items need attention/)
    fireEvent.click(screen.getByRole('button', { name: /Sizin için|For you/ }))
    expect(document.querySelectorAll('.today-card .today-list li')).toHaveLength(5)   // kartta yalnız 5
    fireEvent.click(screen.getByRole('button', { name: /Tümünü gör|See all/ }))
    const modal = await screen.findByRole('dialog')
    await waitFor(() => expect(modal.querySelectorAll('.today-modal-row')).toHaveLength(10))
    expect(within(modal).getByRole('navigation', { name: /Sayfalama|Pagination/ })).toBeInTheDocument()
    expect(modal.textContent).toMatch(/1[–-]10 (\/|of) 23/)
    fireEvent.change(modal.querySelector('input[type=text]'), { target: { value: 'c2' } })
    await waitFor(() => expect(modal.querySelectorAll('.today-modal-row')).toHaveLength(3))   // c20, c21, c22
    fireEvent.click(within(modal).getByRole('button', { name: 'c21.example.com' }))
    expect(onOpen).toHaveBeenCalledWith('c21.example.com')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('hepsi sıfır → yeşil "ilgilenilecek bir şey yok", kart yok; başlık katlanır ve tercih saklanır', async () => {
    api.me.today.mockResolvedValue({ success: true, data: {
      certs: { count: 0, items: [] }, alerts: { count: 0, items: [] }, ...EMPTY_MON, weekly: { count: 1, missing: 0, week: 37, items: [] },
    } })
    render(<TodayPanel />)
    await screen.findByText(/Bugün ilgilenilecek bir şey yok|Nothing needs attention today/)
    expect(document.querySelector('.today-grid')).toBeNull()
    const head = screen.getByRole('button', { name: /Sizin için|For you/ })
    expect(head).toHaveAttribute('aria-expanded', 'false')   // varsayılan kapalı
    fireEvent.click(head)
    expect(head).toHaveAttribute('aria-expanded', 'true')
    expect(localStorage.getItem('today-panel-open')).toBe('true')
    expect(document.querySelector('.today-ok')).toBeNull()   // hepsi temizken "Sorun yok" şeridi tekrar olurdu
  })

  it('2026-09-19 izleme kartları: kararsız/yavaşlayan/sessiz/alan adı satırları; satır tıklaması izlemenin sekmesine ?monitor=; pop-up aynı satırı çizer; "Sayfaya git" bölüm sekmesine', async () => {
    const data = {
      certs: { count: 0, items: [] }, alerts: { count: 0, items: [] }, weekly: { count: 0, missing: 0, items: [] },
      flapping: { count: 1, items: [{ type: 'HTTP', monitor_id: 11, name: 'api', target: 'https://api.example.com/health', transitions: 4, last_status: 'DOWN', team_id: 1, team_name: 'Takım A' }] },
      slow: { count: 1, items: [{ type: 'PING', monitor_id: 12, name: 'gw', target: '10.0.0.1', today_ms: 300, baseline_ms: 100, ratio: 3 }] },
      stale: { count: 3, paused: 2, items: [
        { type: 'PORT', monitor_id: 13, name: 'smtp', target: 'mail.example.com:25', last_check: '2026-09-19T08:00:00', age_min: 90, expected_min: 10 },
        { type: 'SCRIPTED', monitor_id: 14, name: 'login-flow', target: null, last_check: null, never: true, age_min: 600, expected_min: 10 },
        { type: 'KEYWORD', monitor_id: 16, name: 'kw', target: 'https://kw.example.com', last_check: null, never: false, age_min: 10080, expected_min: 10 }] },
      domains: { count: 1, expired: 1, items: [{ type: 'DOMAIN', monitor_id: 15, name: 'd', domain: 'exp.example.com', days: -2, registrar: 'Registrar X' }] },
    }
    api.me.today.mockResolvedValue({ success: true, data })
    const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    try { localStorage.setItem('today-panel-open', 'true') } catch { /* yok */ }
    render(<TodayPanel />)
    await screen.findByText(/6 konu ilgi bekliyor|6 items need attention/)
    expect(screen.getByText(/4 geçiş · şu an DOWN|4 changes · currently DOWN/)).toBeInTheDocument()
    expect(screen.getByText(/300 ms · taban 100 ms · ×3|300 ms · baseline 100 ms · ×3/)).toBeInTheDocument()
    expect(screen.getByText(/90 dk önce · beklenen ≤10 dk|90 min ago · expected ≤10 min/)).toBeInTheDocument()
    expect(screen.getByText(/hiç kontrol edilmedi|never checked/)).toBeInTheDocument()
    expect(screen.getByText(/7\+ gündür kontrol yok|no check for 7\+ days/)).toBeInTheDocument()
    // 2026-09-23: duraklatılmış sayısı sessiz kartından "Susturulmuş ve bakımda" kartına taşındı (orada satır satır)
    expect(screen.queryByText(/izleme duraklatılmış|Paused monitors/)).toBeNull()
    expect(screen.getByText(/1 tanesi DOLMUŞ|1 already EXPIRED/)).toBeInTheDocument()
    expect(screen.getByText('Registrar X')).toBeInTheDocument()
    // satır → izlemenin sekmesi + ?monitor=; Sentetik'te derin bağlantı yok
    fireEvent.click(screen.getByRole('button', { name: 'api' }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'http', params: { monitor: 11 } })
    fireEvent.click(screen.getByRole('button', { name: 'login-flow' }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'scripted', params: undefined })
    fireEvent.click(screen.getByRole('button', { name: 'exp.example.com' }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'domain', params: { monitor: 15 } })
    // pop-up: sessiz kartı → aynı satır bileşeni, "Sayfaya git" → sistem sağlığı
    fireEvent.click(screen.getAllByRole('button', { name: /Tümünü gör|See all/ })[2])
    const modal = await screen.findByRole('dialog')
    await waitFor(() => expect(modal.querySelectorAll('.today-modal-row')).toHaveLength(3))
    expect(within(modal).getByText(/hiç kontrol edilmedi|never checked/)).toBeInTheDocument()
    fireEvent.click(within(modal).getByRole('button', { name: /Sayfaya git|Open the page/ }))
    expect(nav.mock.calls.at(-1)[0].detail.tab).toBe('health')
    window.removeEventListener('sm:navigate', nav)
  })

  it('2026-09-19 ikinci tur: teslim edilemeyen bildirim (kanal rozeti, hata, olay → Alarm Geçmişi) ve sağlık bulgusu (rozetler, kritik kırmızı, istisnalı; alan → onOpenDomain)', async () => {
    const data = {
      certs: { count: 0, items: [] }, alerts: { count: 0, items: [] }, weekly: { count: 0, missing: 0, items: [] }, ...EMPTY_MON,
      notifications: { count: 2, email: 1, webhook: 0, push: 1, items: [
        { channel: 'EMAIL', target: 'ops@example.com', error: '550 mailbox unavailable', at: '2026-09-19T10:00:00', domain: 'a.example.com', monitor_name: 'HTTP_DOWN', alert_event_id: 41, team_id: 1, team_name: 'Takım A' },
        { channel: 'PUSH', target: 'Kullanıcı Bir', error: 'HTTP 500', at: '2026-09-19T09:30:00', domain: null, monitor_name: 'api', alert_event_id: null, team_id: 1 }] },
      health: { count: 2, critical: 1, items: [
        { domain: 'b.example.com', team_id: 1, team_name: 'Takım A', critical: true, silenced: true, findings: [{ key: 'chain', value_key: 'broken', value_args: [] }, { key: 'protocol', value_key: 'outdatedProtocol', value_args: ['TLSv1'] }] },
        { domain: 'c.example.com', team_id: 1, critical: false, silenced: false, findings: [{ key: 'signature', value_key: 'weakAlgorithm', value_args: ['SHA1withRSA'] }] }] },
    }
    api.me.today.mockResolvedValue({ success: true, data })
    const onOpen = vi.fn(); const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    try { localStorage.setItem('today-panel-open', 'true') } catch { /* yok */ }
    render(<TodayPanel onOpenDomain={onOpen} />)
    await screen.findByText(/4 konu ilgi bekliyor|4 items need attention/)
    expect(screen.getByText(/e-posta 1 · webhook 0 · push 1|email 1 · webhook 0 · push 1/)).toBeInTheDocument()
    expect(screen.getByText('550 mailbox unavailable')).toBeInTheDocument()
    expect(screen.getByText(/1 tanesi KRİTİK \(güven|1 CRITICAL \(trust/)).toBeInTheDocument()
    const chain = screen.getByText(/^Zincir kırık$|^Broken chain$/)
    expect(chain.className).toContain('is-bad')                                   // kritik anahtar kırmızı
    expect(chain).toHaveAttribute('title', expect.stringMatching(/Kırık|Broken/))   // hlth.val.broken
    expect(screen.getByText(/^Eski TLS$|^Outdated TLS$/).className).not.toContain('is-bad')
    expect(screen.getByText(/^Eski TLS$|^Outdated TLS$/)).toHaveAttribute('title', expect.stringMatching(/TLSv1/))
    expect(screen.getByText(/^istisnalı$|^exception$/)).toBeInTheDocument()
    // bildirim satırı → Alarm Geçmişi + incident; olaysız push → yalnız sekme
    fireEvent.click(screen.getByRole('button', { name: 'a.example.com' }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'alerthistory', params: { incident: 41 } })
    fireEvent.click(screen.getByRole('button', { name: 'api' }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'alerthistory', params: undefined })
    // sağlık satırı → sertifika detayı (onOpenDomain)
    fireEvent.click(screen.getByRole('button', { name: 'b.example.com' }))
    expect(onOpen).toHaveBeenCalledWith('b.example.com')
    // pop-up: sağlık → aynı satır, "Sayfaya git" → Zayıf Algoritma Raporu
    fireEvent.click(screen.getAllByRole('button', { name: /Tümünü gör|See all/ })[1])
    const modal = await screen.findByRole('dialog')
    await waitFor(() => expect(modal.querySelectorAll('.today-modal-row')).toHaveLength(2))
    fireEvent.click(within(modal).getByRole('button', { name: /Sayfaya git|Open the page/ }))
    expect(nav.mock.calls.at(-1)[0].detail.tab).toBe('weakalgo')
    window.removeEventListener('sm:navigate', nav)
  })

  it('2026-09-23 susturulmuş ve bakımda: süren bakım / duraklatılmış (yaklaşık ~, 7+ gün uyarı) / dolacak istisna; satır tıklamaları doğru sayfaya; pop-up aynı satırı çizer', async () => {
    const until = new Date(Date.now() + 30 * 60_000).toISOString().slice(0, 19)   // sunucu biçimi: UTC, Z'siz
    const data = {
      certs: { count: 0, items: [] }, alerts: { count: 0, items: [] }, weekly: { count: 0, missing: 0, items: [] }, ...EMPTY_MON,
      quiet: { count: 4, maint_active: 1, maint_soon: 0, paused: 2, paused_long: 1, exceptions: 1, items: [
        { kind: 'MAINT_ACTIVE', window_id: 5, name: 'Gece bakımı', until, target_count: -1, team_id: null, public: true },
        { kind: 'PAUSED', type: 'HTTP', monitor_id: 21, name: 'eski-api', target: 'https://old.example.com', paused_days: 10, paused_since_exact: false, team_id: 1, team_name: 'Takım A' },
        { kind: 'PAUSED', type: 'PING', monitor_id: 22, name: 'gw2', target: '10.0.0.2', paused_days: 0, paused_since_exact: true },
        { kind: 'EXCEPTION', domain: 'weak.example.com', until: '2026-09-24', days_left: 1, reason: 'Tedarikçi yenileyecek' }] },
    }
    api.me.today.mockResolvedValue({ success: true, data })
    const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    try { localStorage.setItem('today-panel-open', 'true') } catch { /* yok */ }
    render(<TodayPanel />)
    await screen.findByText(/4 konu ilgi bekliyor|4 items need attention/)
    const card = screen.getByText(/^Susturulmuş ve bakımda$|^Muted and under maintenance$/).closest('.today-card')
    expect(card.className).toContain('today-card--warn')   // 7+ gündür duraklatılmış + dolacak istisna
    expect(within(card).getByText(/1 bakım sürüyor · 2 izleme duraklatılmış \(1 tanesi 7\+ gündür\) · 1 istisnanın süresi doluyor|Maintenance in progress: 1 · Paused monitors: 2 \(1 for over a week\) · Exceptions expiring: 1/)).toBeInTheDocument()
    expect(within(card).getByText(/^BAKIMDA$|^MAINTENANCE$/)).toBeInTheDocument()
    expect(within(card).getByText(/tüm izlemeler|all monitors/)).toBeInTheDocument()
    const approx = within(card).getByText(/~10 gündür duraklatılmış|~paused for 10 days/)
    expect(approx.className).toContain('is-warn')
    expect(approx).toHaveAttribute('title', expect.stringMatching(/Yaklaşık|Approximate/))
    expect(within(card).getByText(/^bugün duraklatıldı$|^paused today$/)).not.toHaveAttribute('title')   // kesin tarih → ~ yok
    expect(within(card).getByText(/yarın doluyor|expires tomorrow/)).toBeInTheDocument()
    // satırlar: bakım → Bakım sayfası; duraklatılmış → izlemenin kendisi; istisna → Zayıf Algoritma
    fireEvent.click(within(card).getByRole('button', { name: 'Gece bakımı' }))
    expect(nav.mock.calls.at(-1)[0].detail.tab).toBe('maintenance')
    fireEvent.click(screen.getByRole('button', { name: 'eski-api' }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'http', params: { monitor: 21 } })
    fireEvent.click(screen.getByRole('button', { name: 'weak.example.com' }))
    expect(nav.mock.calls.at(-1)[0].detail.tab).toBe('weakalgo')
    // pop-up: dört satır aynı bileşenle (anahtarlar çakışmaz), "Sayfaya git" → bakım sayfası (bakım varken)
    fireEvent.click(within(screen.getByText(/^Susturulmuş ve bakımda$|^Muted and under maintenance$/).closest('.today-card'))
      .getByRole('button', { name: /Tümünü gör|See all/ }))
    const modal = await screen.findByRole('dialog')
    await waitFor(() => expect(modal.querySelectorAll('.today-modal-row')).toHaveLength(4))
    fireEvent.click(within(modal).getByRole('button', { name: /Sayfaya git|Open the page/ }))
    expect(nav.mock.calls.at(-1)[0].detail.tab).toBe('maintenance')
    window.removeEventListener('sm:navigate', nav)
  })

  it('2026-09-23 dünden bugüne: prev gelen kartta ▲ artış (kötü) / ▼ azalış (iyi) + açıklayıcı başlık; prev yoksa ya da fark 0 ise gösterge yok; son 24 saat şeridi', async () => {
    api.me.today.mockResolvedValue({ success: true, data: {
      certs: { count: 3, expired: 0, prev: 1, items: [{ domain: 'a.example.com', days: 10 }, { domain: 'b.example.com', days: 11 }, { domain: 'c.example.com', days: 12 }] },
      alerts: { count: 0, critical: 0, prev: 2, items: [] },
      ...EMPTY_MON,
      quiet: { count: 1, maint_active: 0, maint_soon: 0, paused: 1, paused_long: 0, exceptions: 0, prev: 1,
        items: [{ kind: 'PAUSED', type: 'PING', monitor_id: 3, name: 'gw', paused_days: 2, paused_since_exact: true }] },
      weekly: { count: 0, missing: 0, items: [] },
      recent: { opened: 4, resolved: 6, renewed: 0, hours: 24 },
      trend_at: '2026-09-22T09:00:00',
    } })
    try { localStorage.setItem('today-panel-open', 'true') } catch { /* yok */ }
    render(<TodayPanel />)
    await screen.findByText(/4 konu ilgi bekliyor|4 items need attention/)
    const up = screen.getByLabelText(/Dün bu saate göre 2 arttı \(dün: 1\)|Up 2 on this time yesterday \(was 1\)/)
    expect(up.textContent).toBe('▲2')
    expect(up.className).toContain('is-worse')
    const down = screen.getByLabelText(/Dün bu saate göre 2 azaldı \(dün: 2\)|Down 2 on this time yesterday \(was 2\)/)
    expect(down.textContent).toBe('▼2')
    expect(down.className).toContain('is-better')
    expect(document.querySelectorAll('.today-delta')).toHaveLength(2)   // quiet: fark 0; izleme kartları: prev yok
    const strip = document.querySelector('.today-recent')
    expect(strip.textContent).toMatch(/Son 24 saatte:.*4 alarm açıldı · 6 alarm çözüldü|Last 24 hours:.*4 alerts opened · 6 alerts resolved/)
    expect(strip.textContent).not.toMatch(/sertifika yenilendi|certificates? renewed/)   // 0 olan parça yazılmaz
    expect(strip.textContent).toMatch(/dün bu saate göre|compared with this time yesterday/)
  })

  it('2026-09-23 son 24 saat şeridi panel TEMİZKEN de görünür (iyi haber); trend görüntüsü yoksa gösterge açıklaması yok', async () => {
    api.me.today.mockResolvedValue({ success: true, data: {
      certs: { count: 0, items: [] }, alerts: { count: 0, items: [] }, ...EMPTY_MON, weekly: { count: 0, missing: 0, items: [] },
      recent: { opened: 0, resolved: 0, renewed: 2, hours: 24 },
    } })
    try { localStorage.setItem('today-panel-open', 'true') } catch { /* yok */ }
    render(<TodayPanel />)
    await screen.findByText(/Bugün ilgilenilecek bir şey yok|Nothing needs attention today/)
    expect(document.querySelector('.today-grid')).toBeNull()
    const strip = document.querySelector('.today-recent')
    expect(strip.textContent).toMatch(/2 sertifika yenilendi|2 certificates renewed/)
    expect(strip.textContent).not.toMatch(/dün bu saate göre|compared with this time yesterday/)
  })

  it('2026-09-23 yerleşim: sayısı 0 olan kart ÇİZİLMEZ, adı alttaki "Sorun yok" şeridine gider (kart sırasıyla); 0\'a düşen kartın ▼ göstergesi şeritte kalır; haftalık rapor tamamsa şeritte', async () => {
    api.me.today.mockResolvedValue({ success: true, data: {
      certs: { count: 2, expired: 0, items: [{ domain: 'a.example.com', days: 10 }, { domain: 'b.example.com', days: 20 }] },
      alerts: { count: 0, critical: 0, prev: 3, items: [] },
      ...EMPTY_MON,
      quiet: { count: 0, items: [] },
      weekly: { count: 1, missing: 0, week: 38, items: [{ team_id: 1, team_name: 'Takım A', status: 'APPROVED' }] },
    } })
    try { localStorage.setItem('today-panel-open', 'true') } catch { /* yok */ }
    render(<TodayPanel />)
    await screen.findByText(/2 konu ilgi bekliyor|2 items need attention/)
    expect(document.querySelectorAll('.today-card')).toHaveLength(1)   // yalnız 30 gün altı sertifika
    const ok = document.querySelector('.today-ok')
    expect(ok.textContent).toMatch(/^Sorun yok:|^All clear:/)
    expect([...ok.querySelectorAll('li')].map((li) => li.firstChild.textContent)).toEqual([
      expect.stringMatching(/Açık alarm|Open alert/), expect.stringMatching(/Kararsız|Flapping/), expect.stringMatching(/Yavaşlayan|Slow/),
      expect.stringMatching(/Sessiz|Silent/), expect.stringMatching(/alan adı|domain/i), expect.stringMatching(/bildirim|notification/i),
      expect.stringMatching(/sağlık|health/i), expect.stringMatching(/Susturulmuş|Muted/), expect.stringMatching(/38/)])
    const down = within(ok).getByLabelText(/Dün bu saate göre 3 azaldı \(dün: 3\)|Down 3 on this time yesterday \(was 3\)/)
    expect(down.textContent).toBe('▼3')
    expect(down.className).toContain('is-better')
  })

  it('uç başarısız → panel çizilmez', async () => {
    api.me.today.mockResolvedValue({ success: false })
    const { container } = render(<TodayPanel />)
    await new Promise((r) => setTimeout(r, 10))
    expect(container.querySelector('.today')).toBeNull()
  })
})
