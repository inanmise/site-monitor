import { describe, it, expect } from 'vitest'
import {
  sortTeams, windowPct, sealRemainingMs, formatShortDuration, chartRows, stormTimeline, reasonKey, isDay, STATUS_META,
} from '../components/storm/stormModel.js'

const t = (k, ...a) => (a.length ? `${k}:${a.join(',')}` : k)

describe('stormModel — Alarm Fırtınası sayfası yardımcıları (2026-09-30)', () => {
  it('sortTeams: fırtına → eşiğe yakın → izleniyor → sakin; eşitte pencere hedefi çok olan, sonra ad', () => {
    const out = sortTeams([
      { team_id: 1, team_name: 'Z', status: 'CALM', window_targets: 0 },
      { team_id: 2, team_name: 'B', status: 'NEAR', window_targets: 3 },
      { team_id: 3, team_name: 'A', status: 'NEAR', window_targets: 4 },
      { team_id: 4, team_name: 'S', status: 'STORM', window_targets: 6 },
      { team_id: 5, team_name: 'W', status: 'WATCH', window_targets: 1 },
      { team_id: 6, team_name: 'X', status: 'BOGUS' },
    ])
    expect(out.map((x) => x.team_id)).toEqual([4, 3, 2, 5, 1, 6])
    expect(Object.keys(STATUS_META)).toEqual(['STORM', 'NEAR', 'WATCH', 'CALM'])
  })

  it('windowPct: hedef/eşik yüzdesi 0–100 kırpılır; eşik 0 → 0', () => {
    expect(windowPct({ window_targets: 3, threshold: 5 })).toBe(60)
    expect(windowPct({ window_targets: 9, threshold: 5 })).toBe(100)
    expect(windowPct({ window_targets: 2, threshold: 0 })).toBe(0)
    expect(windowPct(null)).toBe(0)
  })

  it('sealRemainingMs + formatShortDuration: UTC dilimsiz mühür anı → kalan süre; dk / sa biçimi', () => {
    const now = Date.UTC(2026, 8, 30, 12, 0, 0)
    expect(sealRemainingMs({ seal_at: '2026-09-30T12:05:00' }, now)).toBe(5 * 60_000)
    expect(sealRemainingMs({ seal_at: '2026-09-30T11:00:00' }, now)).toBe(-3_600_000)
    expect(sealRemainingMs({}, now)).toBeNull()
    expect(formatShortDuration(5 * 60_000, t)).toBe('sf.min:5')
    expect(formatShortDuration(125 * 60_000, t)).toBe('sf.hour:2 sf.min:5')
    expect(formatShortDuration(120 * 60_000, t)).toBe('sf.hour:2')
    expect(formatShortDuration(-1, t)).toBe('sf.min:0')
  })

  it('chartRows: ilk 5 takım ayrı seri, kalanı "other"; gün satırında toplam korunur', () => {
    const teams = Array.from({ length: 7 }, (_, i) => ({ team_id: i + 1, team_name: `T${i + 1}`, storms: 10 - i }))
    const series = [{ day: '2026-09-01', total: 3, by_team: { 1: 1, 6: 1, 7: 1 } }, { day: '2026-09-02', total: 1, by_team: { 2: 1 } }]
    const { rows, keys, hasOther } = chartRows({ series, teams })
    expect(keys.map((k) => k.key)).toEqual(['t1', 't2', 't3', 't4', 't5'])
    expect(rows[0]).toMatchObject({ day: '2026-09-01', total: 3, t1: 1, other: 2 })
    expect(rows[1]).toMatchObject({ t2: 1, other: 0 })
    expect(hasOther).toBe(true)
    expect(chartRows(null)).toEqual({ rows: [], keys: [], hasOther: false })
  })

  it('stormTimeline: açılış → tetikleyen → son üye → mühür (gelecek) sıralı; kapanmışta çözüm olayı ve neden etiketi', () => {
    const open = { created_at: '2026-09-30T10:00:00', targets_at_open: 6, threshold_effective: 5, quiet_minutes: 5,
      trigger: { domain: 'a.example.com', alert_type: 'HTTP_DOWN', created_at: '2026-09-30T10:00:00' },
      last_member_at: '2026-09-30T10:20:00', last_re_alert_at: '2026-09-30T10:00:00', seal_at: '2026-09-30T10:25:00', sealed: false, resolved: false }
    const tl = stormTimeline(open, t)
    expect(tl.map((e) => e.kind)).toEqual(['opened', 'trigger', 'member', 'seal'])
    expect(tl[0].text).toBe('sf.tl.opened:6,5')
    expect(tl.at(-1)).toMatchObject({ future: true, text: 'sf.tl.sealAt:5' })

    const closed = { ...open, resolved: true, resolved_at: '2026-09-30T10:40:00', resolve_reason: 'SEALED' }
    const tl2 = stormTimeline(closed, t)
    expect(tl2.at(-1)).toMatchObject({ kind: 'resolved', text: 'sf.tl.resolved:sf.reason.SEALED' })
    expect(tl2.some((e) => e.kind === 'seal')).toBe(false)
    expect(stormTimeline(null, t)).toEqual([])
  })

  it('reasonKey: açık → OPEN, bilinen kod → kendi anahtarı, bilinmeyen → UNKNOWN; isDay', () => {
    expect(reasonKey({ resolved: false })).toBe('sf.reason.OPEN')
    expect(reasonKey({ resolved: true, resolve_reason: 'FLOOR' })).toBe('sf.reason.FLOOR')
    expect(reasonKey({ resolved: true, resolve_reason: 'weird' })).toBe('sf.reason.UNKNOWN')
    expect(reasonKey(null)).toBe('sf.reason.UNKNOWN')
    expect(isDay('2026-09-30')).toBe(true)
    expect(isDay('30.09.2026')).toBe(false)
  })
})
