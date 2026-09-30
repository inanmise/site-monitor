import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { Checkbox } from '@/components/shadcn/checkbox'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => (s ? `exact:${s}` : ''),
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      // Bakım rozeti modül önbellekli tek istek atar — dosya boyunca aynı bakım hedefi.
      maintenance: { active: vi.fn(() => Promise.resolve({ success: true, data: { all: false, targets: ['https://maint.example.com/'] } })) },
    },
  }),
}))

import HttpMonitorCard from '../components/http/HttpMonitorCard.jsx'
import MonitorCardActions from '../components/MonitorCardActions.jsx'
import MonitorCardMeta from '../components/MonitorCardMeta.jsx'
import { CARD_CHECK } from '../components/monitoring/MonitorCard.jsx'
import {
  httpFailureReason, isCustomExpected, metaRow, methodOf, requestChips, responseView, statusVerdict,
} from '../components/http/httpCardModel.js'

const stamp = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const URL_ = 'https://www.example.com/'

const base = {
  id: 7, name: 'Corporate site', url: URL_, method: 'GET', expected_status: '200-399', follow_redirects: true,
  verify_ssl: false, check_ssl_errors: false, ssl_expiry_reminders: false, domain_expiry_reminders: false,
  status: 'up', ok: true, http_status: 200, response_ms: 212, error: null, active: true, team_id: 1, team_name: 'Takım A',
  group_name: 'Corporate Web', tags: 'prod', interval_seconds: 600, timeout_ms: 7000, use_proxy: 'AUTO',
  proxy_effective: 'direct', proxy_source: 'none', proxy_bypassed: false, checked_at: stamp(5 * 60_000),
}

/** 24 saatlik saatlik kova dizisi (useSparklines biçimi) — ms değerleri verilir. */
const sparkOf = (msList) => ({
  n: msList.length * 60, fail: 0, up_pct: 100, last: [],
  buckets: msList.map((ms, h) => ({ t: `2026-09-27T${String(h).padStart(2, '0')}`, n: 60, fail: 0, ms })),
})

const statusKey = (m) => (m.status === 'up' ? 'up' : m.status === 'unknown' ? 'unknown' : 'down')
const cardOf = (c) => c.querySelector('[data-slot="card"]')
const slot = (root, name) => root.querySelector(`[data-slot="${name}"]`)
const tile = (c, metric) => c.querySelector(`[data-slot="http-metric"][data-metric="${metric}"]`)
const sub = (el) => el.querySelector('[data-slot="http-metric-sub"]')?.textContent ?? null
const chip = (c, key) => c.querySelector(`[data-slot="http-chip"][data-chip="${key}"]`)

function renderCard(monitor = {}, props = {}) {
  const m = { ...base, ...monitor }
  return render(<HttpMonitorCard monitor={m} status={statusKey(m)} onOpen={props.onOpen || (() => {})}
    meta={<MonitorCardMeta monitor={metaRow(m)} />} {...props} />)
}

