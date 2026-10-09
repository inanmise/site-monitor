import { useEffect, useState } from 'react'

/**
 * Hareketsizlik uyarısının saniye sayacı (2026-10-09, performans): eskiden sayaç App'in durumundaydı ve uyarı boyunca
 * saniyede bir TÜM uygulama ağacı (menü + 50 kart) yeniden çiziliyordu. Artık yalnız bu küçük yaprak sayar; App yalnız
 * uyarıyı açıp kapatır ve son anı (`deadline`, ms) verir.
 *
 * @param {number} deadline  otomatik çıkış anı (Date.now() tabanlı ms)
 * @param {(sec:number) => string} format  saniyeyi HTML metne çeviren işlev (çağıranın t() anahtarı)
 */
export default function InactivityCountdownText({ deadline, format, className }) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    setNow(Date.now())
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [deadline])
  const sec = Math.max(0, Math.ceil((deadline - now) / 1000))
  return <span data-slot="inactivity-countdown" className={className} dangerouslySetInnerHTML={{ __html: format(sec) }} />
}
