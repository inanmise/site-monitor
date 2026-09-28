import { useEffect, useState } from 'react'

/**
 * Gecikmeli bayrak — `flag` en az `delayMs` boyunca KESİNTİSİZ true kaldıysa true döner; false olunca anında düşer.
 *
 * <p>Yükleme göstergeleri (soluklaştırma, satır içi Spinner) için: hızlı yanıtta (< ~180 ms) gösterge HİÇ çizilmez,
 * dolayısıyla süzgeçler arasında gezinirken ekran "yanıp sönmez" (Olaylar titreme bildirimi, 2026-09-28). Bayrak
 * her true'ya dönüşte gecikme baştan sayılır — bir önceki beklemenin bittiği an taşınmaz.
 */
export function useDelayedFlag(flag, delayMs = 180) {
  // Gecikmesi dolan "tur": her true dönemi yeni bir tur; gösterge yalnız GÜNCEL tur dolduysa yanar.
  const [round, setRound] = useState(0)
  const [elapsedRound, setElapsedRound] = useState(-1)
  const [wasOn, setWasOn] = useState(false)
  // Önceki render'a göre durum ayarla (React'in belgelenmiş deseni): false → true geçişi yeni tur açar.
  if (flag !== wasOn) {
    setWasOn(flag)
    if (flag) setRound((r) => r + 1)
  }
  useEffect(() => {
    if (!flag) return undefined
    const id = setTimeout(() => setElapsedRound(round), delayMs)
    return () => clearTimeout(id)
  }, [flag, round, delayMs])
  return flag && elapsedRound === round
}

export default useDelayedFlag
