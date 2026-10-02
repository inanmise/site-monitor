import { describe, it, expect } from 'vitest'
import {
  STATE_META, STATE_ORDER, BANNER_KEY, LEGEND_KEY, stateMeta, stateRank, isProblem, compareServices, groupByTeam,
  defaultOpenGroups, sortMonitors, worstState, serviceLabel, pctText, severityMeta, isStatusPayload, shortTime,
} from '../components/statuspage/statusPageModel.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

// Kurum içi Durum Sayfası (2026-10-01) — saf model: durum tanımları, Türkçe harmanlamalı sıralama, gruplama.
const t = (k, ...a) => a.reduce((s, v, i) => s.split(`{${i}}`).join(String(v)), EN[k] ?? k)

const svc = (key, name, state, extra = {}) => ({ key, name, state, ungrouped: false, team_id: 1, team_name: 'A', ...extra })

describe('statusPageModel — durum tanımları', () => {
  it('altı durumun her biri ikon + etiket + rozet taşır; sıra sunucu STATE_ORDER ile aynı ağırlıkta', () => {
    expect(Object.keys(STATE_META).sort()).toEqual([...STATE_ORDER].sort())
    for (const k of STATE_ORDER) {
      expect(STATE_META[k].Icon, k).toBeTruthy()
      expect(TR[STATE_META[k].labelKey], k).toBeTruthy()
      expect(EN[STATE_META[k].labelKey], k).toBeTruthy()
      expect(TR[BANNER_KEY[k]] && EN[BANNER_KEY[k]], k).toBeTruthy()
      expect(TR[LEGEND_KEY[k]] && EN[LEGEND_KEY[k]], k).toBeTruthy()
    }
    // no_data < operational < maintenance < degraded < partial < major (StatusPageService.STATE_ORDER)
    expect(['no_data', 'operational', 'maintenance', 'degraded', 'partial_outage', 'major_outage'].map(stateRank)).toEqual([0, 1, 2, 3, 4, 5])
    expect(stateMeta('bogus')).toBe(STATE_META.no_data)
    expect(['degraded', 'partial_outage', 'major_outage'].every(isProblem)).toBe(true)
    expect(['operational', 'maintenance', 'no_data', undefined].some(isProblem)).toBe(false)
  })

  it('önem rozeti, yüzde metni, kısa zaman, yük doğrulaması', () => {
    expect(severityMeta('critical').state).toBe('major_outage')
    expect(severityMeta('LOW').labelKey).toBe('sp.sev.LOW')
    expect(severityMeta('???').labelKey).toBeNull()
    expect(pctText(99.95)).toBe('99.95%')            // test ortamı İngilizce (dateLocale varsayılanı)
    expect(pctText(null)).toBeNull()
    expect(shortTime(null, 'en-GB')).toBe('—')
    expect(shortTime('2026-10-01T09:30:00', 'en-GB')).toMatch(/Oct/)
    expect(isStatusPayload({ overall: {} })).toBe(true)
    expect(isStatusPayload([])).toBe(false)          // lazy-tabs vekili `data: []`
    expect(isStatusPayload(null)).toBe(false)
  })
})

describe('statusPageModel — sıralama ve gruplama', () => {
  it('hizmetler: sorunlular önce (büyük → kısmi → bozulmuş), sonra ad A→Z (Türkçe), grupsuz en sonda', () => {
    const list = [
      svc('1|zeta', 'Zeta', 'operational'),
      svc('1|', null, 'operational', { ungrouped: true }),
      svc('1|çağrı', 'Çağrı', 'operational'),
      svc('1|deniz', 'Deniz', 'maintenance'),
      svc('1|ödeme', 'Ödeme', 'degraded'),
      svc('1|mobil', 'Mobil', 'major_outage'),
      svc('1|kart', 'Kart', 'partial_outage'),
      svc('1|cari', 'Cari', 'operational'),
    ]
    const sorted = [...list].sort((a, b) => compareServices(a, b, t)).map((s) => serviceLabel(s, t))
    // Sorunlular önce; kalanlar Türkçe harmanla (C < Ç < D … Z); grupsuz "Other monitors" en sonda
    expect(sorted).toEqual(['Mobil', 'Kart', 'Ödeme', 'Cari', 'Çağrı', 'Deniz', 'Zeta', 'Other monitors'])
  })

  it('takım grupları: sorunlu gruplar önce (en kötü durum ağır olan önce), sonra takım adı A→Z (Türkçe), takımsız en sonda', () => {
    const services = [
      svc('2|x', 'X', 'operational', { team_id: 2, team_name: 'Dijital Kanallar' }),
      svc('3|x', 'X', 'operational', { team_id: 3, team_name: 'Çağrı Merkezi' }),
      svc('4|x', 'X', 'degraded', { team_id: 4, team_name: 'Bankacılık' }),
      svc('5|x', 'X', 'operational', { team_id: 5, team_name: 'Altyapı' }),
      svc('-|x', 'X', 'operational', { team_id: null, team_name: null }),
      svc('6|a', 'A', 'operational', { team_id: 6, team_name: 'Ödeme Sistemleri' }),
      svc('6|b', 'B', 'partial_outage', { team_id: 6, team_name: 'Ödeme Sistemleri' }),
    ]
    const groups = groupByTeam(services, t)
    expect(groups.map((g) => g.label)).toEqual(['Ödeme Sistemleri', 'Bankacılık', 'Altyapı', 'Çağrı Merkezi', 'Dijital Kanallar', 'No team'])
    const pay = groups[0]
    expect(pay).toMatchObject({ key: '6', state: 'partial_outage', problems: 1, total: 2 })
    expect(pay.services.map((s) => s.key)).toEqual(['6|b', '6|a'])   // grup içinde de sorunlu önce
    expect(worstState([])).toBe('no_data')
    expect(defaultOpenGroups(groups)).toEqual(['6', '4'])            // yalnız sorunlu gruplar açık
    expect(defaultOpenGroups([groups[2]])).toEqual(['5'])             // tek grup her zaman açık
    expect(defaultOpenGroups([])).toEqual([])
  })

  it('izleme listesi: düşük → bozulmuş → bakım → veri yok → ayakta → duraklatılmış; eşitlikte ad (Türkçe)', () => {
    const ms = sortMonitors([
      { name: 'z', status: 'up' }, { name: 'p', status: 'paused' }, { name: 'ç', status: 'down' },
      { name: 'c', status: 'down' }, { name: 'm', status: 'maintenance' }, { name: 'd', status: 'degraded' }, { name: 'u', status: 'unknown' },
    ])
    expect(ms.map((m) => m.name)).toEqual(['c', 'ç', 'd', 'm', 'u', 'z', 'p'])
  })
})
