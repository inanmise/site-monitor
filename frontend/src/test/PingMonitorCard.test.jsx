import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within, waitFor } from './test-utils.jsx'
import { Checkbox } from '@/components/shadcn/checkbox'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => (s ? `exact:${s}` : ''),
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      // Bakım rozeti modül önbellekli tek istek atar — dosya boyunca aynı bakım hedefi.
      maintenance: { active: vi.fn(() => Promise.resolve({ success: true, data: { all: false, targets: ['db.example.com'] } })) },
    },
  }),
}))

import PingMonitorCard from '../components/ping/PingMonitorCard.jsx'
import MonitorCardActions from '../components/MonitorCardActions.jsx'
import { CARD_CHECK } from '../components/monitoring/MonitorCard.jsx'
import {
  failureReason, intervalText, latencyBaseline, lossTone, pingStatusKey, rttAssessment, signedPercent,
} from '../components/ping/pingCardModel.js'

const stamp = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)

const base = {
  id: 7, name: 'gw.example.com', host: 'gw.example.com', status: 'up', active: true, ip_version: 'auto', packet_count: 4,
  interval_seconds: 60, rtt_ms: 12, packet_loss: 0, error: null, team_id: 1, team_name: 'Takım A', group_name: 'Core Network',
  tags: 'prod', slow_response_enabled: false, slow_threshold_percent: 20, checked_at: stamp(5 * 60_000),
}

/** 24 saatlik saatlik kova dizisi — ms değerleri verilir, ölçümsüz saat null. */
const sparkOf = (msList) => ({
  n: msList.length * 60, fail: 0, up_pct: 100, last: [],
  buckets: msList.map((ms, h) => ({ t: `2026-09-27T${String(h).padStart(2, '0')}`, n: 60, fail: ms == null ? 60 : 0, ms })),
})

const cardOf = (container) => container.querySelector('[data-slot="card"]')
const tile = (container, metric) => container.querySelector(`[data-slot="ping-metric"][data-metric="${metric}"]`)

function renderCard(monitor = {}, props = {}) {
  return render(<PingMonitorCard monitor={{ ...base, ...monitor }} onOpen={props.onOpen || (() => {})} {...props} />)
}

describe('pingCardModel — saf yardımcılar', () => {
  it('24 sa tabanı: saatlik ortalamalardan (ölçümsüz saat hariç), en az 3 ölçüm; aralık saatlik ortalamaların aralığı', () => {
    expect(latencyBaseline(sparkOf([10, 20, 30, null]))).toEqual({ avg: 20, min: 10, max: 30, hours: 3 })
    expect(latencyBaseline(sparkOf([10, null, 30]))).toBeNull()
    expect(latencyBaseline(undefined)).toBeNull()
  })

  it('RTT değerlendirmesi: eşik YALNIZ yavaşlık alarmı açıkken; yoksa nötr + sapma; yanıt yoksa bad', () => {
    const baseline = { avg: 40 }
    expect(rttAssessment({ rtt_ms: 96, slow_response_enabled: true, slow_threshold_percent: 30 }, baseline)).toEqual({ tone: 'warn', delta: 140 })
    expect(rttAssessment({ rtt_ms: 44, slow_response_enabled: true, slow_threshold_percent: 30 }, baseline)).toEqual({ tone: 'ok', delta: 10 })
    expect(rttAssessment({ rtt_ms: 96, slow_response_enabled: false }, baseline)).toEqual({ tone: 'neutral', delta: 140 })
    expect(rttAssessment({ rtt_ms: 96, slow_response_enabled: true }, null)).toEqual({ tone: 'neutral', delta: null })
    expect(rttAssessment({ rtt_ms: null, status: 'down' }, baseline).tone).toBe('bad')
    expect(rttAssessment({ rtt_ms: null, status: 'unknown' }, baseline).tone).toBe('neutral')
  })

  it('kayıp tonu: %0 ok · kısmi warn · %100 bad · ölçüm yok neutral', () => {
    expect([lossTone(0), lossTone(25), lossTone(100), lossTone(null)]).toEqual(['ok', 'warn', 'bad', 'neutral'])
  })

  it('kapalı nedeni: DNS / ulaşılamıyor / zaman aşımı / N/A / ham hata; çalışanda yok', () => {
    expect(failureReason({ status: 'up' })).toBeNull()
    expect(failureReason({ status: 'down', error: 'ping: x.example.com: Name or service not known' }).kind).toBe('dns')
    expect(failureReason({ status: 'down', error: 'ping: bad address \'x\'' }).kind).toBe('dns')
    expect(failureReason({ status: 'down', error: 'From 203.0.113.1 icmp_seq=1 Destination Host Unreachable' }).kind).toBe('unreachable')
    expect(failureReason({ status: 'down', packet_loss: 100, error: 'Yanıt yok (%100 paket kaybı)' }).kind).toBe('timeout')
    expect(failureReason({ status: 'na', error: 'ICMP bu ortamda kullanılamıyor' }).kind).toBe('na')
    expect(failureReason({ status: 'down', error: 'garip çıktı\nikinci satır' })).toEqual({ kind: 'error', detail: 'garip çıktı' })
    expect(failureReason({ status: 'down' }).kind).toBe('down')
  })

  it('durum anahtarı, sıklık metni, işaretli yüzde', () => {
    expect(['up', 'down', 'na', 'unknown', undefined].map((s) => pingStatusKey({ status: s }))).toEqual(['up', 'down', 'unknown', 'unknown', 'unknown'])
    const t = (k, ...a) => `${k}(${a.join(',')})`
    expect([30, 60, 300, 3600, 43200, null].map((s) => intervalText(s, t)))
      .toEqual(['ping.card.everySec(30)', 'ping.card.everyMin1()', 'ping.card.everyMin(5)', 'ping.card.everyHour1()', 'ping.card.everyHour(12)', null])
    const pct = (v) => `${v}%`
    expect([140, -12, 0, null].map((d) => signedPercent(d, pct))).toEqual(['+140%', '−12%', '±0%', null])
  })
})

