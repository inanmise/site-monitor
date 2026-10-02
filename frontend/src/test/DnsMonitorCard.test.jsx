import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within, act } from './test-utils.jsx'
import DnsMonitorCard from '../components/dns/DnsMonitorCard.jsx'
import { formatTtl, responseTone, splitValues, unexpectedValues, mxParts, LONG_VALUE } from '../components/dns/dnsValue.js'
import { MonitorStatusBadge } from '../components/monitoring/MonitorCard.jsx'
import MonitorCardMeta from '../components/MonitorCardMeta.jsx'
import MonitorCardActions from '../components/MonitorCardActions.jsx'
import { EN } from '../i18n/en.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => `exact ${s ?? ''}`,
  api: withApiFallback({
    // MaintenanceBadge önbelleği modül düzeyinde: bakımda olan TEK alan adı baştan tanımlı (diğer kartlar etkilenmez)
    monitoring: { maintenance: { active: vi.fn(() => Promise.resolve({ success: true, data: { all: false, targets: ['maint.example.com'] } })) } },
  }),
}))

const ago = (min) => new Date(Date.now() - min * 60_000).toISOString().slice(0, 19)

const base = {
  id: 7, name: 'www.example.com', domain: 'www.example.com', record_type: 'A', standalone: false, active: true,
  team_id: 1, team_name: 'Takım A', group_name: 'Kurumsal Web', value: '203.0.113.10', ttl: 300, response_ms: 14,
  checked_at: ago(3), changed: false, rotated: false, expected_value: null, slow_threshold_ms: null, active_alarm: false,
}

/** Kartı sayfadaki gibi kurar: durum rozeti + yuvalar sayfadan gelir (burada test yerine basit karşılıkları). */
function renderCard(patch = {}, props = {}) {
  const m = { ...base, ...patch }
  const onOpen = vi.fn()
  const utils = render(
    <DnsMonitorCard monitor={m} status={m.active_alarm ? 'down' : 'up'} onOpen={onOpen}
      statusBadge={<MonitorStatusBadge status={m.active_alarm ? 'down' : 'up'}>{m.active_alarm ? 'Alarming' : 'Healthy'}</MonitorStatusBadge>}
      alarmLabel={`Active alarm — ${m.alarm_level || ''}`}
      actions={<span data-testid="actions">actions</span>}
      {...props} />,
  )
  const card = utils.container.querySelector('[data-slot="card"]')
  return { ...utils, card, onOpen, m }
}

const slot = (root, name) => root.querySelector(`[data-slot="${name}"]`)
const slots = (root, name) => [...root.querySelectorAll(`[data-slot="${name}"]`)]

describe('dnsValue yardımcıları', () => {
  it('çoklu değer satırlara ayrılır (boş satır/boşluk atılır), beklenmeyen = beklenen listede olmayan', () => {
    expect(splitValues(' 203.0.113.10\n\n203.0.113.11 \n')).toEqual(['203.0.113.10', '203.0.113.11'])
    expect(splitValues(null)).toEqual([])
    expect(unexpectedValues('203.0.113.10\n203.0.113.11', ['203.0.113.10', '198.51.100.66'])).toEqual(['198.51.100.66'])
    // Rotasyon (alt küme) sapma değil; kilit kapalıysa hiçbir şey beklenmeyen değil
    expect(unexpectedValues('203.0.113.10\n203.0.113.11', ['203.0.113.11'])).toEqual([])
    expect(unexpectedValues('', ['198.51.100.66'])).toEqual([])
    expect(mxParts('10 mx1.example.com.')).toEqual({ priority: '10', host: 'mx1.example.com.' })
    expect(mxParts('mx1.example.com.')).toBeNull()
  })

  it('TTL insan diline çevrilir: en büyük birim + sıfır değilse hemen alttaki', () => {
    const t = (k, n) => EN[k].replace('{0}', n)
    expect(formatTtl(300, t)).toBe('5 min')
    expect(formatTtl(90, t)).toBe('1 min 30 s')
    expect(formatTtl(3660, t)).toBe('1 h 1 min')
    expect(formatTtl(3600, t)).toBe('1 h')
    expect(formatTtl(86400, t)).toBe('1 d')
    expect(formatTtl(86400 + 300, t)).toBe('1 d')          // 0 sa → dakikalar gösterilmez
    expect(formatTtl(2 * 86400 + 3 * 3600, t)).toBe('2 d 3 h')
    expect(formatTtl(45, t)).toBe('45 s')
    expect(formatTtl(0, t)).toBe('0 s')
    expect(formatTtl(null, t)).toBeNull()
    expect(formatTtl('abc', t)).toBeNull()
  })

  it('yanıt tonu yalnız izlemenin kendi eşiğiyle: aşarsa slow, altındaysa ok, eşik yoksa ton yok', () => {
    expect(responseTone(1840, 1500)).toBe('slow')
    expect(responseTone(1500, 1500)).toBe('slow')
    expect(responseTone(200, 1500)).toBe('ok')
    expect(responseTone(1840, null)).toBeNull()
    expect(responseTone(null, 1500)).toBeNull()
  })
})

