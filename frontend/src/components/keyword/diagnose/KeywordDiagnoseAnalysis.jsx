import { Search, Lightbulb, Quote, Layers, FileText, CaseSensitive, Ban } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import { Metric } from '../../http/diagnose/HttpDiagnosePath.jsx'
import { CodeBlock, Kv, KvList, SectionTitle } from '../../http/diagnose/HttpDiagnoseParts.jsx'
import { formatBytes } from '../../http/diagnose/httpDiagnoseModel.js'
import { cn } from '@/lib/utils'
import { HintCard } from '../KeywordCheckFailure.jsx'
import { alternativeRows, analysisHints, pseudoCheck, rulePhrase } from './keywordDiagnoseModel.js'

/**
 * Keyword uçtan uca tanılamasının ANAHTAR KELİME ÇÖZÜMLEMESİ bölümü (2026-10-04) — hükmün hemen altında: kural ve
 * sonuç (bulunan adet ↔ koşul), yanıt meta verisi (durum, son URL + yönlendirme, içerik türü, karakter kümesi, boyut ↔
 * izlemenin 2 MB okuma tavanı), alternatif okumalar (harf duyarsız / normalleştirilmiş / başka karakter kümesi / yalnız
 * görünür metin — izlemenin kaçırdığı eşleşmeyi bulan satır vurgulu), "neden bulunamadı" ipucu kartları, en çok 5
 * eşleşme bağlamı (vurgulu) ve görünür metin önizlemesi (geçmişe KAYDEDİLMEZ). Yalnız izlemenin yolu çözümlenir.
 */
