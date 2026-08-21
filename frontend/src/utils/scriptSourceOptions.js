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
 *   4. Yerleşik şablonlar   — ürünle gelen küratörlü katalog
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

  for (const s of savedScripts) {
    opts.push({
      value: `saved:${s.id}`,
      label: s.id === savedSourceId ? `${s.name} ${t('scripted.srcThisMonitor')}` : s.name,
      group: t('scripted.srcGroupSaved'),
    })
  }

  const team    = templates.filter(x => x.scope === 'team')
  const general = templates.filter(x => x.scope === 'general' && !x.builtin)
  const builtin = templates.filter(x => x.scope === 'general' && x.builtin)

  const push = (rows, group) => {
    for (const tpl of rows) opts.push({ value: templateValue(tpl), label: templateLabel(tpl, lang), group })
  }
  push(team,    t('scripted.srcGroupMyTeam'))
  push(general, t('scripted.srcGroupGeneral'))
  push(builtin, t('scripted.srcGroupTemplates'))

  return opts
}

/** `tpl:<token>` → şablon satırı. Satır silinmişse `null` (panel çizilmez, bugünkü davranış). */
export function resolveTemplate(templates, token) {
  if (!token) return null
  const key = String(token)
  return templates.find(x => String(x.select_token) === key || String(x.id) === key) || null
}
