import { describe, it, expect } from 'vitest'
import { TOUR_VERSION, decideAutoStart, newStepsSince, filterSteps } from '../components/tour/tourEngine.js'
import { MAIN_STEPS } from '../components/tour/tourSteps.js'

// Güncel tanıtım turu (2026-10-02, öneri 24): yeni ana ekranlar `since: 2` adımlarıyla eklendi; TOUR_VERSION 2 →
// turu v1'de TAMAMLAMIŞ kullanıcıya yalnız bu adımlar "Yenilikler" olarak sunulur (tamamı yeniden değil);
// kapatan kullanıcıya hiçbir şey çıkmaz. Eski adım kimlikleri korunur.
const V1_IDS = ['welcome', 'sidebar', 'dashboard', 'filters', 'check-now', 'add-domain', 'card', 'modal', 'all', 'monitoring',
  'alerts', 'reports', 'admin', 'health', 'audit', 'palette', 'inbox', 'user', 'help', 'done']
const NEW_IDS = ['monitoring', 'favorites', 'saved-views', 'status-page', 'alert-history', 'shortcuts']

describe('ürün turu — sürüm 2 yenilikleri', () => {
  it('eski adım kimlikleri yerinde; sıra korunur', () => {
    const ids = MAIN_STEPS.map((s) => s.id)
    for (const id of V1_IDS) expect(ids, id).toContain(id)
    const v1Order = ids.filter((id) => V1_IDS.includes(id))
    expect(v1Order).toEqual(V1_IDS)
  })

  it('yalnız yeni/güncellenen adımlar since 2; "Yenilikler" turu tam olarak bunlar', () => {
    expect(TOUR_VERSION).toBe(2)
    expect(newStepsSince(MAIN_STEPS, 1).map((s) => s.id)).toEqual(NEW_IDS)
    expect(newStepsSince(MAIN_STEPS, 2)).toEqual([])
  })

  it('izleme adımı HTTP sekmesi yerine İzleme Panosu; yeni ekran adımlarının hedefleri', () => {
    const by = Object.fromEntries(MAIN_STEPS.map((s) => [s.id, s]))
    expect(by.monitoring).toMatchObject({ target: 'nav-tab-monitoring', before: { reveal: 'monitoring' } })
    expect(by['status-page']).toMatchObject({ target: 'nav-tab-status', before: { reveal: 'status' } })
    expect(by['alert-history']).toMatchObject({ target: 'nav-tab-alerthistory', before: { reveal: 'alerthistory' } })
    expect(by.favorites).toMatchObject({ target: 'mo-views', tab: 'monitoring' })
    expect(by['saved-views']).toMatchObject({ target: 'saved-views', tab: 'monitoring' })
    expect(by.shortcuts).toMatchObject({ center: true, mobile: false })
  })

  it('otomatik başlatma: v1 tamamlayan → whatsnew; telefonda, kapatanda ve v2 tamamlayanda yok; hiç görmeyen → tam tur', () => {
    expect(decideAutoStart({ status: 'completed', version: 1 }, { steps: MAIN_STEPS })).toBe('whatsnew')
    expect(decideAutoStart({ status: 'completed', version: 1 }, { steps: MAIN_STEPS, isMobile: true })).toBeNull()
    expect(decideAutoStart({ status: 'dismissed', version: 1 }, { steps: MAIN_STEPS })).toBeNull()
    expect(decideAutoStart({ status: 'completed', version: 2 }, { steps: MAIN_STEPS })).toBeNull()
    expect(decideAutoStart(null, { steps: MAIN_STEPS })).toBe('welcome')
  })

  it('kısayol adımı telefonda düşer; diğer yeni adımlar her rolde görünür', () => {
    const ids = (ctx) => filterSteps(MAIN_STEPS, ctx).map((s) => s.id)
    expect(ids({ role: 'USER', isMobile: true })).not.toContain('shortcuts')
    for (const role of ['USER', 'AUDIT', 'ADMIN']) {
      expect(ids({ role })).toEqual(expect.arrayContaining(NEW_IDS))
    }
  })
})
