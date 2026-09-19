import { useEffect, useRef, useState } from 'react'
import { Play } from 'lucide-react'
import { Spinner } from './Progress.jsx'
import { useT } from '../../i18n/index.jsx'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'

/**
 * "Şimdi kontrol et" düğmesi ve kart üstündeki ÇALIŞIYOR şeridi.
 *
 * <p><b>Neden var:</b> tetikleme aslında senkron — istek kontrol bitene kadar açık kalıyor ve
 * sonuç dönünce kart güncelleniyor. Ama ekrandaki tek geri bildirim düğmenin GRİLEŞMESİYDİ:
 * simge hâlâ ▶ olarak duruyordu. Sayfa hızı gibi 12–15 saniye süren bir kontrolde kullanıcı
 * hiçbir şey olmadığını sanıyor, düğmeye tekrar basıyordu. Bu iki bileşen "çalışıyor" durumunu
 * GÖRÜNÜR ve SÜREKLİ kılar: iş bitene kadar döner, geçen süreyi sayar.
 *
 * <p>Düğme ayrı bir bileşen: on izleme sayfasının altısı {@code MonitorCardActions}'ı
 * kullanıyor, dördü (DNS/Port/Sentetik/Sertifika) aynı kodu kendi içinde çiziyordu çünkü
 * ek koşulları var (ör. sentetikte k6 kurulu mu). Onları zorla tek eyleme bloğuna toplamak o
 * farkları gizlerdi; DÜĞMEYİ paylaşmak ise farkları bozmadan davranışı birleştiriyor.
 */

/** Kaç saniyedir çalışıyor — {@code running} false olunca sıfırlanır. */
function useElapsedSeconds(running) {
  const startedAt = useRef(null)
  const [seconds, setSeconds] = useState(0)

  useEffect(() => {
    if (running) {
      startedAt.current = Date.now()
      setSeconds(0)
    } else {
      startedAt.current = null
      setSeconds(0)
    }
  }, [running])

  // Sayaç GEÇEN SÜREDEN türetilir, tik sayısından değil: sekme arka plandayken
  // useVisibleInterval durur; artışı saymak olsaydı geri dönüldüğünde süre eksik kalırdı.
  useVisibleInterval(() => {
    if (startedAt.current != null) setSeconds(Math.floor((Date.now() - startedAt.current) / 1000))
  }, running ? 1000 : 0)

  return seconds
}

/**
 * Kontrol düğmesi. Çalışırken ▶ yerine spinner gösterir ve kilitlenir.
 *
 * @param {boolean} running  bu monitör şu anda kontrol ediliyor
 * @param {boolean} disabled çalışma dışı bir sebeple kapalı (ör. k6 kurulu değil)
 */
export function CheckNowButton({ running, disabled, onClick, title, className = '' }) {
  const t = useT()
  const label = running ? t('mon.checkRunning') : title
  return (
    <button
      type="button"
      // `.btn`/`.btn-sm` BİLEREK yok: onların dolgusu (10px 18px / 5px 10px) kare ikon
      // düğmesiyle çakışıyor, `.btn-sm`'in margin-right'ı da sarmalayıcının gap'iyle kavga
      // ediyordu (düzensiz boşluk).
      className={`mon-act mon-act--check ${className}`.trim()}
      disabled={running || disabled}
      onClick={onClick}
      title={label}
      aria-label={label}
      aria-busy={running || undefined}
    >
      {running ? <Spinner size={12} inline decorative /> : <Play size={12} />}
    </button>
  )
}

/**
 * Eylem satırında, düğmelerin SOLUNDA beliren "Kontrol ediliyor… N sn" şeridi.
 *
 * <p>Düğmedeki spinner tek başına yetmiyor: düğme 28 px'lik bir kare ve içine saniye sayacı
 * sığmaz. Şerit kullanıcının AZ ÖNCE TIKLADIĞI yerin hemen yanında belirir — gözün zaten
 * orada olduğu nokta. Çalışmıyorken HİÇBİR ŞEY render etmez, böylece kart yüksekliği
 * değişmez ve liste zıplamaz.
 */
export function CheckRunningStrip({ running, label }) {
  const t = useT()
  const seconds = useElapsedSeconds(running)
  if (!running) return null
  return (
    <span className="mon-running" role="status" aria-live="polite">
      <Spinner size={11} inline decorative />
      {/* `label`: aynı şerit başka bir evreyi de anlatabilir ("Kaydediliyor…", "İlk kontrol koşuyor…"). */}
      <span className="mon-running-lbl">{label || t('mon.checkRunning')}</span>
      {/* Saniye sayacı "askıda mı kaldı" sorusunu cevaplar: rakam ilerliyorsa iş sürüyor. */}
      <span className="mon-running-sec">{seconds} {t('mon.secShort')}</span>
    </span>
  )
}
