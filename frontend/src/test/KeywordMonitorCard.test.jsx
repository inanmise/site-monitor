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
      maintenance: { active: vi.fn(() => Promise.resolve({ success: true, data: { all: false, targets: ['https://maint.example.com/'] } })) },
    },
  }),
}))

import KeywordMonitorCard from '../components/keyword/KeywordMonitorCard.jsx'
import MonitorCardActions from '../components/MonitorCardActions.jsx'
import MonitorCardMeta from '../components/MonitorCardMeta.jsx'
import { CARD_CHECK } from '../components/monitoring/MonitorCard.jsx'
import {
  LONG_KEYWORD, alertTrigger, failureReason, highlightParts, httpTone, metaRow, msText, proxyMode, responseAssessment,
  ruleLabel, ruleOf, verdictOf,
} from '../components/keyword/keywordCardModel.js'

const stamp = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const URL_ = 'https://www.example.com/'

const base = {
  id: 9, name: 'Corporate site', url: URL_, keyword: 'Welcome back', operator: 'GTE', match_count: 1, case_sensitive: false,
  status: 'up', found: true, occurrences: 3, ok: true, http_status: 200, response_ms: 212, error: null,
  snippet: '<h1 class="hero">Welcome back — sign in</h1>', active: true, team_id: 1, team_name: 'Takım A',
  group_name: 'Corporate Web', tags: 'prod', interval_seconds: 300, timeout_ms: 10000, use_proxy: 'AUTO',
  proxy_effective: 'direct', proxy_source: 'none', proxy_bypassed: false, slow_response_enabled: false, slow_threshold_ms: 3000,
  checked_at: stamp(5 * 60_000),
}

const statusKey = (m) => (m.status === 'up' ? 'up' : m.status === 'unknown' ? 'unknown' : 'down')
const cardOf = (c) => c.querySelector('[data-slot="card"]')
const slot = (root, name) => root.querySelector(`[data-slot="${name}"]`)
const tile = (c, metric) => c.querySelector(`[data-slot="keyword-metric"][data-metric="${metric}"]`)

function renderCard(monitor = {}, props = {}) {
  const m = { ...base, ...monitor }
  return render(<KeywordMonitorCard monitor={m} status={statusKey(m)} onOpen={props.onOpen || (() => {})}
    meta={<MonitorCardMeta monitor={metaRow(m)} />} {...props} />)
}

