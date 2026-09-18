/** Takım/grup filtrelerinde "atanmamış" seçeneğinin değeri (SearchableSelect option value'su). */
export const NO_ASSIGNMENT = '__none__'

/**
 * Bir monitör, seçili takım ve grup filtrelerine uyuyor mu?
 *
 * <p>Bu sekiz satır sekiz izleme sayfasında (domain/http/keyword/page/ping/port/dns/scripted)
 * <b>birebir aynı</b> kopyalanmıştı. Kopya kodun genel bedeli ölçüldü (Mayıs'tan beri izleme
 * sayfalarına dokunan 91 commit'in 15'i dört ya da daha fazla sayfaya aynı anda dokunmak
 * zorunda kaldı), ama bu blok ayrıca <b>kapsam belirleme</b> mantığı: burada bir hata,
 * kullanıcının seçtiğinden BAŞKA bir takımın monitörünü listede gösterir. Sekiz kopyada
 * yaşayan bir kural, sekiz kez yanlış düzeltilebilir.
 *
 * <p>Sözleşme (aynen korundu — çıkarımda hiçbir şey "iyileştirilmedi"):
 * <ul>
 *   <li>{@code 'all'} → o boyutta filtre yok,</li>
 *   <li>{@code '__none__'} → YALNIZ ataması olmayanlar ("takımsız"/"grupsuz"),</li>
 *   <li>aksi halde tam eşitlik (kısmi eşleşme YOK — takım adı benzerliği yanlış satır getirmesin),</li>
 *   <li>iki boyut bağımsız ve VE ile birleşir.</li>
 * </ul>
 *
 * <p>Arama BİLEREK dışarıda: her sayfa farklı alanlarda arıyor (http → url/name/tags,
 * domain → domain/name/registrar, keyword → url/keyword, ping → host …). Ortak bir arama
 * sözleşmesi uydurmak davranışı değiştirirdi.
 *
 * @param {{team_name?: string, group_name?: string}} m  monitör satırı
 * @param {string} teamFilter   'all' | '__none__' | takım adı
 * @param {string} groupFilter  'all' | '__none__' | grup adı
 * @returns {boolean}
 */
export function matchesTeamAndGroup(m, teamFilter, groupFilter) {
  if (teamFilter !== 'all') {
    if (teamFilter === NO_ASSIGNMENT) { if (m.team_name) return false }
    else if (m.team_name !== teamFilter) return false
  }
  if (groupFilter !== 'all') {
    if (groupFilter === NO_ASSIGNMENT) { if (m.group_name) return false }
    else if (m.group_name !== groupFilter) return false
  }
  return true
}

/**
 * İzleme sayfalarının ORTAK URL durumu — paylaşılabilir bağlantı (derin link) için.
 *
 * <p>Bu altı satır sekiz izleme sayfasında birebir aynıydı. Sözleşme sade görünüyor ama iki
 * inceliği var ve ikisi de testsizdi:
 * <ul>
 *   <li><b>Varsayılan değer param ÜRETMEZ</b> — temiz URL. 'all' filtre, boş arama, 'total'
 *       istatistiği ve 1. sayfa adres çubuğunda görünmez.</li>
 *   <li>{@code ps} (sayfa boyutu) yalnız varsayılandan (50) farklıysa <b>ya da</b> ilk sayfada
 *       değilsek yazılır. İkinci koşul şart: 2. sayfadayken boyut 50 olsa bile bağlantıyı alan
 *       kişi aynı sayfayı görebilsin diye boyutun URL'de olması gerekiyor.</li>
 * </ul>
 *
 * <p>Sayfaya özgü parametreler ({@code monitor}, {@code mtab}, Domain'deki {@code sort})
 * BİLEREK dışarıda: varsayılan sekme adı sayfadan sayfaya değişiyor (çoğunda 'control',
 * Sayfa Bütünlüğü'nde 'issues'). Onları buraya gömmek farkı gizlerdi; çağıran yerde açıkça
 * yazılıyorlar.
 *
 * @returns {object} useUrlQuerySync'e yayılacak (spread) ortak parametreler
 */
export function monitorUrlState({ teamFilter, groupFilter, tagFilter = 'all', search, statFilter, pager }) {
  return {
    team: teamFilter === 'all' ? null : teamFilter,
    group: groupFilter === 'all' ? null : groupFilter,
    tag: tagFilter === 'all' ? null : tagFilter,   // etiket filtresi (2026-09-18)
    q: search.trim() || null,
    stat: statFilter && statFilter !== 'total' ? statFilter : null,
    page: pager.page > 1 ? pager.page : null,
    ps: (pager.pageSize !== 50 || pager.page > 1) ? pager.pageSize : null,
  }
}

/** Virgülle ayrılmış etiket dizesini temiz listeye çevirir ("prod, kritik" → ['prod','kritik']). */
export function tagsOf(m) {
  return String(m?.tags || '').split(',').map((x) => x.trim()).filter(Boolean)
}

/**
 * Etiket filtresi (2026-09-18, ürün kararı: grup + etiket bazlı filtreleme her izleme sayfasında
 * VARSAYILAN). Sözleşme takım/grupla aynı: 'all' → filtre yok; '__none__' → yalnız etiketsizler
 * (eski kayıtlar); aksi hâlde etiket listesinde TAM eşleşme (büyük/küçük harf duyarsız).
 */
export function matchesTag(m, tagFilter) {
  if (!tagFilter || tagFilter === 'all') return true
  const tags = tagsOf(m)
  if (tagFilter === NO_ASSIGNMENT) return tags.length === 0
  const want = tagFilter.toLowerCase()
  return tags.some((t) => t.toLowerCase() === want)
}

/** Listedeki tüm etiketler — tekil, harf-duyarsız birleştirilmiş, alfabetik. */
export function tagNamesOf(monitors) {
  const seen = new Map()
  for (const m of monitors || []) for (const t of tagsOf(m)) { const k = t.toLowerCase(); if (!seen.has(k)) seen.set(k, t) }
  return [...seen.values()].sort((a, b) => a.localeCompare(b))
}

/**
 * Serbest metin araması grup adı ve etiketlerde de eşleşsin (sayfaya özgü alan aramasına EK).
 * Kullanıcı "kritik" yazınca URL'sinde geçmese de o etiketli izlemeler listelenir.
 */
export function matchesGroupOrTagText(m, search) {
  const q = String(search || '').trim().toLowerCase()
  if (!q) return false
  return (m?.group_name || '').toLowerCase().includes(q) || tagsOf(m).some((t) => t.toLowerCase().includes(q))
}
