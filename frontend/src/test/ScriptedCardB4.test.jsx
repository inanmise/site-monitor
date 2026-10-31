import { describe, it, expect, vi } from 'vitest'
import { render } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => (s ? `exact:${s}` : ''),
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({ monitoring: { maintenance: { active: vi.fn(() => Promise.resolve({ success: true, data: { all: false, targets: [] } })) } } }),
}))

import ScriptedMonitorCard from '../components/scripted/ScriptedMonitorCard.jsx'
import MonitorCardMeta from '../components/MonitorCardMeta.jsx'
import { MonitorStatusBadge } from '../components/monitoring/MonitorCard.jsx'
import { isScheduled, scheduleText } from '../components/scripted/scriptedCardModel.js'

const stamp = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const base = {
  id: 7, name: 'Portal login', script: "export default function(){}", env: [], status: 'PASS', active: true, interval_seconds: 300,
  timeout_seconds: 60, checks_passed: 3, checks_failed: 0, duration_ms: 2380, http_req_avg_ms: 164, exit_code: 0, error: null,
  team_id: 1, team_name: 'Takım A', group_name: 'Kurumsal Web', tags: 'prod', checked_at: stamp(5 * 60_000),
}
const statusOf = (s) => (s === 'PASS' ? 'up' : ['FAIL', 'ERROR', 'TIMEOUT'].includes(s) ? 'down' : s === 'NO_CHECKS' ? 'warn' : 'unknown')
function renderCard(monitor = {}, props = {}) {
  const m = { ...base, ...monitor }
  const status = statusOf(m.status)
  return render(<ScriptedMonitorCard monitor={m} status={status} badge={<MonitorStatusBadge status={status}>{m.status}</MonitorStatusBadge>} onOpen={() => {}} {...props} />)
}
const q = (c, slot) => c.querySelector(`[data-slot="${slot}"]`)

describe('Sentetik kart — B4 fe2', () => {
  it('O5: ERROR / TIMEOUT koşumunda düşen doğrulama yoksa sayaç NÖTR "koşabildi · yarıda kaldı" (yeşil "geçti" değil)', () => {
    for (const status of ['ERROR', 'TIMEOUT']) {
      const { container, unmount } = renderCard({ status, exit_code: status === 'ERROR' ? 107 : -1, error: 'x' })
      const checks = q(container, 'scripted-checks')
      expect(checks.dataset.halted).toBe('true')
      expect(checks.querySelector('.text-success')).toBeNull()
      expect(checks.textContent).toMatch(/3/)
      expect(checks.textContent).toMatch(/stopped part-way|yarıda kaldı/)
      expect(container.querySelector('[data-slot="progress-bar"]')).toBeNull()   // "tam dolu" çubuk yok
      unmount()
    }
    // Kompakt da aynı kural
    const { container } = renderCard({ status: 'ERROR', exit_code: 107, error: 'x' }, { density: 'compact' })
    expect(q(container, 'scripted-compact-checks').dataset.halted).toBe('true')
  })

  it('O5: düşen doğrulama varsa ERROR kartı kırmızı sayaç taşımaya devam eder', () => {
    const { container } = renderCard({ status: 'ERROR', checks_passed: 1, checks_failed: 2, exit_code: 107, error: 'x' })
    const checks = q(container, 'scripted-checks')
    expect(checks.dataset.halted).toBeUndefined()
    expect(checks.querySelector('.text-destructive')).not.toBeNull()
  })

  it('D6: duraklatılmış kart sonuç paneli NÖTR (eski yeşil / kırmızı taşınmaz); süre kutusu tonlanmaz', () => {
    const { container } = renderCard({ active: false, status: 'PASS' })
    expect(q(container, 'scripted-result').dataset.tone).toBe('none')
    expect(q(container, 'scripted-checks').querySelector('.text-success')).toBeNull()
    const { container: c2 } = renderCard({ active: false, status: 'TIMEOUT', checks_passed: null, checks_failed: null, error: 'x', disabled_reason: 'anomali' })
    expect(q(c2, 'scripted-result').dataset.tone).toBe('none')
    expect(c2.querySelector('[data-slot="scripted-metric"][data-metric="duration"]').dataset.tone).toBe('neutral')
    expect(q(c2, 'scripted-reason').className).toMatch(/text-muted-foreground/)
    const { container: c3 } = renderCard({ active: false, status: 'FAIL', checks_passed: 1, checks_failed: 2, error: 'x' }, { density: 'compact' })
    expect(q(c3, 'scripted-compact-run').dataset.tone).toBe('none')
  })

  it('kartta p95: liste satırı `last_http_req_p95_ms` taşıyorsa istek kutusunun alt satırında; yoksa bugünkü metin', () => {
    const { container } = renderCard({ last_http_req_p95_ms: 1240 })
    const sub = container.querySelector('[data-slot="scripted-metric"][data-metric="request"] [data-slot="scripted-metric-sub"]')
    expect(sub.textContent).toMatch(/p95 1\.2 s/)
    const { container: c2 } = renderCard({})
    expect(c2.querySelector('[data-slot="scripted-metric"][data-metric="request"] [data-slot="scripted-metric-sub"]').textContent).not.toMatch(/p95/)
  })

  it('E1: alarm ekran okuyucu etiketi seviyenin ADINI söyler (ham "HIGH" değil)', () => {
    const { container } = renderCard({ active_alarm: true, alarm_level: 'HIGH', status: 'FAIL', error: 'x' })
    const sr = container.querySelector('[data-slot="monitor-alarm"] .sr-only')
    expect(sr.textContent).toMatch(/High|Yüksek/)
    expect(sr.textContent).not.toMatch(/HIGH/)
  })
})

