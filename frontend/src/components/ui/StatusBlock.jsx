/**
 * Ortalanmış durum bloğu — boş liste, hata ekranı, "sonuç yok" gibi tam-bölüm durumları.
 *
 * Neden var: `.empty-state` (App.css) `color: var(--success)` taşıyor, yani BOŞ ve HATALI
 * durumlar yeşil çıkıyor. ErrorBoundary fallback'i de o sınıfı kullandığı için çökme ekranı
 * yeşil bir gövdeye kırmızı bir başlık koyuyordu. Bu bileşen tonu açık bir prop yapar.
 *
 * Saf sunum: metinler ve ikon dışarıdan gelir, içinde useT() yoktur.
 */
export default function StatusBlock({
  tone = 'neutral', icon: Icon, title, description, actions, children, role, className = '',
}) {
  return (
    <div className={['status-block', `status-block--${tone}`, className].filter(Boolean).join(' ')} role={role}>
      {Icon && (
        <div className="status-block-icon" aria-hidden="true">
          <Icon size={36} />
        </div>
      )}
      {title && <div className="status-block-title">{title}</div>}
      {description && <div className="status-block-desc">{description}</div>}
      {actions && <div className="status-block-actions">{actions}</div>}
      {children}
    </div>
  )
}
