import { describe, it, expect, vi, beforeEach, afterAll } from 'vitest'
import { useState } from 'react'
import { render, fireEvent, waitFor, within } from './test-utils.jsx'

// Nöbet Rutinleri — Geçmiş görünümü (2026-11-26): takvim okuması (`/calendar`), ısı haritası durumları + erişilebilir
// adlar, bilinmeyen / kayıtsız gün ASLA yeşil değil, dar kapta ay görünümüne düşüş, klavye gezinmesi (oklar / Enter),
// okunamayan geçmiş → tehlike bloğu + tekrar dene, aralık seçici (hazır aralık yayımlanır, geçersiz özel aralık istek
// göndermez).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', async () => {
  const { localDayKey } = await import('../utils/localDay.js')
  return {
    localDayKey,
    formatDate: (s) => s ?? '',
    api: withApiFallback({ oncallRoutines: { calendar: vi.fn() } }),
  }
})
// Kap genişliği: 0 = ölçülemedi (jsdom) → yıl ızgarası; test dar kabı (ay görünümü) bununla taklit eder
const widthBox = vi.hoisted(() => ({ w: 0 }))
vi.mock('../hooks/useElementWidth.js', async (orig) => ({
  ...(await orig()),
  useElementWidth: () => [() => {}, widthBox.w],
}))

// Sabit "bugün": İstanbul 2026-11-26 (fikstür tarihleri ona göre). YALNIZ `Date` sahte — zamanlayıcılar gerçek
// (waitFor ve jsdom'un animasyon döngüsü etkilenmez).
vi.hoisted(() => {
  vi.useFakeTimers({ toFake: ['Date'], shouldAdvanceTime: true })
  vi.setSystemTime(new Date('2026-11-26T09:00:00Z'))
})
afterAll(() => vi.useRealTimers())

import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import HistoryView, {
  MAX_RANGE_DAYS, presetOf, resolveRange, summarizeDays, validateRange,
} from '../components/oncallroutine/HistoryView.jsx'
import RoutineHeatmap, {
  buildYearGrid, cellSizeFor, isFutureDay, monthLabelCols, normalizeStatus, statusSwatch,
} from '../components/oncallroutine/RoutineHeatmap.jsx'
import { TR } from '../i18n/tr.js'

const cal = () => api.oncallRoutines.calendar

const DAYS = [
  { date: '2026-11-02', workday: true, status: 'NONE', required: 0, done: 0, skipped: 0, problems_opened: 0 },
  { date: '2026-11-03', workday: true, status: 'SOMETHING_NEW', required: 18, done: 18, skipped: 0, problems_opened: 0 },
  { date: '2026-11-04', workday: true, status: 'COMPLETED', required: 18, done: 18, skipped: 0, problems_opened: 0,
    responsible_name: 'Ayşe Yılmaz (Dijital Kanallar / Uzman)', completed_at: '2026-11-04T14:05:00Z' },
  { date: '2026-11-05', workday: true, status: 'COMPLETED_PARTIAL', required: 18, done: 16, skipped: 2, problems_opened: 1, completed_late: true },
  { date: '2026-11-06', workday: true, status: 'INCOMPLETE', required: 18, done: 10, skipped: 0, problems_opened: 2 },
  { date: '2026-11-07', workday: false, status: 'NOT_WORKDAY', required: 0 },
  { date: '2026-11-08', workday: false, status: 'NOT_WORKDAY', required: 0 },
  { date: '2026-11-09', workday: true, status: 'MISSED', required: 18, done: 0, skipped: 0 },
  { date: '2026-11-10', workday: true, status: 'PAUSED', required: 0 },
  { date: '2026-11-26', workday: true, status: 'OPEN', required: 18, done: 12, skipped: 1 },
]
const calendarOk = (over = {}) => ({
  success: true,
  data: { from: '2026-11-02', to: '2026-11-26', today: '2026-11-26', routine_since: '2026-11-04', days: DAYS, ...over },
})

function Harness({ from: f0 = null, to: t0 = null, onRangeChange, onOpenDay = vi.fn() }) {
  const [range, setRange] = useState({ from: f0, to: t0 })
  return (
    <HistoryView teamId={12} teamName="Dijital Kanallar SY" from={range.from} to={range.to} onOpenDay={onOpenDay}
      onRangeChange={(r) => { onRangeChange?.(r); setRange(r) }} />
  )
}

