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
      // Bakım rozeti modül önbellekli tek istek atar — dosya boyunca aynı bakım hedefi (sentetikte hedef = izleme adı).
      maintenance: { active: vi.fn(() => Promise.resolve({ success: true, data: { all: false, targets: ['Nightly export'] } })) },
    },
  }),
}))

import ScriptedMonitorCard from '../components/scripted/ScriptedMonitorCard.jsx'
import MonitorCardActions from '../components/MonitorCardActions.jsx'
import { CARD_CHECK, MonitorStatusBadge } from '../components/monitoring/MonitorCard.jsx'
import {
  checkCounts, durationAssessment, errorGist, failedCheckNames, humanizeMs, isBrowserScript, runFailure, scenarioTarget, versionDrift,
} from '../components/scripted/scriptedCardModel.js'

const stamp = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const k6 = (url) => `import http from 'k6/http'\nimport { check } from 'k6'\nexport default function () {\n  check(http.get('${url}'), { 'ok': (r) => r.status === 200 })\n}`
const checksJson = (rows) => JSON.stringify(rows.map(([name, passed]) => ({ name, passed })))

const base = {
  id: 7, name: 'Portal login', script: k6('https://portal.example.com/login'), env: [], status: 'PASS', active: true,
  interval_seconds: 300, timeout_seconds: 60, script_version: '7', run_script_version: '7', checks_passed: 14, checks_failed: 0,
  duration_ms: 2380, http_req_avg_ms: 164, exit_code: 0, error: null, checks_json: null, phases: null,
  slow_response_enabled: false, slow_threshold_ms: 15000, team_id: 1, team_name: 'Takım A', group_name: 'Kurumsal Web',
  tags: 'prod', never_succeeded: false, disabled_reason: null, checked_at: stamp(5 * 60_000),
}

// Sayfanın durum sözlüğü (ScriptedMonitorPage.statusKey) — kart bunu yuva olarak alır.
const statusOf = (s) => (s === 'PASS' ? 'up' : ['FAIL', 'ERROR', 'TIMEOUT'].includes(s) ? 'down' : s === 'NO_CHECKS' ? 'warn' : 'unknown')

const cardOf = (container) => container.querySelector('[data-slot="card"]')
const result = (container) => container.querySelector('[data-slot="scripted-result"]')
const tile = (container, metric) => container.querySelector(`[data-slot="scripted-metric"][data-metric="${metric}"]`)

function renderCard(monitor = {}, props = {}) {
  const m = { ...base, ...monitor }
  const status = statusOf(m.status)
  return render(<ScriptedMonitorCard monitor={m} status={status} badge={<MonitorStatusBadge status={status}>{m.status}</MonitorStatusBadge>}
    onOpen={props.onOpen || (() => {})} {...props} />)
}

