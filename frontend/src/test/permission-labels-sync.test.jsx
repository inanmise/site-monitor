import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { TR, EN } from '../i18n/index.jsx'

/**
 * DİLLER-ARASI BEKÇİ: İzin Matrisi'ndeki HER kaynağın ve grubun iki dilde etiketi olmalı.
 *
 * `settings-labels-sync.test.jsx`'in birebir kardeşi ve aynı kusur bu ekranda da yaşandı:
 * backend kataloğuna 11 kaynak + 2 grup eklendi, `perm.res.*` / `perm.group.*` karşılıkları
 * eklenmedi. `useT` eksik anahtarda ANAHTARIN KENDİSİNİ döndürdüğü için ekranda
 * `perm.res.maintenance.view` gibi ham anahtarlar dizildi — ikisi akordiyon BAŞLIĞIydı, yani
 * en görünür yüzeyde.
 *
 * Neden statik kapılar yakalamadı: etiketler `t('perm.res.' + item.resource_key)` ile DİNAMİK
 * kuruluyor, dolayısıyla kaynakta o dize hiç geçmiyor. `i18n-used-keys` dinamik anahtarları
 * açıkça kapsam dışı bırakıyor, `i18n-parity` ise yalnız TR↔EN eşitliğine bakıyor — ikisinde de
 * eksik olduğu için sessiz kaldı. Tek doğruluk kaynağı backend kataloğu olduğundan kapı da
 * oradan okumak zorunda.
 */
const CATALOG = path.resolve(
  __dirname, '../../../backend/src/main/java/com/sitemonitor/service/PermissionCatalog.java')

const src = () => fs.readFileSync(CATALOG, 'utf8')

/** `List.of(...)` gövdesini adıyla çeker. */
function block(name) {
  const m = src().match(new RegExp(`${name}\\s*=\\s*List\\.of\\(([\\s\\S]*?)\\);`))
  return m ? m[1] : ''
}

/** r("kaynak", "grup", ...) → [{ key, group }] */
function rows(body) {
  return [...body.matchAll(/r\(\s*"([^"]+)"\s*,\s*"([^"]+)"/g)]
    .map(m => ({ key: m[1], group: m[2] }))
}

/**
 * UI'da görünen kaynaklar = ALL \ INTERNAL.
 * INTERNAL satırları matriste çizilmez (rol kapıları), dolayısıyla etiket de gerekmez.
 */
function visibleRows() {
  const internal = new Set(rows(block('INTERNAL')).map(r => r.key))
  const seen = new Set()
  return rows(block('ALL')).filter(r => {
    if (internal.has(r.key) || seen.has(r.key)) return false
    seen.add(r.key)
    return true
  })
}

describe('İzin Matrisi etiketleri ↔ backend kataloğu', () => {
  it('katalog gerçekten okunabiliyor (kapı boşa çalışmıyor)', () => {
    // Regex kayarsa liste boşalır ve aşağıdaki iddialar SESSİZCE geçerdi — vakum koruması.
    expect(visibleRows().length).toBeGreaterThan(30)
  })

  it('her kaynağın TR ve EN etiketi var', () => {
    const missing = []
    for (const r of visibleRows()) {
      const k = `perm.res.${r.key}`
      if (!TR[k]) missing.push(`TR ${k}`)
      if (!EN[k]) missing.push(`EN ${k}`)
    }
    expect(missing, 'eksik anahtar ekranda HAM olarak görünür').toEqual([])
  })

  it('her grubun TR ve EN etiketi var', () => {
    const missing = []
    for (const g of new Set(visibleRows().map(r => r.group))) {
      const k = `perm.group.${g}`
      if (!TR[k]) missing.push(`TR ${k}`)
      if (!EN[k]) missing.push(`EN ${k}`)
    }
    expect(missing, 'grup başlıkları akordiyonun en görünür yüzeyi').toEqual([])
  })
})
