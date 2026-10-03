import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import LoginMethodsSettings from '../components/admin/LoginMethodsSettings.jsx'
import {
  activeSeries, channelOfLoginMethod, chartPoints, delta, enabledChannels, loginStatsParams, reasonLabel, usersCsv,
} from '../components/admin/loginmethods/stats/loginStatsModel.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const csvMock = vi.hoisted(() => ({ downloadCsv: vi.fn() }))
vi.mock('../utils/csvExport.js', async (orig) => ({ ...(await orig()), downloadCsv: csvMock.downloadCsv }))
vi.mock('../api/client', async (orig) => ({
  ...(await orig()),
  api: withApiFallback({
    loginMethodsAdmin: { get: vi.fn(), save: vi.fn(), pushTest: vi.fn(), stats: vi.fn(), statsUsers: vi.fn(), statsUser: vi.fn() },
  }),
}))

import { api } from '../api/client'

/**
 * Giriş Yöntemleri → İstatistikler (2026-10-03): sekme + URL, KPI'lar ve değişim tonları, kanal kartları (Kapalı rozeti,
 * huni, dokununca tablo süzgeci), grafik bozuk noktaya dayanıklı, neden etiketleri, kullanıcı tablosu (arama / sıralama /
 * sayfa / CSV), Sheet derin bağlantısı (lm_user) ve boş / hata durumları. Yer tutucu adlar; gerçek kişi / takım yok.
 */
const SETTINGS = {
  ldap_enabled: true, push_enabled: true, email_enabled: false, push_ttl_seconds: 45, email_ttl_seconds: 60,
  max_attempts: 3, resend_cooldown_seconds: 30, max_requests_per_user: 5, max_requests_per_ip: 20,
  max_failed_verifications: 5, allow_global_admins: false, push_require_phone: true, email_require_email: true,
  max_contact_mismatches: 5,
}
const ADMIN = { success: true, data: {
  settings: SETTINGS, limits: { ttl: [30, 300], window_minutes: 15 },
  status: { push_gateway_configured: true, smtp: { configured: true, host: 'smtp.example.com' }, ldap_integration_enabled: true },
  public: {}, coverage: { active_users: 0 }, activity: [], activity_types: [],
} }

