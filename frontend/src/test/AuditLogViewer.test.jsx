import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import AuditLogViewer from '../components/admin/AuditLogViewer.jsx'

// Denetim Logu (2026-09-26 yeniden tasarım) — api mock'lu. Metinler dilden bağımsız (regex TR|EN) doğrulanır;
// sorgular rol/ad ve data-slot ile (legacy sınıf YOK).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const mobile = vi.hoisted(() => ({ on: false }))
const toastMock = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), dismiss: vi.fn() }))

vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))
vi.mock('../components/ui/Toast.jsx', async (orig) => ({ ...(await orig()), useToast: () => toastMock }))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getAuditLogs: vi.fn(),
      getAuditStats: vi.fn(),
      getAuditIntegrity: vi.fn(),
      getAuditResourceHistory: vi.fn(),
      getAuditEventTypes: vi.fn(),
      getAuditCorrelated: vi.fn(),
      getAuditEntry: vi.fn(),
      auditExportUrl: vi.fn(() => 'http://x/export'),
    },
  }),
}))
import { api } from '../api/client'

const row = (over) => ({
  id: 1, event_time: '2026-07-28T10:00:00', event_type: 'USER_UPDATE', actor: 'alice', actor_id: 11,
  actor_role: 'ADMIN', ip_address: '203.0.113.4', ip_city: 'İstanbul', ip_country: 'Türkiye',
  user_agent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/129.0', ua_summary: 'Windows · Chrome',
  resource_type: 'USER', resource_id: '5', outcome: 'SUCCESS',
  changes: '{"systemRole":{"from":"USER","to":"ADMIN"}}', seq: 10, row_hash: 'ab'.repeat(32), prev_hash: 'cd'.repeat(32),
  ...over,
})
const list = (rows, extra = {}) => ({ success: true, data: rows, total: rows.length, page: 0, ...extra })
/** Liste çağrıları arasından SÜZGEÇ çağrısını bul (ayrıntı paneli ±15 dk penceresi için de getAuditLogs çağırır). */
const listCalls = () => api.admin.getAuditLogs.mock.calls.map(c => c[0]).filter(p => p.size !== 25)
const lastListCall = () => listCalls().at(-1)
const openButtons = () => screen.getAllByRole('button', { name: /^(Ayrıntıyı aç|Open details)/ })
/** Geniş ekran (xl): matchMedia eşleşir → sağ bölme. Test ortamı varsayılanı DAR (Sheet). */
function wideScreen() {
  const orig = window.matchMedia
  window.matchMedia = (q) => ({ matches: true, media: q, addEventListener() {}, removeEventListener() {},
    addListener() {}, removeListener() {}, onchange: null, dispatchEvent: () => false })
  return () => { window.matchMedia = orig }
}

