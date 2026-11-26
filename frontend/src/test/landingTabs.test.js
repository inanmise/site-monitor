import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { LANDING_TAB_ORDER, LANDING_SCOPE_WAIT_MS, landingTabOptions, channelReportsVisibility } from '../utils/landingTabs.js'

/**
 * Açılış sekmesi seçenekleri (öneri 23) ↔ App.jsx VALID_TABS. Seçicide olup App'in tanımadığı bir kimlik sessizce Pano'ya
 * düşerdi; App'e eklenen yeni sekme de seçicide unutulmasın — Pano dışındaki HER geçerli sekme listede.
 */
function validTabs() {
  // VALID_TABS 2026-10-08'den beri tek kaynakta: utils/appRoutes.js
  const src = fs.readFileSync(path.resolve(__dirname, '../utils/appRoutes.js'), 'utf8')
  const start = src.indexOf('export const VALID_TABS = new Set([')
  if (start < 0) throw new Error('VALID_TABS utils/appRoutes.js içinde bulunamadı')
  const body = src.slice(start, src.indexOf('])', start))
  return [...body.matchAll(/'([\w-]+)'/g)].map((m) => m[1])
}

describe('açılış sekmesi seçenekleri', () => {
  const valid = validTabs()

  it('liste = VALID_TABS − Pano (aynı küme, tekrar yok)', () => {
    const ids = LANDING_TAB_ORDER.map((o) => o.id)
    expect(new Set(ids).size).toBe(ids.length)
    expect(new Set(ids)).toEqual(new Set(valid.filter((id) => id !== 'dashboard')))
  })

  it('görünürlük kuralları App/Nav ile aynı: Ayarlar yalnız ADMIN, SQL Playground yalnız global yönetici, Haftalık Raporlar modül açıksa', () => {
    const user = landingTabOptions({ systemRole: 'USER', globalAdmin: false, weeklyReportsVisible: false }).map((o) => o.id)
    expect(user).not.toContain('settings')
    expect(user).not.toContain('sqlplayground')
    expect(user).not.toContain('weeklyreports')
    expect(user).toContain('monitoring')
    const scoped = landingTabOptions({ systemRole: 'ADMIN', globalAdmin: false, weeklyReportsVisible: true }).map((o) => o.id)
    expect(scoped).toContain('settings')
    expect(scoped).toContain('weeklyreports')
    expect(scoped).not.toContain('sqlplayground')
    expect(landingTabOptions({ systemRole: 'ADMIN', globalAdmin: true }).map((o) => o.id)).toContain('sqlplayground')
  })

  it('H paketi 2026-11-26: Yönetici Özeti kapsamlı yönetici (ADMIN, global değil) için de var; USER / TEAM_ADMIN için yok', () => {
    const ids = (o) => landingTabOptions(o).map((x) => x.id)
    expect(ids({ systemRole: 'ADMIN', globalAdmin: false })).toContain('executive')
    expect(ids({ systemRole: 'AUDIT', globalAdmin: false })).toContain('executive')
    expect(ids({ systemRole: 'ADMIN', globalAdmin: true })).toContain('executive')
    expect(ids({ systemRole: 'USER', globalAdmin: false })).not.toContain('executive')
    expect(ids({ systemRole: 'TEAM_ADMIN', globalAdmin: false })).not.toContain('executive')
  })

  it('H paketi 2026-11-26: Kanal raporları menü kuralıyla — false gizler, null / verilmedi (bilinmiyor) gösterir', () => {
    const ids = (o) => landingTabOptions({ systemRole: 'USER', ...o }).map((x) => x.id)
    expect(ids({ channelReportsVisible: false })).not.toContain('channelreports')
    expect(ids({ channelReportsVisible: true })).toContain('channelreports')
    expect(ids({ channelReportsVisible: null })).toContain('channelreports')
    expect(ids({})).toContain('channelreports')
  })

  it('açılış kararının izin beklemesi sınırlı: 5 sn (inceleme bulgusu 6)', () => {
    expect(LANDING_SCOPE_WAIT_MS).toBe(5000)
  })

  it('channelReportsVisibility: okunmadan ve okuma başarısızsa null (bilinmiyor); okununca menü kuralı', () => {
    expect(channelReportsVisibility(null, false, false)).toBeNull()
    expect(channelReportsVisibility({ channel_reports: false }, false, false)).toBeNull()
    expect(channelReportsVisibility({ channel_reports: false }, true, false)).toBe(false)
    expect(channelReportsVisibility({ channel_reports: true }, true, false)).toBe(true)
    expect(channelReportsVisibility(null, true, false)).toBe(true)        // eski sunucu: kapsam yok → görünür
    expect(channelReportsVisibility(null, false, true)).toBeNull()        // okuma başarısız: bilinmiyor (≤ 5 sn sonra Pano)
    expect(channelReportsVisibility({ channel_reports: true }, true, true)).toBe(true)   // önceki anlık görüntü korunur
  })

  it('her seçeneğin kenar çubuğu etiket anahtarı var', () => {
    for (const o of LANDING_TAB_ORDER) expect(o.labelKey, o.id).toMatch(/^nav\./)
  })
})
