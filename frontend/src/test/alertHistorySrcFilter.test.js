import { describe, it, expect } from 'vitest'
import { filtersFromUrl, listParams, activeAlertFilters, FILTER_DEFAULTS, isSrcKey, SRC_KEYS } from '../components/admin/alerts/alertHistoryModel.js'
import { MONITOR_ALERT_TYPES } from '../utils/monitorAlertTypes.js'

/**
 * Alarm Geçmişi kategori süzgeci (URL `src`, 2026-09-30): İzleme menüsündeki rozet Alarm Geçmişi'ni o izleme türüne
 * süzülmüş açar — sunucuya türün TÜM alarm tipleri (`alertTypes`) gider, çip görünür, geçersiz anahtar düşer.
 */
describe('Alarm Geçmişi — kategori süzgeci (src)', () => {
  it('URL src geçerli izleme türü ise süzgece girer; bilinmeyen değer düşer', () => {
    expect(filtersFromUrl((k) => (k === 'src' ? 'scripted' : '')).src).toBe('scripted')
    expect(filtersFromUrl((k) => (k === 'src' ? 'bogus' : '')).src).toBe('')
    expect(SRC_KEYS).toEqual(Object.keys(MONITOR_ALERT_TYPES))
    expect(isSrcKey('http')).toBe(true)
    expect(isSrcKey('')).toBe(false)
  })

  it('listParams: src → alertTypes = türün tüm alarm tipleri; gömülü types verilmişse o kazanır', () => {
    const p = listParams({ tab: 'open', filters: { ...FILTER_DEFAULTS, src: 'scripted' }, page: 0, pageSize: 20, domain: null, typesParam: null })
    expect(p.alertTypes).toBe('SCRIPTED_FAIL,SCRIPTED_SLOW')
    expect(p.resolved).toBe('false')
    const q = listParams({ tab: 'open', filters: { ...FILTER_DEFAULTS, src: 'scripted' }, page: 0, pageSize: 20, domain: null, typesParam: 'HTTP_DOWN' })
    expect(q.alertTypes).toBe('HTTP_DOWN')
  })

  it('etkin çipler: src çipi ilk sırada, × süzgeci sıfırlar', () => {
    const chips = activeAlertFilters({ ...FILTER_DEFAULTS, src: 'http', level: 'CRITICAL' }, 'open')
    expect(chips[0]).toEqual({ key: 'src', value: 'http', patch: { src: '' } })
    expect(chips.map((c) => c.key)).toEqual(['src', 'level'])
  })
})
