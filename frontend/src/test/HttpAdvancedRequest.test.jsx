import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, fillGroupAndTags } from './test-utils.jsx'
import HttpMonitorPage from '../components/HttpMonitorPage.jsx'
import HttpMonitorCard from '../components/http/HttpMonitorCard.jsx'
import { httpFailureReason, requestChips } from '../components/http/httpCardModel.js'
import {
  ADV_EMPTY, advFormFrom, headerError, httpAdvancedPayload, httpAdvancedTestPayload, isValidJsonPath, validateHttpAdvanced,
} from '../components/http/httpAdvancedModel.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:    (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups:        vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults:   vi.fn(() => Promise.resolve({ success: true, data: { http: {} } })),
      getHttpMonitors:   vi.fn(),
      getCheckHistory:   vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      createHttpMonitor: vi.fn(),
      updateHttpMonitor: vi.fn(),
      deleteHttpMonitor: vi.fn(),
      triggerHttpCheck:  vi.fn(),
      testHttp:          vi.fn(),
    },
    admin: { getTeams: vi.fn(), getAlerts: vi.fn() },
  }),
}))
import { api } from '../api/client'

/**
 * HTTP izlemesi — "Gelişmiş istek" bölümü (2026-10-01, onaylı öneri 9): başlık / Basic auth / POST gövdesi / JSON
 * doğrulaması / yavaş yanıt alarmı. Kilit: bölüm VARSAYILAN KAPALI ve dokunulmayan izlemenin kayıt yükü DEĞİŞMEZ.
 */

// Bugünkü (2026-10-01 öncesi) oluşturma yükünün anahtarları — yeni bir anahtar sızarsa bu test kırılır.
const LEGACY_CREATE_KEYS = ['name', 'url', 'method', 'expectedStatus', 'followRedirects', 'verifySsl', 'useProxy',
  'groupName', 'teamId', 'tags', 'notificationGroupId', 'notifyEmail', 'alertLevel', 'notifyWebhook',
  'checkSslErrors', 'sslExpiryReminders', 'domainExpiryReminders', 'sslReminderDays', 'domainReminderDays',
  'intervalSeconds', 'timeoutMs', 'confirmAttempts', 'confirmIntervalSeconds', 'recoveryChecks', 'recoveryIntervalSeconds',
  'active', 'nocNotify', 'nocGroupIds'].sort()

const stored = {
  id: 1, name: 'Ödeme API', url: 'https://api.example.com/health', method: 'GET', expected_status: '200-399',
  group_name: 'Kurumsal', tags: 'prod', team_id: 5, team_name: 'SY-A', status: 'up', http_status: 200, response_ms: 120,
  interval_seconds: 300, timeout_ms: 10000, active: true, checked_at: '2026-10-01T08:00:00',
  basic_auth_user: 'izleme', has_basic_auth_pass: true, has_custom_headers: true, custom_header_names: ['X-Api-Key'],
  slow_response_enabled: false, slow_threshold_ms: 3000, json_path: null, json_expected: null,
}

const advTrigger = () => screen.getByRole('button', { name: /gelişmiş istek|advanced request/i })
const saveBtn = () => screen.getByRole('button', { name: /^save$|^kaydet$/i })
const textbox = (re) => screen.getByRole('textbox', { name: re })

async function openNewAsUser() {
  render(<HttpMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
  await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
  fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör|yeni izleme/i }))
  fireEvent.change(screen.getByPlaceholderText('https://example.com'), { target: { value: 'https://plain.example.com' } })
  await fillGroupAndTags()
}

async function openEditAsGlobalAdmin(row = stored) {
  api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [row] })
  render(<HttpMonitorPage systemRole="ADMIN" globalAdmin teamId={5} teamName="SY-A" />)
  await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
  await waitFor(() => expect(screen.getAllByRole('button', { name: /düzenle|edit/i }).length).toBeGreaterThan(0))
  fireEvent.click(screen.getAllByRole('button', { name: /düzenle|edit/i })[0])
  await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument())
}

