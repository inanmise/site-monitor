import { vi } from 'vitest'

/**
 * Test mock'larındaki `api` nesnesine YEDEK katman geçirir: elle yazılmış her uç aynen
 * kalır, TANIMLANMAMIŞ her metot ilk erişimde otomatik üretilir ve başarılı boş yanıt döner.
 *
 * NEDEN VAR: 2026-08-19'da CI, testlerin HEPSİ geçtiği hâlde kırmızıya döndü —
 * `SystemHealth.test.jsx`'in elle sayılan mock listesinde `getLoginSeries` yoktu.
 * Bileşen o ucu çağırınca efekt içinde `is not a function` ile REDDEDİLEN bir promise
 * oluştu; yerelde zamanlama nedeniyle yüzeye çıkmadı, CI'da "1 unhandled rejection" oldu.
 * Elle sayılan mock listesi, bileşen yeni bir uç çağırdığı gün sessizce eksik kalır —
 * bu, düzelttiğimiz asıl hatayla (eksik import) aynı sürüklenme sınıfıdır.
 *
 * Kullanım:
 *   const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
 *   vi.mock('../api/client', () => ({
 *     api: withApiFallback({ monitoring: { getX: vi.fn() } }),
 *   }))
 *
 * Davranış notları:
 * - Var olan anahtarlar DEĞİŞTİRİLMEZ; testin kurduğu fixture aynen geçerlidir.
 * - Otomatik üretilen metotlar kararlıdır (aynı vi.fn örneği döner), böylece
 *   `expect(api.x.y).toHaveBeenCalled()` ve `mockResolvedValue` çalışır.
 * - `vi.clearAllMocks()` yalnız çağrı kayıtlarını siler, implementasyonu korur.
 */

const CACHE = Symbol('withApiFallback.cache')

/** Düz nesne mi (sınıf örneği / dizi / fonksiyon değil)? */
function isPlainObject(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v)
}

/**
 * Hem çağrılabilir hem gezilebilir mock: `x()` başarılı boş yanıt döner, `x.y.z()` de çalışır.
 * Bilinmeyen bir adın metot mu ad alanı mı olduğunu önceden bilemeyiz; bu ikisini birden
 * karşılar. Çocuklar önbelleklenir, böylece aynı yol her seferinde AYNI vi.fn örneğini verir
 * (`toHaveBeenCalled` / `mockResolvedValue` bu kararlılığa dayanır).
 */
function deepMock() {
  const fn = vi.fn(() => Promise.resolve({ success: true, data: [] }))
  const children = new Map()
  return new Proxy(fn, {
    get(t, prop, receiver) {
      if (prop === 'then' || prop === 'catch' || prop === 'finally') return undefined
      if (typeof prop === 'symbol') return Reflect.get(t, prop, receiver)
      // vi.fn'in kendi yüzeyi (mock, mockResolvedValue, calls…) aynen geçmeli.
      if (prop in t) return Reflect.get(t, prop, receiver)
      if (!children.has(prop)) children.set(prop, deepMock())
      return children.get(prop)
    },
  })
}

export function withApiFallback(target = {}) {
  if (!isPlainObject(target)) return target
  if (target[CACHE]) return target[CACHE]

  const wrapped = new Proxy(target, {
    get(t, prop, receiver) {
      // Promise gibi davranmasın: await edilirse sonsuz zincire girer.
      if (prop === 'then' || prop === 'catch' || prop === 'finally') return undefined
      if (typeof prop === 'symbol') return Reflect.get(t, prop, receiver)

      if (prop in t) {
        const value = t[prop]
        // Alt ad alanları da (api.admin, api.monitoring…) yedek katmanı devralır.
        return isPlainObject(value) ? withApiFallback(value) : value
      }

      // Bilinmeyen uç: kararlı bir "hem çağrılabilir hem gezilebilir" mock üret ve SAKLA.
      // Hem `api.admin.yeniUc()` hem `api.yeniAlan.yeniUc()` çalışmalı — bilinmeyen bir adın
      // metot mu ad alanı mı olduğunu önceden bilemeyiz.
      t[prop] = deepMock()
      return t[prop]
    },
    has() { return true },
  })

  Object.defineProperty(target, CACHE, { value: wrapped, enumerable: false, configurable: true })
  return wrapped
}

export default withApiFallback