describe('AuditLogViewer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mobile.on = false
    localStorage.clear()
    window.history.replaceState(null, '', '/')
    api.admin.getAuditStats.mockResolvedValue({ success: true, data: {
      total_24h: 5, anomalies_24h: 1, failed_logins_24h: 2, total_7d: 40, anomalies_7d: 3, failed_logins_7d: 7,
      by_outcome_7d: [{ key: 'SUCCESS', count: 30 }, { key: 'BLOCKED', count: 6 }],
      by_event_type_7d: [{ key: 'LOGIN', count: 20 }, { key: 'ACCESS_DENIED', count: 4 }],
      top_actors_7d: [{ key: 'alice', count: 12 }],
      by_day_14d: [{ key: '2026-07-20', count: 3 }, { key: '2026-07-21', count: 5 }],
    } })
    api.admin.getAuditLogs.mockResolvedValue(list([row()]))
    api.admin.getAuditIntegrity.mockResolvedValue({ success: true, data: { ok: true, checked: 42 } })
    api.admin.getAuditResourceHistory.mockResolvedValue({ success: true, data: [row(), row({ id: 2, event_type: 'MONITOR_UPDATE' })] })
    api.admin.getAuditCorrelated.mockResolvedValue({ success: true, data: [] })
    api.admin.getAuditEntry.mockResolvedValue({ success: false })
    api.admin.getAuditEventTypes.mockResolvedValue({ success: true, data: [
      { type: 'LOGIN', category: 'AUTH', count: 42 },
      { type: 'MAINTENANCE_CREATE', category: 'MAINTENANCE', count: 3 },
      { type: 'MONITOR_TEST', category: 'MONITOR', count: 0 },
    ] })
  })
  afterEach(() => { vi.unstubAllGlobals() })

  // ── Kapsam ────────────────────────────────────────────────────────────────────────────────
  it('ekip kapsamı (fullScope=false): bilgi şeridi + "Ekip kapsamı" rozeti; özet ucu HİÇ çağrılmaz; bütünlük/özet kartı yok; dışa aktarma var', async () => {
    render(<AuditLogViewer fullScope={false} />)
    expect(await screen.findByTitle('USER_UPDATE')).toBeInTheDocument()
    expect(screen.getByText(/Ekibinizin denetim kayıtları|Your team's audit records/)).toBeInTheDocument()
    expect(document.querySelector('[data-scope="team"]')).not.toBeNull()
    expect(api.admin.getAuditStats).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: /Bütünlüğü doğrula|Verify integrity/ })).toBeNull()
    expect(document.querySelector('[data-slot="stats-panel"]'), 'ekip kapsamında özet kartı OLMAMALI').toBeNull()
    expect(screen.queryByRole('button', { name: /Eğilim ve dağılım|Trends and breakdown/ })).toBeNull()
    expect(screen.getByRole('button', { name: /^(Dışa aktar|Export)$/ })).toBeInTheDocument()
  })

  it('tam kapsam (varsayılan): özet çağrılır, kartlar + bütünlük düğmesi var, "Tam erişim" rozeti, ekip şeridi yok', async () => {
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditStats).toHaveBeenCalled())
    expect(await screen.findByRole('button', { name: /Bütünlüğü doğrula|Verify integrity/ })).toBeInTheDocument()
    expect(document.querySelector('[data-scope="full"]')).not.toBeNull()
    await waitFor(() => expect(document.querySelectorAll('[data-slot="stat-item"]')).toHaveLength(5))
    expect(screen.queryByText(/Ekibinizin denetim kayıtları|Your team's audit records/)).toBeNull()
  })

  it('takım adları verilirse rozette görünür', async () => {
    render(<AuditLogViewer fullScope={false} teamNames={['Takım A', 'Takım B']} />)
    expect(await screen.findByText(/Ekip: Takım A, Takım B|Team: Takım A, Takım B/)).toBeInTheDocument()
  })

  // ── Özet kartları süzer ─────────────────────────────────────────────────────────────────
  it('özet kartı (başarısız girişler) → LOGIN_FAILED + 7 günlük pencere ile istek; adres a_ anahtarları; ikinci tık temizler', async () => {
    render(<AuditLogViewer />)
    const tile = await screen.findByRole('button', { name: /Başarısız girişler|Failed sign-ins/ })
    expect(tile).toHaveAttribute('data-slot', 'stat-item')
    fireEvent.click(tile)
    await waitFor(() => {
      const last = lastListCall()
      expect(last.eventType).toBe('LOGIN_FAILED')
      expect(last.since).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
      expect(last.page).toBe(0)
    })
    expect(tile).toHaveAttribute('aria-pressed', 'true')
    expect(window.location.search).toContain('a_eventType=LOGIN_FAILED')
    expect(window.location.search).toContain('a_range=7d')
    expect(window.location.search, 'hazır pencere GÖRELİ saklanır (mutlak a_since değil)').not.toContain('a_since')
    fireEvent.click(tile)
    await waitFor(() => expect(lastListCall().eventType).toBe(''))
    expect(tile).toHaveAttribute('aria-pressed', 'false')
    expect(window.location.search).not.toContain('a_eventType')
  })

  it('kart değerleri YALNIZ sunucunun döndürdüğü sayılar (engellenen = by_outcome_7d, erişim reddi = by_event_type_7d)', async () => {
    render(<AuditLogViewer />)
    // Kart adı MonitorStatsBar'dan: "… ile süz / Filter by … (7 gün)" — etiket adın İÇİNDE, başında değil.
    const blocked = await screen.findByRole('button', { name: /(Engellenen|Blocked) \(7/ })
    expect(within(blocked).getByText('6')).toBeInTheDocument()
    const denied = screen.getByRole('button', { name: /(Erişim reddi|Access denied) \(7/ })
    expect(within(denied).getByText('4')).toBeInTheDocument()
  })

  it('özet ucu düşerse uyarı + yeniden dene (sessiz değil)', async () => {
    api.admin.getAuditStats.mockResolvedValue({ success: false })
    render(<AuditLogViewer />)
    expect(await screen.findByText(/Özet kartları yüklenemedi|summary cards couldn’t be loaded/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Yeniden dene|Retry/ }))
    await waitFor(() => expect(api.admin.getAuditStats).toHaveBeenCalledTimes(2))
  })

  it('eğilim ve dağılım katlanır bölümünde dağılım satırı süzgeç uygular', async () => {
    render(<AuditLogViewer />)
    fireEvent.click(await screen.findByRole('button', { name: /Eğilim ve dağılım|Trends and breakdown/ }))
    const btn = await screen.findByRole('button', { name: /(Erişim reddedildi|Access denied).*\(4\)/ })
    fireEvent.click(btn)
    await waitFor(() => expect(lastListCall().eventType).toBe('ACCESS_DENIED'))
  })

  // ── Süzgeçler: çipler, temizleme, adres ─────────────────────────────────────────────────
  it('etkin süzgeç çipleri: kaldırınca o süzgeç düşer, "Tümünü temizle" hepsini siler', async () => {
    window.history.replaceState(null, '', '/?a_outcome=BLOCKED&a_actor=alice')
    render(<AuditLogViewer />)
    await waitFor(() => expect(lastListCall()).toMatchObject({ outcome: 'BLOCKED', actor: 'alice' }))
    const chips = screen.getByRole('group', { name: /Etkin süzgeçler|Active filters/ })
    expect(within(chips).getByRole('button', { name: /(Süzgeci kaldır|Remove filter): (Sonuç|Outcome)/ })).toBeInTheDocument()
    fireEvent.click(within(chips).getByRole('button', { name: /(Süzgeci kaldır|Remove filter): (Kullanıcı|User): alice/ }))
    await waitFor(() => expect(lastListCall()).toMatchObject({ outcome: 'BLOCKED', actor: '' }))
    expect(window.location.search).not.toContain('a_actor')
    fireEvent.click(screen.getByRole('button', { name: /Tümünü temizle|Clear all/ }))
    await waitFor(() => expect(lastListCall().outcome).toBe(''))
    expect(window.location.search).not.toContain('a_outcome')
    expect(screen.queryByRole('group', { name: /Etkin süzgeçler|Active filters/ })).toBeNull()
  })

  it('eski derin bağlantı (a_since/a_until, a_range yok) özel aralık sayılır ve aynen gönderilir', async () => {
    window.history.replaceState(null, '', '/?a_since=2026-07-01T00:00:00&a_until=2026-07-31T23:59:59')
    render(<AuditLogViewer />)
    await waitFor(() => expect(lastListCall()).toMatchObject({ since: '2026-07-01T00:00:00', until: '2026-07-31T23:59:59' }))
    expect(window.location.search).toContain('a_range=custom')
  })

  it('sonuç seçici → outcome; anomali düğmesi → anomalyOnly; adres eşlenir', async () => {
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    fireEvent.change(screen.getByRole('combobox', { name: /^(Sonuç|Outcome)$/ }), { target: { value: 'FAILURE' } })
    await waitFor(() => expect(lastListCall().outcome).toBe('FAILURE'))
    fireEvent.click(screen.getByRole('button', { name: /Sadece anomaliler|Anomalies only/ }))
    await waitFor(() => expect(lastListCall().anomalyOnly).toBe(true))
    expect(window.location.search).toContain('a_outcome=FAILURE')
    expect(window.location.search).toContain('a_anomalyOnly=1')
  })

  it('metin süzgeci gecikmeli, Enter hemen uygular', async () => {
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    const before = listCalls().length
    const input = screen.getByRole('textbox', { name: /^(Ara|Search)$/ })
    fireEvent.change(input, { target: { value: 'db-01' } })
    expect(listCalls().length, 'her tuşta istek ATILMAZ').toBe(before)
    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() => expect(lastListCall().q).toBe('db-01'))
    expect(window.location.search).toContain('a_q=db-01')
  })

  it('olay türü faset süzgeci SUNUCU kataloğundan beslenir, sayı gösterir, çoklu seçim CSV gider', async () => {
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditEventTypes).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /^(Olay türü|Event type)$/ }))
    const search = document.querySelector('[cmdk-input]')
    fireEvent.change(search, { target: { value: 'MAINTENANCE' } })
    const opt = await screen.findByRole('option', { name: /Bakım|Maintenance/ })
    expect(within(opt).getByText('3')).toHaveAttribute('data-slot', 'facet-count')
    fireEvent.click(opt)
    fireEvent.change(search, { target: { value: 'LOGIN' } })
    fireEvent.click(await screen.findByRole('option', { name: /42/ }))
    await waitFor(() => expect(lastListCall().eventType).toBe('MAINTENANCE_CREATE,LOGIN'))
    expect(window.location.search).toContain('a_eventType=MAINTENANCE_CREATE%2CLOGIN')
  })

  it('katalog ucu düşerse YEDEK listeyle çalışmaya devam eder', async () => {
    api.admin.getAuditEventTypes.mockRejectedValue(new Error('network'))
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /^(Olay türü|Event type)$/ }))
    fireEvent.change(document.querySelector('[cmdk-input]'), { target: { value: 'LOGIN_FAILED' } })
    expect(await screen.findByRole('option', { name: /Giriş başarısız|Sign-in failed/ })).toBeInTheDocument()
  })

  // ── Görünümler ──────────────────────────────────────────────────────────────────────────
  it('hazır görünüm (Güvenlik olayları) → outcome=BLOCKED; adrese yansır; yeniden basınca temizlenir', async () => {
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /^(Görünümler|Views)$/ }))
    fireEvent.click(await screen.findByRole('button', { name: /Güvenlik olaylar|Security events/ }))
    await waitFor(() => expect(lastListCall().outcome).toBe('BLOCKED'))
    await waitFor(() => expect(window.location.search).toContain('a_outcome=BLOCKED'))
    fireEvent.click(screen.getByRole('button', { name: /^(Görünümler|Views)$/ }))
    const active = await screen.findByRole('button', { name: /Güvenlik olaylar|Security events/ })
    expect(active).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(active)
    await waitFor(() => expect(lastListCall().outcome).toBe(''))
  })

  it('görünüm kaydet → listede belirir + tıklayınca uygulanır (localStorage, sm. önekli)', async () => {
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /^(Görünümler|Views)$/ }))
    fireEvent.change(await screen.findByPlaceholderText(/Görünüm adı|View name/), { target: { value: 'Benim görünümüm' } })
    fireEvent.click(screen.getByRole('button', { name: /^(Görünümü kaydet|Save view)$/ }))
    expect(await screen.findByRole('button', { name: 'Benim görünümüm' })).toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem('sm.audit.savedViews'))[0].name).toBe('Benim görünümüm')
    const before = listCalls().length
    fireEvent.click(screen.getByRole('button', { name: 'Benim görünümüm' }))
    await waitFor(() => expect(listCalls().length).toBeGreaterThan(before))
    fireEvent.click(screen.getByRole('button', { name: /^(Görünümler|Views)$/ }))
    fireEvent.click(await screen.findByRole('button', { name: /Benim görünümüm — (Görünümü sil|Delete view)/ }))
    await waitFor(() => expect(JSON.parse(localStorage.getItem('sm.audit.savedViews'))).toEqual([]))
  })

  it('ESKİ anahtardaki kayıtlı görünümler GÖÇ eder (kullanıcı görünümlerini kaybetmez)', async () => {
    localStorage.setItem('auditSavedViews', JSON.stringify([{ name: 'Eski görünüm', filters: { outcome: 'BLOCKED' } }]))
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /^(Görünümler|Views)$/ }))
    expect(await screen.findByRole('button', { name: 'Eski görünüm' })).toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem('sm.audit.savedViews'))[0].name).toBe('Eski görünüm')
    expect(localStorage.getItem('auditSavedViews'), 'eski anahtar temizlenmeli').toBeNull()
  })

  // ── Liste ────────────────────────────────────────────────────────────────────────────────
  it('satır ÇEVRİLMİŞ etiket gösterir, ham tür TITLE içinde kalır; her satırın adlı bir açma düğmesi var', async () => {
    render(<AuditLogViewer />)
    const badge = await screen.findByTitle('USER_UPDATE')
    expect(badge).toHaveAttribute('data-slot', 'badge')
    expect(badge).toHaveAttribute('data-event', 'ev-edit')
    expect(badge.textContent, 'ham SNAKE_CASE basılmamalı').not.toBe('USER_UPDATE')
    const [open] = openButtons()
    expect(open.getAttribute('aria-label')).toMatch(/alice/)
    expect(open).toHaveAttribute('tabindex', '0')
    expect(document.querySelector('[data-slot="audit-list"] table')).not.toBeNull()
    expect(screen.getByText(/1 olay|1 events/)).toBeInTheDocument()
  })

  it('anomali bayrağı çevrilmiş etiket ve renk SINIFI taşır (satır-içi hex değil)', async () => {
    api.admin.getAuditLogs.mockResolvedValue(list([row({ id: 93, event_type: 'LOGIN_FAILED', outcome: 'FAILURE', anomaly_flags: 'OFF_HOURS,BRUTE_FORCE' })]))
    const { container } = render(<AuditLogViewer />)
    await screen.findByTitle('LOGIN_FAILED')
    expect(container.querySelector('[data-flag="off_hours"]')).not.toBeNull()
    expect(container.querySelector('[data-flag="brute_force"]')).not.toBeNull()
    expect(container.querySelector('[data-flag]').getAttribute('style')).toBeNull()
    expect(screen.queryByText('OFF HOURS'), 'ham bayrak basılmamalı').toBeNull()
  })

  it('ilk yükleme iskelet, liste hatası SESSİZ değil: uyarı + yeniden dene', async () => {
    api.admin.getAuditLogs.mockResolvedValue({ success: false, error: 'boom' })
    render(<AuditLogViewer />)
    expect(document.querySelector('[data-skeleton]'), 'ilk yüklemede iskelet').not.toBeNull()
    expect(await screen.findByText(/yüklenemedi|Could not load/)).toBeInTheDocument()
    const before = listCalls().length
    fireEvent.click(screen.getByText(/Yeniden dene|Retry/))
    await waitFor(() => expect(listCalls().length).toBeGreaterThan(before))
  })

  it('boş sonuç: süzgeç varken TÜMÜNÜ TEMİZLE eylemi sunulur, süzgeçsizken sunulmaz', async () => {
    api.admin.getAuditLogs.mockResolvedValue(list([]))
    render(<AuditLogViewer />)
    expect(await screen.findByText(/Kayıt bulunamadı|No records found/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Tümünü temizle|Clear all/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Sadece anomaliler|Anomalies only/ }))
    expect(await screen.findByText(/Bu (süzgeç|filtre)lerle kayıt yok|No records match/)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /Tümünü temizle|Clear all/ }).length).toBeGreaterThan(0)
  })

  // ── Ayrıntı: Sheet (dar) / bölme (geniş) ──────────────────────────────────────────────
  it('dar ekranda satır → yan Sheet (dialog) açılır: diff tablosu, kim/nereden, ham kayıt kopyalanır', async () => {
    const write = vi.spyOn(navigator.clipboard, 'writeText')
    render(<AuditLogViewer />)
    fireEvent.click(await screen.findByTitle('USER_UPDATE'))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByText(/Kim, ne zaman, nereden|Who, when and where/)).toBeInTheDocument()
    expect(dlg.querySelector('[data-diff="to"]')).not.toBeNull()
    expect(within(dlg).getByText('203.0.113.4')).toBeInTheDocument()
    expect(within(dlg).getByText('Windows · Chrome')).toBeInTheDocument()
    expect(screen.queryByRole('complementary'), 'dar ekranda yan bölme OLMAMALI').toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: /Ham kayıt|Raw record/ }))
    expect(dlg.querySelector('[data-slot="audit-raw"]').textContent).toContain('"row_hash"')
    fireEvent.click(within(dlg).getByRole('button', { name: /Ham kaydı kopyala|Copy raw record/ }))
    await waitFor(() => expect(write).toHaveBeenCalledWith(expect.stringContaining('"event_type": "USER_UPDATE"')))
    write.mockRestore()
  })

  it('GENİŞ ekranda sağ bölme (complementary) açılır, dialog AÇILMAZ; kapat → seçim düşer, yer tutucu döner', async () => {
    const restore = wideScreen()
    try {
      render(<AuditLogViewer />)
      expect(await screen.findByText(/Bir olay seçin|Select an event/)).toBeInTheDocument()
      fireEvent.click(await screen.findByTitle('USER_UPDATE'))
      const aside = screen.getByRole('complementary')
      expect(within(aside).getByText(/Ne oldu|What happened/)).toBeInTheDocument()
      expect(screen.queryByRole('dialog'), 'geniş ekranda Sheet AÇILMAMALI').toBeNull()
      await waitFor(() => expect(window.location.search).toContain('a_sel=1'))   // adres eşlemesi 300 ms gecikmeli
      fireEvent.click(within(aside).getByRole('button', { name: /^(Kapat|Close)$/ }))
      expect(await screen.findByText(/Bir olay seçin|Select an event/)).toBeInTheDocument()
      await waitFor(() => expect(window.location.search).not.toContain('a_sel'))
    } finally { restore() }
  })

  it('ayrıntıdaki hızlı süzgeç "bu kullanıcının olayları" → actor süzgeci, Sheet kapanır', async () => {
    render(<AuditLogViewer />)
    fireEvent.click(await screen.findByTitle('USER_UPDATE'))
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: /Bu kullanıcının olayları|This user’s events/ }))
    await waitFor(() => expect(lastListCall().actor).toBe('alice'))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('±15 dakika penceresi: aynı aktörün diğer olayları listelenir, seçilince ayrıntı ona geçer (sayfada olmasa da)', async () => {
    const other = row({ id: 77, event_type: 'LOGOUT', event_time: '2026-07-28T10:05:00', changes: null })
    api.admin.getAuditLogs.mockImplementation((p) => Promise.resolve(p.size === 25 ? list([row(), other]) : list([row()])))
    render(<AuditLogViewer />)
    fireEvent.click(await screen.findByTitle('USER_UPDATE'))
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalledWith(expect.objectContaining({ actorId: 11, size: 25 })))
    const around = within(dlg).getByText(/Aynı kullanıcı|Same user/).closest('[data-slot="card"]')
    expect(within(around).queryByText(/başka olay yok|No other events/), 'seçili olayın KENDİSİ listelenmez, diğeri listelenir').toBeNull()
    expect(within(around).getByText(/\+5 (dk|min)/)).toBeInTheDocument()
    fireEvent.click(within(around).getByRole('button', { name: /(Ayrıntıyı aç|Open details).*(Çıkış|Signed out)/ }))
    await waitFor(() => expect(within(screen.getByRole('dialog')).getAllByTitle('LOGOUT').length).toBeGreaterThan(0))
    await waitFor(() => expect(window.location.search).toContain('a_sel=77'))
  })

  it('derin bağlantı a_sel sayfada değilse tekil uçtan getirilir ve açılır', async () => {
    window.history.replaceState(null, '', '/?a_sel=555')
    api.admin.getAuditEntry.mockResolvedValue({ success: true, data: row({ id: 555, event_type: 'DOMAIN_DELETE', changes: null }) })
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditEntry).toHaveBeenCalledWith(555))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getAllByTitle('DOMAIN_DELETE').length).toBeGreaterThan(0)
  })

  it('kaynak geçmişi açılınca YÜKLENİR (her seçimde değil) ve zaman çizelgesi çizer', async () => {
    render(<AuditLogViewer />)
    fireEvent.click(await screen.findByTitle('USER_UPDATE'))
    const dlg = await screen.findByRole('dialog')
    expect(api.admin.getAuditResourceHistory).not.toHaveBeenCalled()
    fireEvent.click(within(dlg).getByRole('button', { name: /USER:5 — (geçmiş|history)/ }))
    await waitFor(() => expect(api.admin.getAuditResourceHistory).toHaveBeenCalledWith('USER', '5', 50))
    expect(await within(dlg).findByRole('button', { name: /(Ayrıntıyı aç|Open details).*(İzleme güncellendi|Monitor updated)/ })).toBeInTheDocument()
  })

  // ── Klavye ──────────────────────────────────────────────────────────────────────────────
  it('klavye: ↑/↓ seçimi taşır ve odak o satırın düğmesine gider, Enter açar, Esc seçimi temizler', async () => {
    api.admin.getAuditLogs.mockResolvedValue(list([row({ id: 1 }), row({ id: 2, event_type: 'USER_DELETE', changes: null })]))
    const { container } = render(<AuditLogViewer />)
    await screen.findByTitle('USER_DELETE')
    const tbody = container.querySelector('tbody')
    const rowsSel = () => [...container.querySelectorAll('tbody tr')].map(tr => tr.getAttribute('aria-selected'))

    fireEvent.keyDown(tbody, { key: 'ArrowDown' })
    await waitFor(() => expect(rowsSel()).toEqual(['true', 'false']))
    expect(document.activeElement).toBe(openButtons()[0])
    fireEvent.keyDown(tbody, { key: 'ArrowDown' })
    await waitFor(() => expect(rowsSel()).toEqual(['false', 'true']))
    expect(document.activeElement).toBe(openButtons()[1])
    expect(screen.queryByRole('dialog'), 'ok tuşu yalnız SEÇER, açmaz').toBeNull()

    fireEvent.click(document.activeElement)   // Enter = düğmenin kendi tıklaması
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    fireEvent.keyDown(document.activeElement, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    fireEvent.keyDown(tbody, { key: 'ArrowUp' })
    await waitFor(() => expect(rowsSel()).toEqual(['true', 'false']))
    fireEvent.keyDown(tbody, { key: 'Escape' })
    await waitFor(() => expect(container.querySelector('tr[aria-selected="true"]')).toBeNull())
  })

  it('ayrıntıda "daha eski / daha yeni" düğmeleri sayfa içinde gezer', async () => {
    api.admin.getAuditLogs.mockResolvedValue(list([row({ id: 1 }), row({ id: 2, event_type: 'USER_DELETE', changes: null })]))
    render(<AuditLogViewer />)
    fireEvent.click(await screen.findByTitle('USER_UPDATE'))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByRole('button', { name: /Daha yeni|Newer/ })).toBeDisabled()
    fireEvent.click(within(dlg).getByRole('button', { name: /Daha eski|Older/ }))
    await waitFor(() => expect(within(screen.getByRole('dialog')).getAllByTitle('USER_DELETE').length).toBeGreaterThan(0))
    expect(within(screen.getByRole('dialog')).getByRole('button', { name: /Daha eski|Older/ })).toBeDisabled()
  })

  // ── Telefon ─────────────────────────────────────────────────────────────────────────────
  it('telefon (useIsMobile): tablo YOK, güne göre kart listesi; kart tam genişlik Sheet açar; süzgeçler Sheet içinde', async () => {
    mobile.on = true
    api.admin.getAuditLogs.mockResolvedValue(list([row({ id: 1 }), row({ id: 2, event_time: '2026-07-27T09:00:00', event_type: 'LOGIN', changes: null })]))
    render(<AuditLogViewer />)
    const cards = await screen.findAllByRole('button', { name: /^(Ayrıntıyı aç|Open details)/ })
    expect(cards).toHaveLength(2)
    expect(document.querySelector('[data-slot="audit-list"] table'), 'telefonda tablo çizilmez').toBeNull()
    expect(document.querySelectorAll('[data-slot="audit-list"] section')).toHaveLength(2)   // iki gün
    expect(within(cards[0]).getByText('203.0.113.4')).toBeInTheDocument()   // IP kartta GÖRÜNÜR (ipucu değil)

    fireEvent.click(screen.getByRole('button', { name: /^(Süzgeçler|Filters)$/ }))
    const filters = await screen.findByRole('dialog')
    expect(within(filters).getByRole('combobox', { name: /Zaman aralığı|Time range/ })).toBeInTheDocument()
    fireEvent.change(within(filters).getByRole('combobox', { name: /Zaman aralığı|Time range/ }), { target: { value: '24h' } })
    await waitFor(() => expect(lastListCall().since).toBeTruthy())
    expect(within(filters).getByRole('button', { name: /(2 sonucu göster|Show 2 results)/ })).toBeInTheDocument()
    fireEvent.keyDown(filters, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.getByRole('button', { name: /(Süzgeçler|Filters) \(1\)/ })).toBeInTheDocument()

    fireEvent.click(cards[0])
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByText(/Ne oldu|What happened/)).toBeInTheDocument()
  })

  // ── Dışa aktarma / bütünlük ─────────────────────────────────────────────────────────────
  it('dışa aktarma menüsü: CSV → süzgeçli URL fetch edilir ve indirilir; 429 → dostça bildirim', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({ ok: true, status: 200, blob: () => Promise.resolve(new Blob(['a'])), headers: new Headers({ 'Content-Disposition': 'attachment; filename="audit-2026-09-26.csv"' }) })
      .mockResolvedValueOnce({ ok: false, status: 429, headers: new Headers() })
    vi.stubGlobal('fetch', fetchMock)
    // jsdom'da URL.createObjectURL YOK — dar stub (test sonunda kaldırılır).
    const create = vi.fn(() => 'blob:x')
    URL.createObjectURL = create
    URL.revokeObjectURL = vi.fn()
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())

    pressMenuTrigger(screen.getByRole('button', { name: /^(Dışa aktar|Export)$/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /CSV/ }))
    await waitFor(() => expect(toastMock.success).toHaveBeenCalled())
    expect(api.admin.auditExportUrl).toHaveBeenCalledWith('csv', expect.objectContaining({ anomalyOnly: false }))
    expect(fetchMock).toHaveBeenCalledWith('http://x/export', expect.objectContaining({ credentials: 'include' }))
    expect(create).toHaveBeenCalled()

    pressMenuTrigger(screen.getByRole('button', { name: /^(Dışa aktar|Export)$/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /JSON/ }))
    await waitFor(() => expect(toastMock.info).toHaveBeenCalledWith(expect.stringMatching(/Başka bir dışa aktarma sürüyor|Another export is already running/)))
    expect(toastMock.error).not.toHaveBeenCalled()
    delete URL.createObjectURL; delete URL.revokeObjectURL
  })

  it('dışa aktarma sınırı ipucu tavanı söyler; eşleşen kayıt tavanı aşarsa uyarır (ekip 5.000)', async () => {
    api.admin.getAuditLogs.mockResolvedValue(list([row()], { total: 7000 }))
    render(<AuditLogViewer fullScope={false} />)
    await screen.findByTitle('USER_UPDATE')
    fireEvent.click(screen.getByRole('button', { name: /Dışa aktarma sınırları|Export limits/ }))
    const tip = await screen.findByRole('tooltip')
    expect(tip.textContent).toMatch(/5[.,]000/)
    expect(tip.textContent).toMatch(/7[.,]000/)
  })

  it('bütünlüğü doğrula → sağlam zincir rozeti; kırık zincir → alarm şeridi', async () => {
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /Bütünlüğü doğrula|Verify integrity/ }))
    await waitFor(() => expect(api.admin.getAuditIntegrity).toHaveBeenCalled())
    expect(await screen.findByText(/Zincir sağlam|Chain intact/)).toBeInTheDocument()
    api.admin.getAuditIntegrity.mockResolvedValue({ success: true, data: { ok: false, checked: 10, broken_seq: 7 } })
    fireEvent.click(screen.getByRole('button', { name: /Bütünlüğü doğrula|Verify integrity/ }))
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toMatch(/#7/)
  })

  // ── Ayrıntı içeriği ─────────────────────────────────────────────────────────────────────
  it('DÜZ METİN ayrıntı ekranda gösterilir (JSON olmayan detail sessizce düşmez)', async () => {
    api.admin.getAuditLogs.mockResolvedValue(list([row({ id: 91, event_type: 'USER_PUSH_TEST', resource_type: 'USER_PUSH', resource_id: 'test',
      detail: 'test → ops@example.com', changes: null })]))
    render(<AuditLogViewer />)
    fireEvent.click(await screen.findByTitle('USER_PUSH_TEST'))
    expect(await screen.findByText('test → ops@example.com')).toBeInTheDocument()
  })

  it('JSON ayrıntı alan/değer tablosuna açılır', async () => {
    api.admin.getAuditLogs.mockResolvedValue(list([row({ id: 92, event_type: 'MONITOR_TEST', resource_type: 'PORT_MONITOR', resource_id: 'test',
      detail: '{"host":"db-01","port":5432}', changes: null })]))
    render(<AuditLogViewer />)
    fireEvent.click(await screen.findByTitle('MONITOR_TEST'))
    const dlg = await screen.findByRole('dialog')
    expect(await within(dlg).findByText('db-01')).toBeInTheDocument()
    expect(within(dlg).getByText('5432')).toBeInTheDocument()
  })

  // ── Yarış / sayfalama ───────────────────────────────────────────────────────────────────
  it('R12: sıra dışı yanıt — önce başlayan (eski süzgeç) istek SONRA dönerse ekrana yazılmaz', async () => {
    let releaseStale
    api.admin.getAuditLogs
      .mockImplementationOnce(() => new Promise(r => { releaseStale = () => r(list([row()])) }))
      .mockImplementationOnce(() => Promise.resolve(list([row({ id: 9, event_type: 'LOGIN', outcome: 'BLOCKED', changes: null })])))
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole('button', { name: /Sadece anomaliler|Anomalies only/ }))
    expect(await screen.findByTitle('LOGIN')).toBeInTheDocument()

    releaseStale()                                   // ilk (süzgeçsiz) istek ŞİMDİ dönüyor
    await act(async () => { await new Promise(r => setTimeout(r, 0)) })
    expect(screen.getByTitle('LOGIN')).toBeInTheDocument()
    expect(screen.queryByTitle('USER_UPDATE')).toBeNull()
  })

  // Sayfalama standardı (2026-09-26): page/ps adresi kancada; ps ön ayar listesine karşı doğrulanır.
  it('derin bağlantı ?page=2&ps=100 → ilk istek page=1 size=100; geçersiz ps=33 yok sayılır; süzgeç başa döner', async () => {
    window.history.replaceState({}, '', '/?tab=system&page=2&ps=100')
    api.admin.getAuditLogs.mockResolvedValue(list([row()], { total: 400, page: 1 }))
    const { unmount } = render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    expect(listCalls()[0]).toMatchObject({ page: 1, size: 100 })
    fireEvent.click(await screen.findByRole('button', { name: /Sadece anomaliler|Anomalies only/ }))
    await waitFor(() => expect(lastListCall()).toMatchObject({ page: 0, size: 100, anomalyOnly: true }))
    unmount()
    api.admin.getAuditLogs.mockClear()
    window.history.replaceState({}, '', '/?tab=system&ps=33')
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    expect(listCalls()[0].size).not.toBe(33)
    window.history.replaceState({}, '', '/')
  })
})
