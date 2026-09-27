import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import MyAuditLog from '../components/MyAuditLog.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
/** Telefon düzeni (useIsMobile) — testte anahtarla değiştirilir (jsdom medya sorgusu uygulamaz). */
const mobile = vi.hoisted(() => ({ value: false }))

vi.mock('../api/client', () => ({
  api: withApiFallback({ me: {
    getMyAudit: vi.fn(),
    getMyDevices: vi.fn(),
    getMyDeviceLogins: vi.fn(),
    reportSuspiciousLogin: vi.fn(),
    logoutOtherDevices: vi.fn(),
  } }),
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
}))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.value }))
import { api } from '../api/client'

const HOUR = 3_600_000
const DAY = 24 * HOUR
/** UTC ISO, ek yok — backend biçimi. Tarihler koşu anına GÖRELİ (sabit tarih = zaman bombası). */
const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)

const WIN = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36'
let nextId = 100
function ev(msAgo, type, extra = {}) {
  return {
    id: nextId++, event_type: type, event_time: iso(msAgo), actor: 'demo', outcome: 'SUCCESS',
    ip_address: '203.0.113.24', ip_city: 'İstanbul', ip_country: 'Türkiye', ip_org: 'Example Broadband',
    user_agent: WIN, ua_summary: 'Windows · Chrome', resource_type: 'USER', resource_id: 'demo',
    detail: null, changes: null, failure_reason: null, anomaly_flags: null, correlation_id: 'c-0001',
    ...extra,
  }
}

/** Sekiz olay: 3 giriş (biri yeni ağdan), 2 başarısız (biri hız sınırı → BLOCKED), çıkış, parola, izleme farkı. */
function fixture() {
  nextId = 100
  return [
    ev(1_000, 'LOGIN'),
    ev(2 * HOUR, 'MONITOR_UPDATE', { resource_type: 'HTTP_MONITOR', resource_id: '12', detail: 'www.example.com',
      changes: JSON.stringify({ interval_seconds: { from: 300, to: 60 } }) }),
    ev(1 * DAY + HOUR, 'LOGIN_FAILED', { outcome: 'FAILURE', failure_reason: "BAD_PASSWORD: attempt #1/5 for 'demo'",
      ip_address: '192.0.2.77', ip_city: 'Amsterdam', ip_country: 'Netherlands', user_agent: 'python-requests/2.32.3', ua_summary: null }),
    ev(1 * DAY + HOUR + 60_000, 'LOGIN_FAILED', { outcome: 'BLOCKED', failure_reason: 'Rate limited: too many login attempts from 192.0.2.77',
      anomaly_flags: 'RATE_LIMITED', ip_address: '192.0.2.77', ip_city: 'Amsterdam', ip_country: 'Netherlands', user_agent: 'python-requests/2.32.3', ua_summary: null }),
    ev(2 * DAY, 'LOGOUT'),
    ev(3 * DAY, 'LOGIN', { anomaly_flags: 'UNUSUAL_IP', ip_address: '198.51.100.7', ip_city: 'Ankara', ua_summary: 'iOS · Safari' }),
    ev(5 * DAY, 'SELF_PASSWORD_CHANGE', { resource_id: '42' }),
    ev(6 * DAY, 'LOGIN', { ip_address: '10.20.30.41', ip_city: 'LAN', ip_country: 'Private', ip_org: 'Internal' }),
  ]
}

/** Backend süzgecinin taklidi (findOwnFiltered): tam eşleşme + dize karşılaştırması + 0 tabanlı sayfa. */
function serve(events) {
  api.me.getMyAudit.mockImplementation(async (p = {}) => {
    const { eventType, outcome, since, until, page = 0, size = 50 } = p
    const rows = events.filter((e) => (!eventType || e.event_type === eventType) && (!outcome || e.outcome === outcome)
      && (!since || e.event_time >= since) && (!until || e.event_time <= until))
    const sz = Math.min(Number(size), 200)
    return { success: true, data: rows.slice(page * sz, page * sz + sz), total: rows.length, page, total_pages: Math.ceil(rows.length / sz) }
  })
}
/** Liste çağrıları: sunucu süzgeci HER İKİ anahtarı (eventType + outcome, boş da olsa) taşır; özet çekimleri taşımaz. */
const isList = (p) => 'eventType' in p && 'outcome' in p
const listCalls = () => api.me.getMyAudit.mock.calls.map((c) => c[0]).filter(isList)
const lastList = () => listCalls().at(-1)
const entries = () => document.querySelectorAll('[data-slot="activity-entry"]')
const timeline = () => document.querySelector('[data-slot="activity-timeline"]')

