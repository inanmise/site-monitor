import { describe, it, expect } from 'vitest'
import {
  placeTooltip, spotlightRect, filterSteps, newStepsSince, decideAutoStart, shouldOfferPageTour, checklistProgress,
  mergeState, keyAction, TOUR_VERSION, SNOOZE_MAX, TIP_W,
} from '../components/tour/tourEngine.js'
import { MAIN_STEPS, PAGE_TOURS, CHECKLIST_ITEMS, CHECKLIST_BY_TAB } from '../components/tour/tourSteps.js'

// Ürün turu motoru (2026-09-13): saf kurallar — yerleşim, süzme, otomatik başlatma, "bir daha gösterme".
const vp = { w: 1280, h: 800 }
const tip = { w: TIP_W, h: 200 }

describe('tourEngine — yerleşim', () => {
  it('tercih edilen yön sığıyorsa onu kullanır; sığmıyorsa sırayla döner; hedef yoksa merkez', () => {
    const target = { top: 100, left: 100, width: 200, height: 40 }
    expect(placeTooltip(target, tip, vp, 'bottom')).toMatchObject({ placement: 'bottom', top: 152 })
    expect(placeTooltip(target, tip, vp, 'right')).toMatchObject({ placement: 'right', left: 312 })
    // Alt kenara yakın hedef: bottom sığmaz → top
    const low = { top: 700, left: 100, width: 200, height: 40 }
    expect(placeTooltip(low, tip, vp, 'bottom').placement).toBe('top')
    // Sağ kenara yakın + alt/üst sığmayan dar viewport → left
    const edge = { top: 150, left: 900, width: 100, height: 40 }   // üst: 150-212<12, alt: 402>368, sağ: taşar → sol
    expect(placeTooltip(edge, tip, { w: 1000, h: 380 }, 'right').placement).toBe('left')
    expect(placeTooltip(null, tip, vp).placement).toBe('center')
    // Yatay kırpma: balon viewport dışına taşmaz
    const farLeft = { top: 100, left: 0, width: 20, height: 20 }
    expect(placeTooltip(farLeft, tip, vp, 'bottom').left).toBeGreaterThanOrEqual(12)
  })
  it('spot ışığı deliği kenar payı ile viewport içinde kalır', () => {
    expect(spotlightRect({ top: 2, left: 2, width: 50, height: 20 }, 6, vp)).toEqual({ x: 0, y: 0, w: 58, h: 28 })
    expect(spotlightRect(null)).toBeNull()
  })
})

describe('tourEngine — adım süzme ve rol', () => {
  it('USER yönetim adımlarını görmez; ADMIN görür; AUDIT denetim adımını görür; mobilde mobile:false adımlar düşer', () => {
    const ids = (ctx) => filterSteps(MAIN_STEPS, ctx).map((s) => s.id)
    expect(ids({ role: 'USER' })).not.toContain('admin')
    expect(ids({ role: 'USER' })).toContain('palette')
    expect(ids({ role: 'ADMIN' })).toEqual(expect.arrayContaining(['admin', 'health', 'add-domain']))
    expect(ids({ role: 'TEAM_ADMIN' })).toContain('add-domain')
    expect(ids({ role: 'AUDIT' })).toContain('audit')
    expect(ids({ role: 'USER', isMobile: true })).not.toContain('palette')
    expect(filterSteps([{ id: 'x', when: () => { throw new Error('boom') } }], {})).toEqual([])
  })
  it('newStepsSince yalnız sürümden yeni adımları verir', () => {
    const steps = [{ id: 'a', since: 1 }, { id: 'b', since: 2 }, { id: 'c' }]
    expect(newStepsSince(steps, 1).map((s) => s.id)).toEqual(['b'])
    expect(newStepsSince(steps, 0)).toHaveLength(3)
  })
})

describe('tourEngine — otomatik başlatma kararı', () => {
  it('hiç görmemiş → welcome; dismissed → asla; completed güncel → hiç; snoozed tavana kadar; zorunlu şifre → hiç', () => {
    expect(decideAutoStart(null)).toBe('welcome')
    expect(decideAutoStart({ status: 'dismissed' })).toBeNull()
    expect(decideAutoStart({ status: 'completed', version: TOUR_VERSION })).toBeNull()
    expect(decideAutoStart({ status: 'snoozed', snoozed: 1 })).toBe('welcome')
    expect(decideAutoStart({ status: 'snoozed', snoozed: SNOOZE_MAX })).toBeNull()
    expect(decideAutoStart({ status: 'started' })).toBe('welcome')
    expect(decideAutoStart(null, { mustChangePwd: true })).toBeNull()
  })
  it('completed ama eski sürüm + yeni adım var → whatsnew (mobilde değil)', () => {
    const steps = [{ id: 'a', since: 1 }, { id: 'b', since: TOUR_VERSION + 1 }]
    expect(decideAutoStart({ status: 'completed', version: TOUR_VERSION - 1 }, { steps: [{ id: 'a', since: TOUR_VERSION }] })).toBe('whatsnew')
    expect(decideAutoStart({ status: 'completed', version: TOUR_VERSION - 1 }, { steps, isMobile: true })).toBeNull()
    expect(decideAutoStart({ status: 'completed', version: TOUR_VERSION - 1 }, { steps: [] })).toBeNull()
  })
})

describe('tourEngine — sayfa turu, liste, ayna, klavye', () => {
  it('sayfa çipi: turu olan ve görülmemiş sayfada; dismissed kullanıcıda asla', () => {
    expect(shouldOfferPageTour('all', null, PAGE_TOURS)).toBe(true)
    expect(shouldOfferPageTour('all', { status: 'completed', seen_pages: ['all'] }, PAGE_TOURS)).toBe(false)
    expect(shouldOfferPageTour('all', { status: 'dismissed' }, PAGE_TOURS)).toBe(false)
    expect(shouldOfferPageTour('forecast', { status: 'completed' }, PAGE_TOURS)).toBe(false)
  })
  it('başlangıç listesi ilerlemesi; sekme → madde eşlemesi izleme sayfalarını kapsar', () => {
    expect(checklistProgress(CHECKLIST_ITEMS, { tour: true, card: true })).toMatchObject({ done: 2, total: 6, complete: false })
    expect(checklistProgress(CHECKLIST_ITEMS, Object.fromEntries(CHECKLIST_ITEMS.map((i) => [i.key, true]))).complete).toBe(true)
    expect(CHECKLIST_BY_TAB.http).toBe('monitor'); expect(CHECKLIST_BY_TAB.ping).toBe('monitor'); expect(CHECKLIST_BY_TAB.all).toBe('all')
  })
  it('mergeState: sunucu varsa sunucu, yoksa ayna; keyAction metin alanlarında pasif', () => {
    expect(mergeState({ status: 'completed' }, { status: 'dismissed' })).toEqual({ status: 'completed' })
    expect(mergeState(null, { status: 'dismissed' })).toEqual({ status: 'dismissed' })
    expect(keyAction({ key: 'ArrowRight', target: { tagName: 'DIV' } })).toBe('next')
    expect(keyAction({ key: 'Escape', target: { tagName: 'BUTTON' } })).toBe('close')
    expect(keyAction({ key: 'ArrowRight', target: { tagName: 'INPUT' } })).toBeNull()
  })
})
