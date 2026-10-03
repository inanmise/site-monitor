import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'

/**
 * Segmented control — "bir grup içinde tek aktif seçim" için TEK standart bileşen.
 * İç uygulama shadcn ToggleGroup (type="single"); saf sunum, etiketler dışarıdan.
 *
 * options: [{ value, label, icon?, title?, disabled? }] — icon lucide bileşeni (opsiyonel);
 * `disabled` o seçeneği seçilemez yapar (ör. karşılaştırılacak önceki sürüm yokken "Fark").
 *
 * Sözleşme notları (dış API değişmedi):
 *  • Radix öğe değerleri DİZE ister ve '' "seçim yok" demektir; seçenek değerleri ise sayı
 *    (7, 30) ya da '' ("Tümü") olabiliyor. Bu yüzden öğeler SIRA NUMARASIYLA anahtarlanır ve
 *    dış değer eskisi gibi katı eşitlikle (===) eşlenir — `onChange` orijinal değeri alır.
 *  • Etkin öğeye yeniden basmak Radix'te seçimi boşaltır (''): tek-aktif-seçim sözleşmesi
 *    gereği yok sayılır ve onChange TETİKLENMEZ (gereksiz yeniden yükleme yok).
 *  • Erişilebilirlik: Radix'in tekli kipteki radiogroup/radio rolleri yerine eski sözleşme
 *    (role="group" + aria-pressed'li düğmeler) korunur — onlarca ekran ve testi düğme adıyla
 *    seçim yapıyor; WAI-ARIA'da geçerli bir "toggle button group" kalıbıdır.
 *  • `itemClassName` (2026-10-03, isteğe bağlı): öğe sınıfına eklenir — ör. giriş ekranında telefonda tam genişlik ve
 *    40 px dokunma hedefi (`flex-1 max-sm:h-10`). Verilmezse çizim birebir eskisi gibi.
 */
export default function SegmentedControl({ value, onChange, options, ariaLabel, className = '', itemClassName = '' }) {
  const activeIdx = options.findIndex((o) => o.value === value)

  return (
    <ToggleGroup
      type="single"
      role="group"
      aria-label={ariaLabel}
      spacing={0.5}
      value={activeIdx >= 0 ? String(activeIdx) : ''}
      onValueChange={(v) => {
        if (v === '') return
        const o = options[Number(v)]
        if (o && o.value !== value) onChange?.(o.value)
      }}
      className={cn('inline-flex rounded-lg bg-muted p-[3px]', className)}
    >
      {options.map((o, i) => {
        const Icon = o.icon
        const active = i === activeIdx
        return (
          <ToggleGroupItem
            key={String(o.value)}
            value={String(i)}
            role="button"
            aria-pressed={active}
            aria-checked={undefined}
            title={o.title}
            disabled={o.disabled || undefined}
            className={cn('h-7 gap-1.5 px-3 text-xs font-medium text-muted-foreground hover:bg-transparent hover:text-foreground data-[state=on]:bg-background data-[state=on]:text-foreground data-[state=on]:shadow-sm dark:data-[state=on]:bg-input/50', itemClassName)}
          >
            {Icon ? <Icon className="size-3.5" aria-hidden="true" /> : null}
            {o.label}
          </ToggleGroupItem>
        )
      })}
    </ToggleGroup>
  )
}