function chooseMethod(method) {
  const modal = screen.getByRole('dialog')
  const trigger = [...modal.querySelectorAll('button[role="combobox"]')].find((b) => /^(GET|HEAD|POST)$/.test(b.textContent.trim()))
  fireEvent.mouseDown(trigger)
  fireEvent.mouseDown([...document.querySelectorAll('[role="option"]')].find((o) => o.textContent.trim() === method))
}

describe('HTTP gelişmiş istek — form', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [] })
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } })
    api.monitoring.listGroups.mockResolvedValue({ success: true, data: [] })
    api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { http: {} } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
    api.monitoring.createHttpMonitor.mockResolvedValue({ success: true, data: {} })
    api.monitoring.updateHttpMonitor.mockResolvedValue({ success: true, data: { ...stored } })
    api.monitoring.testHttp.mockResolvedValue({ success: true, data: { condition_met: true, http_status: 200 } })
  })

  it('bölüm VARSAYILAN KAPALI; dokunulmayan yeni izlemenin yükü bugünküyle aynı anahtarları taşır', async () => {
    await openNewAsUser()
    const trigger = advTrigger()
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('textbox', { name: /json yolu|json path/i })).toBeNull()   // kapalıyken içerik DOM'da yok

    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.monitoring.createHttpMonitor).toHaveBeenCalled())
    expect(Object.keys(api.monitoring.createHttpMonitor.mock.calls[0][0]).sort()).toEqual(LEGACY_CREATE_KEYS)
  })

  it('bölüm açılıp hiçbir şey değiştirilmeden kaydedilirse yük yine değişmez; Test yükü de eski anahtarlarla', async () => {
    await openNewAsUser()
    fireEvent.click(advTrigger())
    expect(advTrigger()).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(screen.getByRole('button', { name: /test et|^test$/i }))
    await waitFor(() => expect(api.monitoring.testHttp).toHaveBeenCalled())
    expect(Object.keys(api.monitoring.testHttp.mock.calls[0][0]).sort())
      .toEqual(['expectedStatus', 'followRedirects', 'method', 'timeoutMs', 'url', 'useProxy', 'verifySsl'])

    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.monitoring.createHttpMonitor).toHaveBeenCalled())
    expect(Object.keys(api.monitoring.createHttpMonitor.mock.calls[0][0]).sort()).toEqual(LEGACY_CREATE_KEYS)
  })

  it('yeni izleme: POST gövdesi + JSON doğrulaması + yavaşlık alarmı beklenen anahtarlarla gönderilir', async () => {
    await openNewAsUser()
    chooseMethod('POST')
    fireEvent.click(advTrigger())
    // global admin değil → başlık alanı kilitli
    expect(textbox(/özel başlıklar|custom headers/i)).toBeDisabled()
    fireEvent.change(textbox(/basic auth kullanıcı adı|basic auth username/i), { target: { value: ' izleme ' } })
    fireEvent.change(screen.getByLabelText(/basic auth parolası|basic auth password/i), { target: { value: 'p@ss' } })
    fireEvent.change(textbox(/istek gövdesi|request body/i), { target: { value: '{"ping":true}' } })
    fireEvent.change(textbox(/json yolu|json path/i), { target: { value: '$.status' } })
    fireEvent.change(textbox(/beklenen değer|expected value/i), { target: { value: 'ok' } })
    fireEvent.click(screen.getByRole('checkbox', { name: /yavaş yanıt alarmı|slow response alert/i }))
    fireEvent.change(screen.getByRole('spinbutton', { name: /yavaşlık eşiği|slowness threshold/i }), { target: { value: '4500' } })

    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.monitoring.createHttpMonitor).toHaveBeenCalled())
    const p = api.monitoring.createHttpMonitor.mock.calls[0][0]
    expect(p).toMatchObject({
      method: 'POST', basicAuthUser: 'izleme', basicAuthPass: 'p@ss', requestBody: '{"ping":true}',
      jsonPath: '$.status', jsonExpected: 'ok', slowResponseEnabled: true, slowThresholdMs: 4500,
    })
    expect(p).not.toHaveProperty('customHeaders')        // global admin değil
    expect(p).not.toHaveProperty('requestContentType')   // boş bırakıldı → sunucu application/json
  })

  it('satır içi hatalar: bozuk JSON yolu ve aralık dışı eşik alanın altında; kayıt YOK; kapalı bölüm hata için açılır', async () => {
    await openNewAsUser()
    fireEvent.click(advTrigger())
    fireEvent.change(textbox(/json yolu|json path/i), { target: { value: '$.items[x]' } })
    fireEvent.click(screen.getByRole('checkbox', { name: /yavaş yanıt alarmı|slow response alert/i }))
    fireEvent.change(screen.getByRole('spinbutton', { name: /yavaşlık eşiği|slowness threshold/i }), { target: { value: '50' } })
    fireEvent.click(advTrigger())   // bölümü kapat
    expect(advTrigger()).toHaveAttribute('aria-expanded', 'false')

    fireEvent.click(saveBtn())
    await waitFor(() => expect(advTrigger()).toHaveAttribute('aria-expanded', 'true'))
    const jsonField = document.querySelector('[data-field="advJsonPath"]')
    expect(within(jsonField).getByText(/geçersiz json yolu|invalid json path/i)).toBeInTheDocument()
    expect(textbox(/json yolu|json path/i)).toHaveAttribute('aria-invalid', 'true')
    const slowField = document.querySelector('[data-field="advSlowThreshold"]')
    expect(within(slowField).getByText(/100.*300000/)).toBeInTheDocument()
    expect(api.monitoring.createHttpMonitor).not.toHaveBeenCalled()

    // alan düzeltilince kendi hatası silinir
    fireEvent.change(textbox(/json yolu|json path/i), { target: { value: '$.items[0]' } })
    expect(document.querySelector('[data-field="advJsonPath"]')).not.toHaveAttribute('data-invalid')
  })

  it('HEAD + JSON yolu → alan altında hata (yanıtta gövde yok)', async () => {
    await openNewAsUser()
    chooseMethod('HEAD')
    fireEvent.click(advTrigger())
    expect(screen.getByText(/yalnız post yönteminde|only sent with post/i)).toBeInTheDocument()   // gövde alanı yok
    fireEvent.change(textbox(/json yolu|json path/i), { target: { value: '$.status' } })
    fireEvent.click(saveBtn())
    const jsonField = await waitFor(() => document.querySelector('[data-field="advJsonPath"][data-invalid]'))
    expect(within(jsonField).getByText(/HEAD/)).toBeInTheDocument()
    expect(api.monitoring.createHttpMonitor).not.toHaveBeenCalled()
  })

  it('düzenleme (global admin): kayıtlı parola "kayıtlı" yer tutucusuyla, BOŞ bırakılınca gönderilmez; yalnız değişen alanlar gider', async () => {
    await openEditAsGlobalAdmin()
    fireEvent.click(advTrigger())
    expect(screen.getByText(/kayıtlı başlıklar: x-api-key|stored headers: x-api-key/i)).toBeInTheDocument()
    const pass = screen.getByLabelText(/basic auth parolası|basic auth password/i)
    expect(pass).toHaveAttribute('type', 'password')
    expect(pass).toHaveValue('')
    expect(pass).toHaveAttribute('placeholder', expect.stringMatching(/kayıtlı|stored/i))
    expect(textbox(/basic auth kullanıcı adı|basic auth username/i)).toHaveValue('izleme')
    fireEvent.click(screen.getByRole('checkbox', { name: /yavaş yanıt alarmı|slow response alert/i }))

    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.monitoring.updateHttpMonitor).toHaveBeenCalled())
    const p = api.monitoring.updateHttpMonitor.mock.calls[0][1]
    expect(p.slowResponseEnabled).toBe(true)
    for (const k of ['basicAuthPass', 'basicAuthUser', 'customHeaders', 'requestBody', 'requestContentType', 'jsonPath', 'jsonExpected', 'slowThresholdMs']) {
      expect(p, k).not.toHaveProperty(k)
    }
  })

  it('düzenleme (global admin): geçersiz başlık satırı → alan altında satır numaralı hata; geçerli başlık yazılınca gönderilir', async () => {
    await openEditAsGlobalAdmin()
    fireEvent.click(advTrigger())
    const headers = textbox(/özel başlıklar|custom headers/i)
    expect(headers).not.toBeDisabled()
    fireEvent.change(headers, { target: { value: 'X-Ok: 1\nHost: evil.example.com' } })
    fireEvent.click(saveBtn())
    const f = await waitFor(() => document.querySelector('[data-field="advHeaders"][data-invalid]'))
    expect(within(f).getByText(/satır 2|line 2/i)).toBeInTheDocument()
    expect(api.monitoring.updateHttpMonitor).not.toHaveBeenCalled()

    fireEvent.change(headers, { target: { value: 'X-Api-Key: yeni-anahtar' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.monitoring.updateHttpMonitor).toHaveBeenCalled())
    expect(api.monitoring.updateHttpMonitor.mock.calls[0][1].customHeaders).toBe('X-Api-Key: yeni-anahtar')
  })

  it('Kopyala: sırlar (parola/başlık) TAŞINMAZ; kullanıcı adı ve diğer gelişmiş alanlar kopyalanır', async () => {
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [{ ...stored, json_path: '$.status', json_expected: 'ok' }] })
    render(<HttpMonitorPage systemRole="ADMIN" globalAdmin teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    await waitFor(() => expect(screen.getAllByRole('button', { name: /kopyala|duplicate/i }).length).toBeGreaterThan(0))
    fireEvent.click(screen.getAllByRole('button', { name: /kopyala|duplicate/i })[0])
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.monitoring.createHttpMonitor).toHaveBeenCalled())
    const p = api.monitoring.createHttpMonitor.mock.calls[0][0]
    expect(p).toMatchObject({ basicAuthUser: 'izleme', jsonPath: '$.status', jsonExpected: 'ok' })
    expect(p).not.toHaveProperty('basicAuthPass')
    expect(p).not.toHaveProperty('customHeaders')
  })
})

