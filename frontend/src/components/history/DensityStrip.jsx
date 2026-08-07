import { useT } from '../../i18n/index.jsx'
import { formatDateSec } from '../../api/client'

/**
 * Durum yoğunluk şeridi — seçili aralığın kovaları (dakika/saat/gün) tek satır hücre dizisi.
 * Hücre rengi hata oranına göre success→danger arasında (color-mix, token'lı — hex yok);
 * tıklanınca o kovanın alt-aralığına iner (onZoom). Hatalı bölgeyi scroll'suz bulmanın yolu.
 * Saf div — grafik kütüphanesi yok. Boş kovalar (hiç kontrol yok) backend'den gelmez, atlanır.
 */

/** Kova anahtarını [fromIso, toIso] aralığına açar — uzunluk kovanın genişliğini söyler. */
export function bucketBounds(key) {
  if (!key) return null
  if (key.length === 16) return [key + ':00', key + ':59']            // dakika
  if (key.length === 13) return [key + ':00:00', key + ':59:59']      // saat
  if (key.length === 10) return [key + 'T00:00:00', key + 'T23:59:59'] // gün
  return [key, key]
}

export default function DensityStrip({ buckets, onZoom, zoomed = false, onReset }) {
  const t = useT()
  if (!buckets || buckets.length === 0) return null
  return (
    <div className="hist-strip-wrap">
      <div className="hist-strip" role="group" aria-label={t('hist.stripLabel')}>
        {buckets.map(b => {
          const total = Number(b.total) || 0
          const fail = Number(b.fail) || 0
          const ratio = total > 0 ? fail / total : 0
          const pct = Math.round(ratio * 100)
          const bg = fail === 0
            ? 'color-mix(in srgb, var(--success) 30%, transparent)'
            : `color-mix(in srgb, var(--danger) ${Math.max(35, pct)}%, var(--success))`
          const bounds = bucketBounds(String(b.key))
          const label = `${formatDateSec(bounds[0])} — ${total} / ${fail} ${t('hist.stripFail')}`
          return (
            <button key={b.key} type="button" className={`hist-strip-cell${fail > 0 ? ' hist-strip-cell--fail' : ''}`}
              style={{ background: bg }} title={label} aria-label={label}
              onClick={() => bounds && onZoom?.(bounds[0], bounds[1])} />
          )
        })}
      </div>
      {zoomed && (
        <button type="button" className="hist-strip-reset" onClick={onReset}>{t('hist.resetZoom')}</button>
      )}
    </div>
  )
}