describe('MyAuditLog (Etkinliklerim — zaman çizelgesi)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mobile.value = false
    sessionStorage.clear()
    localStorage.clear()
    serve(fixture())
    api.me.getMyDevices.mockResolvedValue({ success: true, data: { current: { known: false }, remembered: [] } })
    api.me.getMyDeviceLogins.mockResolvedValue({ success: true, data: { rows: [], total: 0, page: 0 } })
  })

  it('kendi kaydını GÜNE göre gruplanmış çizelgede, insan cümlesiyle listeler (rozet yalnız başarısızda)', async () => {
    render(<MyAuditLog />)
    expect(await screen.findAllByText('Signed in from Chrome on Windows')).toHaveLength(2)
    expect(entries()).toHaveLength(8)
    // Gün başlıkları: en yeni olay (1 sn önce) bugünün başlığı altında; başlık sayacı o günün satırlarını sayar
    const heads = document.querySelectorAll('[data-slot="activity-day-head"]')
    expect(heads.length).toBeGreaterThanOrEqual(4)
    expect(heads[0]).toHaveTextContent(/^Today/)
    // Başarısız satırlar rozet + sebep; başarılı satırda sonuç rozeti YOK (istisna vurgusu)
    expect(screen.getByText('Sign-in attempt blocked — too many tries')).toBeInTheDocument()
    expect(document.querySelectorAll('[data-slot="activity-entry"] [data-outcome="FAILURE"]')).toHaveLength(1)
    expect(document.querySelectorAll('[data-slot="activity-entry"][data-outcome="SUCCESS"] [data-slot="badge"][data-outcome]')).toHaveLength(0)
    // İzleme değişikliği: etiket + hedef; yeni ağdan giriş: işaret rozeti satırda ("Neler yaptınız" kartı da etiketi
    // yazar → sorgu çizelgeye daraltılır)
    expect(within(timeline()).getByText('Monitor updated')).toBeInTheDocument()
    expect(within(timeline()).getByText('www.example.com')).toBeInTheDocument()
    expect(within(timeline()).getByText('Unfamiliar IP')).toBeInTheDocument()
    // Kurum ağı: özel IP'de konum metni
    expect(screen.getAllByText('Corporate network').length).toBeGreaterThanOrEqual(1)
  })

  it('tarih süzgeci UTC ISO gönderir: varsayılan son 30 gün, "24 saat" ön ayarı pencereyi kaydırır, "Özel" alanları açar', async () => {
    render(<MyAuditLog />)
    await waitFor(() => expect(lastList()).toBeTruthy())
    const first = lastList()
    expect(first.since).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)   // 'Z' yok, saniye var
    expect(Math.abs(Date.parse(first.since + 'Z') - (Date.now() - 30 * DAY))).toBeLessThan(60_000)
    expect(first.until).toBe('')
    expect(first).toMatchObject({ page: 0, size: 50 })

    fireEvent.click(screen.getByRole('button', { name: '24 hours' }))
    await waitFor(() => expect(Math.abs(Date.parse(lastList().since + 'Z') - (Date.now() - DAY))).toBeLessThan(60_000))
    // 24 saatlik pencerede yalnız 2 olay (1 sn ve 2 sa önce)
    await waitFor(() => expect(entries()).toHaveLength(2))

    fireEvent.click(screen.getByRole('button', { name: 'Custom' }))
    expect(document.querySelector('[data-slot="my-activity-custom-range"]')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /From|Until/ }).length).toBeGreaterThanOrEqual(2)
  })

  it('sayım kartları aralık ÖZETİNDEN sayar ve tıklanınca listeyi süzer; çip kaldırır; "Tümü" temizler', async () => {
    render(<MyAuditLog />)
    const panel = await waitFor(() => { const p = document.querySelector('[data-slot="stats-panel"]'); expect(p).toBeTruthy(); return p })
    const value = (label) => within(within(panel).getByRole('button', { name: new RegExp(label) })).getByText((_, el) => el?.getAttribute('data-slot') === 'stat-value').textContent
    await waitFor(() => expect(value('All activity')).toBe('8'))
    expect(value('Sign-ins')).toBe('3')
    expect(value('Failed sign-ins')).toBe('2')
    expect(value('Blocked')).toBe('1')
    expect(value('Password changes')).toBe('1')
    expect(value('Open device history')).toBe('2')   // giriş cihazları: Windows · Chrome (2 giriş) + iOS · Safari
    expect(within(panel).getByRole('button', { name: /Failed sign-ins/ })).toHaveAttribute('data-tone', 'error')

    fireEvent.click(within(panel).getByRole('button', { name: 'Filter: Failed sign-ins' }))
    await waitFor(() => expect(lastList()).toMatchObject({ eventType: 'LOGIN_FAILED', outcome: '', page: 0 }))
    await waitFor(() => expect(entries()).toHaveLength(2))
    expect(within(panel).getByRole('button', { name: /Failed sign-ins/ })).toHaveAttribute('aria-pressed', 'true')
    // Etkin süzgeç çipi → kaldırınca tür süzgeci düşer
    const chip = screen.getByRole('button', { name: 'Remove filter: Event: Sign-in failed' })
    fireEvent.click(chip)
    await waitFor(() => expect(lastList().eventType).toBe(''))
    await waitFor(() => expect(entries()).toHaveLength(8))

    fireEvent.click(within(panel).getByRole('button', { name: 'Filter: Blocked' }))
    await waitFor(() => expect(lastList()).toMatchObject({ outcome: 'BLOCKED', eventType: '' }))
    fireEvent.click(within(panel).getByRole('button', { name: /All activity/ }))
    await waitFor(() => expect(lastList()).toMatchObject({ outcome: '', eventType: '' }))
  })

  it('güvenlik uyarısı: başarısız/engellenen/yeni ağdan giriş varsa görünür, "Başarısız girişleri göster" süzer; temiz veride yok', async () => {
    render(<MyAuditLog onChangePassword={vi.fn()} />)
    const alert = await waitFor(() => { const a = document.querySelector('[data-slot="alert"][data-tone="danger"]'); expect(a).toBeTruthy(); return a })
    expect(alert).toHaveTextContent('Check your recent sign-in activity')
    expect(alert).toHaveTextContent('2 failed sign-in attempts in this period')
    expect(alert).toHaveTextContent('192.0.2.77')
    expect(alert).toHaveTextContent('1 attempt was blocked')
    expect(alert).toHaveTextContent('Signed in from a network you had not used before: 198.51.100.7')
    expect(alert).toHaveTextContent('change your password straight away')
    fireEvent.click(within(alert).getByRole('button', { name: 'Show failed sign-ins' }))
    await waitFor(() => expect(lastList()).toMatchObject({ eventType: 'LOGIN_FAILED' }))
  })

  it('temiz veride güvenlik uyarısı YOK; boş aralıkta durum bloğu "son 30 günü göster" önerir', async () => {
    serve(fixture().filter((e) => e.event_type === 'LOGIN' && !e.anomaly_flags).concat())
    render(<MyAuditLog />)
    await waitFor(() => expect(document.querySelector('[data-slot="stats-panel"]')).toBeTruthy())
    expect(document.querySelector('[data-slot="alert"]')).toBeNull()

    serve([])
    fireEvent.click(screen.getByRole('button', { name: '7 days' }))
    const empty = await waitFor(() => { const e = document.querySelector('[data-slot="empty"]'); expect(e).toBeTruthy(); return e })
    expect(empty).toHaveTextContent('No activity in this period')
    fireEvent.click(within(empty).getByRole('button', { name: /Show the last 30 days/ }))
    await waitFor(() => expect(Math.abs(Date.parse(lastList().since + 'Z') - (Date.now() - 30 * DAY))).toBeLessThan(60_000))
  })

  it('satır açılır: tam zaman, IP kopyala, ham tarayıcı bilgisi, alan farkı tablosu, kayıt bağlantısı', async () => {
    render(<MyAuditLog />)
    await screen.findAllByText('Signed in from Chrome on Windows')
    const row = within(timeline()).getByText('Monitor updated').closest('[data-slot="activity-entry"]')
    const trigger = within(row).getByRole('button', { expanded: false })
    fireEvent.click(trigger)
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    const detail = within(row).getByText('When').closest('[data-slot="activity-detail"]')
    expect(within(detail).getByText('MONITOR_UPDATE')).toBeInTheDocument()
    expect(within(detail).getByRole('button', { name: 'Copy IP address' })).toBeInTheDocument()
    expect(within(detail).getByText(WIN)).toBeInTheDocument()
    expect(within(detail).getByText('HTTP_MONITOR · 12')).toBeInTheDocument()
    expect(within(detail).getByRole('button', { name: '12 — Open' })).toBeInTheDocument()
    // Alan farkı (DiffTable)
    expect(within(detail).getByText('interval_seconds')).toHaveAttribute('data-diff', 'field')
    expect(within(detail).getByText('300')).toHaveAttribute('data-diff', 'from')
    expect(within(detail).getByText('60')).toHaveAttribute('data-diff', 'to')
    // Giriş olmayan satırda "ben yapmadım" yok; tekrar tıklayınca kapanır
    expect(within(detail).queryByRole('button', { name: /This was not me/ })).toBeNull()
    fireEvent.click(trigger)
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
  })

  it('"Bu girişi ben yapmadım": onay → bildirim ucu satır id ile → kalıcı girişleri iptal önerisi', async () => {
    api.me.reportSuspiciousLogin.mockResolvedValue({ success: true, ref: 'R-7' })
    render(<MyAuditLog />)
    const row = (await screen.findByText('Sign-in attempt blocked — too many tries')).closest('[data-slot="activity-entry"]')
    fireEvent.click(within(row).getByRole('button', { expanded: false }))
    fireEvent.click(within(row).getByRole('button', { name: /This was not me/ }))
    // useDialog onayı: rol yalnız `alert` tipinde alertdialog, onay penceresi `dialog` (ui/Dialog.jsx)
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveTextContent('Report this sign-in?')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Report' }))
    await waitFor(() => expect(api.me.reportSuspiciousLogin).toHaveBeenCalledWith(103))
    const next = await screen.findByText('Protect your account?')
    expect(next.closest('[role="dialog"]')).toHaveTextContent('R-7')
    fireEvent.click(within(next.closest('[role="dialog"]')).getByRole('button', { name: 'Not now' }))
    expect(api.me.logoutOtherDevices).not.toHaveBeenCalled()
  })

  it('sayfa içi arama: yalnız görünen sayfayı süzer, kapsamı söyler; eşleşme yoksa dürüst boş durum', async () => {
    render(<MyAuditLog />)
    await screen.findAllByText('Signed in from Chrome on Windows')
    fireEvent.change(screen.getByRole('searchbox', { name: 'Find on this page' }), { target: { value: '192.0.2' } })
    expect(entries()).toHaveLength(2)
    expect(screen.getByText('2 of 8 events on this page match')).toBeInTheDocument()
    expect(listCalls()).toHaveLength(1)   // sunucuya gitmez
    fireEvent.change(screen.getByRole('searchbox', { name: 'Find on this page' }), { target: { value: 'yok-boyle-bir-sey' } })
    const empty = document.querySelector('[data-slot="empty"]')
    expect(empty).toHaveTextContent('Nothing on this page matches “yok-boyle-bir-sey”')
    expect(empty).toHaveTextContent('only looks through the 8 events on this page')
    fireEvent.click(within(empty).getByRole('button', { name: /Clear search/ }))
    expect(entries()).toHaveLength(8)
  })

  it('sayfalama standardı: sonraki sayfa page=1; süzgeç değişince başa döner (page=0)', async () => {
    const many = Array.from({ length: 130 }, (_, i) => ev(i * HOUR, 'LOGIN', { id: 1000 + i, ip_address: `203.0.113.${i % 250}` }))
    serve(many)
    render(<MyAuditLog />)
    await waitFor(() => expect(entries()).toHaveLength(50))
    fireEvent.click(screen.getByRole('button', { name: /^(Sonraki|Next)$/ }))
    await waitFor(() => expect(lastList()).toMatchObject({ page: 1 }))
    fireEvent.click(screen.getByRole('button', { name: '7 days' }))
    await waitFor(() => expect(lastList()).toMatchObject({ page: 0 }))
  })

  it('özet, aralık örneklemi aşınca kesin sayıları süzgeçli çekimlerin toplamından alır (sahte sayı yok)', async () => {
    const many = Array.from({ length: 260 }, (_, i) => ev(i * HOUR, i % 40 === 0 ? 'LOGIN_FAILED' : 'LOGIN',
      { id: 2000 + i, ...(i % 40 === 0 ? { outcome: 'FAILURE' } : {}) }))
    serve(many)
    render(<MyAuditLog />)
    const panel = await waitFor(() => { const p = document.querySelector('[data-slot="stats-panel"]'); expect(p).toBeTruthy(); return p })
    const value = (label) => within(within(panel).getByRole('button', { name: new RegExp(label) })).getByText((_, el) => el?.getAttribute('data-slot') === 'stat-value').textContent
    await waitFor(() => expect(value('All activity')).toBe('260'))
    expect(value('Sign-ins')).toBe('253')
    expect(value('Failed sign-ins')).toBe('7')
    const summaryCalls = api.me.getMyAudit.mock.calls.map((c) => c[0]).filter((p) => !isList(p))
    expect(summaryCalls.some((p) => p.eventType === 'LOGIN' && p.size === 200)).toBe(true)
    expect(summaryCalls.some((p) => p.eventType === 'LOGIN_FAILED')).toBe(true)
    expect(summaryCalls.some((p) => p.outcome === 'BLOCKED')).toBe(true)
    // Tür dağılımı kartı kısmi örneklemi söyler
    expect(screen.getByText('Based on your latest 200 of 260 events.')).toBeInTheDocument()
  })

  it('push tercihi: anahtar API geri çağrısını tetikler; "Cihazlarım" Cihaz Geçmişi panelini yan panelde açar', async () => {
    const onPush = vi.fn()
    render(<MyAuditLog pushOptOut={false} onPushOptOutChange={onPush} onChangePassword={vi.fn()} />)
    await screen.findAllByText('Signed in from Chrome on Windows')
    const sw = screen.getByRole('switch', { name: 'I do not want webhook push notifications' })
    expect(sw).not.toBeChecked()
    fireEvent.click(sw)
    expect(onPush).toHaveBeenCalledWith(true)

    expect(api.me.getMyDevices).not.toHaveBeenCalled()   // panel tembel: yalnız açılınca çeker
    fireEvent.click(screen.getByRole('button', { name: 'My devices' }))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByText('Device history')).toBeInTheDocument()
    await waitFor(() => expect(api.me.getMyDevices).toHaveBeenCalled())
    fireEvent.click(within(dlg).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('loginInfo verilince giriş özeti (önceki giriş + o zamandan beri başarısız deneme); verilmezse sayfa yine çalışır', async () => {
    const { unmount } = render(<MyAuditLog loginInfo={{
      prev_login_at: '2026-08-10T09:00:00', prev_login_ip: '203.0.113.9', prev_login_method: 'PASSWORD',
      failed_before_login: 2, last_failed_at: '2026-08-11T10:00:00', last_failed_ip: '192.0.2.8',
      last_failed_reason: 'BAD_PASSWORD', current_login_at: '2026-08-12T11:00:00', first_login: false,
    }} />)
    const sum = document.querySelector('[data-slot="sign-in-summary"]')
    expect(sum).toHaveTextContent('My Sign-in Info')
    expect(sum).toHaveTextContent('2026-08-10T09:00:00')
    expect(sum).toHaveTextContent('Password')
    expect(within(sum).getByText('2 failed attempts since then')).toBeInTheDocument()
    expect(sum).toHaveTextContent('Wrong password')
    unmount()
    render(<MyAuditLog />)
    await screen.findAllByText('Signed in from Chrome on Windows')
    expect(document.querySelector('[data-slot="sign-in-summary"]')).toBeNull()
  })

  it('liste hatası: uyarı + "Tekrar dene" yeniden çeker', async () => {
    api.me.getMyAudit.mockResolvedValue({ success: false, error: 'boom' })
    render(<MyAuditLog />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Your activity could not be loaded.')
    serve(fixture())
    fireEvent.click(within(alert).getByRole('button', { name: /Try again/ }))
    expect(await screen.findAllByText('Signed in from Chrome on Windows')).toHaveLength(2)
  })

  it('telefon: tür/sonuç/arama "Süzgeçler" alt panelinde; çizelge tek sütun aynı yapı', async () => {
    mobile.value = true
    render(<MyAuditLog />)
    await screen.findAllByText('Signed in from Chrome on Windows')
    expect(screen.queryByRole('combobox', { name: 'Filter by event type' })).toBeNull()
    expect(screen.queryByRole('searchbox')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Filters' }))
    const sheet = await screen.findByRole('dialog', { name: 'Filters' })
    expect(within(sheet).getByRole('combobox', { name: 'Filter by event type' })).toBeInTheDocument()
    expect(within(sheet).getByRole('searchbox', { name: 'Find on this page' })).toBeInTheDocument()
    fireEvent.click(within(sheet).getByRole('button', { name: 'Blocked' }))
    await waitFor(() => expect(lastList()).toMatchObject({ outcome: 'BLOCKED' }))
    fireEvent.click(within(sheet).getByRole('button', { name: 'Show results' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.getByRole('button', { name: 'Filters (1)' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remove filter: Outcome: Blocked' })).toBeInTheDocument()
    await waitFor(() => expect(entries()).toHaveLength(1))
  })
})