describe('PingMonitorCard', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('anahtar ölçüler: RTT (ms) + paket kaybı tonlu; yavaşlık eşiği aşılınca görünür "Slow" rozeti ve 24 sa sapması', () => {
    const { container } = renderCard({ rtt_ms: 96, packet_loss: 0, slow_response_enabled: true, slow_threshold_percent: 30 },
      { spark: sparkOf([30, 40, 50]) })
    const rtt = tile(container, 'rtt')
    expect(rtt).toHaveAttribute('data-tone', 'warn')
    expect(rtt.querySelector('[data-slot="ping-metric-value"]').textContent).toBe('96ms')
    const verdict = within(rtt).getByText(/^(Slow|Yavaş)$/)
    expect(verdict).toHaveAttribute('data-slot', 'ping-metric-verdict')
    expect(verdict).toHaveAttribute('data-variant', 'outline')   // shadcn Badge
    expect(rtt.querySelector('[data-slot="ping-metric-sub"]').textContent).toMatch(/\+140% vs 24 h avg|\+%140 \(24 sa ort\.\)/)
    const loss = tile(container, 'loss')
    expect(loss).toHaveAttribute('data-tone', 'ok')
    expect(loss.textContent).toMatch(/0%.*No loss|%0.*Kayıp yok/)
    // 24 sa özeti: ortalama + saatlik aralık (paket düzeyi değil)
    expect(container.querySelector('[data-slot="ping-range"]').textContent).toMatch(/24 h avg 40 ms · hourly 30–50 ms/)
  })

  it('olağan RTT (eşik içinde) yeşil ve rozetsiz; hüküm ekran okuyucuya söylenir. Yavaşlık alarmı kapalıysa ton NÖTR', () => {
    const { container, unmount } = renderCard({ rtt_ms: 42, slow_response_enabled: true, slow_threshold_percent: 30 }, { spark: sparkOf([30, 40, 50]) })
    expect(tile(container, 'rtt')).toHaveAttribute('data-tone', 'ok')
    expect(tile(container, 'rtt').querySelector('[data-slot="ping-metric-verdict"]')).toBeNull()
    expect(tile(container, 'rtt').querySelector('[data-slot="ping-metric-sub"]').textContent).toMatch(/^Normal · \+5% vs 24 h avg$|^Normal · \+%5 \(24 sa ort\.\)$/)
    unmount()
    const neutral = renderCard({ rtt_ms: 96, slow_response_enabled: false }, { spark: sparkOf([30, 40, 50]) })
    expect(tile(neutral.container, 'rtt')).toHaveAttribute('data-tone', 'neutral')
    expect(tile(neutral.container, 'rtt').querySelector('[data-slot="ping-metric-verdict"]')).toBeNull()
  })

  it('paket kaybı: kısmi → warn "Partial loss"; %100 → bad "All packets lost" + RTT "No reply"', () => {
    const partial = renderCard({ rtt_ms: 38, packet_loss: 25 })
    expect(tile(partial.container, 'loss')).toHaveAttribute('data-tone', 'warn')
    expect(tile(partial.container, 'loss').textContent).toMatch(/Partial loss|Kısmi kayıp/)
    partial.unmount()
    const all = renderCard({ status: 'down', rtt_ms: null, packet_loss: 100, error: 'Yanıt yok (%100 paket kaybı)' })
    expect(tile(all.container, 'loss')).toHaveAttribute('data-tone', 'bad')
    expect(tile(all.container, 'loss').textContent).toMatch(/All packets lost|Tüm paketler kayıp/)
    expect(tile(all.container, 'rtt')).toHaveAttribute('data-tone', 'bad')
    expect(tile(all.container, 'rtt').textContent).toMatch(/—.*(No reply|Yanıt yok)/)
  })

  it('kapalı kart NEDENİ tek satırda (zaman aşımı / DNS / ham hata); TÜM kenar kırmızı tonlu — sol şerit YOK', () => {
    const timeout = renderCard({ status: 'down', rtt_ms: null, packet_loss: 100, error: 'Yanıt yok (%100 paket kaybı)' })
    const reason = timeout.container.querySelector('[data-slot="ping-reason"]')
    expect(reason).toHaveAttribute('data-reason', 'timeout')
    expect(reason.textContent).toMatch(/every packet timed out|zaman aşımına uğradı/)
    const card = cardOf(timeout.container)
    expect(card).toHaveAttribute('data-status', 'down')
    expect(card.className).toMatch(/(^|\s)border-destructive\/45(\s|$)/)
    expect(card.className).not.toMatch(/border-l-|before:/)
    timeout.unmount()
    const dns = renderCard({ status: 'down', rtt_ms: null, packet_loss: null, error: 'ping: gw.example.com: Name or service not known' })
    expect(dns.container.querySelector('[data-slot="ping-reason"]')).toHaveAttribute('data-reason', 'dns')
    expect(dns.container.querySelector('[data-slot="ping-reason"]').textContent).toMatch(/couldn’t be resolved \(DNS\)|çözümlenemedi \(DNS\)/)
    dns.unmount()
    const raw = renderCard({ status: 'down', rtt_ms: null, packet_loss: null, error: 'sendmsg: Operation not supported' })
    expect(raw.container.querySelector('[data-slot="ping-reason"]').textContent).toMatch(/(Error|Hata): sendmsg: Operation not supported/)
    raw.unmount()
    const up = renderCard()
    expect(up.container.querySelector('[data-slot="ping-reason"]')).toBeNull()
    expect(cardOf(up.container).className).not.toMatch(/border-destructive/)
  })

  it('N/A (ICMP yok): ölçü kutuları çizilmez, amber neden satırı; hiç kontrol edilmemiş: kutu yok, alt çubukta "Not checked yet"', () => {
    const na = renderCard({ status: 'na', rtt_ms: null, packet_loss: null, error: 'ICMP bu ortamda kullanılamıyor (yetki/binary)' })
    expect(tile(na.container, 'rtt')).toBeNull()
    expect(na.container.querySelector('[data-slot="ping-reason"]')).toHaveAttribute('data-reason', 'na')
    expect(within(cardOf(na.container)).getByText(/^(N\/A)$/)).toBeInTheDocument()
    na.unmount()
    const pending = renderCard({ status: 'unknown', rtt_ms: null, packet_loss: null, checked_at: null })
    expect(tile(pending.container, 'rtt')).toBeNull()
    expect(pending.container.querySelector('[data-slot="ping-checked-at"]')).toHaveAttribute('data-never', 'true')
    expect(pending.container.querySelector('[data-slot="card-footer"]').textContent).toMatch(/Not checked yet|Henüz kontrol edilmedi/)
  })

  it('duraklatılmış: kesik/soluk kart + alt çubukta "Paused" + Sürdür; alarm: seviye rozeti + tüm kart dış çizgisi; bakım rozeti', async () => {
    const onResume = vi.fn()
    const paused = renderCard({ active: false }, {
      actions: <MonitorCardActions rowLabel="gw.example.com" onResume={onResume} onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} checkTitle="Check now" editTitle="Edit" />,
    })
    expect(cardOf(paused.container)).toHaveAttribute('data-inactive', 'true')
    expect(paused.container.querySelector('[data-slot="card-footer"] [data-slot="monitor-paused"]')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /^gw\.example\.com — (Resume|Sürdür)$/ }))
    expect(onResume).toHaveBeenCalledTimes(1)
    paused.unmount()

    const alarm = renderCard({ status: 'down', rtt_ms: null, packet_loss: 100, active_alarm: true, alarm_level: 'CRITICAL', alarm_acknowledged: false })
    const card = cardOf(alarm.container)
    expect(card).toHaveAttribute('data-alarm', 'true')
    expect(card.querySelector('[data-slot="monitor-alarm"]')).toHaveAttribute('data-level', 'CRITICAL')
    expect(card.className).not.toMatch(/border-destructive\/45/)   // alarm varken dış çizgi MonitorCard'ın (çift ton yok)
    alarm.unmount()

    const maint = renderCard({ host: 'db.example.com', name: 'db.example.com' })
    await waitFor(() => expect(maint.container.querySelector('[data-slot="maintenance-badge"]')).not.toBeNull())
  })

  it('başlık (host) GERÇEK düğme ve detayı açar; host kopyala / bağlantı kopyala / seçim kutusu detayı AÇMAZ; adlar satırı taşır', async () => {
    const onOpen = vi.fn()
    const onSel = vi.fn()
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: vi.fn(() => Promise.resolve()) } })
    renderCard({}, {
      onOpen,
      select: <Checkbox className={CARD_CHECK} checked={false} onCheckedChange={onSel} aria-label="Select gw.example.com for bulk action" />,
      actions: <MonitorCardActions rowLabel="gw.example.com" onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} onDelete={() => {}} checkTitle="Check now" editTitle="Edit" deleteTitle="Delete" />,
    })
    const title = screen.getByRole('button', { name: /^gw\.example\.com — (open details|detayları aç)$/ })
    expect(title).toHaveAttribute('data-monitor-open', 'true')
    fireEvent.click(title)
    expect(onOpen).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: /^gw\.example\.com — (Copy host|Host adresini kopyala)$/ }))
    await waitFor(() => expect(navigator.clipboard.writeText).toHaveBeenCalledWith('gw.example.com'))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select gw.example.com for bulk action' }))
    expect(onSel).toHaveBeenCalled()
    for (const name of [/— Check now$/, /— Edit$/, /— (Duplicate|Kopyala)$/, /— Delete$/]) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument()
    }
    expect(onOpen).toHaveBeenCalledTimes(1)   // hiçbiri detayı açmadı
  })

  it('çipler + meta: ICMP/IPv6/paket/sıklık; ad host’tan farklıysa ikinci satır; ilk üç etiket + "+N"; son kontrol göreli, tam zaman ekran okuyucuda', () => {
    const { container } = renderCard({ host: '2001:db8::10', name: 'Core router', ip_version: 'V6', packet_count: 5, interval_seconds: 300, tags: 'prod,network,edge,dr,critical' })
    const chips = [...container.querySelectorAll('[data-slot="ping-protocol"] [data-chip]')].map((c) => c.textContent)
    expect(chips).toEqual(['ICMPv6', 'IPv6', expect.stringMatching(/^5 (packets|paket)$/), expect.stringMatching(/every 5 min|her 5 dk/)])
    expect(container.querySelector('[data-slot="ping-name"]').textContent).toBe('Core router')
    const tags = container.querySelector('[data-slot="ping-tags"]')
    expect([...tags.querySelectorAll('[data-slot="badge"]')].map((b) => b.textContent)).toEqual(['prod', 'network', 'edge', '+2dr, critical'])
    expect(container.querySelector('[data-slot="meta-team"]')).not.toBeNull()
    const at = container.querySelector('[data-slot="ping-checked-at"]')
    expect(at.textContent).toMatch(/^(5 min ago|5 dk önce) \(exact:/)
    expect(at.getAttribute('datetime')).toMatch(/Z$/)
  })

  it('ad host ile aynıysa ikinci satır yok; telefon: ölçüler her genişlikte iki sütun, kopyala düğmeleri dokunmatikte 40 px', () => {
    const { container } = renderCard()
    expect(container.querySelector('[data-slot="ping-name"]')).toBeNull()
    expect(container.querySelector('[data-slot="monitor-metrics"]').className).toMatch(/(^|\s)grid-cols-2(\s|$)/)
    const copyHost = screen.getByRole('button', { name: /Copy host|Host adresini kopyala/ })
    expect(copyHost.className).toContain('pointer-coarse:size-10')
  })
})

