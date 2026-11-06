import { Suspense, lazy } from 'react'
import { FileDiff } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { Skeleton } from '@/components/shadcn/skeleton'
import ToneBadge from '../../admin/ToneBadge.jsx'
import RuleTestResults, { ruleResultsOf } from '../rules/RuleTestResults.jsx'
import { isSimpleRule, logicOf, rulesFromRow } from '../rules/ruleModel.js'
import { verdictOf } from '../keywordCardModel.js'
import { keywordResultText } from '../KeywordCardParts.jsx'
import { cardReason, expectPhrase, ruleOutcomeOf } from '../keywordFailureModel.js'
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
  const checked = !!m.checked_at && m.status !== 'unknown'
  // Sayfa okunamadı (istek tamamlanmadı): kural değerlendirilmedi — "kural sonucu yok" değil, neden söylenir (DÜŞÜK-7)
  const unreadable = checked && m.status === 'error'
  // Basit tek kural: sunucu kural sonucu yazmaz (eski alanlarla değerlendirilir) — sayım + kartla aynı hüküm
  const simple = checked && !unreadable && !results && rules.length === 1 && isSimpleRule(rules[0])
  const verdict = simple ? verdictOf(m) : null
  const count = Number.isFinite(Number(m.occurrences)) && m.occurrences !== null && m.occurrences !== '' ? Number(m.occurrences) : null

  return (
    <div data-slot="kw-content-tab" className="flex min-w-0 flex-col gap-5">
      <section data-slot="kw-content-last" className="flex min-w-0 flex-col gap-2">
        <h4 className="m-0 text-sm font-semibold">{t('keyword.content.lastRules')}</h4>
        {!checked ? (
          <p data-slot="kw-content-last-none" className="m-0 text-sm text-muted-foreground">{t('keyword.content.notChecked')}</p>
        ) : unreadable ? (
          <p data-slot="kw-content-last-unreadable" className="m-0 text-sm text-destructive [overflow-wrap:anywhere]">
            {t('keyword.content.unreadable', cardReason(m, t)?.label || t('keyword.card.checkFailed'))}
          </p>
        ) : results ? (
          <RuleTestResults results={results} rules={rules} level={m.breach_level} sizeOk={sizeOk} monitorLevel={level}
            logic={logicOf(m)} outcome={ruleOutcomeOf(m, m, t)} title={t('keyword.rule.res.title')} />
        ) : simple ? (
          <>
            <div data-slot="kw-content-last-simple" data-result={verdict.kind} className="flex min-w-0 flex-wrap items-center gap-2">
              <ToneBadge tone={verdict.tone === 'ok' ? 'success' : verdict.tone === 'bad' ? 'danger' : 'neutral'}
                className="h-auto rounded-md overflow-visible whitespace-normal">{keywordResultText(verdict, m, t)}</ToneBadge>
              {verdict.kind !== 'other' && count != null && (
                <span data-slot="kw-content-last-count" className="text-sm text-muted-foreground tabular-nums [overflow-wrap:anywhere]">
                  {t('kwhist.countVsRule', count, expectPhrase(m, t))}
                </span>
              )}
            </div>
            {sizeOk != null && <RuleTestResults results={null} sizeOk={sizeOk} title={t('keyword.content.sizeTitle')} />}
          </>
        ) : (
          <>
            <p data-slot="kw-content-last-none" className="m-0 text-sm text-muted-foreground">{t('keyword.rule.res.none')}</p>
            {sizeOk != null && <RuleTestResults results={null} sizeOk={sizeOk} title={t('keyword.content.sizeTitle')} />}
          </>
        )}
      </section>

      <Suspense fallback={<Skeleton data-slot="kw-content-chart-loading" className="h-56 w-full motion-reduce:animate-none" />}>
        <KeywordContentChart monitorId={m.id} />
      </Suspense>

      {m.change_detect === true ? (
        <KeywordChangePanel monitorId={m.id} canEdit={canEdit} onChanged={onChanged} changeMask={m.change_mask ?? null}
          changeSelector={m.change_selector ?? null} />
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