const CH = (channel, success, failed, extra = {}) => ({
  channel, success, failed, attempts: success + failed, success_rate: success + failed ? success / (success + failed) : null,
  unique_users: success ? 1 : 0, share: success / 8, estimated: 0, ...extra,
})
function summary(over = {}) {
  return { success: true, data: {
    days: 7, granularity: 'day', from: '2026-09-26T21:00:00', to: '2026-10-03T09:30:00', generated_at: '2026-10-03T09:30:00',
    truncated: false, row_count: 20, row_cap: 250000, estimated: 1,
    totals: { attempts: 14, success: 8, failed: 6, success_rate: 0.5714, unique_users: 2, unknown_user_failures: 2,
      unattributed_success: 0, delivery_failures: 1 },
    previous: { attempts: 2, success: 1, failed: 1, success_rate: 0.5, unique_users: 1 },
    channels: [
      CH('LDAP', 4, 3, { estimated: 1 }), CH('LOCAL', 2, 0),
      CH('OTP_PUSH', 1, 1, { otp: { requested: 4, sent: 2, verified: 1, suppressed: 2, rate_limited: 0, delivery_failed: 0,
        wrong_code: 1, expired: 0, locked: 0, conversion: 0.5, suppressed_reasons: [{ reason: 'COOLDOWN', count: 1 }, { reason: 'NEW_CODE', count: 1 }] } }),
      CH('OTP_EMAIL', 0, 0, { otp: { requested: 0, sent: 0, verified: 0, suppressed: 0, rate_limited: 0, delivery_failed: 1,
        wrong_code: 0, expired: 0, locked: 0, conversion: null, suppressed_reasons: [] } }),
      CH('REMEMBER_ME', 1, 0),
    ],
    series: [
      { ts: '2026-09-26T21:00:00', LDAP: 1, LOCAL: 0, OTP_PUSH: 0, OTP_EMAIL: 0, REMEMBER_ME: 0, OTHER: 0, failed: 0 },
      { ts: null, LDAP: 5 },
      'bozuk',
      { ts: 'not-a-date', LDAP: 9 },
      { ts: '2026-10-02T21:00:00', LDAP: 0, LOCAL: 1, OTP_PUSH: 1, OTP_EMAIL: 0, REMEMBER_ME: 0, OTHER: 0, failed: 1 },
    ],
    failure_reasons: [
      { reason: 'BAD_PASSWORD', count: 2, channels: { LDAP: 2 } },
      { reason: 'UNKNOWN_USER', count: 2, channels: { UNKNOWN: 2 } },
      { reason: 'SOMETHING_NEW', count: 1, channels: { LDAP: 1 } },
    ],
    ...over,
  } }
}
const USER = (name, extra = {}) => ({
  username: name, display_name: `Kullanici ${name.slice(-1)}`, team_id: 1, team_name: 'Takim A', source: 'LDAP', active: true,
  success: { LDAP: 3, LOCAL: 0, OTP_PUSH: 1, OTP_EMAIL: 0, REMEMBER_ME: 0 }, failed_by: {}, success_total: 4, failed: 1,
  attempts: 5, success_rate: 0.8, estimated: 0, last_success: { at: '2026-10-03T08:00:00', channel: 'LDAP' },
  last_failure: { at: '2026-10-01T09:00:00', reason: 'BAD_PASSWORD' }, ...extra,
})
const USERS = (items, total = items.length) => ({ success: true, data: { items, total, page: 1, size: 25, total_pages: Math.ceil(total / 25) } })
const DETAIL = { success: true, data: {
  user: { username: 'USER-A', display_name: 'Kullanici A', team_id: 1, team_name: 'Takim A', source: 'LOCAL', active: false },
  found: true, days: 7, granularity: 'day', truncated: false, estimated: 0, identity_masked: false,
  totals: { attempts: 5, success: 4, failed: 1, success_rate: 0.8, unique_users: 1 },
  channels: [{ channel: 'LOCAL', success: 3, failed: 0, attempts: 3, success_rate: 1 }, { channel: 'OTP_PUSH', success: 1, failed: 1, attempts: 2, success_rate: 0.5 },
    { channel: 'LDAP', success: 0, failed: 0, attempts: 0, success_rate: null }],
  failure_reasons: [{ reason: 'OTP_INVALID', count: 1, channels: { OTP_PUSH: 1 } }],
  series: [{ ts: '2026-10-02T21:00:00', success: 2, failed: 1 }, { ts: 7 }],
  recent: [
    { id: 9, time: '2026-10-03T08:00:00', event: 'LOGIN', actor: 'USER-A', channel: 'LOCAL', channel_estimated: false, outcome: 'SUCCESS',
      ip: '192.0.2.10', city: 'Doc City', country: 'Docland', ua_summary: 'Chrome 130 · Windows', flags: 'OFF_HOURS' },
    { id: 8, time: '2026-10-03T07:08:00', event: 'LOGIN_OTP_VERIFY_FAILED', actor: 'USER-A', channel: 'OTP_PUSH', outcome: 'FAILURE', reason: 'OTP_INVALID', identity_masked: true },
  ],
} }

const go = (qs) => window.history.replaceState(null, '', `/?tab=settings&sec=loginmethods${qs}`)
const stats = () => document.querySelector('[data-slot="lm-stats"]')
const card = (ch) => document.querySelector(`[data-slot="lm-channel"][data-channel="${ch}"]`)

