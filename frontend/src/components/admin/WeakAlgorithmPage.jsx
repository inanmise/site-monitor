import { lazy, Suspense, useState } from 'react'
import { Atom, ShieldAlert } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { readUrlParam, useUrlQuerySync } from '../../hooks/useUrlQuerySync.js'
import { LoadingBlock } from '../ui/Progress.jsx'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import WeakAlgorithmReport from './WeakAlgorithmReport.jsx'

// Kripto envanteri yalnız kendi sekmesi açılınca iner (grafik + tablo + dışa aktarım menüsü — bulgu görünümünü ağırlaştırmasın)
const CryptoInventoryView = lazy(() => import('../cryptoinv/CryptoInventoryView.jsx'))

/**
 * Zayıf Algoritma sayfası (2026-10-10): "Bulgular | Kripto envanteri & PQC" sekmeleri. Aynı veri (sertifika algoritması),
 * aynı izin (`weak_algo.read`), aynı görüş kapsamı — kripto envanteri ayrı bir menü öğesi yerine bu sayfanın sekmesi,
 * gezinme tutarlı kalsın. Sekme URL'de: `ci_view=crypto` (varsayılan yazılmaz; `ci_` öneki sekme değişince temizlenir).
 * Radix pasif sekmeyi söker: envanter isteği yalnız sekme açıkken atılır.
 */
export default function WeakAlgorithmPage() {
  const t = useT()
  const [view, setView] = useState(() => (readUrlParam('ci_view') === 'crypto' ? 'crypto' : 'findings'))
  useUrlQuerySync({ ci_view: view === 'crypto' ? 'crypto' : null })
  return (
    <Tabs value={view} onValueChange={setView} className="min-w-0 gap-4">
      <TabsList aria-label={t('cinv.tabs')} className="h-auto! w-full flex-row! sm:w-fit" data-slot="wa-tabs">
        <TabsTrigger value="findings" data-tab="findings" className="min-h-10 w-auto! flex-1 justify-center! gap-1.5 px-3 sm:min-h-8 sm:flex-none">
          <ShieldAlert aria-hidden="true" />{t('cinv.tab.findings')}
        </TabsTrigger>
        <TabsTrigger value="crypto" data-tab="crypto" className="min-h-10 w-auto! flex-1 justify-center! gap-1.5 px-3 sm:min-h-8 sm:flex-none">
          <Atom aria-hidden="true" />{t('cinv.tab.crypto')}
        </TabsTrigger>
      </TabsList>
      <TabsContent value="findings" className="mt-0 min-w-0">
        <WeakAlgorithmReport />
      </TabsContent>
      <TabsContent value="crypto" className="mt-0 min-w-0">
        <Suspense fallback={<LoadingBlock label={t('cinv.loading')} />}>
          <CryptoInventoryView />
        </Suspense>
      </TabsContent>
    </Tabs>
  )
}
