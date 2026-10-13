import { describe, it, expect } from 'vitest'
import { OLDEST_SEVERE_RED_MIN, STAT_DEFS, STAT_TYPES, SUCCESS_AMBER, SUCCESS_GREEN, statReading } from '../components/mydash/statModel.js'
import { WIDGET_CATALOG, STAT_PRESET } from '../components/mydash/myDashModel.js'
import { WIDGETS } from '../components/mydash/widgets.jsx'

/**
 * Renkli panel kutuları (2026-10-13): tek gösterge + eşik rengi. Okunamayan / yetkisiz / verisi olmayan gösterge ASLA yeşil
 * değil; eşikler tek yerde (statModel), bağlantılar süzülmüş ekranı açar.
 */
const tabs = (lv, extra = {}) => ({ http: { count: 1, unacked: 0, levels: { critical: 0, high: 0, warning: 0, other: 0, ...lv }, items: [], ...extra } })

describe('renkli panel kataloğu', () => {
  it('her tanımın bileşeni ve katalog girdisi var; katalogda "stat" grubu; duvar düzeni hepsini küçük boyutta içerir', () => {
    for (const t of STAT_TYPES) {
      expect(WIDGETS[t], t).toBeTruthy()
      expect(WIDGET_CATALOG.find((e) => e.type === t)).toMatchObject({ group: 'stat', size: 'sm' })
    }
    expect(STAT_PRESET.map((p) => p.type)).toEqual(STAT_TYPES)
    expect(STAT_PRESET.every((p) => p.size === 'sm')).toBe(true)
    expect(STAT_DEFS.length).toBeGreaterThanOrEqual(14)
  })
  it('her katalog türü sunucunun biçim kuralına uyar ([a-z_], ≤ 40) — aksi halde kayıtlı düzenden düşer / kayıt 400 alır', () => {
    for (const e of WIDGET_CATALOG) expect(e.type, e.type).toMatch(/^[a-z_]{1,40}$/)
  })
})

