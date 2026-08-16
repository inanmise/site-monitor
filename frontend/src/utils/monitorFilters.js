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
