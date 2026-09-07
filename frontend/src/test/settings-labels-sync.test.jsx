import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { TR, EN } from '../i18n/index.jsx'

/**
 * DİLLER-ARASI BEKÇİ: Genel Ayarlar ekranındaki HER ayarın iki dilde etiketi olmalı.
 *
 * Bu tam olarak yaşandı ve UZUN süre görünmedi: backend kataloğuna 86 ayar eklendi,
 * `general.lbl.*` / `general.grp.*` karşılıkları eklenmedi. `useT` eksik anahtarda
 * ANAHTARIN KENDİSİNİ döndürdüğü için (i18n/index.jsx `dict[key] ?? key`) ekranda
 * `general.lbl.site.monitor.pagespeed.max-total-kb` gibi ham anahtarlar dizildi.
 *
 * Neden hiçbir test yakalamadı: etiketler `t('general.lbl.' + it.key)` ile DİNAMİK kuruluyor,
 * yani kaynakta "general.lbl.site.monitor..." diye bir dize hiç geçmiyor —
 * `i18n-used-keys` taraması dinamik anahtarları açıkça kapsam dışı bırakıyor ve
 * `i18n-parity` yalnız TR↔EN eşitliğine bakıyor (ikisinde de eksikse sessiz kalır).
 * Tek kaynak backend kataloğu olduğu için kapı da oradan okumak zorunda.
 *
 * Kardeş kapı: `change-kinds-sync.test.jsx` (aynı desen, izleme türleri için).
 */
const BACKEND = path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor')
const FRONT = path.resolve(__dirname, '..')

const read = (p) => fs.readFileSync(p, 'utf8')

/**
 * Kendi yönetim sayfası olan gruplar Genel Ayarlar'da GÖSTERİLMEZ, dolayısıyla etiket de
 * gerekmez. Liste, bileşendeki SKIP_GROUPS ile birebir aynı olmak zorunda — testin kendisi
 * bunu aşağıda doğruluyor, yani ikisi ayrışırsa kapı kırılır.
 */
const SKIP_GROUPS = ['branding', 'retention', 'userpush', 'storm', 'login-anomaly']

/** new Setting("key", "group", Type.X) → [{key, group}] */
function catalogSettings() {
  const src = read(path.join(BACKEND, 'service/AppSettingsCatalog.java'))
  return [...src.matchAll(/new Setting\(\s*"([^"]+)"\s*,\s*"([^"]+)"/g)]
    .map(m => ({ key: m[1], group: m[2] }))
}

/** const SKIP_GROUPS = new Set([...]) — bileşenden okunur */
function componentSkipGroups() {
  const src = read(path.join(FRONT, 'components/admin/GeneralSettings.jsx'))
  const m = /const SKIP_GROUPS\s*=\s*new Set\(\[([\s\S]*?)\]\)/.exec(src)
  if (!m) return []
  return [...m[1].matchAll(/'([^']+)'/g)].map(x => x[1])
}

describe('Genel Ayarlar etiketleri — backend kataloğuyla senkron', () => {
  const all = catalogSettings()
  const shown = all.filter(s => !SKIP_GROUPS.includes(s.group))

  it('backend kataloğu okunabiliyor (boş liste = yol veya sözdizimi değişmiş)', () => {
    expect(all.length, 'AppSettingsCatalog okunamadı').toBeGreaterThan(100)
    expect(shown.length, 'gösterilecek ayar kalmadı').toBeGreaterThan(50)
  })

  it('testin atlama listesi bileşendekiyle AYNI (ayrışırsa kapı yalan söyler)', () => {
    expect(componentSkipGroups().sort()).toEqual([...SKIP_GROUPS].sort())
  })

  it('gösterilen HER ayarın TR etiketi var (yoksa ekranda ham anahtar görünür)', () => {
    expect(shown.filter(s => !TR[`general.lbl.${s.key}`]).map(s => s.key)).toEqual([])
  })

  it('gösterilen HER ayarın EN etiketi var', () => {
    expect(shown.filter(s => !EN[`general.lbl.${s.key}`]).map(s => s.key)).toEqual([])
  })

  it('gösterilen HER grubun TR ve EN başlığı var', () => {
    const groups = [...new Set(shown.map(s => s.group))]
    expect(groups.filter(g => !TR[`general.grp.${g}`])).toEqual([])
    expect(groups.filter(g => !EN[`general.grp.${g}`])).toEqual([])
  })

  it('etiket ham anahtarın kendisi DEĞİL (kopyala-yapıştır kaçağı)', () => {
    // `'general.lbl.x': 'general.lbl.x'` gibi bir satır testi geçer ama ekranda yine
    // ham anahtar görünür — bariz görünse de sessiz kalan tam olarak bu sınıftı.
    const bad = shown.filter(s => TR[`general.lbl.${s.key}`] === `general.lbl.${s.key}`
                               || EN[`general.lbl.${s.key}`] === `general.lbl.${s.key}`
                               || TR[`general.lbl.${s.key}`] === s.key
                               || EN[`general.lbl.${s.key}`] === s.key)
    expect(bad.map(s => s.key)).toEqual([])
  })

  it('atlanan grupların ayarı ekrana HİÇ gelmiyor (ikinci yönetim yüzeyi yok)', () => {
    const skipped = all.filter(s => SKIP_GROUPS.includes(s.group))
    expect(skipped.length, 'atlanan grup kalmadı — liste bayatlamış olabilir').toBeGreaterThan(20)
    expect(shown.some(s => SKIP_GROUPS.includes(s.group))).toBe(false)
  })
})
