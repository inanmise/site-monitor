import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import CertCheckHistory from '../components/certmodal/CertCheckHistory.jsx'

/**
 * Sertifika Kontrol Geçmişi (CertificateModal "Kontrol Geçmişi" sekmesi) — 2026-09-28 shadcn yeniden tasarımı.
 *
 * Tel biçimi GERÇEK: `/ssl-history` zarfının `items`'ı sunucunun `CertificateCheck` varlıklarıdır (snake_case, UTC `Z`siz);
 * `/ssl/response-series` zarfı `{ series: [{ ts, count, down, days, … }], bucket, from, to, down_total }`.
 * Zaman damgaları `Date.now()`'dan TÜRER (CheckHistoryTab'ın saklama/kırpma uyarısı "şimdi - aralık" ile karşılaştırır;
 * son hata kutucuğu göreli zaman yazar) — sabit tarih kayan pencereye karşı zaman bombası olurdu.
 * Sorgular rol / erişilebilir ad / `data-slot` ile. Diller: test sağlayıcısı İngilizce.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))
vi.mock('../api/client', async (importOriginal) => {
  const real = await importOriginal()
  return {
    ...real,
    api: withApiFallback({
      monitoring: {
        getCheckHistory: vi.fn(),
        getCheckHistoryCsvUrl: vi.fn(() => '/api/monitoring/uptime/www.example.com/ssl-history?format=csv'),
        getSslResponseSeries: vi.fn(),
      },
    }),
  }
})
import { api, formatDate, formatDateSec } from '../api/client'

const H = 3_600_000
const D = 24 * H
const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const inDays = (d) => new Date(Date.now() + d * D).toISOString().slice(0, 19)
const FP_A = 'AA'.repeat(32)
const FP_B = 'BB'.repeat(32)
const FP_C = 'CC'.repeat(32)
const PKIX = 'PKIX path building failed: unable to find valid certification path to requested target (www.example.com:443)'

const check = (id, hoursAgo, over = {}) => ({
  id, domain: 'www.example.com', checked_at: iso(hoursAgo * H), created_at: iso(hoursAgo * H), run_id: `run-${id}`,
  status: 'valid', warning: false, days_remaining: 30, not_before: iso(335 * D), not_after: inDays(30),
  subject: 'www.example.com', issuer: 'Example Trust Ltd', issuer_cn: 'Example TLS RSA CA 2026',
  serial_number: '0A1B2C', fingerprint: FP_A, chain_status: 'VALID', revocation_status: 'VALID', trust_status: 'TRUSTED',
  deployment_status: 'UNKNOWN', tls_version: 'TLSv1.3', cipher_suite: 'TLS_AES_256_GCM_SHA384', response_ms: 412,
  intermediate_days_remaining: 1600, error: null, error_class: null, maintenance: false, ...over,
})

// Sayfa (yeniden eskiye): 1 sa önce YENİLENDİ (30 → 395, yeni parmak izi) · 2 sa önce 30 gün (bakımda; bir önceki BAŞARILI
// kontrole göre 1 gün az) · 3 sa önce BAŞARISIZ · 26 sa önce 31 gün (27 sa öncekine göre FARKLI sertifika, aynı gün) · 27 sa önce 31 gün.
const C_RENEWED = check(5, 1, { days_remaining: 395, not_after: inDays(395), fingerprint: FP_B, serial_number: '7F2C99' })
const C_BEFORE = check(4, 2, { maintenance: true })
const C_FAILED = check(3, 3, {
  status: 'error', days_remaining: null, not_before: null, not_after: null, issuer: null, issuer_cn: null, subject: null,
  serial_number: null, fingerprint: null, tls_version: null, cipher_suite: null, chain_status: 'UNKNOWN',
  revocation_status: 'UNKNOWN', trust_status: null, error: PKIX, error_class: 'CERT', response_ms: 10012,
})
const C_SWAPPED = check(2, 26, { days_remaining: 31 })
const C_OLDEST = check(1, 27, { days_remaining: 31, fingerprint: FP_C, serial_number: '55EE01' })
const ITEMS = [C_RENEWED, C_BEFORE, C_FAILED, C_SWAPPED, C_OLDEST]

const envelope = (over = {}) => ({ success: true, data: {
  items: ITEMS, page: 0, size: 50, total: ITEMS.length, counts: { total: 120, fail: 3 },
  range: { from: iso(7 * D), to: iso(0) }, retention_days: 180, oldest_at: iso(170 * D), newest_at: iso(H),
  buckets: [], alerts: [], ...over,
} })

const seriesEnv = (over = {}) => ({ success: true, data: {
  bucket: 'hour', unit: 'ms', from: iso(7 * D), to: iso(0), total: 6, down_total: 3, capped: false,
  series: [
    { ts: iso(27 * H), count: 1, down: 0, avg: 400, min: 400, max: 400, p95: 400, days: 31 },
    { ts: iso(26 * H), count: 1, down: 0, avg: 410, min: 410, max: 410, p95: 410, days: 31 },
    { ts: iso(3 * H), count: 3, down: 3, avg: null, min: null, max: null, p95: null, days: null },
    { ts: iso(2 * H), count: 1, down: 0, avg: 420, min: 420, max: 420, p95: 420, days: 30 },
    // Son kova ORTALAMASI 394 (kovadaki iki ölçüm 395/394); satırdaki en yeni kesin ölçüm 395 — kutucuk satırı kullanmalı.
    { ts: iso(1 * H), count: 2, down: 0, avg: 390, min: 380, max: 400, p95: 400, days: 394 },
  ],
  ...over,
} })

/** Varsayılan uç davranışı: son-hata sorgusu (status=fail, size=1) ayrı yanıt alır. */
function wire({ main = envelope(), failPage = envelope({ items: [C_FAILED], total: 3 }), series = seriesEnv() } = {}) {
  api.monitoring.getCheckHistory.mockImplementation((kind, id, p = {}) => {
    if (p.status === 'fail' && p.size === 1) return Promise.resolve({ success: true, data: { ...envelope().data, items: [C_FAILED], size: 1, total: 3 } })
    if (p.status === 'fail') return Promise.resolve(typeof failPage === 'function' ? failPage(p) : failPage)
    return typeof main === 'function' ? main(p) : Promise.resolve(main)
  })
  api.monitoring.getSslResponseSeries.mockImplementation(() => (typeof series === 'function' ? series() : Promise.resolve(series)))
}

