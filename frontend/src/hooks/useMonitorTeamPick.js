import { useCallback, useMemo } from 'react'

/**
 * İzleme formunda takım seçimi (2026-09-18, kullanıcı isteği): birden fazla takımı olan kullanıcı
 * yeni izlemeyi HANGİ takımına ekleyeceğini seçebilmeli.
 *
 * <p>Eskiden takım kutusu yalnız global admin için açıktı; diğer herkes birincil takımına kilitliydi
 * ve ikincil takımının izlemesini düzenleyemiyordu (`isOwnTeam` yalnız birincil takıma bakıyordu).
 * Sunucu tarafı da aynı anda gevşetildi: USER rolü artık ÜYESİ olduğu her takım için iş görür
 * (`MonitoringController.canOperateTeam` → memberTeamIds).
 *
 * <p>Dokuz izleme sayfası aynı üçlüyü kullanır — kopya kod olmasın diye tek hook:
 * <ul>
 *   <li>{@code canPickTeam}: kutu açılır mı (admin ya da 2+ takım),</li>
 *   <li>{@code pickTeams}: kutuda listelenecek takımlar — admin için sunucudan gelen tam liste,
 *       diğerlerinde yalnız ÜYESİ olduğu takımlar (admin ucu çağrılmaz),</li>
 *   <li>{@code isOwnTeam(m)}: satır kullanıcının herhangi bir takımına mı ait (düzenle/kontrol kapısı).</li>
 * </ul>
 *
 * @param {object} p
 * @param {boolean} p.isAdmin          global admin (tam takım listesi + takımsız seçeneği)
 * @param {Array<{id:number,name:string}>} p.adminTeams  sunucudan gelen tam liste (yalnız admin dolu)
 * @param {Array<{id:number,name:string}>} p.myTeams     kullanıcının üyesi olduğu takımlar (/me team_ids × team_names)
 * @param {number|null} p.teamId         birincil takım (varsayılan seçim)
 */
export function useMonitorTeamPick({ isAdmin, adminTeams = [], myTeams = [], teamId }) {
  const myTeamIdSet = useMemo(() => {
    const s = new Set(myTeams.map((tm) => String(tm.id)))
    if (teamId != null) s.add(String(teamId))
    return s
  }, [myTeams, teamId])

  const canPickTeam = isAdmin || myTeams.length > 1
  const pickTeams = isAdmin ? adminTeams : myTeams

  const isOwnTeam = useCallback((m) => m?.team_id != null && myTeamIdSet.has(String(m.team_id)), [myTeamIdSet])

  return { canPickTeam, pickTeams, isOwnTeam, myTeamIdSet }
}

/** /me yanıtındaki paralel dizileri ({team_ids, team_names}) takım nesnelerine çevirir. */
export function teamsFromMe(res) {
  const ids = Array.isArray(res?.team_ids) ? res.team_ids : []
  const names = Array.isArray(res?.team_names) ? res.team_names : []
  return ids.map((id, i) => ({ id, name: names[i] ?? String(id) })).filter((tm) => tm.id != null)
}