describe('keywordCardModel — saf yardımcılar', () => {
  const t = (k, ...a) => `${k}(${a.join(',')})`

  it('kural türü: içermeli (GTE 1 / GT 0), içermemeli (LTE 0 / EQ 0 / LT 1), diğerleri adet; rozet metni tekili ayırır', () => {
    expect([['GTE', 1], ['GT', 0], ['LTE', 0], ['EQ', 0], ['LT', 1], ['GTE', 10], ['EQ', 1], ['GTE', 0], ['LT', 0]]
      .map(([operator, match_count]) => ruleOf({ operator, match_count }).kind))
      .toEqual(['contains', 'contains', 'absent', 'absent', 'absent', 'count', 'count', 'count', 'count'])
    expect(ruleOf({ operator: 'bogus', match_count: null })).toEqual({ kind: 'contains', op: 'GTE', n: 1 })
    expect(ruleLabel(ruleOf({ operator: 'GTE', match_count: 10 }), t)).toBe('keyword.card.rule.GTE(10)')
    expect(ruleLabel(ruleOf({ operator: 'EQ', match_count: 1 }), t)).toBe('keyword.card.rule1.EQ()')
    expect(ruleLabel(ruleOf({ operator: 'LTE', match_count: 0 }), t)).toBe('keyword.card.ruleAbsent()')
    expect(alertTrigger(ruleOf({ operator: 'LTE', match_count: 0 }), ' 503 ', t)).toBe('keyword.trig.LTE0(« 503 »)')
    expect(alertTrigger(ruleOf({ operator: 'GTE', match_count: 3 }), '', t)).toBe('keyword.trig.GTE(keyword.theKeyword(),3)')
  })

  it('hüküm: sunucunun durumu (up/down) kurala göre adlandırılır; error ve bekleyen ayrı; sayı occurrences', () => {
    const v = (m) => { const r = verdictOf(m); return `${r.kind}/${r.tone}/${r.count}` }
    expect(v({ status: 'up', occurrences: 3 })).toBe('found/ok/3')
    expect(v({ status: 'down', occurrences: 0 })).toBe('missing/bad/0')
    expect(v({ status: 'up', operator: 'LTE', match_count: 0, occurrences: 0 })).toBe('absent/ok/0')
    expect(v({ status: 'down', operator: 'LTE', match_count: 0, occurrences: 2 })).toBe('forbidden/bad/2')
    expect(v({ status: 'down', operator: 'GTE', match_count: 10, occurrences: 4 })).toBe('countFail/bad/4')
    expect(v({ status: 'up', operator: 'EQ', match_count: 1, occurrences: 1 })).toBe('countOk/ok/1')
    expect(v({ status: 'error', error: 'x' })).toBe('error/bad/null')
    expect(v({ status: 'unknown' })).toBe('pending/neutral/null')
  })

  it('neden: zaman aşımı / DNS / TLS / reddedildi / SSRF / yapılandırma / ham hata / HTTP 4xx-5xx; sağlıklıda yok', () => {
    const kind = (m) => failureReason(m)?.kind ?? null
    expect(kind({ status: 'up', http_status: 500 })).toBeNull()
    expect(failureReason({ status: 'error', error: 'HTTP connect timed out', timeout_ms: 10000 })).toEqual({ kind: 'timeout', detail: 10000 })
    expect(kind({ status: 'error', error: 'Keyword yanıt gövdesi 10000 ms içinde tamamlanmadı — akış kesildi' })).toBe('timeout')
    expect(kind({ status: 'error', error: 'çözümlenemeyen host: x.example.com' })).toBe('dns')
    expect(kind({ status: 'error', error: 'java.net.UnknownHostException: x.example.com' })).toBe('dns')
    expect(kind({ status: 'error', error: 'PKIX path building failed: unable to find valid certification path' })).toBe('tls')
    expect(kind({ status: 'error', error: 'java.net.ConnectException: Connection refused' })).toBe('refused')
    expect(kind({ status: 'error', error: 'izin verilmeyen hedef x → 127.0.0.1 (loopback)' })).toBe('blocked')
    expect(kind({ status: 'error', error: "yapılandırma hatası: URL'de geçerli bir host yok (şema eksik veya bozuk)" })).toBe('config')
    expect(failureReason({ status: 'error', error: 'garip\nikinci' })).toEqual({ kind: 'error', detail: 'garip' })
    expect(failureReason({ status: 'down', http_status: 503 })).toEqual({ kind: 'http5xx', detail: 503 })
    expect(kind({ status: 'down', http_status: 404 })).toBe('http4xx')
    expect(kind({ status: 'down', http_status: 200 })).toBeNull()   // yalnız kural sağlanmadı — hüküm satırı söylüyor
  })

  it('HTTP tonu sınıftan; süre tonu yalnız yavaşlık eşiği açıkken; zaman aşımı bad; süre metni ms / s', () => {
    expect([200, 301, 404, 503, null].map((c) => httpTone(c))).toEqual(['ok', 'neutral', 'warn', 'bad', 'neutral'])
    expect(httpTone(null, true)).toBe('bad')
    expect(responseAssessment({ response_ms: 3480, slow_response_enabled: true, slow_threshold_ms: 3000 })).toMatchObject({ tone: 'warn', limit: 3000 })
    expect(responseAssessment({ response_ms: 212, slow_response_enabled: true, slow_threshold_ms: 3000 }).tone).toBe('ok')
    expect(responseAssessment({ response_ms: 9000, slow_response_enabled: false }).tone).toBe('neutral')
    expect(responseAssessment({ status: 'error', error: 'timed out', response_ms: 10004 })).toMatchObject({ tone: 'bad', timedOut: true })
    expect([212, 999.6, 1890, 10004, 3000, null].map(msText)).toEqual(['212 ms', '1 s', '1.9 s', '10 s', '3 s', null])
  })

  it('vekil kipi yalnız ON/OFF; ON iken doğrudan çıkıldıysa atlandı; kip zorlanmışsa meta yol rozeti düşer', () => {
    expect(proxyMode({ use_proxy: 'AUTO', proxy_effective: 'proxy' })).toBeNull()
    expect(proxyMode({ use_proxy: 'ON', proxy_effective: 'proxy' })).toEqual({ mode: 'ON', bypassed: false })
    expect(proxyMode({ use_proxy: 'ON', proxy_effective: 'direct', proxy_bypassed: true })).toEqual({ mode: 'ON', bypassed: true })
    expect(proxyMode({ use_proxy: 'off' })).toEqual({ mode: 'OFF', bypassed: false })
    expect(metaRow({ use_proxy: 'ON', proxy_effective: 'proxy' }).proxy_effective).toBeNull()
    const auto = { use_proxy: 'AUTO', proxy_effective: 'proxy' }
    expect(metaRow(auto)).toBe(auto)
  })

  it('vurgu parçaları: tüm geçişler, harf duyarsız (varsayılan) / duyarlı, boşluk indirgenir, özel karakter kaçışlı', () => {
    expect(highlightParts('a Foo b foo', 'foo')).toEqual([
      { text: 'a ', match: false }, { text: 'Foo', match: true }, { text: ' b ', match: false }, { text: 'foo', match: true }])
    expect(highlightParts('a Foo b foo', 'foo', true).filter((p) => p.match).map((p) => p.text)).toEqual(['foo'])
    expect(highlightParts('x "status":"UP" y', '"status":"UP"').find((p) => p.match).text).toBe('"status":"UP"')
    expect(highlightParts('Welcome back home', 'Welcome\n  back').find((p) => p.match).text).toBe('Welcome back')
    expect(highlightParts('a (b) c', '(b)').find((p) => p.match).text).toBe('(b)')
  })
})

