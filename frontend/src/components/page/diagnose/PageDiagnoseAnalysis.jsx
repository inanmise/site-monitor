import { FileSearch, ListChecks, ShieldAlert, Info } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import ToneBadge from '../../admin/ToneBadge.jsx'
import { Metric } from '../../http/diagnose/HttpDiagnosePath.jsx'
import { Kv, KvList, SectionTitle } from '../../http/diagnose/HttpDiagnoseParts.jsx'
import { formatBytes } from '../../http/diagnose/httpDiagnoseModel.js'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import {
  ISSUE_TONE, STATUS_KEY, counterRows, issueLabelKey, issueMeta, issueRows, recordedTone, ruleRows,
} from './pageDiagnoseModel.js'

/** Kap bu genişlikten darsa sorunlar kart olarak çizilir (jsdom = 0 → tablo). */
export const ISSUE_TABLE_MIN = 640

/**
 * Sayfa Bütünlüğü uçtan uca tanılamasının KAYNAK ÇÖZÜMLEMESİ bölümü (2026-10-05) — hükmün hemen altında: izlemenin bu
 * sonucu nasıl KAYDEDECEĞİ (Sağlıklı / Bozulmuş / Erişilemiyor), sayaçlar (kaynak, kırık, zaman aşımı, mixed, belirsiz,
 * yavaş, alarma sayılan), sayfanın kendisi (HTTP, süre, boyut), kimin sorunu (kendi siteniz ↔ başka siteler), izlemenin
 * alarm kuralları ve en çok 20 sorunlu kaynak (alarma sayılan önce). Sorun listesi KAP genişliğine göre tablo ↔ kart.
 * Yalnız izlemenin yolu çözümlenir; öteki yolun özeti yol kartında.
 */
export default function PageDiagnoseAnalysis({ data }) {
  const t = useT()
  const p = data?.page
  if (!p) return null
  const tone = recordedTone(p)
  const tt = p.totals || {}
  return (
    <section data-slot="pgdx-analysis" data-status={p.recorded_status || ''} className="flex min-w-0 flex-col gap-4 rounded-xl border bg-card px-4 py-3.5">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
        <SectionTitle icon={FileSearch}>{t('pgdx.analysis.title')}</SectionTitle>
        {p.recorded_status && (
          <ToneBadge tone={tone} data-slot="pgdx-recorded" data-status={p.recorded_status} className="font-semibold">
            {t('pgdx.analysis.recorded')}: {t(STATUS_KEY[p.recorded_status] || 'page.statusUnknown')}
          </ToneBadge>
        )}
      </div>

      {p.analyzed === false ? (
        <p data-slot="pgdx-unanalyzed" className="text-sm text-muted-foreground">{t('pgdx.analysis.unanalyzed')}</p>
      ) : (
        <>
          <div data-slot="pgdx-counters" className="flex flex-wrap gap-x-6 gap-y-2">
            {counterRows(p).map((c) => (
              <Metric key={c.key} label={t(`pgdx.count.${c.key}`)} value={c.value ?? '—'} tone={c.bad ? 'bad' : null} slot={c.key} />
            ))}
          </div>

          <KvList>
            <Kv label={t('pgdx.analysis.page')}>
              <span className="tabular-nums">{[
                p.http_status != null ? `HTTP ${p.http_status}` : null,
                p.response_ms != null ? `${p.response_ms} ms` : null,
                p.body_bytes != null ? formatBytes(p.body_bytes) : null,
              ].filter(Boolean).join(' · ') || '—'}</span>
            </Kv>
            <Kv label={t('pgdx.analysis.parties')}>
              <span data-slot="pgdx-parties">{t('pgdx.analysis.partiesValue', tt.first_party ?? 0, tt.third_party ?? 0)}</span>
            </Kv>
            <Kv label={t('pgdx.analysis.rules')}>
              <span data-slot="pgdx-rules" className="flex min-w-0 flex-wrap gap-1">
                {ruleRows(p).map((r) => (
                  <ToneBadge key={r.key} tone={r.on ? 'info' : 'muted'} data-rule={r.key} data-on={r.on ? 'true' : 'false'}>
                    {t(`pgdx.rule.${r.key}`)}: {t(r.on ? 'pgdx.rule.on' : 'pgdx.rule.off')}
                  </ToneBadge>
                ))}
                <ToneBadge tone="muted">{t('pgdx.rule.slow', p.toggles?.slow_resource_ms ?? '—')}</ToneBadge>
              </span>
            </Kv>
          </KvList>

          {(p.monitor_mode === 'SITE_CRAWL' || p.limits?.resource_cap_hit || p.limits?.time_budget_hit) && (
            <ul data-slot="pgdx-notes" className="flex min-w-0 flex-col gap-1 text-xs text-muted-foreground">
              {p.monitor_mode === 'SITE_CRAWL' && (
                <li className="flex items-start gap-1.5"><Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
                  <span className="min-w-0">{t('pgdx.analysis.modeCrawl', p.crawl_max_pages ?? 50)}</span></li>
              )}
              {p.limits?.resource_cap_hit && (
                <li className="flex items-start gap-1.5"><Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
                  <span className="min-w-0">{t('pgdx.analysis.limitResources', p.limits.resource_cap)}</span></li>
              )}
              {p.limits?.time_budget_hit && (
                <li className="flex items-start gap-1.5"><Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
                  <span className="min-w-0">{t('pgdx.analysis.limitTime', p.limits.time_budget_s)}</span></li>
              )}
            </ul>
          )}

          <IssueList page={p} />
        </>
      )}
    </section>
  )
}

