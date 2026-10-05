import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

/**
 * Uptime detayı — SSL geçmişi (2026-09-28): sertifika penceresinin ZENGİN Kontrol Geçmişi (CertCheckHistory); HTTP geçmişi
 * paylaşılan genel görünümde kalır. Korunan Uptime davranışları: TEK tarih seçici iki geçmişi sürer (kontrollü aralık — ön ayar
 * çubuğu yok, canlı yenileme yok), adres çubuğuna yazılmaz, pencere her açılışta aralığı BUGÜNE döndürür (openModal).
 * Eski satırın bilgileri (zaman, durum, kalan gün, hata) yerinde; yanıt süresi satır ayrıntısında.
 * Tel biçimi GERÇEK (snake_case, UTC `Z`siz); zamanlar `Date.now()`'dan türer.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', async (importOriginal) => {
  const real = await importOriginal()
  return {
    ...real,
    api: withApiFallback({
      monitoring: {
        getUptimeOverview: vi.fn(),
        getCheckHistory: vi.fn(),
        getCheckHistoryCsvUrl: vi.fn((kind, id) => `/api/monitoring/uptime/${id}/${kind}?format=csv`),
        getSslResponseSeries: vi.fn(),
      },
    }),
  }
})
import { api } from '../api/client'
import UptimePage from '../components/UptimePage.jsx'

const H = 3_600_000
const D = 24 * H
const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const inDays = (d) => new Date(Date.now() + d * D).toISOString().slice(0, 19)
const isoOf = (d) => d.toISOString().slice(0, 19)
const todayStart = () => { const d = new Date(); d.setHours(0, 0, 0, 0); return d }
const PKIX = 'PKIX path building failed: unable to find valid certification path to requested target (www.example.com:443)'

const ROWS = ['www.example.com', 'shop.example.com'].map((domain) => ({
  domain, port: 443, status: 'up', http_ok: true, ssl_valid_days: 120, uptime_7d: 100, uptime_30d: 99.9,
  incidents_1d: 0, incidents_7d: 0, incidents_15d: 0, incidents_30d: 0, team_name: 'Takım A', can_manage: true,
  uptime_checked_at: iso(2 * 60_000), ssl_checked_at: iso(H), response_ms: 120,
}))
const sslCheck = (id, hoursAgo, over = {}) => ({
  id, domain: 'www.example.com', checked_at: iso(hoursAgo * H), status: 'valid', warning: false, days_remaining: 120,
  not_before: iso(245 * D), not_after: inDays(120), subject: 'www.example.com', issuer_cn: 'Example TLS RSA CA 2026', issuer: 'Example Trust Ltd',
  serial_number: '0A1B2C', fingerprint: 'AA'.repeat(32), tls_version: 'TLSv1.3', cipher_suite: 'TLS_AES_256_GCM_SHA384', response_ms: 388,
  chain_status: 'VALID', revocation_status: 'VALID', trust_status: 'TRUSTED', error: null, error_class: null, maintenance: false, ...over,
})
const SSL_ITEMS = [sslCheck(2, 0.2), sslCheck(1, 0.4, { status: 'error', days_remaining: null, not_after: null, issuer: null, issuer_cn: null,
  fingerprint: null, serial_number: null, error: PKIX, error_class: 'CERT' })]
const HTTP_ITEMS = [{ id: 11, checked_at: iso(0.1 * H), status: 'up', http_status: 200, response_ms: 131, error: null }]
const env = (items, over = {}) => ({ success: true, data: {
  items, page: 0, size: 50, total: items.length, counts: { total: items.length, fail: items.filter((i) => i.error).length },
  buckets: [], alerts: [], range: { from: iso(6 * H), to: iso(0) }, ...over,
} })

let sslEmpty = false
beforeEach(() => {
  vi.clearAllMocks()
  sslEmpty = false
  localStorage.clear()
  window.history.replaceState(null, '', '/?tab=uptime')
  api.monitoring.getUptimeOverview.mockResolvedValue({ success: true, data: ROWS })
  api.monitoring.getCheckHistory.mockImplementation((kind, id, p = {}) => {
    if (kind === 'uptime-http') return Promise.resolve(env(HTTP_ITEMS))
    if (sslEmpty) return Promise.resolve(env([], { counts: { total: 0, fail: 0 } }))
    return Promise.resolve(p.status === 'fail' ? env([SSL_ITEMS[1]]) : env(SSL_ITEMS))
  })
  api.monitoring.getSslResponseSeries.mockResolvedValue({ success: true, data: { bucket: 'minute', from: iso(6 * H), to: iso(0), down_total: 1,
    series: [{ ts: iso(0.4 * H), count: 1, down: 1, days: null }, { ts: iso(0.2 * H), count: 1, down: 0, days: 120 }] } })
})

const sslCalls = () => api.monitoring.getCheckHistory.mock.calls.filter(([k, , p]) => k === 'uptime-ssl' && !(p?.status === 'fail' && p?.size === 1))
const httpCalls = () => api.monitoring.getCheckHistory.mock.calls.filter(([k]) => k === 'uptime-http')

async function openDetail(domain) {
  const title = await waitFor(() => {
    const b = screen.getAllByRole('button', { name: new RegExp(domain.replace(/\./g, '[.]')) }).find((x) => x.hasAttribute('data-monitor-open'))
    expect(b).toBeTruthy()
    return b
  })
  fireEvent.click(title)
  const dlg = await screen.findByRole('dialog')
  const cols = dlg.querySelector('[data-slot="uptime-history-cols"]')
  return { dlg, http: cols.children[0], ssl: cols.children[cols.children.length - 1] }
}

describe('Uptime detayı — SSL geçmişi zengin sertifika görünümünde', () => {
  it('SSL sütunu: sertifika kutucukları + sütunlu satırlar (kalan gün, sertifika, hata); HTTP sütunu genel görünümde kalır', async () => {
    render(<UptimePage systemRole="ADMIN" />)
    const { http, ssl } = await openDetail('www.example.com')
    await within(ssl).findByText(PKIX)
    expect(ssl.querySelector('[data-slot="cert-hist-insights"]')).not.toBeNull()
    expect(ssl.querySelectorAll('[data-slot="hist-tile"]')).toHaveLength(6)
    expect(within(ssl).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Time', 'Status', 'Days left', 'Certificate', 'Details'])
    expect([...ssl.querySelectorAll('[data-slot="cert-hist-status"]')].map((b) => b.getAttribute('data-status'))).toEqual(['ok', 'fail'])
    expect(within(ssl).getByText('120 days')).toBeInTheDocument()
    expect(ssl.querySelector('[class*="upt-rt-"]')).toBeNull()
    // Yanıt süresi kaybolmadı: başarılı satırın teknik özeti + ayrıntı
    expect(within(ssl).getByText('TLSv1.3 · 388 ms')).toBeInTheDocument()
    // HTTP sütunu: genel 4 kutucuk + ms sütunu (değişmedi)
    await within(http).findByText('131ms')
    expect(http.querySelectorAll('[data-slot="hist-tile"]')).toHaveLength(4)
    expect(http.querySelector('[data-slot="cert-hist-insights"]')).toBeNull()
  })

  it('tek seçici iki geçmişi sürer: SSL listesi + serisi bugünün aralığıyla (from/to), ön ayar çubuğu ve canlı rozet yok; URL\'ye yazılmaz', async () => {
    // "Bugünün başı" çizimden ÖNCE ve iddiada yeniden alınır: gece yarısı çizim ile iddia arasına düşerse ikisinden
    // biri doğrudur (eskiden yalnız iddia anında hesaplanıyordu → gece yarısı sınırında yanlış kırmızı; 2026-09-28c ek-2).
    const day0 = isoOf(todayStart())
    render(<UptimePage systemRole="ADMIN" />)
    const { dlg, ssl } = await openDetail('www.example.com')
    await within(ssl).findByText(PKIX)
    const p = sslCalls().at(-1)[2]
    expect([day0, isoOf(todayStart())]).toContain(p.from)
    const from = p.from
    expect(p.days).toBeUndefined()
    expect(httpCalls().at(-1)[2].from).toBe(from)                                  // aynı aralık iki sütunda
    await waitFor(() => expect(api.monitoring.getSslResponseSeries).toHaveBeenLastCalledWith('www.example.com', expect.objectContaining({ from })))
    expect(within(dlg).queryByRole('group', { name: 'Time range' })).toBeNull()
    expect(dlg.querySelector('[data-slot="hist-live"]')).toBeNull()
    const before = window.location.search
    fireEvent.click(ssl.querySelectorAll('[data-slot="hist-tile"]')[1])            // Başarısız süzgeci
    await waitFor(() => expect(sslCalls().at(-1)[2]).toMatchObject({ status: 'fail', from }))
    await new Promise((r) => setTimeout(r, 500))   // useUrlQuerySync 300 ms debounce'lu — yazım olsaydı gerçekleşsin
    expect(window.location.search).toBe(before)
    expect(new URLSearchParams(window.location.search).has('hst')).toBe(false)
  })

  it('boş SSL aralığında "son 90 gün" ÜSTTEKİ seçiciyi değiştirir (iki sütun yeniden ister); pencere yeniden açılınca aralık BUGÜNE döner', async () => {
    sslEmpty = true
    const day0 = isoOf(todayStart())   // çizimden ÖNCE (gece yarısı sınırı — bkz. önceki test)
    render(<UptimePage systemRole="ADMIN" />)
    const { dlg, ssl } = await openDetail('www.example.com')
    await within(ssl).findByText('No certificate checks in this range')
    // Uptime'da "başlıktaki Çalıştır" düğmesi yok → açıklama onu anmaz
    expect(within(ssl).getByText('This certificate wasn’t checked during the selected range. Try a wider range.')).toBeInTheDocument()
    const httpBefore = httpCalls().length
    fireEvent.click(within(ssl).getByRole('button', { name: 'Show the last 90 days' }))
    await waitFor(() => {
      const f = Date.parse(sslCalls().at(-1)[2].from + 'Z')
      expect(Math.abs(f - (Date.now() - 90 * D))).toBeLessThan(60_000)
    })
    await waitFor(() => expect(httpCalls().length).toBeGreaterThan(httpBefore))   // HTTP sütunu da aynı aralığa geçti
    expect(Math.abs(Date.parse(httpCalls().at(-1)[2].from + 'Z') - (Date.now() - 90 * D))).toBeLessThan(60_000)

    // Kapat → başka izleme: aralık bugüne döner (openModal kuralı)
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Close|Kapat)$/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    sslEmpty = false
    const second = await openDetail('shop.example.com')
    await waitFor(() => expect(sslCalls().at(-1)[1]).toBe('shop.example.com'))
    expect([day0, isoOf(todayStart())]).toContain(sslCalls().at(-1)[2].from)   // gece yarısı sınırına dayanıklı (ek-2)
    expect(second.ssl.querySelector('[data-slot="cert-hist-insights"]')).not.toBeNull()
  })
})

/**
 * HTTP sütunu dar kapta (2026-09-28, 2. tur): yan yana düzende sütun 768'de 323 px — 4 sütunlu tablo kabında yatay kayıyordu.
 * Aynı `cardsBelow` mekanizması: kap eşiğin altındaysa sütun etiketli kart listesi (zaman, durum, ms, hata — bilgi kaybı yok).
 * jsdom yerleşim yapmaz → kap genişliği `check-history` kökü için taklit edilir; gerçek ölçüm Playwright'ta.
 */