describe('httpCardModel — saf yardımcılar', () => {
  it('hüküm sunucunun durumundan: up = ok (beklenen 404 bile yeşil), down = mismatch, error = yanıt yok, diğeri bekleyen', () => {
    const v = (m) => { const r = statusVerdict(m); return `${r.kind}/${r.tone}/${r.cls}` }
    expect(v({ status: 'up', http_status: 200 })).toBe('ok/ok/2xx')
    expect(v({ status: 'up', http_status: 404, expected_status: '404' })).toBe('ok/ok/4xx')
    expect(v({ status: 'up', http_status: 301 })).toBe('ok/ok/3xx')
    expect(v({ status: 'down', http_status: 503 })).toBe('mismatch/bad/5xx')
    expect(v({ status: 'error', http_status: null, error: 'x' })).toBe('error/bad/null')
    expect(v({ status: 'unknown' })).toBe('pending/neutral/null')
    expect(v({})).toBe('pending/neutral/null')
  })

  it('neden: istisna türleri (zaman aşımı / DNS / TLS / reddedildi / SSRF / yapılandırma / ham) + yanıt kodu (4xx / 5xx / beklenmeyen) beklenenle', () => {
    const kind = (m) => httpFailureReason(m)?.kind ?? null
    expect(kind({ status: 'up', http_status: 500 })).toBeNull()
    expect(kind({ status: 'unknown' })).toBeNull()
    expect(httpFailureReason({ status: 'error', error: 'HTTP connect timed out', timeout_ms: 10000 })).toEqual({ kind: 'timeout', detail: 10000 })
    expect(kind({ status: 'error', error: 'çözümlenemeyen host: old.example.org' })).toBe('dns')
    expect(kind({ status: 'error', error: 'PKIX path building failed: unable to find valid certification path' })).toBe('tls')
    expect(kind({ status: 'error', error: 'java.net.ConnectException: Connection refused' })).toBe('refused')
    expect(kind({ status: 'error', error: 'izin verilmeyen hedef x → 10.0.0.5 (site-local)' })).toBe('blocked')
    expect(kind({ status: 'error', error: "yapılandırma hatası: URL'de geçerli bir host yok (şema eksik veya bozuk)" })).toBe('config')
    expect(httpFailureReason({ status: 'error', error: 'garip\nikinci' })).toEqual({ kind: 'error', detail: 'garip' })
    expect(httpFailureReason({ status: 'down', http_status: 503 })).toEqual({ kind: 'http5xx', detail: 503, expected: '200-399' })
    expect(httpFailureReason({ status: 'down', http_status: 404, expected_status: '200' })).toEqual({ kind: 'http4xx', detail: 404, expected: '200' })
    expect(httpFailureReason({ status: 'down', http_status: 200, expected_status: ' 204 ' })).toEqual({ kind: 'mismatch', detail: 200, expected: '204' })
    expect(httpFailureReason({ status: 'down', http_status: null })).toEqual({ kind: 'down', detail: null })
  })

  it('süre: zaman aşımında bad; yanıt hiç gelmediyse değer yok + "N ms sonra"; yavaşlık eşiği satırda varsa warn/ok; yoksa nötr', () => {
    expect(responseView({ status: 'error', error: 'timed out', response_ms: 10004, timeout_ms: 10000 })).toMatchObject({ tone: 'bad', timedOut: true, parts: { num: '10', unit: 's' } })
    expect(responseView({ status: 'error', error: 'çözümlenemeyen host: x', response_ms: 4 })).toMatchObject({ tone: 'neutral', parts: null, failedAfter: '4 ms' })
    expect(responseView({ status: 'up', response_ms: 3480, slow_response_enabled: true, slow_threshold_ms: 3000 })).toMatchObject({ tone: 'warn', limit: 3000 })
    expect(responseView({ status: 'up', response_ms: 212, slow_response_enabled: true, slow_threshold_ms: 3000 }).tone).toBe('ok')
    expect(responseView({ status: 'up', response_ms: 9000 })).toMatchObject({ tone: 'neutral', limit: null, parts: { num: '9', unit: 's' } })
  })

  it('istek çipleri: beklenen kod HER ZAMAN (varsayılan nötr, elle değiştirilmiş vurgulu — 2026-09-30); diğerleri yalnız varsayılandan farklıysa; yöntem büyük harf, yoksa GET', () => {
    expect(requestChips(base)).toEqual([{ key: 'expected', value: '200-399', custom: false }])
    expect(requestChips({ ...base, expected_status: '200-403' })).toEqual([{ key: 'expected', value: '200-403', custom: true }])
    expect(methodOf({ method: 'post' })).toBe('POST')
    expect(methodOf({})).toBe('GET')
    expect(isCustomExpected({ expected_status: ' 200 - 399 ' })).toBe(false)
    expect(isCustomExpected({ expected_status: '' })).toBe(false)
    expect(isCustomExpected({ expected_status: '2xx' })).toBe(true)
    const keys = (m) => requestChips({ ...base, ...m }).map((c) => (c.key === 'alerts' ? `alerts:${c.variant}` : c.key))
    expect(keys({ url: 'http://legacy.example.net/', expected_status: '201', follow_redirects: false, verify_ssl: true, check_ssl_errors: true, ssl_expiry_reminders: true }))
      .toEqual(['plain', 'expected', 'redirects', 'strictTls', 'alerts:both'])
    expect(keys({ check_ssl_errors: true })).toEqual(['expected', 'alerts:tls'])
    expect(keys({ domain_expiry_reminders: true })).toEqual(['expected', 'alerts:expiry'])
    expect(keys({ follow_redirects: undefined })).toEqual(['expected'])   // yalnız açıkça false; beklenen kod her zaman
  })
})