describe('HTTP gelişmiş istek — saf model', () => {
  it('payload: tabanla aynıysa hiçbir anahtar; değişenler ve yazılmış sırlar eklenir', () => {
    expect(httpAdvancedPayload({ ...ADV_EMPTY }, ADV_EMPTY, { canEditHeaders: true })).toEqual({})
    const base = advFormFrom(stored)
    expect(httpAdvancedPayload({ ...base }, base, { canEditHeaders: true })).toEqual({})
    expect(httpAdvancedPayload({ ...base, basicAuthUser: '' }, base)).toEqual({ basicAuthUser: null })
    expect(httpAdvancedPayload({ ...base, basicAuthPass: 'x' }, base)).toEqual({ basicAuthPass: 'x' })
    expect(httpAdvancedPayload({ ...base, customHeaders: ' A: 1 ' }, base, { canEditHeaders: false })).toEqual({})
    expect(httpAdvancedPayload({ ...base, customHeaders: ' A: 1 ' }, base, { canEditHeaders: true })).toEqual({ customHeaders: 'A: 1' })
    expect(httpAdvancedPayload({ ...base, clearHeaders: true }, base, { canEditHeaders: true })).toEqual({ customHeaders: null })
    expect(httpAdvancedPayload({ ...ADV_EMPTY, jsonPath: ' $.a ' }, ADV_EMPTY)).toEqual({ jsonPath: '$.a' })
    // eşik yalnız alarm AÇIKKEN gider — kapalıyken boşaltılmış kutu sunucuda 400 doğurmasın
    expect(httpAdvancedPayload({ ...ADV_EMPTY, slowThresholdMs: '' }, ADV_EMPTY)).toEqual({})
    expect(httpAdvancedPayload({ ...ADV_EMPTY, slowResponseEnabled: true, slowThresholdMs: 5000 }, ADV_EMPTY))
      .toEqual({ slowResponseEnabled: true, slowThresholdMs: 5000 })
  })

  it('test yükü: yalnız dolu değerler; gövde yalnız POST', () => {
    expect(httpAdvancedTestPayload({ ...ADV_EMPTY })).toEqual({})
    const f = { ...ADV_EMPTY, requestBody: '{}', requestContentType: 'text/plain', jsonPath: '$.a' }
    expect(httpAdvancedTestPayload(f, { method: 'GET' })).toEqual({ jsonPath: '$.a' })
    expect(httpAdvancedTestPayload(f, { method: 'POST' })).toEqual({ requestBody: '{}', requestContentType: 'text/plain', jsonPath: '$.a' })
  })

  it('JSON yolu sözdizimi sunucuyla aynı', () => {
    for (const ok of ['$', '$.status', 'items[0].id', '$.items[0].id', "$['a.b'].c", '$["x"]']) expect(isValidJsonPath(ok), ok).toBe(true)
    for (const bad of ['', '$.', '$..a', '.a', '$a', '$[x]', '$[-1]', "$['a", '$[0', 'a b', '$.items[1234567890]']) {
      expect(isValidJsonPath(bad), bad).toBe(false)
    }
  })

  it('başlık doğrulaması: iki noktasız satır, kısıtlı başlık, kontrol karakteri, 20 tavanı', () => {
    expect(headerError('A: 1\n# yorum\n\nB: 2')).toBeNull()
    expect(headerError('A: 1\nbozuk')).toEqual({ key: 'http.adv.errHeaderLine', args: [2] })
    expect(headerError('Content-Length: 5')).toEqual({ key: 'http.adv.errHeaderRestricted', args: [1, 'Content-Length'] })
    expect(headerError('X-A: a\rb')?.key).toBe('http.adv.errHeaderCtl')
    expect(headerError(Array.from({ length: 21 }, (_, i) => `X-${i}: v`).join('\n'))?.key).toBe('http.adv.errHeaderCount')
  })

  it('validate: kapalı/boş alan hata üretmez; gövde 64 KB tavanı yalnız POST’ta', () => {
    expect(validateHttpAdvanced({ ...ADV_EMPTY })).toEqual({})
    const big = { ...ADV_EMPTY, requestBody: 'a'.repeat(64 * 1024 + 1) }
    expect(validateHttpAdvanced(big, { method: 'GET' })).toEqual({})
    expect(validateHttpAdvanced(big, { method: 'POST' })).toHaveProperty('advBody')
    expect(validateHttpAdvanced({ ...ADV_EMPTY, basicAuthUser: 'a:b' })).toHaveProperty('advBasicUser')
    expect(validateHttpAdvanced({ ...ADV_EMPTY, requestContentType: 'json' }, { method: 'POST' })).toHaveProperty('advContentType')
  })
})