describe('Uptime detayı — HTTP geçmişi dar kapta kart listesi', () => {
  function stubWidth(width) {
    const orig = HTMLElement.prototype.getBoundingClientRect
    HTMLElement.prototype.getBoundingClientRect = function () {
      if (this.getAttribute?.('data-slot') === 'check-history') return { width, height: 900, top: 0, left: 0, right: width, bottom: 900, x: 0, y: 0 }
      return orig.call(this)
    }
    return () => { HTMLElement.prototype.getBoundingClientRect = orig }
  }
  const FAIL_HTTP = { id: 12, checked_at: iso(0.2 * H), status: 'down', http_status: 503, response_ms: 10004, error: 'Connection timed out after 10000 ms' }

  it('323 px kapta HTTP sütunu kart: zaman + durum başlıkta, "ms" etiketli süre, hata metni — tablo yok', async () => {
    api.monitoring.getCheckHistory.mockImplementation((kind, id, p = {}) => Promise.resolve(kind === 'uptime-http'
      ? env([HTTP_ITEMS[0], FAIL_HTTP]) : (p.status === 'fail' ? env([SSL_ITEMS[1]]) : env(SSL_ITEMS))))
    const restore = stubWidth(323)
    try {
      render(<UptimePage systemRole="ADMIN" />)
      const { http } = await openDetail('www.example.com')
      await within(http).findByText('131ms')
      expect(http.querySelector('[data-view="cards"]')).not.toBeNull()
      expect(within(http).queryByRole('table')).toBeNull()
      const cards = [...http.querySelectorAll('[data-slot="hist-card"]')]
      expect(cards).toHaveLength(2)
      expect(within(cards[0]).getByText('ms')).toBeInTheDocument()             // sütun etiketi korunur
      expect(within(cards[1]).getByText('10004ms')).toBeInTheDocument()
      expect(within(cards[1]).getByText(/^(Down|Kapalı|Erişilemiyor)$/)).toBeInTheDocument()
      // Hata teşhisi (2026-10-05): ham hata metni yerine okunur neden rozeti; ham metin bilgi kaybı olmadan açılan
      // panelin "Teknik ayrıntı" bloğunda (kopyalanabilir).
      expect(within(cards[1]).getByText(/^(Connection timeout|Bağlantı zaman aşımı)$/)).toBeInTheDocument()
      fireEvent.click(within(cards[1]).getByRole('button', { name: /show details|ayrıntıyı göster/i }))
      const panel = await within(cards[1]).findByRole('region', { name: /failure detail|hata ayrıntısı/i })
      expect(within(panel).getByText('Connection timed out after 10000 ms')).toBeInTheDocument()
    } finally { restore() }
  })

  it('geniş kapta (900 px) HTTP sütunu tablo olarak kalır (masaüstü düzeni değişmez)', async () => {
    const restore = stubWidth(900)
    try {
      render(<UptimePage systemRole="ADMIN" />)
      const { http } = await openDetail('www.example.com')
      await within(http).findByText('131ms')
      expect(within(http).getByRole('table')).toBeInTheDocument()
      expect(within(http).getAllByRole('columnheader').map((h) => h.textContent)).toEqual([expect.any(String), expect.any(String), 'ms', ''])
    } finally { restore() }
  })
})
