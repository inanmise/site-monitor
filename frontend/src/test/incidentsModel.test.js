import { describe, it, expect } from 'vitest'
import {
  laneOf, clientFacet, summarize, buildTimeline, serverParams, statusOf, filtersFromUrl, filtersToUrl, sortFromUrl,
  incidentHref, activeFilters, teamOptions, FILTER_DEFAULTS,
} from '../components/incidents/incidentsModel.js'

const open = { id: 1, status: 'ongoing', acknowledged: false, alert_level: 'CRITICAL', team_id: 5, started_at: '2026-07-10T10:00:00' }
const acked = { id: 2, status: 'ongoing', acknowledged: true, alert_level: 'HIGH', team_id: null, started_at: '2026-07-10T09:00:00' }
const done = { id: 3, status: 'resolved', acknowledged: true, alert_level: 'WARNING', team_id: 6, started_at: '2026-07-10T08:00:00', resolved_at: '2026-07-10T09:30:00', resolved_by: 'system' }

describe('incidentsModel — saf yardımcılar', () => {
  it('laneOf: açık+onaysız → open, açık+onaylı → ack, kapalı → resolved (onaylı olsa da)', () => {
    expect(laneOf(open)).toBe('open')
    expect(laneOf(acked)).toBe('ack')
    expect(laneOf(done)).toBe('resolved')
  })

  it('statusOf / serverParams: açık-altkümesi kartları "ongoing", resolved24 "resolved" + 24 saatlik since; istemci boyutları sunucuya gitmez', () => {
    expect(statusOf('ack')).toBe('ongoing')
    expect(statusOf('critical')).toBe('ongoing')
    expect(statusOf('resolved24')).toBe('resolved')
    expect(statusOf('')).toBe('all')
    const now = Date.parse('2026-09-26T09:30:00Z')
    const p = serverParams({ ...FILTER_DEFAULTS, stat: 'resolved24', level: 'HIGH', team: '5', since: '2026-01-01' }, { by: 'started', dir: 'asc' }, now)
    expect(p).toEqual({ status: 'resolved', rootCause: '', q: '', since: '2026-09-25T09:30:00', until: '', sort: 'started', dir: 'asc' })
    expect(Object.keys(p)).not.toContain('level')
    expect(serverParams(FILTER_DEFAULTS, { by: '', dir: 'desc' }, now).sort).toBeUndefined()
  })

  it('clientFacet: ack/critical/unassigned/mine + önem + takım yüklenen satırlara uygulanır', () => {
    const rows = [open, acked, done]
    expect(clientFacet(rows, { ...FILTER_DEFAULTS, stat: 'ack' }).map(r => r.id)).toEqual([2, 3])
    expect(clientFacet(rows, { ...FILTER_DEFAULTS, stat: 'critical' }).map(r => r.id)).toEqual([1])
    expect(clientFacet(rows, { ...FILTER_DEFAULTS, stat: 'unassigned' }).map(r => r.id)).toEqual([2])
    expect(clientFacet(rows, { ...FILTER_DEFAULTS, stat: 'mine' }, 5).map(r => r.id)).toEqual([1])
    expect(clientFacet(rows, { ...FILTER_DEFAULTS, stat: 'mine' }, null)).toEqual([])
    expect(clientFacet(rows, { ...FILTER_DEFAULTS, level: 'WARNING' }).map(r => r.id)).toEqual([3])
    expect(clientFacet(rows, { ...FILTER_DEFAULTS, team: '6' }).map(r => r.id)).toEqual([3])
  })

  it('summarize: açık toplam sunucudan, türetilmişler örnekten; örnek toplamdan küçükse exact=false', () => {
    const s = summarize([open, acked], 2, 4, 5)
    expect(s).toEqual({ open: 2, ack: 1, resolved24: 4, critical: 1, unassigned: 1, mine: 1, exact: true, sampled: 2 })
    expect(summarize([open], 300, null, null)).toEqual(expect.objectContaining({ open: 300, resolved24: 0, mine: 0, exact: false, sampled: 1 }))
  })

  it('buildTimeline: zamana göre sıralı; ZAMANSIZ onay (DTO acknowledged_at taşımaz) açılışın hemen ardına düşer, sonra bildirim → yorum → çözüm', () => {
    const tl = buildTimeline({
      incident: { ...done, acknowledged: true },
      comments: [{ id: 9, body: 'not', author_name: 'A', created_at: '2026-07-10T09:10:00' }],
      notifications: [{ id: 4, sent_at: '2026-07-10T08:01:00', recipient_name: 'Takım A', trigger: 'NEW', email_status: 'SENT' }],
    })
    expect(tl.map(e => e.kind)).toEqual(['opened', 'acknowledged', 'notified', 'comment', 'resolved'])
    expect(tl[1].when).toBeNull()
    expect(tl.at(-1).by).toBe('system')
    // Açık olayda çözüm yok; acknowledged_at varsa onay o zamana oturur
    const tl2 = buildTimeline({ incident: { ...open, acknowledged: true, acknowledged_at: '2026-07-10T10:30:00', acknowledged_by: 'Demo' },
      comments: [{ id: 1, body: 'x', created_at: '2026-07-10T10:05:00' }] })
    expect(tl2.map(e => e.kind)).toEqual(['opened', 'comment', 'acknowledged'])
    expect(tl2[2]).toEqual(expect.objectContaining({ when: '2026-07-10T10:30:00', by: 'Demo' }))
    expect(buildTimeline({ incident: null })).toEqual([])
  })

  it('URL: yalnız sayfa-durumu anahtarları (stat/type/q/from/to/level/team/view/sort), geçersiz değerler düşer, uygulama anahtarlarına dokunulmaz', () => {
    const read = (k) => ({ stat: 'critical', type: 'HTTP_DOWN', q: 'x', from: '2026-01-01', level: 'BOGUS', team: '5', view: 'list', sort: 'started:asc', incident: '7' })[k] ?? null
    expect(filtersFromUrl(read)).toEqual({ stat: 'critical', rootCause: 'HTTP_DOWN', q: 'x', since: '2026-01-01', until: '', level: '', team: '5' })
    expect(filtersFromUrl((k) => (k === 'stat' ? 'bogus' : null)).stat).toBe('')
    expect(sortFromUrl(read)).toEqual({ by: 'started', dir: 'asc' })
    expect(sortFromUrl(() => 'nope:asc')).toEqual({ by: '', dir: 'desc' })
    const out = filtersToUrl({ ...FILTER_DEFAULTS, stat: 'open' }, 'board', { by: '', dir: 'desc' })
    expect(out).toEqual({ stat: 'open', type: null, q: null, from: null, to: null, level: null, team: null, view: null, sort: null })
    for (const k of ['tab', 'domain', 'monitor', 'incident']) expect(Object.keys(out)).not.toContain(k)
  })

  it('incidentHref: izleme alarmı sekme+odak, sertifika alarmı pano+alan adı', () => {
    expect(incidentHref({ monitor: { tab: 'http', monitor_id: 9 } })).toBe('?tab=http&monitor=9')
    expect(incidentHref({ domain: 'cert.example.com', monitor: { tab: 'dashboard', monitor_id: null } })).toBe('?tab=dashboard&domain=cert.example.com')
  })

  it('activeFilters / teamOptions', () => {
    expect(activeFilters({ ...FILTER_DEFAULTS, q: 'a', team: '5' }).map(f => f.key)).toEqual(['q', 'team'])
    expect(teamOptions([{ team_id: 6, team_name: 'Takım B' }, { team_id: 5, team_name: 'Takım A' }, { team_id: null }], [{ team_id: 5, team_name: 'Takım A' }]))
      .toEqual([{ id: '5', name: 'Takım A' }, { id: '6', name: 'Takım B' }])
  })
})
