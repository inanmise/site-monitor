import { useEffect, useRef, useState } from 'react'
import { Play } from 'lucide-react'
import { Spinner } from './Progress.jsx'
import { useT } from '../../i18n/index.jsx'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'

/*
 * İzleme eylem düğmesi dili (eski `.mon-act` ailesinin shadcn karşılığı) — kart ve detay
 * modalında AYNI: 28 px kare outline ikon düğmesi, soluk ikon; üzerine gelince eylemin vurgu
 * rengi (kontrol=yeşil, düzenle=mavi, kopyala=metin, sil=kırmızı) + hafif kalkış.
 * MonitorModalActions da bunları kullanır.
 */
export const MON_ACT = cn(
  'size-7 rounded-lg bg-card text-muted-foreground shadow-none',
  'transition-[color,border-color,background-color,translate,box-shadow] motion-reduce:transition-none',
  'enabled:hover:-translate-y-px enabled:hover:shadow-md enabled:active:translate-y-0 enabled:active:shadow-none motion-reduce:enabled:hover:translate-y-0',
)
export const MON_ACT_TONE = {
  check:  'enabled:hover:border-success enabled:hover:bg-success/10 enabled:hover:text-success',
  edit:   'enabled:hover:border-primary enabled:hover:bg-primary/10 enabled:hover:text-primary',
  copy:   'enabled:hover:border-foreground enabled:hover:bg-accent enabled:hover:text-foreground',
  danger: 'enabled:hover:border-destructive enabled:hover:bg-destructive/10 enabled:hover:text-destructive',
}
// ÇALIŞIRKEN düğme "kapalı" değil "meşgul" görünmeli: tıklanamaz ama soluklaşmaz — soluk bir
// düğme "bir şey olmuyor" der, oysa iş tam da o an sürüyor.
const CHECK_BUSY = 'aria-busy:border-success aria-busy:bg-success/10 aria-busy:text-success aria-busy:disabled:opacity-100'

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
    <Button
      type="button"
      variant="outline"
      size="icon-sm"
      className={cn(MON_ACT, MON_ACT_TONE.check, CHECK_BUSY, className)}
      disabled={running || disabled}
      onClick={onClick}
      title={label}
      aria-label={label}
      aria-busy={running || undefined}
    >
      {running ? <Spinner size={12} inline decorative /> : <Play size={12} />}
    </Button>
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
    <span data-slot="check-running" role="status" aria-live="polite"
      className="mr-0.5 inline-flex items-center gap-1.5 text-[11.5px] font-semibold whitespace-nowrap text-success">
      <Spinner size={11} inline decorative />
      {/* `label`: aynı şerit başka bir evreyi de anlatabilir ("Kaydediliyor…", "İlk kontrol koşuyor…").
          Dar ekranda metin düşer, sayaç kalır: asıl bilgi "hâlâ sürüyor mu" sorusudur. */}
      <span className="tracking-[.01em] max-[640px]:hidden">{label || t('mon.checkRunning')}</span>
      {/* Saniye sayacı "askıda mı kaldı" sorusunu cevaplar: rakam ilerliyorsa iş sürüyor.
          Tabular rakam: 7→8→9→10 geçişinde şerit sağa sola oynamasın. Test kancası: data-slot. */}
      <Badge variant="secondary" data-slot="check-running-seconds"
        className="bg-success/15 px-1.5 py-px font-bold tabular-nums text-success">
        {seconds} {t('mon.secShort')}
      </Badge>
    </span>
  )
}
