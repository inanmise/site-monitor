import { Suspense, lazy } from 'react'
import { FileDiff } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { Skeleton } from '@/components/shadcn/skeleton'
import RuleTestResults, { ruleResultsOf } from '../rules/RuleTestResults.jsx'
import { rulesFromRow } from '../rules/ruleModel.js'
import KeywordChangePanel from './KeywordChangePanel.jsx'
import KeywordMatchesPanel from './KeywordMatchesPanel.jsx'
import { sizeOkOf } from './contentDetailModel.js'

// Grafik kitaplığı yalnız bu sekme açılınca (tembel parça — kapı keywordLazyChunks.test.js)
const KeywordContentChart = lazy(() => import('./KeywordContentChart.jsx'))

/**
 * Ayrıntı penceresi "İçerik" sekmesi (kw paket C + D + E2, 2026-11-05) — Aşama 2'de `DetailTabs`'a
 * `['content', t('keyword.tabContent')]` olarak bağlanır. Yukarıdan aşağıya:
 * <ol>
 *   <li>Son kontrolün kural sonuçları (`rule_results`, `breach_level`, boyut kuralı) — kayıt yoksa nedenini söyler.</li>
 *   <li>Eşleşme sayısı + sayfa boyutu grafiği (tembel; okunamadı ≠ boş).</li>
 *   <li>İçerik değişimi paneli (yalnız değişim açık izlemede; kapalıysa nasıl açılacağı yazılır).</li>
 *   <li>Canlı eşleşmeler (EXECUTE yetkisiyle).</li>
 * </ol>
 *
 * @param {object}  monitor       GET /monitoring/keyword satırı (snake_case)
 * @param {boolean} canEdit       düzenleme (temel al / sıfırla)
 * @param {boolean} canExecute    tetik yetkisi (canlı eşleşmeler)
 * @param {Function} onChanged    temel değişince (sayfa listeyi tazelesin)
 */
export default function KeywordContentTab({ monitor, canEdit = false, canExecute = false, onChanged }) {
  const t = useT()
  const m = monitor || {}
  const rules = rulesFromRow(m)
  const results = ruleResultsOf(m.rule_results)
  const sizeOk = sizeOkOf(m)
  const level = m.alert_level || 'WARNING'

  return (
    <div data-slot="kw-content-tab" className="flex min-w-0 flex-col gap-5">
      <section data-slot="kw-content-last" className="flex min-w-0 flex-col gap-2">
        <h4 className="m-0 text-sm font-semibold">{t('keyword.content.lastRules')}</h4>
        {results || sizeOk != null ? (
          <RuleTestResults results={results} rules={rules} level={m.breach_level} sizeOk={sizeOk} monitorLevel={level}
            title={t('keyword.rule.res.title')} />
        ) : (
          <p data-slot="kw-content-last-none" className="m-0 text-sm text-muted-foreground">
            {m.checked_at ? t('keyword.rule.res.none') : t('keyword.content.notChecked')}
          </p>
        )}
      </section>

      <Suspense fallback={<Skeleton data-slot="kw-content-chart-loading" className="h-56 w-full motion-reduce:animate-none" />}>
        <KeywordContentChart monitorId={m.id} />
      </Suspense>

      {m.change_detect === true ? (
        <KeywordChangePanel monitorId={m.id} canEdit={canEdit} onChanged={onChanged} />
      ) : m.change_detect === false ? (
        <section data-slot="kw-content-change-off" className="flex min-w-0 flex-col gap-2">
          <h4 className="m-0 flex items-center gap-1.5 text-sm font-semibold"><FileDiff aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />{t('keyword.change.title')}</h4>
          <StatusBlock tone="neutral" title={t('keyword.change.disabled')} className="py-4" />
        </section>
      ) : null}

      <KeywordMatchesPanel monitorId={m.id} canExecute={canExecute} rules={rules} />
    </div>
  )
}
