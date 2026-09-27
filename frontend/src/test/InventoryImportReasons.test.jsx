import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { TR, EN } from '../i18n/index.jsx'

/**
 * DİLLER-ARASI BEKÇİ: envanter CSV içe aktarımının satır nedeni (`reason`) her iki sözlükte de etiketli olmalı.
 *
 * `InventoryImportModal` nedeni `t(\`inv.importReason.${r.reason}\`)` ile basar; anahtar yoksa kullanıcı ham
 * `inv.importReason.unknown_noc_group` görür. 7/24 sütunları (2026-09-27) yeni bir kod ekledi ve etiketi atlandı —
 * hiçbir test kırılmadı (i18n-parity yalnız TR↔EN dengesine bakar, backend'in ürettiği KODA değil).
 *
 * Kaynak: `InventoryImportService` içindeki `new RowResult(line, domain, "<eylem>", "<neden>", …)` çağrıları.
 * Kırılırsa: yeni nedenin `inv.importReason.<kod>` anahtarını TR ve EN sözlüğüne ekleyin.
 */
const SERVICE = path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/InventoryImportService.java')

function backendReasons() {
  const src = fs.readFileSync(SERVICE, 'utf8')
  // new RowResult(line, domain, "error", "unknown_noc_group", List.of())  — neden null ise (create/update) eşleşmez
  const re = /new\s+RowResult\([^;]*?"(?:error|skip|create|update)"\s*,\s*"([a-z_]+)"/g
  return [...new Set([...src.matchAll(re)].map((m) => m[1]))].sort()
}

describe('inv.importReason.* — backend içe aktarma nedenleriyle senkron', () => {
  const codes = backendReasons()

  it('backend nedenleri okunabiliyor (boş liste = yol ya da sözdizimi değişmiş)', () => {
    expect(fs.existsSync(SERVICE), `bulunamadı: ${SERVICE}`).toBe(true)
    expect(codes.length).toBeGreaterThan(5)
    expect(codes).toContain('unknown_noc_group')
  })

  it('her neden kodunun TR ve EN etiketi var (ham anahtar ekrana düşmez)', () => {
    const missing = []
    for (const c of codes) {
      for (const [lang, dict] of [['TR', TR], ['EN', EN]]) {
        const v = dict[`inv.importReason.${c}`]
        if (typeof v !== 'string' || !v.trim()) missing.push(`${lang}: inv.importReason.${c}`)
      }
    }
    expect(missing).toEqual([])
  })

  it('TR ve EN etiketleri birbirinin kopyası değil (çeviri unutulmamış)', () => {
    for (const c of codes) expect(EN[`inv.importReason.${c}`], c).not.toBe(TR[`inv.importReason.${c}`])
  })
})
