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
      // Bakım rozeti modül önbellekli tek istek atar — dosya boyunca aynı bakım hedefi (sayfa türünde hedef = URL).
      maintenance: { active: vi.fn(() => Promise.resolve({ success: true, data: { all: false, targets: ['https://docs.example.org/guide'] } })) },
    },
  }),
}))

import PageMonitorCard from '../components/page/PageMonitorCard.jsx'
import MonitorCardActions from '../components/MonitorCardActions.jsx'
import { CARD_CHECK, MonitorStatusBadge } from '../components/monitoring/MonitorCard.jsx'
import { exclusionCount, httpTone, humanizeMs, integrityResult, pageFailure, urlParts } from '../components/page/pageCardModel.js'

const stamp = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)

const base = {
  id: 4, name: null, url: 'https://www.example.com/', mode: 'SINGLE_PAGE', crawl_depth: 2, crawl_max_pages: 50, exclude_patterns: '',
  alert_third_party: false, alert_mixed_content: true, alert_timeout: true, interval_seconds: 300, timeout_ms: 4000, active: true,
  status: 'OK', http_status: 200, response_ms: 386, total_resources: 84, broken_resources: 0, timeout_count: 0, mixed_content_count: 0,
  pages_crawled: 1, error: null, team_id: 1, team_name: 'Takım A', group_name: 'Kurumsal Web', tags: 'prod', checked_at: stamp(5 * 60_000),
}

// Sayfanın durum sözlüğü (PageMonitorPage.statusKey) — kart bunu yuva olarak alır.
const statusOf = (s) => (s === 'OK' ? 'up' : s === 'DOWN' ? 'down' : s === 'DEGRADED' ? 'warn' : 'unknown')

const cardOf = (container) => container.querySelector('[data-slot="card"]')
const panel = (container) => container.querySelector('[data-slot="page-integrity"]')
const tile = (container, metric) => container.querySelector(`[data-slot="page-metric"][data-metric="${metric}"]`)

function renderCard(monitor = {}, props = {}) {
  const m = { ...base, ...monitor }
  const status = statusOf(m.status)
  return render(<PageMonitorCard monitor={m} status={status} badge={<MonitorStatusBadge status={status}>{m.status}</MonitorStatusBadge>}
    onOpen={props.onOpen || (() => {})} {...props} />)
}

describe('pageCardModel — saf yardımcılar', () => {
  it('URL parçaları: https şeması gizli, http görünür, kök "/" yazılmaz, yol + sorgu korunur; ayrıştırılamayan metin host’a düşer', () => {
    expect(urlParts('https://www.example.com/')).toEqual({ scheme: '', host: 'www.example.com', path: '' })
    expect(urlParts('http://legacy.example.com/index.php?lang=tr')).toEqual({ scheme: 'http://', host: 'legacy.example.com', path: '/index.php?lang=tr' })
    expect(urlParts('intranet')).toEqual({ scheme: '', host: 'intranet', path: '' })
  })

  it('bütünlük sonucu: sorunlu = kırık + zaman aşımı (mixed AYRI); ton kırık→crit, yalnız timeout/mixed→warn, temiz→ok, kaynaksız→none; hiç kontrol yok→null', () => {
    expect(integrityResult({ total_resources: 132, broken_resources: 7, timeout_count: 2, mixed_content_count: 1 }))
      .toEqual({ total: 132, broken: 7, timeouts: 2, mixed: 1, problems: 9, healthy: 123, pages: null, tone: 'crit' })
    expect(integrityResult({ total_resources: 45, broken_resources: 0, timeout_count: 0, mixed_content_count: 6 }).tone).toBe('warn')
    expect(integrityResult({ total_resources: 45, broken_resources: 0, timeout_count: 2, mixed_content_count: 0 }))
      .toMatchObject({ healthy: 43, tone: 'warn' })
    expect(integrityResult({ total_resources: 84, broken_resources: 0 }).tone).toBe('ok')
    expect(integrityResult({ total_resources: 0 }).tone).toBe('none')
    expect(integrityResult({ total_resources: null })).toBeNull()
    expect(integrityResult({ mode: 'SITE_CRAWL', total_resources: 10, pages_crawled: 37 }).pages).toBe(37)
  })

  it('HTTP tonu, sayfa-düzeyi neden (DOWN / CONFIG_ERROR), hariç tutma sayısı, süre biçimi', () => {
    expect([200, 301, 404, 503, null].map(httpTone)).toEqual(['ok', 'neutral', 'bad', 'bad', 'neutral'])
    expect(pageFailure({ status: 'DEGRADED', error: 'x' })).toBeNull()
    expect(pageFailure({ status: 'OK' })).toBeNull()
    // Sunucunun sabit Türkçe metinleri arayüz diline çevrilir; ağ katmanının ham metni olduğu gibi kalır
    expect(pageFailure({ status: 'DOWN', error: 'ana sayfa HTTP 503\nikinci' })).toEqual({ kind: 'down', detail: 'HTTP 503' })
    expect(pageFailure({ status: 'DOWN', http_status: 502, error: 'ana sayfa alınamadı' })).toEqual({ kind: 'down', detail: 'HTTP 502' })
    expect(pageFailure({ status: 'DOWN' })).toEqual({ kind: 'down', detail: null })
    expect(pageFailure({ status: 'DOWN', error: 'Connection refused' })).toEqual({ kind: 'down', detail: 'Connection refused' })
    expect(pageFailure({ status: 'CONFIG_ERROR', error: "yapılandırma hatası: URL'de geçerli bir host yok (şema eksik veya bozuk)" }))
      .toEqual({ kind: 'config', detail: null, detailKey: 'page.card.configNoHost' })
    expect(pageFailure({ status: 'CONFIG_ERROR', error: 'başka' })).toEqual({ kind: 'config', detail: 'başka' })
    expect([exclusionCount('/a/\n\n  /b/ \n'), exclusionCount(''), exclusionCount(null)]).toEqual([2, 0, 0])
    expect([humanizeMs(386), humanizeMs(1720), humanizeMs(null)]).toEqual([{ num: '386', unit: 'ms' }, { num: '1.7', unit: 's' }, null])
  })
})

