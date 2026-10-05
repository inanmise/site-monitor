import { useMemo, useState } from 'react'
import { CheckCircle2, AlertTriangle, AlertOctagon, GitCompare, ListTree, Server, Info, History, Waypoints, Route } from 'lucide-react'
import { formatDateSec } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'
import { buildVerdict, pathTitle, routeText } from './httpDiagnoseModel.js'
import { PathCard, PathDetail } from './HttpDiagnosePath.jsx'
import { SectionTitle } from './HttpDiagnoseParts.jsx'

const VERDICT_ICON = { success: CheckCircle2, warning: AlertTriangle, danger: AlertOctagon, muted: Info }
/** Hüküm kutusunun tonu — TÜM çerçeve + hafif zemin (sol şerit YOK, kullanıcı kararı 2026-09-26). */
const VERDICT_BOX = {
  success: 'border-success/40 bg-success/5',
  warning: 'border-amber-500/40 bg-amber-500/5',
  danger: 'border-destructive/40 bg-destructive/5',
  muted: 'border-border bg-muted/30',
}
const VERDICT_ICON_TONE = { success: 'text-success', warning: 'text-amber-600 dark:text-amber-400', danger: 'text-destructive', muted: 'text-muted-foreground' }

/**
 * Tanılama sonucu (canlı ya da kayıtlı) — okuma sırası: HÜKÜM (ne oldu, nerede takıldı) → YOLLAR (yan yana; fark
 * varsa uyarı) → AYRINTI (yol başına sekme: zamanlama, istek zinciri, istemci, döküm) → KAYNAK (pod/düğüm/IP + çıkış
 * notu). Ayrıntılar kademeli açılır; ilk ekranda karar verdirecek kadar bilgi var.
 */
export default function HttpDiagnoseResult({ data, stored = false, row = null, extra = null, pathExtra = null }) {
  const t = useT()
  const verdict = useMemo(() => buildVerdict(data, t), [data, t])
  const paths = Array.isArray(data?.paths) ? data.paths : []
  const cmp = data?.comparison?.available === true && paths.length > 1
  const [tab, setTab] = useState(() => (paths.some((p) => p.key === verdict.path) ? verdict.path : paths[0]?.key || 'monitor'))
  const by = data?.executed_by || row?.executed_by || '—'

  return (
    <div data-slot="httpdx-result" data-stored={stored ? 'true' : undefined} className="flex min-w-0 flex-col gap-5">
      {stored && (
        <AlertBanner tone="info" icon={History} className="mb-0" title={t('httpdx.stored.title', data?.run_id ?? row?.id ?? '—')}>
          {t('httpdx.stored.body', formatDateSec(data?.started_at || row?.started_at), by)}
        </AlertBanner>
      )}

      <Verdict verdict={verdict} />

      {/* Türe özgü bölüm (2026-10-04, keyword: anahtar kelime çözümlemesi) — HTTP'de yok */}
      {extra}

      <section data-slot={cmp ? 'httpdx-compare' : 'httpdx-paths'} className="flex min-w-0 flex-col gap-2.5">
        <SectionTitle icon={cmp ? GitCompare : Route}>{t(cmp ? 'httpdx.compare.title' : 'httpdx.paths.title')}</SectionTitle>
        {cmp && <CompareNote data={data} paths={paths} />}
        <div className={cn('grid min-w-0 grid-cols-1 gap-3', paths.length > 1 && 'md:grid-cols-2')}>
          {paths.map((p) => <PathCard key={p.key} path={p} data={data} extra={pathExtra ? pathExtra(p) : null} />)}
        </div>
      </section>

      {paths.length > 0 && (
        <section data-slot="httpdx-details" className="flex min-w-0 flex-col gap-2.5">
          <SectionTitle icon={ListTree}>{t('httpdx.details.title')}</SectionTitle>
          {paths.length > 1 ? (
            <Tabs value={tab} onValueChange={setTab} className="min-w-0">
              <TabsList aria-label={t('httpdx.details.tabs')}
                className="h-auto w-full flex-wrap justify-start gap-1 group-data-[orientation=horizontal]/tabs:h-auto">
                {paths.map((p) => (
                  <TabsTrigger key={p.key} value={p.key} data-path={p.key}
                    className="min-h-9 flex-none gap-1.5 px-3 py-1.5 whitespace-normal max-sm:w-full max-sm:justify-start pointer-coarse:min-h-10">
                    {p.route === 'proxy' ? <Waypoints aria-hidden="true" /> : <Route aria-hidden="true" />}
                    <span>{pathTitle(p.key, t)} · {routeText(p.route, t)}</span>
                    <span aria-hidden="true" className={cn('size-2 shrink-0 rounded-full', p.outcome === 'ok' ? 'bg-success'
                      : p.outcome === 'slow' ? 'bg-amber-500' : 'bg-destructive')} />
                  </TabsTrigger>
                ))}
              </TabsList>
              {paths.map((p) => (
                <TabsContent key={p.key} value={p.key} className="min-w-0 pt-3">
                  <PathDetail path={p} data={data} stored={stored} />
                </TabsContent>
              ))}
            </Tabs>
          ) : <PathDetail path={paths[0]} data={data} stored={stored} />}
        </section>
      )}

      <SourceFooter data={data} />
    </div>
  )
}

