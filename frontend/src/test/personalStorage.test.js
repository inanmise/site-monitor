import { describe, it, expect, beforeEach } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { OWNER_KEY, claimPersonalStorage, clearPersonalStorage, personalKey } from '../utils/personalStorage.js'
import { RECENT_KEY as PALETTE_KEY, readRecents, writeRecents, pushRecent as pushPalette } from '../components/palette/paletteModel.js'
import { RECENT_KEY as DEXP_KEY, readRecent, pushRecent as pushDexp } from '../components/diagnostics/diagModel.js'

/**
 * 2026-09-27 regresyon taraması, FRONTEND B/9 — localStorage'ta kişisel veri, paylaşılan makine. Komut paleti son
 * kullanılanları (yöneticinin kullanıcı aramaları: ad + kullanıcı adı), alan adı teşhis son sorguları ve haftalık rapor
 * taslak yedekleri kullanıcıya ÖZEL değildi ve çıkışta silinmiyordu → aynı tarayıcıdaki sonraki kullanıcı görüyordu.
 */
const item = { kind: 'user', id: 7, label: 'Örnek Kişi', sub: 'N00001' }

describe('personalStorage — kullanıcıya göre ayrım + çıkışta temizlik', () => {
  beforeEach(() => localStorage.clear())

  it('paletin ve teşhisin son kullanılanları KULLANICI anahtarında tutulur; başka kullanıcı girince görünmez', () => {
    claimPersonalStorage('alice')
    writeRecents(pushPalette([], item))
    pushDexp('a.example.com')
    expect(localStorage.getItem(`${PALETTE_KEY}:alice`)).toContain('Örnek Kişi')
    expect(localStorage.getItem(`${DEXP_KEY}:alice`)).toContain('a.example.com')
    expect(localStorage.getItem(PALETTE_KEY)).toBeNull()          // paylaşılan (anahtarsız) kayıt yok

    claimPersonalStorage('bob')                                      // oturum düştü, başka biri girdi
    expect(readRecents()).toEqual([])
    expect(readRecent()).toEqual([])
    expect(Object.keys(localStorage).filter((k) => k.includes('alice'))).toEqual([])
  })

  it('AYNI kullanıcı geri gelince (oturum kesintisi) kayıtları — taslak yedeği dâhil — korunur', () => {
    claimPersonalStorage('alice')
    localStorage.setItem('wr.draft.12', '{"content_json":"{}"}')
    writeRecents(pushPalette([], item))
    claimPersonalStorage('alice')
    expect(localStorage.getItem('wr.draft.12')).not.toBeNull()
    expect(readRecents()).toHaveLength(1)
  })

  it('çıkış: kişisel kayıtların HEPSİ (her kullanıcı + eski anahtarsız + wr.draft.*) silinir, ilgisiz ayarlar kalır', () => {
    localStorage.setItem(PALETTE_KEY, '[]')                          // eski sürümden anahtarsız kalıntı
    localStorage.setItem(`${PALETTE_KEY}:alice`, '[]')
    localStorage.setItem(`${DEXP_KEY}:bob`, '[]')
    localStorage.setItem('wr.draft.5', '{}')
    localStorage.setItem(OWNER_KEY, 'alice')
    localStorage.setItem('sm.pageSize.incidents', '50')              // kişisel değil — dokunulmaz
    clearPersonalStorage()
    expect(Object.keys(localStorage).sort()).toEqual(['sm.pageSize.incidents'])
  })

  it('sahip bilinmiyorsa (ilk giriş / eski sürüm) anahtarsız kalıntılar girişte temizlenir', () => {
    localStorage.setItem(PALETTE_KEY, JSON.stringify([{ kind: 'user', id: '1', label: 'Başkası' }]))
    localStorage.setItem('wr.draft.9', '{}')
    claimPersonalStorage('carol')
    expect(localStorage.getItem(PALETTE_KEY)).toBeNull()
    expect(localStorage.getItem('wr.draft.9')).toBeNull()
    expect(localStorage.getItem(OWNER_KEY)).toBe('carol')
    expect(personalKey('x')).toBe('x:carol')
  })

  it('App: iki çıkış yolu (elle + boşta kalma) temizler, iki giriş yolu (form + oturum geri yükleme) sahiplenir', () => {
    const src = fs.readFileSync(path.resolve(__dirname, '..', 'App.jsx'), 'utf8')
    const body = (startRe) => {
      const m = startRe.exec(src)
      expect(m, String(startRe)).not.toBeNull()
      // Pencere fonksiyonun BAŞINI kapsar (sıra denetimi): 2026-10-09'da çıkışa oturum verisi sıfırlama eklendi → 1400
      return src.slice(m.index, m.index + 1400)
    }
    expect(body(/async function handleLogout\(\)/)).toMatch(/clearPersonalStorage\(\)[\s\S]*setUser\(null\)/)
    expect(body(/const doAutoLogout = async \(\) =>/)).toMatch(/clearPersonalStorage\(\)[\s\S]*setUser\(null\)/)
    expect(body(/function handleLogin\(userData\)/)).toMatch(/claimPersonalStorage\(userData\.username\)[\s\S]*setUser\(userData\.username\)/)
    expect(body(/api\.getMe\(\)\.then\(/)).toMatch(/claimPersonalStorage\(res\.username\)[\s\S]*setUser\(res\.username\)/)
  })
})
