import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'

/**
 * KAYIT SONRASI İLK / TAZE KONTROL — sekiz izleme sayfası (Sentetik ayrı: ScriptedMonitorPage.firstCheck.test.jsx).
 *
 * Kullanıcı bildirimi (2026-09-28): "Test et → başarılı → Kaydet. Sonrasında açılan kartta veriler yansımıyor, boş bir
 * görünüm oluyor. İlk koşumun verilerinin hemen karta yansımasını beklerim." Oluşturma ucu kontrol koşmuyordu; kart
 * zamanlayıcının ilk turuna + 60 sn'lik liste yenilemesine kadar boş kalıyordu.
 *
 * Her tür için AYNI üç senaryo, sayfanın gerçek akışıyla (kart → Kopyala / Düzenle → Kaydet):
 *  1. Oluşturma → kartın "Şimdi kontrol et" ucu YENİ kimlikle çağrılır; pencere kapanır, yeni kart ızgarada dönen
 *     göstergeyle "İlk kontrol yapılıyor…" der; yanıt gelince aynı kart sonucu gösterir (boş alan yok).
 *  2. Düzenleme — kontrolü etkileyen alan değişti (sunucunun kaydetme yanıtında) → taze kontrol.
 *  3. Düzenleme — yalnız meta (ad, etiket) → kontrol BAŞLAMAZ.
 */

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({ monitoring: {}, admin: {} }),
}))
import { api } from '../api/client'

import HttpMonitorPage from '../components/HttpMonitorPage.jsx'
import PingMonitorPage from '../components/PingMonitorPage.jsx'
import KeywordMonitorPage from '../components/KeywordMonitorPage.jsx'
import PageMonitorPage from '../components/PageMonitorPage.jsx'
import PageSpeedMonitorPage from '../components/PageSpeedMonitorPage.jsx'
import DnsMonitorPage from '../components/DnsMonitorPage.jsx'
import PortMonitorPage from '../components/PortMonitorPage.jsx'
import DomainMonitorPage from '../components/DomainMonitorPage.jsx'

const STAMP = '2026-09-28T09:00:00'
const COMMON = {
  team_id: 5, team_name: 'Takım A', group_name: 'Kurumsal Web', tags: 'prod', active: true, interval_seconds: 300,
  confirm_attempts: 3, confirm_interval_seconds: 30, recovery_checks: 3, recovery_interval_seconds: 30,
  notify_email: true, notify_webhook: true, alert_level: 'WARNING', active_alarm: false, noc_notify: false, noc_group_ids: [],
}

/**
 * Tür tablosu. `label` = kart eylemlerinin satır adı (MonitorCardActions rowLabel), `row` = yapılandırma, `never` = hiç
 * kontrol yokken sunucunun döndürdüğü sonuç alanları (enrich* else dalı), `result` = kontrol yanıtı, `resultSel` = kart
 * sonucu gösterdiğinde var olan öğe, `target` = kontrolü etkileyen bir alan değişikliği (CHECK_FIELDS'ten).
 */
