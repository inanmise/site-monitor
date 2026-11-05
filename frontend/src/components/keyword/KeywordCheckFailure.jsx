import {
  ChevronDown, CircleHelp, Zap, Wrench, Lightbulb, FileSearch, Bug, Stethoscope, ListTree, Info, FlaskConical,
} from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { CodeBlock, Kv, KvList, SectionTitle } from '../http/diagnose/HttpDiagnoseParts.jsx'
import { highlightParts } from './keywordCardModel.js'
import { detailRows, excerptHasKeyword, failureTexts, hintTexts, hintsOf, ruleOutcomeOf, suggestsSynthetic } from './keywordFailureModel.js'
import RuleTestResults, { ruleResultsOf } from './rules/RuleTestResults.jsx'
import { logicOf, rulesFromRow } from './rules/ruleModel.js'

/**
 * Keyword KONTROL GEÇMİŞİ hata teşhisi (2026-10-04, kullanıcı isteği: "kontrol geçmişinde hata olduğunda herhangi bir
 * detay bulunmuyor … kullanıcı hata aldığında sebep sonuç ile detaylıca bilgi sahibi olsun"). İki parça:
 *
 * <ul>
 *   <li>{@link KeywordFailureCell} — başarısız satırın "Ayrıntı" hücresi: neden rozeti + tek satır açıklama + aç/kapa
 *       düğmesi (dokunmatikte 40 px).</li>
 *   <li>{@link KeywordFailurePanel} — açılınca satırın ALTINDA tam genişlik: Neden / Etkisi / Ne yapmalı, kayıttaki
 *       ayrıntılar (HTTP durumu, son URL + yönlendirme, içerik türü, boyut + tavan, karakter kümesi, süre, yol), ipucu
 *       kartları, görünür metin alıntısı (anahtar kelime vurgulu, aranıp aranmadığı söylenir), teknik ayrıntı ve
 *       "Bu kontrolü tanıla" eylemi.</li>
 * </ul>
 * Eski satırlar (teşhis kolonları NULL) zarifçe çizilir: en yakın neden + "ayrıntı kaydedilmemiş — tanılamayı çalıştırın".
 * shadcn + Tailwind; sol renk şeridi YOK (durum rozet + `data-code`). Telefonda her şey alt alta, hiçbir şey yatay taşmaz.
 */