const cell = (date) => document.querySelector(`[data-slot="or-heat-cell"][data-date="${date}"]`)
const heatmap = () => document.querySelector('[data-slot="or-heatmap"]')

beforeEach(() => {
  vi.clearAllMocks()
  widthBox.w = 0
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  cal().mockResolvedValue(calendarOk())
})

describe('aralık yardımcıları (saf)', () => {
  it('çözümleme: ikisi de yoksa bugünle biten 365 gün; yalnız bitiş / yalnız başlangıç', () => {
    expect(resolveRange(null, null, '2026-11-26')).toEqual({ from: '2025-11-27', to: '2026-11-26' })
    expect(resolveRange(null, '2026-06-30', '2026-11-26')).toEqual({ from: '2025-07-01', to: '2026-06-30' })
    expect(resolveRange('2026-10-01', null, '2026-11-26')).toEqual({ from: '2026-10-01', to: '2026-11-26' })
    expect(resolveRange('çöp', 'x', '2026-11-26')).toEqual({ from: '2025-11-27', to: '2026-11-26' })
  })

  it('doğrulama: sıra, gelecek, en çok 366 gün (iki uç dahil)', () => {
    const today = '2026-11-26'
    expect(validateRange({ from: '2026-11-01', to: '2026-11-26' }, today)).toBeNull()
    expect(validateRange({ from: '2026-11-20', to: '2026-11-10' }, today)?.key).toBe('or.hist.range.order')
    expect(validateRange({ from: '2026-11-01', to: '2026-11-27' }, today)?.key).toBe('or.hist.range.futureTo')
    expect(validateRange({ from: null, to: '2026-11-26' }, today)?.field).toBe('from')
    expect(validateRange({ from: '2025-11-26', to: '2026-11-26' }, today)).toBeNull()        // 366 gün
    expect(validateRange({ from: '2025-11-25', to: '2026-11-26' }, today)?.key).toBe('or.hist.range.tooLong')
    expect(MAX_RANGE_DAYS).toBe(366)
  })

  it('hazır aralık tanıma: bitiş bugün ve 30 / 90 / 365 gün; aksi özel', () => {
    const today = '2026-11-26'
    expect(presetOf({ from: '2026-10-28', to: today }, today)).toBe(30)
    expect(presetOf({ from: '2026-08-29', to: today }, today)).toBe(90)
    expect(presetOf({ from: '2025-11-27', to: today }, today)).toBe(365)
    expect(presetOf({ from: '2026-10-27', to: today }, today)).toBe('custom')
    expect(presetOf({ from: '2026-10-28', to: '2026-11-25' }, today)).toBe('custom')
  })

  it('sayaçlar yalnız renkli durumları sayar; tanınmayan kod sayılmaz', () => {
    const s = summarizeDays(DAYS, { today: '2026-11-26' })
    expect(s).toMatchObject({ COMPLETED: 1, COMPLETED_PARTIAL: 1, INCOMPLETE: 1, MISSED: 1, OPEN: 1, recorded: 5 })
    expect(s.workdays).toBe(7)   // 2 hafta sonu ve duraklatılmış gün iş günü sayılmaz
  })
})

describe('ısı haritası modeli (saf)', () => {
  it('bilinmeyen / kayıtsız durum ASLA yeşil değil', () => {
    for (const st of ['NONE', 'PAUSED', 'NOT_WORKDAY', 'FUTURE', 'SOMETHING_NEW', undefined, null]) {
      const sw = statusSwatch(st)
      expect(sw.className, String(st)).not.toMatch(/success/)
      expect(JSON.stringify(sw.style || {}), String(st)).not.toMatch(/success/)
    }
    expect(normalizeStatus('SOMETHING_NEW')).toBe('NONE')
    expect(statusSwatch('COMPLETED').className).toBe('bg-success')
    expect(statusSwatch('MISSED').className).toBe('bg-destructive')
    expect(statusSwatch('INCOMPLETE').className).toBe('bg-warning')
    expect(statusSwatch('OPEN').className).toMatch(/ring-primary/)
    expect(statusSwatch('COMPLETED_PARTIAL').style.backgroundColor).toBe('color-mix(in oklab, var(--success) 55%, var(--warning))')
  })

  it('yıl ızgarası: ISO haftaları Pazartesi başlar; ay başları; çakışan ilk etiket gerçek ay başına yer açar', () => {
    const g = buildYearGrid('2026-10-29', '2026-11-26')
    expect(g.weeks[0].monday).toBe('2026-10-26')
    expect(g.weeks.at(-1).monday).toBe('2026-11-23')
    expect(g.weeks).toHaveLength(5)
    expect(g.monthStarts).toEqual([{ col: 0, date: '2026-11-01' }])
    expect(monthLabelCols('2026-10-29', g.monthStarts, 3)).toEqual([{ col: 0, date: '2026-11-01' }])
    const year = buildYearGrid('2025-11-27', '2026-11-26')
    expect(year.weeks.length).toBeLessThanOrEqual(54)
    expect(cellSizeFor(year.weeks.length)).toBe(12)
    expect(cellSizeFor(5)).toBe(20)
    expect(buildYearGrid('2026-11-26', '2026-11-01')).toEqual({ weeks: [], monthStarts: [] })
  })
})