const TYPES = [
  {
    kind: 'http', Page: HttpMonitorPage, list: 'getHttpMonitors', create: 'createHttpMonitor', update: 'updateHttpMonitor', trigger: 'triggerHttpCheck',
    label: (m) => m.url,
    row: { name: 'Site', url: 'https://www.example.com/', method: 'GET', expected_status: '200-399', follow_redirects: true, verify_ssl: false, use_proxy: 'AUTO', timeout_ms: 10000 },
    copy: { url: 'https://copy.example.com/' },
    never: { status: 'unknown', ok: null, http_status: null, response_ms: null, error: null, checked_at: null },
    result: { status: 'up', ok: true, http_status: 200, response_ms: 120, error: null, checked_at: STAMP },
    resultSel: '[data-slot="http-metric"]',
    target: { url: 'https://www.example.org/' },
  },
  {
    kind: 'ping', Page: PingMonitorPage, list: 'getPingMonitors', create: 'createPingMonitor', update: 'updatePingMonitor', trigger: 'triggerPingCheck',
    label: (m) => m.host,
    row: { name: 'Gateway', host: 'gw.example.com', ip_version: 'auto', packet_count: 4, timeout_ms: 2000, slow_response_enabled: false },
    copy: { host: 'gw2.example.com' },
    never: { status: 'unknown', up: null, rtt_ms: null, packet_loss: null, error: null, checked_at: null },
    result: { status: 'up', up: true, rtt_ms: 12, packet_loss: 0, error: null, checked_at: STAMP },
    resultSel: '[data-slot="ping-metric"]',
    target: { host: 'gw.example.org' },
    // Ping Kopyala'da AYNI host ile kaydetmeyi engeller (mükerrer koruması) — kullanıcı host'u değiştirir.
    fillCopy: () => fireEvent.change(screen.getByPlaceholderText('1.2.3.4 / host.example.com'), { target: { value: 'gw2.example.com' } }),
  },
  {
    kind: 'keyword', Page: KeywordMonitorPage, list: 'getKeywordMonitors', create: 'createKeywordMonitor', update: 'updateKeywordMonitor', trigger: 'triggerKeywordCheck',
    label: (m) => m.url,
    row: { name: 'Portal', url: 'https://www.example.com/', keyword: 'Welcome', operator: 'GTE', match_count: 1, case_sensitive: false, use_proxy: 'AUTO', timeout_ms: 10000 },
    copy: { url: 'https://copy.example.com/' },
    never: { status: 'unknown', found: null, occurrences: null, ok: null, http_status: null, response_ms: null, snippet: null, error: null, checked_at: null },
    result: { status: 'up', found: true, occurrences: 2, ok: true, http_status: 200, response_ms: 150, snippet: 'Welcome back', error: null, checked_at: STAMP },
    resultSel: '[data-slot="keyword-panel"][data-result="countOk"], [data-slot="keyword-panel"][data-result="found"]',
    target: { keyword: 'Sign in' },
  },
  {
    kind: 'page', Page: PageMonitorPage, list: 'getPageMonitors', create: 'createPageMonitor', update: 'updatePageMonitor', trigger: 'triggerPageCheck',
    label: (m) => m.url,
    row: { name: 'Home', url: 'https://www.example.com/', mode: 'SINGLE_PAGE', crawl_depth: 2, crawl_max_pages: 50, exclude_patterns: '',
      slow_resource_ms: 3000, alert_third_party: false, alert_mixed_content: true, alert_timeout: true, resource_concurrency: 6, use_proxy: 'AUTO', timeout_ms: 4000 },
    copy: { url: 'https://copy.example.com/' },
    never: { status: 'unknown', ok: null, http_status: null, response_ms: null, total_resources: null, broken_resources: null, timeout_count: null,
      mixed_content_count: null, pages_crawled: null, error: null, checked_at: null },
    result: { status: 'OK', ok: true, http_status: 200, response_ms: 380, total_resources: 40, broken_resources: 0, timeout_count: 0,
      mixed_content_count: 0, pages_crawled: 1, error: null, checked_at: STAMP },
    resultSel: '[data-slot="page-integrity"][data-tone="ok"]',
    target: { mode: 'SITE_CRAWL' },
  },
  {
    kind: 'pagespeed', Page: PageSpeedMonitorPage, list: 'getPageSpeedMonitors', create: 'createPageSpeedMonitor', update: 'updatePageSpeedMonitor', trigger: 'triggerPageSpeedCheck',
    label: (m) => m.url,
    row: { name: 'Shop', url: 'https://www.example.com/', timeout_ms: 30000, max_load_ms: null, max_ttfb_ms: null, max_page_kb: null, max_requests: null,
      user_agent: null, send_dnt: false, exclude_trackers: false, tracker_patterns: null, resource_concurrency: 6, basic_auth_user: null, use_proxy: 'OFF' },
    copy: { url: 'https://copy.example.com/' },
    never: { status: 'unknown', ok: null, http_status: null, ttfb_ms: null, response_ms: null, total_bytes: null, request_count: null, breached_metrics: [], error: null, last_check: null },
    result: { status: 'OK', ok: true, http_status: 200, ttfb_ms: 200, response_ms: 1200, total_bytes: 400000, request_count: 30, breached_metrics: [], error: null, last_check: STAMP },
    // Ölçerler bütçeleri göstermek için ölçümsüz de çizilir ("—") → sonuç = yükleme ölçerinde gerçek değer.
    hasResult: (card) => {
      const v = card.querySelector('[data-slot="budget-meter"][data-metric="load"] [data-slot="meter-value"]')
      return !!v && v.textContent.trim() !== '—'
    },
    pendingGoneSel: '[data-slot="pspd-pending"]',
    target: { max_load_ms: 2500 },
  },
  {
    kind: 'dns', Page: DnsMonitorPage, list: 'getDnsMonitors', create: 'createDnsMonitor', update: 'updateDnsMonitor', trigger: 'triggerDnsCheck',
    label: (m) => m.domain,
    row: { name: 'Web', domain: 'www.example.com', record_type: 'A', standalone: true, expected_value: null, propagation_check: false, dns_change_alert_enabled: true, slow_threshold_ms: null },
    copy: { domain: 'copy.example.com' },
    never: { value: null, previous_value: null, changed: false, rotated: false, checked_at: null, ttl: null, response_ms: null },
    result: { value: '192.0.2.10', previous_value: null, changed: false, rotated: false, checked_at: STAMP, ttl: 300, response_ms: 14 },
    resultSel: '[data-slot="dns-value"]',
    target: { record_type: 'AAAA' },
  },
  {
    kind: 'port', Page: PortMonitorPage, list: 'getPortMonitors', create: 'createPortMonitor', update: 'updatePortMonitor', trigger: 'triggerPortCheck',
    label: (m) => `${m.host}:${m.port}`,
    row: { name: 'Mail', host: 'mail.example.com', port: 443, protocol: 'TCP', expect: null, send_data: null, use_proxy: 'OFF', ip_version: 'auto',
      timeout_ms: 5000, standalone: true, slow_response_enabled: false, slow_threshold_ms: 3000 },
    copy: { host: 'copy.example.com' },
    never: { status: 'unknown', response_ms: null, checked_at: null, error: null },
    result: { status: 'open', response_ms: 18, checked_at: STAMP, error: null },
    resultSel: '[data-slot="port-result"][data-state="open"]',
    target: { port: 8443 },
  },
  {
    kind: 'domain', Page: DomainMonitorPage, list: 'getDomainMonitors', create: 'createDomainMonitor', update: 'updateDomainMonitor', trigger: 'triggerDomainCheck',
    label: (m) => m.domain,
    row: { name: 'Corporate', domain: 'example.com', thresholds_csv: '60,30,14,7,3,1', warning_days: 30, critical_days: 7, check_timeout_ms: null,
      transfer_lock_alert: true, blacklist_enabled: false, change_alert: true, renewal_planned_at: null, renewal_overdue: false },
    copy: { domain: 'example.org' },
    never: { status: 'UNKNOWN', source: null, days_remaining: null, expiry_date: null, registration_date: null, last_changed: null, registrar: null,
      status_codes: [], nameservers: [], transfer_lock: 'UNKNOWN', blacklist_status: 'UNKNOWN', error: null, checked_at: null },
    result: { status: 'OK', source: 'RDAP', days_remaining: 200, expiry_date: '2027-04-16', registration_date: '2014-03-01', last_changed: '2026-04-16',
      registrar: 'Example Registrar Ltd.', status_codes: [], nameservers: ['ns1.example.com'], transfer_lock: 'BOTH', blacklist_status: 'SKIPPED', error: null, checked_at: STAMP },
    resultSel: '[data-slot="domain-days"]',
    target: { domain: 'example.net' },
  },
]

