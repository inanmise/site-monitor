import { describe, it, expect, vi } from 'vitest'
import { render, screen } from './test-utils.jsx'
import PageSpeedMonitorCard from '../components/pagespeed/PageSpeedMonitorCard.jsx'
import { budgetCompliance, extraBreaches, metersFor, meterTone } from '../components/pagespeed/pageSpeedCardModel.js'
import { failureKind, rebucketTypeSeries, MIN_BAR_PX } from '../components/pagespeed/pageSpeedInsightsModel.js'
import { pageSpeedOverBudget, pageSpeedSlowest, pageState } from '../components/mydash/pageSpeedWidgetModel.js'
import { suggestionIsPartial } from '../utils/pageSpeedThresholds.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => (s ? `exact:${s}` : ''),
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: { maintenance: { active: vi.fn(() => Promise.resolve({ success: true, data: { all: false, targets: [] } })) } },
  }),
}))

/**
 * Sayfa Hızı zenginleştirme 1. sürüm — bağımsız inceleme düzeltmeleri (2026-11-14), arayüz tarafı. Her blok bir bulguyu
 * kanıtlar ve düzeltme geri alınırsa kırılır.
 */

const page = (over = {}) => ({
  id: 1, name: 'Sayfa', url: 'https://ornek.test/', active: true, status: 'OK', response_ms: 800, ttfb_ms: 120,
  total_bytes: 300_000, request_count: 20, breached_metrics: [], last_check: '2026-11-14T10:00:00', budget_defined: true,
  max_load_ms: 3000, ...over,
})

describe('F1 — "En yavaş sayfalar": başlık ve ton etiketi görüntü sınırından bağımsız', () => {
  it('küçük boyutta (0 satır) en yavaş ve bütçe aşan sayısı TÜM ölçülenlerden', () => {
    const data = [
      ...[1, 2, 3, 4, 5].map((i) => page({ id: i, name: `Yavaş ${i}`, response_ms: 5000 + i, max_load_ms: 9000 })),
      page({ id: 9, name: 'Hızlı ama bütçe dışı', response_ms: 2000, max_load_ms: 1000, status: 'SLOW', breached_metrics: ['LOAD'] }),
    ]
    const s0 = pageSpeedSlowest(data, 0)
    expect(s0.rows).toEqual([])
    expect(s0.slowest).toMatchObject({ id: 5, ms: 5005 })
    expect(s0.overLoad).toBe(1)
    const s5 = pageSpeedSlowest(data, 5)
    expect(s5.rows.some((r) => r.over)).toBe(false)   // listede görünmeyen sayfa
    expect(s5.overLoad).toBe(1)                          // ama sayı onu bilir
    expect(s5.tone).toBe('warning')
  })
})

describe('F7 / B1 — bütçe tanımsız ve kararsız ölçüm "eşik içinde" sayılmaz', () => {
  it('pageState: bütçesiz → nobudget; kararsız eşik → unknown; eski sunucu (alan yok) → ok', () => {
    expect(pageState(page({ budget_defined: false }))).toBe('nobudget')
    expect(pageState(page({ undecided_metrics: ['JS'] }))).toBe('unknown')
    expect(pageState(page({ budget_defined: undefined }))).toBe('ok')
  })
  it('hepsi bütçesizse panel yeşil olmaz; karışıkta yeşil yalnız gerçek "eşik içinde" sayfalarla', () => {
    const allNo = pageSpeedOverBudget([page({ id: 1, budget_defined: false }), page({ id: 2, budget_defined: false })])
    expect(allNo).toMatchObject({ ok: 0, noBudget: 2, tone: 'muted' })
    const mixed = pageSpeedOverBudget([page({ id: 1 }), page({ id: 2, budget_defined: false })])
    expect(mixed).toMatchObject({ ok: 1, noBudget: 1, tone: 'success' })
    const undecided = pageSpeedOverBudget([page({ id: 1 }), page({ id: 2, undecided_metrics: ['SIZE'] })])
    expect(undecided).toMatchObject({ unknown: 1, tone: 'muted' })
  })
  it('budgetCompliance: bütçesiz izlemede yüzde yok (noBudget), sunucunun budget_defined bayrağı da sayılır', () => {
    expect(budgetCompliance({ n: 5, fail: 0, budget_pct: 100 }, 30, false)).toMatchObject({ pct: null, noBudget: true })
    expect(budgetCompliance({ n: 5, fail: 0, budget_pct: 100, budget_defined: false }, 30)).toMatchObject({ pct: null, noBudget: true })
    expect(budgetCompliance({ n: 5, fail: 0, budget_pct: 100 }, 30, true)).toMatchObject({ pct: 100, noBudget: false })
  })
})

