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
      maintenance: { active: vi.fn(() => Promise.resolve({ success: true, data: { all: false, targets: ['mail.example.com'] } })) },
    },
  }),
}))

import PortMonitorCard from '../components/port/PortMonitorCard.jsx'
import MonitorCardActions from '../components/MonitorCardActions.jsx'
import MonitorCardMeta from '../components/MonitorCardMeta.jsx'
import { CARD_CHECK, MonitorStatusBadge } from '../components/monitoring/MonitorCard.jsx'
import {
  connectAssessment, endpointText, portResult, protocolOf, serviceName, sourceOf,
} from '../components/port/portCardModel.js'

const stamp = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)

const base = {
  id: 9, name: 'Orders DB', host: 'db.example.com', port: 3306, protocol: 'TCP', status: 'open', active: true,
  response_ms: 14, error: null, timeout_ms: 5000, interval_seconds: 300, ip_version: 'auto', standalone: true,
  team_id: 1, team_name: 'Takım A', group_name: 'Core Network', tags: 'prod', slow_response_enabled: false,
  slow_threshold_ms: 3000, checked_at: stamp(3 * 60_000),
}

/** 24 saatlik saatlik kova dizisi — ms değerleri verilir, ölçümsüz saat null. */
const sparkOf = (msList) => ({
  n: msList.length * 60, fail: 0, up_pct: 100, last: [],
  buckets: msList.map((ms, h) => ({ t: `2026-09-27T${String(h).padStart(2, '0')}`, n: 60, fail: ms == null ? 60 : 0, ms })),
})

const cardOf = (container) => container.querySelector('[data-slot="card"]')
const panel = (container) => container.querySelector('[data-slot="port-result"]')
const tile = (container, metric) => container.querySelector(`[data-slot="port-metric"][data-metric="${metric}"]`)

/** Sayfanın durum sözlüğüyle (cardStatus / statusBadge) aynı eşleme — kart sunumu için yeterli. */
const statusOf = (m) => (m.active === false ? 'unknown' : m.status === 'open' ? 'up' : m.status === 'closed' ? 'down' : 'unknown')
function renderCard(monitor = {}, props = {}) {
  const m = { ...base, ...monitor }
  const key = m.status === 'open' ? 'up' : m.status === 'closed' ? 'down' : 'unknown'
  return render(<PortMonitorCard monitor={m} status={statusOf(m)} onOpen={props.onOpen || (() => {})}
    badge={<MonitorStatusBadge status={key}>{m.status === 'open' ? 'Open' : m.status === 'closed' ? 'Closed' : 'Unknown'}</MonitorStatusBadge>}
    alarmLabel={`Active alarm${m.alarm_level ? ' — ' + m.alarm_level : ''}`} {...props} />)
}

