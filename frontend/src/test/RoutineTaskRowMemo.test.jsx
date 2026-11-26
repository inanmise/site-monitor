import { describe, it, expect } from 'vitest'

/**
 * Nöbet Rutinleri — görev satırının memo karşılaştırması (`sameRowProps`, inceleme bulgusu #17, 2026-11-26). Saf doğruluk
 * tablosu: `signals` haritasından yalnız BU satırın anahtarları, `nowMs`'ten yalnız gecikme / "henüz değil" durumu
 * karşılaştırılır (30 sn'lik saat ve 60 sn'lik sayaç yenilemesi her satırı yeniden çizmesin); başka her prop başvuruyla.
 * `true` = aynı (yeniden çizilmez).
 */
import TaskRow, { sameRowProps } from '../components/oncallroutine/TaskRow.jsx'
import { istanbulInstantMs } from '../components/oncallroutine/routineModel.js'
import { TEAM_ID, TODAY, buildItem } from './helpers/oncallRoutineFixtures.js'

const at = (hm) => istanbulInstantMs(TODAY, hm)
const noop = () => {}

/** Bekleyen, zorunlu, bugün 09:00–10:30 penceresinde, tek canlı sayacı (open_alerts) olan görev. */
const ITEM = buildItem({
  task_id: 't-dash', title: 'Alarm panosunu kontrol et', signals: ['open_alerts'], window_from: '09:00', window_to: '10:30',
})
const sig = (over = {}) => ({
  value: 3, tone: 'warn', partial: false, hidden: false, error: null,
  link: { tab: 'alerthistory', params: { view: 'open', team: String(TEAM_ID) } }, ...over,
})
const SIGNALS = Object.freeze({
  open_alerts: sig(),
  down_monitors: { value: null, tone: 'neutral', partial: false, hidden: false, error: 'unavailable', link: null },
})

function props(over = {}) {
  return {
    item: ITEM, today: TODAY, nowMs: at('09:15'), contextDate: TODAY, teamId: TEAM_ID, editable: true,
    canEditTemplate: false, signals: SIGNALS, signalsLoading: false, showSignals: true, panel: null,
    onToggle: noop, onSkip: noop, onPanel: noop, onNoteSaved: noop, onClearNote: noop, onProblemCreated: noop,
    onImagesDone: noop, onHistory: noop, onEditTemplate: noop,
    ...over,
  }
}
const withSignals = (patch) => ({ ...SIGNALS, ...patch })

