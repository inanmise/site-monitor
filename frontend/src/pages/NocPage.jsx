import { useState } from 'react'
import { RadioTower, ShieldCheck } from 'lucide-react'
import { useT } from '../i18n/index.jsx'
import { readUrlParam, useUrlQuerySync } from '../hooks/useUrlQuerySync.js'
import { usePermissions } from '../contexts/PermissionsProvider.jsx'
import NocCoveragePage from './NocCoveragePage.jsx'
import NocConsole from '../components/noc/console/NocConsole.jsx'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'

export const NOC_VIEWS = ['console', 'coverage']

/** Açılış görünümü: 7/24 operatörü konsolla, diğerleri (global görücü) bugünkü gibi kapsamla açılır. */
export function defaultNocView(nocOperator) {
  return nocOperator ? 'console' : 'coverage'
}

/**
 * 7/24 sekmesi (`?tab=noc`, 2026-10-04): **7/24 Konsolu** (kurum geneli canlı alarm/bildirim görünümü + arama) ve
 * **7/24 Kapsamı** (hangi izlemeler 7/24'e gidiyor). Konsol yalnız TÜM alarmları görebilene açıktır — 7/24 izleme ekibi
 * takımının üyesi (ya da eski AUDIT + arama kaydı düzeni) ve global görücü (admin/AUDIT); diğerleri bugünkü gibi
 * yalnız kapsam sayfasını görür (sekme listesi hiç çizilmez). Görünüm URL'de `n_view` (varsayılan yazılmaz).
 */
export default function NocPage({ nocOperator = false, nocCanWrite = false, globalViewer = false, ...coverageProps }) {
  const t = useT()
  // CANLI kapsam (2026-10-22, UIM-29): `/api/me/permissions` scope.noc_console / noc_operator 60 sn'de tazelenir — 7/24
  // takımına eklenen konsolu, çıkarılan kapsamı yeniden girmeden görür (Kişisel Panom ile aynı kural). Eski sunucu
  // (scope yok / alan boolean değil) → girişteki /me değerleri, bugünkü davranış.
  const { scope } = usePermissions()
  const operator = typeof scope?.noc_operator === 'boolean' ? scope.noc_operator : nocOperator
  const canConsole = typeof scope?.noc_console === 'boolean' ? scope.noc_console : (nocOperator || globalViewer)
  const def = defaultNocView(operator)
  const [view, setView] = useState(() => {
    const v = readUrlParam('n_view', null)
    return canConsole && NOC_VIEWS.includes(v) ? v : def
  })
  useUrlQuerySync({ n_view: canConsole && view !== def ? view : null })

  if (!canConsole) return <NocCoveragePage {...coverageProps} nocOperator={operator} />
  return (
    <Tabs value={view} onValueChange={(v) => { if (NOC_VIEWS.includes(v)) setView(v) }} data-slot="noc-views" className="min-w-0 gap-4">
      <TabsList aria-label={t('noc.view.label')} className="w-full justify-start overflow-x-auto group-data-[orientation=horizontal]/tabs:h-auto sm:w-auto">
        <TabsTrigger value="console" data-view="console" className="h-10 gap-1.5 sm:h-9 sm:pointer-coarse:h-10">
          <RadioTower aria-hidden="true" />{t('noc.view.console')}
        </TabsTrigger>
        <TabsTrigger value="coverage" data-view="coverage" className="h-10 gap-1.5 sm:h-9 sm:pointer-coarse:h-10">
          <ShieldCheck aria-hidden="true" />{t('noc.view.coverage')}
        </TabsTrigger>
      </TabsList>
      <TabsContent value="console" className="min-w-0">
        <NocConsole canWrite={nocCanWrite} />
      </TabsContent>
      <TabsContent value="coverage" className="min-w-0">
        <NocCoveragePage {...coverageProps} nocOperator={operator} />
      </TabsContent>
    </Tabs>
  )
}
