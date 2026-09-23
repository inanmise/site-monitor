import { BarChart3, ChevronDown } from 'lucide-react'
import MonitorStatsBar from './MonitorStatsBar.jsx'
import { useT } from '../i18n/index.jsx'

/**
 * İzleme sayfalarının istatistik şeridi: katlanabilir başlık + sayım kartları + aktif filtre çubuğu.
 *
 * <p>Bu 18 satırlık JSX sekiz izleme sayfasında (domain/http/keyword/page/ping/port/scripted/dns)
 * <b>birebir aynı</b> kopyalanmıştı — projedeki en büyük tekrar eden blok. Tek fark DNS
 * sayfasındaki yerel değişken adıydı ({@code filtered} vs {@code displayMonitors}); aynı sayıyı
 * gösterdiği için burada tek bir {@code shownCount} prop'una indirgendi.
 *
 * <p>Neden çıkarıldı: kopya blokların bedeli ölçüldü — Mayıs'tan bu yana izleme sayfalarına
 * dokunan 91 commit'in 15'i dört ya da daha fazla sayfaya AYNI ANDA dokunmak zorunda kaldı.
 * Bu blok her seferinde sekiz kez elle düzenleniyordu ve bir kopyanın atlanması sessiz bir
 * tutarsızlık bırakıyordu.
 *
 * <p>Davranış aynen korundu: şerit yalnız yükleme bittiğinde VE en az bir monitör varken
 * görünür; kartlar ancak şerit açıkken çizilir; filtre çubuğu yalnız 'total' dışı bir filtre
 * seçiliyse ve şerit açıkken görünür.
 *
 * @param {boolean}  loading       sayfanın yükleme durumu
 * @param {number}   total         ham monitör sayısı (filtre ÖNCESİ) — şerit bununla gösterilir
 * @param {boolean}  statsVisible  şerit açık mı
 * @param {Function} onToggle      şeridi aç/kapa
 * @param {Array}    items         MonitorStatsBar öğeleri ({ key, Icon, label, value, cls, hint? })
 * @param {string|null} activeFilter seçili sayım kartı anahtarı
 * @param {Function} onStatClick   kart tıklaması (toggle filtre)
 * @param {Function} onClearFilter "filtreyi temizle" düğmesi
 * @param {number}   shownCount    filtre uygulandıktan SONRA görünen monitör sayısı
 */
export default function MonitorStatsSection({
  loading, total, statsVisible, onToggle,
  items, activeFilter, onStatClick, onClearFilter, shownCount,
}) {
  const t = useT()
  const hasMonitors = !loading && total > 0
  return (
    <>
      {hasMonitors && (
        <div className="stats-collapse-bar" onClick={onToggle}
          role="button" tabIndex={0} aria-expanded={statsVisible}
          aria-label={statsVisible ? t('app.collapseStats') : t('app.expandStats')}
          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onToggle() } }}
          title={statsVisible ? t('app.collapseStats') : t('app.expandStats')}>
          <span className="stats-collapse-icon"><BarChart3 size={18} /></span>
          <span className="stats-collapse-label">{t('app.statistics')}</span>
          {!statsVisible && <span className="stats-collapse-hint">{t('app.expandStats')}</span>}
          <span className={`stats-collapse-chevron${statsVisible ? ' open' : ''}`}><ChevronDown size={18} /></span>
        </div>
      )}
      {statsVisible && hasMonitors && (
        <MonitorStatsBar items={items} activeFilter={activeFilter} onStatClick={onStatClick} />
      )}
      {statsVisible && activeFilter && activeFilter !== 'total' && (
        <div className="stats-filter-bar" style={{ marginBottom: 16 }}>
          <span>{items.find(s => s.key === activeFilter)?.label} — {t('mondash.showing', shownCount)}</span>
          <button className="stats-filter-clear" onClick={onClearFilter}>{t('app.clearFilter')}</button>
        </div>
      )}
    </>
  )
}
