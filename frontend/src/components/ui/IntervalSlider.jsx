import { useT } from '../../i18n/index.jsx'

/**
 * "Kontrol Aralığı" kaydırma çubuğu — HER izleme türünde AYNI etkileşim.
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

  return (
    <div className="full-width http-interval-block">
      <div className="http-block-title">{t(titleKey)}</div>
      <div className="field-hint" style={{ marginBottom: 8 }}>
        {t(everyKey).replace('{0}', t(options[idx].labelKey))}
      </div>
      <input type="range" className="http-interval-slider"
        min={0} max={options.length - 1} step={1} value={idx}
        aria-label={t(titleKey)}
        onChange={e => onChange(options[Number(e.target.value)].value)} />
      <div className="http-interval-ticks">
        {options.map((o, j) => (
          <span key={o.value} className={`http-interval-tick${j === idx ? ' active' : ''}`}>{t(o.labelKey)}</span>
        ))}
      </div>
      {note && <div className="field-hint" style={{ marginTop: 6 }}>{note}</div>}
    </div>
  )
}