describe('PageMonitorCard', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('temiz tarama: "84 / 84 resources OK" + yeşil oran çubuğu (ui/Progress) + "No broken or insecure resources"; HTTP 200 yeşil', () => {
    const { container } = renderCard()
    const p = panel(container)
    expect(p).toHaveAttribute('data-tone', 'ok')
    expect(p.querySelector('[data-slot="page-counts"]').textContent).toBe('84 / 84resources OK84 of 84 resources loaded without problems')
    expect(p.querySelector('[data-slot="progress-indicator"]').className).toContain('bg-success')
    expect(p.querySelector('[data-slot="page-no-issues"]').textContent).toBe('No broken or insecure resources')
    expect(p.querySelector('[data-slot="page-issue"]')).toBeNull()
    expect(tile(container, 'http')).toHaveAttribute('data-tone', 'ok')
    expect(tile(container, 'http').textContent).toBe('HTTP status200page loaded')
    expect(tile(container, 'scan').querySelector('[data-slot="page-metric-value"]').textContent).toBe('386ms')
  })

  it('kırık kaynak: sorunsuz oran kırmızı ("123 / 132"), kırık/zaman aşımı/mixed ÇİPLERİ sayılarıyla; panel crit; sol şerit YOK', () => {
    const { container } = renderCard({ status: 'DEGRADED', total_resources: 132, broken_resources: 7, timeout_count: 2, mixed_content_count: 1 })
    const p = panel(container)
    expect(p).toHaveAttribute('data-tone', 'crit')
    expect(p.querySelector('[data-slot="page-counts"]').textContent).toMatch(/^123 \/ 132resources OK/)
    expect(p.querySelector('[data-slot="progress-indicator"]').className).toContain('bg-destructive')
    expect([...p.querySelectorAll('[data-slot="page-issue"]')].map((c) => [c.dataset.kind, c.textContent]))
      .toEqual([['broken', '7 broken'], ['timeout', '2 timed out'], ['mixed', '1 mixed content']])
    expect(p.querySelector('[data-slot="page-no-issues"]')).toBeNull()
    const card = cardOf(container)
    expect(card).toHaveAttribute('data-status', 'warn')
    expect(card.className).not.toMatch(/border-l-|before:|border-destructive\/45/)
  })

  it('yalnız mixed content: panel amber (warn), sorunsuz oran tam; site taraması: gezilen sayfa çipi + kapsam çipleri', () => {
    const mixed = renderCard({ status: 'DEGRADED', total_resources: 45, mixed_content_count: 6 })
    expect(panel(mixed.container)).toHaveAttribute('data-tone', 'warn')
    expect(panel(mixed.container).querySelector('[data-slot="page-counts"]').textContent).toMatch(/^45 \/ 45/)
    mixed.unmount()
    const crawl = renderCard({ mode: 'SITE_CRAWL', crawl_depth: 3, crawl_max_pages: 120, pages_crawled: 37, total_resources: 1480,
      exclude_patterns: '/archive/\n/print/\ncdn.example.net/legacy', alert_third_party: true })
    expect(panel(crawl.container).querySelector('[data-slot="page-pages"]').textContent).toBe('37 pages')
    // Yalnız zaman aşımı (kırık yok): oran AMBER (kırmızı kırık içindir), çubuk da amber
    const timeouts = renderCard({ total_resources: 40, timeout_count: 3 })
    const counts = panel(timeouts.container).querySelector('[data-slot="page-counts"]')
    expect(counts.textContent).toMatch(/^37 \/ 40/)
    expect(counts.firstElementChild.className).toContain('text-amber-700')
    expect(panel(timeouts.container).querySelector('[data-slot="progress-indicator"]').className).toContain('bg-warning')
    timeouts.unmount()
    const chips = [...crawl.container.querySelectorAll('[data-slot="page-scope"] [data-chip]')].map((c) => [c.dataset.chip, c.textContent])
    expect(chips).toEqual([
      ['mode', 'Site Crawl · depth 3 · up to 120 pages'], ['interval', 'every 5 min'],
      ['third-party', 'third-party included'], ['exclusions', '3 exclusion rules'],
    ])
  })

  it('sayfa yüklenemedi (DOWN): kırmızı neden paneli + sunucu metni, HTTP 503 kırmızı "page error"; TÜM kenar kırmızı tonlu', () => {
    const { container } = renderCard({ status: 'DOWN', http_status: 503, response_ms: 98, total_resources: 0, error: 'ana sayfa HTTP 503' })
    const p = panel(container)
    expect(p).toHaveAttribute('data-tone', 'crit')
    const reason = p.querySelector('[data-slot="page-reason"]')
    expect(reason).toHaveAttribute('data-reason', 'down')
    expect(reason.textContent).toBe('The page didn’t loadHTTP 503')   // sunucunun Türkçe "ana sayfa HTTP 503"ü çevrildi
    expect(p.querySelector('[data-slot="page-counts"]')).toBeNull()      // 0 / 0 uydurulmaz
    expect(tile(container, 'http')).toHaveAttribute('data-tone', 'bad')
    expect(tile(container, 'http').textContent).toMatch(/503page error$/)
    expect(cardOf(container).className).toMatch(/(^|\s)border-destructive\/45(\s|$)/)
  })

  it('yapılandırma hatası: mor panel (kesinti değil); hiç kontrol edilmemiş: nötr "ilk kontrolü bekleniyor", kutu yok, "Not checked yet"', () => {
    const cfg = renderCard({ url: 'intranet', status: 'CONFIG_ERROR', http_status: null, total_resources: null, error: "yapılandırma hatası: URL'de geçerli bir host yok (şema eksik veya bozuk)" })
    expect(panel(cfg.container)).toHaveAttribute('data-tone', 'config')
    expect(panel(cfg.container).textContent).toBe('The URL can’t be checkedIt has no valid host — the scheme (https://) is missing or the address is malformed')
    expect(cfg.container.querySelector('[data-slot="page-metric"]')).toBeNull()   // istek hiç atılmadı → boş kutu yok
    expect(cardOf(cfg.container).className).not.toMatch(/border-destructive\/45/)
    cfg.unmount()
    const never = renderCard({ status: 'unknown', checked_at: null, http_status: null, response_ms: null, total_resources: null })
    expect(panel(never.container)).toHaveAttribute('data-tone', 'none')
    expect(panel(never.container).textContent).toBe('Waiting for its first check')
    expect(never.container.querySelector('[data-slot="page-metric"]')).toBeNull()
    expect(never.container.querySelector('[data-slot="page-checked-at"]')).toHaveAttribute('data-never', 'true')
    expect(never.container.querySelector('[data-slot="card-footer"]').textContent).toMatch(/Not checked yet/)
  })

  it('URL başlığı: host vurgulu, yol soluk, https şeması gizli / http şeması amber; tam URL title’da; ad URL’den farklıysa ikinci satır', () => {
    const https = renderCard({ url: 'https://shop.example.com/checkout?step=2', name: 'Checkout' })
    const title = screen.getByRole('button', { name: 'https://shop.example.com/checkout?step=2 — open details' })
    expect(title.querySelector('[data-slot="page-url-host"]').textContent).toBe('shop.example.com')
    expect(title.querySelector('[data-slot="page-url-path"]').textContent).toBe('/checkout?step=2')
    expect(title.querySelector('[data-slot="page-url-scheme"]')).toBeNull()
    expect(title.querySelector('[title]')).toHaveAttribute('title', 'https://shop.example.com/checkout?step=2')
    expect(https.container.querySelector('[data-slot="page-name"]').textContent).toBe('Checkout')
    https.unmount()
    const http = renderCard({ url: 'http://legacy.example.com/' })
    expect(screen.getByRole('button', { name: /^http:\/\/legacy\.example\.com\/ — open details$/ }).querySelector('[data-slot="page-url-scheme"]').textContent).toBe('http://')
    expect(http.container.querySelector('[data-slot="page-name"]')).toBeNull()
  })

  it('başlık GERÇEK düğme ve detayı açar; URL kopyala / seçim kutusu / eylemler detayı AÇMAZ; adlar satırı (URL) taşır', async () => {
    const onOpen = vi.fn()
    const onSel = vi.fn()
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: vi.fn(() => Promise.resolve()) } })
    renderCard({}, {
      onOpen,
      select: <Checkbox className={CARD_CHECK} checked={false} onCheckedChange={onSel} aria-label="Select https://www.example.com/ for bulk action" />,
      actions: <MonitorCardActions rowLabel="https://www.example.com/" onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} onDelete={() => {}} checkTitle="Check" editTitle="Edit" deleteTitle="Delete" />,
    })
    const title = screen.getByRole('button', { name: 'https://www.example.com/ — open details' })
    expect(title).toHaveAttribute('data-monitor-open', 'true')
    fireEvent.click(title)
    expect(onOpen).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: 'https://www.example.com/ — Copy URL' }))
    await waitFor(() => expect(navigator.clipboard.writeText).toHaveBeenCalledWith('https://www.example.com/'))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select https://www.example.com/ for bulk action' }))
    expect(onSel).toHaveBeenCalled()
    for (const name of ['https://www.example.com/ — Check', 'https://www.example.com/ — Edit', /^https:\/\/www\.example\.com\/ — (Duplicate|Kopyala)$/, 'https://www.example.com/ — Delete']) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument()
    }
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('duraklatılmış: kesik/soluk kart + "Paused" + Sürdür; alarm: seviye rozeti + tüm kart dış çizgisi; bakım rozeti (hedef = URL)', async () => {
    const onResume = vi.fn()
    const paused = renderCard({ active: false }, {
      actions: <MonitorCardActions rowLabel="https://www.example.com/" onResume={onResume} onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} checkTitle="Check" editTitle="Edit" />,
    })
    expect(cardOf(paused.container)).toHaveAttribute('data-inactive', 'true')
    expect(paused.container.querySelector('[data-slot="card-footer"] [data-slot="monitor-paused"]')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'https://www.example.com/ — Resume' }))
    expect(onResume).toHaveBeenCalledTimes(1)
    paused.unmount()

    const alarm = renderCard({ status: 'DOWN', total_resources: 0, error: 'ana sayfa alınamadı', active_alarm: true, alarm_level: 'HIGH', alarm_acknowledged: false })
    const card = cardOf(alarm.container)
    expect(card).toHaveAttribute('data-alarm', 'true')
    expect(card.querySelector('[data-slot="monitor-alarm"]')).toHaveAttribute('data-level', 'HIGH')
    expect(card.className).not.toMatch(/border-destructive\/45/)
    alarm.unmount()

    const maint = renderCard({ url: 'https://docs.example.org/guide' })
    await waitFor(() => expect(maint.container.querySelector('[data-slot="maintenance-badge"]')).not.toBeNull())
  })

  it('meta + son kontrol: takım/grup/vekil + ilk üç etiket "+N"; göreli zaman; telefon: iki sütun ölçü, 40 px kopyala', () => {
    const { container } = renderCard({ tags: 'prod,web,edge,dr', proxy_effective: 'proxy' })
    expect(container.querySelector('[data-slot="meta-proxy"]')).not.toBeNull()
    const tags = container.querySelector('[data-slot="page-tags"]')
    expect([...tags.querySelectorAll('[data-slot="badge"]')].map((b) => b.textContent)).toEqual(['prod', 'web', 'edge', '+1dr'])
    const at = container.querySelector('[data-slot="page-checked-at"]')
    expect(at.textContent).toMatch(/^5 min ago \(exact:/)
    expect(container.querySelector('[data-slot="monitor-metrics"]').className).toMatch(/(^|\s)grid-cols-2(\s|$)/)
    expect(screen.getByRole('button', { name: 'https://www.example.com/ — Copy URL' }).className).toContain('pointer-coarse:size-10')
  })
})