/** Sorunlu kaynaklar — KAP ≥ {@link ISSUE_TABLE_MIN} px tablo, altında kart. */
function IssueList({ page }) {
  const t = useT()
  const rows = issueRows(page)
  const [measureRef, width] = useElementWidth()
  const table = width === 0 || width >= ISSUE_TABLE_MIN
  const total = page.issues_total ?? rows.length
  return (
    <div ref={measureRef} data-slot="pgdx-issues" data-view={table ? 'table' : 'cards'} className="flex min-w-0 flex-col gap-2">
      <SectionTitle icon={ListChecks}>
        {t('pgdx.analysis.issues')} <span className="font-normal text-muted-foreground">· {total}</span>
      </SectionTitle>
      {!rows.length ? (
        <p data-slot="pgdx-no-issues" className="text-xs text-muted-foreground">{t('pgdx.analysis.noIssues')}</p>
      ) : table ? <IssueTable rows={rows} /> : (
        <ul className="flex min-w-0 flex-col gap-2">{rows.map((r) => <IssueCard key={r.i} row={r} />)}</ul>
      )}
      {total > rows.length && (
        <p data-slot="pgdx-issues-more" className="text-xs text-muted-foreground">{t('pgdx.analysis.issuesShown', total, rows.length)}</p>
      )}
    </div>
  )
}

function KindBadge({ row }) {
  const t = useT()
  const key = issueLabelKey(row.kind)
  return <ToneBadge tone={ISSUE_TONE[row.kind] || 'muted'} data-kind={row.kind || ''}>{key ? t(key) : (row.kind || '—')}</ToneBadge>
}

function AlarmBadge({ row }) {
  const t = useT()
  return row.alarm
    ? <ToneBadge tone="danger" data-slot="pgdx-alarm"><ShieldAlert aria-hidden="true" />{t('pgdx.alarm.yes')}</ToneBadge>
    : <span className="text-muted-foreground">{t('pgdx.alarm.no')}</span>
}

function UrlCell({ row }) {
  const t = useT()
  return (
    <span className="flex min-w-0 flex-col gap-0.5">
      <span data-slot="pgdx-issue-url" className="min-w-0 font-mono break-all">{row.url || '—'}</span>
      {row.sourcePage && (
        <span className="min-w-0 text-[11px] break-all text-muted-foreground">{t('pgdx.issue.sourcePage')}: {row.sourcePage}</span>
      )}
    </span>
  )
}

const CELL = 'whitespace-normal align-top text-xs'

function IssueTable({ rows }) {
  const t = useT()
  return (
    <div className="min-w-0 overflow-hidden rounded-md border">
      <Table className="table-fixed text-xs">
        <TableHeader className="bg-muted/50">
          <TableRow>
            <TableHead className="w-[15%] px-2.5 text-xs">{t('pgdx.col.kind')}</TableHead>
            <TableHead className="px-2.5 text-xs">{t('pgdx.col.resource')}</TableHead>
            <TableHead className="w-[8%] px-2.5 text-xs">{t('pgdx.col.type')}</TableHead>
            <TableHead className="w-[8%] px-2.5 text-xs">HTTP</TableHead>
            <TableHead className="w-[10%] px-2.5 text-xs">{t('pgdx.col.ms')}</TableHead>
            <TableHead className="w-[10%] px-2.5 text-xs">{t('pgdx.col.party')}</TableHead>
            <TableHead className="w-[16%] px-2.5 text-xs">{t('pgdx.col.alarm')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((r) => (
            <TableRow key={r.i} data-slot="pgdx-issue" data-kind={r.kind || ''} data-alarm={r.alarm ? 'true' : 'false'}>
              <TableCell className={CELL}><KindBadge row={r} /></TableCell>
              <TableCell className={CELL}><UrlCell row={r} /></TableCell>
              <TableCell className={cn(CELL, 'font-mono')}>{r.type || '—'}</TableCell>
              <TableCell className={cn(CELL, 'tabular-nums')}>{r.status ?? '—'}</TableCell>
              <TableCell className={cn(CELL, 'tabular-nums')}>{r.ms != null ? `${r.ms} ms` : '—'}</TableCell>
              <TableCell className={CELL}>{t(r.firstParty ? 'pgdx.party.first' : 'pgdx.party.third')}</TableCell>
              <TableCell className={CELL}><AlarmBadge row={r} /></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

function IssueCard({ row }) {
  const t = useT()
  return (
    <li data-slot="pgdx-issue" data-kind={row.kind || ''} data-alarm={row.alarm ? 'true' : 'false'}
      className="flex min-w-0 flex-col gap-1.5 rounded-md border bg-card px-3 py-2.5 text-xs">
      <div className="flex min-w-0 flex-wrap items-center gap-1.5">
        <KindBadge row={row} />
        {row.alarm && <AlarmBadge row={row} />}
      </div>
      <UrlCell row={row} />
      <span className="text-muted-foreground tabular-nums">{issueMeta(row, t)}</span>
    </li>
  )
}

/** Yol kartına ek ölçü: o yolda alarma sayılan sorun sayısı (karşılaştırma için). */
export function PathResources({ path }) {
  const t = useT()
  const s = path?.page
  if (!s || s.alarm == null) return null
  return <Metric label={t('pgdx.path.alarm')} value={s.alarm} tone={s.alarm > 0 ? 'bad' : null} slot="alarm" />
}
