import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { isSystemResolver, systemResolverKey, SYSTEM_RESOLVERS } from '../utils/resolvedBy.js'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..')

/**
 * "Alarmı kim/ne kapattı" ayrımı.
 *
 * `AlertEvent.resolvedBy` iki farklı şey taşıyor: gerçek bir SİCİL ya da bir SİSTEM JETONU.
 * Arayüz bunu ayırmıyordu ve `inventory_delete` gibi jetonlar KİŞİ ROZETİ olarak çiziliyordu —
 * ekranda "inventory_delete" adlı bir kullanıcı varmış gibi görünüyor, kullanıcı alarmın neden
 * kapandığını anlayamıyordu. (Envanterden sertifika silindiğinde alarm doğru biçimde çözülüyor;
 * eksik olan tek şey SEBEBİN görünmesiydi.)
 */
describe('resolvedBy — sistem jetonu vs kişi', () => {
  it('bilinen sistem jetonları KİŞİ sayılmaz', () => {
    for (const token of ['system', 'inventory_delete', 'inventory_deactivate']) {
      expect(isSystemResolver(token), token).toBe(true)
      expect(systemResolverKey(token)).toBeTruthy()
    }
  })

  it('sicil KİŞİdir — rozet olarak çizilmeli', () => {
    expect(isSystemResolver('N00001')).toBe(false)
    expect(systemResolverKey('N00001')).toBeNull()
  })

  it('null/undefined/sayı çökertmez', () => {
    for (const v of [null, undefined, 42, {}]) {
      expect(isSystemResolver(v)).toBe(false)
      expect(systemResolverKey(v)).toBeNull()
    }
  })

  it('KAYNAK TARAMASI: backend’in yazdığı her sistem jetonu burada TANIMLI', () => {
    // Kapı: backend yeni bir jeton eklerse (ör. "monitor_deleted") ve buraya eklenmezse,
    // ekranda yine kişi rozeti olarak çizilir. Bu test o anda kırmızıya döner.
    const esc = fs.readFileSync(path.join(ROOT, 'backend', 'src', 'main', 'java', 'com',
      'sitemonitor', 'service', 'EscalationService.java'), 'utf8')
    const tokens = [...esc.matchAll(/setResolvedBy\("([a-z_]+)"\)/g)].map(m => m[1])
    const closers = [...esc.matchAll(/closeOpenAlerts\([^,]+,\s*"([a-z_]+)"/g)].map(m => m[1])
    const all = [...new Set([...tokens, ...closers])]

    expect(all.length, 'tarama boşa düştü — regex bozulmuş olabilir').toBeGreaterThan(0)
    const missing = all.filter(tk => !Object.prototype.hasOwnProperty.call(SYSTEM_RESOLVERS, tk))
    expect(missing, 'backend bu jetonu yazıyor ama arayüz KİŞİ sanacak').toEqual([])
  })
})