describe('KeywordMonitorCard', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('Bulundu: yeşil panel, "Must contain" rozeti, « » içinde anahtar kelime, eşleşme sayısı, ilk eşleşme <mark> ile vurgulu', () => {
    const { container } = renderCard()
    const panel = slot(container, 'keyword-panel')
    expect(panel).toHaveAttribute('data-result', 'found')
    expect(panel).toHaveAttribute('data-tone', 'ok')
    const rule = slot(panel, 'keyword-rule')
    expect(rule).toHaveAttribute('data-rule', 'contains')
    expect(rule).toHaveAttribute('data-slot', 'keyword-rule')
    expect(rule.textContent).toBe('Must contain')
    expect(slot(panel, 'keyword-chip').textContent).toBe('«Welcome back»')
    expect(slot(panel, 'keyword-result').textContent).toMatch(/^Found3 matches$/)
    const snippet = slot(panel, 'keyword-snippet')
    expect(snippet.textContent).toMatch(/First match/)
    expect([...snippet.querySelectorAll('mark')].map((mk) => mk.textContent)).toEqual(['Welcome back'])
    expect(slot(panel, 'keyword-reason')).toBeNull()
    expect(cardOf(container).className).not.toMatch(/border-destructive/)
  })

  it('Bulunamadı: kırmızı panel "Not found", sayı/eşleşme çevresi yok; kapalı kart TÜM kenarı kırmızı — sol şerit YOK', () => {
    const { container } = renderCard({ status: 'down', found: false, occurrences: 0, ok: false, snippet: null })
    const panel = slot(container, 'keyword-panel')
    expect(panel).toHaveAttribute('data-result', 'missing')
    expect(panel).toHaveAttribute('data-tone', 'bad')
    expect(slot(panel, 'keyword-result').textContent).toBe('Not found')
    expect(slot(panel, 'keyword-count')).toBeNull()
    expect(slot(panel, 'keyword-snippet')).toBeNull()
    const card = cardOf(container)
    expect(card).toHaveAttribute('data-status', 'down')
    expect(card.className).toMatch(/(^|\s)border-destructive\/45(\s|$)/)
    expect(card.className).not.toMatch(/border-l-|before:/)
  })

  it('İçermemeli + bulundu: "Must NOT contain" rozeti, "Found — not allowed", kanıt kırmızı vurgulu; alarm tetik cümlesi dokun-gör balonunda', async () => {
    const { container } = renderCard({
      keyword: 'Service Unavailable', operator: 'LTE', match_count: 0, status: 'down', ok: false, occurrences: 2,
      snippet: '<title>503 Service Unavailable</title> <body>service unavailable</body>',
    })
    const panel = slot(container, 'keyword-panel')
    expect(panel).toHaveAttribute('data-result', 'forbidden')
    expect(slot(panel, 'keyword-rule')).toHaveAttribute('data-rule', 'absent')
    expect(slot(panel, 'keyword-rule').textContent).toBe('Must NOT contain')
    expect(slot(panel, 'keyword-result').textContent).toBe('Found — not allowed2 matches')
    const marks = [...slot(panel, 'keyword-snippet').querySelectorAll('mark')]
    expect(marks.map((mk) => mk.textContent)).toEqual(['Service Unavailable', 'service unavailable'])   // harf duyarsız
    expect(marks[0].className).toMatch(/text-destructive/)
    fireEvent.click(screen.getByRole('button', { name: `${URL_} — Must NOT contain` }))
    expect((await screen.findByRole('tooltip')).textContent).toBe('Raises an alert if « Service Unavailable » appears on the page.')
  })

  it('adet kuralı: "At least 10 times" + "Condition not met 4 matches"; tekil "Exactly once"; harf duyarlı rozeti + açıklaması', async () => {
    const fail = renderCard({ operator: 'GTE', match_count: 10, status: 'down', ok: false, occurrences: 4, snippet: null })
    expect(slot(fail.container, 'keyword-rule').textContent).toBe('At least 10 times')
    expect(slot(fail.container, 'keyword-result').textContent).toBe('Condition not met4 matches')
    fail.unmount()
    const once = renderCard({ operator: 'EQ', match_count: 1, occurrences: 1, case_sensitive: true, snippet: 'a welcome back b Welcome back' })
    expect(slot(once.container, 'keyword-rule').textContent).toBe('Exactly once')
    expect(slot(once.container, 'keyword-result').textContent).toBe('Condition met1 match')
    // Harf duyarlı kuralda vurgu da duyarlı: yalnız "Welcome back"
    expect([...once.container.querySelectorAll('[data-slot="keyword-snippet"] mark')].map((mk) => mk.textContent)).toEqual(['Welcome back'])
    expect(slot(once.container, 'keyword-case').textContent).toBe('Case-sensitive')
    fireEvent.click(screen.getByRole('button', { name: `${URL_} — Case-sensitive` }))
    expect((await screen.findByRole('tooltip')).textContent).toMatch(/Upper and lower case must match exactly/)
  })

  it('uzun anahtar kelime iki satırda kırpılır, tamamı dokun-gör; kısa kelimede tetik yok. Uzun eşleşme çevresi de dokun-gör', async () => {
    const long = 'Hoş geldiniz — oturum açma sayfası yüklendi ve form gönderime hazır durumda'
    expect(long.length).toBeGreaterThan(LONG_KEYWORD)
    const { container } = renderCard({ keyword: long, snippet: `<div class="banner"> ${long} </div> <footer>` })
    const chip = slot(container, 'keyword-chip')
    expect(chip.querySelector('.line-clamp-2')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: `${URL_} — Show the full keyword` }))
    expect((await screen.findByRole('tooltip')).textContent).toBe(long)
    fireEvent.keyDown(document.activeElement || document.body, { key: 'Escape' })
    fireEvent.click(screen.getByRole('button', { name: `${URL_} — Show the match in context` }))
    await waitFor(() => expect(screen.getByRole('tooltip').querySelector('mark').textContent).toBe(long))
  })

  it('kısa anahtar kelime ve kısa çevre: dokun-gör tetiği YOK (örtünün altında, tıklamak detayı açar)', () => {
    const { container } = renderCard({ keyword: 'OK', snippet: '"state":"OK"' })
    expect(slot(container, 'keyword-chip').closest('[data-slot="hint-trigger"]')).toBeNull()
    expect(slot(container, 'keyword-snippet').querySelector('[data-slot="hint-trigger"]')).toBeNull()
  })

  it('ölçü kutuları: HTTP sınıf tonu + alt satır; yavaşlık eşiği aşılınca "Slow" rozeti ve sınır; eşik kapalıysa nötr', () => {
    const slow = renderCard({ response_ms: 3480, slow_response_enabled: true, slow_threshold_ms: 3000 })
    expect(tile(slow.container, 'http')).toHaveAttribute('data-tone', 'ok')
    expect(tile(slow.container, 'http').textContent).toBe('HTTP status200Success')
    const resp = tile(slow.container, 'response')
    expect(resp).toHaveAttribute('data-tone', 'warn')
    expect(resp.querySelector('[data-slot="keyword-metric-value"]').textContent).toBe('3.5s')
    expect(within(resp).getByText('Slow')).toHaveAttribute('data-slot', 'keyword-metric-verdict')
    expect(resp.querySelector('[data-slot="keyword-metric-sub"]').textContent).toBe('Limit 3 s')
    slow.unmount()
    const ok = renderCard({ response_ms: 212, slow_response_enabled: true, slow_threshold_ms: 3000 })
    expect(tile(ok.container, 'response')).toHaveAttribute('data-tone', 'ok')
    expect(tile(ok.container, 'response').textContent).toMatch(/Under the 3 s limit/)
    ok.unmount()
    const neutral = renderCard({ response_ms: 9000 })
    expect(tile(neutral.container, 'response')).toHaveAttribute('data-tone', 'neutral')
    expect(tile(neutral.container, 'response').querySelector('[data-slot="keyword-metric-sub"]')).toBeNull()
  })

  it('HTTP 500 + kelime yok: panelde "Not found" + NEDEN satırı; HTTP kutusu kırmızı "Server error"', () => {
    const { container } = renderCard({ status: 'down', http_status: 500, found: false, occurrences: 0, snippet: null })
    const reason = slot(container, 'keyword-reason')
    expect(reason).toHaveAttribute('data-reason', 'http5xx')
    expect(reason.textContent).toBe('The server returned HTTP 500 (server error)')
    expect(tile(container, 'http')).toHaveAttribute('data-tone', 'bad')
    expect(tile(container, 'http').textContent).toMatch(/500Server error$/)
  })

  it('zaman aşımı: panel "Couldn’t read the page" + neden (süreyle); HTTP "No response", süre kutusu "Timed out"', () => {
    const { container } = renderCard({ status: 'error', error: 'HTTP connect timed out', http_status: null, response_ms: 10004,
      found: false, occurrences: 0, ok: false, snippet: null })
    const panel = slot(container, 'keyword-panel')
    expect(panel).toHaveAttribute('data-result', 'error')
    expect(slot(panel, 'keyword-result').textContent).toMatch(/^Couldn’t read the page$/)
    expect(slot(panel, 'keyword-reason')).toHaveAttribute('data-reason', 'timeout')
    expect(slot(panel, 'keyword-reason').textContent).toBe('The request timed out after 10 s')
    expect(tile(container, 'http').textContent).toBe('HTTP status—No response')
    expect(tile(container, 'http')).toHaveAttribute('data-tone', 'bad')
    expect(tile(container, 'response')).toHaveAttribute('data-tone', 'bad')
    expect(tile(container, 'response').textContent).toMatch(/Timed out$/)
    expect(cardOf(container)).toHaveAttribute('data-status', 'down')
  })

  it('hiç kontrol edilmemiş: nötr (kesik) panel "Not checked yet", ölçü kutusu yok, alt çubukta "Not checked yet"', () => {
    const { container } = renderCard({ status: 'unknown', found: null, occurrences: null, ok: null, http_status: null,
      response_ms: null, snippet: null, checked_at: null })
    const panel = slot(container, 'keyword-panel')
    expect(panel).toHaveAttribute('data-result', 'pending')
    expect(panel.className).toMatch(/border-dashed/)
    expect(slot(panel, 'keyword-result').textContent).toBe('Waiting for the first check')   // alt çubuğun metnini tekrar etmez
    expect(tile(container, 'http')).toBeNull()
    expect(container.querySelector('[data-slot="card-footer"]').textContent).toMatch(/Not checked yet/)
  })

  it('vekil kipi ON: "Always via proxy" çipi, yol rozeti tekrar ÇİZİLMEZ; atlandıysa amber; AUTO: çip yok, yol rozeti var', async () => {
    const on = renderCard({ use_proxy: 'ON', proxy_effective: 'proxy', proxy_source: 'monitor' })
    const chip = slot(on.container, 'keyword-proxy')
    expect(chip).toHaveAttribute('data-mode', 'ON')
    expect(chip.textContent).toBe('Always via proxy')
    expect(slot(on.container, 'meta-proxy')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: `${URL_} — Always via proxy` }))
    expect((await screen.findByRole('tooltip')).textContent).toMatch(/corporate proxy/)
    on.unmount()
    const bypass = renderCard({ use_proxy: 'ON', proxy_effective: 'direct', proxy_bypassed: true })
    expect(slot(bypass.container, 'keyword-proxy')).toHaveAttribute('data-bypassed', 'true')
    expect(slot(bypass.container, 'keyword-proxy').textContent).toBe('Proxy bypassed')
    bypass.unmount()
    const off = renderCard({ use_proxy: 'OFF' })
    expect(slot(off.container, 'keyword-proxy').textContent).toBe('Always direct')
    off.unmount()
    const auto = renderCard({ use_proxy: 'AUTO', proxy_effective: 'proxy' })
    expect(slot(auto.container, 'keyword-proxy')).toBeNull()
    expect(slot(auto.container, 'meta-proxy')).not.toBeNull()
  })

  it('duraklatılmış: kesik/soluk kart + "Paused" + Sürdür; alarm: seviye rozeti + tüm kart dış çizgisi (çift ton yok); bakım rozeti', async () => {
    const onResume = vi.fn()
    const paused = renderCard({ active: false }, {
      actions: <MonitorCardActions rowLabel={URL_} onResume={onResume} onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} checkTitle="Check now" editTitle="Edit" />,
    })
    expect(cardOf(paused.container)).toHaveAttribute('data-inactive', 'true')
    expect(paused.container.querySelector('[data-slot="card-footer"] [data-slot="monitor-paused"]')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: `${URL_} — Resume` }))
    expect(onResume).toHaveBeenCalledTimes(1)
    paused.unmount()

    const alarm = renderCard({ status: 'down', occurrences: 0, snippet: null, active_alarm: true, alarm_level: 'CRITICAL', alarm_acknowledged: false })
    const card = cardOf(alarm.container)
    expect(card).toHaveAttribute('data-alarm', 'true')
    expect(card.querySelector('[data-slot="monitor-alarm"]')).toHaveAttribute('data-level', 'CRITICAL')
    expect(card.className).not.toMatch(/border-destructive\/45/)
    alarm.unmount()

    const maint = renderCard({ url: 'https://maint.example.com/' })
    await waitFor(() => expect(maint.container.querySelector('[data-slot="maintenance-badge"]')).not.toBeNull())
  })

  it('başlık GERÇEK düğme ve detayı açar; URL kopyala / bağlantı kopyala / seçim kutusu / eylemler detayı AÇMAZ; adlar satırı taşır', async () => {
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
    // https:// ve kök yol "/" gizli, host vurgulu; tam URL başlığın title'ında
    expect(title.textContent).toBe('www.example.com')
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
    // meta + etiketler + ad + sıklık
    expect(slot(container, 'meta-team')).not.toBeNull()
    expect(slot(container, 'keyword-name').textContent).toBe('Corporate site')
    expect(slot(container, 'keyword-interval').textContent).toBe('every 5 min')
    expect(container.querySelector('[data-slot="ping-tags"]').textContent).toMatch(/prod/)
  })

  it('telefon: ölçüler iki sütun, kopyala 40 px dokunma, uzun URL/kelime kırpılır (min-w-0 + break-all), düz http şeması görünür', () => {
    const { container } = renderCard({
      url: 'http://odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com/giris/kullanici?lang=tr&utm_source=kampanya',
    })
    expect(slot(container, 'monitor-metrics').className).toMatch(/(^|\s)grid-cols-2(\s|$)/)
    expect(screen.getByRole('button', { name: /— Copy URL$/ }).className).toContain('pointer-coarse:size-10')
    const title = container.querySelector('[data-monitor-open]')
    expect(title.querySelector('.truncate')).not.toBeNull()
    expect(title.textContent.startsWith('http://')).toBe(true)
    expect(slot(container, 'keyword-chip').className).toMatch(/max-w-full/)
    expect(slot(container, 'keyword-chip').querySelector('.break-all')).not.toBeNull()
  })
})

