import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client.js'

/**
 * Şablon kütüphanesini yükler ve yetenek bayraklarını olduğu gibi taşır.
 *
 * <p>Bayraklar (`can_create_general`, satır başına `can_edit`/`can_promote`/…) SUNUCUDAN gelir
 * ve burada yeniden hesaplanmaz. Yetkiyi iki yerde hesaplamak, iki yerin kaçınılmaz olarak
 * ayrışması demektir — ve ayrıştığında kullanıcı basabildiği ama 403 yiyen bir düğme görür.
 *
 * <p>Yükleme HATASI sessizce yutulmaz ama sayfayı da düşürmez: `error` döner, çağıran onu
 * `AlertBanner` ile gösterir. Şablon listesi boş kalsa bile monitör formu çalışmaya devam
 * etmeli — script'i elle yazmak her zaman mümkün.
 */
export function useScriptedTemplates(scope) {
  const [templates, setTemplates] = useState([])
  const [meta, setMeta] = useState({ can_create_general: false, can_view_trash: false, writable_team_ids: [] })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      const res = await api.monitoring.getScriptedTemplates(scope)
      const d = res?.data || {}
      setTemplates(d.templates || [])
      setMeta({
        can_create_general: !!d.can_create_general,
        can_view_trash: !!d.can_view_trash,
        writable_team_ids: d.writable_team_ids || [],
        k6_version: d.k6_version || null,
      })
      setError(null)
    } catch (e) {
      setError(e?.message || String(e))
      setTemplates([])
    } finally {
      setLoading(false)
    }
  }, [scope])

  useEffect(() => { reload() }, [reload])

  return { templates, meta, loading, error, reload }
}