/** Asıl liste çağrıları (son-hata sorgusu hariç). */
const mainCalls = () => api.monitoring.getCheckHistory.mock.calls.filter(([, , p]) => !(p?.status === 'fail' && p?.size === 1))
const lastMain = () => mainCalls().at(-1)?.[2]
const tiles = () => [...document.querySelectorAll('[data-slot="hist-tile"]')]
const renderIt = (props = {}) => render(<CertCheckHistory domain="www.example.com" urlSync={false} live={false} {...props} />)

beforeEach(() => {
  vi.clearAllMocks()
  mobile.on = false
  wire()
})

describe('CertCheckHistory — özet kutucukları', () => {
  it('kontrol / başarısız (süzgeç) · başarı oranı · kalan gün (en yeni KESİN ölçüm) · yenileme (seriden) · son hata (kesin zaman)', async () => {
    renderIt()
    await screen.findByText(PKIX)
    const t = tiles()
    expect(t).toHaveLength(6)
    expect(t[0].tagName).toBe('BUTTON')
    expect(t[0]).toHaveAttribute('aria-pressed', 'true')
    expect(t[0].textContent).toContain('120')
    expect(t[1]).toHaveAttribute('aria-pressed', 'false')
    expect(t[1]).toHaveAttribute('data-tone', 'danger')
    expect(t[1].textContent).toContain('3')
    expect(t[2].textContent).toContain('97.50%')                    // (120 − 3) / 120
    expect(within(t[3]).getByText('395')).toBeInTheDocument()       // 1. satırın KESİN ölçümü — son kova ortalaması (394) değil
    expect(t[3]).toHaveAttribute('data-tone', 'success')
    await waitFor(() => expect(within(tiles()[4]).getByText('1')).toBeInTheDocument())   // bütün aralıkta 1 yenileme
    expect(tiles()[4]).toHaveAttribute('data-tone', 'success')
    // Son hata: ayrı, dar sorgu (status=fail, size=1) — AYNI aralıkla; kutucuk kesin zamanı alt satırda yazar.
    await waitFor(() => expect(within(tiles()[5]).getByText(formatDate(C_FAILED.checked_at))).toBeInTheDocument())
    expect(tiles()[5]).toHaveAttribute('data-tone', 'danger')
    expect(api.monitoring.getCheckHistory).toHaveBeenCalledWith('uptime-ssl', 'www.example.com',
      expect.objectContaining({ status: 'fail', page: 0, size: 1, days: 7 }))
    expect(api.monitoring.getSslResponseSeries).toHaveBeenCalledWith('www.example.com', { days: 7 })
  })

  it('aralıkta hata yoksa son-hata sorgusu HİÇ atılmaz; kutucuk "None in this range"', async () => {
    wire({ main: envelope({ items: [C_RENEWED, C_BEFORE], counts: { total: 40, fail: 0 }, total: 2 }) })
    renderIt()
    await waitFor(() => expect(tiles()).toHaveLength(6))
    await screen.findByText('None in this range')
    expect(api.monitoring.getCheckHistory.mock.calls.some(([, , p]) => p?.size === 1)).toBe(false)
    expect(tiles()[2].textContent).toContain('100%')
  })

  it('Başarısız kutucuğu süzgeçtir: status=fail ile yeniden ister ve aria-pressed geçer', async () => {
    renderIt()
    await screen.findByText(PKIX)
    fireEvent.click(tiles()[1])
    await waitFor(() => expect(lastMain()).toMatchObject({ status: 'fail', page: 0 }))
    await waitFor(() => expect(tiles()[1]).toHaveAttribute('aria-pressed', 'true'))
    expect(tiles()[0]).toHaveAttribute('aria-pressed', 'false')
  })
})