describe('sameRowProps — doğruluk tablosu', () => {
  it('TaskRow bu karşılaştırmayla memo’lanır', () => {
    expect(TaskRow.compare).toBe(sameRowProps)
  })

  it('değişmeyen propslar (yeni nesne, aynı değerler) → aynı', () => {
    const a = props()
    expect(sameRowProps(a, a)).toBe(true)
    expect(sameRowProps(a, { ...a })).toBe(true)
  })

  it('yoklamadan gelen yeni sayaç nesnesi, satırın sayacı aynı değerde → aynı', () => {
    const fresh = { open_alerts: sig(), down_monitors: { ...SIGNALS.down_monitors } }
    expect(sameRowProps(props(), props({ signals: fresh }))).toBe(true)
  })

  it('BAŞKA satırın sayacı değişti → aynı (bu satır çizilmez)', () => {
    expect(sameRowProps(props(), props({ signals: withSignals({ down_monitors: sig({ value: 9 }) }) }))).toBe(true)
    expect(sameRowProps(props(), props({ signals: withSignals({ open_incidents: sig({ value: 12, partial: true }) }) }))).toBe(true)
  })

  it('satırın KENDİ sayacı değişti (değer / ton / kısmi / gizli / hata / bağlantı) → farklı', () => {
    const cases = [
      { value: 4 }, { tone: 'neutral' }, { partial: true }, { hidden: true }, { error: 'unavailable' },
      { link: { tab: 'alerthistory', params: { view: 'all', team: String(TEAM_ID) } } },
      { link: { tab: 'incident-history', params: { view: 'open', team: String(TEAM_ID) } } },
      { link: null },
    ]
    for (const c of cases) {
      expect(sameRowProps(props(), props({ signals: withSignals({ open_alerts: sig(c) }) })), JSON.stringify(c)).toBe(false)
    }
  })

  it('sayaç haritası yok ↔ var → farklı; ikisi de yok → aynı; satırın sayacı haritadan düştü → farklı', () => {
    expect(sameRowProps(props({ signals: null }), props())).toBe(false)
    expect(sameRowProps(props(), props({ signals: null }))).toBe(false)
    expect(sameRowProps(props({ signals: null }), props({ signals: null }))).toBe(true)
    expect(sameRowProps(props(), props({ signals: { down_monitors: SIGNALS.down_monitors } }))).toBe(false)
  })

  it('showSignals kapalıyken sayaç değişikliği yok sayılır; showSignals değişirse farklı', () => {
    const off = { showSignals: false }
    expect(sameRowProps(props(off), props({ ...off, signals: withSignals({ open_alerts: sig({ value: 99 }) }) }))).toBe(true)
    expect(sameRowProps(props(off), props({ ...off, signals: null }))).toBe(true)
    expect(sameRowProps(props(), props(off))).toBe(false)
  })

  it('sayacı olmayan görevde harita tamamen değişse de aynı', () => {
    const plain = buildItem({ task_id: 't-errors', title: 'Gece hata kayıtlarını incele', window_to: '10:30' })
    expect(sameRowProps(props({ item: plain }), props({ item: plain, signals: null }))).toBe(true)
    expect(sameRowProps(props({ item: plain }), props({ item: plain, signals: { open_alerts: sig({ value: 7 }) } }))).toBe(true)
  })

  it('nowMs değişti ama gecikme / "henüz değil" aynı → aynı', () => {
    expect(sameRowProps(props({ nowMs: at('09:15') }), props({ nowMs: at('09:45') }))).toBe(true)
    // İkisi de gecikmiş
    expect(sameRowProps(props({ nowMs: at('11:00') }), props({ nowMs: at('13:41') }))).toBe(true)
    // İkisi de pencere başından önce
    expect(sameRowProps(props({ nowMs: at('08:00') }), props({ nowMs: at('08:30') }))).toBe(true)
  })

  it('nowMs gecikme anını geçti (pencere sonu 10:30, saniye hassasiyeti) → farklı', () => {
    const end = at('10:30')
    expect(sameRowProps(props({ nowMs: end }), props({ nowMs: end + 1000 }))).toBe(false)
    expect(sameRowProps(props({ nowMs: end - 60_000 }), props({ nowMs: end }))).toBe(true)
    // Geri yönde de (saat düzeltmesi)
    expect(sameRowProps(props({ nowMs: end + 1000 }), props({ nowMs: end - 1000 }))).toBe(false)
  })

  it('nowMs pencere başını geçti ("henüz değil" bitti) → farklı', () => {
    expect(sameRowProps(props({ nowMs: at('08:59') }), props({ nowMs: at('09:01') }))).toBe(false)
  })

  it('yapılmış görev saatle yeniden çizilmez (gecikme anı geçse de durum aynı)', () => {
    const done = buildItem({ task_id: 't-dash', signals: ['open_alerts'], window_from: '09:00', window_to: '10:30', state: 'DONE' })
    expect(sameRowProps(props({ item: done, nowMs: at('08:59') }), props({ item: done, nowMs: at('13:41') }))).toBe(true)
  })

  it('fazla ya da eksik prop → farklı', () => {
    expect(sameRowProps(props(), { ...props(), extra: 1 })).toBe(false)
    const missing = props()
    delete missing.onHistory
    expect(sameRowProps(props(), missing)).toBe(false)
    expect(sameRowProps(missing, props())).toBe(false)
  })

  it('başka her prop başvuruyla karşılaştırılır (madde, panel, düzenlenebilirlik, bugün, geri çağrı, bağlam günü)', () => {
    const cases = [
      { item: { ...ITEM } },
      { panel: 'note' },
      { editable: false },
      { today: '2026-11-27' },
      { onToggle: () => {} },
      { contextDate: '2026-11-25' },
      { signalsLoading: true },
      { canEditTemplate: true },
    ]
    for (const c of cases) expect(sameRowProps(props(), props(c)), Object.keys(c)[0]).toBe(false)
  })
})
