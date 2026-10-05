import { Gauge } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import HttpDiagnoseDialog from '../../http/diagnose/HttpDiagnoseDialog.jsx'
import { Kv } from '../../http/diagnose/HttpDiagnoseParts.jsx'
import { Metric } from '../../http/diagnose/HttpDiagnosePath.jsx'
import { formatBytes } from '../../http/diagnose/httpDiagnoseModel.js'
import { PAGESPEED_DIAG_PATH } from '../../page/diagnose/pageDiagCodes.js'
import PageSpeedDiagnoseAnalysis from './PageSpeedDiagnoseAnalysis.jsx'
import { pageSpeedReportExtra, thresholdChips } from './pageSpeedDiagnoseModel.js'

/**
 * SAYFA HIZI UÇTAN UCA TANILAMA penceresi (2026-10-05, kullanıcı isteği). HTTP tanılama penceresinin AYNISI — türe özgü
 * parçalar `variant` ile: Sayfa Hızı uçları, hedef şeridinde eşikler, başlangıçta "Eşikler" satırı, hükmün altında
 * "NEDEN YAVAŞ?" çözümlemesi (eşik ↔ ölçülen, yol başına fazlar, en ağır / en yavaş kaynaklar), yol kartlarında izlemenin
 * ölçtüğü toplam süre ve ağırlık, raporda ölçüm bölümü. Tembel yüklenir (PageSpeedMonitorPage `lazy`); açılışta ASLA
 * koşmaz. Derin bağlantı `psdx`.
 */

function ThresholdLine({ monitor }) {
  const t = useT()
  const chips = thresholdChips(monitor, t)
  if (!chips.length) return null
  return (
    <div data-slot="psdx-target" className="flex min-w-0 flex-wrap items-center gap-1.5 text-xs">
      {chips.map((c) => <ToneBadge key={c.key} tone="muted" data-metric={c.key}>{c.text}</ToneBadge>)}
    </div>
  )
}

function ThresholdStartRow({ monitor }) {
  const t = useT()
  const chips = thresholdChips(monitor, t)
  return (
    <Kv label={<span className="inline-flex items-center gap-1"><Gauge aria-hidden="true" className="size-3.5" />{t('psdx.start.thresholds')}</span>}>
      <span data-slot="psdx-start-thresholds" className="[overflow-wrap:anywhere]">
        {chips.length ? chips.map((c) => c.text).join(' · ') : t('psdx.start.noThresholds')}
      </span>
    </Kv>
  )
}

/** Yol kartına ek ölçü: izlemenin o yoldaki ölçümü (toplam süre + ağırlık). */
export function PathMeasured({ path }) {
  const t = useT()
  const s = path?.pagespeed
  if (!s || s.total_ms == null) return null
  const breached = Array.isArray(s.breached) && s.breached.length > 0
  return (
    <>
      <Metric label={t('psdx.path.measured')} value={`${s.total_ms} ms`} tone={breached ? 'bad' : null} slot="measured" />
      {s.total_bytes != null && <Metric label={t('psdx.path.weight')} value={formatBytes(s.total_bytes)} slot="weight" />}
    </>
  )
}

export const PAGESPEED_DIAG_VARIANT = {
  kind: 'pagespeed',
  titleKey: 'psdx.title',
  run: (id, body, opts) => api.monitoring.diagnosePageSpeed(id, body, opts),
  getRun: (id, runId) => api.monitoring.pageSpeedDiagnoseRun(id, runId),
  loadHistory: (id) => api.monitoring.pageSpeedDiagnoseHistory(id),
  pathRe: PAGESPEED_DIAG_PATH,
  fileStem: 'pagespeed-diagnose',
  reportTitleKey: 'psdx.report.title',
  reportExtra: pageSpeedReportExtra,
  startStepsKey: 'psdx.start.stepsValue',
  renderTarget: (m) => <ThresholdLine monitor={m} />,
  renderStartExtra: (m) => <ThresholdStartRow monitor={m} />,
  renderResultExtra: (data) => <PageSpeedDiagnoseAnalysis data={data} />,
  renderPathExtra: (p) => <PathMeasured path={p} />,
}

export default function PageSpeedDiagnoseDialog(props) {
  return <HttpDiagnoseDialog {...props} variant={PAGESPEED_DIAG_VARIANT} />
}
