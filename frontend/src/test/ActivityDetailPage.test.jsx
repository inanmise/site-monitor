import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', async (importOriginal) => {
  const mod = await importOriginal()
  return {
    ...mod,
    api: withApiFallback({
      getActivityFullDetail: vi.fn(),
      getActivityDetail: vi.fn(),
      admin: { getAlert: vi.fn(), getAlertNotifications: vi.fn(), getAlertPushDeliveries: vi.fn(), getAlertStormPush: vi.fn() },
      monitoring: { getChangeDetail: vi.fn() },
    }),
  }
})
// Kök neden paneli fe2'nin — burada yalnız YERLEŞİMİ sınanır (hangi aktivite için, hangi durumda çizildiği)
vi.mock('../components/activity/related/RelatedActivityPanel.jsx', () => ({
  default: ({ activityId }) => <div data-testid="related-panel">related:{activityId}</div>,
}))
// Tanılama penceresi ağır; yalnız açıldığı ve hangi izlemeyle açıldığı sınanır
vi.mock('../components/http/diagnose/HttpDiagnoseDialog.jsx', () => ({
  default: ({ monitor, onClose }) => <div role="dialog" aria-label="http-diag">diag:{monitor?.id}:{monitor?.url}<button type="button" onClick={onClose}>kapat</button></div>,
}))

import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import ActivityDetailPage from '../components/activity/detail/ActivityDetailPage.jsx'

/**
 * Aktivite ayrıntı sayfası (2026-11-05, act fe1): sözleşmedeki `/detail` yanıtıyla tüm bölümler, yaklaşık eşleşme,
 * yetkisiz alarm, kontrol satırı yokken aktiviteden teşhis, 404 → bulunamadı, eski sunucu → temel görünüm, okuma hatası →
 * tekrar dene, değişiklik farkı, geç yanıt koruması, bağlantılar, tanılama. Kayıt adları sahte.
 */
const LONG = 'Ödeme Sistemleri Entegrasyon Sağlık Kontrolü — Üretim Ortamı (Bölge 2 / Yedek Hat) ağ geçidi'

const base = {
  id: 42, activity_time: '2026-11-05T10:00:00', monitor_type: 'PING', monitor_id: 9, monitor_name: LONG,
  target: '10.0.0.1', action: 'SCHEDULED_CHECK', result_status: 'ERROR', result_summary: 'zaman aşımı',
  error_message: 'Request timed out', error_class: 'NETWORK', team_id: 1, team_name: 'Ağ Ekibi', response_ms: null,
  failure_reason: 'ICMP_NO_REPLY', failure_detail: '{"phase":"NETWORK","target":"10.0.0.1"}', alert_event_id: 5,
  check_id: 7, http_status: null, request_id: null,
}

function fullBody(over = {}) {
  return {
    success: true,
    data: { ...base },
    check: { type: 'ping', match: 'exact', row: { id: 7, up: false, failure_reason: 'ICMP_NO_REPLY', rtt_ms: null, checked_at: '2026-11-05T10:00:00', error: 'Request timed out' } },
    previous_check: { id: 6, up: true, rtt_ms: 12, checked_at: '2026-11-05T09:55:00', activity_id: 41 },
    next_check: { id: 8, up: true, rtt_ms: 14, checked_at: '2026-11-05T10:05:00' },
    series: {
      from: '2026-11-04T10:00:00', to: '2026-11-06T10:00:00', truncated: false,
      points: [
        { t: '2026-11-05T09:55:00', ms: 12, status: 'SUCCESS' },
        { t: '2026-11-05T10:00:00', ms: null, status: 'ERROR' },
        { t: '2026-11-05T10:05:00', ms: 14, status: 'SUCCESS' },
      ],
    },
    alarm: { id: 5, alert_type: 'PING_DOWN', level: 'CRITICAL', opened_at: '2026-11-05T10:01:30', resolved_at: '2026-11-05T10:06:00',
      resolved_by_kind: 'auto', acknowledged: false, storm_id: null, match: 'exact', can_open: true },
    change: null,
    links: {
      monitor: { tab: 'ping', params: { monitor: 9 } }, history: { tab: 'ping', params: { monitor: 9, mtab: 'control' } },
      changes: { tab: 'ping', params: { monitor: 9, mtab: 'changes' } }, alert: { tab: 'alerthistory', params: { alert: 5 } },
      can_diagnose: false,
    },
    actor: { name: 'scheduler', request_id: null },
    ...over,
  }
}

