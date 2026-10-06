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
  'via', 'dq',   // izleme sayfaları vekil süzgeci; alan adı hızlı süzgeci (2026-09-22)
  'platform',    // Genel Bakış platform süzgeci (2026-09-25; utils/platformFilter.js PLATFORM_URL_KEY)
  'scope',       // Durum İzleme "Takımlarım | Tüm takımlar" anahtarı (2026-09-26; envanter i_scope, sertifikalar c_scope önekli) + Olaylar kapsamı mine|others|all (2026-09-28)
  'src',         // Alarm Geçmişi kategori süzgeci (İzleme menüsü rozetleri, 2026-09-30)
  'noc',         // Alarm Geçmişi "7/24'e gidenler" süzgeci (noc=sent, 2026-10-04)
  'hdx',         // HTTP izleme detayında açık uçtan uca tanılama çalıştırması (2026-10-02; yalnız KAYITLI sonucu açar)
  'kdx',         // Keyword izleme detayında açık uçtan uca tanılama çalıştırması (2026-10-04; yalnız KAYITLI sonucu açar)
  'pgdx', 'ptdx', 'dndx',   // Ping / Port / DNS uçtan uca tanılama çalıştırması (2026-10-05; yalnız KAYITLI sonucu açar)
  'pidx', 'psdx',           // Sayfa Bütünlüğü / Sayfa Hızı uçtan uca tanılama çalıştırması (2026-10-05; yalnız KAYITLI sonucu açar)
  'open']        // UYGULAMA düzeyi tek seferlik "vardığında aç" (cert | noc — utils/monitorDeepLink.js DEEP_OPEN_PARAM, 2026-09-28); tüketilince silinir

/**
 * Sekme değişince temizlenecek param AİLELERİ (önek eşleşmesi).
 *
 * <p>Bazı ekranların durumu sabit bir ad listesiyle sayılamaz: Denetim Kaydı 10 filtresini
 * `a_actor`, `a_eventType`, `a_since` … diye önekli paramlarda taşıyor. Bunlar
 * {@link PAGE_STATE_PARAMS}'ta olmadığı için sekme değiştirildiğinde URL'de ASILI kalıyordu —
 * kullanıcı başka bir sekmeye geçip geri döndüğünde kendisinin kurmadığı bir filtreyle
 * karşılaşıyor, boş listeyi "kayıt yok" sanıyordu.
 */
export const PAGE_STATE_PREFIXES = ['a_', 'r_', 'd_', 'i_', 'f_', 'u_', 'c_', 'w_', 'm_', 'p_', 'g_', 'ir_', 'wa_', 'n_', 'ih_', 'st_', 'ch_', 'mo_', 'sf_', 'lm_', 'mc_']   // mc_: Manuel Sertifikalar (mc_q/mc_status/mc_team + mc_upload sihirbaz isteği, 2026-10-06) · a_: Denetim Kaydı · r_: Veri Saklama koşum listesi · d_: Dağıtım geçmişi · i_: Envanter süzgeçleri · f_: Vade takvimi süzgeçleri · u_: Sistem Sağlığı kullanıcı etkinliği · c_: Tüm Sertifikalar tablosu · w_: Haftalık Raporlar · m_: SMTP Gönderim Logu · p_: Webhook Push Gönderim Logu · g_: Yönetim Paneli (alt sekme + süzgeçler) · ir_: Sorun Bildirimleri (ir_id açık rapor, ir_view yönetici sekmesi, ir_status süzgeç) · wa_: Dikkat Gerektiren Sertifikalar (wa_why/wa_q/wa_team/wa_tier/wa_sort/wa_view/wa_page/wa_ps) · n_: 7/24 Kapsamı (n_q/n_team/n_type/n_reason/n_status/n_ct/n_page/n_ps) · ih_: Olay & Hata Geçmişi (ih_q/ih_sev/ih_st/ih_cat/ih_ch/ih_team/ih_from/ih_to/ih_sla/ih_open/ih_preset + açık ayrıntı ih_id) · st_: İstatistikler (st_k/st_q/st_iss/st_team/st_tier/st_lvl/st_sort/st_mx/st_mxall/st_page/st_ps) · mo_: İzleme Panosu (mo_win/mo_type/mo_status/mo_team/mo_q, 2026-09-30) · sf_: Alarm Fırtınası (sf_tab/sf_team/sf_from/sf_to/sf_res/sf_page/sf_days + açık ayrıntı sf_storm, 2026-09-30) · ch_: İzleme Değişiklikleri (ch_q/ch_kind/ch_ev/ch_actor/ch_team/ch_res/ch_range/ch_from/ch_to/ch_page/ch_ps + açık ayrıntı ch_id) · lm_: Giriş Yöntemleri (lm_tab ayarlar/istatistikler, lm_p dönem, lm_ch kanal, lm_q/lm_sort/lm_page/lm_ps kullanıcı tablosu + açık ayrıntı lm_user, 2026-10-03)

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

/** Zamanlayıcısı henüz dolmamış (debounce'lu) yazımlar — {@link flushUrlQuerySync} bunları hemen uygular. */
const pendingWrites = new Set()

/**
 * Bekleyen TÜM debounce'lu yazımları ŞİMDİ uygular (2026-09-28). Bir sayfa başka sekmeye gitmeden HEMEN ÖNCE çağırır
 * (7/24 Kapsamı → izleme): son 300 ms'de değişen süzgeç o anki geçmiş kaydına yazılsın ki Geri onu geri getirsin. Sekme
 * geçişi sayfayı söker ve söküm zamanlayıcıyı İPTAL eder (yazılmamış süzgeç kaybolurdu); söküm anında yazmak ise
 * App'in az önce pushState ettiği YENİ kaydın adresini kirletirdi — bu yüzden yazım gezinmeden önce, açıkça.
 */
export function flushUrlQuerySync() {
  for (const write of [...pendingWrites]) write()
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
    // Zamanlayıcı dolunca YA DA flushUrlQuerySync çağrılınca bir kez koşar (hangisi önce gelirse).
    const write = () => {
      pendingWrites.delete(write)
      if (timerRef.current) { clearTimeout(timerRef.current); timerRef.current = null }
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
    }
    pendingWrites.add(write)
    timerRef.current = setTimeout(write, debounceMs)
    return () => {
      pendingWrites.delete(write)
      if (timerRef.current) { clearTimeout(timerRef.current); timerRef.current = null }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [depsKey, enabled])
}

export default useUrlQuerySync
