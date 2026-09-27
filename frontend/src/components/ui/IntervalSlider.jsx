import { useT } from '../../i18n/index.jsx'
import { Slider } from '@/components/shadcn/slider'
import { cn } from '@/lib/utils'

/**
 * "Kontrol Aralığı" kaydırma çubuğu — HER izleme türünde AYNI etkileşim. Çizim shadcn Slider (Radix).
 *
 * <p><b>Neden ortak bileşen:</b> aynı ayar dokuz formda iki farklı biçimde soruluyordu —
 * HTTP/Keyword/Port/Page/PageSpeed bir kaydırma çubuğu, DNS/Ping/Domain ise açılır liste.
 * Kullanıcı bir ekranda sürükleyip diğerinde listeden seçiyordu; aynı soru, iki farklı el
 * alışkanlığı.
 *
 * <p><b>Aralık listesi prop'tur, sabit DEĞİL:</b> türlerin tabanı bilinçli olarak farklı.
 * Sayfa Hızı'nın alt sınırı 5 dakikadır çünkü tek ölçüm onlarca istek demektir; DNS 30 saniyeye
 * inebilir. Ortak bir liste dayatmak bu kısıtları sessizce silerdi.
 *
 * <p>Çubuk SIRA NUMARASI üzerinde çalışır (0..n-1, eşit aralıklı duraklar); dışarıya her zaman
 * SANİYE döner. Başparmak (role="slider") adını başlıktan, okunur değerini (aria-valuetext)
 * seçili aralığın metninden alır — ekran okuyucu "3" değil "5 dk" okur.
 *
 * @param options {value, labelKey}[] — sıralı aralık seçenekleri
 * @param value   saniye cinsinden seçili aralık; listede yoksa EN YAKIN seçenek işaretlenir
 *                (kayıt eski bir değer taşıyorsa çubuk boşa düşmesin)
 * @param onChange yeni aralığı (saniye) döndürür
 * @param titleKey/everyKey — türe özel başlık/özet metni (ör. "Her 5 dk kontrol edilir")
 * @param note    çubuğun altında gösterilecek açıklama (ör. Sayfa Hızı'nın 5 dk taban gerekçesi)
 */
export default function IntervalSlider({
  options, value, onChange,
  titleKey = 'notify.intervalTitle', everyKey = 'notify.intervalEvery', note = null,
}) {
  const t = useT()
  if (!options || options.length === 0) return null

  // Listede olmayan bir değer (eski kayıt / ayar değişikliği) çubuğu 0'a düşürmemeli:
  // en yakın seçeneği göster ki kullanıcı gerçekte ne kayıtlı olduğunu görsün.
  let idx = options.findIndex(o => o.value === value)
  if (idx < 0) {
    let best = 0, bd = Infinity
    options.forEach((o, j) => { const d = Math.abs(o.value - (value ?? 0)); if (d < bd) { bd = d; best = j } })
    idx = best
  }
  const current = t(options[idx].labelKey)

  return (
    <div data-slot="interval-slider" className="col-span-full flex min-w-0 flex-col gap-2 rounded-lg border bg-muted/30 px-3.5 py-3">
      <div className="text-sm font-semibold">{t(titleKey)}</div>
      <p className="text-xs text-muted-foreground">{t(everyKey).replace('{0}', current)}</p>
      <Slider
        min={0} max={options.length - 1} step={1} value={[idx]}
        onValueChange={([v]) => { if (options[v]) onChange(options[v].value) }}
        thumbProps={{ 'aria-label': t(titleKey), 'aria-valuetext': current }}
        className="py-1.5"
      />
      <div className="flex justify-between gap-1 text-[11px] text-muted-foreground" aria-hidden="true">
        {options.map((o, j) => (
          <span key={o.value} data-slot="interval-tick" data-active={j === idx ? 'true' : undefined}
            className={cn('whitespace-nowrap', j === idx && 'font-semibold text-foreground')}>
            {t(o.labelKey)}
          </span>
        ))}
      </div>
      {note && <p className="text-xs text-muted-foreground">{note}</p>}
    </div>
  )
}