export default function KeywordDiagnoseAnalysis({ data, stored = false }) {
  const t = useT()
  const kw = data?.keyword
  if (!kw) return null
  const met = kw.condition_met
  const hints = analysisHints(kw)
  const alts = alternativeRows(kw, t)
  const contexts = Array.isArray(kw.contexts) ? kw.contexts : []
  const monitor = { ...(data?.monitor || {}), keyword: kw.keyword, operator: kw.operator, match_count: kw.match_count, case_sensitive: kw.case_sensitive }
  return (
    <section data-slot="kwdx-analysis" data-met={met == null ? '' : String(met)} className="flex min-w-0 flex-col gap-4 rounded-xl border bg-card px-4 py-3.5">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
        <SectionTitle icon={Search}>{t('kwdx.analysis.title')}</SectionTitle>
        {met != null && (
          <ToneBadge tone={met ? 'success' : 'danger'} data-slot="kwdx-met" className="font-semibold">
            {t(met ? 'kwdx.analysis.conditionMet' : 'kwdx.analysis.conditionFailed')}
          </ToneBadge>
        )}
      </div>

      <div className="flex min-w-0 flex-col gap-1.5">
        <code data-slot="kwdx-keyword" className="w-fit max-w-full rounded-md border bg-muted/40 px-2 py-1 font-mono text-[13px] whitespace-pre-wrap [overflow-wrap:anywhere]">
          « {kw.keyword} »
        </code>
        <div className="flex flex-wrap gap-1.5">
          <ToneBadge tone="info">{rulePhrase(kw, t)}</ToneBadge>
          {kw.case_sensitive && <ToneBadge tone="muted"><CaseSensitive aria-hidden="true" />{t('kwdx.analysis.caseSensitive')}</ToneBadge>}
          {kw.absence_rule && <ToneBadge tone="muted"><Ban aria-hidden="true" />{t('kwdx.analysis.absenceRule')}</ToneBadge>}
        </div>
      </div>

      {kw.analyzed === false ? (
        <p data-slot="kwdx-unanalyzed" className="text-sm text-muted-foreground">{t('kwdx.analysis.unanalyzed')}</p>
      ) : (
        <>
          <div className="flex flex-wrap gap-x-6 gap-y-2">
            <Metric label={t('kwdx.analysis.found')} value={kw.occurrences ?? '—'} tone={met === false ? 'bad' : null} slot="found" />
            <Metric label={t('kwdx.analysis.status')} value={kw.http_status ?? '—'} tone={kw.http_status >= 400 ? 'bad' : null} slot="status" />
            <Metric label={t('kwdx.analysis.size')} value={formatBytes(kw.body_bytes)} tone={kw.body_truncated ? 'bad' : null} slot="size" />
          </div>

          <KvList>
            {kw.final_url && (
              <Kv label={t('kwdx.analysis.finalUrl')} mono>
                <span>{kw.final_url}</span>
                {kw.redirect_count > 0 && <span className="ml-1.5 font-sans text-muted-foreground">· {t('kwhist.redirects', kw.redirect_count)}</span>}
              </Kv>
            )}
            <Kv label={t('kwdx.analysis.contentType')} mono>{kw.content_type || '—'}</Kv>
            {kw.text_like === false && <Kv label={t('kwdx.analysis.textLike')}><span className="text-destructive">{t('kwdx.analysis.notText')}</span></Kv>}
            <Kv label={t('kwdx.analysis.charset')} mono>{kw.charset || t('kwdx.analysis.charsetNone')}</Kv>
            <Kv label={t('kwdx.analysis.cap')}>
              <span data-slot="kwdx-cap" data-truncated={kw.body_truncated ? 'true' : 'false'} className={cn(kw.body_truncated && 'font-semibold text-destructive')}>
                {kw.body_truncated ? t('kwdx.analysis.capHit', formatBytes(kw.checker_cap_bytes)) : t('kwdx.analysis.capOk', formatBytes(kw.body_bytes), formatBytes(kw.checker_cap_bytes))}
              </span>
            </Kv>
          </KvList>

          {alts.length > 0 && (
            <div className="flex min-w-0 flex-col gap-1.5">
              <SectionTitle icon={Layers}>{t('kwdx.analysis.alternatives')}</SectionTitle>
              <ul data-slot="kwdx-alts" className="flex min-w-0 flex-col divide-y overflow-hidden rounded-md border text-xs">
                {alts.map((r) => (
                  <li key={r.key} data-alt={r.key} data-better={r.better ? 'true' : undefined}
                    className={cn('flex min-w-0 items-center justify-between gap-3 px-2.5 py-1.5', r.better && 'bg-amber-500/10 font-semibold')}>
                    <span className="min-w-0 [overflow-wrap:anywhere]">{r.label}</span>
                    <span className="shrink-0 tabular-nums">{r.count}</span>
                  </li>
                ))}
              </ul>
              <p className="text-xs text-muted-foreground">{t('kwdx.analysis.alternativesHint')}</p>
            </div>
          )}

          {hints.length > 0 && (
            <div className="flex min-w-0 flex-col gap-1.5">
              <SectionTitle icon={Lightbulb}>{t('kwhist.hintsTitle')}</SectionTitle>
              <ul data-slot="kwdx-hints" className="grid min-w-0 grid-cols-1 gap-2 md:grid-cols-2">
                {hints.map((code) => <HintCard key={code} code={code} check={pseudoCheck(kw)} monitor={monitor} />)}
              </ul>
            </div>
          )}

          <div className="flex min-w-0 flex-col gap-1.5">
            <SectionTitle icon={Quote}>{t('kwdx.analysis.contexts')}</SectionTitle>
            {contexts.length > 0 ? (
              <ol data-slot="kwdx-contexts" className="flex min-w-0 flex-col gap-1.5">
                {contexts.map((c, i) => (
                  <li key={i} data-source={c.source || ''} className="min-w-0 rounded-md border bg-muted/40 px-2.5 py-1.5 font-mono text-xs [overflow-wrap:anywhere]">
                    <span className="text-muted-foreground">{c.before}</span>
                    <mark className="rounded-sm bg-amber-300/60 px-0.5 text-foreground dark:bg-amber-400/30">{c.match}</mark>
                    <span className="text-muted-foreground">{c.after}</span>
                    {c.source === 'raw' && <span className="ml-1.5 font-sans text-[11px] text-amber-700 dark:text-amber-300">({t('kwdx.analysis.rawSource')})</span>}
                  </li>
                ))}
              </ol>
            ) : (
              <p data-slot="kwdx-no-contexts" className="text-xs text-muted-foreground">
                {stored && kw.preview_stored === false ? t('kwdx.analysis.contextsNotStored') : t('kwdx.analysis.noContexts')}
              </p>
            )}
          </div>

          <div className="flex min-w-0 flex-col gap-1.5">
            <SectionTitle icon={FileText}>{t('kwdx.analysis.visible')}</SectionTitle>
            {kw.visible_text_preview ? (
              <>
                <CodeBlock maxH="max-h-64" data-slot="kwdx-visible" copy={kw.visible_text_preview} copyLabel={t('kwdx.analysis.copyVisible')}>
                  {kw.visible_text_preview}
                </CodeBlock>
                {kw.visible_text_truncated && <p className="text-xs text-muted-foreground">{t('kwdx.analysis.visibleTruncated')}</p>}
              </>
            ) : (
              <p data-slot="kwdx-visible-note" className="text-xs text-muted-foreground">
                {stored ? t('kwdx.analysis.visibleNotStored') : t('kwdx.analysis.visibleNone')}
              </p>
            )}
          </div>
        </>
      )}
    </section>
  )
}

/** Yol kartına ek ölçü: o yolda bulunan adet (koşul sağlanmadıysa kırmızı). */
export function PathOccurrences({ path }) {
  const t = useT()
  const k = path?.keyword
  if (!k || k.occurrences == null) return null
  return <Metric label={t('kwdx.path.occurrences')} value={k.occurrences} tone={k.condition_met === false ? 'bad' : null} slot="occurrences" />
}