const esc = (s) => s.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')

/** Kartın eylem düğmelerinin satır adıyla (`<rowLabel> — …`) kartı bulur — başlık metni türlere göre kısaltılıyor. */
function cardFor(rowLabel) {
  const re = new RegExp(`^${esc(rowLabel)} — `)
  const btn = [...document.querySelectorAll('[data-slot="card"] button')].find((b) => re.test(b.getAttribute('aria-label') || ''))
  return btn ? btn.closest('[data-slot="card"]') : null
}
const findCard = (rowLabel) => waitFor(() => { const c = cardFor(rowLabel); if (!c) throw new Error(`kart yok: ${rowLabel}`); return c })
const actionOf = (card, rowLabel, name) => [...card.querySelectorAll('button')]
  .find((b) => b.getAttribute('aria-label') === `${rowLabel} — ${name}`)
const saveButton = () => screen.findByRole('button', { name: /^(Save|Kaydet)$/ })

function deferred() {
  let resolve
  const p = new Promise((r) => { resolve = r })
  return { p, resolve }
}

beforeEach(() => {
  vi.clearAllMocks()
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }] })
  api.monitoring.listGroups.mockResolvedValue({ success: true, data: [{ name: 'Kurumsal Web' }] })
  api.monitoring.listTags.mockResolvedValue({ success: true, data: [] })
  api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: {} })
})