describe('okuma ve eşikler', () => {
  it('veri yoksa (yüklenmedi / null) gri "—", asla yeşil', () => {
    for (const t of STAT_TYPES) expect(statReading(t, null), t).toMatchObject({ value: null, tone: 'muted' })
  })

  it('alarmlar: kritik varsa kırmızı (+yüksek alt satırda); sahiplenilmemiş turuncu; yetkisiz gri', () => {
    expect(statReading('stat_alarms_critical', { total: 3, tabs: tabs({ critical: 2, high: 1 }) }))
      .toMatchObject({ value: 2, tone: 'danger', sub: ['mydash.st.plusHigh', 1], link: ['alerthistory', { level: 'CRITICAL' }] })
    expect(statReading('stat_alarms_critical', { total: 0, tabs: {} })).toMatchObject({ value: 0, tone: 'success' })
    expect(statReading('stat_alarms_critical', { visible: false, total: 0, tabs: {} })).toMatchObject({ tone: 'muted', reason: 'noaccess' })
    expect(statReading('stat_alarms_unacked', { total: 5, tabs: tabs({ warning: 5 }, { unacked: 3 }) }))
      .toMatchObject({ value: 3, tone: 'warning', link: ['alerthistory', { ack: 'unack' }] })
  })

  it('en eski kritik alarm: yoksa yeşil "yok"; 30 dk altı turuncu, üstü kırmızı; damga yoksa süre bilinmez → gri', () => {
    const now = Date.parse('2026-10-13T10:00:00Z')
    expect(statReading('stat_alarm_oldest', { total: 1, tabs: tabs({ warning: 1 }) }, now)).toMatchObject({ none: true, tone: 'success' })
    expect(statReading('stat_alarm_oldest', { total: 1, oldest_severe_at: '2026-10-13T09:50:00', tabs: tabs({ critical: 1 }) }, now))
      .toMatchObject({ value: 10, minutes: true, tone: 'warning' })
    // inceleme: en eski alarm YÜKSEK seviyeli olabilir — bağlantı seviyeyle süzmez, en eski önce sıralar (boş liste açmaz)
    expect(statReading('stat_alarm_oldest', { total: 1, oldest_severe_at: '2026-10-13T09:00:00Z', tabs: tabs({ high: 1 }) }, now))
      .toMatchObject({ value: 60, tone: 'danger', link: ['alerthistory', { sort: 'opened_asc' }] })
    expect(OLDEST_SEVERE_RED_MIN).toBe(30)
    expect(statReading('stat_alarm_oldest', { total: 1, tabs: tabs({ critical: 1 }) }, now)).toMatchObject({ tone: 'muted' })
  })

  it('izleme: çalışmayan kırmızı, gecikmiş turuncu; etkin izleme yoksa gri; başarı oranı eşikleri', () => {
    const ov = (t) => ({ totals: { active: 50, ...t }, types: [], monitors: [] })
    expect(statReading('stat_monitors_down', ov({ down: 2 }))).toMatchObject({ value: 2, tone: 'danger', link: ['monitoring', { mo_status: 'down' }] })
    expect(statReading('stat_monitors_down', ov({ down: 0 }))).toMatchObject({ value: 0, tone: 'success' })
    expect(statReading('stat_monitors_down', { totals: { active: 0 } })).toMatchObject({ tone: 'muted' })
    expect(statReading('stat_monitors_stale', ov({ stale: 1 }))).toMatchObject({ tone: 'warning' })
    expect([SUCCESS_GREEN, SUCCESS_AMBER]).toEqual([99, 95])
    expect(statReading('stat_success_rate', ov({ success_rate_window: 99.5 }))).toMatchObject({ tone: 'success', percent: true })
    expect(statReading('stat_success_rate', ov({ success_rate_window: 97 }))).toMatchObject({ tone: 'warning' })
    expect(statReading('stat_success_rate', ov({ success_rate_window: 80 }))).toMatchObject({ tone: 'danger' })
    expect(statReading('stat_success_rate', ov({}))).toMatchObject({ tone: 'muted', reason: 'nodata' })
    // inceleme: ölçülmemiş izleme varken "0" yeşil değil; hepsi ölçülmemişse veri yok
    expect(statReading('stat_monitors_down', ov({ down: 0, unknown: 3 }))).toMatchObject({ value: 0, tone: 'muted', sub: ['mydash.st.unknownMonitors', 3] })
    expect(statReading('stat_monitors_stale', ov({ stale: 0, unknown: 3 }))).toMatchObject({ tone: 'muted' })
    expect(statReading('stat_monitors_down', ov({ down: 2, unknown: 3 }))).toMatchObject({ tone: 'danger' })
    expect(statReading('stat_monitors_down', ov({ unknown: 50 }))).toMatchObject({ tone: 'muted', reason: 'nodata' })
  })

  it('sorunlu takım: çalışmayan izlemesi olan takım sayısı; izleme yoksa gri', () => {
    const m = (team_id, status) => ({ team_id, team_name: 'T' + team_id, type: 'http', status, active: true })
    expect(statReading('stat_teams_failing', { types: [{ type: 'http' }], monitors: [m(1, 'down'), m(1, 'up'), m(2, 'up'), m(3, 'stale')] }))
      .toMatchObject({ value: 1, tone: 'danger', sub: ['mydash.st.ofTeams', 3] })
    expect(statReading('stat_teams_failing', { types: [], monitors: [] })).toMatchObject({ tone: 'muted' })
    // Ölçülmemiş takım varken çalışmayan yok → gri; yalnız gecikmiş takım "çalışmayan" sayılmaz → yeşil
    expect(statReading('stat_teams_failing', { types: [{ type: 'http' }], monitors: [m(1, 'up'), m(2, 'unknown')] })).toMatchObject({ value: 0, tone: 'muted' })
    expect(statReading('stat_teams_failing', { types: [{ type: 'http' }], monitors: [m(1, 'up'), m(2, 'stale')] })).toMatchObject({ value: 0, tone: 'success' })
  })

  it('hizmet / olay: kesinti kırmızı, yalnız yavaşlık turuncu; olaylar okunamadıysa gri', () => {
    const sp = (states) => ({ services: states.map((state, i) => ({ key: String(i), state })) })
    expect(statReading('stat_service_outages', sp(['operational', 'partial_outage', 'degraded']))).toMatchObject({ value: 1, tone: 'danger' })
    expect(statReading('stat_service_outages', sp(['operational', 'degraded']))).toMatchObject({ value: 0, tone: 'warning' })
    expect(statReading('stat_service_outages', sp([]))).toMatchObject({ tone: 'muted' })
    // inceleme: yeşil yalnız BÜTÜN hizmetler çalışırken; bakım → bilgi; veri yok → gri
    expect(statReading('stat_service_outages', sp(['operational', 'operational']))).toMatchObject({ value: 0, tone: 'success' })
    expect(statReading('stat_service_outages', sp(['operational', 'maintenance']))).toMatchObject({ value: 0, tone: 'info' })
    expect(statReading('stat_service_outages', sp(['no_data', 'no_data']))).toMatchObject({ value: 0, tone: 'muted' })
    expect(statReading('stat_service_outages', sp(['operational', 'no_data']))).toMatchObject({ tone: 'muted' })
    expect(statReading('stat_incidents_severe', { incidents: { active: [{ id: 1, severity: 'HIGH' }], active_visible: 1 } })).toMatchObject({ value: 1, tone: 'danger' })
    expect(statReading('stat_incidents_severe', { incidents: { active: [], active_unavailable: true } })).toMatchObject({ tone: 'muted' })
  })

  it('7/24: 15 dk aşan bekleyen kırmızı; konsol listesi kesik ve gecikmiş yoksa gri (yeşil değil)', () => {
    const now = Date.parse('2026-10-13T10:00:00Z')
    const item = (id, min) => ({ id, call_count: 0, noc: { sent_at: new Date(now - min * 60000).toISOString() } })
    expect(statReading('stat_noc_overdue', { total: 2, items: [item(1, 40), item(2, 3)] }, now)).toMatchObject({ value: 1, tone: 'danger' })
    expect(statReading('stat_noc_overdue', { total: 0, items: [] }, now)).toMatchObject({ value: 0, tone: 'success' })
    expect(statReading('stat_noc_overdue', { total: 0, items: [], truncated: true }, now)).toMatchObject({ tone: 'muted', reason: 'partial' })
  })

  it('sertifikalar: dolmuş / 7 gün kırmızı, 30 gün / tarama hatası turuncu; sertifika yoksa gri', () => {
    const st = { total_certificates: 40, expired: 1, expiring_in_7_days: 0, expiring_in_30_days: 3, error_count: 2 }
    expect(statReading('stat_certs_expired', st)).toMatchObject({ value: 1, tone: 'danger', link: ['all', { c_win: 'expired' }] })
    expect(statReading('stat_certs_week', st)).toMatchObject({ value: 0, tone: 'success' })
    expect(statReading('stat_certs_month', st)).toMatchObject({ value: 3, tone: 'warning' })
    expect(statReading('stat_cert_errors', st)).toMatchObject({ value: 2, tone: 'warning', link: ['all', { c_st: 'error' }] })
    expect(statReading('stat_certs_expired', { total_certificates: 0 })).toMatchObject({ tone: 'muted' })
  })
})
