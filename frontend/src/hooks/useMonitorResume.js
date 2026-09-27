import { useCallback, useRef, useState } from 'react'
import { useT } from '../i18n/index.jsx'
import { useToast } from '../components/ui/Toast.jsx'

/**
 * Duraklatılmış izlemeyi karttan TEK tıkla yeniden etkinleştirir (kullanıcı isteği 2026-09-26: "pause edilen bir
 * izleme kartını tekrar hızlı bir şekilde aktif edebilmek için kart üzerinde resume seçeneği olmalı; tüm izleme
 * sayfalarında default").
 *
 * <p>Yazma yolu toplu işlem çubuğuyla AYNI (`BulkActionBar` "Sürdür" → `api.update(id, { active: true })`): yeni bir
 * uç ya da yetki kuralı uydurulmaz; sunucu takım kapsamını her zamanki gibi doğrular. Sayfa yalnız kendi güncelleme
 * fonksiyonunu ve listeyi tazeleyen geri çağrıyı verir.
 *
 * @param {(id: number, body: object) => Promise<any>} update  sayfanın izleme güncelleme ucu (ör. api.monitoring.updateHttpMonitor)
 * @param {(monitor: object) => void} [onDone]                 başarıdan sonra listeyi tazele (yeniden açılan izlemeyle çağrılır)
 * <p>Meşgul durumu tek kimlik değil KÜME (`useRunningChecks` deseni, 2026-09-27 regresyon taraması): tek yuvada
 * A sürerken B'ye basınca A'nın göstergesi sönüyor, önce biten diğerinin kilidini de açıyordu. Çağıran
 * `isResuming(id)` kullanır; `resumingId` geriye uyum içindir (en son başlayıp HÂLÂ süren kimlik).
 *
 * @returns {{ resume: (monitor: {id: number}) => Promise<void>, isResuming: (id: number) => boolean, resumingId: number|null }}
 */
export function useMonitorResume(update, onDone) {
  const t = useT()
  const toast = useToast()
  const [resuming, setResuming] = useState(() => new Set())
  // Aynı kimliğin çift tıkla iki kez gitmesini engeller (küme state'i asenkron güncellenir).
  const inFlight = useRef(new Set())

  const resume = useCallback(async (m) => {
    if (!m || m.id == null) return
    if (inFlight.current.has(m.id)) return
    inFlight.current.add(m.id)
    setResuming(prev => { const next = new Set(prev); next.add(m.id); return next })
    try {
      const r = await update(m.id, { active: true })
      if (r && r.success === false) throw new Error(r.error || t('mon.resumeFailed'))
      toast.success(t('mon.resumed'))
      onDone?.(m)   // izleme argümanı: açık detay penceresi kendi kopyasını da güncelleyebilsin
    } catch (e) {
      toast.error(e?.message || t('mon.resumeFailed'))
    } finally {
      // Yalnız KENDİ kimliğini siler: önce biten, hâlâ süren diğerinin göstergesini söndürmez.
      inFlight.current.delete(m.id)
      setResuming(prev => { const next = new Set(prev); next.delete(m.id); return next })
    }
  }, [update, onDone, t, toast])

  const isResuming = useCallback((id) => resuming.has(id), [resuming])
  const resumingId = resuming.size ? [...resuming][resuming.size - 1] : null

  return { resume, isResuming, resumingId }
}
