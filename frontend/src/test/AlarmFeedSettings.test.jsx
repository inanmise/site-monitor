import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

// Ayarlar → Platform → Alarm akışı (2026-10-30): yükleme + durum (adres, örnekler, son çağrı, 24 sa kutucukları), bilinmeyen
// alanlar "—", akışı açmak onay ister, yalnız değişen alanlar kaydedilir, sunucu alan hatası alanın altında, istemci
// doğrulaması isteği durdurur, IP izin listesi, önizleme (satır açılır → BODY), çağrı günlüğü (süzgeç değişince eski satırlar
// hemen gider, geç yanıt atılır), ayrıntı paneli (girintili gövde / "saklanmadı"), CSV, okuma hatası + Tekrar dene, 403.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    alarmFeed: { get: vi.fn(), save: vi.fn(), preview: vi.fn(), requests: vi.fn(), request: vi.fn(), exportRequests: vi.fn() },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import AlarmFeedSettings from '../components/admin/alarmfeed/AlarmFeedSettings.jsx'

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const SETTINGS = {
  enabled: false, min_level: 'WARNING', team_ids: [3], types: [], resolved_minutes: 15, resolved_mode: 'status', clear_text: 'Clear',
  severity_critical: 'Critical', severity_high: 'Major', severity_warning: 'Warning', rci_mode: 'target_type', source: 'SiteMonitor',
  rcitype: '', mail_separator: 'semicolon', allowed_ips: ['10.0.0.0/8'], rate_per_minute: 30, max_rows: 2000, store_body: true,
}
const now = new Date()
const iso = (msAgo) => new Date(now.getTime() - msAgo).toISOString().slice(0, 19)
const STATUS = {
  last_call_at: iso(30_000), last_call_ip: '10.20.30.40', last_outcome: 'OK', calls_24h: 1440, ok_24h: 1430, refused_24h: 8,
  errors_24h: 2, distinct_ips_24h: 2, avg_duration_ms_24h: 84, last_row_count: 12, log_write_failures: 0, served_by: 'site-monitor-7c9d-xk2lq',
}
const data = (extra = {}) => ({
  endpoint: '/api/public/alarm-feed', base_url: 'https://sitemonitor.example.com', settings: SETTINGS,
  limits: { resolved_minutes: [1, 1440], rate_per_minute: [1, 600], max_rows: [100, 10000], text_max: 60, allowed_ips_max: 100 },
  log_retention_days: 90, teams: [{ id: 3, name: LONG_TEAM }, { id: 7, name: 'Ödeme Sistemleri' }],
  types: [{ key: 'cert', label: 'Sertifika' }, { key: 'http', label: 'HTTP' }, { key: 'ping', label: 'Ping' }],
  status: STATUS,
  callers: [{ ip: '10.20.30.40', first_seen_at: '2026-10-29T08:00:00', last_seen_at: iso(30_000), call_count: 1440,
    last_user_agent: 'python-requests/2.32.3', last_outcome: 'OK' }],
  ...extra,
})
const ROW = {
  CREATEDTIME: '2026-10-30 11:02:00', SEVERITY: 'Critical', RCI: 'api.example.com · SSL sertifika süresi', RCITYPE: 'SSL', CATEGORY: 'Sertifika',
  FILTERSOURCE: 'SiteMonitor', TEXT: 'Sertifika 3 gün içinde bitiyor', BODY: 'Satır 1\nSatır 2\nSatır 3', MAILS: 'a@example.com;b@example.com',
  STATUS: 'OPEN', RESOLVEDTIME: null, ALERTID: 501, TEAM: LONG_TEAM, UGTEAM: null, LINK: 'https://sitemonitor.example.com/?tab=alerthistory&alert=501',
}
const ITEMS = [
  { id: 11, requested_at: iso(30_000), client_ip: '10.20.30.40', remote_addr: '10.0.0.5', forwarded_for: '10.20.30.40, 10.0.0.5',
    user_agent: 'python-requests/2.32.3', method: 'GET', query: '', status: 200, outcome: 'OK', row_count: 12, open_count: 10, resolved_count: 2,
    duration_ms: 84, response_bytes: 2048, response_hash: 'sha256:ab12', served_by: 'site-monitor-7c9d', request_id: 'req-1', format: 'json', error: null },
  { id: 10, requested_at: iso(90_000), client_ip: '192.0.2.9', remote_addr: '10.0.0.5', forwarded_for: '192.0.2.9', user_agent: 'curl/8.4.0',
    method: 'GET', query: '', status: 403, outcome: 'IP_DENIED', row_count: null, open_count: null, resolved_count: null, duration_ms: 3,
    response_bytes: 120, response_hash: null, served_by: 'site-monitor-7c9d', request_id: 'req-0', format: 'json', error: 'IP izin listesinde değil' },
]
const ok = (d) => ({ success: true, data: d })
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.alarmFeed.get.mockResolvedValue(ok(data()))
  api.alarmFeed.requests.mockResolvedValue(ok({ items: ITEMS, total: 2, page: 0, size: 25 }))
  api.alarmFeed.preview.mockResolvedValue(ok({ columns: Object.keys(ROW), rows: [ROW], count: 1, open: 1, resolved: 0, truncated: false,
    generated_at: iso(0) }))
  api.alarmFeed.request.mockResolvedValue(ok({ ...ITEMS[0], body: '[{"ALERTID":501,"STATUS":"OPEN"}]', body_available: true,
    body_content_type: 'application/json' }))
  api.alarmFeed.exportRequests.mockResolvedValue({ success: true, name: 'x.csv' })
})