describe('Sentetik kart — zamanlama çipi (k6r D2 alanları)', () => {
  const tt = (k, ...a) => (a.length ? `${k}:${a.join('|')}` : k)

  it('isScheduled: yalnız CRON ya da dolu süzgeç; alan yoksa (eski sunucu) false', () => {
    expect(isScheduled({ interval_seconds: 300 })).toBe(false)
    expect(isScheduled({ schedule_mode: 'INTERVAL', windows: [], blackouts: [], skip_holidays: false, skip_dates: [] })).toBe(false)
    expect(isScheduled({ schedule_mode: 'CRON', cron_expression: '0 9 * * 1-5' })).toBe(true)
    expect(isScheduled({ windows: [{ days: [1], from: '09:00', to: '18:00' }] })).toBe(true)
    expect(isScheduled({ blackouts: [{ from: '00:00', to: '01:00' }] })).toBe(true)
    expect(isScheduled({ skip_holidays: true })).toBe(true)
    expect(isScheduled({ skip_dates: ['2026-11-02'] })).toBe(true)
  })

  it('scheduleText: aralık = bugünkü "her N dk"; CRON insan diliyle ya da ham; süzgeçler ek olarak', () => {
    expect(scheduleText({ interval_seconds: 300 }, tt)).toBe('ping.card.everyMin:5')
    expect(scheduleText({ schedule_mode: 'CRON', cron_expression: '0 9 * * 1-5' }, tt)).toBe('dbq.card.cronWeekdays:09:00')
    expect(scheduleText({ schedule_mode: 'CRON', cron_expression: '7 9-17/2 * * 1-5' }, tt)).toBe('dbq.card.cronRaw:7 9-17/2 * * 1-5')
    expect(scheduleText({ interval_seconds: 600, windows: [{}], skip_holidays: true, skip_dates: ['2026-11-02'] }, tt))
      .toBe('ping.card.everyMin:10 · dbq.card.schedWindows · dbq.card.schedHolidays · scripted.card.schedSkipDates')
    expect(scheduleText({ schedule_mode: 'CRON', cron_expression: '' }, tt)).toBeNull()
  })

  it('kart: zamanlamalı izlemede çip takvim simgesi + data-schedule taşır ve SARAR; aralık kipinde bugünkü çip', () => {
    const { container } = renderCard({ schedule_mode: 'CRON', cron_expression: '0 9 * * 1-5', windows: [{}], skip_holidays: true })
    const chip = container.querySelector('[data-chip="interval"]')
    expect(chip.dataset.schedule).toBe('cron')
    expect(chip.textContent).toMatch(/09:00/)
    expect(chip.textContent).toMatch(/holidays|tatil/)
    expect(chip.className).toMatch(/whitespace-normal/)
    expect(chip.className).not.toMatch(/truncate/)
    expect(chip.querySelector('svg.lucide-calendar-clock')).not.toBeNull()
    const { container: c2 } = renderCard({})
    const plain = c2.querySelector('[data-chip="interval"]')
    expect(plain.dataset.schedule).toBeUndefined()
    expect(plain.querySelector('svg.lucide-timer')).not.toBeNull()
  })
})

describe('MonitorCardMeta — O8 (dokuz türün ortak kartı)', () => {
  it('uzun AD takım adı KESİLMEZ: TeamBadge sarar; grup adı da sarar', () => {
    const { container } = render(<MonitorCardMeta monitor={{ team_name: LONG_TEAM, group_name: 'Bireysel Bankacılık Giriş Akışları ve Uzun Grup Adı' }} />)
    const badge = container.querySelector('[data-slot="team-badge"]')
    expect(badge.textContent).toBe(LONG_TEAM)
    expect(badge.className).toMatch(/whitespace-normal/)
    expect(badge.querySelector('.truncate')).toBeNull()
    const group = container.querySelector('[data-slot="meta-group"] span')
    expect(group.className).not.toMatch(/truncate/)
    expect(group.className).toMatch(/overflow-wrap:anywhere/)
  })
})
