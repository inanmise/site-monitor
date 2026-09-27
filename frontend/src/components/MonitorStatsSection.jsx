import { BarChart3 } from 'lucide-react'
import MonitorStatsBar from './MonitorStatsBar.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import CollapsibleSection from './ui/CollapsibleSection.jsx'
import { useT } from '../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * İzleme sayfalarının istatistik şeridi: katlanabilir başlık + sayım kartları + aktif filtre çubuğu.
 * Çizim shadcn: Collapsible (tetik Button, aria-expanded Radix'ten), filtre çubuğu ui/AlertBanner.
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
  const toggleLabel = statsVisible ? t('app.collapseStats') : t('app.expandStats')
  // Başlık = projenin tek katlanır şeridi (ui/CollapsibleSection; Pano/İstatistikler/Sistem Sağlığı ile aynı)
  return (
    <CollapsibleSection open={statsVisible} onOpenChange={() => onToggle()} showTrigger={hasMonitors}
      icon={BarChart3} label={t('app.statistics')} hint={t('app.expandStats')} toggleLabel={toggleLabel}
      triggerClassName="mb-2">
      {hasMonitors && (
        <MonitorStatsBar items={items} activeFilter={activeFilter} onStatClick={onStatClick} />
      )}
      {activeFilter && activeFilter !== 'total' && (
        <AlertBanner tone="info" className="mb-4"
          actions={<Button type="button" variant="outline" size="sm" onClick={onClearFilter}>{t('app.clearFilter')}</Button>}>
          {items.find(s => s.key === activeFilter)?.label} — {t('mondash.showing', shownCount)}
        </AlertBanner>
      )}
    </CollapsibleSection>
  )
}