describe('portCardModel — saf yardımcılar', () => {
  it('sonuç sınıflaması: açık / reddedildi / filtreli / DNS / aile / ulaşılamıyor / engelli / vekil / TLS / HTTP / banner / ham hata', () => {
    const r = (status, error, extra = {}) => portResult({ status, error, ...extra })
    expect(r('open', null)).toMatchObject({ kind: 'open', tone: 'ok', proto: 'TCP' })
    expect(r('closed', 'Connection refused')).toMatchObject({ kind: 'refused', tone: 'bad', udp: false })
    expect(r('closed', 'Connection refused: connect')).toMatchObject({ kind: 'refused' })
    expect(r('closed', 'UDP port erisilemez (ICMP unreachable)', { protocol: 'UDP' })).toMatchObject({ kind: 'refused', udp: true })
    expect(r('closed', 'Connect timed out')).toMatchObject({ kind: 'filtered', udp: false })
    expect(r('closed', 'UDP yanit yok (timeout — acik/filtreli olabilir)', { protocol: 'udp' })).toMatchObject({ kind: 'filtered', udp: true, proto: 'UDP' })
    expect(r('closed', 'çözümlenemeyen host: old.example.com')).toMatchObject({ kind: 'dns', family: null })
    expect(r('closed', 'No IPv6 address')).toMatchObject({ kind: 'dns', family: 'v6' })
    expect(r('closed', 'No route to host (Host unreachable)')).toMatchObject({ kind: 'unreachable' })
    expect(r('closed', 'izin verilmeyen hedef x.example.com → 169.254.169.254 (cloud-metadata endpoint)')).toMatchObject({ kind: 'blocked' })
    expect(r('closed', 'vekil tüneli reddetti: 403 — vekil bu porta tünel açmıyor olabilir (izinli: 443, 8443)')).toMatchObject({ kind: 'proxy' })
    expect(r('closed', 'Remote host terminated the handshake\nat sun.security')).toEqual({ kind: 'tls', tone: 'bad', proto: 'TCP', detail: 'Remote host terminated the handshake' })
    expect(r('closed', 'HTTP 503 (beklenen: 2xx)', { protocol: 'HTTP' })).toMatchObject({ kind: 'http', code: 503, expected: '2xx' })
    expect(r('closed', 'HTTP 404', { protocol: 'HTTP', expect: '200' })).toMatchObject({ kind: 'http', code: 404, expected: '200' })
    expect(r('closed', "Beklenen yanit yok: '220' (gelen: 554 Service unavailable)", { protocol: 'BANNER' }))
      .toMatchObject({ kind: 'banner', expected: '220', got: '554 Service unavailable' })
    expect(r('closed', "Beklenen yanit yok: 'SSH-2.0' (gelen: bos)", { protocol: 'BANNER' })).toMatchObject({ kind: 'banner', expected: 'SSH-2.0', got: '' })
    expect(r('closed', 'Banner alinamadi', { protocol: 'BANNER' })).toMatchObject({ kind: 'banner', expected: null, got: null })
    expect(r('closed', 'garip çıktı\nikinci satır')).toMatchObject({ kind: 'error', detail: 'garip çıktı' })
    expect(r('closed', null)).toMatchObject({ kind: 'closed', tone: 'bad' })
    expect(r('unknown', null)).toMatchObject({ kind: 'pending', tone: 'neutral' })
    expect(r('unknown', null, { checked_at: '2026-09-27T10:00:00' })).toMatchObject({ kind: 'unknown', tone: 'neutral' })
  })

  it('hizmet adı yalnız bilinen portta (uydurulmaz); uç nokta metni IPv6’yı köşeli parantezle; kaynak; tür', () => {
    expect([22, 25, 53, 80, 443, 3306, 5432, 6379, 1433, 1521, 389, 636, '8443'].map(serviceName))
      .toEqual(['SSH', 'SMTP', 'DNS', 'HTTP', 'HTTPS', 'MySQL', 'PostgreSQL', 'Redis', 'SQL Server', 'Oracle DB', 'LDAP', 'LDAPS', 'HTTPS (alt)'])
    expect([7001, null, 'abc', 0].map(serviceName)).toEqual([null, null, null, null])
    expect(endpointText('2001:db8::10', 443)).toBe('[2001:db8::10]:443')
    expect(endpointText('db.example.com', 3306)).toBe('db.example.com:3306')
    expect(endpointText('[2001:db8::10]', 443)).toBe('[2001:db8::10]:443')
    expect([true, false, null, undefined].map((s) => sourceOf({ standalone: s }))).toEqual(['standalone', 'inventory', 'inventory', 'inventory'])
    expect([undefined, 'tls', ' http '].map((p) => protocolOf({ protocol: p }))).toEqual(['TCP', 'TLS', 'HTTP'])
  })

  it('bağlantı süresi: zaman aşımında bad, bağlantı yoksa bad, eşik YALNIZ yavaşlık alarmı açıkken; kapalıysa nötr + 24 sa sapması', () => {
    const open = portResult({ status: 'open' })
    const baseline = { avg: 40 }
    expect(connectAssessment({ response_ms: 3480, slow_response_enabled: true, slow_threshold_ms: 3000 }, open, baseline)).toMatchObject({ tone: 'warn', limit: 3000 })
    expect(connectAssessment({ response_ms: 120, slow_response_enabled: true, slow_threshold_ms: 3000 }, open, baseline)).toMatchObject({ tone: 'ok', limit: 3000 })
    expect(connectAssessment({ response_ms: 96, slow_response_enabled: false, slow_threshold_ms: 3000 }, open, baseline)).toMatchObject({ tone: 'neutral', limit: null, delta: 140 })
    expect(connectAssessment({ response_ms: null, status: 'closed', error: 'Connect timed out' }, portResult({ status: 'closed', error: 'Connect timed out' }), baseline))
      .toMatchObject({ tone: 'bad', timedOut: true })
    expect(connectAssessment({ response_ms: null }, portResult({ status: 'closed', error: 'Connection refused' }), null)).toMatchObject({ tone: 'bad', noConnection: true })
    expect(connectAssessment({ response_ms: null }, portResult({ status: 'unknown', checked_at: 'x' }), null)).toMatchObject({ tone: 'neutral', noConnection: false })
  })
})

