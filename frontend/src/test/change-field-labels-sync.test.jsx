import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { TR, EN } from '../i18n/index.jsx'

/**
 * DİLLER-ARASI BEKÇİ: değişiklik geçmişinde görünebilecek HER alanın etiketi olmalı.
 *
 * Geçmiş satırları ham diff taşır: `{"crawlMaxPages":{"from":50,"to":80}}`. Ekranda etiketi
 * yoksa `fieldLabel` sessizce ham anahtarı basar — kullanıcı "crawlMaxPages" görür. Bu sessiz
 * bozulma: hiçbir test kırılmaz, hiçbir hata çıkmaz, yalnız ekran yarı-İngilizce/teknik olur.
 *
 * Alan listeleri backend'de diff'i ÜRETEN dizilerdir; oradan yeni bir alan eklenip buraya
 * etiket yazılmazsa bu test kırılır. İki dil de zorunlu (`i18n-parity` yalnız TR↔EN dengesine
 * bakar, anahtarın VARLIĞINA değil).
 */
const BACKEND = path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor')

const SOURCES = [
  { file: 'controller/MonitoringController.java', name: 'MON_FIELDS' },
  { file: 'controller/MonitoringController.java', name: 'SCRIPTED_FIELDS' },
  { file: 'controller/MonitoringController.java', name: 'PAGESPEED_FIELDS' },
  { file: 'controller/AdminController.java', name: 'INVENTORY_FIELDS' },
  { file: 'controller/MaintenanceController.java', name: 'MAINTENANCE_FIELDS' },
]

/** `private static final String[] X = { "a", "b" };` → ['a','b'] */
function javaFields({ file, name }) {
  const src = fs.readFileSync(path.join(BACKEND, file), 'utf8')
  const m = new RegExp(`String\\[\\]\\s+${name}\\s*=\\s*\\{([\\s\\S]*?)\\}`).exec(src)
  if (!m) return []
  return [...m[1].matchAll(/"([^"]+)"/g)].map(x => x[1])
}

describe('chg.field.* etiketleri — backend alan listeleriyle senkron', () => {
  const all = SOURCES.map(s => ({ ...s, fields: javaFields(s) }))

  it('backend dizileri okunabiliyor (boş liste = yol veya sözdizimi değişmiş)', () => {
    for (const s of all) {
      expect(s.fields.length, `${s.name} okunamadı`).toBeGreaterThan(5)
    }
  })

  it.each(all)('$name alanlarının TÜMÜ TR ve EN sözlüğünde etiketli', ({ fields }) => {
    const missingTr = fields.filter(f => !TR[`chg.field.${f}`])
    const missingEn = fields.filter(f => !EN[`chg.field.${f}`])
    expect(missingTr, 'TR etiketi eksik').toEqual([])
    expect(missingEn, 'EN etiketi eksik').toEqual([])
  })

  it('etiketler iki dilde de FARKLI yazılmış olmalı — kopyala-yapıştır TR metni kalmasın', () => {
    // Bilinçli istisnalar: özel ad, kısaltma ya da sektörde İngilizce kullanılan terim
    // ("SSL pinning", "JKS keystore") olduğu için iki dilde aynı yazılanlar.
    const SAME_BY_DESIGN = new Set(['port', 'url', 'tier', 'protocol', 'tags', 'openshift',
      'netscaler', 'teamId', 'method', 'mode', 'domain',
      'script', 'sslPinning', 'jksKeystore', 'wafEnabled'])
    const keys = [...new Set(all.flatMap(s => s.fields))]
    const suspicious = keys.filter(k =>
      !SAME_BY_DESIGN.has(k) && TR[`chg.field.${k}`] === EN[`chg.field.${k}`])
    expect(suspicious, 'TR ve EN etiketi birebir aynı — çeviri unutulmuş olabilir').toEqual([])
  })

  it('süre birimleri de çevrilmiş — İngilizce ekranda "dk" görünmemeli', () => {
    for (const k of ['chg.unitSec', 'chg.unitMin', 'chg.unitHour']) {
      expect(TR[k], `${k} TR eksik`).toBeTruthy()
      expect(EN[k], `${k} EN eksik`).toBeTruthy()
      expect(EN[k]).not.toBe(TR[k])
    }
  })
})
