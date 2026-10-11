import { describe, it, expect } from 'vitest'
import {
  TABLE_MIN_PX, activeChips, chartPoints, pickLayout, removeChip, seriesTotal,
} from '../components/activity/activityModel.js'

/** Aktivite sayfası saf modeli (2026-10-11): yerleşim kararı, çizelge noktaları (İstanbul saati), süzgeç çipleri. */
describe('activityModel', () => {
  it('pickLayout: ölçülen kap genişliği belirler; ölçüm yoksa görünüm alanı', () => {
    expect(pickLayout(TABLE_MIN_PX - 1, false)).toBe('cards')
    expect(pickLayout(TABLE_MIN_PX, true)).toBe('table')
    expect(pickLayout(0, true)).toBe('cards')
    expect(pickLayout(0, false)).toBe('table')
  })

  it('chartPoints: saatlik UTC anahtarı İstanbul saatine, günlük anahtar aynı güne biçimlenir', () => {
    const hourly = chartPoints({ bucket: 'hour', points: [{ t: '2026-10-11T08:00:00Z', success: 2, warning: 0, error: 1, unknown: 0 }] }, 'tr')
    expect(hourly[0].label).toBe('11:00')
    expect(hourly[0].success).toBe(2)
    expect(hourly[0].error).toBe(1)
    const daily = chartPoints({ bucket: 'day', points: [{ t: '2026-10-05', success: 1 }] }, 'en')
    expect(daily[0].label).toMatch(/5 Oct/)
    expect(daily[0].warning).toBe(0)
    expect(chartPoints(null)).toEqual([])
    expect(seriesTotal(hourly)).toBe(3)
  })

  it('activeChips: türler + varsayılan dışı aralık + arama; durum çipi yok; removeChip geri alır', () => {
    const f = { types: ['HTTP', 'CERT'], status: 'ERROR', range: '7d', q: 'web' }
    const chips = activeChips(f)
    expect(chips.map((c) => c.key)).toEqual(['type:HTTP', 'type:CERT', 'range', 'q'])
    expect(activeChips({ types: [], status: 'ERROR', range: '24h', q: '' })).toEqual([])
    expect(removeChip(f, chips[0]).types).toEqual(['CERT'])
    expect(removeChip(f, chips[2]).range).toBe('24h')
    expect(removeChip(f, chips[3]).q).toBe('')
    expect(removeChip(f, chips[3]).status).toBe('ERROR')
  })
})