describe('PortMonitorCard', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('UÇ NOKTA kimliği: host (eş aralıklı başlık) · :port · tür rozeti · HTTP yolu · "usually …" hizmeti · IP ailesi · sıklık', () => {
    const { container, unmount } = renderCard({ host: 'api.example.com', port: 8443, protocol: 'HTTP', send_data: '/health', ip_version: 'v6', interval_seconds: 60 })
    const title = container.querySelector('[data-monitor-open]')
    expect(title.textContent).toBe('api.example.com')
    expect(title.className).toMatch(/(^|\s)font-mono(\s|$)/)
    const ep = container.querySelector('[data-slot="port-endpoint"]')
    expect(ep.querySelector('[data-slot="port-number"]').textContent).toBe(':8443')
    expect(ep.querySelector('[data-slot="port-protocol"]')).toHaveAttribute('data-protocol', 'HTTP')
    expect(ep.querySelector('[data-slot="port-protocol"]')).toHaveAttribute('data-variant', 'outline')   // shadcn Badge
    expect(ep.querySelector('[data-slot="port-path"]').textContent).toBe('/health')
    expect(ep.querySelector('[data-slot="port-service"]').textContent).toMatch(/^(usually|genelde) HTTPS \(alt\)$/)
    expect([...ep.querySelectorAll('[data-chip]')].map((c) => c.textContent)).toEqual(['IPv6', expect.stringMatching(/every minute|her dakika/)])
    unmount()
    // Bilinmeyen port → hizmet uydurulmaz; yol yalnız HTTP türünde; otomatik IP ailesi yazılmaz
    const other = renderCard({ port: 7001, protocol: 'UDP', send_data: 'ping' })
    const ep2 = other.container.querySelector('[data-slot="port-endpoint"]')
    expect(ep2.querySelector('[data-slot="port-service"]')).toBeNull()
    expect(ep2.querySelector('[data-slot="port-path"]')).toBeNull()
    expect(ep2.querySelector('[data-chip="family"]')).toBeNull()
    // port ve tür kartta YALNIZ bir kez (uç nokta satırında)
    expect(cardOf(other.container).textContent.match(/:7001/g)).toHaveLength(1)
    expect(cardOf(other.container).textContent.match(/UDP/g)).toHaveLength(1)
  })

  it('tür rozeti dokun-gör: ne denetlendiğini balonda söyler (telefonda da açılır); adı satırı taşır', async () => {
    renderCard({ protocol: 'BANNER', expect: 'SSH-2.0', port: 22, host: 'bastion.example.com' })
    const trigger = screen.getByRole('button', { name: /^bastion\.example\.com:22 — Banner — / })
    fireEvent.click(trigger)
    expect(await screen.findByRole('tooltip')).toHaveTextContent(/Banner — (Response match|Yanıt eşleştirme)/)
  })

  it('SONUÇ PANELİ: açık / reddedildi / filtreli (zaman aşımı) / DNS / HTTP — durum + tek satırlık neden, tonlu', () => {
    const cases = [
      [{}, 'open', 'ok', /^(Accepting connections|Bağlantı kabul ediyor)$/, /TCP handshake completed|TCP el sıkışması tamamlandı/],
      [{ status: 'closed', response_ms: null, error: 'Connection refused' }, 'refused', 'bad', /^(Connection refused|Bağlantı reddedildi)$/,
        /nothing is listening on :3306|:3306 portunda dinleyen servis yok/],
      [{ status: 'closed', response_ms: null, error: 'Connect timed out', timeout_ms: 5000 }, 'filtered', 'bad', /^(Filtered — timed out|Filtreli — zaman aşımı)$/,
        /No reply within 5 s — a firewall|5 s içinde yanıt yok — büyük olasılıkla/],
      [{ status: 'closed', response_ms: null, error: 'çözümlenemeyen host: db.example.com' }, 'dns', 'bad', /^(DNS failure|DNS hatası)$/,
        /db\.example\.com (couldn’t be resolved|çözümlenemedi)/],
      [{ status: 'closed', protocol: 'HTTP', send_data: '/status', response_ms: 96, error: 'HTTP 503 (beklenen: 2xx)' }, 'http', 'bad', /^(Unexpected status|Beklenmeyen durum kodu)$/,
        /Returned HTTP 503; expected 2xx|HTTP 503 döndü; beklenen 2xx/],
      [{ status: 'closed', protocol: 'UDP', response_ms: null, error: 'UDP yanit yok (timeout — acik/filtreli olabilir)' }, 'filtered', 'bad', /Filtered|Filtreli/,
        /open and filtered ports look the same|açık ve filtreli port aynı görünür/],
      [{ status: 'unknown', response_ms: null, checked_at: null }, 'pending', 'neutral', /^(Awaiting first check|İlk kontrol bekleniyor)$/, /after the first check|ilk kontrolden sonra/],
    ]
    for (const [row, state, tone, title, why] of cases) {
      const { container, unmount } = renderCard(row)
      const p = panel(container)
      expect(p, state).toHaveAttribute('data-state', state)
      expect(p).toHaveAttribute('data-tone', tone)
      expect(p).toHaveAttribute('role', 'group')
      expect(p.querySelector('[data-slot="port-result-title"]').textContent).toMatch(title)
      expect(p.querySelector('[data-slot="port-result-why"]').textContent).toMatch(why)
      expect(cardOf(container)).toHaveAttribute('data-result', state)
      unmount()
    }
  })

  it('başarılı sonuç türe göre kanıtı söyler: HTTP yolu + beklenen kod, banner beklenen metni, TLS el sıkışma (TLS/sertifika bilgisi satırda YOK → kutu uydurulmaz)', () => {
    const http = renderCard({ protocol: 'HTTP', send_data: '/health/ready', expect: '200' })
    expect(panel(http.container).textContent).toMatch(/GET \/health\/ready matched 200|GET \/health\/ready → 200 ile eşleşti/)
    http.unmount()
    const banner = renderCard({ protocol: 'BANNER', expect: 'SSH-2.0' })
    expect(panel(banner.container).textContent).toMatch(/contained «SSH-2\.0»|«SSH-2\.0» içeriyordu/)
    banner.unmount()
    const tls = renderCard({ protocol: 'TLS', port: 443, response_ms: 42 })
    expect(panel(tls.container).textContent).toMatch(/TLS handshake OK|TLS el sıkışması başarılı/)
    expect(tile(tls.container, 'connect').textContent).toMatch(/Handshake time|El sıkışma süresi/)
    expect([...tls.container.querySelectorAll('[data-slot="port-metric"]')].map((x) => x.getAttribute('data-metric'))).toEqual(['connect'])
  })

  it('bağlantı süresi kutusu: eşik aşılınca amber "Slow" + sınır; eşik içinde yeşil; eşik yoksa 24 sa sapması; zaman aşımında kırmızı "No reply within 5 s"; reddedilince "No connection"', () => {
    const slow = renderCard({ response_ms: 3480, slow_response_enabled: true, slow_threshold_ms: 3000 })
    const c = tile(slow.container, 'connect')
    expect(c).toHaveAttribute('data-tone', 'warn')
    expect(c.querySelector('[data-slot="port-metric-value"]').textContent).toBe('3.5s')
    expect(within(c).getByText(/^(Slow|Yavaş)$/)).toHaveAttribute('data-slot', 'port-metric-verdict')
    expect(c.querySelector('[data-slot="port-metric-sub"]').textContent).toMatch(/^(Limit 3 s|Sınır 3 s)$/)
    slow.unmount()
    const ok = renderCard({ response_ms: 120, slow_response_enabled: true, slow_threshold_ms: 3000 })
    expect(tile(ok.container, 'connect')).toHaveAttribute('data-tone', 'ok')
    expect(tile(ok.container, 'connect').querySelector('[data-slot="port-metric-verdict"]')).toBeNull()
    ok.unmount()
    const neutral = renderCard({ response_ms: 96 }, { spark: sparkOf([30, 40, 50]) })
    expect(tile(neutral.container, 'connect')).toHaveAttribute('data-tone', 'neutral')
    expect(tile(neutral.container, 'connect').querySelector('[data-slot="port-metric-sub"]').textContent).toMatch(/\+140% vs 24 h avg|\+%140 \(24 sa ort\.\)/)
    neutral.unmount()
    const filtered = renderCard({ status: 'closed', response_ms: null, error: 'Connect timed out', timeout_ms: 5000 })
    expect(tile(filtered.container, 'connect')).toHaveAttribute('data-tone', 'bad')
    expect(tile(filtered.container, 'connect').textContent).toMatch(/—.*(No reply within 5 s|5 s içinde yanıt yok)/)
    filtered.unmount()
    const refused = renderCard({ status: 'closed', response_ms: null, error: 'Connection refused' })
    expect(tile(refused.container, 'connect')).toHaveAttribute('data-tone', 'bad')
    expect(tile(refused.container, 'connect').textContent).toMatch(/No connection|Bağlantı yok/)
  })

  it('24 sa ortalaması kutusu trendden (en az 3 saat); yoksa çizilmez ve ilk kutu tam genişlik; hiç kontrol yoksa kutu yok', () => {
    const withBase = renderCard({ response_ms: 12 }, { spark: sparkOf([10, 12, 20, null]) })
    const avg = tile(withBase.container, 'avg24h')
    expect(avg.querySelector('[data-slot="port-metric-value"]').textContent).toBe('14ms')
    expect(avg.textContent).toMatch(/hourly 10–20 ms|saatlik 10–20 ms/)
    expect(tile(withBase.container, 'connect').className).not.toMatch(/col-span-2/)
    withBase.unmount()
    const noBase = renderCard({ response_ms: 12 }, { spark: sparkOf([10, null]) })
    expect(tile(noBase.container, 'avg24h')).toBeNull()
    expect(tile(noBase.container, 'connect').className).toMatch(/(^|\s)col-span-2(\s|$)/)
    noBase.unmount()
    const pending = renderCard({ status: 'unknown', response_ms: null, checked_at: null })
    expect(pending.container.querySelector('[data-slot="port-metric"]')).toBeNull()
    expect(pending.container.querySelector('[data-slot="ping-checked-at"]')).toHaveAttribute('data-never', 'true')
  })

  it('kaynak rozeti: Bağımsız / Envanterden — açıklama dokun-gör balonunda; adı satırı taşır', async () => {
    const standalone = renderCard({ standalone: true })
    expect(standalone.container.querySelector('[data-slot="port-source"]')).toHaveAttribute('data-source', 'standalone')
    standalone.unmount()
    const derived = renderCard({ standalone: null, name: 'db.example.com' })
    const badge = derived.container.querySelector('[data-slot="port-source"]')
    expect(badge).toHaveAttribute('data-source', 'inventory')
    expect(badge.textContent).toMatch(/^(From inventory|Envanterden)$/)
    fireEvent.click(screen.getByRole('button', { name: /^db\.example\.com:3306 — (From inventory|Envanterden)$/ }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent(/only stops monitoring|yalnız izlemeyi durdurur/)
    // ad host ile aynıysa ikinci satır yok
    expect(derived.container.querySelector('[data-slot="port-name"]')).toBeNull()
  })

  it('kapalı kart TÜM kenarı kırmızı tonlu (sol şerit YOK); duraklatılmış: kesik/soluk + "Paused" + Sürdür; alarm: seviye rozeti + dış çizgi; bakım rozeti', async () => {
    const down = renderCard({ status: 'closed', response_ms: null, error: 'Connection refused' })
    const card = cardOf(down.container)
    expect(card).toHaveAttribute('data-status', 'down')
    expect(card.className).toMatch(/(^|\s)border-destructive\/45(\s|$)/)
    expect(card.className).not.toMatch(/border-l-|before:/)
    down.unmount()

    const onResume = vi.fn()
    const paused = renderCard({ active: false }, {
      actions: <MonitorCardActions rowLabel="db.example.com:3306" onResume={onResume} onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} checkTitle="Check now" editTitle="Edit" />,
    })
    expect(cardOf(paused.container)).toHaveAttribute('data-inactive', 'true')
    expect(cardOf(paused.container).className).not.toMatch(/border-destructive\/45/)
    expect(paused.container.querySelector('[data-slot="card-footer"] [data-slot="monitor-paused"]')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /^db\.example\.com:3306 — (Resume|Sürdür)$/ }))
    expect(onResume).toHaveBeenCalledTimes(1)
    paused.unmount()

    const alarm = renderCard({ status: 'closed', response_ms: null, error: 'Connect timed out', active_alarm: true, alarm_level: 'CRITICAL', alarm_acknowledged: false })
    const ac = cardOf(alarm.container)
    expect(ac).toHaveAttribute('data-alarm', 'true')
    expect(ac.querySelector('[data-slot="monitor-alarm"]')).toHaveAttribute('data-level', 'CRITICAL')
    expect(ac.className).not.toMatch(/border-destructive\/45/)
    alarm.unmount()

    const maint = renderCard({ host: 'mail.example.com', port: 25 })
    await waitFor(() => expect(maint.container.querySelector('[data-slot="maintenance-badge"]')).not.toBeNull())
    expect(maint.container.querySelector('[data-slot="port-service"]').textContent).toMatch(/SMTP/)
  })

  it('başlık GERÇEK düğme ve detayı açar; host:port kopyala (IPv6 köşeli) / tür / kaynak / seçim kutusu / eylemler detayı AÇMAZ; adlar satırı taşır', async () => {
    const onOpen = vi.fn()
    const onSel = vi.fn()
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: vi.fn(() => Promise.resolve()) } })
    renderCard({ host: '2001:db8::10', port: 443, name: 'Edge router' }, {
      onOpen,
      select: <Checkbox className={CARD_CHECK} checked={false} onCheckedChange={onSel} aria-label="Select 2001:db8::10:443 for bulk action" />,
      meta: <MonitorCardMeta monitor={{ ...base, host: '2001:db8::10' }} />,
      actions: <MonitorCardActions rowLabel="2001:db8::10:443" onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} onDelete={() => {}} checkTitle="Check now" editTitle="Edit" deleteTitle="Delete" />,
    })
    const title = screen.getByRole('button', { name: /^2001:db8::10:443 — (open details|detayları aç)$/ })
    expect(title).toHaveAttribute('data-monitor-open', 'true')
    fireEvent.click(title)
    expect(onOpen).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: /^2001:db8::10:443 — (Copy address \(host:port\)|Adresi kopyala \(host:port\))$/ }))
    await waitFor(() => expect(navigator.clipboard.writeText).toHaveBeenCalledWith('[2001:db8::10]:443'))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select 2001:db8::10:443 for bulk action' }))
    expect(onSel).toHaveBeenCalled()
    for (const name of [/— Check now$/, /— Edit$/, /— (Duplicate|Kopyala)$/, /— Delete$/]) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument()
    }
    expect(onOpen).toHaveBeenCalledTimes(1)   // hiçbiri detayı açmadı
    expect(document.querySelector('[data-slot="port-name"]').textContent).toBe('Edge router')
    expect(document.querySelector('[data-slot="meta-team"]')).not.toBeNull()
  })

  it('telefon: ölçüler her genişlikte iki sütun, uç nokta satırı SARAR, host kırpılır (uzun ad / IPv6), dokunmatikte 40 px hedefler; son kontrol göreli + tam zaman', () => {
    const { container } = renderCard({ host: 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com', port: 8443, protocol: 'HTTP', send_data: '/health/ready' },
      { spark: sparkOf([10, 12, 20]) })
    expect(container.querySelector('[data-slot="monitor-metrics"]').className).toMatch(/(^|\s)grid-cols-2(\s|$)/)
    expect(container.querySelector('[data-slot="port-endpoint"]').className).toMatch(/(^|\s)flex-wrap(\s|$)/)
    expect(container.querySelector('[data-slot="port-path"]').className).toMatch(/(^|\s)truncate(\s|$)/)
    const titleText = container.querySelector('[data-monitor-open] > span')
    expect(titleText.className).toMatch(/(^|\s)truncate(\s|$)/)
    expect(titleText).toHaveAttribute('title', 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com:8443')
    const copy = screen.getByRole('button', { name: /Copy address|Adresi kopyala/ })
    expect(copy.className).toContain('pointer-coarse:size-10')
    for (const hint of container.querySelectorAll('[data-slot="hint-trigger"]')) expect(hint.className).toContain('pointer-coarse:min-h-10')
    expect(container.querySelector('[data-slot="port-result-why"]').className).toMatch(/(^|\s)line-clamp-2(\s|$)/)
    const at = container.querySelector('[data-slot="ping-checked-at"]')
    expect(at.textContent).toMatch(/^(3 min ago|3 dk önce) \(exact:/)
  })
})

/**
 * KART YOĞUNLUĞU (2026-09-27): Kompakt = durum satırı + host + TEK ikincil satır (:port · tür · ad) + bağlantı HÜKMÜ ve
 * süresi + kötüyse tek satır neden + kaynak rozeti + YALNIZ takım + alt çubuk. Sonuç paneli, süre kutuları, trend, yol /
 * hizmet / aile / sıklık çipleri, grup/vekil ve etiketler yalnız Zengin'de.
 */
describe('PortMonitorCard — Kompakt / Zengin yoğunluk', () => {
  const q = (root, s) => root.querySelector(`[data-slot="${s}"]`)
  const richOnly = ['monitor-card-rich', 'port-result', 'port-metric', 'monitor-spark', 'port-service', 'port-path', 'meta-group', 'ping-tags']

  it('Kompakt: Zengin parçalar DOM\'da YOK; host, :port · tür · ad, hüküm + süre, kaynak, takım, zaman ve eylemler VAR', () => {
    const m = { host: 'api.example.com', port: 8443, protocol: 'HTTP', send_data: '/health', ip_version: 'v6', interval_seconds: 60, name: 'Ödeme API', response_ms: 42 }
    const onOpen = vi.fn()
    const { container } = renderCard(m, {
      density: 'compact', onOpen, spark: sparkOf([30, 40, 50]),
      meta: <MonitorCardMeta monitor={{ ...base, ...m }} />,
      actions: <MonitorCardActions rowLabel="api.example.com:8443" onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} onDelete={() => {}} checkTitle="Check now" editTitle="Edit" deleteTitle="Delete" />,
    })
    const card = cardOf(container)
    expect(card).toHaveAttribute('data-density', 'compact')
    for (const s of richOnly) expect(q(card, s), s).toBeNull()
    expect(card.querySelector('[data-chip]')).toBeNull()                       // aile / sıklık çipleri yok
    // TEK ikincil satır: :port · tür · ad (sarmaz, ad kırpılır)
    const ep = q(card, 'port-endpoint')
    expect(ep).toHaveAttribute('data-density', 'compact')
    expect(ep.className).not.toMatch(/flex-wrap/)
    expect(q(ep, 'port-number').textContent).toBe(':8443')
    expect(q(ep, 'port-protocol')).toHaveAttribute('data-protocol', 'HTTP')
    expect(q(ep, 'port-name').textContent).toBe('Ödeme API')
    expect(q(ep, 'port-name').className).toMatch(/(^|\s)truncate(\s|$)/)
    expect(card.querySelectorAll('[data-slot="port-name"]')).toHaveLength(1)   // ad bir kez
    // ana ölçü: hüküm + süre
    const sum = q(card, 'port-compact')
    expect(sum).toHaveAttribute('data-state', 'open')
    expect(sum).toHaveAttribute('data-tone', 'ok')
    expect(q(sum, 'port-compact-verdict').textContent).toMatch(/^(Expected status|Beklenen durum kodu)$/)
    expect(q(sum, 'port-compact-time').textContent).toMatch(/(Response time|Yanıt süresi): 42 ms$/)
    expect(q(card, 'port-compact-reason')).toBeNull()                          // sağlıklı → neden satırı yok
    expect(q(card, 'port-source')).toHaveAttribute('data-source', 'standalone')
    expect(q(card, 'meta-team').textContent).toMatch(/Takım A/)
    expect(q(card, 'ping-checked-at').textContent).toMatch(/^(3 min ago|3 dk önce)/)
    for (const name of [/— Check now$/, /— Edit$/, /— (Duplicate|Kopyala)$/, /— Delete$/]) expect(screen.getByRole('button', { name })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /^api\.example\.com:8443 — (open details|detayları aç)$/ }))
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('Zengin (varsayılan): sonuç paneli, süre kutuları ve trend MonitorCardRich içinde; uç nokta çipleri + ad satırı + etiketler; Kompakt özeti yok', () => {
    const { container } = renderCard({ protocol: 'HTTP', send_data: '/health', response_ms: 12 }, { spark: sparkOf([10, 12, 20]), meta: <MonitorCardMeta monitor={base} /> })
    const card = cardOf(container)
    expect(card).toHaveAttribute('data-density', 'rich')
    const rich = q(card, 'monitor-card-rich')
    for (const s of ['port-result', 'port-metric', 'monitor-spark']) expect(q(rich, s), s).not.toBeNull()
    expect(q(card, 'port-endpoint')).not.toHaveAttribute('data-density')
    expect(q(card, 'port-path').textContent).toBe('/health')
    expect(q(card, 'meta-group')).not.toBeNull()
    expect(q(card, 'port-compact')).toBeNull()
  })

  it('Kompakt sorun: reddedildi → kırmızı hüküm + TEK satır neden (kırpılır), tamamı dokun-gör balonunda; süre yoksa sağ uç boş', async () => {
    const { container } = renderCard({ status: 'closed', response_ms: null, error: 'Connection refused', active_alarm: true, alarm_level: 'CRITICAL' }, { density: 'compact' })
    const sum = q(container, 'port-compact')
    expect(sum).toHaveAttribute('data-state', 'refused')
    expect(sum).toHaveAttribute('data-tone', 'bad')
    expect(q(sum, 'port-compact-verdict').textContent).toMatch(/^(Connection refused|Bağlantı reddedildi)$/)
    expect(q(sum, 'port-compact-time')).toBeNull()
    const reason = q(sum, 'port-compact-reason')
    expect(reason.textContent).toMatch(/nothing is listening on :3306|:3306 portunda dinleyen servis yok/)
    expect(reason.className).toMatch(/(^|\s)truncate(\s|$)/)
    const trigger = reason.closest('button')
    expect(trigger.className).toContain('pointer-coarse:min-h-10')
    fireEvent.click(trigger)
    expect((await screen.findByRole('tooltip')).textContent).toBe(reason.textContent)
    expect(q(container, 'monitor-alarm')).toHaveAttribute('data-level', 'CRITICAL')
  })

  it('Kompakt: yavaşlık eşiği aşılınca süre amber (salyangoz + ekran okuyucuya "Slow"); ilk kontrol bekleniyorsa neden yok', () => {
    const slow = renderCard({ response_ms: 3480, slow_response_enabled: true, slow_threshold_ms: 3000 }, { density: 'compact' })
    const time = q(slow.container, 'port-compact-time')
    expect(time).toHaveAttribute('data-tone', 'warn')
    expect(time.textContent).toMatch(/3\.5 s — (Slow|Yavaş)$/)
    slow.unmount()
    const pending = renderCard({ status: 'unknown', response_ms: null, checked_at: null }, { density: 'compact' })
    expect(q(pending.container, 'port-compact')).toHaveAttribute('data-state', 'pending')
    expect(q(pending.container, 'port-compact-reason')).toBeNull()
    expect(q(pending.container, 'ping-checked-at')).toHaveAttribute('data-never', 'true')
  })

  it('kaynak rozeti İKİ görünümde de; envanter türevi satırda eylem adı "izlemeyi durdur" Kompakt\'ta da aynı', () => {
    for (const density of ['compact', 'rich']) {
      const { container, unmount } = renderCard({ standalone: null }, {
        density,
        actions: <MonitorCardActions rowLabel="db.example.com:3306" onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} onDelete={() => {}}
          checkTitle="Check now" editTitle="Edit" deleteTitle="Stop monitoring (inventory-derived record is kept)" />,
      })
      expect(q(container, 'port-source'), density).toHaveAttribute('data-source', 'inventory')
      expect(q(container, 'port-source').textContent).toMatch(/^(From inventory|Envanterden)$/)
      expect(screen.getByRole('button', { name: 'db.example.com:3306 — Stop monitoring (inventory-derived record is kept)' })).toBeInTheDocument()
      unmount()
    }
  })
})
