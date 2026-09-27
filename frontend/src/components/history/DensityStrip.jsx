import { useT } from '../../i18n/index.jsx'
import { formatDateSec } from '../../api/client'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * Durum yoğunluk şeridi — seçili aralığın kovaları (dakika/saat/gün) tek satır hücre dizisi.
 * Hücre rengi hata oranına göre success→danger arasında (color-mix, token'lı — hex yok);
 * tıklanınca o kovanın alt-aralığına iner (onZoom). Hatalı bölgeyi scroll'suz bulmanın yolu.
 * Grafik kütüphanesi yok; hücreler shadcn Button (renk satır içi — hata oranından hesaplanır),
 * ipucu shadcn Tooltip. Boş kovalar (hiç kontrol yok) backend'den gelmez, atlanır.
 * Test kancaları: hücre `data-cell` (+ hatalıysa `data-fail`), sıfırlama düğmesi adıyla.
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
    <div className="mt-0.5 mb-2.5 flex items-center gap-2">
      <div className="flex h-5 flex-auto gap-px overflow-hidden rounded" role="group" aria-label={t('hist.stripLabel')}>
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
            <SimpleTooltip key={b.key} content={label}>
              <Button type="button" variant="ghost" data-cell="true" data-fail={fail > 0 ? 'true' : undefined}
                className="h-full min-w-[3px] flex-1 basis-0 rounded-none p-0 transition-[filter] duration-100 hover:brightness-[.82] focus-visible:ring-0 focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-primary"
                style={{ background: bg }} aria-label={label}
                onClick={() => bounds && onZoom?.(bounds[0], bounds[1])} />
            </SimpleTooltip>
          )
        })}
      </div>
      {zoomed && (
        <Button type="button" variant="outline" size="xs" onClick={onReset}
          className="shrink-0 rounded-full border-primary text-[11px] font-semibold text-primary hover:bg-primary/10 hover:text-primary">
          {t('hist.resetZoom')}
        </Button>
      )}
    </div>
  )
}
