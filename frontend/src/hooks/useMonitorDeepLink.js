import { useEffect, useRef } from 'react'

/**
 * E-posta/bildirim CTA'sındaki {@code ?monitor=<id>} bağlantısını açar.
 *
 * <p>Bu blok yedi izleme sayfasında birebir aynıydı; tek fark açıcı fonksiyonun adıydı
 * (openDetail / openModal / setDetailMonitor), o da parametre olarak alındı.
 *
 * <p>Üç ayrıntı burada yaşıyor ve üçü de sessizce bozulabilir:
 * <ul>
 *   <li><b>YALNIZ BİR KEZ</b> ({@code done} ref'i): liste her yenilendiğinde (60 sn'lik
 *       yoklama) etki tekrar koşar. Guard olmazsa kullanıcının kapattığı detay modalı her
 *       yenilemede kendiliğinden geri açılır — kapatılamayan bir pencere.</li>
 *   <li><b>Liste dolmadan çalışmaz</b>: ilk render'da {@code monitors} boştur; guard olmadan
 *       etki "bulunamadı" deyip bir kez harcanır ve bağlantı hiç açılmaz.</li>
 *   <li><b>Gevşek id karşılaştırması</b>: URL'den gelen değer daima string, monitör id'si
 *       sayıdır. {@code String()} sarmalaması olmazsa hiçbir bağlantı eşleşmez.</li>
 * </ul>
 *
 * <p>{@code monitor} parametresi URL'de KALIR (useUrlQuerySync yazıp siliyor) — burada
 * temizlenmez; bağlantı yenilendiğinde de aynı monitörü açsın.
 *
 * @param {Array<{id: number|string}>} monitors  yüklenen monitör listesi
 * @param {(m: object) => void} open             eşleşen monitörü açan fonksiyon
 */
export function useMonitorDeepLink(monitors, open) {
  const done = useRef(false)
  // `open` her render'da yeni bir closure; ref ile taşınır ki etki YALNIZ monitors'a bağlı kalsın
  // (aksi halde her render'da yeniden koşar ve "bir kez" güvencesi anlamsızlaşır).
  const openRef = useRef(open)
  openRef.current = open

  useEffect(() => {
    if (done.current || monitors.length === 0) return
    done.current = true
    let id
    try { id = new URLSearchParams(window.location.search).get('monitor') } catch { return }
    if (!id) return
    const m = monitors.find(x => String(x.id) === String(id))
    if (m) openRef.current(m)
  }, [monitors])
}
