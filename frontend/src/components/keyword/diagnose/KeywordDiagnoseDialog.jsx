import { Search, CaseSensitive } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import HttpDiagnoseDialog from '../../http/diagnose/HttpDiagnoseDialog.jsx'
import { Kv } from '../../http/diagnose/HttpDiagnoseParts.jsx'
import KeywordDiagnoseAnalysis, { PathOccurrences } from './KeywordDiagnoseAnalysis.jsx'
import { keywordReportExtra, rulePhrase } from './keywordDiagnoseModel.js'
import { KEYWORD_DIAG_PATH } from './keywordDiagCodes.js'

/**
 * KEYWORD UÇTAN UCA TANILAMA penceresi (2026-10-04, kullanıcı isteği: "http/website izlemedeki gibi keyword izlemeye de
 * tanılama alanı"). HTTP tanılama penceresinin AYNISI (başlangıç → koşu/iptal → hüküm → yollar → ayrıntı → kaynak,
 * geçmiş, rapor, JSON, 429 şeridi, derin bağlantı) — türe özgü parçalar `variant` ile: keyword uçları, hedef şeridinde
 * aranan metin + kural, başlangıçta "Aranan" satırı, hükmün altında ANAHTAR KELİME ÇÖZÜMLEMESİ, yol kartlarında bulunan
 * adet, raporda keyword bölümü. Tembel yüklenir (KeywordMonitorPage `lazy`); açılışta ASLA koşmaz. Derin bağlantı `kdx`.
 *
 * @param {object}   monitor        keyword izleme satırı (snake_case; id, url, keyword, operator, match_count, timeout_ms …)
 * @param {number}   [initialRunId] derin bağlantıdan gelen kayıtlı çalıştırma
 * @param {Function} onClose
 * @param {Function} [onRunChange]  (runId|null) — gösterilen çalıştırma (sayfa URL'e `kdx` yazar)
 */

function KeywordTargetLine({ monitor }) {
  const t = useT()
  const m = monitor || {}
  if (!m.keyword) return null
  return (
    <div data-slot="kwdx-target" className="flex min-w-0 flex-wrap items-center gap-1.5 text-xs">
      <Search aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground" />
      <code className="min-w-0 rounded border bg-muted/40 px-1.5 font-mono text-[12px] whitespace-pre-wrap [overflow-wrap:anywhere]">« {m.keyword} »</code>
      <ToneBadge tone="info">{rulePhrase(m, t)}</ToneBadge>
      {m.case_sensitive && <ToneBadge tone="muted"><CaseSensitive aria-hidden="true" />{t('kwdx.analysis.caseSensitive')}</ToneBadge>}
    </div>
  )
}

function KeywordStartRow({ monitor }) {
  const t = useT()
  return (
    <Kv label={<span className="inline-flex items-center gap-1"><Search aria-hidden="true" className="size-3.5" />{t('kwdx.start.searched')}</span>}>
      <span className="[overflow-wrap:anywhere]">« {monitor?.keyword} » · {rulePhrase(monitor, t)}</span>
    </Kv>
  )
}

export const KEYWORD_DIAG_VARIANT = {
  kind: 'keyword',
  titleKey: 'kwdx.title',
  run: (id, body, opts) => api.monitoring.diagnoseKeyword(id, body, opts),
  getRun: (id, runId) => api.monitoring.keywordDiagnoseRun(id, runId),
  loadHistory: (id) => api.monitoring.keywordDiagnoseHistory(id),
  pathRe: KEYWORD_DIAG_PATH,
  fileStem: 'keyword-diagnose',
  reportTitleKey: 'kwdx.report.title',
  reportExtra: keywordReportExtra,
  startStepsKey: 'kwdx.start.stepsValue',
  renderTarget: (m) => <KeywordTargetLine monitor={m} />,
  renderStartExtra: (m) => <KeywordStartRow monitor={m} />,
  renderResultExtra: (data, stored) => <KeywordDiagnoseAnalysis data={data} stored={stored} />,
  renderPathExtra: (p) => <PathOccurrences path={p} />,
}

export default function KeywordDiagnoseDialog(props) {
  return <HttpDiagnoseDialog {...props} variant={KEYWORD_DIAG_VARIANT} />
}
