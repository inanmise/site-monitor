import { useEffect, useRef } from 'react'
import { api } from '../../../api/client'
import { KIND_IMAGES, onImagesChanged, sameImageList } from './imageSync.js'
import { panelOpenedFor } from './panelWindow.js'

/** Panel açıldıysa pencereye dönünce görseller en çok bu sıklıkla yeniden okunur. */
export const EDITOR_FOCUS_RELOAD_MS = 5000
/** Okuma sürerken bu pencerede görsel yüklenirse / silinirse okuma en çok bu kadar kez yeniden yapılır. */
export const SYNC_RETRY_MAX = 3

/**
 * Rapor düzenleyicisi: başka pencere (Görsel paneli) bu raporun görsellerini değiştirince YALNIZ görsel listesi yeniden
 * okunur ve `apply(liste, durum)` ile uygulanır — taslak değerleri, otomatik kayıt ve sürüm DEĞİŞMEZ (görseller ayrı
 * toplu yazımlardır). Bu pencereden panel açıldıysa pencereye dönünce de okunur: panel yükleme sürerken kapatıldıysa
 * (ileti hiç gönderilmediyse) liste yine güncellenir.
 *
 * <p><b>İnceleme 2026-10-18:</b>
 * <ul>
 *   <li>Liste DEĞİŞMEDİYSE hiçbir şey uygulanmaz (eskiden düzenleyicinin yükleme geri çağrısı kullanılıyordu: gönderilmiş
 *   raporda panel açıldıktan sonraki her pencere dönüşü "gönderimden sonra değişti" şeridini gösteriyordu). Değiştiyse
 *   raporun durumu SUNUCUNUN yanıtındaki durumdur (`report.status`) — tahmin edilmez.</li>
 *   <li>Okuma sürerken bu pencerede görsel yüklendiyse / silindiyse (`localGen` arttı) yanıt bu pencerenin daha yeni
 *   listesini EZMEZ: okuma yeniden yapılır (en çok {@link SYNC_RETRY_MAX} kez; yeni okuma her iki değişikliği de görür).
 *   Geç gelen eski okuma daha yenisini ezmez (sıra numarası).</li>
 * </ul>
 *
 * @param reportId  rapor kimliği
 * @param opts.getImages  düzenleyicinin ŞU ANKİ görsel listesi
 * @param opts.apply      `(liste, sunucuDurumu)` — yalnız liste değişince
 * @param opts.localGen   ref: bu penceredeki her görsel değişikliğinde artar
 */
export function useImagesSync(reportId, { getImages, apply, localGen } = {}) {
  const cb = useRef({ getImages, apply, localGen })
  cb.current = { getImages, apply, localGen }
  useEffect(() => {
    if (reportId == null) return undefined
    let alive = true
    let seq = 0
    let last = 0
    const genNow = () => cb.current.localGen?.current ?? 0
    const reload = async (attempt = 0) => {
      const my = ++seq
      const gen = genNow()
      last = Date.now()
      try {
        const res = await api.techErrors.report(reportId)
        if (!alive || my !== seq) return
        // Okuma sürerken bu pencerede değişiklik oldu: yanıt ondan eski olabilir — yeniden oku
        if (genNow() !== gen) {
          if (attempt < SYNC_RETRY_MAX) reload(attempt + 1)
          return
        }
        const list = res?.success ? res.data?.images : null
        if (!Array.isArray(list)) return
        if (sameImageList(cb.current.getImages?.() || [], list)) return
        cb.current.apply?.(list, res.data?.report?.status ?? null)
      } catch { /* bir sonraki ileti ya da sayfa yüklemesi düzeltir */ }
    }
    const off = onImagesChanged(reportId, () => reload(), [KIND_IMAGES])
    const onBack = () => {
      if (document.visibilityState !== 'visible' || !panelOpenedFor(reportId) || Date.now() - last < EDITOR_FOCUS_RELOAD_MS) return
      reload()
    }
    window.addEventListener('focus', onBack)
    document.addEventListener('visibilitychange', onBack)
    return () => {
      alive = false
      off()
      window.removeEventListener('focus', onBack)
      document.removeEventListener('visibilitychange', onBack)
    }
  }, [reportId])
}