describe('Geçmiş görünümü', () => {
  it('takvimi takım + aralıkla okur; hücreler durum, renk ve erişilebilir adla çizilir', async () => {
    render(<Harness />)
    await waitFor(() => expect(cell('2026-11-04')).toBeTruthy())
    const [teamId, opts] = cal().mock.calls[0]
    expect(teamId).toBe(12)
    expect(opts.from).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(opts.to).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(opts.signal).toBeInstanceOf(AbortSignal)

    expect(heatmap().getAttribute('data-mode')).toBe('grid')
    expect(document.querySelector('[role="grid"]')).toBeTruthy()
    // 2026-11-02 … 2026-11-26 = 25 gün → 25 hücre (son haftanın sonrası boşluk, hücre değil)
    expect(document.querySelectorAll('[data-slot="or-heat-cell"]')).toHaveLength(25)

    const done = cell('2026-11-04')
    expect(done.getAttribute('data-status')).toBe('COMPLETED')
    expect(done.className).toMatch(/bg-success/)
    expect(done.getAttribute('aria-label')).toMatch(/18\/18/)
    expect(done.getAttribute('aria-label')).toMatch(/Ayşe Yılmaz \(Dijital Kanallar \/ Uzman\)/)
    expect(cell('2026-11-06').getAttribute('aria-label')).toMatch(/10\/18/)
    expect(cell('2026-11-05').getAttribute('data-status')).toBe('COMPLETED_PARTIAL')
    expect(cell('2026-11-09').className).toMatch(/bg-destructive/)
    expect(cell('2026-11-26').getAttribute('data-today')).toBe('true')
  })

  it('kayıtsız, duraklatılmış, iş günü olmayan ve tanınmayan günler gri — hiçbiri yeşil değil', async () => {
    render(<Harness />)
    await waitFor(() => expect(cell('2026-11-02')).toBeTruthy())
    for (const d of ['2026-11-02', '2026-11-03', '2026-11-07', '2026-11-10', '2026-11-11']) {
      expect(cell(d).className, d).not.toMatch(/bg-success/)
      expect(cell(d).className, d).toMatch(/bg-muted/)
    }
    expect(cell('2026-11-03').getAttribute('data-status')).toBe('NONE')      // tanınmayan kod → NONE
    expect(cell('2026-11-11').getAttribute('data-status')).toBe('NONE')      // satırı olmayan gün → NONE
    // Açıklama: renkli durumlar + gri durumlar; duraklatılmış gün olduğundan PAUSED da listelenir
    const legend = document.querySelector('[data-slot="or-heat-legend"]')
    const shown = [...legend.querySelectorAll('[data-status]')].map((x) => x.getAttribute('data-status'))
    expect(shown).toEqual(['COMPLETED', 'COMPLETED_PARTIAL', 'INCOMPLETE', 'MISSED', 'OPEN', 'NONE', 'NOT_WORKDAY', 'PAUSED'])
  })

  it('özet sayaçları ve rutin başlangıcı notu', async () => {
    render(<Harness />)
    await waitFor(() => expect(document.querySelector('[data-slot="or-hist-summary"]')).toBeTruthy())
    const chip = (st) => document.querySelector(`[data-slot="or-hist-chip"][data-status="${st}"]`)
    for (const st of ['COMPLETED', 'COMPLETED_PARTIAL', 'INCOMPLETE', 'MISSED', 'OPEN']) expect(chip(st).getAttribute('data-count')).toBe('1')
    expect(chip('WORKDAYS').textContent).toMatch(/7/)
    expect(document.querySelector('[data-slot="or-hist-since"]').textContent).toMatch(/04\.11\.2026/)
    expect(document.querySelector('[data-slot="or-hist-empty"]')).toBeNull()
  })

  it('tıklama ve klavye: oklar gün / hafta gezer, aralık dışına çıkmaz, Enter günü açar', async () => {
    const onOpenDay = vi.fn()
    render(<Harness onOpenDay={onOpenDay} />)
    await waitFor(() => expect(cell('2026-11-26')).toBeTruthy())
    // Gezici tabindex: yalnız bugünün hücresi sekme sırasında
    expect(cell('2026-11-26').getAttribute('tabindex')).toBe('0')
    expect(cell('2026-11-04').getAttribute('tabindex')).toBe('-1')

    cell('2026-11-26').focus()
    fireEvent.keyDown(cell('2026-11-26'), { key: 'ArrowLeft' })
    await waitFor(() => expect(document.activeElement?.getAttribute('data-date')).toBe('2026-11-19'))
    fireEvent.keyDown(document.activeElement, { key: 'ArrowUp' })
    await waitFor(() => expect(document.activeElement?.getAttribute('data-date')).toBe('2026-11-18'))
    expect(cell('2026-11-18').getAttribute('tabindex')).toBe('0')
    fireEvent.keyDown(document.activeElement, { key: 'Enter' })
    expect(onOpenDay).toHaveBeenLastCalledWith('2026-11-18')

    // Aralık dışı (2026-11-26 + 7) → yerinde kalır
    cell('2026-11-26').focus()
    fireEvent.keyDown(cell('2026-11-26'), { key: 'ArrowRight' })
    expect(document.activeElement?.getAttribute('data-date')).toBe('2026-11-26')
    fireEvent.keyDown(cell('2026-11-26'), { key: 'Home' })
    await waitFor(() => expect(document.activeElement?.getAttribute('data-date')).toBe('2026-11-02'))

    fireEvent.click(cell('2026-11-05'))
    expect(onOpenDay).toHaveBeenLastCalledWith('2026-11-05')
  })

  it('dar kap → ay görünümü (MonthCalendar); renkli günler olay, gri günler olay değil; olay günü açar', async () => {
    widthBox.w = 400
    const onOpenDay = vi.fn()
    render(<Harness onOpenDay={onOpenDay} />)
    await waitFor(() => expect(heatmap()?.getAttribute('data-mode')).toBe('month'))
    expect(document.querySelector('[data-slot="month-calendar"]')).toBeTruthy()
    expect(document.querySelector('[data-slot="or-heat-cell"]')).toBeNull()
    expect(document.querySelector('[role="grid"]')).toBeNull()
    // 5 renkli gün → 5 olay; NONE / PAUSED / NOT_WORKDAY / tanınmayan kod olay üretmez
    expect(document.querySelectorAll('[data-slot="month-calendar-event"]')).toHaveLength(5)
    const missedDay = document.querySelector('[data-slot="month-calendar-day"][data-day="2026-11-09"]')
    expect(missedDay.getAttribute('data-tone')).toBe('bad')
    const noneDay = document.querySelector('[data-slot="month-calendar-day"][data-day="2026-11-02"]')
    expect(noneDay.getAttribute('data-tone')).toBeNull()
    const ev = document.querySelector('[data-slot="month-calendar-day"][data-day="2026-11-06"] [data-slot="month-calendar-event"]')
    expect(ev.getAttribute('title')).toMatch(/10\/18/)
    fireEvent.click(ev)
    expect(onOpenDay).toHaveBeenLastCalledWith('2026-11-06')
    // Açıklama ay görünümünde de var
    expect(document.querySelector('[data-slot="or-heat-legend"]')).toBeTruthy()
  })

  it('okunamayan geçmiş → tehlike bloğu (asla "kayıt yok") + Tekrar dene yeniden okur', async () => {
    cal().mockResolvedValueOnce({ success: false, status: 503, code: 'DB_UNAVAILABLE', error: 'Veritabanına şu an ulaşılamıyor; birkaç saniye sonra yeniden deneyin.' })
    render(<Harness />)
    const failed = await waitFor(() => {
      const el = document.querySelector('[data-slot="or-hist-failed"]')
      expect(el).toBeTruthy()
      return el
    })
    expect(failed.textContent).toMatch(/Veritabanına şu an ulaşılamıyor/)
    expect(heatmap()).toBeNull()
    expect(document.querySelector('[data-slot="or-hist-empty"]')).toBeNull()
    fireEvent.click(document.querySelector('[data-slot="or-hist-retry"]'))
    await waitFor(() => expect(cell('2026-11-04')).toBeTruthy())
    expect(document.querySelector('[data-slot="or-hist-failed"]')).toBeNull()
  })

  it('ağ hatası (istek reddedildi) da tehlike bloğu gösterir', async () => {
    cal().mockRejectedValueOnce(Object.assign(new Error('Sunucuya ulaşılamadı; bağlantınızı denetleyip yeniden deneyin.'), { name: 'ApiError', code: 'NETWORK_ERROR' }))
    render(<Harness />)
    await waitFor(() => expect(document.querySelector('[data-slot="or-hist-failed"]')).toBeTruthy())
    expect(document.querySelector('[data-slot="or-hist-failed"]').textContent).toMatch(/Sunucuya ulaşılamadı/)
  })

  it('aralık değişince eski veri hemen silinir; geç gelen eski yanıt yeni aralığın verisini ezmez', async () => {
    let resolveFirst
    cal().mockImplementationOnce(() => new Promise((r) => { resolveFirst = r }))
    const onRangeChange = vi.fn()
    render(<Harness onRangeChange={onRangeChange} />)
    await waitFor(() => expect(document.querySelector('[data-slot="or-hist-loading"]')).toBeTruthy())
    // 30 gün seç → yeni istek
    const presets = within(document.querySelector('[data-slot="or-range-presets"]')).getAllByRole('button')
    fireEvent.click(presets[0])
    expect(onRangeChange).toHaveBeenCalledTimes(1)
    const r = onRangeChange.mock.calls[0][0]
    expect(r.from && r.to).toBeTruthy()
    await waitFor(() => expect(cal()).toHaveBeenCalledTimes(2))
    expect(cal().mock.calls[1][1]).toMatchObject({ from: r.from, to: r.to })
    await waitFor(() => expect(cell('2026-11-04')).toBeTruthy())
    // İlk (eski) yanıt geç gelir: boş gün listesi — ekrandaki veri değişmez
    resolveFirst({ success: true, data: { from: '2026-11-02', to: '2026-11-26', today: '2026-11-26', days: [] } })
    await new Promise((res) => setTimeout(res, 0))
    expect(cell('2026-11-04').getAttribute('data-status')).toBe('COMPLETED')
  })

  it('365 gün seçimi varsayılana döner (adres anahtarları boş)', async () => {
    const onRangeChange = vi.fn()
    render(<Harness from="2026-10-01" to="2026-11-20" onRangeChange={onRangeChange} />)
    await waitFor(() => expect(cell('2026-11-04')).toBeTruthy())
    expect(document.querySelector('[data-slot="or-range"]').getAttribute('data-mode')).toBe('custom')
    const presets = within(document.querySelector('[data-slot="or-range-presets"]')).getAllByRole('button')
    fireEvent.click(presets[2])
    expect(onRangeChange).toHaveBeenLastCalledWith({ from: null, to: null })
  })

  it('geçersiz özel aralık: alanın altında söylenir, İSTEK GİTMEZ', async () => {
    render(<Harness from="2026-11-20" to="2026-11-10" />)
    await waitFor(() => expect(document.querySelector('[data-slot="or-range-custom"]')).toBeTruthy())
    const from = document.querySelector('[data-slot="or-range-from"]')
    expect(from.textContent).toMatch(/Başlangıç, bitişten sonra olamaz/)
    expect(cal()).not.toHaveBeenCalled()
    expect(document.querySelector('[data-slot="or-history"]').getAttribute('data-state')).toBe('idle')
    expect(heatmap()).toBeNull()
  })

  it('366 günden uzun özel aralık da istek göndermez', async () => {
    render(<Harness from="2024-01-01" to="2026-01-01" />)
    await waitFor(() => expect(document.querySelector('[data-slot="or-range-custom"]')).toBeTruthy())
    expect(document.querySelector('[data-slot="or-range-from"]').textContent).toMatch(/366/)
    expect(cal()).not.toHaveBeenCalled()
  })

  it('aralıkta hiç kayıt yoksa nötr not; ızgara yine gri çizilir', async () => {
    cal().mockResolvedValue(calendarOk({ days: [{ date: '2026-11-04', workday: true, status: 'NONE' }], routine_since: null }))
    render(<Harness />)
    await waitFor(() => expect(document.querySelector('[data-slot="or-hist-empty"]')).toBeTruthy())
    expect(cell('2026-11-04').className).toMatch(/bg-muted/)
    expect(document.querySelector('[data-slot="or-hist-since"]')).toBeNull()
  })
})