describe.each(TYPES)('kayıt sonrası kontrol — $kind', (T) => {
  const src = { id: 1, ...COMMON, ...T.row, ...T.result }

  it('oluşturma: kontrol YENİ kimlikle çağrılır; pencere kapanır, yeni kart "İlk kontrol yapılıyor…" der, yanıtla sonuç gösterir', async () => {
    const created = { id: 2, ...COMMON, ...T.row, ...T.copy, name: 'Copy', ...T.never }
    api.monitoring[T.list].mockResolvedValue({ success: true, data: [src] })
    api.monitoring[T.create].mockImplementation(async () => {
      // Sunucu kaydı yazdı: sonraki liste çağrısı yeni (hiç kontrol edilmemiş) satırı da döner.
      api.monitoring[T.list].mockResolvedValue({ success: true, data: [src, created] })
      return { success: true, data: created }
    })
    const check = deferred()
    api.monitoring[T.trigger].mockReturnValue(check.p)

    render(<T.Page systemRole="ADMIN" teamId={5} teamName="Takım A" />)
    const srcCard = await findCard(T.label(src))
    fireEvent.click(actionOf(srcCard, T.label(src), 'Duplicate'))
    const save = await saveButton()
    T.fillCopy?.()
    fireEvent.click(save)

    await waitFor(() => expect(api.monitoring[T.create]).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(api.monitoring[T.trigger]).toHaveBeenCalledWith(2))
    expect(api.monitoring[T.trigger]).toHaveBeenCalledTimes(1)
    // Liste kontrolden ÖNCE tazelendi (kart ızgarada) ve pencere kapandı — kullanıcı boş bir şeye bakmıyor.
    const card = await findCard(T.label(created))
    expect(screen.queryByRole('button', { name: /^(Save|Kaydet)$/ })).toBeNull()
    expect(card).toHaveAttribute('data-running', 'true')
    expect(card.querySelector('[data-slot="monitor-first-check"]')).toHaveTextContent(/^(Running the first check…|İlk kontrol yapılıyor…)$/)
    expect(T.hasResult ? T.hasResult(card) : !!card.querySelector(T.resultSel)).toBe(false)

    await act(async () => { check.resolve({ success: true, data: { ...created, ...T.result } }) })

    await waitFor(() => expect(cardFor(T.label(created))).not.toHaveAttribute('data-running'))
    const done = cardFor(T.label(created))
    expect(done.querySelector('[data-slot="monitor-first-check"]')).toBeNull()
    expect(T.hasResult ? T.hasResult(done) : !!done.querySelector(T.resultSel)).toBe(true)
    if (T.pendingGoneSel) expect(done.querySelector(T.pendingGoneSel)).toBeNull()
  })

  it('düzenleme: kontrolü etkileyen alan değişti → aynı izleme için taze kontrol', async () => {
    api.monitoring[T.list].mockResolvedValue({ success: true, data: [src] })
    const saved = { ...src, ...T.target }
    api.monitoring[T.update].mockImplementation(async () => {
      api.monitoring[T.list].mockResolvedValue({ success: true, data: [saved] })
      return { success: true, data: saved }
    })
    api.monitoring[T.trigger].mockResolvedValue({ success: true, data: { ...saved, ...T.result } })

    render(<T.Page systemRole="ADMIN" teamId={5} teamName="Takım A" />)
    const card = await findCard(T.label(src))
    fireEvent.click(actionOf(card, T.label(src), 'Edit'))
    fireEvent.click(await saveButton())

    await waitFor(() => expect(api.monitoring[T.update]).toHaveBeenCalledTimes(1))
    expect(api.monitoring[T.update].mock.calls[0][0]).toBe(1)
    await waitFor(() => expect(api.monitoring[T.trigger]).toHaveBeenCalledWith(1))
    expect(api.monitoring[T.trigger]).toHaveBeenCalledTimes(1)
  })

  it('düzenleme: yalnız meta (ad, etiket) → kontrol BAŞLAMAZ', async () => {
    api.monitoring[T.list].mockResolvedValue({ success: true, data: [src] })
    const saved = { ...src, name: 'Renamed', tags: 'prod,web' }
    api.monitoring[T.update].mockResolvedValue({ success: true, data: saved })

    render(<T.Page systemRole="ADMIN" teamId={5} teamName="Takım A" />)
    const card = await findCard(T.label(src))
    fireEvent.click(actionOf(card, T.label(src), 'Edit'))
    fireEvent.click(await saveButton())

    await waitFor(() => expect(api.monitoring[T.update]).toHaveBeenCalledTimes(1))
    // Kayıt akışı sonuna kadar koşsun (liste tazelenir, pencere kapanır) — ancak o zaman "çağrılmadı" anlamlıdır.
    await waitFor(() => expect(screen.queryByRole('button', { name: /^(Save|Kaydet)$/ })).toBeNull())
    await act(async () => {})
    expect(api.monitoring[T.trigger]).not.toHaveBeenCalled()
  })
})