describe('HttpMonitorCard', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('2xx sağlıklı: HTTP kutusu yeşil "200 Success", süre nötr + zaman aşımı sınırı; neden yok; kenar kırmızı DEĞİL', () => {
    const { container } = renderCard()
    const status = tile(container, 'status')
    expect(status).toHaveAttribute('data-tone', 'ok')
    expect(status.textContent).toBe('HTTP status200Success')
    const resp = tile(container, 'response')
    expect(resp).toHaveAttribute('data-tone', 'neutral')
    expect(resp.querySelector('[data-slot="http-metric-value"]').textContent).toBe('212ms')
    expect(sub(resp)).toBe('Times out at 7 s')
    expect(slot(container, 'http-reason')).toBeNull()
    expect(cardOf(container)).toHaveAttribute('data-result', 'ok')
    expect(cardOf(container).className).not.toMatch(/border-destructive/)
  })

  it('24 sa trendi varsa süre alt satırı ortalamaya göre sapmayı söyler (eşik uydurmadan, ton nötr)', () => {
    const { container } = renderCard({ response_ms: 300 }, { spark: sparkOf([200, 200, 200]) })
    expect(sub(tile(container, 'response'))).toBe('+50% vs 24 h avg')
    expect(tile(container, 'response')).toHaveAttribute('data-tone', 'neutral')
  })

  it('3xx: "Redirect" yeşil (beklenen aralıkta); yönlendirme takip edilmiyorsa çip + açıklaması dokun-gör', async () => {
    const { container } = renderCard({ http_status: 301, follow_redirects: false })
    expect(tile(container, 'status').textContent).toBe('HTTP status301Redirect')
    expect(tile(container, 'status')).toHaveAttribute('data-tone', 'ok')
    expect(chip(container, 'redirects').textContent).toBe('Redirects not followed')
    fireEvent.click(screen.getByRole('button', { name: `${URL_} — Redirects not followed` }))
    expect((await screen.findByRole('tooltip')).textContent).toBe('Redirects aren’t followed, so a 3xx reply is the final status.')
  })

  it('4xx kapalı: HTTP kutusu kırmızı "Client error", neden beklenenle; kapalı kart TÜM kenarı kırmızı — sol şerit YOK', () => {
    const { container } = renderCard({ status: 'down', ok: false, http_status: 404 })
    expect(tile(container, 'status')).toHaveAttribute('data-tone', 'bad')
    expect(tile(container, 'status').textContent).toBe('HTTP status404Client error')
    const reason = slot(container, 'http-reason')
    expect(reason).toHaveAttribute('data-reason', 'http4xx')
    expect(reason).toHaveAttribute('role', 'note')
    expect(reason.textContent).toBe('Client error 404 — expected 200-399')
    const card = cardOf(container)
    expect(card).toHaveAttribute('data-status', 'down')
    expect(card).toHaveAttribute('data-result', 'mismatch')
    expect(card.className).toMatch(/(^|\s)border-destructive\/45(\s|$)/)
    expect(card.className).not.toMatch(/border-l-|before:/)
  })

  it('5xx + alarm: seviye rozeti, tüm kart dış çizgisi (çift ton yok), neden "Server error 503"', () => {
    const { container } = renderCard({ status: 'down', ok: false, http_status: 503, active_alarm: true, alarm_level: 'CRITICAL', alarm_acknowledged: false })
    const card = cardOf(container)
    expect(card).toHaveAttribute('data-alarm', 'true')
    expect(card.querySelector('[data-slot="monitor-alarm"]')).toHaveAttribute('data-level', 'CRITICAL')
    expect(card.className).not.toMatch(/border-destructive\/45/)
    expect(slot(container, 'http-reason').textContent).toBe('Server error 503 — expected 200-399')
    expect(tile(container, 'status').textContent).toMatch(/503Server error$/)
  })

  it('beklenmeyen 2xx (204 bekleniyor, 200 geldi): kırmızı "Unexpected status" — kırmızı "Success" çelişkisi yok; beklenen çipi + açıklaması', async () => {
    const { container } = renderCard({ status: 'down', ok: false, http_status: 200, expected_status: '204' })
    expect(tile(container, 'status').textContent).toBe('HTTP status200Unexpected status')
    expect(slot(container, 'http-reason')).toHaveAttribute('data-reason', 'mismatch')
    expect(slot(container, 'http-reason').textContent).toBe('Returned 200 — expected 204')
    expect(chip(container, 'expected').textContent).toBe('Expects 204')
    fireEvent.click(screen.getByRole('button', { name: `${URL_} — Expects 204` }))
    expect((await screen.findByRole('tooltip')).textContent).toBe('The check only passes if the server replies with: 204')
  })

  it('zaman aşımı: HTTP "— No response" kırmızı, süre kırmızı "10 s · Timed out", neden süreyle', () => {
    const { container } = renderCard({ status: 'error', ok: false, http_status: null, response_ms: 10004, timeout_ms: 10000, error: 'HTTP connect timed out' })
    expect(tile(container, 'status').textContent).toBe('HTTP status—No response')
    expect(tile(container, 'status')).toHaveAttribute('data-tone', 'bad')
    const resp = tile(container, 'response')
    expect(resp).toHaveAttribute('data-tone', 'bad')
    expect(resp.querySelector('[data-slot="http-metric-value"]').textContent).toBe('10s')
    expect(sub(resp)).toBe('Timed out')
    expect(slot(container, 'http-reason')).toHaveAttribute('data-reason', 'timeout')
    expect(slot(container, 'http-reason').textContent).toBe('The request timed out after 10 s')
    expect(cardOf(container)).toHaveAttribute('data-result', 'error')
  })

  it('DNS / TLS / SSRF: süre kutusu değer göstermez ("Failed after N ms"), neden türü ve metni doğru', () => {
    const dns = renderCard({ status: 'error', ok: false, http_status: null, response_ms: 4, error: 'çözümlenemeyen host: old.example.org' })
    expect(tile(dns.container, 'response').querySelector('[data-slot="http-metric-value"]').textContent).toBe('—')
    expect(sub(tile(dns.container, 'response'))).toBe('Failed after 4 ms')
    expect(slot(dns.container, 'http-reason').textContent).toBe('The host name couldn’t be resolved (DNS)')
    dns.unmount()
    const tls = renderCard({ status: 'error', ok: false, http_status: null, response_ms: 79, verify_ssl: true,
      error: 'PKIX path building failed: unable to find valid certification path\nCaused by …' })
    expect(slot(tls.container, 'http-reason')).toHaveAttribute('data-reason', 'tls')
    expect(slot(tls.container, 'http-reason').textContent).toBe('TLS/certificate error: PKIX path building failed: unable to find valid certification path')
    expect(chip(tls.container, 'strictTls').textContent).toBe('Strict TLS')
    tls.unmount()
    const ssrf = renderCard({ status: 'error', ok: false, http_status: null, response_ms: 2, error: 'izin verilmeyen hedef admin.example.com → 10.0.0.5' })
    expect(slot(ssrf.container, 'http-reason')).toHaveAttribute('data-reason', 'blocked')
  })

  it('yavaşlık eşiği satırda varsa (ileriye uyum): "Slow" rozeti + "Limit 3 s" amber', () => {
    const { container } = renderCard({ response_ms: 3480, slow_response_enabled: true, slow_threshold_ms: 3000 })
    const resp = tile(container, 'response')
    expect(resp).toHaveAttribute('data-tone', 'warn')
    expect(resp.querySelector('[data-slot="http-metric-verdict"]').textContent).toBe('Slow')
    expect(sub(resp)).toBe('Limit 3 s')
  })

  it('yöntem rozeti: GET soluk, POST vurgulu; TLS/bitiş alarm çipi tek çip, ayrıntı balonda; düz http amber şema + "Unencrypted"', async () => {
    const get = renderCard()
    expect(slot(get.container, 'http-method')).toHaveAttribute('data-method', 'GET')
    expect(slot(get.container, 'http-method').className).toMatch(/text-muted-foreground/)
    get.unmount()
    const post = renderCard({ method: 'POST', expected_status: '201', http_status: 201, check_ssl_errors: true, ssl_expiry_reminders: true, ssl_reminder_days: '45,20,5' })
    expect(slot(post.container, 'http-method').textContent).toBe('POST')
    expect(slot(post.container, 'http-method').className).toMatch(/text-primary/)
    expect(chip(post.container, 'alerts').textContent).toBe('TLS & expiry alerts')
    fireEvent.click(screen.getByRole('button', { name: `${URL_} — TLS & expiry alerts` }))
    expect((await screen.findByRole('tooltip')).textContent).toBe('Raises an alert on TLS or certificate errors.\nCertificate expiry reminders: 45, 20, 5 days before')
    post.unmount()
    const plainUrl = 'http://legacy.example.net/'
    const plain = renderCard({ url: plainUrl })
    const scheme = slot(plain.container, 'http-scheme')
    expect(scheme.textContent).toBe('http://')
    expect(scheme).toHaveAttribute('data-plain', 'true')
    expect(scheme.className).toMatch(/text-amber-700/)
    expect(chip(plain.container, 'plain').textContent).toBe('Unencrypted')
  })

  it('vekil kipi ON: "Always via proxy" çipi, meta yol rozeti tekrar ÇİZİLMEZ; AUTO: çip yok, yol rozeti var', () => {
    const on = renderCard({ use_proxy: 'ON', proxy_effective: 'proxy', proxy_source: 'monitor' })
    expect(screen.getByRole('button', { name: `${URL_} — Always via proxy` })).toBeInTheDocument()
    expect(slot(on.container, 'meta-proxy')).toBeNull()
    on.unmount()
    const auto = renderCard({ use_proxy: 'AUTO', proxy_effective: 'proxy' })
    expect(screen.queryByRole('button', { name: `${URL_} — Always via proxy` })).toBeNull()
    expect(slot(auto.container, 'meta-proxy')).not.toBeNull()
  })

  it('hiç kontrol edilmemiş: ölçü kutusu yok, kesik "Waiting for the first check", alt çubukta "Not checked yet"', () => {
    const { container } = renderCard({ status: 'unknown', ok: null, http_status: null, response_ms: null, checked_at: null })
    expect(tile(container, 'status')).toBeNull()
    expect(slot(container, 'http-pending').textContent).toBe('Waiting for the first check')
    expect(slot(container, 'http-pending').className).toMatch(/border-dashed/)
    expect(container.querySelector('[data-slot="card-footer"]').textContent).toMatch(/Not checked yet/)
  })

  it('duraklatılmış: kesik/soluk kart + "Paused" + Sürdür; bakım rozeti; son kontrol göreli, tam zaman ekran okuyucuda', async () => {
    const onResume = vi.fn()
    const paused = renderCard({ active: false }, {
      actions: <MonitorCardActions rowLabel={URL_} onResume={onResume} onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} checkTitle="Check now" editTitle="Edit" />,
    })
    expect(cardOf(paused.container)).toHaveAttribute('data-inactive', 'true')
    expect(paused.container.querySelector('[data-slot="card-footer"] [data-slot="monitor-paused"]')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: `${URL_} — Resume` }))
    expect(onResume).toHaveBeenCalledTimes(1)
    const time = paused.container.querySelector('[data-slot="ping-checked-at"]')
    expect(time.textContent).toMatch(/^5 min ago/)
    expect(time.textContent).toMatch(/\(exact:/)
    paused.unmount()
    const maint = renderCard({ url: 'https://maint.example.com/' })
    await waitFor(() => expect(maint.container.querySelector('[data-slot="maintenance-badge"]')).not.toBeNull())
  })

  it('başlık GERÇEK düğme ve detayı açar; URL kopyala / seçim kutusu / eylemler detayı AÇMAZ; adlar satırı taşır', async () => {
    const onOpen = vi.fn()
    const onSel = vi.fn()
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: vi.fn(() => Promise.resolve()) } })
    const { container } = renderCard({}, {
      onOpen,
      select: <Checkbox className={CARD_CHECK} checked={false} onCheckedChange={onSel} aria-label={`Select ${URL_} for bulk action`} />,
      actions: <MonitorCardActions rowLabel={URL_} onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} onDelete={() => {}} checkTitle="Check now" editTitle="Edit" deleteTitle="Delete" />,
    })
    const title = screen.getByRole('button', { name: `${URL_} — open details` })
    expect(title).toHaveAttribute('data-monitor-open', 'true')
    expect(title.textContent).toBe('www.example.com')   // https:// ve kök "/" gizli
    expect(title.querySelector('[title]')).toHaveAttribute('title', URL_)
    fireEvent.click(title)
    expect(onOpen).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: `${URL_} — Copy URL` }))
    await waitFor(() => expect(navigator.clipboard.writeText).toHaveBeenCalledWith(URL_))
    fireEvent.click(screen.getByRole('checkbox', { name: `Select ${URL_} for bulk action` }))
    expect(onSel).toHaveBeenCalled()
    for (const name of [/— Check now$/, /— Edit$/, /— (Duplicate|Kopyala)$/, /— Delete$/]) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument()
    }
    expect(onOpen).toHaveBeenCalledTimes(1)
    expect(slot(container, 'meta-team')).not.toBeNull()
    expect(slot(container, 'http-name').textContent).toBe('Corporate site')
    expect(slot(container, 'http-interval').textContent).toBe('every 10 min')
    expect(container.querySelector('[data-slot="ping-tags"]').textContent).toMatch(/prod/)
  })

  it('telefon: ölçüler iki sütun, kopyala 40 px dokunma, çip tetiği 40 px, uzun URL kırpılır, istek satırı sarar', () => {
    const longUrl = 'https://odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com/giris/kullanici?lang=tr&utm_source=kampanya'
    const { container } = renderCard({ url: longUrl, expected_status: '200' })
    expect(slot(container, 'monitor-metrics').className).toMatch(/(^|\s)grid-cols-2(\s|$)/)
    expect(screen.getByRole('button', { name: /— Copy URL$/ }).className).toContain('pointer-coarse:size-10')
    expect(screen.getByRole('button', { name: `${longUrl} — Expects 200` }).className).toContain('pointer-coarse:min-h-10')
    const title = container.querySelector('[data-monitor-open]')
    expect(title.querySelector('.truncate')).not.toBeNull()
    expect(title.className).toMatch(/min-w-0/)
    expect(slot(container, 'http-request').className).toMatch(/flex-wrap/)
    expect(slot(container, 'http-reason')).toBeNull()
  })
})

