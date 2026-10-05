import { Layers, Timer } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import HttpDiagnoseDialog from '../../http/diagnose/HttpDiagnoseDialog.jsx'
import { Kv } from '../../http/diagnose/HttpDiagnoseParts.jsx'
import PageDiagnoseAnalysis, { PathResources } from './PageDiagnoseAnalysis.jsx'
import { pageReportExtra } from './pageDiagnoseModel.js'
import { PAGE_DIAG_PATH } from './pageDiagCodes.js'

/**
 * SAYFA BÜTÜNLÜĞÜ UÇTAN UCA TANILAMA penceresi (2026-10-05, kullanıcı isteği: "tanılama ve teşhisi eksik olan … izlemeler
 * için tanılama ekleyelim … hata alındığında detaylıca ne hatası aldığını görelim"). HTTP tanılama penceresinin AYNISI
 * (başlangıç → koşu/iptal → hüküm → yollar → ayrıntı → kaynak, geçmiş, rapor, JSON, 429 şeridi, derin bağlantı) — türe özgü
 * parçalar `variant` ile: Sayfa Bütünlüğü uçları, hedef şeridinde izleme kipi, başlangıçta "Denenen" satırı, hükmün altında
 * KAYNAK ÇÖZÜMLEMESİ, yol kartlarında alarma sayılan sorun sayısı, raporda kaynak bölümü. Tembel yüklenir (PageMonitorPage
 * `lazy`); açılışta ASLA koşmaz. Derin bağlantı `pidx`.
 *
 * @param {object}   monitor        Sayfa Bütünlüğü izleme satırı (snake_case; id, url, mode, timeout_ms, proxy_effective …)
 * @param {number}   [initialRunId] derin bağlantıdan gelen kayıtlı çalıştırma
 * @param {Function} onClose
 * @param {Function} [onRunChange]  (runId|null) — gösterilen çalıştırma (sayfa URL'e `pidx` yazar)
 */

const MODE_KEY = { SINGLE_PAGE: 'pgdx.mode.SINGLE_PAGE', SITE_CRAWL: 'pgdx.mode.SITE_CRAWL' }

function PageTargetLine({ monitor }) {
  const t = useT()
  const mode = String(monitor?.mode || 'SINGLE_PAGE').toUpperCase()
  return (
    <div data-slot="pgdx-target" className="flex min-w-0 flex-wrap items-center gap-1.5 text-xs">
      <ToneBadge tone="info" data-mode={mode}><Layers aria-hidden="true" />{t(MODE_KEY[mode] || 'pgdx.mode.SINGLE_PAGE')}</ToneBadge>
      {monitor?.timeout_ms != null && (
        <ToneBadge tone="muted"><Timer aria-hidden="true" />{t('pgdx.target.timeout', monitor.timeout_ms)}</ToneBadge>
      )}
    </div>
  )
}

function PageStartRow({ monitor }) {
  const t = useT()
  const crawl = String(monitor?.mode || '').toUpperCase() === 'SITE_CRAWL'
  return (
    <Kv label={<span className="inline-flex items-center gap-1"><Layers aria-hidden="true" className="size-3.5" />{t('pgdx.start.checked')}</span>}>
      <span data-slot="pgdx-start-checked" className="[overflow-wrap:anywhere]">
        {crawl ? t('pgdx.start.checkedCrawl', monitor?.crawl_max_pages ?? 50) : t('pgdx.start.checkedSingle')}
      </span>
    </Kv>
  )
}

export const PAGE_DIAG_VARIANT = {
  kind: 'page',
  titleKey: 'pgdx.title',
  run: (id, body, opts) => api.monitoring.diagnosePage(id, body, opts),
  getRun: (id, runId) => api.monitoring.pageDiagnoseRun(id, runId),
  loadHistory: (id) => api.monitoring.pageDiagnoseHistory(id),
  pathRe: PAGE_DIAG_PATH,
  fileStem: 'page-diagnose',
  reportTitleKey: 'pgdx.report.title',
  reportExtra: pageReportExtra,
  startStepsKey: 'pgdx.start.stepsValue',
  renderTarget: (m) => <PageTargetLine monitor={m} />,
  renderStartExtra: (m) => <PageStartRow monitor={m} />,
  renderResultExtra: (data) => <PageDiagnoseAnalysis data={data} />,
  renderPathExtra: (p) => <PathResources path={p} />,
}

export default function PageDiagnoseDialog(props) {
  return <HttpDiagnoseDialog {...props} variant={PAGE_DIAG_VARIANT} />
}