const ALERT = { id: 5, alert_type: 'PING_DOWN', alert_level: 'CRITICAL', domain: '10.0.0.1', created_at: '2026-11-05T10:01:30',
  resolved: true, resolved_at: '2026-11-05T10:06:00', resolved_by: 'Sistem (otomatik)', acknowledged: false }

function setupAlertMocks() {
  api.admin.getAlert.mockResolvedValue({ success: true, data: ALERT })
  api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [
    { id: 100, trigger: 'INITIAL', recipient_name: 'Ağ Ekibi', recipient_email: 'ag@example.com', email_status: 'SENT', sent_at: '2026-11-05T10:01:35' },
  ] })
  api.admin.getAlertPushDeliveries.mockResolvedValue({ success: true, data: [] })
  api.admin.getAlertStormPush.mockResolvedValue({ success: true, data: { items: [] } })
}

function renderPage(props = {}) {
  const handlers = { onBack: vi.fn(), onOpenActivity: vi.fn(), onOpenTimeline: vi.fn() }
  const utils = render(<ActivityDetailPage id={42} {...handlers} {...props} />)
  return { ...utils, ...handlers }
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')   // metinler Türkçe sözlükle sınanır
  setupAlertMocks()
  api.monitoring.getChangeDetail.mockResolvedValue({ success: false })
})
afterEach(() => { localStorage.removeItem(LANG_STORAGE_KEY) })