/** Başarısız satırın "Ayrıntı" hücresi. */
export function KeywordFailureCell({ check, monitor, open, onToggle, when }) {
  const t = useT()
  const f = failureTexts(check, monitor, t)
  if (!f) return null
  const label = open ? t('kwhist.hideDetails') : t('kwhist.showDetails')
  return (
    <div data-slot="kwfail-cell" data-code={f.code} data-legacy={f.legacy ? 'true' : undefined}
      className="flex min-w-0 flex-col items-start gap-1">
      <ToneBadge tone={f.tone} data-slot="kwfail-badge" data-code={f.code} className="max-w-full font-semibold whitespace-normal">
        {f.short}
      </ToneBadge>
      <span data-slot="kwfail-oneline" className="line-clamp-2 min-w-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{f.why}</span>
      <Button type="button" variant="ghost" size="xs" data-slot="kwfail-toggle" aria-expanded={open ? 'true' : 'false'}
        onClick={onToggle} aria-label={t('kwhist.toggleAria', when, f.short, label)}
        className="h-auto gap-1 px-1.5 py-0.5 text-xs font-semibold text-primary hover:text-primary pointer-coarse:min-h-10">
        {label}
        <ChevronDown aria-hidden="true" className={cn('size-3.5 transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
      </Button>
    </div>
  )
}

/** Neden / Etkisi / Ne yapmalı kutusu. */
function CauseBlock({ icon: Icon, title, slot, children }) {
  return (
    <div data-slot={slot} className="flex min-w-0 flex-col gap-1 rounded-md border bg-muted/30 px-3 py-2">
      <span className="flex items-center gap-1.5 text-[11px] font-bold tracking-[.04em] text-muted-foreground uppercase">
        <Icon aria-hidden="true" className="size-3.5 shrink-0" />{title}
      </span>
      <p className="min-w-0 text-[13px] leading-snug [overflow-wrap:anywhere]">{children}</p>
    </div>
  )
}

const KV_LABEL = {
  status: 'kwhist.kv.status', count: 'kwhist.kv.count', finalUrl: 'kwhist.kv.finalUrl', contentType: 'kwhist.kv.contentType',
  size: 'kwhist.kv.size', charset: 'kwhist.kv.charset', responseMs: 'kwhist.kv.responseMs', route: 'kwhist.kv.route',
}
const KV_TONE = { bad: 'text-destructive', warn: 'text-amber-700 dark:text-amber-300' }

/**
 * Açılan teşhis paneli (satırın altında tam genişlik).
 *
 * @param {object}   check        kontrol satırı (snake_case, GET /monitoring/keyword/{id}/history items)
 * @param {object}   monitor      izleme satırı (keyword, operator, match_count, case_sensitive, url, timeout_ms)
 * @param {boolean}  canDiagnose  `can_diagnose` — false ise tanılama düğmesi hiç çizilmez
 * @param {Function} onDiagnose   uçtan uca tanılama penceresini aç
 * @param {Function} onConvert    (kw F3) "Sentetik izleme ile kur" — yalnız düzenleme yetkisinde verilir; ipucu giriş sayfası
 *                                ya da JavaScript ile çizilen içerik diyorsa düğme çizilir
 */
export function KeywordFailurePanel({ check, monitor, canDiagnose = false, onDiagnose, onConvert, showRules = true }) {
  const t = useT()
  const f = failureTexts(check, monitor, t)
  if (!f) return null
  const hints = hintsOf(check)
  const rows = detailRows(check, monitor, t)
  const technical = [check.failure_detail, check.error && check.error !== check.failure_detail ? check.error : null].filter(Boolean).join('\n')
  return (
    <section data-slot="kwfail-panel" data-code={f.code} aria-label={t('kwhist.panelAria', f.short)}
      className="flex min-w-0 flex-col gap-3 rounded-lg border bg-card p-3 text-xs sm:p-4">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <ToneBadge tone={f.tone} className="max-w-full font-semibold whitespace-normal">{f.short}</ToneBadge>
        <h4 className="min-w-0 text-[13px] font-semibold">{t('kwhist.panelTitle')}</h4>
      </div>

      {f.legacy && (
        <div data-slot="kwfail-legacy">
          <AlertBanner tone="info" icon={Info} className="mb-0">{t('kwhist.legacy')}</AlertBanner>
        </div>
      )}

      <div className="grid min-w-0 grid-cols-1 gap-2 md:grid-cols-3">
        <CauseBlock icon={CircleHelp} title={t('kwhist.why')} slot="kwfail-why">{f.why}</CauseBlock>
        <CauseBlock icon={Zap} title={t('kwhist.effect')} slot="kwfail-effect">{f.effect}</CauseBlock>
        <CauseBlock icon={Wrench} title={t('kwhist.fix')} slot="kwfail-fix">{f.fix}</CauseBlock>
      </div>

      <div data-slot="kwfail-details" className="flex min-w-0 flex-col gap-1.5">
        <SectionTitle icon={ListTree}>{t('kwhist.detailsTitle')}</SectionTitle>
        <KvList>
          {rows.map((r) => (
            <Kv key={r.key} label={t(KV_LABEL[r.key])} mono={!!r.mono}>
              <span data-key={r.key} className={cn('min-w-0', KV_TONE[r.tone])}>{r.value}</span>
              {r.sub && <span className="ml-1.5 text-muted-foreground">· {r.sub}</span>}
            </Kv>
          ))}
        </KvList>
      </div>

      {/* Form testi kural listesini zaten üstte çiziyor (showRules=false) — aynı liste iki kez çizilmez (DÜŞÜK-2) */}
      {showRules && ruleResultsOf(check.rule_results) && (
        <div data-slot="kwfail-rules" className="min-w-0">
          <RuleTestResults results={check.rule_results} rules={rulesFromRow(monitor)} level={check.breach_level}
            monitorLevel={monitor?.alert_level || 'WARNING'} logic={logicOf(monitor)} outcome={ruleOutcomeOf(check, monitor, t)} />
        </div>
      )}

      {hints.length > 0 && (
        <div className="flex min-w-0 flex-col gap-1.5">
          <SectionTitle icon={Lightbulb}>{t('kwhist.hintsTitle')}</SectionTitle>
          <ul data-slot="kwfail-hints" className="grid min-w-0 grid-cols-1 gap-2 md:grid-cols-2">
            {hints.map((code) => <HintCard key={code} code={code} check={check} monitor={monitor} />)}
          </ul>
        </div>
      )}

      {check.excerpt && <Excerpt check={check} monitor={monitor} />}

      {technical && (
        <div data-slot="kwfail-technical" className="flex min-w-0 flex-col gap-1.5">
          <SectionTitle icon={Bug}>{t('kwhist.technical')}</SectionTitle>
          <CodeBlock maxH="max-h-40" copy={technical} copyLabel={t('kwhist.copyTechnical')}>{technical}</CodeBlock>
        </div>
      )}

      {onConvert && suggestsSynthetic(check) && (
        <div data-slot="kwfail-synthetic" className="flex min-w-0 flex-col gap-1.5 rounded-md border border-primary/30 bg-primary/5 p-3 sm:flex-row sm:items-center sm:justify-between">
          <span className="min-w-0 text-xs [overflow-wrap:anywhere]">{t('keyword.k6.f3Hint')}</span>
          <Button type="button" size="sm" variant="outline" data-slot="kwfail-convert" onClick={onConvert} className="shrink-0 pointer-coarse:h-10">
            <FlaskConical aria-hidden="true" /> {t('keyword.k6.setupWithSynthetic')}
          </Button>
        </div>
      )}

      {canDiagnose && onDiagnose && (
        <div className="flex min-w-0 flex-col gap-1.5 border-t pt-3 sm:flex-row sm:items-center sm:justify-between">
          <span className="min-w-0 text-xs text-muted-foreground">{t('kwhist.diagnoseHint')}</span>
          <Button type="button" size="sm" data-slot="kwfail-diagnose" onClick={onDiagnose} className="shrink-0 pointer-coarse:h-10">
            <Stethoscope aria-hidden="true" /> {t('kwhist.diagnose')}
          </Button>
        </div>
      )}
    </section>
  )
}

/** İpucu kartı — başlık + neden → etkisi → ne yapmalı (dokunmatikte de okunur; yalnız-hover yok). */
export function HintCard({ code, check, monitor }) {
  const t = useT()
  const h = hintTexts(code, check, monitor, t)
  return (
    <li data-slot="kwfail-hint" data-code={code} className="flex min-w-0 flex-col gap-1.5 rounded-md border border-amber-500/30 bg-amber-500/5 px-3 py-2">
      <span className="flex min-w-0 items-start gap-1.5 text-[13px] font-semibold">
        <Lightbulb aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 text-amber-600 dark:text-amber-400" />
        <span className="min-w-0 [overflow-wrap:anywhere]">{h.title}</span>
      </span>
      <dl className="flex min-w-0 flex-col gap-1">
        <div className="min-w-0"><dt className="inline font-semibold">{t('kwhist.why')}: </dt><dd className="inline [overflow-wrap:anywhere]">{h.cause}</dd></div>
        <div className="min-w-0"><dt className="inline font-semibold">{t('kwhist.effect')}: </dt><dd className="inline [overflow-wrap:anywhere]">{h.effect}</dd></div>
        <div className="min-w-0"><dt className="inline font-semibold">{t('kwhist.fix')}: </dt><dd className="inline [overflow-wrap:anywhere]">{h.fix}</dd></div>
      </dl>
    </li>
  )
}

/** Görünür metin alıntısı — anahtar kelime vurgulu; bu alıntıda geçip geçmediği açıkça yazılır. */
function Excerpt({ check, monitor }) {
  const t = useT()
  const cs = !!monitor?.case_sensitive
  // Kural #1 düzenli ifadeyse desen metin gibi aranıp vurgulanmaz (yanlış "geçmiyor" demesin) — kw paket C
  const regex = rulesFromRow(monitor)[0]?.kind === 'REGEX'
  const kw = regex ? '' : monitor?.keyword
  const has = excerptHasKeyword(check.excerpt, kw, cs)
  const parts = highlightParts(check.excerpt, kw, cs)
  return (
    <div data-slot="kwfail-excerpt" data-has-keyword={has == null ? '' : String(has)} className="flex min-w-0 flex-col gap-1.5">
      <SectionTitle icon={FileSearch}>{t('kwhist.excerptTitle')}</SectionTitle>
      {has != null && (
        <p className="text-xs text-muted-foreground">
          {has ? t('kwhist.excerptHas', monitor?.keyword) : t('kwhist.excerptMissing', monitor?.keyword ?? '')}
        </p>
      )}
      <pre className="max-h-48 min-w-0 overflow-y-auto rounded-md border bg-muted/50 px-3 py-2 font-sans text-xs leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere]">
        {parts.map((p, i) => (p.match
          ? <mark key={i} data-slot="kwfail-mark" className="rounded-sm bg-amber-300/60 px-0.5 text-foreground dark:bg-amber-400/30">{p.text}</mark>
          : <span key={i}>{p.text}</span>))}
      </pre>
      <p className="text-[11px] text-muted-foreground">{t('kwhist.excerptNote')}</p>
    </div>
  )
}