describe('ısı haritası — gelecek günler açılmaz (henüz veri yok)', () => {
  it('isFutureDay: FUTURE durumu ya da bugünden sonraki tarih; bugün bilinmiyorsa yalnız FUTURE', () => {
    expect(isFutureDay('2026-11-27', 'NONE', '2026-11-26')).toBe(true)
    expect(isFutureDay('2026-11-26', 'OPEN', '2026-11-26')).toBe(false)
    expect(isFutureDay('2026-11-25', 'FUTURE', '2026-11-26')).toBe(true)
    expect(isFutureDay('2026-12-01', 'NONE', null)).toBe(false)
    expect(isFutureDay('2026-12-01', 'FUTURE', undefined)).toBe(true)
  })

  it('yıl ızgarası: gelecek hücre aria-disabled + "henüz veri yok"; tıklama, Enter ve Boşluk açmaz; geçmiş gün açar', () => {
    const onOpenDay = vi.fn()
    const days = [...DAYS, { date: '2026-11-28', workday: true, status: 'FUTURE', required: null }]
    render(<RoutineHeatmap days={days} from="2026-11-20" to="2026-11-30" today="2026-11-26" onOpenDay={onOpenDay} />)
    const fut = cell('2026-11-28')
    expect(fut.getAttribute('aria-disabled')).toBe('true')
    expect(fut.getAttribute('data-future')).toBe('true')
    expect(fut.getAttribute('aria-label')).toContain(TR['or.hist.futureNoData'])
    fireEvent.click(fut)
    fut.focus()
    fireEvent.keyDown(fut, { key: 'Enter' })
    fireEvent.keyDown(fut, { key: ' ' })
    expect(onOpenDay).not.toHaveBeenCalled()
    // Satırı olmayan ama bugünden sonraki gün de gelecek
    expect(cell('2026-11-29').getAttribute('aria-disabled')).toBe('true')
    // Ok tuşu gelecek hücreden geçmişe gezer (ızgara gezintisi bozulmaz)
    fireEvent.keyDown(fut, { key: 'ArrowUp' })
    expect(cell('2026-11-26').getAttribute('aria-disabled')).toBeNull()
    expect(cell('2026-11-26').getAttribute('aria-label')).not.toContain(TR['or.hist.futureNoData'])
    fireEvent.click(cell('2026-11-26'))
    expect(onOpenDay).toHaveBeenLastCalledWith('2026-11-26')
    expect(onOpenDay).toHaveBeenCalledTimes(1)
  })

  it('ay görünümü: bugünden sonraki satır olay üretmez ve günü açılmaz', async () => {
    widthBox.w = 400
    const onOpenDay = vi.fn()
    const days = [...DAYS, { date: '2026-11-28', workday: true, status: 'OPEN', required: 18, done: 0, skipped: 0 }]
    render(<RoutineHeatmap days={days} from="2026-11-02" to="2026-11-30" today="2026-11-26" onOpenDay={onOpenDay} />)
    await waitFor(() => expect(heatmap()?.getAttribute('data-mode')).toBe('month'))
    const futureDay = document.querySelector('[data-slot="month-calendar-day"][data-day="2026-11-28"]')
    expect(futureDay.querySelector('[data-slot="month-calendar-event"]')).toBeNull()
    expect(futureDay.querySelector('[data-slot="month-calendar-day-open"]')).toBeNull()
    expect(document.querySelector('[data-slot="month-calendar-day"][data-day="2026-11-26"] [data-slot="month-calendar-event"]')).toBeTruthy()
  })
})