describe('ActivityDetailPage — tam ayrıntı', () => {
  it('başlık, kontrol teşhisi, kıyas, seri, alarm zinciri ve bağlantılar', async () => {
    api.getActivityFullDetail.mockResolvedValue(fullBody())
    const onTitle = vi.fn()
    const { onOpenActivity, onOpenTimeline } = renderPage({ onTitle })

    const heading = await screen.findByRole('heading', { level: 2, name: LONG })
    expect(onTitle).toHaveBeenLastCalledWith(LONG)   // belge başlığı rota sahibine bildirilir
    await waitFor(() => expect(heading).toHaveFocus())   // sayfa açılınca odak başlıkta
    expect(document.querySelector('[data-slot="act-dt-type"]')).toHaveAttribute('data-type', 'PING')
    expect(document.querySelector('[data-slot="act-dt-status"]')).toHaveAttribute('data-status', 'ERROR')
    expect(screen.getByText('Zamanlayıcı (otomatik)')).toBeInTheDocument()
    expect(within(document.querySelector('[data-slot="act-dt-header"]')).getByText(/13:00:00/)).toBeInTheDocument()   // 10:00 UTC → 13:00 İstanbul

    // Kontrol sonucu: kesin eşleşme + türün teşhis paneli
    const check = document.querySelector('[data-slot="act-dt-check"]')
    expect(check).toHaveAttribute('data-match', 'exact')
    expect(check).toHaveAttribute('data-health', 'down')
    expect(check.querySelector('[data-slot="chkfail-panel"]')).toHaveAttribute('data-code', 'ICMP_NO_REPLY')

    // Kıyas: sorun bu kontrolde başladı, sonraki kontrolde düzeldi
    const kinds = [...document.querySelectorAll('[data-slot="act-dt-transition"]')].map((e) => e.getAttribute('data-kind'))
    expect(kinds).toEqual(['started', 'after-recovered'])
    const prevCell = document.querySelector('[data-slot="act-dt-cmp-cell"][data-key="prev"]')
    fireEvent.click(within(prevCell).getByRole('button', { name: /Önceki kontrol — Bu aktiviteyi aç/ }))
    expect(onOpenActivity).toHaveBeenCalledWith(41)
    // Sonraki hücre aktivite kimliği taşımıyor → kontrol geçmişi bağlantısı (gerçek href)
    const nextCell = document.querySelector('[data-slot="act-dt-cmp-cell"][data-key="next"]')
    expect(within(nextCell).getByRole('link', { name: /Sonraki kontrol — Kontrol geçmişinde gör/ }))
      .toHaveAttribute('href', expect.stringContaining('mtab=control'))

    // Seri
    expect(document.querySelector('[data-slot="act-dt-series"]')).toHaveAttribute('data-state', 'ready')
    expect(screen.getByText('1 hatalı')).toBeInTheDocument()

    // Alarm: kesin, zincir okunur (var olan uçlar)
    const alarm = document.querySelector('[data-slot="act-dt-alarm"]')
    expect(alarm).toHaveAttribute('data-state', 'linked')
    expect(within(alarm).getByText('Otomatik (izleme düzeldi)')).toBeInTheDocument()
    await waitFor(() => expect(document.querySelectorAll('[data-slot="act-dt-alarm-event"]').length).toBeGreaterThanOrEqual(3))
    const kindsAl = [...document.querySelectorAll('[data-slot="act-dt-alarm-event"]')].map((e) => e.getAttribute('data-kind'))
    expect(kindsAl).toEqual(['opened', 'mail', 'resolved'])
    expect(api.admin.getAlert).toHaveBeenCalledWith(5)

    // Bağlantılar: gerçek href, düz tık uygulama içi geçiş
    const actions = document.querySelector('[data-slot="act-dt-actions"]')
    const monitorLink = within(actions).getByRole('link', { name: 'İzleme ayrıntısı' })
    expect(monitorLink).toHaveAttribute('href', expect.stringMatching(/\?tab=ping&monitor=9$/))
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    fireEvent.click(monitorLink)
    window.removeEventListener('sm:navigate', nav)
    expect(nav).toHaveBeenCalledTimes(1)
    expect(nav.mock.calls[0][0].detail).toEqual({ tab: 'ping', params: { monitor: 9 } })
    fireEvent.click(within(actions).getByRole('button', { name: 'Bu izlemenin zaman çizelgesi' }))
    expect(onOpenTimeline).toHaveBeenCalledWith('PING', 9)
    // Tanılama kapısı kapalı → düğme yok
    expect(within(actions).queryByRole('button', { name: 'Uçtan uca tanıla' })).toBeNull()
    // Kök neden paneli hatalı kontrolde yerleşir
    expect(screen.getByTestId('related-panel')).toHaveTextContent('related:42')
  })

  it('yaklaşık eşleşme + yetkisiz alarm: rozetler, alarm zinciri OKUNMAZ', async () => {
    api.getActivityFullDetail.mockResolvedValue(fullBody({
      check: { type: 'ping', match: 'approximate', row: { id: 7, up: false, failure_reason: 'ICMP_NO_REPLY' } },
      alarm: { id: 5, alert_type: 'PING_DOWN', level: 'HIGH', opened_at: '2026-11-05T10:01:30', resolved_at: null, match: 'approximate', can_open: false },
      actor: { name: 'ayse.yilmaz', request_id: 'req-7f3a9c' },
    }))
    renderPage()
    await screen.findByRole('heading', { level: 2, name: LONG })
    expect(document.querySelector('[data-slot="act-dt-check"]')).toHaveAttribute('data-match', 'approximate')
    expect(document.querySelectorAll('[data-slot="act-dt-approx"]').length).toBeGreaterThanOrEqual(3)  // başlık + kontrol + alarm
    expect(document.querySelector('[data-slot="act-dt-alarm-locked"]')).toBeInTheDocument()
    expect(screen.getByText('Hâlâ açık')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Alarm #5 ayrıntısını aç/ })).toBeNull()
    expect(api.admin.getAlert).not.toHaveBeenCalled()
    expect(api.admin.getAlertNotifications).not.toHaveBeenCalled()
    // Kişi + istek kimliği (kopyala)
    expect(screen.getByText('ayse.yilmaz')).toBeInTheDocument()
    expect(within(document.querySelector('[data-slot="act-dt-request-id"]')).getByText('req-7f3a9c')).toBeInTheDocument()
  })

  it('kontrol satırı yok + hatalı aktivite: teşhis aktivitenin kendi alanlarından, notla', async () => {
    api.getActivityFullDetail.mockResolvedValue(fullBody({ check: null, previous_check: null, next_check: null, alarm: null }))
    renderPage()
    await screen.findByRole('heading', { level: 2, name: LONG })
    const check = document.querySelector('[data-slot="act-dt-check"]')
    expect(check).toHaveAttribute('data-match', 'none')
    expect(within(check).getByText(/teşhis aktivite kaydında saklanan hata nedeninden/)).toBeInTheDocument()
    expect(check.querySelector('[data-slot="chkfail-panel"]')).toHaveAttribute('data-code', 'ICMP_NO_REPLY')
    // Komşu yok → kıyas bölümü yok; alarm yok ama hatalı kontrol → nötr not (asla "sorun yok" değil)
    expect(document.querySelector('[data-slot="act-dt-compare"]')).toBeNull()
    expect(document.querySelector('[data-slot="act-dt-alarm"]')).toHaveAttribute('data-state', 'none')
  })

  it('başarılı kontrol: ölçüler, kök neden paneli ve "alarm yok" notu çizilmez', async () => {
    api.getActivityFullDetail.mockResolvedValue(fullBody({
      data: { ...base, monitor_type: 'HTTP', target: 'https://odeme.example.com/health', result_status: 'SUCCESS', result_summary: '200 · 120ms',
        failure_reason: null, failure_detail: null, response_ms: 120, http_status: 200, alert_event_id: null },
      check: { type: 'http', match: 'exact', row: { id: 3, ok: true, http_status: 200, response_ms: 120 } },
      previous_check: null, next_check: null, alarm: null,
    }))
    renderPage()
    await screen.findByRole('heading', { level: 2, name: LONG })
    const measures = document.querySelector('[data-slot="act-dt-measures"]')
    expect(within(measures).getByText('HTTP 200')).toBeInTheDocument()
    expect(within(measures).getByText('120 ms')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="act-dt-check"]')).toHaveAttribute('data-health', 'up')
    expect(screen.queryByTestId('related-panel')).toBeNull()
    expect(document.querySelector('[data-slot="act-dt-alarm"]')).toBeNull()
  })

  it('bilinmeyen sağlık asla yeşil değil', async () => {
    api.getActivityFullDetail.mockResolvedValue(fullBody({
      data: { ...base, result_status: 'UNKNOWN', failure_reason: null },
      check: { type: 'ping', match: 'exact', row: { id: 7 } }, previous_check: null, next_check: null, alarm: null,
    }))
    renderPage()
    await screen.findByRole('heading', { level: 2, name: LONG })
    const h = document.querySelector('[data-slot="act-dt-check"] [data-slot="act-dt-health"]')
    expect(h).toHaveAttribute('data-health', 'unknown')
    expect(h).toHaveAttribute('data-tone', 'muted')
  })
})

describe('ActivityDetailPage — durumlar', () => {
  it('404 + temel uç da 404 → "bulunamadı ya da yetkiniz yok" + geri', async () => {
    api.getActivityFullDetail.mockResolvedValue({ success: false, status: 404, error: 'not_found' })
    api.getActivityDetail.mockResolvedValue({ success: false, error: 'not_found' })
    const onTitle = vi.fn()
    const { onBack } = renderPage({ onTitle })
    expect(await screen.findByText('Aktivite bulunamadı ya da görme yetkiniz yok')).toBeInTheDocument()
    expect(onTitle).toHaveBeenLastCalledWith(null)
    const backs = screen.getAllByRole('button', { name: /Aktivite listesine dön/ })
    fireEvent.click(backs[backs.length - 1])
    expect(onBack).toHaveBeenCalled()
  })

  it('eski sunucu (/detail yok, temel uç 200) → temel görünüm + mini geçmiş; satır o aktiviteyi açar', async () => {
    api.getActivityFullDetail.mockResolvedValue({ success: false, status: 405 })
    api.getActivityDetail.mockResolvedValue({ success: true, data: { ...base }, recent: [
      { id: 40, result_status: 'SUCCESS', result_summary: '0% kayıp · 2ms', activity_time: '2026-11-05T09:50:00' },
    ] })
    const { onOpenActivity } = renderPage()
    await screen.findByRole('heading', { level: 2, name: LONG })
    expect(screen.getByText('Yalnız temel bilgiler')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="act-dt-legacy"]')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="act-dt-check"]')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /0% kayıp · 2ms/ }))
    expect(onOpenActivity).toHaveBeenCalledWith(40)
    // Bağlantılar satırdan türetilir (geçmiş = mtab=control)
    expect(screen.getByRole('link', { name: 'Kontrol geçmişi' })).toHaveAttribute('href', expect.stringContaining('mtab=control'))
  })

  it('okuma hatası → kırmızı blok + tekrar dene; ikinci deneme başarılı', async () => {
    api.getActivityFullDetail.mockRejectedValueOnce(new Error('Sunucuya ulaşılamadı.'))
      .mockResolvedValueOnce(fullBody())
    renderPage()
    const block = await screen.findByRole('alert')
    expect(block).toHaveAttribute('data-tone', 'danger')
    expect(within(block).getByText('Aktivite ayrıntısı okunamadı')).toBeInTheDocument()
    fireEvent.click(within(block).getByRole('button', { name: /Yeniden dene/ }))
    expect(await screen.findByRole('heading', { level: 2, name: LONG })).toBeInTheDocument()
  })

  it('sessiz tazeleme hatası eldeki ayrıntıyı silmez — uyarı bandı', async () => {
    api.getActivityFullDetail.mockResolvedValueOnce(fullBody()).mockResolvedValueOnce({ success: false, status: 503, error: 'down' })
    renderPage()
    await screen.findByRole('heading', { level: 2, name: LONG })
    fireEvent.click(screen.getByRole('button', { name: 'Ayrıntıyı yenile' }))
    expect(await screen.findByText(/Ayrıntı yenilenemedi/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: LONG })).toBeInTheDocument()
  })

  it('hazır sayfadan başka aktiviteye geçiş: önce yükleniyor, sonra odak YENİ başlıkta', async () => {
    api.getActivityFullDetail.mockResolvedValueOnce(fullBody())
      .mockResolvedValueOnce(fullBody({ data: { ...base, id: 41, monitor_name: 'önceki aktivite' } }))
    const { rerender } = render(<ActivityDetailPage id={42} onBack={() => {}} />)
    const first = await screen.findByRole('heading', { level: 2, name: LONG })
    await waitFor(() => expect(first).toHaveFocus())
    rerender(<ActivityDetailPage id={41} onBack={() => {}} />)
    expect(screen.queryByRole('heading', { level: 2, name: LONG })).toBeNull()
    const next = await screen.findByRole('heading', { level: 2, name: 'önceki aktivite' })
    await waitFor(() => expect(next).toHaveFocus())
  })

  it('geç yanıt koruması: id değişince eski isteğin yanıtı uygulanmaz', async () => {
    let resolveOld
    api.getActivityFullDetail.mockImplementationOnce(() => new Promise((r) => { resolveOld = r }))
      .mockResolvedValueOnce(fullBody({ data: { ...base, id: 43, monitor_name: 'yeni izleme' } }))
    const { rerender } = render(<ActivityDetailPage id={42} onBack={() => {}} />)
    rerender(<ActivityDetailPage id={43} onBack={() => {}} />)
    // id değişti: eski durumun bir karesi bile yeni adres altında çizilmez
    expect(document.querySelector('[data-slot="act-detail-page"]')).toHaveAttribute('data-phase', 'loading')
    const fresh = await screen.findByRole('heading', { level: 2, name: 'yeni izleme' })
    await waitFor(() => expect(fresh).toHaveFocus())   // odak YENİ sayfanın başlığında (eski başlık değil)
    await act(async () => { resolveOld(fullBody()) })
    expect(screen.getByRole('heading', { level: 2, name: 'yeni izleme' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { level: 2, name: LONG })).toBeNull()
  })
})