/**
 * KART YOĞUNLUĞU (2026-09-27): Kompakt = durum satırı + URL (+ad) + bütünlük HÜKMÜ tek satırda (sorunsuz oran + en ağır
 * sorun çipi) + yüklenemediyse tek satır neden + YALNIZ takım + alt çubuk. Kapsam çipleri, sonuç paneli (çubuk + tüm
 * çipler), HTTP/süre kutuları, trend, grup/vekil/etiketler yalnız Zengin'de.
 */
describe('PageMonitorCard — Kompakt / Zengin yoğunluk', () => {
  const q = (root, s) => root.querySelector(`[data-slot="${s}"]`)
  const richOnly = ['monitor-card-rich', 'page-scope', 'page-integrity', 'progress-indicator', 'page-metric', 'monitor-spark', 'page-tags', 'meta-group', 'meta-proxy']
  const spark = { n: 12, fail: 0, up_pct: 100, last: [], buckets: [{ t: '2026-09-27T10', n: 12, fail: 0, ms: 400 }] }

  it('Kompakt: Zengin parçalar DOM\'da YOK; URL + ad, sorunsuz oran + en ağır sorun çipi, takım, zaman ve eylemler VAR', () => {
    const onOpen = vi.fn()
    const { container } = renderCard({ status: 'DEGRADED', total_resources: 132, broken_resources: 7, timeout_count: 2, mixed_content_count: 1,
      name: 'Kurumsal site', proxy_effective: 'proxy', mode: 'SITE_CRAWL', pages_crawled: 12 }, {
      density: 'compact', onOpen, spark,
      actions: <MonitorCardActions rowLabel="https://www.example.com/" onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} onDelete={() => {}} checkTitle="Check" editTitle="Edit" deleteTitle="Delete" />,
    })
    const card = cardOf(container)
    expect(card).toHaveAttribute('data-density', 'compact')
    for (const s of richOnly) expect(q(card, s), s).toBeNull()
    expect(q(card, 'page-name').textContent).toBe('Kurumsal site')
    const sum = q(card, 'page-compact')
    expect(sum).toHaveAttribute('data-tone', 'crit')
    expect(q(sum, 'page-compact-counts').textContent).toMatch(/^123 \/ 132resources OK123 of 132 resources loaded without problems$/)
    const chips = [...sum.querySelectorAll('[data-slot="page-issue"]')]
    expect(chips.map((c) => [c.dataset.kind, c.textContent])).toEqual([['broken', '7 broken']])   // yalnız EN AĞIR sorun
    expect(q(card, 'page-compact-reason')).toBeNull()
    expect(q(card, 'meta-team').textContent).toMatch(/Takım A/)
    expect(q(card, 'page-checked-at').textContent).toMatch(/^5 min ago/)
    for (const name of ['https://www.example.com/ — Check', 'https://www.example.com/ — Edit', 'https://www.example.com/ — Delete']) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument()
    }
    fireEvent.click(screen.getByRole('button', { name: 'https://www.example.com/ — open details' }))
    expect(onOpen).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'https://www.example.com/ — Copy URL' })).toBeInTheDocument()
  })

  it('Zengin (varsayılan): kapsam çipleri, sonuç paneli, kutular ve trend MonitorCardRich içinde; etiket/vekil meta satırında; Kompakt özeti yok', () => {
    const { container } = renderCard({ proxy_effective: 'proxy' }, { spark })
    const card = cardOf(container)
    expect(card).toHaveAttribute('data-density', 'rich')
    const riches = [...card.querySelectorAll('[data-slot="monitor-card-rich"]')]
    expect(riches.some((r) => q(r, 'page-scope'))).toBe(true)
    const content = riches.find((r) => q(r, 'page-integrity'))
    for (const s of ['page-integrity', 'page-metric', 'monitor-spark']) expect(q(content, s), s).not.toBeNull()
    expect(q(card, 'page-tags')).not.toBeNull()
    expect(q(card, 'meta-proxy')).not.toBeNull()
    expect(q(card, 'page-compact')).toBeNull()
  })

  it('Kompakt sorun: sayfa yüklenemedi → kırmızı hüküm + TEK satır neden (kırpılır), tamamı dokun-gör; yapılandırma hatası mor', async () => {
    const down = renderCard({ status: 'DOWN', http_status: 503, total_resources: 0, error: 'ana sayfa HTTP 503' }, { density: 'compact' })
    const sum = q(down.container, 'page-compact')
    expect(sum).toHaveAttribute('data-tone', 'crit')
    expect(q(sum, 'page-compact-verdict').textContent).toBe('The page didn’t load')
    const reason = q(sum, 'page-compact-reason')
    expect(reason.textContent).toBe('HTTP 503')
    expect(reason.className).toMatch(/(^|\s)truncate(\s|$)/)
    expect(reason.closest('button').className).toContain('pointer-coarse:min-h-10')
    fireEvent.click(reason.closest('button'))
    expect((await screen.findByRole('tooltip')).textContent).toBe('HTTP 503')
    expect(cardOf(down.container).className).toMatch(/(^|\s)border-destructive\/45(\s|$)/)   // tüm kenar kırmızı — iki görünümde de
    down.unmount()

    const cfg = renderCard({ url: 'intranet', status: 'CONFIG_ERROR', http_status: null, total_resources: null,
      error: "yapılandırma hatası: URL'de geçerli bir host yok (şema eksik veya bozuk)" }, { density: 'compact' })
    expect(q(cfg.container, 'page-compact')).toHaveAttribute('data-tone', 'config')
    expect(q(cfg.container, 'page-compact-verdict').textContent).toBe('The URL can’t be checked')
    expect(q(cfg.container, 'page-compact-reason').textContent).toMatch(/no valid host/)
  })

  it('Kompakt: temiz tarama yeşil onay; yalnız mixed → amber çip; hiç kontrol yok → "ilk kontrol bekleniyor"; duraklatılmışta Sürdür', () => {
    const clean = renderCard({}, { density: 'compact' })
    expect(q(clean.container, 'page-compact')).toHaveAttribute('data-tone', 'ok')
    expect(q(clean.container, 'page-compact-ok')).not.toBeNull()
    expect(q(clean.container, 'page-issue')).toBeNull()
    clean.unmount()
    const mixed = renderCard({ status: 'DEGRADED', total_resources: 45, mixed_content_count: 6 }, { density: 'compact' })
    expect(q(mixed.container, 'page-compact')).toHaveAttribute('data-tone', 'warn')
    expect(q(mixed.container, 'page-issue').textContent).toBe('6 mixed content')
    mixed.unmount()
    const never = renderCard({ status: 'unknown', checked_at: null, http_status: null, response_ms: null, total_resources: null }, { density: 'compact' })
    expect(q(never.container, 'page-compact')).toHaveAttribute('data-tone', 'none')
    expect(q(never.container, 'page-compact').textContent).toBe('Waiting for its first check')
    never.unmount()
    const onResume = vi.fn()
    renderCard({ active: false }, {
      density: 'compact',
      actions: <MonitorCardActions rowLabel="https://www.example.com/" onResume={onResume} onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} checkTitle="Check" editTitle="Edit" />,
    })
    fireEvent.click(screen.getByRole('button', { name: 'https://www.example.com/ — Resume' }))
    expect(onResume).toHaveBeenCalledTimes(1)
  })
})