describe('Giriş Yöntemleri — İstatistikler sekmesi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.loginMethodsAdmin.get.mockResolvedValue(ADMIN)
    api.loginMethodsAdmin.stats.mockResolvedValue(summary())
    api.loginMethodsAdmin.statsUsers.mockResolvedValue(USERS([USER('USER-A'), USER('USER-B', { active: false, failed: 0 })]))
    api.loginMethodsAdmin.statsUser.mockResolvedValue(DETAIL)
    go('')
  })
  afterEach(() => window.history.replaceState(null, '', '/'))

  it('varsayılan sekme Ayarlar (istek yok); İstatistikler sekmesi yükler ve URL lm_tab=stats yazılır', async () => {
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(document.querySelector('[data-slot="lm-method"]')).not.toBeNull())
    expect(api.loginMethodsAdmin.stats).not.toHaveBeenCalled()
    fireEvent.mouseDown(screen.getByRole('tab', { name: /Statistics|İstatistikler/ }))
    await waitFor(() => expect(stats()).not.toBeNull())
    expect(api.loginMethodsAdmin.stats).toHaveBeenCalledWith(7, false)
    expect(screen.queryByRole('button', { name: /^Save$|^Kaydet$/ })).toBeNull()   // kaydet çubuğu yalnız ayarlarda
    await waitFor(() => expect(window.location.search).toContain('lm_tab=stats'), { timeout: 2000 })
  })

  it('KPI değerleri + değişim tonları (başarısızda artış KÖTÜ, oranda puan); tahmini satır notu; bilinmeyen kullanıcı notu', async () => {
    go('&lm_tab=stats')
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="lm-kpi"]')).toHaveLength(5))
    const kpi = (k) => document.querySelector(`[data-slot="lm-kpi"][data-kpi="${k}"]`)
    expect(kpi('attempts').querySelector('[data-slot="lm-kpi-value"]')).toHaveTextContent('14')
    expect(kpi('failed').querySelector('[data-slot="lm-delta"]')).toHaveAttribute('data-tone', 'bad')
    expect(kpi('success').querySelector('[data-slot="lm-delta"]')).toHaveAttribute('data-tone', 'good')
    expect(kpi('attempts').querySelector('[data-slot="lm-delta"]')).toHaveAttribute('data-tone', 'neutral')
    expect(kpi('rate').querySelector('[data-slot="lm-delta"]')).toHaveTextContent(/\+7[.,]1/)
    expect(document.querySelector('[data-slot="lm-stats-estimated"]')).toHaveTextContent(/estimated|tahmini/)
    expect(document.querySelector('[data-slot="lm-stats-unknown"]')).toHaveTextContent('2')
  })

  it('kanal kartları: sayılar, Kapalı rozeti (e-posta kodu kapalı), push hunisi; karta dokununca tablo o kanala süzülür', async () => {
    go('&lm_tab=stats')
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(card('LDAP')).not.toBeNull())
    expect(card('LDAP')).toHaveTextContent('4')
    expect(card('OTP_EMAIL').querySelector('[data-slot="lm-channel-off"]')).not.toBeNull()
    expect(card('LDAP').querySelector('[data-slot="lm-channel-off"]')).toBeNull()
    const funnel = card('OTP_PUSH').querySelector('[data-slot="lm-funnel"]')
    expect(funnel.querySelector('[data-step="requested"]')).toHaveTextContent('4')
    expect(funnel.querySelector('[data-step="sent"]')).toHaveTextContent('2')
    expect(funnel.querySelector('[data-slot="lm-funnel-conversion"]')).toHaveTextContent('50')
    expect(funnel.querySelector('[data-slot="lm-funnel-reasons"]')).toHaveTextContent(/Resend wait|Yeniden gönderme beklemesi/)
    expect(funnel.querySelector('[data-slot="lm-funnel-reasons"]')).toHaveTextContent('NEW_CODE')   // bilinmeyen kod → ham
    expect(card('OTP_EMAIL').querySelector('[data-kind="delivery_failed"]')).not.toBeNull()

    const btn = card('LDAP').querySelector('[data-slot="lm-channel-filter"]')
    expect(btn).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(btn)
    await waitFor(() => expect(api.loginMethodsAdmin.statsUsers).toHaveBeenLastCalledWith(expect.objectContaining({ channel: 'LDAP' })))
    expect(card('LDAP').querySelector('[data-slot="lm-channel-filter"]')).toHaveAttribute('aria-pressed', 'true')
    expect(document.querySelector('[data-slot="lm-users-channel"]')).toHaveValue('LDAP')
    fireEvent.click(card('LDAP').querySelector('[data-slot="lm-channel-filter"]'))
    await waitFor(() => expect(api.loginMethodsAdmin.statsUsers).toHaveBeenLastCalledWith(expect.objectContaining({ channel: null })))
  })

  it('grafik bozuk noktalara dayanıklı (çökmez); nedenler etiketli, bilinmeyen kod ham gösterilir; teslim notu', async () => {
    go('&lm_tab=stats')
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(document.querySelector('[data-slot="lm-trend"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="lm-trend-summary"]')).toHaveTextContent(/8/)
    const legend = [...document.querySelectorAll('[data-slot="lm-trend-legend"] [data-series]')].map((li) => li.getAttribute('data-series'))
    expect(legend).toEqual(['LDAP', 'LOCAL', 'OTP_PUSH', 'failed'])
    const reasons = document.querySelectorAll('[data-slot="lm-reason"]')
    expect(reasons[0]).toHaveTextContent(/Wrong password|Yanlış şifre/)
    expect(reasons[1]).toHaveTextContent(/Unknown user|Bilinmeyen kullanıcı/)
    expect(reasons[2]).toHaveTextContent('SOMETHING_NEW')
    expect(document.querySelector('[data-slot="lm-reasons-delivery"]')).toHaveTextContent('1')
  })

  it('kullanıcı tablosu: satırlar, pasif rozeti; arama (gecikmeli) ve sıralama isteğe gider; sayfa 2; CSV tüm satırları ister', async () => {
    api.loginMethodsAdmin.statsUsers.mockResolvedValue(USERS([USER('USER-A'), USER('USER-B', { active: false })], 60))
    go('&lm_tab=stats')
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="lm-user-row"]')).toHaveLength(2))
    const rowB = document.querySelector('[data-slot="lm-user-row"][data-user="USER-B"]')
    expect(rowB.querySelector('[data-slot="lm-user-passive"]')).not.toBeNull()
    expect(api.loginMethodsAdmin.statsUsers).toHaveBeenCalledWith({ days: 7, q: null, channel: null, sort: 'logins', page: 1, size: expect.any(Number) })

    fireEvent.change(screen.getByRole('searchbox', { name: /Search users|Kullanıcı ara/ }), { target: { value: 'kullanici a' } })
    await waitFor(() => expect(api.loginMethodsAdmin.statsUsers).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'kullanici a', page: 1 })), { timeout: 2000 })
    fireEvent.change(document.querySelector('[data-slot="lm-users-sort"]'), { target: { value: 'failures' } })
    await waitFor(() => expect(api.loginMethodsAdmin.statsUsers).toHaveBeenLastCalledWith(expect.objectContaining({ sort: 'failures' })))

    fireEvent.click(screen.getByRole('button', { name: /^Next$|^Sonraki$/ }))
    await waitFor(() => expect(api.loginMethodsAdmin.statsUsers).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 })))

    api.loginMethodsAdmin.statsUsers.mockResolvedValueOnce(USERS([USER('USER-A'), USER('USER-B')]))
    fireEvent.click(document.querySelector('[data-slot="lm-users-csv"]'))
    await waitFor(() => expect(csvMock.downloadCsv).toHaveBeenCalled())
    expect(api.loginMethodsAdmin.statsUsers).toHaveBeenCalledWith(expect.objectContaining({ export: 1, sort: 'failures', q: 'kullanici a' }))
    const [name, csv] = csvMock.downloadCsv.mock.calls[0]
    expect(name).toMatch(/^sitemonitor-login-users-7d-.*\.csv$/)
    expect(csv).toContain('USER-A')
    expect(csv).toContain('Takim A')
  })

  it('satırdaki ad Sheet açar (lm_user); Sheet: başlık, pasif, kanallar, nedenler, son olaylar (kanal rozeti, gizli IP)', async () => {
    go('&lm_tab=stats')
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(document.querySelector('[data-slot="lm-user-row"]')).not.toBeNull())
    fireEvent.click(within(document.querySelector('[data-slot="lm-user-row"][data-user="USER-A"]')).getAllByRole('button')[0])
    const sheet = await waitFor(() => {
      const el = document.querySelector('[data-slot="lm-user-sheet"]')
      expect(el).not.toBeNull()
      return el
    })
    await waitFor(() => expect(api.loginMethodsAdmin.statsUser).toHaveBeenCalledWith('USER-A', 7))
    await waitFor(() => expect(sheet.querySelectorAll('[data-slot="lm-user-event"]')).toHaveLength(2))
    expect(sheet).toHaveTextContent('Kullanici A')
    expect(sheet).toHaveTextContent(/Inactive|Pasif/)
    expect(sheet.querySelectorAll('[data-slot="lm-user-channel"]')).toHaveLength(2)   // denemesi olmayan kanal gizli
    const [first, second] = sheet.querySelectorAll('[data-slot="lm-user-event"]')
    expect(first.querySelector('[data-slot="login-channel"]')).toHaveAttribute('data-channel', 'LOCAL')
    expect(first).toHaveTextContent('192.0.2.10')
    expect(first).toHaveTextContent('Chrome 130')
    expect(second).toHaveTextContent(/Wrong code|Yanlış kod/)
    await waitFor(() => expect(window.location.search).toContain('lm_user=USER-A'), { timeout: 2000 })
  })

  it('derin bağlantı: ?lm_tab=stats&lm_user=… Sheet\'i doğrudan açar; dönem lm_p=30 okunur; dönem değişince yeniden ister', async () => {
    go('&lm_tab=stats&lm_p=30&lm_user=USER-A')
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(api.loginMethodsAdmin.statsUser).toHaveBeenCalledWith('USER-A', 30))
    expect(api.loginMethodsAdmin.stats).toHaveBeenCalledWith(30, false)
    expect(stats()).toHaveAttribute('data-period', '30')
    // Sheet modal: kapatılınca sayfa yeniden erişilebilir, lm_user URL'den kalkar
    const sheet = document.querySelector('[data-slot="lm-user-sheet"]')
    fireEvent.click(within(sheet).getByRole('button', { name: /^Close$|^Kapat$/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="lm-user-sheet"]')).toBeNull())
    await waitFor(() => expect(window.location.search).not.toContain('lm_user'), { timeout: 2000 })
    fireEvent.click(within(stats()).getByRole('button', { name: /^24 h$|^24 sa$/ }))
    await waitFor(() => expect(api.loginMethodsAdmin.stats).toHaveBeenLastCalledWith(1, false))
    fireEvent.click(document.querySelector('[data-slot="lm-stats-refresh"]'))
    await waitFor(() => expect(api.loginMethodsAdmin.stats).toHaveBeenLastCalledWith(1, true))
  })

  it('boş dönem → boş durum (tablo yok); hata → yeniden dene', async () => {
    api.loginMethodsAdmin.stats.mockResolvedValueOnce(summary({
      totals: { attempts: 0, success: 0, failed: 0, success_rate: null, unique_users: 0 }, estimated: 0,
      channels: [], series: [], failure_reasons: [],
    }))
    go('&lm_tab=stats')
    const { unmount } = render(<LoginMethodsSettings />)
    await waitFor(() => expect(document.querySelector('[data-slot="lm-stats-empty"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="lm-users"]')).toBeNull()
    unmount()

    api.loginMethodsAdmin.stats.mockResolvedValueOnce({ success: false, error: 'boom' })
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(screen.getByText('boom')).toBeTruthy())
    fireEvent.click(within(stats()).getByRole('button', { name: /Try again|Yeniden dene/ }))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="lm-kpi"]')).toHaveLength(5))
  })
})