describe('ActivityDetailPage — değişiklik ve tanılama', () => {
  const changeBody = (over = {}) => fullBody({
    data: { ...base, monitor_type: 'HTTP', monitor_id: 4, target: 'https://odeme.example.com/health', action: 'CONFIG_CHANGED',
      result_status: 'SUCCESS', result_summary: null, failure_reason: null, alert_event_id: null },
    check: null, previous_check: null, next_check: null, series: null, alarm: null,
    change: { kind: 'http', id: 4, seq: 3 },
    links: { monitor: { tab: 'http', params: { monitor: 4 } }, changes: { tab: 'http', params: { monitor: 4, mtab: 'changes' } }, can_diagnose: true },
    actor: { name: 'ayse.yilmaz', request_id: 'req-1' },
    ...over,
  })

  it('değişiklik: alan bazlı önce → sonra (var olan uç); kontrol bölümü yok', async () => {
    api.getActivityFullDetail.mockResolvedValue(changeBody())
    api.monitoring.getChangeDetail.mockResolvedValue({ success: true, data: {
      seq: 3, event_type: 'UPDATE', actor: 'ayse.yilmaz', at: '2026-11-05T10:00:00', note: 'Zaman aşımı artırıldı',
      changes: JSON.stringify({ timeoutMs: { from: 5000, to: 10000 } }), snapshot: JSON.stringify({ timeoutMs: 10000 }),
    } })
    renderPage()
    await screen.findByRole('heading', { level: 2, name: LONG })
    expect(document.querySelector('[data-slot="act-dt-check"]')).toBeNull()
    await waitFor(() => expect(document.querySelector('[data-slot="act-dt-change"]')).toHaveAttribute('data-state', 'ready'))
    expect(api.monitoring.getChangeDetail).toHaveBeenCalledWith('http', 4, 3)
    expect(screen.getByText('Zaman aşımı artırıldı')).toBeInTheDocument()
    expect(document.querySelector('[data-diff="from"]')).toBeInTheDocument()
    expect(document.querySelector('[data-diff="to"]')).toBeInTheDocument()
  })

  it('değişiklik okunamazsa "okunamadı" + tekrar dene (asla "değişiklik yok")', async () => {
    api.getActivityFullDetail.mockResolvedValue(changeBody())
    api.monitoring.getChangeDetail.mockResolvedValueOnce({ success: false, error: 'x' })
      .mockResolvedValueOnce({ success: true, data: { seq: 3, event_type: 'UPDATE', at: '2026-11-05T10:00:00', changes: '{}' } })
    renderPage()
    await screen.findByRole('heading', { level: 2, name: LONG })
    await waitFor(() => expect(document.querySelector('[data-slot="act-dt-change"]')).toHaveAttribute('data-state', 'error'))
    fireEvent.click(within(document.querySelector('[data-slot="act-dt-change"]')).getByRole('button', { name: /Yeniden dene/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="act-dt-change"]')).toHaveAttribute('data-state', 'ready'))
  })

  it('can_diagnose: HTTP tanılama penceresi izlemeyle açılır', async () => {
    api.getActivityFullDetail.mockResolvedValue(changeBody())
    renderPage()
    await screen.findByRole('heading', { level: 2, name: LONG })
    fireEvent.click(screen.getByRole('button', { name: 'Uçtan uca tanıla' }))
    const dlg = await screen.findByRole('dialog', { name: 'http-diag' })
    expect(dlg).toHaveTextContent('diag:4:https://odeme.example.com/health')
    fireEvent.click(within(dlg).getByRole('button', { name: 'kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'http-diag' })).toBeNull())
  })

  it('penceresi olmayan türde (sertifika) tanılama izleme ayrıntısına bağlanır', async () => {
    api.getActivityFullDetail.mockResolvedValue(changeBody({
      data: { ...base, monitor_type: 'CERT', monitor_id: 12, target: 'odeme.example.com', action: 'SCHEDULED_CHECK', result_status: 'SUCCESS',
        failure_reason: null, alert_event_id: null },
      change: null,
      links: { monitor: { tab: 'dashboard', params: { domain: 'odeme.example.com', open: 'cert' } }, can_diagnose: true },
    }))
    renderPage()
    await screen.findByRole('heading', { level: 2, name: LONG })
    expect(screen.queryByRole('button', { name: 'Uçtan uca tanıla' })).toBeNull()
    expect(screen.getByRole('link', { name: 'Tanılamayı izleme ayrıntısında aç' }))
      .toHaveAttribute('href', expect.stringContaining('tab=dashboard'))
  })
})
