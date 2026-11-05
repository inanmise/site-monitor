import { describe, it, expect } from 'vitest'
import { ADV_FIELD_KEYS, validateHttpAdvanced } from '../components/http/httpAdvancedModel.js'

/**
 * HTTP "Gelişmiş istek" modelinin GERİYE UYUMLU seçenekleri (kw 2026-11-05 — Keyword formu parçaları yeniden kullanır):
 * `keys` alan hata anahtarlarını ezer, `parts` JSON / yavaşlık denetimini kapatır. Verilmezse çıktı birebir eskisi gibi
 * (HTTP formunun kendi kilidi: HttpAdvancedRequest.test.jsx değişmeden yeşil).
 */
const BAD = {
  customHeaders: 'satır', basicAuthUser: 'a:b', requestBody: 'x'.repeat(65 * 1024), requestContentType: 'bozuk',
  jsonPath: '$..', slowResponseEnabled: true, slowThresholdMs: 5,
}

describe('validateHttpAdvanced — seçenekler', () => {
  it('varsayılan: adv* anahtarları, altı hata', () => {
    expect(ADV_FIELD_KEYS).toEqual({ headers: 'advHeaders', basicUser: 'advBasicUser', body: 'advBody', contentType: 'advContentType',
      jsonPath: 'advJsonPath', slowThreshold: 'advSlowThreshold' })
    const errs = validateHttpAdvanced(BAD, { method: 'POST', canEditHeaders: true })
    expect(Object.keys(errs).sort()).toEqual(Object.values(ADV_FIELD_KEYS).sort())
  })

  it('keys: kısmi ezme — verilmeyenler varsayılan kalır', () => {
    const errs = validateHttpAdvanced(BAD, { method: 'POST', canEditHeaders: true,
      keys: { headers: 'customHeaders', basicUser: 'basicAuthUser', body: 'requestBody', contentType: 'requestContentType' } })
    expect(Object.keys(errs).sort()).toEqual(['advJsonPath', 'advSlowThreshold', 'basicAuthUser', 'customHeaders', 'requestBody', 'requestContentType'])
  })

  it('parts: JSON ve yavaşlık denetimi kapatılabilir', () => {
    const errs = validateHttpAdvanced(BAD, { method: 'POST', canEditHeaders: true, parts: { json: false, slow: false } })
    expect(errs).not.toHaveProperty('advJsonPath')
    expect(errs).not.toHaveProperty('advSlowThreshold')
    expect(errs).toHaveProperty('advBody')
  })

  it('metinler t ile çözülür (anahtar + argümanlar)', () => {
    const errs = validateHttpAdvanced({ basicAuthUser: 'a:b' }, { t: (k) => `T(${k})`, keys: { basicUser: 'u' } })
    expect(errs).toEqual({ u: 'T(http.adv.errBasicUser)' })
  })
})
