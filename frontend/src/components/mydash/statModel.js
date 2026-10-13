/**
 * Renkli panel kutuları (2026-10-13, kullanıcı isteği: "renkli panel adetlerini arttıralım, daha çok izleme yapılacak kutu
 * olsun") — saf model. Her kutu TEK gösterge: büyük sayı + eşik rengi (Grafana "stat panel"). Veri mevcut uçlardan; aynı
 * kaynağı okuyan kutular tek istek paylaşır (useWidgetData). Bilinmeyen / okunamayan / yetkisiz veri ASLA yeşil değil.
 *
 * Okuma sonucu: `{ value, tone, text?, sub?: [key, ...args], link?: [tab, params] }`; veri yoksa `{ value: null, tone: 'muted',
 * reason }` (`reason`: noaccess | nodata | partial).
 */
import { zonedMs } from '../../utils/relativeTime.js'
import { alertsSummary, certSummary, incidentsSummary, lightSummary, nocUncalledSummary, teamTypeMatrix } from './myDashModel.js'

export { STAT_DEFS, STAT_TYPES, statDef } from './statDefs.js'

const n = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0)
const NONE = (reason) => ({ value: null, tone: 'muted', reason })

/** Başarı oranı eşikleri (%) — 24 saatlik izleme başarısı. */
export const SUCCESS_GREEN = 99
export const SUCCESS_AMBER = 95
/** En eski kritik / yüksek açık alarm: bu kadar dakikayı geçince kırmızı (altı turuncu). */
export const OLDEST_SEVERE_RED_MIN = 30


/** Sayaçtan ton: 0 → yeşil, aksi verilen sorun tonu. */
const countTone = (count, problemTone) => (count > 0 ? problemTone : 'success')

