import { CATEGORY_ORDER } from './templateCategories.js'

/**
 * Sentetik izleme formundaki "Script kaynağı" seçicisinin seçenekleri.
 *
 * Saf fonksiyon — React'siz test edilir.
 *
 * ## Grup SIRASI sözleşmedir
 * `SearchableSelect` grup başlığını BİTİŞİKLİĞE göre basar: `group` alanı bir öncekinden
 * farklılaştığı anda yeni başlık açar. Yani sıralama çağıranın işi; burada karıştırılan bir
 * liste, aynı başlığı listede iki kez gösterir.
 *
 * Sabit sıra:
 *   1. Kayıtlı script'ler   — düzenlenen monitörün KENDİ girdisi burada
 *   2. Takım şablonlarım    — kullanıcının kendi takımının şablonları
 *   3. Genel şablonlar      — takımlardan genele açılmış (K5) şablonlar
 *   4. Yerleşik şablonlar   — ürünle gelen küratörlü katalog, KATEGORİYE göre dallanır
 *
 * ## Yerleşikler neden kategoriye bölünüyor
 * Yerleşik katalog 100 şablon (10 kategori × 10). Hepsi tek bir "Yerleşik şablonlar" başlığı
 * altına dökülünce bir script'in hangi kategoriden geldiği HİÇ görünmüyordu ve liste 100 satır
 * uzunluğundaydı. Artık her kategori kendi dalı; seçici `collapsibleGroups` ile çiziliyor, yani
 * dallar kapalı gelir ve tıklanınca altındaki 10 script açılır (Şablon Kütüphanesi sekmesindeki
 * ağaçla aynı desen). Kategori sırası CATEGORY_ORDER'dan gelir — alfabetik DEĞİL, bir siteyi
 * izlemeye baştan başlayan birinin ilerleyeceği yol.
 *
 * <p>Kapsam bilgisi KAYBOLMUYOR: kayıtlı/takım/genel kendi başlıklarını koruyor, kategori adı
 * taşıyan her dal ise tanımı gereği YERLEŞİK. İki eksen de okunur kalıyor.
 *
 * ## `tpl:` geriye uyumu
 * Mevcut monitörlerin `template` kolonunda `tpl:smoke-health` gibi değerler YAZILI. Değer
 * dilbilgisi genişletildi, hiçbir şey kaldırılmadı:
 *
 *     'tpl:' <token>,  <token> ::= builtinKey  (^[a-z][a-z0-9._-]*$)  |  templateId (^[0-9]+$)
 *
 * İki dal provably ayrık: backend validator `builtinKey`'i harfle başlamaya zorluyor, yani
 * tamamen rakamdan oluşan bir builtinKey imkânsız. Sunucu her satırda `select_token` üretir —
 * seed'lenen yerleşikler ESKİSİYLE AYNI dizeyi verir, eski monitörler bozulmaz.
 */

/** Çok dilli alan seçimi: istenen dil boşsa diğerine düşer (K6 — kullanıcı şablonu tek metin). */
export function pickLang(primary, alternate, lang) {
  if (lang === 'en') return alternate || primary || ''
  return primary || alternate || ''
}

export function templateLabel(tpl, lang) {
  return pickLang(tpl.name, tpl.name_en, lang)
}

/** Şablonun seçicide taşıdığı kalıcı değer. */
export function templateValue(tpl) {
  return `tpl:${tpl.select_token != null ? tpl.select_token : tpl.id}`
}

/**
 * @param savedScripts  [{ id, name }] — kayıtlı monitör script'leri
 * @param templates     sunucudan gelen şablon satırları ({ scope, builtin, select_token, … })
 * @param savedSourceId düzenlenen monitörün id'si (varsa "(bu monitör)" çapası)
 * @param t             i18n fonksiyonu
 */
export function buildScriptSourceOptions({ savedScripts = [], templates = [], savedSourceId = null, lang = 'tr', t }) {
  const opts = []

  // Boş seçenek YALNIZ yeni monitörde: düzenlemede "boşalt" yolu kaydedilmiş script'i siliyordu.
  if (!savedSourceId) opts.push({ value: '', label: t('scripted.templatePick') })

  // `groupOpen`: kapsam dalları AÇIK başlar. Kullanıcının kendi script'leri birkaç tanedir ve en
  // sık seçilendir — onları katlamak en yaygın işe fazladan tık ekler. Katlanması gereken, 100
  // satırlık yerleşik katalogtur; kategori dalları bu bayrağı TAŞIMAZ.
  for (const s of savedScripts) {
    opts.push({
      value: `saved:${s.id}`,
      label: s.id === savedSourceId ? `${s.name} ${t('scripted.srcThisMonitor')}` : s.name,
      group: t('scripted.srcGroupSaved'),
      groupOpen: true,
    })
  }

  const team    = templates.filter(x => x.scope === 'team')
  const general = templates.filter(x => x.scope === 'general' && !x.builtin)
  const builtin = templates.filter(x => x.scope === 'general' && x.builtin)

  const push = (rows, group, groupOpen = false) => {
    for (const tpl of rows) {
      opts.push({ value: templateValue(tpl), label: templateLabel(tpl, lang), group, groupOpen })
    }
  }
  push(team,    t('scripted.srcGroupMyTeam'),  true)
  push(general, t('scripted.srcGroupGeneral'), true)

  // Yerleşikler kategoriye göre dallanır. Sıra CATEGORY_ORDER'dan; bitişiklik sözleşmesi gereği
  // aynı kategorinin satırları ARDIŞIK olmak zorunda (yoksa aynı başlık iki kez basılırdı).
  for (const key of CATEGORY_ORDER) {
    push(builtin.filter(x => x.category === key), t('tpl.cat.' + key))
  }
  // Kataloğa CATEGORY_ORDER'da olmayan bir kategori (ya da kategorisiz satır) girerse SESSİZCE
  // DÜŞMESİN — eski "Yerleşik şablonlar" başlığı altında toplanır.
  const known = new Set(CATEGORY_ORDER)
  push(builtin.filter(x => !known.has(x.category)), t('scripted.srcGroupTemplates'))

  return opts
}

/** `tpl:<token>` → şablon satırı. Satır silinmişse `null` (panel çizilmez, bugünkü davranış). */
export function resolveTemplate(templates, token) {
  if (!token) return null
  const key = String(token)
  return templates.find(x => String(x.select_token) === key || String(x.id) === key) || null
}
