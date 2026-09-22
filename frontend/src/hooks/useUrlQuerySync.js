import { useEffect, useRef } from 'react'

/**
 * Sayfa içi durumu (filtre/arama/sayfa/açık modal) URL query paramlarına ÇİFT YÖNLÜ bağlar —
 * adres çubuğundaki URL her an paylaşılabilir olur (/?tab=keyword&group=callcenterfacewebmon).
 *
 * Kurallar:
 * - Yalnız varsayılan-dışı (null olmayan) değerler yazılır; null/undefined → param SİLİNİR → URL temiz kalır.
 * - Yazma daima replaceState ile: filtre değişimleri tarayıcı geçmişini şişirmez; sekme geçişlerinin
 *   pushState/popstate davranışı (App.jsx) bozulmaz.
 * - Debounce (varsayılan 300 ms): arama kutusu her tuşta yazmasın — Safari history çağrılarını
 *   hız-sınırlar (~100/30 sn, aşımı exception). Tek zamanlayıcı, son değer kazanır; try/catch'li.
 * - Eşlemede OLMAYAN paramlara (tab, session, …) dokunulmaz; unmount'ta silme yapılmaz
 *   (temizlik sekme geçişinde App.handleTabChange'de merkezîdir — PAGE_STATE_PARAMS).
 */

/** Sekme değişince App.handleTabChange'in temizlediği sayfa-durumu paramları (tek doğruluk kaynağı). */
export const PAGE_STATE_PARAMS = ['group', 'tag', 'team', 'q', 'stat', 'sort', 'page', 'ps', 'monitor', 'range', 'mtab', 'domain', 'incident', 'sec', 'view', 'alert', 'type', 'level', 'ack', 'from', 'to',
  'atype', 'astatus', 'arange', 'aq',   // Aktivite Logu süzgeçleri (QA ISSUE-003: sekme değişince başka sekmeye taşınıyordu)
  'via', 'dq']   // izleme sayfaları vekil süzgeci; alan adı hızlı süzgeci (2026-09-22)

/**
 * Sekme değişince temizlenecek param AİLELERİ (önek eşleşmesi).
 *
 * <p>Bazı ekranların durumu sabit bir ad listesiyle sayılamaz: Denetim Kaydı 10 filtresini
 * `a_actor`, `a_eventType`, `a_since` … diye önekli paramlarda taşıyor. Bunlar
 * {@link PAGE_STATE_PARAMS}'ta olmadığı için sekme değiştirildiğinde URL'de ASILI kalıyordu —
 * kullanıcı başka bir sekmeye geçip geri döndüğünde kendisinin kurmadığı bir filtreyle
 * karşılaşıyor, boş listeyi "kayıt yok" sanıyordu.
 */
export const PAGE_STATE_PREFIXES = ['a_', 'r_', 'd_', 'i_', 'f_', 'u_', 'c_', 'w_', 'm_', 'p_', 'g_']   // a_: Denetim Kaydı · r_: Veri Saklama koşum listesi · d_: Dağıtım geçmişi · i_: Envanter süzgeçleri · f_: Vade takvimi süzgeçleri · u_: Sistem Sağlığı kullanıcı etkinliği · c_: Tüm Sertifikalar tablosu · w_: Haftalık Raporlar · m_: SMTP Gönderim Logu · p_: Webhook Push Gönderim Logu · g_: Yönetim Paneli (alt sekme + süzgeçler)

/** Mount'ta URL'den string param okur (useState initializer'ında kullanılır — flicker yok). */
export function readUrlParam(key, fallback = null) {
  try {
    const v = new URLSearchParams(window.location.search).get(key)
    return v != null && v !== '' ? v : fallback
  } catch {
    return fallback
  }
}

/** Mount'ta URL'den POZİTİF tamsayı okur; eksik/bozuk (abc, -5, 0) → fallback. */
export function readUrlInt(key, fallback = null) {
  const raw = readUrlParam(key, null)
  if (raw == null) return fallback
  const n = Number(raw)
  return Number.isInteger(n) && n > 0 ? n : fallback
}

export function useUrlQuerySync(mapping, { debounceMs = 300, enabled = true } = {}) {
  const timerRef = useRef(null)
  // Son eşleme ref'te — debounce dolduğunda en güncel değerler yazılır (son değer kazanır).
  const latestRef = useRef(mapping)
  latestRef.current = mapping

  const depsKey = JSON.stringify(mapping)
  useEffect(() => {
    if (!enabled) return
    if (timerRef.current) clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => {
      timerRef.current = null
      try {
        const url = new URL(window.location.href)
        let changed = false
        for (const [key, value] of Object.entries(latestRef.current)) {
          const next = value == null || value === '' ? null : String(value)
          const cur = url.searchParams.get(key)
          if (next == null) {
            if (cur != null) { url.searchParams.delete(key); changed = true }
          } else if (cur !== next) {
            url.searchParams.set(key, next); changed = true
          }
        }
        if (changed) {
          const qs = url.searchParams.toString()
          window.history.replaceState(window.history.state, '', url.pathname + (qs ? `?${qs}` : '') + url.hash)
        }
      } catch { /* history rate-limit / kısıtlı ortam — sync en iyi-çaba, sayfayı kırma */ }
    }, debounceMs)
    return () => { if (timerRef.current) { clearTimeout(timerRef.current); timerRef.current = null } }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [depsKey, enabled])
}

export default useUrlQuerySync
