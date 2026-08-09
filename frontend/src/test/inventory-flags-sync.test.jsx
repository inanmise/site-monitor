import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { INVENTORY_FLAGS, emptyFlags } from '../utils/inventoryFlags.js'

/**
 * DİLLER-ARASI BEKÇİ: frontend bayrak listesi ile backend kataloğu birebir aynı olmalı.
 *
 * Zincir üç halkalı:
 *   entity (CertificateInventory)  ←reflection testi→  CertificateInventoryOps.ALL  ←BU TEST→  INVENTORY_FLAGS
 *
 * Ortadaki halka backend'de `CertificateInventoryOpsTest` ile kilitli. Bu test son halkayı
 * kapatır. Gerekçe somut: liste eskiden frontend'de üç kez kopyalanmıştı ve `exportInventory.js`
 * kopyası `use_proxy`'yi kaçırdığı için CSV/PDF çıktısı 13 yerine 12 bayrak bastı (v20.8.0'a
 * kadar). Artık frontend'de tek liste var; bu test onun backend'den ayrışmasını engelliyor.
 *
 * Java kaynağını okuyoruz çünkü iki dil arasında çalışma-zamanı bağı yok — tek doğrulama
 * noktası kaynak metni. Kırılırsa: iki listeden hangisi eksikse ona ekleyin (sıra da önemli).
 */
const OPS_JAVA = path.resolve(
  __dirname, '../../../backend/src/main/java/com/sitemonitor/service/CertificateInventoryOps.java',
)

/** getExternalVendor → external_vendor */
const getterToSnake = (getter) =>
  getter.replace(/^get/, '').replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase()

function backendFlags() {
  const src = fs.readFileSync(OPS_JAVA, 'utf8')
  // new Flag("Netscaler", CertificateInventory::getNetscaler)
  const re = /new\s+Flag\(\s*"([^"]*)"\s*,\s*CertificateInventory::(get\w+)\s*\)/g
  const out = []
  let m
  while ((m = re.exec(src)) !== null) out.push({ label: m[1], key: getterToSnake(m[2]) })
  return out
}

describe('envanter bayrakları — frontend ↔ backend senkron', () => {
  const backend = backendFlags()

  it('backend kataloğu okunabiliyor (boş liste = yanlış yol veya değişmiş sözdizimi)', () => {
    expect(fs.existsSync(OPS_JAVA), `bulunamadı: ${OPS_JAVA}`).toBe(true)
    expect(backend.length).toBeGreaterThan(5)
  })

  it('anahtarlar ve SIRA birebir aynı', () => {
    expect(INVENTORY_FLAGS.map(f => f.key)).toEqual(backend.map(f => f.key))
  })

  it('use_proxy her iki listede de var (v20.8.0 öncesi export bunu kaçırıyordu)', () => {
    expect(INVENTORY_FLAGS.map(f => f.key)).toContain('use_proxy')
    expect(backend.map(f => f.key)).toContain('use_proxy')
  })

  it('her bayrağın i18n etiket anahtarı tanımlı ve benzersiz', () => {
    const labelKeys = INVENTORY_FLAGS.map(f => f.labelKey)
    labelKeys.forEach(k => expect(k).toMatch(/^inv\.form/))
    expect(new Set(labelKeys).size).toBe(labelKeys.length)
  })

  it('emptyFlags() her bayrağı false ile doldurur', () => {
    const empty = emptyFlags()
    expect(Object.keys(empty)).toEqual(INVENTORY_FLAGS.map(f => f.key))
    expect(Object.values(empty).every(v => v === false)).toBe(true)
  })
})
