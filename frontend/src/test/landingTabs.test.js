import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { LANDING_TAB_ORDER, landingTabOptions } from '../utils/landingTabs.js'

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

  it('her seçeneğin kenar çubuğu etiket anahtarı var', () => {
    for (const o of LANDING_TAB_ORDER) expect(o.labelKey, o.id).toMatch(/^nav\./)
  })
})
