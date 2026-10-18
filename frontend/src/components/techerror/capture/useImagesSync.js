import { useEffect, useRef } from 'react'
import { api } from '../../../api/client'
import { KIND_IMAGES, onImagesChanged } from './imageSync.js'
import { panelOpenedFor } from './panelWindow.js'

/** Panel açıldıysa pencereye dönünce görseller en çok bu sıklıkla yeniden okunur. */
export const EDITOR_FOCUS_RELOAD_MS = 5000

/**
 * Rapor düzenleyicisi: başka pencere (Görsel paneli) bu raporun görsellerini değiştirince YALNIZ görsel listesi yeniden
 * okunur ve `onImagesChange(() => liste)` ile uygulanır — taslak değerleri, otomatik kayıt ve sürüm DEĞİŞMEZ (görseller
 * ayrı toplu yazımlardır). Geç gelen yanıt daha yenisini ezmez (sıra numarası). Bu pencereden panel açıldıysa pencereye
 * dönünce de okunur: panel yükleme sürerken kapatıldıysa (ileti hiç gönderilmediyse) liste yine güncellenir.
 */
export function useImagesSync(reportId, onImagesChange) {
  const cb = useRef(onImagesChange)
  cb.current = onImagesChange
  useEffect(() => {
    if (reportId == null) return undefined
    let alive = true
    let seq = 0
    let last = 0
    const reload = async () => {
      const my = ++seq
      last = Date.now()
      try {
        const res = await api.techErrors.report(reportId)
        if (!alive || my !== seq) return
        const list = res?.success ? res.data?.images : null
        if (Array.isArray(list)) cb.current?.(() => list)
      } catch { /* bir sonraki ileti ya da sayfa yüklemesi düzeltir */ }
    }
    const off = onImagesChanged(reportId, reload, [KIND_IMAGES])
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