/** Bir kutunun okuması. `data` = kaynağın yanıtı (`useWidgetData().data`); `nowMs` yaş hesabı için. */
export function statReading(type, data, nowMs = Date.now()) {
  if (data == null) return NONE('nodata')
  switch (type) {
    case 'stat_alarms_critical': {
      if (data.visible === false) return NONE('noaccess')
      const a = alertsSummary(data, 0)
      return { value: a.critical, tone: countTone(a.critical, 'danger'), sub: a.high ? ['mydash.st.plusHigh', a.high] : null,
        link: ['alerthistory', { level: 'CRITICAL' }] }
    }
    case 'stat_alarms_unacked': {
      if (data.visible === false) return NONE('noaccess')
      const a = alertsSummary(data, 0)
      return { value: a.unacked, tone: countTone(a.unacked, 'warning'), sub: a.total ? ['mydash.st.ofOpen', a.total] : null,
        link: ['alerthistory', { ack: 'unack' }] }
    }
    case 'stat_alarm_oldest': {
      const l = lightSummary(data)
      if (l.hidden) return NONE('noaccess')
      if (l.severe === 0) return { value: 0, none: true, tone: 'success', sub: ['mydash.st.noSevere'], link: ['alerthistory'] }
      // Eski sunucu en eski damgayı göndermezse süre bilinmez — renk verilmez (sayı yine gösterilir)
      const ms = zonedMs(l.oldestSevere)
      if (!Number.isFinite(ms)) return { value: l.severe, tone: 'muted', sub: ['mydash.st.severeOpen', l.severe], link: ['alerthistory'] }
      const min = Math.max(0, Math.floor((nowMs - ms) / 60000))
      // Bağlantı seviyeyle SÜZMEZ (en eski alarm yüksek seviyeli olabilir — kritik süzgeci boş liste açıyordu); en eski önce
      return { value: min, minutes: true, tone: min >= OLDEST_SEVERE_RED_MIN ? 'danger' : 'warning',
        sub: ['mydash.st.severeOpen', l.severe], link: ['alerthistory', { sort: 'opened_asc' }] }
    }
    case 'stat_monitors_down':
    case 'stat_monitors_stale':
    case 'stat_success_rate':
    case 'stat_teams_failing': {
      const t = data.totals || {}
      const active = n(t.active)
      const unknown = n(t.unknown)
      // Ölçülmemiş izleme varken "sorun yok" yeşili verilmez (monitorSummary / healthTone kuralı — inceleme)
      const calmTone = (count, problemTone) => (count > 0 ? problemTone : unknown > 0 ? 'muted' : 'success')
      if (type === 'stat_monitors_down') {
        if (active === 0 || unknown >= active) return NONE('nodata')
        return { value: n(t.down), tone: calmTone(n(t.down), 'danger'), sub: unknown ? ['mydash.st.unknownMonitors', unknown] : ['mydash.st.ofActive', active],
          link: ['monitoring', { mo_status: 'down' }] }
      }
      if (type === 'stat_monitors_stale') {
        if (active === 0 || unknown >= active) return NONE('nodata')
        return { value: n(t.stale), tone: calmTone(n(t.stale), 'warning'), sub: unknown ? ['mydash.st.unknownMonitors', unknown] : ['mydash.st.ofActive', active],
          link: ['monitoring', { mo_status: 'stale' }] }
      }
      if (type === 'stat_success_rate') {
        const rate = t.success_rate_window == null ? NaN : Number(t.success_rate_window)
        if (!Number.isFinite(rate)) return NONE('nodata')
        return { value: rate, percent: true, tone: rate >= SUCCESS_GREEN ? 'success' : rate >= SUCCESS_AMBER ? 'warning' : 'danger',
          sub: ['mydash.st.successThresholds', SUCCESS_GREEN, SUCCESS_AMBER], link: ['monitoring'] }
      }
      const m = teamTypeMatrix(data, 100000)
      if (m.totalTeams === 0) return NONE('nodata')
      // Çalışmayan izlemesi olan takım sayısı (takımsız izlemeler de bir "sahip" grubu olarak sayılır)
      const failing = m.rows.filter((r) => r.live && r.worst === 'danger').length
      // Çalışmayan yoksa: ölçülmemiş (gri) takım varken yeşil verilmez — gecikmiş (turuncu) takım "çalışmayan" değildir
      const unknownTeam = m.rows.some((r) => r.live && r.worst === 'muted')
      return { value: failing, tone: failing > 0 ? 'danger' : unknownTeam ? 'muted' : 'success', sub: ['mydash.st.ofTeams', m.totalTeams],
        link: ['monitoring', { mo_status: 'down' }] }
    }
    case 'stat_service_outages': {
      const services = Array.isArray(data.services) ? data.services : []
      if (services.length === 0) return NONE('nodata')
      const outage = services.filter((s) => s?.state === 'partial_outage' || s?.state === 'major_outage').length
      const degraded = services.filter((s) => s?.state === 'degraded').length
      const operational = services.filter((s) => s?.state === 'operational').length
      const maintenance = services.filter((s) => s?.state === 'maintenance').length
      // Yeşil yalnız BÜTÜN hizmetler çalışırken (statusSummary.allOk); bakımda olan → bilgi; veri yok → gri (inceleme)
      const tone = outage > 0 ? 'danger' : degraded > 0 ? 'warning' : operational === services.length ? 'success' : maintenance > 0 ? 'info' : 'muted'
      return { value: outage, tone,
        sub: degraded ? ['mydash.st.degraded', degraded] : ['mydash.st.ofServices', services.length], link: ['status'] }
    }
    case 'stat_incidents_severe': {
      const s = incidentsSummary(data)
      if (s.unavailable) return NONE('nodata')
      return { value: s.severe, tone: countTone(s.severe, 'danger'), sub: s.visible ? ['mydash.st.ofOpenIncidents', s.visible] : null,
        link: ['incident-history', { ih_open: 'true' }] }
    }
    case 'stat_noc_overdue': {
      const s = nocUncalledSummary(data, nowMs)
      if (s.partial && s.overdue === 0) return NONE('partial')
      return { value: s.overdue, atLeast: s.overdueAtLeast, tone: countTone(s.overdue, 'danger'),
        sub: s.total ? ['mydash.st.waitingCalls', s.total] : null,
        link: ['noc', { n_view: 'console', n_cnoc: 'sent', n_ccall: 'no', n_cst: 'open' }] }
    }
    case 'stat_certs_expired':
    case 'stat_certs_week':
    case 'stat_certs_month':
    case 'stat_cert_errors': {
      const c = certSummary(data, 0)
      if (c.total === 0) return NONE('nodata')
      if (type === 'stat_certs_expired') return { value: c.expired, tone: countTone(c.expired, 'danger'), sub: ['mydash.st.ofCerts', c.total], link: ['all', { c_win: 'expired' }] }
      if (type === 'stat_certs_week') return { value: c.exp7, tone: countTone(c.exp7, 'danger'), sub: ['mydash.st.ofCerts', c.total], link: ['all', { c_win: '7' }] }
      if (type === 'stat_certs_month') return { value: c.exp30, tone: countTone(c.exp30, 'warning'), sub: ['mydash.st.ofCerts', c.total], link: ['all', { c_win: '30' }] }
      return { value: c.errors, tone: countTone(c.errors, 'warning'), sub: ['mydash.st.ofCerts', c.total], link: ['all', { c_st: 'error' }] }
    }
    default:
      return NONE('nodata')
  }
}
