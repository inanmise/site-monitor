import { useT } from '../i18n/index.jsx'

/**
 * "Genel Bakış" StatsPanel deseninin veri-agnostik, paylaşılan hâli.
 * Ping/Keyword (ileride port/dns) izleme sayfalarında üstte 6 tıklanabilir sayım kartı gösterir.
 *
 * Props:
 *  - items: [{ key, Icon, label, value, cls }]  (cls = mevcut .stat-item-{cls} renk sınıfı)
 *  - activeFilter: string|null  (aktif kart anahtarı)
 *  - onStatClick: (key) => void (toggle filtre)
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
            onClick={() => onStatClick(item.key)}
            title={isActive ? t('mondash.clearTip') : t('mondash.filterTip', item.label)}
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
