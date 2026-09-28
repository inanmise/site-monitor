import { LayoutGrid, LayoutList } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'

/**
 * Kart görünümü seçicisi — Kompakt / Zengin (2026-09-27). Genel Bakış'taki satır içi ToggleGroup'tan çıkarıldı;
 * Genel Bakış ve dokuz izleme sayfası AYNI bileşeni kullanır (kapı: cardDensityStandard.test.js).
 *
 * Tek seçim, seçili öğe boşaltılamaz (Radix tek seçimde aynı öğeye tıklamak '' verir → yok sayılır). Dokunmatikte
 * 40 px yükseklik. Durumu çağıran tutar: `useCardDensity(sayfa)`.
 *
 * @param {'compact'|'rich'} value
 * @param {(v: 'compact'|'rich') => void} onChange
 * @param {string} [tip]  erişilebilir ad + ipucu; verilmezse genel izleme metni (`card.densityTip`)
 * @param {boolean} [hideLabelsOnPhone]  < 640 px'te yalnız ikon (40×40; metin ekran okuyucuda kalır) — dar araç satırında
 *   yanındaki düğmeye yer açar (Genel Bakış telefon satırı: "Süzgeçler (N)" + bu seçici, 2026-09-28). Varsayılan kapalı.
 */
export default function CardDensityToggle({ value, onChange, tip, className, hideLabelsOnPhone = false }) {
  const t = useT()
  const label = tip || t('card.densityTip')
  const item = cn('px-2.5 pointer-coarse:h-10', hideLabelsOnPhone && 'max-sm:w-10 max-sm:px-0')
  const text = (s) => (hideLabelsOnPhone ? <span className="max-sm:sr-only">{s}</span> : s)
  return (
    <ToggleGroup type="single" variant="outline" size="sm" value={value} data-slot="card-density-toggle"
      onValueChange={(v) => { if (v && v !== value) onChange(v) }}
      aria-label={label} title={label}
      className={cn('shrink-0', className)}>
      <ToggleGroupItem value="compact" className={item}><LayoutGrid aria-hidden="true" /> {text(t('ccx.modeCompact'))}</ToggleGroupItem>
      <ToggleGroupItem value="rich" className={item}><LayoutList aria-hidden="true" /> {text(t('ccx.modeRich'))}</ToggleGroupItem>
    </ToggleGroup>
  )
}