async function mount() {
  render(<AlarmFeedSettings />)
  await waitFor(() => expect(slot('af-root')).toHaveAttribute('data-state', 'ok'))
  await waitFor(() => expect(slots('af-req-row').length).toBeGreaterThan(0))
}
const saveBar = () => slot('settings-save-bar', slot('af-settings'))
const saveBtn = () => within(saveBar()).getByRole('button', { name: /^Kaydet$/ })

describe('AlarmFeedSettings — durum', () => {
  it('yükler: kapalı rozeti, tam adres + kopyala, curl / Python örnekleri, son çağrı, 24 sa kutucukları, çağıranlar', async () => {
    await mount()
    expect(slot('af-enabled')).toHaveAttribute('data-state', 'off')
    expect(slot('af-enabled')).toHaveTextContent('Kapalı')
    expect(slot('af-url')).toHaveTextContent('https://sitemonitor.example.com/api/public/alarm-feed')
    expect(screen.getByRole('button', { name: 'Adresi kopyala' })).toBeInTheDocument()
    expect(slot('af-sample-curl-code')).toHaveTextContent("curl -s 'https://sitemonitor.example.com/api/public/alarm-feed'")
    expect(slot('af-sample-python-code').textContent.split('\n')).toHaveLength(3)
    expect(slot('af-last-call-ip')).toHaveTextContent('10.20.30.40')
    expect(within(slot('af-last-call')).getByText('Başarılı')).toBeInTheDocument()
    expect(slot('af-tile-calls')).toHaveTextContent('1.440')
    expect(slot('af-tile-calls')).toHaveTextContent('1.430 başarılı')
    expect(slot('af-tile-refused')).toHaveTextContent('8')
    expect(slot('af-tile-duration')).toHaveTextContent('84 ms')
    expect(slot('af-served-by')).toHaveTextContent('site-monitor-7c9d-xk2lq')
    expect(slots('af-caller')).toHaveLength(1)
    // Kapalıyken bile sağlık yeşili yok; açık uç uyarısı bilgi tonunda
    expect(slot('af-status')).toHaveAttribute('data-tone', 'off')
  })

  it('bilinmeyen durum alanları "—" (asla 0 / yeşil); taban adres yoksa yalnız yol + Genel ayarlar bağlantısı', async () => {
    api.alarmFeed.get.mockResolvedValue(ok(data({
      base_url: null, settings: { ...SETTINGS, enabled: true },
      status: { last_call_at: null, last_call_ip: null, last_outcome: null, calls_24h: null, ok_24h: null, refused_24h: null,
        errors_24h: null, distinct_ips_24h: null, avg_duration_ms_24h: null, last_row_count: null, log_write_failures: null, served_by: null },
      callers: undefined,
    })))
    await mount()
    for (const s of ['af-tile-calls', 'af-tile-refused', 'af-tile-problems', 'af-tile-ips', 'af-tile-duration']) {
      expect(within(slot(s)).getByText('—')).toBeInTheDocument()
    }
    expect(slot('af-last-call-none')).toHaveTextContent('—')
    expect(slot('af-last-rows')).toHaveTextContent('—')
    expect(slot('af-warnings')).toBeNull()
    expect(slot('af-endpoint')).toHaveAttribute('data-full', 'false')
    expect(slot('af-url')).toHaveTextContent(/^\/api\/public\/alarm-feed$/)
    expect(slot('af-go-general')).toBeInTheDocument()
    expect(slot('af-sample-curl-code')).toHaveTextContent('https://<sitemonitor-adresi>/api/public/alarm-feed')
    expect(screen.getByText('Çağıran listesi okunamadı')).toBeInTheDocument()
    expect(slot('af-sample-python-code')).toHaveTextContent("'CREATEDTIME'")
    expect(slot('af-status').getAttribute('data-tone')).not.toBe('success')
  })

  it('açık + son çağrı 5 dk\'dan eski → "arayan sessiz"; günlük yazma hatası → uyarı; IP listesi boş → açık uç uyarısı', async () => {
    api.alarmFeed.get.mockResolvedValue(ok(data({
      settings: { ...SETTINGS, enabled: true, allowed_ips: [] },
      status: { ...STATUS, last_call_at: iso(10 * 60_000), log_write_failures: 3 },
    })))
    await mount()
    expect(slot('af-status')).toHaveAttribute('data-tone', 'warning')
    expect(slot('af-warning', slot('af-warnings')).getAttribute('data-key')).toBe('silent')
    expect(slots('af-warning').map((x) => x.getAttribute('data-key'))).toEqual(['silent', 'logFailures'])
    expect(slots('af-warning')[1]).toHaveTextContent('3 kez yazılamadı')
    expect(slot('af-public-note')).toHaveTextContent('IP izin listesi şu an BOŞ')
  })
})