describe('scriptedCardModel — saf yardımcılar', () => {
  it('hedef: gizli OLMAYAN ortam değişkeni önce; sonra script adresleri (import satırı ve kütüphane CDN hariç); farklı host sayısı; yoksa null', () => {
    expect(scenarioTarget({ env: [{ name: 'PASSWORD', secret: true, value_set: true }, { name: 'BASE_URL', secret: false, value: 'https://app.example.com' }],
      script: k6('https://api.example.net/x') })).toEqual({ url: 'https://app.example.com', host: 'app.example.com', hosts: 2 })
    const lib = "import { randomItem } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js'\n// https://yorum.example.org/\n" + k6('https://shop.example.com/cart?x=1')
    expect(scenarioTarget({ env: [], script: lib })).toEqual({ url: 'https://shop.example.com/cart?x=1', host: 'shop.example.com', hosts: 1 })
    expect(scenarioTarget({ env: [{ name: 'X', secret: true, value: 'https://gizli.example.com' }], script: 'export default function () {}' })).toBeNull()
    expect(scenarioTarget({})).toBeNull()
  })

  it('tarayıcı senaryosu, sayaçlar, düşen check adları, hata özü', () => {
    expect([isBrowserScript("import { browser } from 'k6/browser'"), isBrowserScript("import { browser } from 'k6/experimental/browser'"), isBrowserScript(k6('https://a.example.com'))])
      .toEqual([true, true, false])
    expect(checkCounts({ checks_passed: 12, checks_failed: 2 })).toEqual({ passed: 12, failed: 2, total: 14 })
    expect(checkCounts({ checks_passed: null, checks_failed: null })).toBeNull()
    expect(failedCheckNames({ checks_json: checksJson([['a', true], ['b', false], ['c', false]]) })).toEqual(['b', 'c'])
    expect(failedCheckNames({ checks_json: '{bozuk' })).toEqual([])
    expect(errorGist('script çalışırken hata verdi:\nTypeError: x is undefined\nat default')).toBe('TypeError: x is undefined')
    expect(errorGist('k6 check/threshold başarısız')).toBe('k6 check/threshold başarısız')
    expect(errorGist(null)).toBeNull()
  })

  it('başarısızlık nedeni: check adı > zaman aşımı > eşik (99) > hata özü; geçen/koşmayan/NO_CHECKS için null', () => {
    expect(runFailure({ status: 'PASS' })).toBeNull()
    expect(runFailure({ status: 'NO_CHECKS', error: 'x' })).toBeNull()
    expect(runFailure({ status: 'FAIL', checks_json: checksJson([['login', false], ['token', false]]), exit_code: 99 })).toEqual({ kind: 'check', name: 'login', more: 1 })
    expect(runFailure({ status: 'TIMEOUT', timeout_seconds: 60, error: 'Süre aşımı:\nRequest Failed — timeout' })).toEqual({ kind: 'timeout', limit: 60, detail: 'Request Failed — timeout' })
    expect(runFailure({ status: 'FAIL', exit_code: 99, error: 'k6 check/threshold başarısız — threshold eşiği aşıldı' })).toEqual({ kind: 'threshold', detail: null })
    expect(runFailure({ status: 'ERROR', exit_code: 107, error: 'hata:\nTypeError: y' })).toEqual({ kind: 'error', detail: 'TypeError: y' })
    expect(runFailure({ status: 'FAIL', exit_code: 0, error: 'k6 check/threshold başarısız' })).toEqual({ kind: 'fail', detail: null })
  })

  it('süre biçimi + tonu (eşik YALNIZ yavaş koşum alarmı açıkken; zaman aşımı kırmızı), sürüm kayması', () => {
    expect([humanizeMs(820), humanizeMs(2380), humanizeMs(2000), humanizeMs(999.6), humanizeMs(null)])
      .toEqual([{ num: '820', unit: 'ms' }, { num: '2.4', unit: 's' }, { num: '2', unit: 's' }, { num: '1', unit: 's' }, null])
    expect(durationAssessment({ duration_ms: 18400, slow_response_enabled: true, slow_threshold_ms: 15000 })).toEqual({ tone: 'warn', limitMs: 15000 })
    expect(durationAssessment({ duration_ms: 2400, slow_response_enabled: true, slow_threshold_ms: 15000 })).toEqual({ tone: 'ok', limitMs: 15000 })
    expect(durationAssessment({ duration_ms: 18400, slow_response_enabled: false, slow_threshold_ms: 15000 })).toEqual({ tone: 'neutral', limitMs: null })
    expect(durationAssessment({ status: 'TIMEOUT', duration_ms: 60000, timeout_seconds: 60 })).toEqual({ tone: 'bad', limitMs: 60000 })
    expect([versionDrift({ script_version: '4', run_script_version: '3' }), versionDrift({ script_version: '4', run_script_version: '4' }), versionDrift({ script_version: '1' })])
      .toEqual(['3', null, null])
  })
})