describe('F2 / B1 — ölçerler: MINSIZE, tür bütçeleri ve alt sınır', () => {
  it('asgari boyut ihlali boyut ölçerini kırmızı yapar (azami boyut olmasa da)', () => {
    const size = metersFor(page({ status: 'SLOW', breached_metrics: ['MINSIZE'] })).find((x) => x.key === 'size')
    expect(size.tone).toBe('crit')
  })
  it('ölçer dışı ihlaller adlandırılır, sunucu sırasıyla', () => {
    expect(extraBreaches(page({ breached_metrics: ['THIRD', 'LOAD', 'JS', 'MINSIZE'] }))).toEqual(['MINSIZE', 'JS', 'THIRD'])
    expect(extraBreaches(page())).toEqual([])
  })
  it('alt sınır bütçenin altındaysa ölçer yeşil değil nötr; bütçeyi aşan alt sınır yine kırmızı', () => {
    expect(meterTone({ ratio: 0.5, lowerBound: true })).toBeNull()
    expect(meterTone({ ratio: 1.2, lowerBound: true })).toBe('crit')
    expect(meterTone({ ratio: 0.5 })).toBe('ok')
    const size = metersFor(page({ time_truncated: true, max_page_kb: 4000 })).find((x) => x.key === 'size')
    expect(size.tone).toBeNull()
  })
})

describe('kart — ölçer dışı ihlal satırı, bütçesiz çip, çip sarmalayıcısı', () => {
  it('tür bütçesi aşıldığında kart adıyla söyler; bütçesizde "Bütçe tanımlı değil" (yeşil değil)', () => {
    const { container, unmount } = render(<PageSpeedMonitorCard monitor={page({ status: 'SLOW', breached_metrics: ['JS', 'IMG'] })}
      status="warn" sla={{ n: 4, fail: 0, budget_pct: 50 }} />)
    expect(container.querySelector('[data-slot="pspd-extra-breaches"]')).toHaveTextContent(/JavaScript/)
    unmount()
    const r = render(<PageSpeedMonitorCard monitor={page({ budget_defined: false, max_load_ms: null })} status="ok"
      sla={{ n: 4, fail: 0, budget_pct: 100, budget_defined: false }} />)
    const chip = r.container.querySelector('[data-slot="pspd-budget-compliance"]')
    expect(chip).toHaveAttribute('data-tone', 'none')
    expect(chip.textContent).toMatch(/^(No budget defined|Bütçe tanımlı değil)$/)
    expect(r.container.querySelector('[data-slot="pspd-chips"]')).not.toBeNull()
    expect(screen.queryByText(/%100/)).toBeNull()
  })
})

describe('F3 — tür grafiği yeniden kovalama', () => {
  const rows = Array.from({ length: 720 }, (_, i) => ({ x: i * 3_600_000, n: 2, html: 10, js: i % 2 ? 20 : null, css: null, img: null, font: null, other: null }))
  it('çizim genişliğinin izin verdiği çubuk sayısına iner; boş kova boş kalır, ortalama ağırlıklı', () => {
    const plotWidth = 250
    const out = rebucketTypeSeries(rows, Math.floor(plotWidth / MIN_BAR_PX))
    expect(out.length).toBeLessThanOrEqual(Math.floor(plotWidth / MIN_BAR_PX))
    expect(out[0].x).toBe(0)
    expect(out[0].html).toBe(10)
    expect(out[0].js).toBe(20)          // null kovalar ortalamaya girmez
    expect(out[0].css).toBeNull()       // hiç değer yok → boşluk (0 değil)
    expect(out.reduce((s, r) => s + r.n, 0)).toBe(1440)
  })
  it('az kovada dokunmaz', () => {
    const few = rows.slice(0, 6)
    expect(rebucketTypeSeries(few, 60)).toBe(few)
  })
})

describe('F4 / F12 — form denemesi ve uç yanıtı', () => {
  it('süre tavanına takılan deneme kısmi sayılır', () => {
    expect(suggestionIsPartial({ time_truncated: true })).toBe(true)
    expect(suggestionIsPartial({})).toBe(false)
  })
  it('eski pod (405 / NOT_FOUND kodlu 404) "desteklenmiyor"; kodsuz 404 "izleme yok"; diğerleri hata', () => {
    expect(failureKind({ status: 405 })).toBe('unsupported')
    expect(failureKind({ status: 404, code: 'NOT_FOUND' })).toBe('unsupported')
    expect(failureKind({ status: 404 })).toBe('gone')
    expect(failureKind({ status: 500 })).toBe('error')
    expect(failureKind({ errorInfo: { status: 404, code: 'NOT_FOUND' } })).toBe('unsupported')
  })
})
