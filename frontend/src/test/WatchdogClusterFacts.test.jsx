import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', async () => {
  const actual = await vi.importActual('../api/client')
  return { ...actual, api: withApiFallback({ admin: { evaluateWatchdog: vi.fn(), testWatchdog: vi.fn() } }) }
})
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { useT } from '../i18n/index.jsx'
import WatchdogSection from '../components/admin/health/WatchdogSection.jsx'
import {
  evaluatedBy, formatSkewSeconds, skewPods, skewTone, SKEW_PROBLEM_MS, SKEW_WARN_MS,
} from '../components/admin/health/watchdogModel.js'

/**
 * Kendi kendini izleme küme gerçekleri (2026-10-23, çok pod R2b, MP-36 / MP-37c): saat uyumu kontrolünün pod başına farkı
 * (en büyük önce, ton eşiğe göre, pod adı kırpılmaz) ve pod'a özgü olgu taşıyan kontrolde "değerlendiren pod" satırı.
 */
const LONG_POD = 'sitemonitor-prod-7d9f8c6b5d-abcde-uzun-bir-pod-adi-kirpilmamali'

function check(overrides = {}) {
  return {
    key: 'clock_skew', label: 'Saat uyumu', status: 'PROBLEM', last_result: 'PROBLEM', severity: 'HIGH',
    since: '2026-10-23T10:00:00', evaluated_at: '2026-10-23T10:05:00',
    detail: 'sm-b pod\'unun saati veritabanı saatinden 45,0 sn ileride.',
    facts: { pods: [{ pod: 'sm-b', skew_ms: 45000 }, { pod: LONG_POD, skew_ms: -6200 }, { pod: 'sm-a', skew_ms: 300 }],
      max_abs_ms: 45000, worst_pod: 'sm-b' },
    ...overrides,
  }
}

function Harness({ data }) {
  const t = useT()
  return <WatchdogSection t={t} data={data} error={null} canAct={false} globalAdmin={false} onChanged={() => {}} />
}

describe('watchdogModel — küme gerçekleri', () => {
  it('saat farkı: işaretli, bir ondalık, dil ayırıcısı; sayı değilse null (0 uydurulmaz)', () => {
    expect(formatSkewSeconds(12000, 'tr')).toBe('+12,0')
    expect(formatSkewSeconds(-400, 'en')).toBe('−0.4')
    expect(formatSkewSeconds(0, 'tr')).toBe('0,0')
    expect(formatSkewSeconds(null)).toBeNull()
    expect(formatSkewSeconds(Number.NaN)).toBeNull()
  })

  it('ton eşikleri sunucuyla aynı: 5 sn üstü uyarı, 30 sn üstü tehlike', () => {
    expect(SKEW_WARN_MS).toBe(5000)
    expect(SKEW_PROBLEM_MS).toBe(30000)
    expect(skewTone(5000)).toBe('success')
    expect(skewTone(-5001)).toBe('warning')
    expect(skewTone(30001)).toBe('danger')
  })

  it('pod listesi: geçersiz satırlar atlanır, sıra sunucudan; liste yoksa boş', () => {
    const pods = skewPods({ facts: { pods: [{ pod: 'a', skew_ms: 100 }, { pod: '', skew_ms: 1 }, { pod: 'b' }, null] } })
    expect(pods).toEqual([{ pod: 'a', ms: 100, tone: 'success' }])
    expect(skewPods({})).toEqual([])
    expect(skewPods(null)).toEqual([])
  })

  it('değerlendiren pod yalnız pod\'a özgü olgu varsa (açık devre, havuz, şema yaması); yoksa null', () => {
    expect(evaluatedBy({ facts: { evaluated_by: 'sm-a', circuit_open: true } })).toBe('sm-a')
    expect(evaluatedBy({ facts: { evaluated_by: 'sm-a', evaluating_pod_pool: 'DEGRADED' } })).toBe('sm-a')
    expect(evaluatedBy({ facts: { evaluated_by: 'sm-a', evaluating_pod_schema_failed: 2 } })).toBe('sm-a')
    expect(evaluatedBy({ facts: { evaluated_by: 'sm-a', circuit_open: false, evaluating_pod_pool: 'UP' } })).toBeNull()
    expect(evaluatedBy({ facts: { circuit_open: true } })).toBeNull()
    expect(evaluatedBy(null)).toBeNull()
  })
})

describe('WatchdogSection — küme gerçekleri', () => {
  beforeEach(() => {
    try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  })

  it('saat uyumu: pod başına fark rozetleri (en büyük önce, eşik tonu, uzun pod adı tam)', async () => {
    render(<Harness data={{ status: 'DEGRADED', enabled: true, interval_seconds: 60, confirm_checks: 2, checks: [check()], channels: {} }} />)
    const list = await screen.findByText('Pod başına saat farkı (veritabanı saatine göre):')
    const box = list.closest('[data-slot="watchdog-skew-pods"]')
    const badges = within(box).getAllByText(/sn$/)
    expect(badges.map((b) => b.textContent)).toEqual(['sm-b: +45,0 sn', `${LONG_POD}: −6,2 sn`, 'sm-a: +0,3 sn'])
    expect(badges.map((b) => b.closest('[data-slot="badge"]').getAttribute('data-tone'))).toEqual(['danger', 'warning', 'success'])
    expect(badges[1].closest('[data-slot="badge"]').className).toContain('whitespace-normal')
    expect(badges[1].closest('[data-slot="badge"]').className).not.toContain('truncate')
  })

  it('pod\'a özgü olgu: "Değerlendiren pod" satırı; olgu yoksa satır yok, saat listesi olmayan kontrolde liste yok', async () => {
    const push = { key: 'push_outbox', label: 'Push kuyruğu', status: 'OK', last_result: 'OK', since: '2026-10-23T10:00:00',
      detail: '0 push 10 dk\'dan uzun süredir gecikmede.', facts: { stuck: 0, circuit_open: true, evaluated_by: 'sm-a' } }
    const db = { key: 'database', label: 'Veritabanı', status: 'OK', last_result: 'OK', since: '2026-10-23T10:00:00',
      detail: 'Veritabanı durumu: UP.', facts: { status: 'UP', evaluating_pod_pool: 'UP', evaluated_by: 'sm-b' } }
    render(<Harness data={{ status: 'UP', enabled: true, checks: [push, db], channels: {} }} />)
    const rows = await screen.findAllByText(/^Değerlendiren pod:/)
    expect(rows).toHaveLength(1)
    expect(rows[0].textContent).toContain('sm-a')
    expect(rows[0].closest('[data-slot="watchdog-check"]').getAttribute('data-key')).toBe('push_outbox')
    expect(document.querySelector('[data-slot="watchdog-skew-pods"]')).toBeNull()
  })

  it('bekleyen (henüz ölçülmemiş) kontrol pod bilgisi çizmez', async () => {
    render(<Harness data={{ status: 'UNKNOWN', enabled: true, checks: [{ key: 'clock_skew', label: 'Saat uyumu', status: 'PENDING',
      facts: { pods: [{ pod: 'sm-a', skew_ms: 100 }] } }], channels: {} }} />)
    await screen.findByText('Saat uyumu')
    expect(document.querySelector('[data-slot="watchdog-skew-pods"]')).toBeNull()
  })
})