describe('ScriptedMonitorCard', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('geçen koşu: "14 / 14 checks passed" + yeşil oran çubuğu (ui/Progress), sonuç paneli ok tonlu, neden satırı yok', () => {
    const { container } = renderCard()
    const r = result(container)
    expect(r).toHaveAttribute('data-tone', 'ok')
    expect(r.querySelector('[data-slot="scripted-checks"]').textContent).toMatch(/^14 \/ 14checks passed14 of 14 checks passed$/)
    const bar = r.querySelector('[data-slot="progress-bar"] [data-slot="progress"]')
    expect(bar).not.toBeNull()
    expect(bar.querySelector('[data-slot="progress-indicator"]').className).toContain('bg-success')
    expect(r.querySelector('[data-slot="scripted-reason"]')).toBeNull()
    expect(r.querySelector('[data-slot="scripted-exit"]')).toBeNull()
  })

  it('düşen doğrulama: "12 / 14" + "2 failed", kırmızı çubuk, NEDEN = ilk düşen check adı "+1 more"; TÜM kenar kırmızı — sol şerit YOK', () => {
    const { container } = renderCard({ status: 'FAIL', checks_passed: 12, checks_failed: 2, error: 'k6 check/threshold başarısız',
      checks_json: checksJson([['basket › 200', true], ['payment › status is 200', false], ['payment › order id', false]]) })
    const r = result(container)
    expect(r).toHaveAttribute('data-tone', 'bad')
    expect(r.querySelector('[data-slot="scripted-checks"]').textContent).toMatch(/^12 \/ 14checks passed· 2 failed/)
    expect(r.querySelector('[data-slot="progress-indicator"]').className).toContain('bg-destructive')
    const reason = r.querySelector('[data-slot="scripted-reason"]')
    expect(reason).toHaveAttribute('data-reason', 'check')
    expect(reason.textContent).toBe('Failed: payment › status is 200+1 more')
    const card = cardOf(container)
    expect(card).toHaveAttribute('data-status', 'down')
    expect(card.className).toMatch(/(^|\s)border-destructive\/45(\s|$)/)
    expect(card.className).not.toMatch(/border-l-|before:/)
  })

  it('eşik (çıkış 99) → "Thresholds failed" çipi + eşik nedeni; script hatası (107) → çıkış etiketi + hatanın ÖZÜ (ikinci satır)', () => {
    const thr = renderCard({ status: 'FAIL', exit_code: 99, checks_passed: 20, checks_failed: 0, error: 'k6 check/threshold başarısız — threshold eşiği aşıldı' })
    expect(result(thr.container).querySelector('[data-slot="scripted-exit"]').textContent).toBe('Thresholds failed')
    expect(result(thr.container).querySelector('[data-slot="scripted-reason"]')).toHaveAttribute('data-reason', 'threshold')
    thr.unmount()
    const err = renderCard({ status: 'ERROR', exit_code: 107, checks_passed: null, checks_failed: null, never_succeeded: true,
      error: "script çalışırken hata verdi (k6 yine çıkış 0 verdi):\nTypeError: Cannot read property 'json' of undefined" })
    const r = result(err.container)
    expect(r.querySelector('[data-slot="scripted-exit"]').textContent).toBe('Script runtime error')
    expect(r.querySelector('[data-slot="scripted-checks"]')).toBeNull()   // sayaç yok → 0/0 uydurulmaz
    expect(r.querySelector('[data-slot="scripted-reason"]').textContent).toBe("TypeError: Cannot read property 'json' of undefined")
    expect(err.container.querySelector('[data-slot="never-succeeded-badge"]')).not.toBeNull()
  })

  it('zaman aşımı: neden "Timed out after 60 s" + k6 sebebi, takılınan faz çipi; süre kutusu kırmızı "timeout 60 s"', () => {
    const { container } = renderCard({ status: 'TIMEOUT', exit_code: -1, duration_ms: 60000, http_req_avg_ms: null, checks_passed: null, checks_failed: null,
      error: 'Süre aşımı — süreç sonlandırıldı:\nRequest Failed — Get "https://api.example.net/": request timeout',
      phases: { blocked: 0, connecting: 12, tls: 40, sending: 0, waiting: null, receiving: null } })
    const r = result(container)
    const reason = r.querySelector('[data-slot="scripted-reason"]')
    expect(reason).toHaveAttribute('data-reason', 'timeout')
    expect(reason.textContent).toMatch(/^Timed out after 60 sRequest Failed — Get "https:\/\/api\.example\.net\/": request timeout$/)
    expect(r.querySelector('[data-slot="scripted-exit"]')).toBeNull()   // -1 etiketi zaman aşımında gürültü
    expect(tile(container, 'duration')).toHaveAttribute('data-tone', 'bad')
    expect(tile(container, 'duration').textContent).toMatch(/60s.*timeout 60 s/)
    expect(tile(container, 'request').textContent).toMatch(/—.*not measured/)
  })

  it('NO_CHECKS: amber panel "hiçbir şey doğrulanmadı"; hiç koşmamış: nötr "ilk koşusu bekleniyor", kutu yok, alt çubukta "Never run"', () => {
    const nc = renderCard({ status: 'NO_CHECKS', checks_passed: 0, checks_failed: 0 })
    expect(result(nc.container)).toHaveAttribute('data-tone', 'warn')
    expect(result(nc.container).querySelector('[data-slot="scripted-no-checks"]').textContent).toMatch(/verified nothing|hiçbir şey doğrulamadı/)
    expect(result(nc.container).querySelector('[data-slot="scripted-checks"]')).toBeNull()
    nc.unmount()
    const never = renderCard({ status: 'unknown', checked_at: null, duration_ms: null, checks_passed: null, checks_failed: null, http_req_avg_ms: null })
    expect(result(never.container)).toHaveAttribute('data-tone', 'none')
    expect(result(never.container).textContent).toMatch(/Waiting for its first run/)
    expect(never.container.querySelector('[data-slot="scripted-metric"]')).toBeNull()
    expect(never.container.querySelector('[data-slot="scripted-last-run"]')).toHaveAttribute('data-never', 'true')
    expect(never.container.querySelector('[data-slot="card-footer"]').textContent).toMatch(/Never run/)
  })

  it('süre kutusu: yavaş eşik aşılınca amber + görünür "Slow" rozeti + "over the 15 s limit"; eşik içinde yeşil (hüküm ekran okuyucuda); alarm kapalıysa nötr', () => {
    const slow = renderCard({ duration_ms: 18400, slow_response_enabled: true, slow_threshold_ms: 15000 })
    const d = tile(slow.container, 'duration')
    expect(d).toHaveAttribute('data-tone', 'warn')
    expect(d.querySelector('[data-slot="scripted-metric-value"]').textContent).toBe('18.4s')
    expect(within(d).getByText('Slow')).toHaveAttribute('data-slot', 'scripted-metric-verdict')
    expect(d.querySelector('[data-slot="scripted-metric-sub"]').textContent).toBe('over the 15 s limit')
    slow.unmount()
    const ok = renderCard({ duration_ms: 2380, slow_response_enabled: true, slow_threshold_ms: 15000 })
    expect(tile(ok.container, 'duration')).toHaveAttribute('data-tone', 'ok')
    expect(tile(ok.container, 'duration').querySelector('[data-slot="scripted-metric-sub"]').textContent).toBe('Normal · limit 15 s')
    expect(tile(ok.container, 'request').querySelector('[data-slot="scripted-metric-value"]').textContent).toBe('164ms')
    ok.unmount()
    const neutral = renderCard()
    expect(tile(neutral.container, 'duration')).toHaveAttribute('data-tone', 'neutral')
    expect(tile(neutral.container, 'duration').querySelector('[data-slot="scripted-metric-verdict"]')).toBeNull()
  })

  it('künye: sürüm çipi (VersionChip) + son koşu eski sürümle yapıldıysa amber "last run on v3" (dokun-gör açıklama); sıklık; sistem kapattı rozeti', async () => {
    const { container } = renderCard({ script_version: '4', run_script_version: '3', active: false,
      disabled_reason: 'Anomali durumu tespit edildi: tek koşumda 5000 istek atıldı (tavan 200).' })
    const chips = container.querySelector('[data-slot="scripted-chips"]')
    expect(chips.querySelector('[data-slot="version-chip"]').textContent).toBe('Script version v4')
    const drift = chips.querySelector('[data-chip="drift"]')
    expect(drift.textContent).toBe('last run on v3')
    // Dokunmatikte açıklamalı çipin dokunma alanı dikeyde 40 px (32 px taban + ::after ±4 px)
    expect(drift.closest('[data-slot="hint-trigger"]').className).toContain('pointer-coarse:after:-inset-y-1')
    fireEvent.click(drift.closest('[data-slot="hint-trigger"]'))
    expect((await screen.findByRole('tooltip')).textContent).toMatch(/The last run used v3/)
    expect(chips.querySelector('[data-chip="interval"]').textContent).toMatch(/every 5 min/)
    // Uyarı rozetleri ÖNCE (kaçırılmasın)
    expect(chips.firstElementChild.querySelector('[data-slot="autodisabled-badge"]')).not.toBeNull()
  })

  it('hedef satırı: host (+N) soluk; hedef ADRESİ kopyalanır ve detay AÇILMAZ; tarayıcı senaryosu "k6 · browser" etiketi', async () => {
    const onOpen = vi.fn()
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: vi.fn(() => Promise.resolve()) } })
    const { container } = renderCard({
      env: [{ name: 'BASE_URL', secret: false, value: 'https://portal.example.com' }],
      script: "import { browser } from 'k6/browser'\nexport default async function () {\n  const p = await browser.newPage()\n  await p.goto('https://sso.example.com/auth')\n}",
    }, { onOpen })
    const target = container.querySelector('[data-slot="scripted-target"]')
    expect(target.textContent).toBe('Target: portal.example.com+1and 1 more host')
    fireEvent.click(screen.getByRole('button', { name: 'Portal login — Copy target URL' }))
    await waitFor(() => expect(navigator.clipboard.writeText).toHaveBeenCalledWith('https://portal.example.com'))
    expect(onOpen).not.toHaveBeenCalled()
    const tag = container.querySelector('[data-slot="monitor-card-tag"]')
    expect(tag).toHaveAttribute('data-runner', 'browser')
    expect(tag.textContent).toBe('k6 · browser')
  })

  it('başlık GERÇEK düğme ve detayı açar (uzun ad iki satıra kadar); seçim kutusu/eylemler detayı AÇMAZ; adlar satırı taşır', () => {
    const onOpen = vi.fn()
    const onSel = vi.fn()
    renderCard({}, {
      onOpen,
      select: <Checkbox className={CARD_CHECK} checked={false} onCheckedChange={onSel} aria-label="Select Portal login for bulk action" />,
      actions: <MonitorCardActions rowLabel="Portal login" onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} onDelete={() => {}} checkTitle="Run now" editTitle="Edit" deleteTitle="Delete" />,
    })
    const title = screen.getByRole('button', { name: 'Portal login — open details' })
    expect(title).toHaveAttribute('data-monitor-open', 'true')
    expect(title.className).toContain('[&>span]:line-clamp-2')
    fireEvent.click(title)
    expect(onOpen).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Portal login for bulk action' }))
    expect(onSel).toHaveBeenCalled()
    for (const name of ['Portal login — Run now', 'Portal login — Edit', /^Portal login — (Duplicate|Kopyala)$/, 'Portal login — Delete']) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument()
    }
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('duraklatılmış: kesik/soluk kart + "Paused" + Sürdür; alarm: seviye rozeti + tüm kart dış çizgisi (kırmızı kenar tonu yok); bakım rozeti', async () => {
    const onResume = vi.fn()
    const paused = renderCard({ active: false }, {
      actions: <MonitorCardActions rowLabel="Portal login" onResume={onResume} onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} checkTitle="Run now" editTitle="Edit" />,
    })
    expect(cardOf(paused.container)).toHaveAttribute('data-inactive', 'true')
    expect(paused.container.querySelector('[data-slot="card-footer"] [data-slot="monitor-paused"]')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Portal login — Resume' }))
    expect(onResume).toHaveBeenCalledTimes(1)
    paused.unmount()

    const alarm = renderCard({ status: 'FAIL', checks_failed: 1, active_alarm: true, alarm_level: 'CRITICAL', alarm_acknowledged: false })
    const card = cardOf(alarm.container)
    expect(card).toHaveAttribute('data-alarm', 'true')
    expect(card.querySelector('[data-slot="monitor-alarm"]')).toHaveAttribute('data-level', 'CRITICAL')
    expect(card.className).not.toMatch(/border-destructive\/45/)
    alarm.unmount()

    const maint = renderCard({ name: 'Nightly export' })
    await waitFor(() => expect(maint.container.querySelector('[data-slot="maintenance-badge"]')).not.toBeNull())
  })

  it('meta + son koşu: takım/grup + ilk üç etiket "+N"; göreli zaman, tam zaman ekran okuyucuda; telefon: iki sütun ölçü, 40 px kopyala', () => {
    const { container } = renderCard({ tags: 'prod,auth,edge,dr' })
    expect(container.querySelector('[data-slot="meta-team"]')).not.toBeNull()
    const tags = container.querySelector('[data-slot="scripted-tags"]')
    expect([...tags.querySelectorAll('[data-slot="badge"]')].map((b) => b.textContent)).toEqual(['prod', 'auth', 'edge', '+1dr'])
    const at = container.querySelector('[data-slot="scripted-last-run"]')
    expect(at.textContent).toMatch(/^5 min ago \(exact:/)
    expect(at.getAttribute('datetime')).toMatch(/Z$/)
    expect(container.querySelector('[data-slot="monitor-metrics"]').className).toMatch(/(^|\s)grid-cols-2(\s|$)/)
    expect(screen.getByRole('button', { name: 'Portal login — Copy target URL' }).className).toContain('pointer-coarse:size-10')
  })
})