describe('loginStatsModel', () => {
  const t = (k, ...a) => (k.startsWith('lm.stats.reason.BAD') ? 'Yanlış şifre' : `${k}${a.length ? ':' + a.join('|') : ''}`)

  it('grafik noktaları: bozuk kayıt düşer, sayısal olmayan 0; aktif seriler sabit sırada', () => {
    const pts = chartPoints(summary().data.series, 'day', 'tr-TR')
    expect(pts).toHaveLength(2)
    expect(pts[1]).toMatchObject({ LOCAL: 1, OTP_PUSH: 1, failed: 1, success: 2 })
    expect(activeSeries(pts)).toEqual(['LDAP', 'LOCAL', 'OTP_PUSH', 'failed'])
    expect(chartPoints(null, 'hour', 'tr-TR')).toEqual([])
    expect(chartPoints([{ ts: '2026-10-03T09:00:00', LDAP: 'x' }], 'hour', 'tr-TR')[0]).toMatchObject({ LDAP: 0, label: '12:00' })
  })

  it('değişim: yüzde / puan / önceki 0 / iyi-kötü ton', () => {
    expect(delta(10, 5)).toMatchObject({ dir: 'up', tone: 'good', pct: 1 })
    expect(delta(10, 5, { good: 'down' })).toMatchObject({ tone: 'bad' })
    expect(delta(3, 0)).toMatchObject({ pct: null, diff: 3 })
    expect(delta(0.9, 0.8, { rate: true })).toMatchObject({ points: 10, tone: 'good' })
    expect(delta(5, 5)).toMatchObject({ dir: 'flat', tone: 'neutral' })
    expect(delta(null, 5)).toBeNull()
  })

  it('kanal yardımcıları: son giriş yöntemi → kanal; kapalı yöntemler; derin bağlantı; neden etiketi yedeği; CSV', () => {
    expect(channelOfLoginMethod('PASSWORD', 'LDAP')).toBe('LDAP')
    expect(channelOfLoginMethod('PASSWORD', null)).toBe('LOCAL')
    expect(channelOfLoginMethod('OTP_EMAIL', 'LDAP')).toBe('OTP_EMAIL')
    expect(channelOfLoginMethod('', 'LDAP')).toBeNull()
    expect(enabledChannels(SETTINGS, { push_gateway_configured: false })).toEqual({ LDAP: true, LOCAL: true, OTP_PUSH: false, OTP_EMAIL: false, REMEMBER_ME: true })
    expect(enabledChannels(null)).toBeNull()
    expect(loginStatsParams('USER-A')).toEqual({ sec: 'loginmethods', lm_tab: 'stats', lm_user: 'USER-A' })
    expect(reasonLabel('BAD_PASSWORD', t)).toBe('Yanlış şifre')
    expect(reasonLabel('XYZ', (k) => k)).toBe('XYZ')
    const { headers, rows } = usersCsv([USER('USER-A')], (k) => k, 'tr-TR')
    expect(headers).toHaveLength(rows[0].length)
    expect(rows[0].slice(0, 3)).toEqual(['USER-A', 'Kullanici A', 'Takim A'])
  })
})
