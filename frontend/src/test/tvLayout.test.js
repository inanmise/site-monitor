import { describe, it, expect } from 'vitest'
import {
  TV_CELL, TV_DETAIL_MIN_H, TV_MAX_COLS, TV_MAX_ROWS, cellSize, fillTrailingGaps, fitPage, layoutTv, normalizeDensity, packTv,
  tvGrid, tvSpan,
} from '../components/mydash/tvLayout.js'
import { tvDetail } from '../components/mydash/myDashModel.js'

/**
 * Kişisel Panom TV yerleşimi (2026-10-13, kullanıcı isteği: "TV modunda bir ekranda daha fazla kutu, büyüklüğü ona göre,
 * boş alanlar kullanılsın"): yoğunluğa göre ızgara, iki boyutlu ilk-uyan dizilim (açık sayfaların ilkine), sayfa başına en
 * büyük hücreli ızgara, ayrıntılı kutunun satır açıklığı, sınırlar.
 */
const W = 1872
const H = 968   // 1920 × 1080 ekran − üst çubuk − iç boşluk
const stat = (n, size = 'sm') => Array.from({ length: n }, (_, i) => ({ id: `s${i}`, size, detail: false }))
const shape = (pages) => pages.map((p) => `${p.cols}x${p.rows}(${p.placed.length})`)