/**
 * KART YOĞUNLUĞU (2026-09-27): Zengin = bugünkü tam kart; Kompakt = durum satırı + tek satır ad + hedef + yalnız uyarı
 * rozetleri + tek satırlık son koşu özeti (doğrulama sayacı · süre) + gerekiyorsa tek satır neden + takım rozeti + alt
 * çubuk. Sonuç paneli, ölçü kutuları, trend/SLA, künye çipleri, grup ve etiketler Kompakt'ta DOM'a girmez.
 */
describe('ScriptedMonitorCard — yoğunluk (Kompakt / Zengin)', () => {
  const compactRun = (c) => c.querySelector('[data-slot="scripted-compact-run"]')
  const compactReason = (c) => c.querySelector('[data-slot="scripted-compact-reason"]')
  const RICH_ONLY = ['scripted-result', 'scripted-metric', 'monitor-metrics', 'scripted-tags', 'meta-group', 'version-chip', 'monitor-card-tag']

  it('Zengin (varsayılan): sonuç paneli, ölçü kutuları, künye çipleri, koşucu etiketi, grup ve etiketler; kompakt özet YOK', () => {
    const { container } = renderCard({ tags: 'prod,auth' })
    expect(cardOf(container)).toHaveAttribute('data-density', 'rich')
    expect(container.querySelectorAll('[data-slot="monitor-card-rich"]').length).toBeGreaterThanOrEqual(2)
    for (const slot of RICH_ONLY) expect(container.querySelector(`[data-slot="${slot}"]`), slot).not.toBeNull()
    expect(container.querySelector('[data-chip="interval"]')).not.toBeNull()
    expect(compactRun(container)).toBeNull()
  })

  it('Kompakt geçen koşu: yalnız-Zengin bölümler DOM\'da YOK; "14 / 14 checks passed · 2.4 s", hedef, takım rozeti, eylem/seçim/zaman kalır', () => {
    const onOpen = vi.fn()
    const onSel = vi.fn()
    const { container } = renderCard({ tags: 'prod,auth' }, {
      density: 'compact', onOpen,
      select: <Checkbox className={CARD_CHECK} checked={false} onCheckedChange={onSel} aria-label="Select Portal login for bulk action" />,
      actions: <MonitorCardActions rowLabel="Portal login" onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} checkTitle="Run now" editTitle="Edit" />,
    })
    expect(cardOf(container)).toHaveAttribute('data-density', 'compact')
    expect(container.querySelector('[data-slot="monitor-card-rich"]')).toBeNull()
    for (const slot of RICH_ONLY) expect(container.querySelector(`[data-slot="${slot}"]`), slot).toBeNull()
    expect(container.querySelector('[data-chip="interval"]')).toBeNull()
    expect(container.querySelector('[data-slot="progress-bar"]')).toBeNull()
    const run = compactRun(container)
    expect(run).toHaveAttribute('data-tone', 'ok')
    expect(run.querySelector('[data-slot="scripted-compact-checks"]').textContent).toBe('14 / 14checks passed14 of 14 checks passed')
    expect(run.querySelector('[data-slot="scripted-compact-duration"]').textContent.replace(/\s+/g, ' ')).toMatch(/^·Duration: 2\.4s$/)
    expect(compactReason(container)).toBeNull()
    // İkincil satır: hedef (kopyala ile); takım rozeti TEK meta öğesi
    expect(container.querySelector('[data-slot="scripted-target"]').textContent).toMatch(/portal\.example\.com/)
    const meta = container.querySelector('[data-slot="monitor-card-meta"]')
    expect(meta.children).toHaveLength(1)
    expect(meta.querySelector('[data-slot="meta-team"]')).toHaveTextContent('Takım A')
    // Başlık tek satır (iki satır sınırı yalnız Zengin'de) ve detayı açar; seçim ve eylemler çalışır
    const title = screen.getByRole('button', { name: 'Portal login — open details' })
    expect(title.className).not.toContain('line-clamp-2')
    fireEvent.click(title)
    expect(onOpen).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Portal login for bulk action' }))
    expect(onSel).toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Portal login — Run now' })).toBeInTheDocument()
    expect(container.querySelector('[data-slot="scripted-last-run"]').textContent).toMatch(/^5 min ago/)
  })

  it('Kompakt düşen doğrulama: kırmızı sayaç + TEK satır neden (düşen check "+1 more"), tam metin dokun-gör açıklamada; detay AÇILMAZ', async () => {
    const onOpen = vi.fn()
    const { container } = renderCard({ status: 'FAIL', checks_passed: 12, checks_failed: 2,
      checks_json: checksJson([['basket › 200', true], ['payment › status is 200', false], ['payment › order id', false]]) }, { density: 'compact', onOpen })
    expect(compactRun(container)).toHaveAttribute('data-tone', 'bad')
    expect(compactRun(container).querySelector('[data-slot="scripted-compact-checks"] .text-destructive')).not.toBeNull()
    const reason = compactReason(container)
    expect(reason).toHaveAttribute('data-reason', 'check')
    expect(reason.textContent).toBe('Failed: payment › status is 200 +1 more')
    expect(reason.className).toMatch(/(^|\s)truncate(\s|$)/)
    const trigger = screen.getByRole('button', { name: 'Failed: payment › status is 200 +1 more' })
    expect(trigger.className).toContain('pointer-coarse:after:-inset-y-1')
    fireEvent.click(trigger)
    expect((await screen.findByRole('tooltip')).textContent).toBe('Failed: payment › status is 200 +1 more')
    expect(onOpen).not.toHaveBeenCalled()
    expect(container.querySelector('[data-slot="scripted-reason"]')).toBeNull()   // Zengin neden satırı yok
  })

  it('Kompakt zaman aşımı / script hatası / eşik: neden cümlesi + hatanın özü; süre tonu', () => {
    const to = renderCard({ status: 'TIMEOUT', exit_code: -1, duration_ms: 60000, checks_passed: null, checks_failed: null,
      error: 'Süre aşımı — süreç sonlandırıldı:\nRequest Failed — request timeout' }, { density: 'compact' })
    expect(compactReason(to.container)).toHaveAttribute('data-reason', 'timeout')
    expect(compactReason(to.container).textContent).toBe('Timed out after 60 s — Request Failed — request timeout')
    expect(to.container.querySelector('[data-slot="scripted-compact-duration"]')).toHaveAttribute('data-tone', 'bad')
    expect(to.container.querySelector('[data-slot="scripted-compact-checks"]')).toBeNull()   // sayaç yok → 0/0 uydurulmaz
    to.unmount()

    const err = renderCard({ status: 'ERROR', exit_code: 107, checks_passed: null, checks_failed: null,
      error: "script çalışırken hata verdi:\nTypeError: Cannot read property 'json' of undefined" }, { density: 'compact' })
    expect(compactReason(err.container)).toHaveAttribute('data-reason', 'error')
    expect(compactReason(err.container).textContent).toBe("Script runtime error — TypeError: Cannot read property 'json' of undefined")
    err.unmount()

    const thr = renderCard({ status: 'FAIL', exit_code: 99, checks_passed: 20, checks_failed: 0 }, { density: 'compact' })
    expect(compactReason(thr.container)).toHaveAttribute('data-reason', 'threshold')
    expect(compactReason(thr.container).textContent).toBe('A threshold in the script’s options was crossed')
  })

  it('Kompakt NO_CHECKS: amber neden satırı; yavaş koşu: görünür "Slow"; hiç koşmamış: yalnız "ilk koşusu bekleniyor"', () => {
    const nc = renderCard({ status: 'NO_CHECKS', checks_passed: 0, checks_failed: 0 }, { density: 'compact' })
    expect(compactRun(nc.container)).toHaveAttribute('data-tone', 'warn')
    expect(compactReason(nc.container)).toHaveAttribute('data-reason', 'no-checks')
    expect(compactReason(nc.container).textContent).toMatch(/verified nothing/)
    expect(compactReason(nc.container).closest('[data-slot="hint-trigger"]').className).toContain('text-amber-800')
    nc.unmount()

    const slow = renderCard({ duration_ms: 18400, slow_response_enabled: true, slow_threshold_ms: 15000 }, { density: 'compact' })
    const dur = slow.container.querySelector('[data-slot="scripted-compact-duration"]')
    expect(dur).toHaveAttribute('data-tone', 'warn')
    expect(within(dur).getByText('Slow')).toHaveAttribute('data-slot', 'scripted-metric-verdict')
    slow.unmount()

    const never = renderCard({ status: 'unknown', checked_at: null, duration_ms: null, checks_passed: null, checks_failed: null }, { density: 'compact' })
    expect(compactRun(never.container)).toHaveAttribute('data-tone', 'none')
    expect(compactRun(never.container).textContent).toBe('Waiting for its first run')
    expect(compactReason(never.container)).toBeNull()
  })

  it('Kompakt: uyarı rozetleri (sistem kapattı / hiç başarılı olmadı) KALIR, sürüm/sıklık çipleri gider; duraklatılmış + alarm görünür', () => {
    const { container } = renderCard({ active: false, never_succeeded: true, script_version: '4', run_script_version: '3',
      disabled_reason: 'Anomali durumu tespit edildi.', status: 'FAIL', checks_failed: 1, active_alarm: true, alarm_level: 'HIGH' }, { density: 'compact' })
    const chips = container.querySelector('[data-slot="scripted-chips"]')
    expect(chips.querySelector('[data-slot="autodisabled-badge"]')).not.toBeNull()
    expect(chips.querySelector('[data-slot="never-succeeded-badge"]')).not.toBeNull()
    expect(chips.querySelector('[data-slot="version-chip"]')).toBeNull()
    expect(chips.querySelector('[data-chip="drift"]')).toBeNull()
    expect(chips.querySelector('[data-chip="interval"]')).toBeNull()
    const card = cardOf(container)
    expect(card).toHaveAttribute('data-inactive', 'true')
    expect(card.querySelector('[data-slot="monitor-paused"]')).not.toBeNull()
    expect(card.querySelector('[data-slot="monitor-alarm"]')).toHaveAttribute('data-level', 'HIGH')
  })
})