/**
 * KART YOĞUNLUĞU (2026-09-27): Kompakt = durum satırı · URL · TEK ikincil satır (ad + takım) · TEK ana ölçü satırı
 * (HTTP kodu + yanıt süresi) · düşükse TEK satır neden · alt çubuk. İstek satırı, ölçü kutuları, trend/SLA,
 * grup/vekil/etiket yalnız Zengin'de — Kompakt'ta DOM'a HİÇ girmez.
 */
describe('HttpMonitorCard — Kompakt / Zengin', () => {
  beforeEach(() => { vi.clearAllMocks() })
  const RICH_ONLY = ['http-request', 'http-metric', 'monitor-spark', 'monitor-card-meta', 'meta-group', 'meta-proxy', 'ping-tags', 'keyword-proxy']
  const compactValue = (c, metric) => c.querySelector(`[data-slot="http-compact"] [data-slot="compact-value"][data-metric="${metric}"]`)

  it('Kompakt: Zengin-özel bölümler YOK; durum, URL, ad + YALNIZ takım, "200 HTTP · 212 ms", zaman ve AYNI eylemler var', () => {
    const onSel = vi.fn()
    const { container } = renderCard({ use_proxy: 'ON', proxy_effective: 'proxy' }, {
      density: 'compact', spark: sparkOf([200, 210, 220]), badge: <span>Up</span>,
      select: <Checkbox className={CARD_CHECK} checked={false} onCheckedChange={onSel} aria-label={`Select ${URL_} for bulk action`} />,
      actions: <MonitorCardActions rowLabel={URL_} onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} onDelete={() => {}} checkTitle="Check now" editTitle="Edit" deleteTitle="Delete" />,
    })
    expect(cardOf(container)).toHaveAttribute('data-density', 'compact')
    expect(slot(container, 'monitor-card-rich')).toBeNull()
    for (const s of RICH_ONLY) expect(slot(container, s), s).toBeNull()
    expect(screen.getByRole('button', { name: `${URL_} — open details` })).toBeInTheDocument()
    const sub = slot(container, 'card-compact-sub')
    expect(slot(sub, 'http-name').textContent).toBe('Corporate site')
    expect(slot(sub, 'meta-team').textContent).toMatch(/Takım A/)
    expect(compactValue(container, 'status').textContent).toBe('200HTTP')
    expect(compactValue(container, 'status')).toHaveAttribute('data-tone', 'ok')
    expect(compactValue(container, 'response').textContent).toBe('212msResponse')
    expect(slot(container, 'http-reason')).toBeNull()
    expect(slot(container, 'ping-checked-at').textContent).toMatch(/^5 min ago/)
    for (const name of [/— Check now$/, /— Edit$/, /— (Duplicate|Kopyala)$/, /— Delete$/, /— Copy URL$/, /— Copy link$|Bağlantıyı kopyala$/]) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument()
    }
    fireEvent.click(screen.getByRole('checkbox', { name: `Select ${URL_} for bulk action` }))
    expect(onSel).toHaveBeenCalled()
  })

  it('Zengin (varsayılan): istek satırı, iki ölçü kutusu, trend, meta + vekil çipi + etiketler; kompakt parçalar YOK', () => {
    const { container } = renderCard({ use_proxy: 'ON', proxy_effective: 'proxy' }, { spark: sparkOf([200, 210, 220]) })
    expect(cardOf(container)).toHaveAttribute('data-density', 'rich')
    for (const s of RICH_ONLY.filter((s) => s !== 'meta-proxy')) expect(slot(container, s), s).not.toBeNull()
    expect(container.querySelectorAll('[data-slot="http-metric"]')).toHaveLength(2)
    expect(slot(container, 'card-compact-sub')).toBeNull()
    expect(slot(container, 'http-compact')).toBeNull()
  })

  it('Kompakt 5xx düşük: kod kırmızı + TEK satır neden (beklenenle), tamamı dokun-gör balonunda, ad satırı taşır, detayı AÇMAZ', async () => {
    const onOpen = vi.fn()
    const { container } = renderCard({ status: 'down', ok: false, http_status: 503, response_ms: 90 }, { density: 'compact', onOpen })
    expect(compactValue(container, 'status').textContent).toBe('503HTTP')
    expect(compactValue(container, 'status')).toHaveAttribute('data-tone', 'bad')
    const reason = slot(container, 'http-reason')
    expect(reason).toHaveAttribute('data-compact', 'true')
    expect(reason).toHaveAttribute('data-reason', 'http5xx')
    expect(reason.querySelector('.truncate')).not.toBeNull()
    const trigger = screen.getByRole('button', { name: `${URL_} — Server error 503 — expected 200-399` })
    expect(trigger.className).toContain('pointer-coarse:min-h-10')
    fireEvent.click(trigger)
    expect((await screen.findByRole('tooltip')).textContent).toBe('Server error 503 — expected 200-399')
    expect(onOpen).not.toHaveBeenCalled()
  })

  it('Kompakt yanıt yok / yavaş / bekleyen: yanıt yoksa ölçü satırı YOK (neden satırı söyler); eşik aşıldıysa amber + "Slow"; hiç kontrol yoksa soluk tek satır', () => {
    const dns = renderCard({ status: 'error', ok: false, http_status: null, response_ms: 3, error: 'çözümlenemeyen host: www.example.com' }, { density: 'compact' })
    expect(dns.container.querySelector('[data-slot="http-compact"]')).toBeNull()
    expect(dns.container.querySelector('[data-slot="http-reason"]')).toHaveAttribute('data-reason', 'dns')
    dns.unmount()
    const slow = renderCard({ response_ms: 4200, slow_response_enabled: true, slow_threshold_ms: 3000 }, { density: 'compact' })
    expect(compactValue(slow.container, 'response')).toHaveAttribute('data-tone', 'warn')
    expect(compactValue(slow.container, 'response').textContent).toBe('4.2sResponse')
    expect(slow.container.querySelector('[data-slot="compact-verdict"]').textContent).toBe('Slow')
    slow.unmount()
    const pending = renderCard({ status: 'unknown', ok: null, http_status: null, response_ms: null, checked_at: null }, { density: 'compact' })
    const p = pending.container.querySelector('[data-slot="http-pending"]')
    expect(p).toHaveAttribute('data-compact', 'true')
    expect(p.textContent).toBe('Waiting for the first check')
    expect(pending.container.querySelector('[data-slot="http-compact"]')).toBeNull()
  })
})
