import { describe, it, expect } from 'vitest'
import { SCRIPTED_TEMPLATES } from '../components/scriptedTemplates.js'

/**
 * Şablonlar kullanıcının BAŞLANGIÇ NOKTASI — buradaki bir tuzak, kopyalayan herkese yayılır.
 * Bu yüzden iki kural teste bağlandı: (1) istekler açık timeout taşımalı, (2) script'ler
 * ortamdaki k6 sürümünün ayrıştırabildiği sözdizimini kullanmalı.
 */
describe('SCRIPTED_TEMPLATES', () => {
  const byId = id => SCRIPTED_TEMPLATES.find(t => t.id === id)

  it('her şablonun kimliği, iki dilde adı/açıklaması/senaryosu ve script gövdesi var', () => {
    for (const tpl of SCRIPTED_TEMPLATES) {
      expect(tpl.id, 'id').toBeTruthy()
      expect(tpl.name.tr && tpl.name.en, `${tpl.id} ad`).toBeTruthy()
      expect(tpl.desc.tr && tpl.desc.en, `${tpl.id} açıklama`).toBeTruthy()
      // `when` = kullanım senaryosu; seçicinin altındaki kutuda gösterilir (TemplateInfo).
      expect(tpl.when && tpl.when.tr && tpl.when.en, `${tpl.id} kullanım senaryosu`).toBeTruthy()
      expect(Array.isArray(tpl.env), `${tpl.id} env dizisi`).toBe(true)
      expect(tpl.script, `${tpl.id} script`).toContain('export default function')
    }
  })

  it('şablon kimlikleri tekil (seçicide value + React key olarak kullanılıyor)', () => {
    const ids = SCRIPTED_TEMPLATES.map(t => t.id)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('her şablon, kullandığı __ENV adlarını env listesinde tanımlar', () => {
    // Tanımsız __ENV, kaydetme sırasında uyarı üretir (auditEnvReferences) — şablon o uyarıyla gelmemeli.
    for (const tpl of SCRIPTED_TEMPLATES) {
      const defined = new Set(tpl.env.map(e => e.name))
      const referenced = [...tpl.script.matchAll(/__ENV\.([A-Za-z_$][\w$]*)/g)].map(m => m[1])
      for (const name of referenced) {
        expect(defined.has(name), `${tpl.id}: __ENV.${name} tanımlı değil`).toBe(true)
      }
    }
  })

  it('hiçbir şablon vus/iterations/stages tanımlamaz (motor --vus 1 --iterations 1 ile ezer)', () => {
    for (const tpl of SCRIPTED_TEMPLATES) {
      expect(tpl.script, `${tpl.id}: vus`).not.toMatch(/\bvus\s*:/)
      expect(tpl.script, `${tpl.id}: iterations`).not.toMatch(/\biterations\s*:/)
      expect(tpl.script, `${tpl.id}: stages`).not.toMatch(/\bstages\s*:/)
    }
  })

  it('HER şablondaki her http isteği açık timeout taşır', () => {
    // Tek bir timeout'suz istek, o şablonu kopyalayan herkese sebepsiz "Süre aşımı" tuzağını taşır.
    for (const tpl of SCRIPTED_TEMPLATES) {
      const requests = (tpl.script.match(/http\.(get|post|put|del|patch)\(/g) || []).length
      const timeouts = (tpl.script.match(/timeout:\s*'\d+s'/g) || []).length
      expect(requests, `${tpl.id}: istek yok`).toBeGreaterThan(0)
      expect(timeouts, `${tpl.id}: ${requests} istek var, ${timeouts} timeout`).toBeGreaterThanOrEqual(requests)
    }
  })

  it('smoke şablonu env GEREKTİRMEZ ve kurumsal hedefi doğrular', () => {
    const smoke = byId('smoke-health')
    expect(smoke).toBeDefined()
    expect(smoke.env).toEqual([])
    expect(smoke.script).not.toContain('__ENV')          // "env gerektirmez" vaadi
    expect(smoke.script).toContain('https://www.akbank.com')
    // Gövde uzunluğu YETMEZ: engelleyen vekil sayfasının da gövdesi var. İçerik doğrulanmalı.
    expect(smoke.script).toContain("indexOf('Akbank')")
  })

  it('smoke şablonundaki istek AÇIK ve süreç timeout undan kısa bir timeout taşır', () => {
    // Tuzak (prod'da 294 koşum): k6'nın varsayılan istek timeout'u 60 sn, monitörün varsayılan
    // süreç timeout'u da 60 sn. Eşit olunca istek kendi kendine düşemeden süreci öldürüyoruz ve
    // k6 sebebi yazamıyor → ekranda sebepsiz "Süre aşımı". Açık timeout bunu FAIL + sebep yapar.
    const smoke = byId('smoke-health')
    const m = /timeout:\s*'(\d+)s'/.exec(smoke.script)
    expect(m, 'smoke şablonunda açık istek timeout u yok').not.toBeNull()
    expect(Number(m[1])).toBeLessThan(60)
  })

  it('hiçbir şablon k6 0.49 (gömülü Babel 6) ayrıştıramadığı sözdizimini kullanmaz', () => {
    // `?.`, `??` ve nesne spread'i bu motorda SyntaxError verir; şablon buna düşerse kullanıcı
    // hatayı kendi yazım hatası sanar (bkz. scriptedExitCodes.diagnosisHint / hintOldEngine).
    for (const tpl of SCRIPTED_TEMPLATES) {
      expect(tpl.script, `${tpl.id}: optional chaining`).not.toMatch(/\?\./)
      expect(tpl.script, `${tpl.id}: nullish coalescing`).not.toMatch(/\?\?/)
      expect(tpl.script, `${tpl.id}: nesne spread`).not.toMatch(/\{\s*\.\.\./)
    }
  })
})