describe('AlarmFeedSettings — istatistik okunamadı', () => {
  it('status.error → "okunamadı" uyarısı, kutucuklar "—"; takım dizini okunamazsa seçili takım "silinmiş" denmez', async () => {
    api.alarmFeed.get.mockResolvedValue(ok(data({
      settings: { ...SETTINGS, enabled: true }, teams: null,
      status: { error: 'DataAccessResourceFailureException', served_by: 'pod-a', log_write_failures: 0, last_call_at: null, last_call_ip: null,
        last_outcome: null, last_row_count: null, calls_24h: null, ok_24h: null, refused_24h: null, errors_24h: null, avg_duration_ms_24h: null,
        distinct_ips_24h: null },
    })))
    await mount()
    expect(slots('af-warning').map((x) => x.getAttribute('data-key'))).toEqual(['statsUnreadable'])
    expect(within(slot('af-tile-calls')).getByText('—')).toBeInTheDocument()
    expect(slot('af-f-teams-selected')).toHaveTextContent('Adı okunamayan takım')
    expect(slot('af-f-teams-selected')).not.toHaveTextContent('Silinmiş takım')
  })
})

describe('AlarmFeedSettings — ayarlar', () => {
  it('değişiklik yokken Kaydet kapalı; yalnız değişen alan gönderilir; kayıt sonrası temiz', async () => {
    await mount()
    expect(saveBtn()).toBeDisabled()
    api.alarmFeed.save.mockResolvedValue({ success: true, message: 'Alarm akışı ayarları kaydedildi.', data: data({ settings: { ...SETTINGS, source: 'SM-Prod' } }) })
    fireEvent.change(slot('af-f-source'), { target: { value: '  SM-Prod ' } })
    expect(saveBar()).toHaveAttribute('data-dirty', 'true')
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.alarmFeed.save).toHaveBeenCalledTimes(1))
    expect(api.alarmFeed.save).toHaveBeenCalledWith({ source: 'SM-Prod' })
    await waitFor(() => expect(saveBar()).not.toHaveAttribute('data-dirty', 'true'))
    expect(slot('af-f-source')).toHaveValue('SM-Prod')
  })

  it('akışı AÇMAK onay ister: Vazgeç → istek yok; onay → { enabled: true }', async () => {
    await mount()
    fireEvent.click(within(slot('af-f-enabled')).getByRole('switch'))
    fireEvent.click(saveBtn())
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveTextContent('https://sitemonitor.example.com/api/public/alarm-feed')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.alarmFeed.save).not.toHaveBeenCalled()
    api.alarmFeed.save.mockResolvedValue({ success: true, data: data({ settings: { ...SETTINGS, enabled: true } }) })
    fireEvent.click(saveBtn())
    const dlg2 = await screen.findByRole('dialog')
    fireEvent.click(within(dlg2).getByRole('button', { name: 'Kaydet ve aç' }))
    await waitFor(() => expect(api.alarmFeed.save).toHaveBeenCalledWith({ enabled: true }))
    await waitFor(() => expect(slot('af-enabled')).toHaveAttribute('data-state', 'on'))
  })

  it('sunucu alan hatası (400 VALIDATION_FAILED + field) alanın ALTINDA; tost yok', async () => {
    await mount()
    api.alarmFeed.save.mockResolvedValue({ success: false, status: 400, code: 'VALIDATION_FAILED', field: 'severity_high',
      error: 'SEVERITY metni yalnız harf, rakam, boşluk ve - _ içerebilir.' })
    fireEvent.change(slot('af-f-severity_high'), { target: { value: 'Major!!' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.alarmFeed.save).toHaveBeenCalledWith({ severity_high: 'Major!!' }))
    const field = await waitFor(() => {
      const f = document.querySelector('[data-field="severity_high"]')
      expect(f).toHaveTextContent('SEVERITY metni yalnız harf')
      return f
    })
    expect(within(field).getByRole('textbox')).toHaveAttribute('aria-invalid', 'true')
    // Alan düzenlenince hata silinir
    fireEvent.change(slot('af-f-severity_high'), { target: { value: 'Major' } })
    expect(document.querySelector('[data-field="severity_high"]')).not.toHaveTextContent('SEVERITY metni yalnız harf')
  })

  it('istemci doğrulaması: sınır dışı sayı → alanın altında, istek gitmez', async () => {
    await mount()
    fireEvent.change(slot('af-f-resolved_minutes'), { target: { value: '0' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(document.querySelector('[data-field="resolved_minutes"]')).toHaveTextContent('1 ile 1440 arasında bir tam sayı girin.'))
    expect(api.alarmFeed.save).not.toHaveBeenCalled()
  })

  it('IP izin listesi: geçersiz giriş alanın altında kalır, geçerli IPv6 eklenir ve kaydedilir', async () => {
    await mount()
    const box = slot('af-f-allowed_ips')
    const input = within(box).getByRole('textbox')
    fireEvent.change(input, { target: { value: 'bozuk-adres' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(document.querySelector('[data-field="allowed_ips"]')).toHaveTextContent('Geçersiz IP adresi ya da CIDR: bozuk-adres')
    fireEvent.change(input, { target: { value: '2001:DB8::/32' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(slots('token-chip', box).map((c) => c.getAttribute('data-value'))).toEqual(['10.0.0.0/8', '2001:db8::/32'])
    api.alarmFeed.save.mockResolvedValue({ success: true, data: data({ settings: { ...SETTINGS, allowed_ips: ['10.0.0.0/8', '2001:db8::/32'] } }) })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.alarmFeed.save).toHaveBeenCalledWith({ allowed_ips: ['10.0.0.0/8', '2001:db8::/32'] }))
  })

  it('tür seçimi + kapanış kipi: clear seçilince kapanış metni alanı çıkar; gövde yalnız değişenler', async () => {
    await mount()
    expect(slot('af-f-types-hint')).toHaveTextContent('tüm türler')
    fireEvent.click(within(slot('af-f-types')).getByRole('button', { name: 'HTTP' }))
    expect(slot('af-f-types-hint')).toHaveTextContent('1 türün')
    expect(slot('af-f-clear_text')).toBeNull()
    fireEvent.click(within(slot('af-f-resolved_mode')).getByRole('radio', { name: /SEVERITY = kapanış metni/ }))
    expect(slot('af-f-clear_text')).toHaveValue('Clear')
    api.alarmFeed.save.mockResolvedValue({ success: true, data: data({ settings: { ...SETTINGS, types: ['http'], resolved_mode: 'clear' } }) })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.alarmFeed.save).toHaveBeenCalledWith({ types: ['http'], resolved_mode: 'clear' }))
  })

  it('RCI değişince EVENTKEY notu uyarı tonuna geçer; saklama süresi salt okunur metin', async () => {
    await mount()
    expect(slot('af-rci-eventkey')).toHaveAttribute('data-changed', 'false')
    fireEvent.click(within(slot('af-f-rci_mode')).getByRole('radio', { name: /Yalnız hedef/ }))
    expect(slot('af-rci-eventkey')).toHaveAttribute('data-changed', 'true')
    expect(slot('af-retention')).toHaveTextContent('Çağrı günlüğü 90 gün saklanır.')
    // Seçici tetiği uzun takım adını kısaltır; seçilen takım altında TAM adla listelenir
    expect(slot('af-f-teams-selected')).toHaveTextContent(LONG_TEAM)
    expect(slot('af-go-retention')).toBeInTheDocument()
  })
})

describe('AlarmFeedSettings — önizleme', () => {
  it('"Şimdi ne dönerdi?" → sayılar + satır; satır açılınca BODY ve bütün kolonlar', async () => {
    await mount()
    expect(slot('af-pv-idle')).toBeInTheDocument()
    fireEvent.click(slot('af-preview-run'))
    await waitFor(() => expect(slots('af-pv-row')).toHaveLength(1))
    expect(slot('af-pv-count')).toHaveTextContent('1 satır')
    expect(slot('af-pv-open')).toHaveTextContent('1 açık')
    expect(slot('af-pv-list')).toHaveAttribute('data-layout', 'table')
    expect(slot('af-pv-body')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Satırın ayrıntısını göster: api.example.com · SSL sertifika süresi' }))
    const body = slot('af-pv-body')
    expect(body.textContent).toBe('Satır 1\nSatır 2\nSatır 3')
    expect(slots('af-pv-kv').map((k) => k.querySelector('dt').textContent)).toContain('ALERTID')
    expect(slot('af-pv-copy-json')).toHaveTextContent("JSON'u kopyala")
  })

  it('önizleme okunamazsa tehlike bloğu + Tekrar dene ("satır yok" değil)', async () => {
    api.alarmFeed.preview.mockResolvedValue({ success: false, status: 500, error: 'Önizleme üretilemedi: alarm tablosu okunamadı.' })
    await mount()
    fireEvent.click(slot('af-preview-run'))
    await waitFor(() => expect(slot('af-preview-retry')).not.toBeNull())
    expect(slot('af-preview')).toHaveTextContent('Önizleme üretilemedi: alarm tablosu okunamadı.')
    expect(slot('af-pv-empty')).toBeNull()
  })
})

describe('AlarmFeedSettings — çağrı günlüğü', () => {
  it('liste yüklenir; süzgeç değişince eski satırlar HEMEN gider, geç gelen eski yanıt atılır', async () => {
    await mount()
    expect(slots('af-req-row')).toHaveLength(2)
    const first = deferred()
    const second = deferred()
    api.alarmFeed.requests.mockReturnValueOnce(first.p).mockReturnValueOnce(second.p)
    fireEvent.change(slot('af-req-f-outcome'), { target: { value: 'OK' } })
    await waitFor(() => expect(slots('af-req-row')).toHaveLength(0))
    expect(api.alarmFeed.requests).toHaveBeenLastCalledWith(expect.objectContaining({ outcome: 'OK', page: 0 }))
    fireEvent.change(slot('af-req-f-outcome'), { target: { value: 'IP_DENIED' } })
    await waitFor(() => expect(api.alarmFeed.requests).toHaveBeenLastCalledWith(expect.objectContaining({ outcome: 'IP_DENIED' })))
    await act(async () => { second.resolve(ok({ items: [ITEMS[1]], total: 1, page: 0, size: 25 })) })
    await waitFor(() => expect(slots('af-req-row')).toHaveLength(1))
    // Eski (OK süzgeçli) yanıt geç gelir → atılır
    await act(async () => { first.resolve(ok({ items: [ITEMS[0]], total: 1, page: 0, size: 25 })) })
    expect(slots('af-req-row').map((r) => r.getAttribute('data-id'))).toEqual(['10'])
  })

  it('ayrıntı paneli: bütün alanlar, X-Forwarded-For zinciri, girintili JSON gövde; gövde yoksa "saklanmadı"', async () => {
    await mount()
    fireEvent.click(slots('af-req-open')[0])
    const sheet = await waitFor(() => { const s = slot('af-req-detail'); expect(s).not.toBeNull(); return s })
    await waitFor(() => expect(slot('af-req-body', sheet)).not.toBeNull())
    expect(slot('af-req-body', sheet)).toHaveAttribute('data-pretty', 'true')
    expect(slot('af-req-body-pre', sheet).textContent).toBe('[\n  {\n    "ALERTID": 501,\n    "STATUS": "OPEN"\n  }\n]')
    expect(slots('af-req-xff', sheet)[0].querySelectorAll('li')).toHaveLength(2)
    expect(sheet).toHaveTextContent('sha256:ab12')
    expect(sheet).toHaveTextContent('req-1')
    fireEvent.click(slot('af-req-detail-close'))
    await waitFor(() => expect(slot('af-req-detail')).toBeNull())

    api.alarmFeed.request.mockResolvedValue(ok({ ...ITEMS[1], body: null, body_available: false, body_content_type: null }))
    fireEvent.click(slots('af-req-open')[1])
    await waitFor(() => expect(slot('af-req-body-none')).not.toBeNull())
    expect(slot('af-req-body-none')).toHaveTextContent('Yanıt gövdesi saklanmadı')
    expect(slot('af-req-error')).toHaveTextContent('IP izin listesinde değil')
  })

  it('liste okunamazsa tehlike bloğu + Tekrar dene ("çağrı yok" değil); tekrar deneme yükler', async () => {
    api.alarmFeed.requests.mockResolvedValueOnce({ success: false, status: 500, error: 'Çağrı günlüğü okunamadı: veritabanı yanıt vermedi.' })
    render(<AlarmFeedSettings />)
    await waitFor(() => expect(slot('af-req-retry')).not.toBeNull())
    expect(slot('af-req-empty')).toBeNull()
    expect(slot('af-requests')).toHaveTextContent('veritabanı yanıt vermedi')
    fireEvent.click(slot('af-req-retry'))
    await waitFor(() => expect(slots('af-req-row')).toHaveLength(2))
  })

  it('CSV: aynı süzgeçlerle dışa aktarır, başarı tostu', async () => {
    await mount()
    fireEvent.change(slot('af-req-f-outcome'), { target: { value: 'RATE_LIMITED' } })
    fireEvent.click(slot('af-req-export'))
    await waitFor(() => expect(api.alarmFeed.exportRequests).toHaveBeenCalledWith(expect.objectContaining({ outcome: 'RATE_LIMITED' })))
    expect(await screen.findByText(/CSV olarak indirildi/)).toBeInTheDocument()
  })
})

describe('AlarmFeedSettings — okuma hatası ve 403', () => {
  it('ilk okuma başarısız → tehlike bloğu + Tekrar dene; tekrar deneme sayfayı açar', async () => {
    api.alarmFeed.get.mockResolvedValueOnce({ success: false, status: 503, error: 'Veritabanına ulaşılamıyor; birkaç saniye sonra tekrar deneyin.' })
    render(<AlarmFeedSettings />)
    await waitFor(() => expect(slot('af-root')).toHaveAttribute('data-state', 'failed'))
    expect(slot('af-root')).toHaveTextContent('Veritabanına ulaşılamıyor')
    fireEvent.click(slot('af-retry'))
    await waitFor(() => expect(slot('af-root')).toHaveAttribute('data-state', 'ok'))
  })

  it('403 → "yalnız global yönetici" bloğu; kırık ekran / Tekrar dene yok', async () => {
    api.alarmFeed.get.mockResolvedValue({ success: false, status: 403, code: 'FORBIDDEN', error: 'Bu işlem için yetkiniz yok.' })
    render(<AlarmFeedSettings />)
    await waitFor(() => expect(slot('af-root')).toHaveAttribute('data-state', 'forbidden'))
    expect(screen.getByText('Bu bölüm yalnız global yöneticiye açık')).toBeInTheDocument()
    expect(slot('af-retry')).toBeNull()
    expect(slot('af-settings')).toBeNull()
  })
})
