import { useEffect, useRef, useState } from 'react'
import { readUrlParam } from './useUrlQuerySync.js'
import { DEEP_OPEN, DEEP_OPEN_PARAM } from '../utils/monitorDeepLink.js'
import { requestNocFieldFocus } from '../components/noc/forms/nocFieldFocus.js'

/** App.handleTabChange aynı sekmede param yazınca yayılır (bkz. App.jsx TAB_PARAMS_EVENT; HelpPage/SystemHealth deseni). */
const TAB_PARAMS_EVENT = 'sm:tab-params'

/** Adresteki `open` tek seferliktir: tüketilince silinir (Geri/yenile pencereyi ya da formu yeniden açmasın). */
function stripOpenParam() {
  try {
    const url = new URL(window.location.href)
    if (!url.searchParams.has(DEEP_OPEN_PARAM)) return
    url.searchParams.delete(DEEP_OPEN_PARAM)
    const qs = url.searchParams.toString()
    window.history.replaceState(window.history.state, '', url.pathname + (qs ? `?${qs}` : '') + url.hash)
  } catch { /* history yok — en iyi çaba */ }
}

/** Mount anındaki istek: `?monitor=<id>[&open=noc]` → { id, action } | null. */
function requestFromUrl() {
  const id = readUrlParam('monitor', null)
  if (id == null) return null
  return { id, action: readUrlParam(DEEP_OPEN_PARAM, null) }
}

/**
 * `?monitor=<id>` derin bağlantısını açar — e-posta/bildirim CTA'sı, İzleme Değişiklikleri konsolu, 7/24 Kapsamı.
 * Dokuz izleme sayfasının ORTAK kancası (Sentetik dâhil, 2026-09-28).
 *
 * <p>Sessizce bozulabilen ayrıntılar (her biri testte pinli — useMonitorDeepLink.test.jsx, monitorPagesDeepLink.test.jsx):
 * <ul>
 *   <li><b>İstek MOUNT'ta okunur</b> (2026-09-28): sayfanın URL senkronu (`useUrlQuerySync`, `monitor: selected?.id`)
 *       pencere kapalıyken `monitor`'ü 300 ms sonra adresten SİLER. İstek liste gelince okunuyordu → liste 300 ms'den geç
 *       gelirse (üretimde yüzlerce izleme) bağlantı hiçbir şey açmıyordu. `mtab`'ın aynı yarışı useDeepLinkTab'da
 *       kapatılmıştı; bu kanca o düzeltmeyi almamıştı.</li>
 *   <li><b>YALNIZ BİR KEZ</b>: istek tüketilince silinir; 60 sn'lik yoklama listeyi tazeledikçe kapatılan pencere geri
 *       AÇILMAZ.</li>
 *   <li><b>Liste dolmadan karar verilmez</b>: `loaded` (sayfa: `!loading && !loadError`) gelene dek beklenir. Yükleme
 *       hatasında istek BEKLER (hata bandı zaten görünür; sonraki başarılı yüklemede açılır) — "bulunamadı" denmez.</li>
 *   <li><b>Bulunamadı / erişim yok / silinmiş</b>: liste yüklendi ve kimlik yoksa `onNotFound(id)` (sayfa: toast).
 *       Eskiden hiçbir şey olmuyordu — kullanıcı bağlantının bozuk olduğunu anlamıyordu.</li>
 *   <li><b>Gevşek kimlik karşılaştırması</b>: URL değeri daima string, izleme kimliği sayı.</li>
 *   <li><b>Aynı sekmede ikinci bağlantı</b>: sayfa zaten açıkken gelen `sm:tab-params` `{ monitor }` olayı yeni isteği
 *       aynı yoldan işler (bildirim kutusundan başka izlemeye geçiş). Sekme değişiminde sayfa zaten yeniden bağlanır.</li>
 *   <li><b>`open=noc`</b> (7/24 Kapsamı "7/24 ayarını düzenle"): `onEdit` verilmiş VE `canEdit(m)` doğruysa detay yerine
 *       DÜZENLEME formu açılır; formdaki "7/24 izleme ekibine bildir" alanı (NocNotifyField, `nocType` türü) görünüme
 *       kaydırılıp odaklanır. Yetkisizde detaya düşer (paylaşılan bağlantıyı açan başkası 403'lü form görmesin).
 *       `open` tüketilince adresten silinir.</li>
 * </ul>
 *
 * <p>`monitor` parametresi adreste KALIR — sayfanın URL senkronu onu açık pencereye bağlar: pencere açıkken bağlantı
 * paylaşılabilir ve yenileme aynı izlemeyi açar; pencere kapanınca silinir (Geri/yenile artık açmaz).
 *
 * @param {Array<{id: number|string}>} monitors  sayfanın TAM listesi (süzgeç/sayfalama ÖNCESİ)
 * @param {(m: object) => void} open             eşleşen izlemenin detayını açar
 * @param {{ loaded?: boolean, onNotFound?: (id: string) => void, onEdit?: (m: object) => void,
 *           canEdit?: (m: object) => boolean, nocType?: string }} [opts]
 *        `loaded` verilmezse (eski çağıranlar) boş olmayan liste "yüklendi" sayılır ve boş listede beklenir.
 */
export function useMonitorDeepLink(monitors, open, { loaded, onNotFound, onEdit, canEdit, nocType } = {}) {
  const pending = useRef(undefined)
  if (pending.current === undefined) pending.current = requestFromUrl()   // yalnız ilk render'da (mount)
  const [nonce, setNonce] = useState(0)
  // Geri çağrılar her render'da yeni closure; ref'le taşınır ki etki yalnız veri/istek değişince koşsun.
  const cb = useRef({})
  cb.current = { open, onNotFound, onEdit, canEdit, nocType }

  const list = Array.isArray(monitors) ? monitors : []
  const ready = loaded === undefined ? list.length > 0 : !!loaded

  useEffect(() => {
    const on = (e) => {
      const d = e?.detail || {}
      if (d.monitor == null || d.monitor === '') return
      pending.current = { id: String(d.monitor), action: d[DEEP_OPEN_PARAM] ?? null }
      setNonce((n) => n + 1)
    }
    window.addEventListener(TAB_PARAMS_EVENT, on)
    return () => window.removeEventListener(TAB_PARAMS_EVENT, on)
  }, [])

  useEffect(() => {
    const req = pending.current
    if (!req || !ready) return
    pending.current = null
    if (req.action != null) stripOpenParam()
    const m = list.find((x) => String(x?.id) === String(req.id))
    if (!m) { cb.current.onNotFound?.(String(req.id)); return }
    const { onEdit: edit, canEdit: may } = cb.current
    if (req.action === DEEP_OPEN.NOC && edit && (may ? may(m) : true)) {
      if (cb.current.nocType) requestNocFieldFocus(cb.current.nocType)
      edit(m)
    } else cb.current.open(m)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [monitors, ready, nonce])
}