describe('HTTP gelişmiş istek — kart', () => {
  it('JSON doğrulaması nedeni sınıflandırmaya sokulmaz (yol "timeout" içerse bile), çipler yalnız kullanılıyorsa', () => {
    const m = { status: 'down', http_status: 200, json_assertion_failed: true,
      error: 'JSON doğrulaması başarısız: $.timeout = "x" (beklenen "y")' }
    expect(httpFailureReason(m)).toEqual({ kind: 'json', detail: '$.timeout = "x" (beklenen "y")' })
    expect(requestChips({ url: 'https://a.example.com' }).map((c) => c.key)).toEqual(['expected'])
    expect(requestChips({ url: 'https://a.example.com', basic_auth_user: 'u', json_path: '$.status' }).map((c) => c.key))
      .toEqual(['expected', 'auth', 'json'])
  })

  it('açık HTTP_SLOW → kartta amber "yavaş yanıt alarmı" rozeti; JSON nedeni metinle görünür', () => {
    const row = { ...stored, status: 'down', json_assertion_failed: true, slow_alarm: true, slow_response_enabled: true,
      error: 'JSON doğrulaması başarısız: $.status bulunamadı' }
    render(<HttpMonitorCard monitor={row} status="down" badge={null} onOpen={() => {}} />)
    expect(document.querySelector('[data-slot="http-slow-alarm"]')).toBeInTheDocument()
    const reason = document.querySelector('[data-slot="http-reason"]')
    expect(reason).toHaveAttribute('data-reason', 'json')
    expect(reason.textContent).toMatch(/\$\.status bulunamadı/)
    expect(document.querySelector('[data-chip="auth"]')).toBeInTheDocument()

    const { container } = render(<HttpMonitorCard monitor={{ ...row, slow_alarm: false }} status="down" badge={null} onOpen={() => {}} />)
    expect(container.querySelector('[data-slot="http-slow-alarm"]')).toBeNull()
  })
})