describe('DnsMonitorCard', () => {
  it('başlık: alan adı gerçek düğme (stretched) → detayı açar; üst satırda durum rozeti + kayıt tipi rozeti; alan adı bir kez yazılır', async () => {
    const { card, onOpen } = renderCard()
    const title = within(card).getByRole('button', { name: 'www.example.com — open details' })
    expect(title).toHaveAttribute('data-monitor-open', 'true')
    fireEvent.click(title)
    expect(onOpen).toHaveBeenCalledTimes(1)
    expect(screen.getAllByText('www.example.com')).toHaveLength(1)
    expect(slot(card, 'dns-record-type').textContent).toBe('Record Type: A')
    // Alan adını kopyala (satırı ayırt eden ad) ve bağlantıyı kopyala ayrı düğmeler; kopyala detayı AÇMAZ
    await act(async () => { fireEvent.click(within(card).getByRole('button', { name: 'Copy www.example.com' })) })
    expect(onOpen).toHaveBeenCalledTimes(1)
    // 2026-09-27 a11y A1: bağlantıyı kopyala düğmesinin adı KARTI ayırt eder (ızgarada özdeş "Copy link" yok)
    expect(within(card).getByRole('button', { name: 'www.example.com — Copy link' })).toHaveAttribute('title', 'Copy link')
  })

  it('çoklu değer: ilk 3 değer satır satır (her biri kendi kopyala düğmesiyle), kalanı "+2 more" çipinde; çip dokun-gör balonunda listeler', () => {
    const { card } = renderCard({ value: '203.0.113.10\n203.0.113.11\n203.0.113.12\n203.0.113.13\n203.0.113.14' })
    const panel = slot(card, 'dns-values')
    expect(panel).toHaveAttribute('data-count', '5')
    expect(panel.textContent).toMatch(/Record values · 5/)
    expect(slots(panel, 'dns-value').map((li) => li.textContent)).toEqual(['203.0.113.10', '203.0.113.11', '203.0.113.12'])
    for (const v of ['203.0.113.10', '203.0.113.11', '203.0.113.12']) {
      expect(within(panel).getByRole('button', { name: `Copy ${v}` })).toBeInTheDocument()
    }
    expect(within(panel).queryByText('203.0.113.13')).toBeNull()
    const more = within(panel).getByRole('button', { name: 'www.example.com — +2 more' })
    expect(more.textContent).toBe('+2 more')
    fireEvent.click(more)
    const tip = screen.getByRole('tooltip')
    expect(tip.textContent).toBe('203.0.113.13\n203.0.113.14')
  })

  it('tek değerde sayaç ve "+N" yok; MX önceliği ayrı gösterilir, kopyalanan ad ham değer', () => {
    const { card } = renderCard({ record_type: 'MX', value: '10 mx1.example.com.' })
    const panel = slot(card, 'dns-values')
    expect(panel.textContent).not.toMatch(/·/)
    expect(slot(panel, 'dns-more-values')).toBeNull()
    expect(within(panel).getByText('10')).toBeInTheDocument()
    expect(within(panel).getByText('mx1.example.com.')).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Copy 10 mx1.example.com.' })).toBeInTheDocument()
  })

  it('uzun TXT değeri iki satırda kırpılır, tamamı dokun-gör balonunda; kısa değerde balon tetiği yok', () => {
    const spf = '"v=spf1 include:_spf.example.com include:mail.example.net ip4:203.0.113.0/24 ~all"'
    expect(spf.length).toBeGreaterThan(LONG_VALUE)
    const { card } = renderCard({ record_type: 'TXT', value: `${spf}\n"short=1"` })
    const [longRow, shortRow] = slots(card, 'dns-value')
    const trigger = longRow.querySelector('[data-slot="hint-trigger"]')
    expect(trigger).not.toBeNull()
    expect(shortRow.querySelector('[data-slot="hint-trigger"]')).toBeNull()
    fireEvent.click(trigger)
    expect(screen.getByRole('tooltip').textContent).toBe(spf)
    // Kopyala düğmesinin adı kısaltılır (ekran okuyucu 80 karakteri okumasın), kopyalanan değer ham
    expect(within(longRow).getByRole('button', { name: `Copy ${spf.slice(0, 40)}…` })).toBeInTheDocument()
  })

  it('değişti: amber çağrı — ne zaman + önceki → güncel (sunucu önceki değeri gönderirse)', () => {
    const { card } = renderCard({ changed: true, checked_at: ago(5), value: '198.51.100.20', previous_value: '203.0.113.20' })
    const callout = slot(card, 'dns-changed')
    expect(callout).not.toBeNull()
    expect(callout).not.toHaveAttribute('role')                       // 50 kartta 50 canlı bölge olmasın
    expect(callout.textContent).toMatch(/Value changed · detected 5 min ago/)
    expect(callout.querySelector('s').textContent).toBe('203.0.113.20')
    expect(callout.textContent).toMatch(/198\.51\.100\.20$/)
    expect(slot(card, 'dns-rotation')).toBeNull()
  })

  it('değişti ama önceki değer yok (liste yanıtı taşımıyor): karşılaştırma için kartı açma yönlendirmesi', () => {
    const { card } = renderCard({ changed: true, rotated: true })
    const callout = slot(card, 'dns-changed')
    expect(callout.textContent).toMatch(/Open the card to compare it with the previous value\./)
    expect(callout.querySelector('s')).toBeNull()
    expect(slot(card, 'dns-rotation')).toBeNull()                   // değişti baskın: rotasyon çipi çizilmez
  })

  it('rotasyon: nötr "kesinti değil" çipi, açıklaması dokun-gör; değişti çağrısı yok', () => {
    const { card } = renderCard({ rotated: true, value: '203.0.113.30\n203.0.113.31' })
    const chip = slot(card, 'dns-rotation')
    expect(chip.textContent).toBe('Rotation — not an outage')
    expect(chip).toHaveAttribute('data-variant', 'outline')
    fireEvent.click(chip.closest('button'))
    expect(screen.getByRole('tooltip').textContent).toBe(EN['dns.rotationTitle'])
    expect(slot(card, 'dns-changed')).toBeNull()
  })

  it('beklenmeyen değer: kilit doluyken listede olmayan değer yıkıcı satır + işaretli değer; beklenen liste dokun-gör', () => {
    const { card } = renderCard({ value: '198.51.100.66\n203.0.113.40', expected_value: '203.0.113.40\n203.0.113.41' })
    expect(slot(card, 'dns-mismatch').textContent).toBe('Not in the expected list: 198.51.100.66')
    expect(slots(card, 'dns-value').map((li) => li.getAttribute('data-unexpected'))).toEqual(['true', null])
    expect(slot(card, 'dns-expected-ok')).toBeNull()
    fireEvent.click(slot(card, 'dns-mismatch').closest('button'))
    expect(screen.getByRole('tooltip').textContent).toBe('Expected values: 203.0.113.40, 203.0.113.41')
  })

  it('kilit tutuyor (değerler beklenen listenin alt kümesi = rotasyon toleransı): yeşil "Matches expected", sapma yok', () => {
    const { card } = renderCard({ value: '203.0.113.41', expected_value: '203.0.113.40\n203.0.113.41' })
    expect(slot(card, 'dns-mismatch')).toBeNull()
    expect(slot(card, 'dns-expected-ok').textContent).toBe('Matches expected')
  })

  it.each([
    [true, 'standalone', 'Standalone', EN['dns.standaloneHint']],
    [false, 'inventory', 'From inventory', EN['dns.cardFromInventoryHint']],
  ])('kaynak rozeti standalone=%s → %s (açıklaması dokun-gör)', (standalone, source, text, hint) => {
    const { card } = renderCard({ standalone })
    const badge = slot(card, 'dns-source')
    expect(badge).toHaveAttribute('data-source', source)
    expect(badge.textContent).toBe(text)
    fireEvent.click(badge.closest('button'))
    expect(screen.getByRole('tooltip').textContent).toBe(hint)
  })

  it('ölçüler: TTL insan dilinde, yanıt süresi izlemenin eşiğine göre tonlanır (eşik yoksa ton yok)', () => {
    const slow = renderCard({ ttl: 3600, response_ms: 1840, slow_threshold_ms: 1500 })
    const metrics = slot(slow.card, 'monitor-metrics')
    expect(metrics.textContent).toMatch(/1 hTTL/)
    expect(slot(metrics, 'dns-response')).toHaveAttribute('data-tone', 'slow')
    expect(slot(metrics, 'dns-response').textContent).toMatch(/1840 ms — Slow resolution/)
    slow.unmount()

    const ok = renderCard({ response_ms: 200, slow_threshold_ms: 1500 })
    expect(slot(ok.card, 'dns-response')).toHaveAttribute('data-tone', 'ok')
    ok.unmount()

    const none = renderCard({ response_ms: 1840 })
    expect(slot(none.card, 'dns-response')).not.toHaveAttribute('data-tone')
  })

  it('yanıt yok (kontrol edildi, değer boş) ve hiç kontrol edilmedi ayrı sözlerle; alarmda yıkıcı ton', () => {
    const failed = renderCard({ value: '', active_alarm: true, alarm_level: 'CRITICAL', ttl: null, response_ms: null })
    const empty = slot(failed.card, 'dns-values-empty')
    expect(empty.textContent).toBe('No answer — no records returned')
    expect(empty).toHaveAttribute('data-failed', 'true')
    expect(slot(failed.card, 'monitor-metrics')).toBeNull()
    failed.unmount()

    const fresh = renderCard({ value: null, checked_at: null, ttl: null, response_ms: null })
    expect(slot(fresh.card, 'dns-values-empty').textContent).toBe('Awaiting the first check')
    expect(slot(fresh.card, 'monitor-card-time')).toBeNull()
  })

  it('duraklatılmış / alarm / bakım: kart durumları ortak aileden (sol şerit yok, tüm kenar)', async () => {
    const paused = renderCard({ active: false })
    expect(paused.card).toHaveAttribute('data-inactive', 'true')
    expect(slot(paused.card, 'monitor-paused').textContent).toMatch(/Paused/)
    paused.unmount()

    const alarm = renderCard({ active_alarm: true, alarm_level: 'CRITICAL' })
    expect(alarm.card).toHaveAttribute('data-alarm', 'true')
    expect(alarm.card).toHaveAttribute('data-status', 'down')
    expect(slot(alarm.card, 'monitor-alarm').textContent).toMatch(/Critical/)
    expect(alarm.card.className).not.toMatch(/border-l-|before:/)
    alarm.unmount()

    const maint = renderCard({ domain: 'maint.example.com', name: 'maint.example.com' })
    expect(await within(maint.card).findByText('Under maintenance')).toBeInTheDocument()
  })

  it('son kontrol göreli zamanla, tam zaman ipucunda; ad alan adından farklıysa alt satırda', () => {
    const { card } = renderCard({ checked_at: ago(12), name: 'Mail exchangers' })
    const time = slot(card, 'monitor-card-time')
    expect(time.textContent).toMatch(/12 min ago/)
    expect(time.querySelector('time')).toHaveAttribute('dateTime', expect.stringMatching(/^\d{4}-\d\d-\d\dT/))
    expect(slot(card, 'dns-name').textContent).toBe('Mail exchangers')
  })

  it('telefon: değer kopyala düğmeleri dokunmatikte 40 px ve görünür; değerler kırılarak sarar (yatay taşma yok)', () => {
    const { card } = renderCard({ value: '2001:db8:85a3::8a2e:370:7334' })
    const copy = within(card).getByRole('button', { name: 'Copy 2001:db8:85a3::8a2e:370:7334' })
    expect(copy.className).toMatch(/pointer-coarse:size-10/)
    expect(copy.className).toMatch(/pointer-coarse:opacity-100/)
    const text = slot(card, 'dns-value').querySelector('.font-mono')
    expect(text.className).toMatch(/break-all/)
    expect(text.className).toMatch(/min-w-0/)
  })
})