/** Hüküm kutusu: durum rozeti (`data-status` ok|warn|fail) + başlık + gövde + takılan adım; diğer bulgular kompakt. */
function Verdict({ verdict }) {
  const t = useT()
  const Icon = VERDICT_ICON[verdict.tone] || Info
  return (
    <section data-slot="httpdx-verdict" data-status={verdict.status} data-code={verdict.code}
      className={cn('flex min-w-0 flex-col gap-3 rounded-xl border px-4 py-3.5', VERDICT_BOX[verdict.tone] || VERDICT_BOX.muted)}>
      <div className="flex min-w-0 items-start gap-3">
        <span className={cn('mt-0.5 grid size-9 shrink-0 place-items-center rounded-lg bg-background', VERDICT_ICON_TONE[verdict.tone])}>
          <Icon aria-hidden="true" className="size-5" />
        </span>
        <div className="flex min-w-0 flex-1 flex-col gap-1.5">
          <div className="flex min-w-0 flex-wrap items-center gap-2">
            <ToneBadge tone={verdict.tone} data-slot="httpdx-verdict-badge" data-status={verdict.status} className="font-semibold">
              {t(`httpdx.status.${verdict.status}`)}
            </ToneBadge>
            <h3 data-slot="httpdx-verdict-title" className="min-w-0 text-base leading-snug font-semibold break-words">{verdict.title}</h3>
          </div>
          <p data-slot="httpdx-verdict-body" className="text-sm break-words">{verdict.body}</p>
          {(verdict.failedStep || verdict.path) && (
            <div className="flex flex-wrap gap-1.5 text-xs">
              {verdict.failedStep && (
                <ToneBadge tone="danger" data-slot="httpdx-verdict-step" data-step={verdict.failedStep}>
                  {t('httpdx.verdict.failedStep')}: {t(`httpdx.step.${verdict.failedStep}`)}
                </ToneBadge>
              )}
              {verdict.path && <ToneBadge tone="muted">{pathTitle(verdict.path, t)}</ToneBadge>}
            </div>
          )}
        </div>
      </div>
      {verdict.others.length > 0 && (
        <div className="flex min-w-0 flex-col gap-1.5 border-t pt-2.5">
          <span className="text-xs font-semibold text-muted-foreground">{t('httpdx.verdict.others')}</span>
          <ul data-slot="httpdx-findings" className="flex min-w-0 flex-col gap-2">
            {verdict.others.map((f, i) => (
              <li key={`${f.code}-${f.path}-${i}`} data-code={f.code} data-severity={f.severity} className="flex min-w-0 flex-col gap-0.5">
                <span className="flex min-w-0 flex-wrap items-center gap-1.5">
                  <ToneBadge tone={f.tone}>{t(`httpdx.sev.${f.severity}`)}</ToneBadge>
                  <span className="min-w-0 text-sm font-medium break-words">{f.title}</span>
                  {f.path && <span className="text-xs text-muted-foreground">· {pathTitle(f.path, t)}</span>}
                </span>
                <span className="text-xs break-words text-muted-foreground">{f.body}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  )
}

/** Karşılaştırma notu: iki yol farklıysa uyarı + her yolun kısa sonucu; aynıysa kısa bilgi. */
function CompareNote({ data, paths }) {
  const t = useT()
  const side = (p) => t('httpdx.compare.side', routeText(p.route, t),
    p.http_status != null ? `HTTP ${p.http_status}` : t('httpdx.path.noResponse'), p.total_ms ?? '—')
  if (!data.comparison?.differs) {
    return <p data-slot="httpdx-compare-same" className="text-xs text-muted-foreground">{t('httpdx.compare.same')}</p>
  }
  return (
    <div data-slot="httpdx-compare-diff">
      <AlertBanner tone="warning" icon={GitCompare} className="mb-0" title={t('httpdx.compare.differs')}>
        {paths.map(side).join(' · ')}
      </AlertBanner>
    </div>
  )
}

/** Kaynak: tanılamanın koştuğu pod / düğüm / pod IP + çalıştırma künyesi + çıkış (egress) notu. */
function SourceFooter({ data }) {
  const t = useT()
  const s = data?.source || {}
  const items = [
    ['pod', t('httpdx.source.pod'), s.pod],
    ['node', t('httpdx.source.node'), s.node],
    ['ip', t('httpdx.source.podIp'), s.pod_ip],
    ['ran', t('httpdx.source.ran'), data?.started_at ? formatDateSec(data.started_at) : null],
    ['run', t('httpdx.source.runId'), data?.run_id != null ? `#${data.run_id}` : null],
    ['dur', t('httpdx.source.duration'), data?.duration_ms != null ? `${data.duration_ms} ms` : null],
  ]
  return (
    <footer data-slot="httpdx-source" className="flex min-w-0 flex-col gap-2 rounded-lg border bg-muted/30 px-3 py-2.5">
      <span className="flex items-center gap-1.5 text-xs font-semibold"><Server aria-hidden="true" className="size-3.5 text-muted-foreground" />{t('httpdx.source.title')}</span>
      <dl className="grid min-w-0 grid-cols-1 gap-x-4 gap-y-1 text-xs sm:grid-cols-2 lg:grid-cols-3">
        {items.map(([k, label, value]) => (
          <div key={k} data-key={k} className="flex min-w-0 gap-1.5">
            <dt className="shrink-0 text-muted-foreground">{label}:</dt>
            <dd className="min-w-0 font-mono break-all">{value || '—'}</dd>
          </div>
        ))}
      </dl>
      <p data-slot="httpdx-egress" className="flex items-start gap-1.5 text-xs text-muted-foreground">
        <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
        <span className="min-w-0">{t('httpdx.source.egress')}</span>
      </p>
    </footer>
  )
}
