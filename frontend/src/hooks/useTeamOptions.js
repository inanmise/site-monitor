import { useMemo } from 'react'
import { useT } from '../i18n/index.jsx'

/**
 * İzleme listesinden takım filtresi seçeneklerini üretir.
 *
 * <p>Bu blok altı izleme sayfasında (domain/http/keyword/page/ping/scripted) <b>birebir aynı</b>
 * kopyalanmıştı. Kopya kodun bedeli ölçüldü: Mayıs'tan bu yana izleme sayfalarına dokunan 91
 * commit'in 15'i dört ya da daha fazla sayfaya <i>aynı anda</i> dokunmak zorunda kaldı. Asıl
 * zarar ise bir kopyada düzeltilen hatanın diğerlerine taşınmaması — 2026-08 denetiminde bulunan
 * "Şimdi Kontrol Et butonu kilitleniyor" hatası tam olarak böyle oluşmuştu: ScriptedMonitorPage'de
 * bulunup düzeltilmiş, yanına açıklayıcı yorum bile yazılmış, ama altı kopyada aynen durmuştu.
 *
 * <p>Davranış AYNEN korunur (çıkarım sırasında hiçbir şey "iyileştirilmedi"):
 * <ul>
 *   <li>ilk seçenek daima "Tüm Takımlar" ({@code all}),</li>
 *   <li>takım adları {@code localeCompare} ile sıralanır (Türkçe harf sırası),</li>
 *   <li>takımsız monitör varsa listeye {@code __none__} EN SONA eklenir — araya değil.</li>
 * </ul>
 *
 * @param {Array<{team_name?: string}>} monitors  sayfanın ham monitör listesi (filtrelenmemiş)
 * @returns {{teamOptions: Array<{value: string, label: string}>, hasTeamOptions: boolean}}
 *          {@code hasTeamOptions}: gerçek bir takım var mı — 'all'/'__none__' sayılmaz; filtre
 *          çubuğunun gösterilip gösterilmeyeceğine bu karar veriyor.
 */
export function useTeamOptions(monitors) {
  const t = useT()
  const teamOptions = useMemo(() => {
    const names = new Set(); let hasNone = false
    for (const m of monitors) { if (m.team_name) names.add(m.team_name); else hasNone = true }
    const opts = [{ value: 'all', label: t('app.allTeams') }]
    ;[...names].sort((a, b) => a.localeCompare(b)).forEach(n => opts.push({ value: n, label: n }))
    if (hasNone) opts.push({ value: '__none__', label: t('app.noTeam') })
    return opts
  }, [monitors, t])

  const hasTeamOptions = teamOptions.some(o => o.value !== 'all' && o.value !== '__none__')
  return { teamOptions, hasTeamOptions }
}
