import { describe, it, expect } from 'vitest'
import { maskedColumnsHeader } from '../api/client.js'

/** SQL Playground tam dışa aktarım: `X-Masked-Columns` yüzde kodlu ad listesi (sunucu `headerList`) doğru çözülür. */
describe('X-Masked-Columns başlığı', () => {
  it('virgülle ayrılır, her ad UTF-8 yüzde kodundan çözülür; bozuk kod olduğu gibi kalır', () => {
    expect(maskedColumnsHeader('password_hash,parola%C5%9Fifre,a%2Cb, token')).toEqual(['password_hash', 'parolaşifre', 'a,b', 'token'])
    expect(maskedColumnsHeader('bad%E0%A4%A')).toEqual(['bad%E0%A4%A'])
    expect(maskedColumnsHeader('')).toEqual([])
    expect(maskedColumnsHeader(null)).toEqual([])
  })
})