/**
 * KART YOĞUNLUĞU (2026-09-27): Kompakt = durum satırı · URL · TEK ikincil satır (ad + takım) · TEK sonuç satırı
 * (Bulundu/Bulunamadı … · « anahtar kelime » · yanıt süresi) · düşükse TEK satır neden · alt çubuk. Kural paneli,
 * ölçü kutuları, trend/SLA, grup/vekil/etiket yalnız Zengin'de — Kompakt'ta DOM'a HİÇ girmez.
 */
describe('KeywordMonitorCard — Kompakt / Zengin', () => {
  beforeEach(() => { vi.clearAllMocks() })
  const RICH_ONLY = ['keyword-panel', 'keyword-rule', 'keyword-chip', 'keyword-snippet', 'keyword-count', 'keyword-metric',
    'monitor-spark', 'monitor-card-meta', 'meta-group', 'ping-tags']
  const spark = { n: 180, fail: 0, up_pct: 100, last: [], buckets: [200, 210, 220].map((ms, h) => ({ t: `2026-09-27T0${h}`, n: 60, fail: 0, ms })) }

  it('Kompakt: Zengin-özel bölümler YOK; durum, URL, ad + YALNIZ takım, "✓ Found « Welcome back » 212 ms", zaman ve AYNI eylemler', () => {
    const onSel = vi.fn()
    const { container } = renderCard({}, {
      density: 'compact', spark,
      select: <Checkbox className={CARD_CHECK} checked={false} onCheckedChange={onSel} aria-label={`Select ${URL_} for bulk action`} />,
      actions: <MonitorCardActions rowLabel={URL_} onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} onDelete={() => {}} checkTitle="Check now" editTitle="Edit" deleteTitle="Delete" />,
    })
    expect(cardOf(container)).toHaveAttribute('data-density', 'compact')
    expect(slot(container, 'monitor-card-rich')).toBeNull()
    for (const s of RICH_ONLY) expect(slot(container, s), s).toBeNull()
    const sub = slot(container, 'card-compact-sub')
    expect(slot(sub, 'keyword-name').textContent).toBe('Corporate site')
    expect(slot(sub, 'meta-team').textContent).toMatch(/Takım A/)
    const row = slot(container, 'keyword-compact')
    expect(row).toHaveAttribute('data-result', 'found')
    expect(row).toHaveAttribute('data-tone', 'ok')
    expect(slot(row, 'keyword-result').textContent).toBe('Found')
    expect(slot(row, 'keyword-compact-kw').textContent).toBe('«Welcome back»')
    expect(slot(row, 'keyword-compact-kw')).toHaveAttribute('title', 'Welcome back')
    expect(row.querySelector('[data-slot="compact-value"][data-metric="response"]').textContent).toBe('212ms')
    expect(slot(container, 'keyword-reason')).toBeNull()
    expect(slot(container, 'ping-checked-at').textContent).toMatch(/^5 min ago/)
    for (const name of [/— Check now$/, /— Edit$/, /— (Duplicate|Kopyala)$/, /— Delete$/, /— Copy URL$/, /— Copy link$/]) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument()
    }
    fireEvent.click(screen.getByRole('checkbox', { name: `Select ${URL_} for bulk action` }))
    expect(onSel).toHaveBeenCalled()
  })

  it('Zengin (varsayılan): kural paneli, ilk eşleşme, ölçü kutuları, trend, meta + etiketler; kompakt parçalar YOK', () => {
    const { container } = renderCard({}, { spark })
    expect(cardOf(container)).toHaveAttribute('data-density', 'rich')
    for (const s of RICH_ONLY) expect(slot(container, s), s).not.toBeNull()
    expect(slot(container, 'card-compact-sub')).toBeNull()
    expect(slot(container, 'keyword-compact')).toBeNull()
  })

  it('Kompakt Bulunamadı / yasak kelime / yavaş: sonuç tonu kırmızı; eşik aşıldıysa amber süre + "Slow"; neden yoksa satır yok', () => {
    const missing = renderCard({ status: 'down', found: false, occurrences: 0, snippet: null }, { density: 'compact' })
    const row = missing.container.querySelector('[data-slot="keyword-compact"]')
    expect(row).toHaveAttribute('data-result', 'missing')
    expect(row).toHaveAttribute('data-tone', 'bad')
    expect(row.querySelector('[data-slot="keyword-result"]').textContent).toBe('Not found')
    expect(missing.container.querySelector('[data-slot="keyword-reason"]')).toBeNull()   // "Not found" zaten söylüyor
    missing.unmount()
    const forbidden = renderCard({ operator: 'LTE', match_count: 0, keyword: '503', status: 'down', occurrences: 2 }, { density: 'compact' })
    expect(forbidden.container.querySelector('[data-slot="keyword-result"]').textContent).toBe('Found — not allowed')
    forbidden.unmount()
    const slow = renderCard({ response_ms: 4200, slow_response_enabled: true, slow_threshold_ms: 3000 }, { density: 'compact' })
    const v = slow.container.querySelector('[data-slot="compact-value"][data-metric="response"]')
    expect(v).toHaveAttribute('data-tone', 'warn')
    expect(v.textContent).toBe('4.2s')
    expect(slow.container.querySelector('[data-slot="compact-verdict"]').textContent).toBe('Slow')
  })

  it('Kompakt zaman aşımı: "Couldn’t read the page" + TEK satır neden (kırpılır), tamamı dokun-gör balonunda; süre yazılmaz; detayı AÇMAZ', async () => {
    const onOpen = vi.fn()
    const { container } = renderCard({ status: 'error', found: false, occurrences: null, http_status: null, response_ms: 10000, snippet: null,
      error: 'HTTP request timed out' }, { density: 'compact', onOpen })
    expect(slot(container, 'keyword-result').textContent).toBe('Couldn’t read the page')
    expect(container.querySelector('[data-slot="compact-value"][data-metric="response"]')).toBeNull()
    const reason = slot(container, 'keyword-reason')
    expect(reason).toHaveAttribute('data-compact', 'true')
    expect(reason).toHaveAttribute('data-reason', 'timeout')
    expect(reason.querySelector('.truncate')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: `${URL_} — The request timed out after 10 s` }))
    expect((await screen.findByRole('tooltip')).textContent).toBe('The request timed out after 10 s')
    expect(onOpen).not.toHaveBeenCalled()
  })

  it('Kompakt bekleyen: soluk "Waiting for the first check", süre yok; ad yoksa ikincil satırda yalnız takım', () => {
    const { container } = renderCard({ name: '', status: 'unknown', found: null, occurrences: null, http_status: null, response_ms: null,
      snippet: null, checked_at: null }, { density: 'compact' })
    const row = slot(container, 'keyword-compact')
    expect(row).toHaveAttribute('data-tone', 'neutral')
    expect(slot(row, 'keyword-result').textContent).toBe('Waiting for the first check')
    expect(row.querySelector('[data-slot="compact-value"]')).toBeNull()
    expect(slot(container, 'keyword-name')).toBeNull()
    expect(container.querySelector('[data-slot="card-compact-sub"] [data-slot="meta-team"]')).not.toBeNull()
  })
})