/**
 * KART YOĞUNLUĞU (2026-09-27): Kompakt = durum satırı + alan adı (+ad) + TEK satır değer özeti (ilk değer · +N · değişti
 * işareti) + sorun varsa tek satır neden + kaynak rozeti + YALNIZ takım + alt çubuk. Değer paneli, TTL/yanıt ölçüleri,
 * trend, grup/vekil yalnız Zengin'de (MonitorCardRich — Kompakt'ta DOM'a hiç girmez).
 */
describe('DnsMonitorCard — Kompakt / Zengin yoğunluk', () => {
  const full = (patch = {}) => ({ ...base, ...patch })
  const richOnlySlots = ['monitor-card-rich', 'dns-values', 'monitor-metrics', 'dns-changed', 'dns-rotation', 'dns-expected-ok']

  it('Kompakt: Zengin parçalar DOM\'da YOK; temel bilgiler (durum, alan adı, kayıt tipi, değer özeti, kaynak, takım, zaman, eylemler) VAR', () => {
    const m = full({ value: '203.0.113.10\n203.0.113.11\n203.0.113.12', changed: true, rotated: true, name: 'Kurumsal site' })
    const { card, onOpen } = renderCard(m, {
      density: 'compact',
      meta: <MonitorCardMeta monitor={m} />,
      spark: <span data-testid="spark">spark</span>,
    })
    expect(card).toHaveAttribute('data-density', 'compact')
    for (const s of richOnlySlots) expect(slot(card, s), s).toBeNull()
    expect(within(card).queryByTestId('spark')).toBeNull()
    expect(slot(card, 'meta-group')).toBeNull()                         // grup çipi Zengin'e kalır
    expect(slot(card, 'meta-team').textContent).toMatch(/Takım A/)      // takım rozeti kalır
    // temel bilgiler
    expect(within(card).getByText('Healthy')).toBeInTheDocument()
    expect(slot(card, 'dns-record-type').textContent).toBe('Record Type: A')
    expect(slot(card, 'dns-name').textContent).toBe('Kurumsal site')
    const sum = slot(card, 'dns-compact')
    expect(sum).toHaveAttribute('data-count', '3')
    expect(slot(sum, 'dns-compact-value').textContent).toBe('203.0.113.10')
    expect(slot(sum, 'dns-compact-value').className).toMatch(/(^|\s)truncate(\s|$)/)
    expect(slot(sum, 'dns-compact-more').textContent).toBe('+2 more')
    expect(slot(sum, 'dns-compact-changed').textContent).toBe('Value changed')
    expect(slot(card, 'dns-compact-reason')).toBeNull()                 // sorun yok → neden satırı yok
    expect(slot(card, 'dns-source')).toHaveAttribute('data-source', 'inventory')
    expect(slot(card, 'monitor-card-time').textContent).toMatch(/3 min ago/)
    expect(within(card).getByTestId('actions')).toBeInTheDocument()
    // başlık hâlâ gerçek düğme ve detayı açar; kopyala düğmeleri duruyor
    fireEvent.click(within(card).getByRole('button', { name: 'www.example.com — open details' }))
    expect(onOpen).toHaveBeenCalledTimes(1)
    expect(within(card).getByRole('button', { name: 'Copy www.example.com' })).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: 'www.example.com — Copy link' })).toBeInTheDocument()
  })

  it('Zengin (varsayılan): bugünkü tam kart — değer paneli, ölçüler, trend ve meta MonitorCardRich içinde; Kompakt özeti yok', () => {
    const m = full({ value: '203.0.113.10\n203.0.113.11', changed: true })
    const { card } = renderCard(m, { meta: <MonitorCardMeta monitor={m} />, spark: <span data-testid="spark">spark</span> })
    expect(card).toHaveAttribute('data-density', 'rich')
    const rich = slot(card, 'monitor-card-rich')
    expect(rich).not.toBeNull()
    for (const s of ['dns-values', 'monitor-metrics', 'dns-changed']) expect(slot(rich, s), s).not.toBeNull()
    expect(within(rich).getByTestId('spark')).toBeInTheDocument()
    expect(slot(card, 'meta-group').textContent).toMatch(/Kurumsal Web/)
    expect(slot(card, 'dns-compact')).toBeNull()
  })

  it('Kompakt sorun: beklenmeyen değer TEK satır neden (kırpılır), tam liste dokun-gör balonunda; ilk değer yıkıcı tonda', () => {
    const { card } = renderCard({ value: '198.51.100.66\n203.0.113.40', expected_value: '203.0.113.40\n203.0.113.41', active_alarm: true, alarm_level: 'HIGH' },
      { density: 'compact' })
    const reason = slot(card, 'dns-compact-reason')
    expect(reason).toHaveAttribute('data-reason', 'mismatch')
    expect(reason.textContent).toBe('Not in the expected list: 198.51.100.66')
    expect(reason.className).toMatch(/(^|\s)truncate(\s|$)/)
    expect(slot(card, 'dns-compact-value')).toHaveAttribute('data-unexpected', 'true')
    const trigger = reason.closest('button')
    expect(trigger.className).toContain('pointer-coarse:min-h-10')
    fireEvent.click(trigger)
    expect(screen.getByRole('tooltip').textContent).toBe('Not in the expected list: 198.51.100.66\nExpected values: 203.0.113.40, 203.0.113.41')
    expect(slot(card, 'monitor-alarm')).toHaveAttribute('data-level', 'HIGH')   // alarm rozeti Kompakt'ta da
  })

  it('Kompakt sorun: izlemenin eşiğini aşan yavaş çözümleme neden satırında; yanıt yok / ilk kontrol ayrı sözlerle', () => {
    const slow = renderCard({ response_ms: 1840, slow_threshold_ms: 1500 }, { density: 'compact' })
    const reason = slot(slow.card, 'dns-compact-reason')
    expect(reason).toHaveAttribute('data-reason', 'slow')
    expect(reason.textContent).toBe('Slow resolution: 1840 ms (threshold 1500 ms)')
    slow.unmount()

    const failed = renderCard({ value: '', active_alarm: true, alarm_level: 'CRITICAL', response_ms: null }, { density: 'compact' })
    const empty = slot(failed.card, 'dns-compact-empty')
    expect(empty.textContent).toBe('No answer — no records returned')
    expect(empty).toHaveAttribute('data-failed', 'true')
    expect(empty.className).toMatch(/text-destructive/)
    failed.unmount()

    const fresh = renderCard({ value: null, checked_at: null, response_ms: null }, { density: 'compact' })
    expect(slot(fresh.card, 'dns-compact-empty').textContent).toBe('Awaiting the first check')
    expect(slot(fresh.card, 'dns-compact-reason')).toBeNull()
  })

  it.each([
    [true, 'standalone', 'Standalone'],
    [false, 'inventory', 'From inventory'],
  ])('kaynak rozeti İKİ görünümde de (standalone=%s → %s)', (standalone, source, text) => {
    for (const density of ['compact', 'rich']) {
      const { card, unmount } = renderCard({ standalone }, { density })
      expect(slot(card, 'dns-source'), density).toHaveAttribute('data-source', source)
      expect(slot(card, 'dns-source').textContent).toBe(text)
      unmount()
    }
  })

  it('Kompakt: duraklatılmış kart Sürdür\'ü ve "Paused" rozetini korur; MX değeri önceliği ayrı gösterir', () => {
    const onResume = vi.fn()
    const paused = renderCard({ active: false }, {
      density: 'compact',
      actions: <MonitorCardActions rowLabel="www.example.com" onResume={onResume} onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} checkTitle="Check now" editTitle="Edit" />,
    })
    expect(paused.card).toHaveAttribute('data-inactive', 'true')
    expect(slot(paused.card, 'monitor-paused').textContent).toMatch(/Paused/)
    fireEvent.click(within(paused.card).getByRole('button', { name: 'www.example.com — Resume' }))
    expect(onResume).toHaveBeenCalledTimes(1)
    paused.unmount()

    const mx = renderCard({ record_type: 'MX', value: '10 mx1.example.com.' }, { density: 'compact' })
    expect(slot(mx.card, 'dns-compact-value').textContent).toBe('10mx1.example.com.')
  })
})
