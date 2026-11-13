import { describe, it, expect } from 'vitest'
import {
  laneOf, clientFacet, summarize, buildTimeline, serverParams, statusOf, filtersFromUrl, filtersToUrl, sortFromUrl,
  incidentHref, activeFilters, teamOptions, FILTER_DEFAULTS, parseLevels, summaryFromServer, minutesText, isSelectable,
  canCommentOn, canDeleteComment, canNotifyOn, evidenceQuery, asAlert,
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

  it('statusOf / serverParams (2026-11-13): süzgeçler SUNUCUYA gider; "Çözüldü (24 sa)" ÇÖZÜLME anıyla (since değil), tarih gün olarak', () => {
    expect(statusOf('ack')).toBe('ongoing')
    expect(statusOf('critical')).toBe('ongoing')
    expect(statusOf('resolved24')).toBe('resolved')
    expect(statusOf('')).toBe('all')
    const now = Date.parse('2026-09-26T09:30:00Z')
    const p = serverParams({ ...FILTER_DEFAULTS, stat: 'resolved24', level: 'HIGH', team: '5', since: '2026-01-01' }, { by: 'started', dir: 'asc' }, now)
    expect(p).toEqual({ status: 'resolved', rootCause: '', q: '', since: '2026-01-01', until: '', sort: 'started', dir: 'asc',
      level: 'HIGH', teamId: '5', resolvedSince: '2026-09-25T09:30:00' })
    expect(serverParams(FILTER_DEFAULTS, { by: '', dir: 'desc' }, now).sort).toBeUndefined()
    // Varsayılan istek eski biçimde (sunucu süzgeci anahtarı yok)
    expect(Object.keys(serverParams(FILTER_DEFAULTS, { by: '', dir: 'desc' }, now)).sort())
      .toEqual(['dir', 'q', 'rootCause', 'since', 'sort', 'status', 'until'])
    // Kart süzgeçleri: onaylı / takımsız / takımım / tekrarlayan
    expect(serverParams({ ...FILTER_DEFAULTS, stat: 'ack' }, null, now)).toEqual(expect.objectContaining({ status: 'ongoing', acknowledged: 'true' }))
    expect(serverParams({ ...FILTER_DEFAULTS, stat: 'unassigned' }, null, now).teamless).toBe('true')
    expect(serverParams({ ...FILTER_DEFAULTS, stat: 'mine' }, null, now).mine).toBe('true')
    expect(serverParams({ ...FILTER_DEFAULTS, recurring: '1' }, null, now).recurring).toBe('true')
  })

  it('serverParams: "Kritik" kartı önem süzgeciyle KESİŞİR — kesişim boşsa eşleşmeyen "-" (süzgeç yok sayılmaz)', () => {
    const now = Date.parse('2026-09-26T09:30:00Z')
    expect(serverParams({ ...FILTER_DEFAULTS, stat: 'critical' }, null, now).level).toBe('CRITICAL')
    expect(serverParams({ ...FILTER_DEFAULTS, stat: 'critical', level: 'CRITICAL,HIGH' }, null, now).level).toBe('CRITICAL')
    expect(serverParams({ ...FILTER_DEFAULTS, stat: 'critical', level: 'WARNING' }, null, now).level).toBe('-')
    expect(serverParams({ ...FILTER_DEFAULTS, level: 'CRITICAL,HIGH' }, null, now).level).toBe('CRITICAL,HIGH')
    expect(parseLevels('high, critical ,bogus')).toEqual(['CRITICAL', 'HIGH'])
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

  it('summarize (eski sunucu yedeği): açık toplam sunucudan, türetilmişler örnekten; okunamayan 24 sa ve takımsız oturumun "Takımım"ı null (0 değil)', () => {
    const s = summarize([open, acked], 2, 4, 5)
    expect(s).toEqual({ open: 2, ack: 1, resolved24: 4, critical: 1, unassigned: 1, mine: 1, exact: true, sampled: 2 })
    expect(summarize([open], 300, null, null)).toEqual(expect.objectContaining({ open: 300, resolved24: null, mine: null, exact: false, sampled: 1 }))
  })

  it('summaryFromServer: kesin özet; tanınmayan yanıt (eski sunucu: dizi / başarısız) null → çağıran eski yola düşer', () => {
    expect(summaryFromServer({ success: true, data: [] })).toBeNull()
    expect(summaryFromServer({ success: false, error: 'x' })).toBeNull()
    expect(summaryFromServer(null)).toBeNull()
    const s = summaryFromServer({ success: true, data: {
      open: 4, acknowledged: 1, critical: 2, teamless: 0, mine: null, resolved_24h: 3,
      mtta_24h_minutes: null, mtta_7d_minutes: 12.5, mttr_24h_minutes: 30, mttr_7d_minutes: 95, mtta_sample_7d: 6, mttr_sample_7d: 9,
      timing_capped: false, longest_open: { id: 7, name: 'api', started_at: '2026-11-12T08:00:00', alert_level: 'HIGH' },
      team_options: [{ id: 5, name: 'Takım A', count: 3 }, { id: 9, name: null }],
    } })
    expect(s).toEqual(expect.objectContaining({ open: 4, ack: 1, critical: 2, unassigned: 0, mine: null, resolved24: 3, exact: true, sampled: null,
      mtta24: null, mtta7: 12.5, mttr24: 30, mttr7: 95, timingKnown: true }))
    expect(s.longestOpen.id).toBe(7)
    expect(s.teamOptions).toEqual([{ id: '5', name: 'Takım A', count: 3 }])   // adsız seçenek ham kimlikle basılmaz
  })

  it('minutesText: okunur süre; bilinmeyen null', () => {
    const t = (k) => ({ 'incov.unit.min': 'dk', 'incov.unit.hour': 'sa', 'incov.unit.day': 'g' })[k] ?? k
    expect(minutesText(null, t)).toBeNull()
    expect(minutesText(0.4, t)).toBe('<1 dk')
    expect(minutesText(72, t)).toBe('1 sa 12 dk')
    expect(minutesText(1500, t)).toBe('1 g 1 sa')
  })

  it('satır izinleri: seçilebilir = açık + işlem hakkı; yorum / yorum silme / yeniden bildir sunucu bayrağından', () => {
    expect(isSelectable(open)).toBe(true)
    expect(isSelectable(done)).toBe(false)
    expect(isSelectable({ ...open, can_act: false })).toBe(false)
    expect(isSelectable({ ...open, can_manage: false })).toBe(false)
    // 7/24 operatörü başka ekibin olayına yorum yazar
    expect(canCommentOn({ ...open, can_manage: false, can_comment: true })).toBe(true)
    expect(canCommentOn({ ...open, can_comment: false })).toBe(false)
    expect(canCommentOn(open)).toBe(true)   // alan yok → eski kural
    expect(canDeleteComment({ can_delete: false }, open)).toBe(false)
    expect(canDeleteComment({ can_delete: true }, { ...open, can_act: false })).toBe(true)
    expect(canDeleteComment({}, open)).toBe(true)
    expect(canNotifyOn({ ...open, can_notify: true })).toBe(true)
    expect(canNotifyOn({ ...open })).toBe(false)
    expect(canNotifyOn({ ...done, can_notify: true })).toBe(false)
  })

  it('evidenceQuery: izleme kimliği + tür → Aktivite sorgusu (açılıştan 15 dk önce, çözümden 1 dk sonra); sertifika olayı null', () => {
    const inc = { status: 'resolved', started_at: '2026-11-12T10:00:00', resolved_at: '2026-11-12T11:00:00', monitor: { type: 'http', monitor_id: 42 } }
    expect(evidenceQuery(inc)).toEqual({ monitor: 'HTTP:42', from: '2026-11-12T09:45:00', to: '2026-11-12T11:01:00', status: 'ERROR,TIMEOUT' })
    expect(evidenceQuery({ ...inc, status: 'ongoing' })).toEqual({ monitor: 'HTTP:42', from: '2026-11-12T09:45:00', status: 'ERROR,TIMEOUT' })
    expect(evidenceQuery({ ...inc, monitor: { type: 'cert', monitor_id: null } })).toBeNull()
    expect(evidenceQuery({ ...inc, monitor: { type: 'http', monitor_id: null } })).toBeNull()
    expect(evidenceQuery({ ...inc, monitor: { type: 'dbquery', monitor_id: 3 } }).monitor).toBe('DBQUERY:3')
  })

  it('asAlert: Alarm Geçmişi parçaları için created_at + resolved', () => {
    expect(asAlert(open)).toEqual(expect.objectContaining({ created_at: open.started_at, resolved: false }))
    expect(asAlert(done).resolved).toBe(true)
    expect(asAlert(null)).toBeNull()
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
    const read = (k) => ({ stat: 'critical', type: 'HTTP_DOWN', q: 'x', from: '2026-01-01', level: 'BOGUS', team: '5', view: 'list', sort: 'started:asc', incident: '7', inc_rec: '1' })[k] ?? null
    expect(filtersFromUrl(read)).toEqual({ stat: 'critical', rootCause: 'HTTP_DOWN', q: 'x', since: '2026-01-01', until: '', level: '', team: '5', recurring: '1' })
    expect(filtersFromUrl((k) => ({ level: 'high,critical', team: 'abc', inc_rec: 'yes' })[k] ?? null))
      .toEqual(expect.objectContaining({ level: 'CRITICAL,HIGH', team: '', recurring: '' }))
    expect(filtersFromUrl((k) => (k === 'stat' ? 'bogus' : null)).stat).toBe('')
    expect(sortFromUrl(read)).toEqual({ by: 'started', dir: 'asc' })
    expect(sortFromUrl(() => 'nope:asc')).toEqual({ by: '', dir: 'desc' })
    const out = filtersToUrl({ ...FILTER_DEFAULTS, stat: 'open' }, 'board', { by: '', dir: 'desc' })
    expect(out).toEqual({ stat: 'open', type: null, q: null, from: null, to: null, level: null, team: null, inc_rec: null, view: null, sort: null })
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
