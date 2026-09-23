import { useT } from '../i18n/index.jsx'

/**
 * "Genel Bakış" StatsPanel deseninin veri-agnostik, paylaşılan hâli.
 * Ping/Keyword (ileride port/dns) izleme sayfalarında üstte 6 tıklanabilir sayım kartı gösterir.
 *
 * Props:
 *  - items: [{ key, Icon, label, value, cls, hint? }]  (cls = mevcut .stat-item-{cls} renk sınıfı)
 *  - activeFilter: string|null  (aktif kart anahtarı)
 *  - onStatClick: (key) => void (toggle filtre)
 *
 * `hint`: kartın NE SAYDIĞINI açıklayan bir cümle; ipucu metninin sonuna eklenir. Etiketler kısa
 * olmak zorunda ve kısa etiket yanlış okunabiliyor — "Sahiplenilmemiş" kartı sahada "takımı yok"
 * diye anlaşıldı, oysa "açık alarmı henüz kimse sahiplenmemiş" demek.
 */
export default function MonitorStatsBar({ items, activeFilter, onStatClick }) {
  const t = useT()
  if (!items || items.length === 0) return null
  return (
    <div className="stats-panel">
      {items.map((item) => {
        const isActive = activeFilter === item.key
        return (
          <div
            key={item.key}
            className={`stat-item stat-item-${item.cls} stat-clickable${isActive ? ' stat-active' : ''}`}
            role="button" tabIndex={0}
            aria-pressed={isActive}
            aria-label={isActive ? t('mondash.clearTip') : t('mondash.filterTip', item.label)}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onStatClick(item.key) } }}
            onClick={() => onStatClick(item.key)}
            title={[isActive ? t('mondash.clearTip') : t('mondash.filterTip', item.label), item.hint]
              .filter(Boolean).join(' — ')}
          >
            <span className="stat-icon"><item.Icon size={32} /></span>
            <span className={`stat-value stat-value-${item.cls}`}>{item.value ?? 0}</span>
            <span className="stat-label">{item.label}</span>
            {isActive && <span className="stat-active-dot" />}
          </div>
        )
      })}
    </div>
  )
}
