/**
 * İlerleme göstergesi ailesi — projedeki TEK standart. Yeni bir "yükleniyor" veya "ilerleme"
 * göstergesi gerektiğinde elle spinner/çubuk yazılmaz, buradan seçilir.
 *
 * Hangisini seçmeli:
 *   • <Spinner>      → kalan iş BİLİNMİYOR (belirsiz). Yüzde yok, aria-valuenow YOK.
 *   • <ProgressRing> → kompakt dairesel yay dolarak tamamlanmaya gidiyor (dar alan, kart köşesi).
 *   • <ProgressBar>  → okunabilir bir track'e yer var. Native <progress> kullanır.
 *   • <LoadingBlock> → sayfa/bölüm gövdesi yükleniyor (spinner + metin, ortalanmış).
 *
 * Erişilebilirlik sözleşmesi:
 *   • Belirli göstergeler role="progressbar" + aria-valuenow/valuemin/valuemax taşır. Ekranda
 *     görünen yüzde ile aria-valuenow AYNI değerdir — ikisi tek hesaptan gelir, ayrışamaz.
 *   • Belirsiz göstergede aria-valuenow VERİLMEZ; ARIA'da "değeri olmayan progressbar" =
 *     belirsiz demektir. Yanlış bir 0 değeri "hiç ilerlemedi" diye okunurdu.
 *   • Metin taşıyan belirsiz göstergeler role="status" + aria-live="polite" ile duyurulur;
 *     salt süs olanlar aria-hidden ile ekran okuyucudan gizlenir (çift okuma olmaz).
 *   • Butonlarda: butona aria-busy verin, içindeki spinner'a `decorative` (etiketi buton
 *     metni zaten taşır).
 *
 * Hareket: tüm animasyonlar prefers-reduced-motion altında dönmeyi bırakır (App.css .pg-*).
 * Native <progress> tarayıcı animasyonunu kendisi yönetir.
 */

/** 0..max aralığına kırpar; max 0/geçersizse null (belirsiz) döner. */
function clampValue(value, max) {
  const m = Number(max)
  if (!Number.isFinite(m) || m <= 0) return null
  const v = Number(value)
  if (!Number.isFinite(v)) return null
  return Math.max(0, Math.min(m, v))
}

const pct = (value, max) => Math.round((value / max) * 100)

/**
 * Belirsiz spinner — kalan iş bilinmiyorken.
 * @param size px cinsinden çap
 * @param label ekran okuyucuya duyurulacak metin; verilirse role="status" alır
 * @param decorative true → aria-hidden (yanında zaten açıklayıcı metin var, ör. buton içi)
 * @param inline true → satır içi hizalama (buton/metin yanı)
 */
export function Spinner({ size = 16, label, decorative = false, inline = false, className = '' }) {
  const cls = ['pg-spinner', inline ? 'pg-spinner--inline' : '', className].filter(Boolean).join(' ')
  const style = { width: size, height: size, borderWidth: Math.max(2, Math.round(size / 8)) }
  if (decorative || !label) {
    return <span className={cls} style={style} aria-hidden="true" />
  }
  return (
    <span className="pg-status" role="status" aria-live="polite">
      <span className={cls} style={style} aria-hidden="true" />
      <span className="pg-sr-only">{label}</span>
    </span>
  )
}

/**
 * Belirli lineer çubuk — native <progress>. Rol ve değer semantiğini tarayıcı verir;
 * ek ARIA bindirmiyoruz (çift semantik ekran okuyucularda çelişki üretir).
 * value/max geçersizse belirsiz <progress> render edilir (tarayıcı kendi animasyonunu çalar).
 */
export function ProgressBar({
  value, max = 100, label, showValue = false, size = 'md', decorative = false, className = '',
}) {
  const v = clampValue(value, max)
  const percent = v == null ? null : pct(v, max)
  const cls = ['pg-bar', `pg-bar--${size}`, className].filter(Boolean).join(' ')
  // decorative: aynı değer hemen yanında zaten METİN olarak görünüyorsa çubuk ekran okuyucuya
  // ikinci kez duyurulmaz (çift okuma gürültüsü). Görsel/animasyon davranışı aynı kalır.
  const a11y = decorative ? { 'aria-hidden': 'true' } : { 'aria-label': label || undefined }
  return (
    <div className="pg-bar-wrap">
      {(label || showValue) && !decorative && (
        <div className="pg-bar-head">
          {label && <span className="pg-bar-label">{label}</span>}
          {showValue && percent != null && <span className="pg-bar-value">%{percent}</span>}
        </div>
      )}
      {v == null
        ? <progress className={cls} {...a11y} />
        : <progress className={cls} value={v} max={max} {...a11y}>{percent}%</progress>}
    </div>
  )
}

/**
 * Belirli dairesel yay. Native karşılığı olmadığı için ARIA'yı elle veriyoruz:
 * role="progressbar" + aria-valuenow/min/max, aria-valuetext ile okunur metin.
 * Merkezdeki yüzde metni ile aria-valuenow aynı hesaptan gelir.
 */
export function ProgressRing({
  value, max = 100, size = 44, stroke = 4, label, showValue = true,
  color = 'var(--accent, #2563eb)', className = '',
}) {
  const v = clampValue(value, max)
  const percent = v == null ? null : pct(v, max)
  const r = (size - stroke) / 2
  const circ = 2 * Math.PI * r
  const dash = percent == null ? circ * 0.25 : (percent / 100) * circ
  const c = size / 2
  const aria = percent == null
    ? { 'aria-label': label || undefined }
    : {
        'aria-valuenow': percent, 'aria-valuemin': 0, 'aria-valuemax': 100,
        'aria-valuetext': `%${percent}`, 'aria-label': label || undefined,
      }
  return (
    <span
      className={['pg-ring', percent == null ? 'pg-ring--indeterminate' : '', className].filter(Boolean).join(' ')}
      role="progressbar" {...aria}
      style={{ width: size, height: size }}
    >
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true">
        <circle cx={c} cy={c} r={r} fill="none" stroke="var(--border, #e5e7eb)" strokeWidth={stroke} />
        <circle cx={c} cy={c} r={r} fill="none" stroke={color} strokeWidth={stroke} strokeLinecap="round"
          strokeDasharray={`${dash} ${circ - dash}`} transform={`rotate(-90 ${c} ${c})`} />
      </svg>
      {showValue && percent != null && <span className="pg-ring-value">{percent}</span>}
    </span>
  )
}

/**
 * Sayfa/bölüm gövdesi yükleniyor — eski `<div className="loading">…</div>` deseninin yerine.
 * @param fullWidth true → grid kaplarında tüm satırı kaplar (.loading'in grid-column:1/-1 davranışı)
 */
export function LoadingBlock({ label, fullWidth = false, size = 20, className = '' }) {
  const cls = ['pg-block', fullWidth ? 'pg-block--full' : '', className].filter(Boolean).join(' ')
  return (
    <div className={cls} role="status" aria-live="polite">
      <span className="pg-spinner" style={{ width: size, height: size, borderWidth: Math.max(2, Math.round(size / 8)) }} aria-hidden="true" />
      {label && <span className="pg-block-label">{label}</span>}
    </div>
  )
}