describe('CertCheckHistory — satırlar (masaüstü tablo)', () => {
  it('sütunlar + durum rozeti (ikon + metin) + kalan gün ve önceki kontrole göre değişim işaretleri', async () => {
    renderIt()
    await screen.findByText(PKIX)
    expect(screen.getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Time', 'Status', 'Days left', 'Certificate', 'Details'])
    const st = [...document.querySelectorAll('[data-slot="cert-hist-status"]')]
    expect(st.map((b) => b.getAttribute('data-status'))).toEqual(['ok', 'ok', 'fail', 'ok', 'ok'])
    expect(st[2].textContent).toBe('Failed')
    expect(st[2].querySelector('svg')).not.toBeNull()               // renk tek başına değil: ikon + metin
    // Yenileme: 30 → 395 (+365) — başarısız kontrol ARADA olsa da karşılaştırma bir önceki BAŞARILI kontrolle
    const renewed = document.querySelectorAll('[data-slot="cert-hist-renewed"]')
    expect(renewed).toHaveLength(1)
    expect(renewed[0].textContent).toBe('Renewed +365')
    // 30 gün (2 sa önce) — bir önceki başarılı 31 gün (26 sa önce; arada başarısız var): azalış, sessiz işaret + ekran okuyucu metni
    const down = document.querySelectorAll('[data-slot="cert-hist-delta"]')
    expect(down).toHaveLength(1)
    expect(down[0].textContent).toContain('down 1 since the previous check')
    // Aynı gün, farklı parmak izi → "Yeni sertifika"
    expect(document.querySelectorAll('[data-slot="cert-hist-newcert"]')).toHaveLength(1)
    // Bakım penceresindeki kontrol rozetle
    expect(document.querySelectorAll('[data-slot="cert-hist-maintenance"]')).toHaveLength(1)
    const days = [...document.querySelectorAll('[data-slot="cert-hist-days"]')]
    expect(days[0].textContent).toBe('395 days')
    expect(days[0]).toHaveAttribute('data-tone', 'ok')
    // Gün ayırıcıları paylaşılan kabuktan (iki farklı gün)
    expect(document.querySelectorAll('[data-slot="hist-day-sep"]').length).toBeGreaterThanOrEqual(2)
    // Eski App.css hücre sınıfları sertifika yüzeyinde YOK
    expect(document.querySelector('[class*="upt-rt-"]')).toBeNull()
    expect(document.querySelector('[data-grid]')).toBeNull()
  })

  it('kritik ve süresi dolmuş kalan gün danger tonunda; süresi dolmuşsa "N gün önce doldu"', async () => {
    wire({ main: envelope({ items: [check(9, 1, { days_remaining: -6, status: 'warning', warning: true }), check(8, 2, { days_remaining: 5 })], total: 2 }) })
    renderIt()
    await screen.findByText('Expired 6 days ago')
    const days = [...document.querySelectorAll('[data-slot="cert-hist-days"]')]
    expect(days.map((d) => d.getAttribute('data-tone'))).toEqual(['danger', 'danger'])
  })

  it('hata metni: hücrede kırpılmış (+ title); "Details" satırın altında tam metni ve kopyalamayı açar (aria-expanded/controls)', async () => {
    renderIt()
    await screen.findByText(PKIX)
    const cellErr = document.querySelector('[data-slot="cert-hist-error"]')
    expect(cellErr).toHaveAttribute('title', PKIX)
    expect(cellErr.className).toContain('truncate')

    const name = `${formatDateSec(C_FAILED.checked_at)} — Details`
    const toggle = screen.getByRole('button', { name })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(toggle).not.toHaveAttribute('aria-controls')
    fireEvent.click(toggle)

    const region = await screen.findByRole('region', { name: `Details of the check at ${formatDateSec(C_FAILED.checked_at)}` })
    const hide = screen.getByRole('button', { name: `${formatDateSec(C_FAILED.checked_at)} — Hide` })
    expect(hide).toHaveAttribute('aria-expanded', 'true')
    expect(hide).toHaveAttribute('aria-controls', region.id)
    expect(within(region).getByText(PKIX)).toBeInTheDocument()
    expect(within(region).getByRole('button', { name: `${formatDateSec(C_FAILED.checked_at)} — Copy error message` })).toBeInTheDocument()
    expect(within(region).getByText('CERT')).toBeInTheDocument()   // hata türü
    // Satır ek satırda (tam genişlik) açılır — tablo sütunlarının dışında
    expect(region.closest('[data-slot="hist-row-extra"]')).not.toBeNull()

    fireEvent.click(hide)
    await waitFor(() => expect(screen.queryByRole('region', { name: /Details of the check at/ })).toBeNull())
  })

  it('yenilenen satırın ayrıntısı değişimi ve önceki seri numarasını söyler', async () => {
    renderIt()
    await screen.findByText(PKIX)
    fireEvent.click(screen.getByRole('button', { name: `${formatDateSec(C_RENEWED.checked_at)} — Details` }))
    const region = await screen.findByRole('region', { name: /Details of the check at/ })
    expect(region.textContent).toContain('Days remaining rose by 365 since the previous check')
    expect(region.textContent).toContain('(previous serial 0A1B2C)')
    expect(within(region).getByText(FP_B)).toBeInTheDocument()
  })
})

describe('CertCheckHistory — boş / hata durumları', () => {
  it('boş aralık: açıklama + "Show the last 90 days" aralığı genişletir', async () => {
    // Aralıkta kontrol yoksa seri de boştur (aynı tablo) — gerçekçi fikstür
    wire({ main: envelope({ items: [], counts: { total: 0, fail: 0 }, total: 0 }), series: seriesEnv({ series: [], total: 0, down_total: 0 }) })
    renderIt()
    await screen.findByText('No certificate checks in this range')
    await waitFor(() => expect(api.monitoring.getSslResponseSeries).toHaveBeenCalled())
    await waitFor(() => expect(document.querySelector('[data-slot="cert-days-trend"][data-state="loading"]')).toBeNull())
    // Hiç kontrol yokken eğilimin "ölçüm yok" satırı İKİNCİ bir boş durum olarak çizilmez; son hata kutucuğu nötr
    expect(document.querySelector('[data-slot="cert-days-trend"][data-state="empty"]')).toBeNull()
    expect(tiles()[5]).toHaveAttribute('data-tone', 'total')
    fireEvent.click(screen.getByRole('button', { name: 'Show the last 90 days' }))
    await waitFor(() => expect(lastMain()).toMatchObject({ days: 90 }))
  })

  it('süzgeçte sonuç yok: "No failed checks in this range" + "Show all checks" süzgeci kaldırır', async () => {
    wire({
      main: envelope({ items: [C_RENEWED], counts: { total: 40, fail: 0 }, total: 1 }),
      failPage: envelope({ items: [], counts: { total: 40, fail: 0 }, total: 0 }),
    })
    renderIt()
    await screen.findAllByText(/Renewed|395 days/)
    fireEvent.click(tiles()[1])
    await screen.findByText('No failed checks in this range')
    fireEvent.click(screen.getByRole('button', { name: 'Show all checks' }))
    await waitFor(() => expect(lastMain()?.status).toBeUndefined())
  })

  it('liste yüklenemezse paylaşılan hata bandı + Retry yeniden ister', async () => {
    wire({ main: () => Promise.resolve({ success: false, error: 'Sunucu hatası' }) })
    renderIt()
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('Could not load check history')
    // "Bilinmiyor" ≠ "sorun yok": sayaçlar "—", son hata kutucuğu "aralıkta hata yok" DEMEZ ve yeşil değildir
    expect(within(tiles()[0]).getByText('—')).toBeInTheDocument()
    expect(within(tiles()[1]).getByText('—')).toBeInTheDocument()
    expect(screen.queryByText('None in this range')).toBeNull()
    expect(tiles()[5]).toHaveAttribute('data-tone', 'total')
    const n = mainCalls().length
    fireEvent.click(within(alert).getByRole('button', { name: 'Retry' }))
    await waitFor(() => expect(mainCalls().length).toBe(n + 1))
  })

  it('eğilim yüklenemezse kendi kartında hata + Retry seriyi yeniden ister (liste etkilenmez)', async () => {
    wire({ series: () => Promise.resolve({ success: false, error: 'boom' }) })
    renderIt()
    await screen.findByText(PKIX)
    const card = await waitFor(() => {
      const el = document.querySelector('[data-slot="cert-days-trend"][data-state="error"]')
      expect(el).not.toBeNull()
      return el
    })
    const n = api.monitoring.getSslResponseSeries.mock.calls.length
    // Ayırt edici ad (Ek 3/7) — görünür metin "Retry" adın başında (label-in-name)
    const retry = within(card).getByRole('button', { name: 'Retry loading days remaining over time' })
    expect(retry).toHaveTextContent('Retry')
    fireEvent.click(retry)
    await waitFor(() => expect(api.monitoring.getSslResponseSeries.mock.calls.length).toBe(n + 1))
  })

  /* Ek 3/7 (2026-09-28): liste de seri de düşerse İKİ "Yeniden dene" var — adları ayrışmalı (hangisi neyi yeniler). */
  it('liste + eğilim birlikte düşerse iki yeniden dene düğmesinin adı ayrışır; her biri kendi isteğini yeniler', async () => {
    wire({ main: () => Promise.resolve({ success: false, error: 'Sunucu hatası' }), series: () => Promise.resolve({ success: false, error: 'boom' }) })
    renderIt()
    const alert = await screen.findByRole('alert')
    await waitFor(() => expect(document.querySelector('[data-slot="cert-days-trend"][data-state="error"]')).not.toBeNull())
    expect(screen.getAllByRole('button', { name: 'Retry' })).toHaveLength(1)                 // yalnız listenin bandı
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    const trendRetry = screen.getByRole('button', { name: 'Retry loading days remaining over time' })
    const series = api.monitoring.getSslResponseSeries.mock.calls.length
    const list = mainCalls().length
    fireEvent.click(trendRetry)
    await waitFor(() => expect(api.monitoring.getSslResponseSeries.mock.calls.length).toBe(series + 1))
    expect(mainCalls().length).toBe(list)
  })

  /*
   * Ek 3/6 (2026-09-28): "Özel" seçilip uçlar UYGULANMADAN seri isteği atılmaz (anahtar null). Eski çizim "bayat" sayılıp
   * eğilim kartında sonsuza dek dönen "Güncelleniyor" göstergesi + %60 soluk çizim kalıyordu.
   */
  it('özel aralık seçilip uygulanmadan: eğilimde dönen gösterge / aria-busy YOK, yeni seri isteği de yok', async () => {
    renderIt()
    await screen.findByText(PKIX)
    const card = await waitFor(() => {
      const el = document.querySelector('[data-slot="cert-days-trend"][data-state="ready"]')
      expect(el).not.toBeNull()
      return el
    }, { timeout: 8000 })   // grafik tembel yüklenir (recharts)
    await waitFor(() => expect(card).not.toHaveAttribute('aria-busy'))
    const n = api.monitoring.getSslResponseSeries.mock.calls.length
    fireEvent.click(screen.getByRole('button', { name: 'Custom Range' }))
    await new Promise((r) => setTimeout(r, 50))
    const now = document.querySelector('[data-slot="cert-days-trend"][data-state="ready"]')
    expect(now).not.toBeNull()
    expect(now).not.toHaveAttribute('aria-busy')
    expect(now.querySelector('[data-slot="spinner"]')).toBeNull()          // dönen gösterge yok
    expect(now.textContent).not.toContain('Updating')
    expect(api.monitoring.getSslResponseSeries.mock.calls.length).toBe(n)
  })

  it('seride kalan gün ölçümü yoksa eğilim bilgi satırına döner', async () => {
    wire({ series: seriesEnv({ series: [{ ts: iso(3 * H), count: 3, down: 3, avg: null, min: null, max: null, p95: null, days: null }] }) })
    renderIt()
    await screen.findByText('No days-remaining readings in this range')
  })
})

describe('CertCheckHistory — eğilim ve yenileme anına inme', () => {
  it('ekran okuyucu özeti + yenileme düğmesi geçmişi o ana (özel aralık) daraltır', async () => {
    renderIt()
    await screen.findByText(PKIX)
    const summary = await waitFor(() => {
      const el = document.querySelector('[data-slot="cert-trend-summary"]')
      expect(el).not.toBeNull()
      return el
    }, { timeout: 8000 })   // grafik tembel yüklenir (recharts)
    expect(summary.textContent).toBe('Days remaining: 31 days at the start of the range and 394 days at the end. Renewals: 1. Failed checks: 3.')
    fireEvent.click(screen.getByRole('button', { name: /30 → 394 days/ }))
    await waitFor(() => {
      const p = lastMain()
      expect(p.days).toBeUndefined()
      expect(typeof p.from).toBe('string')
      expect(typeof p.to).toBe('string')
    })
    // Yenileme kovası (1 sa önce) pencerenin içinde
    const p = lastMain()
    const at = Date.parse(seriesEnv().data.series[4].ts + 'Z')
    expect(Date.parse(p.from + 'Z')).toBeLessThan(at)
    expect(Date.parse(p.to + 'Z')).toBeGreaterThan(at)
  })
})

describe('CertCheckHistory — reloadSignal (Çalıştır / Yenile) remount etmez', () => {
  it('seçili aralık KORUNUR: liste + seri aynı 30 günle yeniden istenir, ön ayar seçili kalır', async () => {
    const { rerender } = renderIt({ reloadSignal: 0 })
    await screen.findByText(PKIX)
    fireEvent.click(screen.getByRole('button', { name: 'Last 30 days' }))
    await waitFor(() => expect(lastMain()).toMatchObject({ days: 30 }))
    await waitFor(() => expect(api.monitoring.getSslResponseSeries).toHaveBeenLastCalledWith('www.example.com', { days: 30 }))
    const before = { list: mainCalls().length, series: api.monitoring.getSslResponseSeries.mock.calls.length }

    rerender(<CertCheckHistory domain="www.example.com" urlSync={false} live={false} reloadSignal={1} />)
    await waitFor(() => expect(mainCalls().length).toBe(before.list + 1))
    await waitFor(() => expect(api.monitoring.getSslResponseSeries.mock.calls.length).toBe(before.series + 1))
    expect(lastMain()).toMatchObject({ days: 30, page: 0 })
    expect(api.monitoring.getSslResponseSeries).toHaveBeenLastCalledWith('www.example.com', { days: 30 })
    expect(screen.getByRole('button', { name: 'Last 30 days' })).toHaveAttribute('aria-pressed', 'true')
  })
})

describe('CertCheckHistory — telefon kartı', () => {
  it('kart düzeni: saat + durum, kalan gün, tam genişlik Ayrıntı; panel kartın İÇİNDE açılır', async () => {
    mobile.on = true
    renderIt()
    await screen.findByText(PKIX)
    expect(document.querySelector('[data-view="cards"]')).not.toBeNull()
    expect(screen.queryByRole('table')).toBeNull()
    const cards = [...document.querySelectorAll('[data-slot="cert-hist-card"]')]
    expect(cards.map((c) => c.getAttribute('data-status'))).toEqual(['ok', 'ok', 'fail', 'ok', 'ok'])
    const toggle = within(cards[2]).getByRole('button', { name: `${formatDateSec(C_FAILED.checked_at)} — Details` })
    expect(toggle.className).toContain('w-full')
    expect(toggle.className).toContain('h-10')                     // 40 px dokunma hedefi
    fireEvent.click(toggle)
    const region = await screen.findByRole('region', { name: /Details of the check at/ })
    expect(cards[2].contains(region)).toBe(true)
    expect(within(cards[0]).getByText('Renewed +365')).toBeInTheDocument()
  })
})

/**
 * 2026-09-28 — aynı görünüm Envanter çekmecesinde ve Uptime detayında: kontrollü aralık (Uptime'ın tek seçicisi iki geçmişi
 * sürer), dar kapta kart listesi, "başlıktaki Çalıştır" demeyen boş durum.
 */
describe('CertCheckHistory — kontrollü aralık (Uptime) ve dar kap (çekmece / yan yana sütun)', () => {
  const isoOf = (d) => d.toISOString().slice(0, 19)

  it('kontrollü aralık: ön ayar çubuğu ve canlı rozet yok; liste + seri + son hata AYNI from/to ile', async () => {
    const from = new Date(Date.now() - 6 * H), to = new Date()
    renderIt({ range: { from, to }, onRangeChange: vi.fn(), live: true })
    await screen.findByText(PKIX)
    expect(screen.queryByRole('group', { name: 'Time range' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Last 30 days' })).toBeNull()
    expect(document.querySelector('[data-slot="hist-live"]')).toBeNull()
    expect(lastMain()).toMatchObject({ from: isoOf(from), to: isoOf(to) })
    expect(lastMain().days).toBeUndefined()
    await waitFor(() => expect(api.monitoring.getSslResponseSeries).toHaveBeenLastCalledWith('www.example.com', { from: isoOf(from), to: isoOf(to) }))
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalledWith('uptime-ssl', 'www.example.com',
      expect.objectContaining({ status: 'fail', size: 1, from: isoOf(from), to: isoOf(to) })))
    expect(api.monitoring.getSslResponseSeries.mock.calls.some(([, p]) => p?.days != null)).toBe(false)
  })

  it('kontrollü aralıkta boş: açıklama Çalıştır\'ı anmaz (runInHeader=false); "Show the last 90 days" ÜSTTEKİ seçiciyi sürer', async () => {
    wire({ main: envelope({ items: [], counts: { total: 0, fail: 0 }, total: 0 }), series: seriesEnv({ series: [], total: 0, down_total: 0 }) })
    const onRangeChange = vi.fn()
    renderIt({ range: { from: new Date(Date.now() - 6 * H), to: new Date() }, onRangeChange, runInHeader: false })
    await screen.findByText('No certificate checks in this range')
    expect(screen.getByText('This certificate wasn’t checked during the selected range. Try a wider range.')).toBeInTheDocument()
    expect(screen.queryByText(/use Run at the top/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Show the last 90 days' }))
    expect(onRangeChange).toHaveBeenCalledTimes(1)
    const [a, b] = onRangeChange.mock.calls[0]
    expect(b.getTime() - a.getTime()).toBe(90 * D)
  })

  it('kontrollü aralık zaten ≥ 90 günse genişletme önerilmez', async () => {
    wire({ main: envelope({ items: [], counts: { total: 0, fail: 0 }, total: 0 }), series: seriesEnv({ series: [], total: 0, down_total: 0 }) })
    renderIt({ range: { from: new Date(Date.now() - 90 * D), to: new Date() }, onRangeChange: vi.fn(), runInHeader: false })
    await screen.findByText('No certificate checks in this range')
    expect(screen.queryByRole('button', { name: 'Show the last 90 days' })).toBeNull()
  })

  it('kontrollü aralıkta yenileme anına inme de ÜSTTEKİ seçiciyi sürer (iç ön ayar değil)', async () => {
    const onRangeChange = vi.fn()
    renderIt({ range: { from: new Date(Date.now() - 7 * D), to: new Date() }, onRangeChange })
    await screen.findByText(PKIX)
    const jump = await screen.findByRole('button', { name: /30 → 394 days/ }, { timeout: 8000 })   // grafik tembel yüklenir
    const before = mainCalls().length
    fireEvent.click(jump)
    expect(onRangeChange).toHaveBeenCalledTimes(1)
    const [a, b] = onRangeChange.mock.calls[0]
    const at = Date.parse(seriesEnv().data.series[4].ts + 'Z')
    expect(a.getTime()).toBeLessThan(at)
    expect(b.getTime()).toBeGreaterThan(at)
    // Üst bileşen aralığı değiştirmedikçe istek atılmaz (kontrollü)
    expect(mainCalls().length).toBe(before)
  })

  it('varsayılan kap eşiği: dar kapta (430 px) masaüstünde de kart listesi; geniş kapta (720 px) tablo', async () => {
    const orig = HTMLElement.prototype.getBoundingClientRect
    const stub = (width) => {
      HTMLElement.prototype.getBoundingClientRect = function () {
        if (this.getAttribute?.('data-slot') === 'check-history') return { width, height: 900, top: 0, left: 0, right: width, bottom: 900, x: 0, y: 0 }
        return orig.call(this)
      }
    }
    try {
      stub(430)
      const { unmount } = renderIt()
      await screen.findByText(PKIX)
      expect(document.querySelector('[data-view="cards"]')).not.toBeNull()
      expect(document.querySelectorAll('[data-slot="cert-hist-card"]')).toHaveLength(5)
      expect(screen.queryByRole('table')).toBeNull()
      unmount()
      stub(720)
      renderIt()
      await screen.findByText(PKIX)
      expect(screen.getByRole('table')).toBeInTheDocument()
    } finally {
      HTMLElement.prototype.getBoundingClientRect = orig
    }
  })
})
