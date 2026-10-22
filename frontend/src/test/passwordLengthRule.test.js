import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { PWD_MAX_LENGTH, PWD_MIN_LENGTH } from '../components/admin/PasswordChangeModal.jsx'

/**
 * Parola uzunluk kapısı (UIA-H1, 2026-10-22): istemci kuralı sunucunun VARSAYILANLARIYLA aynı olmalı. Eskiden istemci 6–10
 * diyordu (sunucu 2026-06-25'te 64'e çıkmıştı) ve alanlar 10 karakterde sessizce kesiliyordu. Sunucu varsayılanı değişirse
 * bu test kırmızıdır — PasswordChangeModal sabitlerini birlikte güncelleyin.
 */
describe('parola uzunluk kuralı = sunucu varsayılanı', () => {
  it('application.properties site.monitor.password.min-length / max-length varsayılanlarıyla eşit', () => {
    const props = fs.readFileSync(path.resolve(__dirname, '../../../backend/src/main/resources/application.properties'), 'utf8')
    const min = /^site\.monitor\.password\.min-length=\$\{PASSWORD_MIN_LENGTH:(\d+)\}/m.exec(props)?.[1]
    const max = /^site\.monitor\.password\.max-length=\$\{PASSWORD_MAX_LENGTH:(\d+)\}/m.exec(props)?.[1]
    expect(Number(min)).toBe(PWD_MIN_LENGTH)
    expect(Number(max)).toBe(PWD_MAX_LENGTH)
  })
})
