/**
 * Segmented control — "bir grup içinde tek aktif seçim" için TEK standart bileşen.
 * Eski desen (btn-primary/btn-secondary renk takası) executive görünmüyordu; bu, DNS modalındaki
 * pill grubunun (.dns-range-btn) rafine, token'lı genellemesidir. Saf sunum: etiketler dışarıdan.
 *
 * options: [{ value, label, icon?, title? }] — icon lucide bileşeni (opsiyonel).
 */
export default function SegmentedControl({ value, onChange, options, ariaLabel, className = '' }) {
  return (
    <div className={`seg-ctl${className ? ' ' + className : ''}`} role="group" aria-label={ariaLabel}>
      {options.map(o => {
        const Icon = o.icon
        const active = value === o.value
        return (
          <button key={String(o.value)} type="button"
            className={`seg-ctl-btn${active ? ' active' : ''}`}
            aria-pressed={active} title={o.title}
            onClick={() => { if (!active) onChange?.(o.value) }}>
            {Icon ? <Icon size={13} className="seg-ctl-icon" /> : null}
            {o.label}
          </button>
        )
      })}
    </div>
  )
}
