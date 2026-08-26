import { useCallback, useRef, useState } from 'react'

/**
 * Aynı anda ÇALIŞAN kontrolleri izler — bir kartın uzun kontrolü diğerlerini bekletmesin.
 *
 * <p><b>Neden gerekti:</b> her izleme sayfası tek bir {@code checking} kimliği tutuyordu
 * ({@code useState(null)}). Bunun iki sonucu vardı ve ikisi de sessizdi:
 * <ul>
 *   <li>İkinci karta basınca {@code checking} onun kimliğine dönüyor, BİRİNCİ kartın
 *       "çalışıyor" göstergesi kayboluyordu — iş sürerken bitmiş gibi görünüyordu.</li>
 *   <li>Önce biten kontrol {@code setChecking(null)} yapıp HÂLÂ SÜREN diğerinin kilidini de
 *       açıyordu; kullanıcı aynı monitörü ikinci kez tetikleyebiliyordu.</li>
 * </ul>
 * Küme tutmak ikisini birden çözüyor: her kontrol yalnız KENDİ kimliğini ekler ve siler.
 *
 * <p>İstekler zaten paralel gidiyordu (tarayıcı beklemiyor); engel yalnız arayüzün tek-kimlik
 * varsayımıydı. Bu yüzden burada eşzamanlılık "açılmıyor", var olan eşzamanlılık DOĞRU
 * gösteriliyor.
 */
export function useRunningChecks() {
  const [running, setRunning] = useState(() => new Set())
  // Aynı kimliğin iki kez sıraya girmesini engeller: küme state'i asenkron güncellendiği için
  // hızlı çift tıklamada `running.has(id)` henüz false görünebilir.
  const inFlight = useRef(new Set())

  const isRunning = useCallback((id) => running.has(id), [running])

  /**
   * {@code fn}'i çalıştırırken {@code id}'yi "çalışıyor" olarak işaretler.
   *
   * <p>{@code finally} ŞART: {@code fn} istisna atarsa kimlik kümede kalır ve o kart kalıcı
   * kilitlenirdi (sayfa yenilenene kadar). Aynı sınıf hata bu projede bir kez yaşandı:
   * kontrol düğmesi ReferenceError atınca altındaki {@code setChecking(null)} hiç çalışmadı.
   *
   * @returns {Promise<*>} {@code fn}'in sonucu; zaten çalışıyorsa {@code undefined}
   */
  const track = useCallback(async (id, fn) => {
    if (inFlight.current.has(id)) return undefined
    inFlight.current.add(id)
    setRunning(prev => { const next = new Set(prev); next.add(id); return next })
    try {
      return await fn()
    } finally {
      inFlight.current.delete(id)
      setRunning(prev => { const next = new Set(prev); next.delete(id); return next })
    }
  }, [])

  return { isRunning, track, runningCount: running.size }
}