/**
 * KART YOĞUNLUĞU (2026-09-27): Kompakt = durum satırı · host · TEK ikincil satır (ad + takım) · TEK ana ölçü (RTT) ·
 * düşükse TEK satır neden · alt çubuk. Zengin-özel bölümler (çipler, ölçü kutuları, 24 sa özeti, trend/SLA, grup/etiket)
 * Kompakt'ta DOM'a HİÇ girmez (gizlenmez).
 */
describe('PingMonitorCard — Kompakt / Zengin', () => {
  beforeEach(() => { vi.clearAllMocks() })
  const slot = (root, name) => root.querySelector(`[data-slot="${name}"]`)
  const RICH_ONLY = ['ping-protocol', 'ping-metric', 'ping-range', 'monitor-spark', 'monitor-card-meta', 'meta-group', 'ping-tags']
  const actions = <MonitorCardActions rowLabel="gw.example.com" onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} onDelete={() => {}} checkTitle="Check now" editTitle="Edit" deleteTitle="Delete" />

  it('Kompakt: Zengin-özel bölümler YOK; durum, host, ad + YALNIZ takım, RTT, zaman ve AYNI eylemler var', () => {
    const onSel = vi.fn()
    const { container } = renderCard({ name: 'Core router', tags: 'prod,edge' }, {
      density: 'compact', spark: sparkOf([10, 12, 14]), actions,
      select: <Checkbox className={CARD_CHECK} checked={false} onCheckedChange={onSel} aria-label="Select gw.example.com for bulk action" />,
    })
    const card = cardOf(container)
    expect(card).toHaveAttribute('data-density', 'compact')
    expect(slot(container, 'monitor-card-rich')).toBeNull()
    for (const s of RICH_ONLY) expect(slot(container, s), s).toBeNull()
    // temel bilgiler
    expect(within(card).getByText(/^(Reachable|Erişilebilir)$/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^gw\.example\.com — (open details|detayları aç)$/ })).toBeInTheDocument()
    const sub = slot(container, 'card-compact-sub')
    expect(slot(sub, 'ping-name').textContent).toBe('Core router')
    expect(slot(sub, 'meta-team').textContent).toMatch(/Takım A/)
    expect(sub.textContent).not.toMatch(/Core Network|prod/)          // grup/etiket yalnız Zengin'de
    // dokunmatikte satır 44 px (takım rozetinin 40 px'lik ::after dokunma alanı satırın içinde kalır), rozet sağa yaslı
    expect(sub.className).toContain('pointer-coarse:min-h-11')
    expect(sub.className).toContain('leading-5')
    expect(slot(sub, 'meta-team').className).toMatch(/(^|\s)ml-auto(\s|$)/)
    const rtt = container.querySelector('[data-slot="ping-compact"] [data-slot="compact-value"][data-metric="rtt"]')
    expect(rtt.textContent).toBe('12msRTT')
    expect(container.querySelector('[data-slot="compact-value"][data-metric="loss"]')).toBeNull()   // %0 kayıp yazılmaz
    expect(slot(container, 'ping-reason')).toBeNull()
    expect(slot(container, 'ping-checked-at').textContent).toMatch(/^(5 min ago|5 dk önce)/)
    for (const name of [/— Check now$/, /— Edit$/, /— (Duplicate|Kopyala)$/, /— Delete$/, /— (Copy host|Host adresini kopyala)$/]) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument()
    }
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select gw.example.com for bulk action' }))
    expect(onSel).toHaveBeenCalled()
  })

  it('Zengin (varsayılan): bugünkü tam kart — çipler, iki ölçü kutusu, 24 sa özeti, trend, meta + etiketler; kompakt parçalar YOK', () => {
    const { container } = renderCard({}, { spark: sparkOf([10, 12, 14]) })
    expect(cardOf(container)).toHaveAttribute('data-density', 'rich')
    expect(slot(container, 'monitor-card-rich')).not.toBeNull()
    for (const s of RICH_ONLY.filter((s) => s !== 'meta-group')) expect(slot(container, s), s).not.toBeNull()
    expect(container.querySelectorAll('[data-slot="ping-metric"]')).toHaveLength(2)
    expect(slot(container, 'meta-group').textContent).toMatch(/Core Network/)
    expect(slot(container, 'card-compact-sub')).toBeNull()
    expect(slot(container, 'ping-compact')).toBeNull()
  })

  it('Kompakt yavaş + kısmi kayıp: RTT amber + görünür "Slow" rozeti, aynı satırda amber "25% loss"', () => {
    const { container } = renderCard({ rtt_ms: 96, packet_loss: 25, slow_response_enabled: true, slow_threshold_percent: 30 },
      { density: 'compact', spark: sparkOf([30, 40, 50]) })
    const row = slot(container, 'ping-compact')
    expect(row.querySelector('[data-metric="rtt"]')).toHaveAttribute('data-tone', 'warn')
    expect(within(row).getByText(/^(Slow|Yavaş)$/)).toHaveAttribute('data-slot', 'compact-verdict')
    const loss = row.querySelector('[data-metric="loss"]')
    expect(loss).toHaveAttribute('data-tone', 'warn')
    expect(loss.textContent).toMatch(/^(25% loss|%25 kayıp)$/)
  })

  it('Kompakt düşük kart: TEK satır neden (kırpılır), tamamı dokun-gör balonunda; ad satırı taşır; tıklamak detayı AÇMAZ', async () => {
    const onOpen = vi.fn()
    const err = 'sendmsg: Operation not supported on this very long interface name that will not fit a compact card'
    const { container } = renderCard({ status: 'down', rtt_ms: null, packet_loss: null, error: err }, { density: 'compact', onOpen })
    const reason = slot(container, 'ping-reason')
    expect(reason).toHaveAttribute('data-compact', 'true')
    expect(reason).toHaveAttribute('data-reason', 'error')
    expect(reason.querySelector('.truncate')).not.toBeNull()
    expect(reason.querySelector('.line-clamp-2')).toBeNull()
    expect(slot(container, 'ping-compact')).toBeNull()   // RTT yok → ölçü satırı yok (neden satırı söylüyor)
    const trigger = screen.getByRole('button', { name: `gw.example.com — Error: ${err}` })
    expect(trigger.className).toContain('pointer-coarse:min-h-10')
    fireEvent.click(trigger)
    expect((await screen.findByRole('tooltip')).textContent).toBe(`Error: ${err}`)
    expect(onOpen).not.toHaveBeenCalled()
    // N/A: amber neden, ölçü satırı yok
    const na = renderCard({ id: 8, status: 'na', rtt_ms: null, packet_loss: null, error: 'ICMP bu ortamda kullanılamıyor' }, { density: 'compact' })
    const naReason = na.container.querySelector('[data-slot="ping-reason"]')
    expect(naReason).toHaveAttribute('data-reason', 'na')
    expect(naReason.querySelector('[data-slot="hint-trigger"]').className).toMatch(/text-amber-800/)
    expect(na.container.querySelector('[data-slot="ping-compact"]')).toBeNull()
  })

  it('Kompakt duraklatılmış / alarm / bakım: rozetler ve Sürdür aynı', async () => {
    const onResume = vi.fn()
    const paused = renderCard({ active: false }, {
      density: 'compact',
      actions: <MonitorCardActions rowLabel="gw.example.com" onResume={onResume} onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} checkTitle="Check now" editTitle="Edit" />,
    })
    expect(paused.container.querySelector('[data-slot="card-footer"] [data-slot="monitor-paused"]')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /^gw\.example\.com — (Resume|Sürdür)$/ }))
    expect(onResume).toHaveBeenCalledTimes(1)
    paused.unmount()
    const alarm = renderCard({ status: 'down', rtt_ms: null, packet_loss: 100, active_alarm: true, alarm_level: 'HIGH' }, { density: 'compact' })
    expect(cardOf(alarm.container)).toHaveAttribute('data-alarm', 'true')
    expect(alarm.container.querySelector('[data-slot="monitor-alarm"]')).toHaveAttribute('data-level', 'HIGH')
    alarm.unmount()
    const maint = renderCard({ host: 'db.example.com', name: 'db.example.com' }, { density: 'compact' })
    await waitFor(() => expect(maint.container.querySelector('[data-slot="maintenance-badge"]')).not.toBeNull())
    expect(maint.container.querySelector('[data-slot="ping-name"]')).toBeNull()   // ad host ile aynı → ikincil satırda yalnız takım
    expect(maint.container.querySelector('[data-slot="card-compact-sub"] [data-slot="meta-team"]')).not.toBeNull()
  })
})