describe('tvLayout', () => {
  it('yoğunluğa göre ızgara: Rahat = eski 4 × 2, Sık 5 × 3, Çok sık 7 × 3; sınırlar', () => {
    expect(tvGrid(W, H, 'comfortable')).toEqual({ cols: 4, rows: 2 })
    expect(tvGrid(W, H, 'compact')).toEqual({ cols: 5, rows: 3 })
    expect(tvGrid(W, H, 'dense')).toEqual({ cols: 7, rows: 3 })
    expect(tvGrid(W, H, 'bilinmeyen')).toEqual(tvGrid(W, H, 'compact'))
    expect(tvGrid(0, 0, 'dense')).toEqual({ cols: 1, rows: 1 })
    expect(tvGrid(100_000, 100_000, 'dense')).toEqual({ cols: TV_MAX_COLS, rows: TV_MAX_ROWS })
    expect(normalizeDensity(undefined)).toBe('compact')
    expect(Object.keys(TV_CELL)).toEqual(['comfortable', 'compact', 'dense'])
  })

  it('daha çok kutu: 14 renkli panel Sık\'ta TEK sayfa (eski düzende 2); Çok sık\'ta 21 panel tek sayfa', () => {
    expect(shape(layoutTv(stat(14), W, H, 'comfortable'))).toEqual(['4x2(8)', '3x2(6)'])
    expect(shape(layoutTv(stat(14), W, H, 'compact'))).toEqual(['5x3(14)'])
    expect(shape(layoutTv(stat(21), W, H, 'dense'))).toEqual(['7x3(21)'])
    expect(layoutTv(stat(24), W, H, 'dense').map((p) => p.placed.length)).toEqual([21, 3])
  })

  it('boş alan kullanılır: az kutulu sayfa büyük hücreli ızgara seçer (5 panel → 3 × 2, 1 panel → tam ekran)', () => {
    expect(shape(layoutTv(stat(5), W, H, 'compact'))).toEqual(['3x2(5)'])
    expect(shape(layoutTv(stat(1), W, H, 'dense'))).toEqual(['1x1(1)'])
    const cell = cellSize(W, H, 3, 2)
    expect(cell.w).toBeGreaterThan(cellSize(W, H, 5, 3).w)
    // 4K: 24 panel 12 × 7 ızgaraya sıkışmaz, 6 × 4 büyük hücreyle ekranı doldurur
    expect(shape(layoutTv(stat(24), 3792, 2048, 'compact'))).toEqual(['6x4(24)'])
  })

  it('Sık / Çok sık\'ta renkli panel tek hücre; Rahat\'ta kullanıcının boyu; ayrıntılı kutu okunur yükseklik kadar satır', () => {
    expect(tvSpan({ size: 'md', detail: false }, 5, 3, 312, 'compact')).toEqual({ c: 1, r: 1 })
    expect(tvSpan({ size: 'md', detail: false }, 4, 2, 476, 'comfortable')).toEqual({ c: 2, r: 1 })
    expect(tvSpan({ size: 'lg', detail: false }, 4, 2, 476, 'comfortable')).toEqual({ c: 4, r: 1 })
    expect(tvSpan({ size: 'lg', detail: true }, 8, 4, 230, 'dense')).toEqual({ c: 4, r: 2 })
    expect(tvSpan({ size: 'md', detail: true }, 5, 3, 312, 'compact')).toEqual({ c: 2, r: Math.ceil(TV_DETAIL_MIN_H / 312) })
    expect(tvSpan({ size: 'sm', detail: true }, 5, 1, 100, 'compact').r, 'ızgaradan uzun olmaz').toBe(1)
  })

  it('ilk-uyan: sonraki kutu önceki sayfadaki boşluğa girer (sıralı kesme tek kutuluk sayfa bırakıyordu)', () => {
    const span = (it) => ({ c: it.c, r: 1 })
    const items = [{ id: 'a', c: 2 }, { id: 'b', c: 2 }, { id: 'c', c: 1 }]
    const pages = packTv(items, 3, 1, span)
    expect(pages.map((p) => p.map((x) => x.item.id))).toEqual([['a', 'c'], ['b']])
    expect(pages[0][1]).toMatchObject({ col: 3, row: 1, c: 1, r: 1 })
    expect(packTv([], 3, 2, span)).toEqual([])
    expect(packTv([{ id: 'x', c: 9 }], 3, 1, (it) => ({ c: Math.min(it.c, 3), r: 1 }))[0][0]).toMatchObject({ col: 1, c: 3 })
  })

  it('sayfa ızgarası: kutuları tek sayfaya sığdıran en büyük hücre; hücre oranı makul (çok ince sütun seçilmez)', () => {
    const page = fitPage(stat(5), 5, 3, W, H, 'compact')
    expect([page.cols, page.rows]).toEqual([3, 2])
    expect(page.placed).toHaveLength(5)
    const one = fitPage(stat(2), 5, 3, W, H, 'compact')
    // 2 × 1 (936 × 968, oran ~1) — 1 × 2 (1872 × 476, oran ~3,9) seçilmez
    expect([one.cols, one.rows]).toEqual([2, 1])
  })

  it('varsayılan düzen (ayrıntılı nöbet kutusuyla) Sık\'ta tek ekrana sığar; 24 kutu sınırı', () => {
    const dflt = [['sm'], ['md'], ['md'], ['sm'], ['md'], ['sm'], ['md'], ['sm', true], ['lg']]
      .map(([size, detail], i) => ({ id: `d${i}`, size, detail: !!detail }))
    expect(layoutTv(dflt, W, H, 'compact')).toHaveLength(1)
    expect(layoutTv(dflt, W, H, 'comfortable').length).toBeGreaterThan(1)
    expect(layoutTv(stat(30), W, H, 'dense').flatMap((p) => p.placed)).toHaveLength(24)
    expect(layoutTv([], W, H, 'compact')).toEqual([])
  })

  it('ayrıntılı kutu: renkli paneli olmayan türler hep; renkli panel kapalıysa normal kutular; stat_* hiç', () => {
    expect(tvDetail({ type: 'team_matrix' }, 'stat')).toBe(true)
    expect(tvDetail({ type: 'oncall' }, 'stat')).toBe(true)
    expect(tvDetail({ type: 'alerts_open' }, 'stat')).toBe(false)
    expect(tvDetail({ type: 'alerts_open' }, 'detail')).toBe(true)
    expect(tvDetail({ type: 'alerts_open', style: 'stat' }, 'detail')).toBe(false)
    expect(tvDetail({ type: 'stat_alarms_critical' }, 'detail')).toBe(false)
  })

  it('satır sonu boşluğu: son kutu sağındaki boş hücrelere uzar (14 panel 5 × 3 → son panel 2 sütun); çok satırlık kutu uzamaz', () => {
    const [page] = layoutTv(stat(14), W, H, 'compact')
    const last = page.placed.find((p) => p.item.id === 's13')
    expect(last).toMatchObject({ row: 3, col: 4, c: 2, r: 1 })
    expect(page.placed.reduce((n, p) => n + p.c * p.r, 0), 'ızgara tam dolu').toBe(15)
    const tall = fillTrailingGaps([{ item: { id: 't' }, row: 1, col: 1, c: 1, r: 2 }], 3, 2)
    expect(tall[0].c, 'iki satırlık kutu yana uzatılmaz').toBe(1)
    const input = [{ item: { id: 'a' }, row: 1, col: 1, c: 1, r: 1 }]
    fillTrailingGaps(input, 3, 1)
    expect(input[0].c, 'girdi değişmez').toBe(1)
  })

  it('dikey ekran / tek kutu: tek kutu tüm alanı alır; tümüyle boş alt satırlara son satırın kutuları aşağı uzar', () => {
    expect(shape(layoutTv(stat(1), 1032, 1808, 'compact'))).toEqual(['1x1(1)'])
    expect(shape(layoutTv(stat(1), 358, 612, 'compact'))).toEqual(['1x1(1)'])
    const down = fillTrailingGaps([
      { item: { id: 'a' }, row: 1, col: 1, c: 1, r: 1 }, { item: { id: 'b' }, row: 1, col: 2, c: 1, r: 1 },
    ], 2, 3)
    expect(down.map((p) => p.r), 'iki boş alt satır doldu').toEqual([3, 3])
    const page = layoutTv(stat(2), 358, 612, 'compact')[0]
    expect(page.placed.reduce((n, p) => n + p.c * p.r, 0), 'ızgara tam dolu').toBe(page.cols * page.rows)
  })
})
