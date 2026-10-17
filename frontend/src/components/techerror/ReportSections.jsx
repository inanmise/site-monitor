import { Fragment, createContext, useContext, useEffect, useRef, useState } from 'react'
import { ArrowDown, ArrowUp, ChevronDown, CircleDashed, PenLine, Plus, Trash2, TriangleAlert, Wand2 } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Input } from '@/components/shadcn/input'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'
import {
  CALC_MANUAL, LIMITS, MAX_RESOLVED_TEXT, changePercent, derivedRates, fieldExempt, formatNumber, formatPercent, formatValue,
  hasAbbreviation, isAmbiguousB, isIncompleteNumber,
  issueDisplayTitle, move, newIssue, parseNumber, resolveIssueTitle, summaryAutoText, summaryLinePreview, summaryLineStatus,
  resolveTextPlaceholders, richBlank, tableCellKey, tableMinWidth,
} from './techErrorModel.js'
import { RichText, RichTextarea } from './RichText.jsx'
import { periodLabel, CopyTextButton } from './TechErrorParts.jsx'
import { CollapsibleCard } from './SectionCollapse.jsx'

/**
 * Sayı alanının ekrandaki metni: kayıtlı sayı arayüz dilinin biçiminde (çözümleyici aynı biçimi geri okur). Yüzde
 * GRUPLAMASIZ yazılır: yüzde alanında tek ayraç ondalıktır, "%1.000" geri okununca 1 olurdu (2. inceleme turu).
 */
function numberText(v, lang, percent = false) {
  if (v == null || v === '') return ''
  const n = Number(v)
  if (!Number.isFinite(n)) return String(v)
  return new Intl.NumberFormat(lang === 'en' ? 'en-US' : 'tr-TR', { maximumFractionDigits: 6, useGrouping: !percent }).format(n)
}

/**
 * Sayı / yüzde girişi: kullanıcı yazarken metin olduğu gibi kalır; geçerliyse sayı üst duruma yazılır, geçersizse
 * alanın altında hata ("1.250.000", "0,42" ve "%3,5" kabul). Dış değer başka yerden değişirse (ör. sunucudan gelen
 * kayıt) metin yenilenir. Sayı alanında KISALTMA (2026-10-16, kullanıcı kararı): "7,42G", "130,1M", "681,6k" yazıldığı
 * gibi kalır, altında anlaşılan tam sayı ("= 7.420.000.000") — yanlış okunan kısaltma her an görülür; tek başına "B"
 * reddedilir (bin mi milyar mı belli değil).
 */
/**
 * Yarım yazım bildirimi (2026-10-16, otomatik kayıt incelemesi): "7,42mi", "1.250.0", "-" gibi tamamlanmamış metin taslağa
 * girmez (son geçerli değer kalır). Düzenleyici bunu bilmezse otomatik kayıt eski ara değeri kaydedip "tümü kaydedildi"
 * derdi. `pendingKey` verilen giriş durumunu buraya söyler; söküldüğünde (yeniden kurulum, satır silme) temizler.
 */
export const PendingInputContext = createContext(null)

export function NumberInput({ id, value, lang, percent = false, negative = false, onChange, onError, describedBy, invalid, disabled, ariaLabel, compact = false, pendingKey }) {
  const t = useT()
  const [text, setText] = useState(() => numberText(value, lang, percent))
  const reportPending = useContext(PendingInputContext)
  const pendingRef = useRef({ report: reportPending, key: pendingKey })
  pendingRef.current = { report: reportPending, key: pendingKey }
  const setPending = (p) => { if (pendingKey) reportPending?.(pendingKey, p) }
  useEffect(() => () => {
    const { report, key } = pendingRef.current
    if (key) report?.(key, false)
  }, [])
  const notNumber = percent ? 'te.err.notNumber' : 'te.err.notNumberAbbrev'
  const expanded = !percent && hasAbbreviation(text) ? parseNumber(text, lang) : null
  const expandedId = id ? `${id}-expanded` : undefined
  const showExpanded = Number.isFinite(expanded) && Math.abs(expanded) <= LIMITS.maxNumber
  useEffect(() => {
    if (isIncompleteNumber(text)) return     // yazılmakta olan metin (ör. "-") dış değerle ezilmez
    const parsed = parseNumber(text, lang, { percent })
    if (!(parsed === value || (parsed == null && value == null))) setText(numberText(value, lang, percent))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value, lang])
  return (
    <div className="relative">
      {percent && lang !== 'en' && (
        <span aria-hidden="true" className={cn('pointer-events-none absolute top-1/2 -translate-y-1/2 text-sm text-muted-foreground', compact ? 'left-2' : 'left-3')}>%</span>
      )}
      <Input id={id} inputMode={percent ? 'decimal' : 'text'} value={text} disabled={disabled} aria-invalid={invalid}
        aria-describedby={[describedBy, showExpanded ? expandedId : null].filter(Boolean).join(' ') || undefined}
        aria-label={ariaLabel}
        // compact: tablo hücresi — dar sütunda rakama yer kalsın (inceleme 2026-10-14: "125.000.000" kırpılıyordu)
        className={cn(compact && 'px-1.5 text-[13px] md:text-[13px]', percent && lang !== 'en' && (compact ? 'pl-6' : 'pl-7'), percent && lang === 'en' && (compact ? 'pr-6' : 'pr-7'), 'tabular-nums pointer-coarse:h-10')}
        onBlur={() => {
          // Yarım kalan yazım alan bırakılınca hata olur (kayıt öncesi düğmeye basmak alanı bırakır)
          if (isIncompleteNumber(text)) onError(!percent && isAmbiguousB(text) ? 'te.err.abbrevB' : notNumber)
        }}
        onChange={(e) => {
          const raw = e.target.value
          setText(raw)
          setPending(isIncompleteNumber(raw))
          const n = parseNumber(raw, lang, { percent })
          if (n == null) { onError(null); onChange(null); return }
          if (Number.isNaN(n) && isIncompleteNumber(raw)) { onError(null); return }
          if (Number.isNaN(n)) { onError(!percent && isAmbiguousB(raw) ? 'te.err.abbrevB' : notNumber); return }
          if (!negative && n < 0) { onError('te.err.negative'); return }
          if (!percent && Math.abs(n) > LIMITS.maxNumber) { onError('te.err.tooLarge'); return }
          onError(null)
          onChange(n)
        }} />
      {percent && lang === 'en' && (
        <span aria-hidden="true" className={cn('pointer-events-none absolute top-1/2 -translate-y-1/2 text-sm text-muted-foreground', compact ? 'right-2' : 'right-3')}>%</span>
      )}
      {showExpanded && (
        <p id={expandedId} data-slot="te-number-expanded"
          className={cn('mt-1 tabular-nums text-muted-foreground [overflow-wrap:anywhere]', compact ? 'text-[11px] leading-tight' : 'text-xs')}>
          <span className="sr-only">{t('te.num.expanded')} </span><span aria-hidden="true">= </span>{numberText(expanded, lang)}
        </p>
      )}
    </div>
  )
}

/** Bölüm kartı — katlanır (2026-10-16): düzenleyici `SectionOpenContext` verir; ipucu yalnız açıkken. */
function SectionShell({ title, children, slot, sectionId, hint }) {
  return (
    <CollapsibleCard sectionId={sectionId} slot={slot} title={title} contentClassName="flex flex-col gap-4"
      hint={hint ? <p className="text-sm text-muted-foreground">{hint}</p> : null}>
      {children}
    </CollapsibleCard>
  )
}

/**
 * Otomatik hesaplanan alanın ÇIKTISI (2026-10-16): kullanıcı girmez, adetlerden hesaplanır. `<output>` etikete bağlıdır
 * (ekran okuyucu alan adını okur); sonuç yoksa "—" ve altında nedeni.
 */
function CalcOutput({ id, field, res, uiLang, describedBy, t }) {
  const shown = res.status !== 'OK' ? '—'
    : field.type === 'yesno' ? (res.value ? t('te.yes') : t('te.no')) : formatPercent(res.value, uiLang)
  return (
    <div className="flex min-h-9 min-w-0 flex-wrap items-center gap-2 rounded-md border border-dashed bg-muted/40 px-3 py-1.5 pointer-coarse:min-h-10"
      data-slot="te-calc" data-status={res.status}>
      <output id={id} aria-describedby={describedBy} className="text-base font-semibold tabular-nums">{shown}</output>
      <Badge variant="secondary" className="rounded-md overflow-visible">{t('te.calc.auto')}</Badge>
    </div>
  )
}

/** Hesabın açıklaması (formül) ya da neden boş / elle olduğu — alan adları raporun şablon diliyle çözülür. */
function calcNoteText(field, res, label, previous, t) {
  const c = field.calc
  const period = periodLabel(previous, t)
  switch (res.status) {
    case 'OK':
      if (c.kind === 'ratio') {
        return c.minus ? t('te.calc.formulaMinus', label(c.num), label(c.minus), label(c.den)) : t('te.calc.formula', label(c.num), label(c.den))
      }
      return t('te.calc.formulaChange', label(c.of), period)
    case 'MISSING': return t('te.calc.missing', res.missing.map(label).join(', '))
    case 'ZERO': return c.kind === 'ratio' ? t('te.calc.zero', label(c.den)) : t('te.calc.zeroPrev', label(c.of), period)
    case 'NEGATIVE': return t('te.calc.negative', label(c.minus), label(c.num))
    case 'EQUAL': return t('te.calc.equal', label(c.of))
    case 'RANGE': return t('te.calc.range')
    case 'MANUAL': return t('te.calc.manual', label(c.of))
    default: return t('te.calc.badRef')
  }
}

/** Önceki ayın değeri + değişim ipucu (gösterim — alanı doldurmaz). */
function PreviousHint({ field, prev, cur, previous, uiLang, t }) {
  if (prev == null) return null
  const yesNo = (b) => (b ? t('te.yes') : t('te.no'))
  const shown = formatValue(field, prev, uiLang, yesNo)
  let delta = null
  if (field.type === 'number' && cur != null) {
    const c = changePercent(prev, cur)
    if (c != null) delta = t('te.prev.changePct', `${c > 0 ? '+' : ''}${formatPercent(Math.round(c * 100) / 100, uiLang)}`)
  } else if (field.type === 'percent' && cur != null) {
    const d = Math.round((Number(cur) - Number(prev)) * 10000) / 10000
    delta = t('te.prev.changePts', `${d > 0 ? '+' : ''}${formatNumber(d, uiLang)}`)
  }
  return (
    <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-prev">
      {t('te.prev.value', periodLabel(previous, t), shown)}{delta ? ` · ${delta}` : ''}
    </p>
  )
}

export function MetricsBlock({ section, values, resolve, uiLang, previous, readOnly, errors, onField, onFieldError, templateFields,
  calc = null }) {
  const t = useT()
  const byId = new Map((templateFields || section.fields || []).map((x) => [x.id, x]))
  const labelOf = (fid) => resolve(byId.get(fid)?.label || fid)
  // Bağlı evet/hayır sorusu "Hayır" olan alan MUAF: sorulmaz (PDF'e de girmez)
  const fields = (section.fields || []).filter((f) => !fieldExempt(f, values.fields))
  const exemptCount = (f) => (f.type === 'yesno' && values.fields[f.id] === false
    ? (templateFields || section.fields || []).filter((x) => x.skip_if_no === f.id).length : 0)
  const compact = fields.filter((f) => f.type !== 'textarea')
  const long = fields.filter((f) => f.type === 'textarea')
  const renderField = (f) => {
    const id = `te-v-${f.id}`
    const err = errors[`values.${f.id}`]
    const hintId = f.hint ? `${id}-h` : undefined
    const errId = err ? `${id}-e` : undefined
    // Otomatik hesap (2026-10-16): formül kullanılabiliyorsa alan SORULMAZ, çıktısı gösterilir; kullanılamıyorsa (geçen
    // ayın raporu yok, formüldeki alan yok) elle girilir — ikisinde de altında açıklama
    const res = calc?.[f.id] || null
    const computed = !!res && !CALC_MANUAL.has(res.status)
    const noteId = res ? `${id}-c` : undefined
    const describedBy = [hintId, noteId, errId].filter(Boolean).join(' ') || undefined
    const v = computed ? (res.status === 'OK' ? res.value : null) : values.fields[f.id]
    let control
    if (computed) {
      control = <CalcOutput id={id} field={f} res={res} uiLang={uiLang} describedBy={describedBy} t={t} />
    } else if (f.type === 'number' || f.type === 'percent') {
      control = (
        <NumberInput id={id} value={v ?? null} lang={uiLang} percent={f.type === 'percent'} negative={f.type === 'percent'}
          disabled={readOnly} describedBy={describedBy} invalid={err ? true : undefined}
          onChange={(n) => onField(f.id, n)} onError={(k) => onFieldError(`values.${f.id}`, k ? t(k) : null)} pendingKey={`values.${f.id}`} />
      )
    } else if (f.type === 'yesno') {
      control = (
        <ToggleGroup id={id} type="single" variant="outline" disabled={readOnly} value={v === true ? 'yes' : v === false ? 'no' : ''}
          aria-describedby={describedBy} aria-label={resolve(f.label)}
          onValueChange={(x) => onField(f.id, x === 'yes' ? true : x === 'no' ? false : null)}>
          <ToggleGroupItem value="yes" className="px-4 pointer-coarse:min-h-10">{t('te.yes')}</ToggleGroupItem>
          <ToggleGroupItem value="no" className="px-4 pointer-coarse:min-h-10">{t('te.no')}</ToggleGroupItem>
        </ToggleGroup>
      )
    } else if (f.type === 'text') {
      control = (
        <Input id={id} className="pointer-coarse:h-10" value={v ?? ''} disabled={readOnly} maxLength={LIMITS.shortText} aria-describedby={describedBy}
          aria-invalid={err ? true : undefined}
          onChange={(e) => onField(f.id, e.target.value === '' ? null : e.target.value)} />
      )
    } else {
      control = (
        <RichTextarea id={id} value={v ?? ''} disabled={readOnly} rows={3} maxLength={LIMITS.longText} describedBy={describedBy}
          invalid={err ? true : undefined} label={resolve(f.label)} onChange={(x) => onField(f.id, x === '' ? null : x)} />
      )
    }
    return (
      <div key={f.id} className={cn('flex min-w-0 flex-col gap-1.5', f.type === 'textarea' && 'sm:col-span-2 lg:col-span-3')}
        data-field={`values.${f.id}`} data-slot="te-value">
        <div data-slot="te-copy-row" className="text-sm leading-snug [overflow-wrap:anywhere]">
          <label htmlFor={f.type === 'yesno' && !computed ? undefined : id} id={`${id}-l`} className="inline text-sm font-semibold leading-snug">
            {resolve(f.label)}
          </label>
          <CopyTextButton text={resolve(f.label)} />
        </div>
        {control}
        {res && (
          <p id={noteId} className="text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-calc-note" data-status={res.status}>
            {calcNoteText(f, res, labelOf, previous, t)}
          </p>
        )}
        {/* Bu adetten türeyen oranlar girişin hemen altında (2026-10-16, kullanıcı isteği: "kota / map yapılmamış hata adedi
            girişinde yüzde de görünsün") — canlı, aşağıdaki oran bölümüyle aynı sonuç */}
        {f.type === 'number' && derivedRates(templateFields || section.fields, f.id)
          .filter((d) => !fieldExempt(d, values.fields) && calc?.[d.id] && !CALC_MANUAL.has(calc[d.id].status))
          .map((d) => {
            const r = calc[d.id]
            return (
              <p key={d.id} className="text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-calc-derived" data-rate={d.id}>
                {resolve(d.label)}: <span className="font-semibold text-foreground tabular-nums">
                  {r.status === 'OK' ? formatPercent(r.value, uiLang) : '—'}
                </span>
              </p>
            )
          })}
        {f.hint && <p id={hintId} className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{resolve(f.hint)}</p>}
        {exemptCount(f) > 0 && (
          <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-exempt-note" role="status">
            {t('te.exemptNote', exemptCount(f))}
          </p>
        )}
        <PreviousHint field={f} prev={previous?.fields?.[f.id]} cur={v} previous={previous} uiLang={uiLang} t={t} />
        {err && <p id={errId} className="text-sm text-destructive" role="alert">{err}</p>}
      </div>
    )
  }
  if (fields.length === 0) {
    // Bölümün tüm alanları "Hayır" cevabıyla muaf: boş kart yerine kısa not (PDF'te bölüm hiç basılmaz)
    return (
      <SectionShell title={resolve(section.title)} slot="te-metrics" sectionId={section.id}>
        <p className="text-sm text-muted-foreground" data-slot="te-section-exempt">{t('te.sectionExempt')}</p>
      </SectionShell>
    )
  }
  return (
    <SectionShell title={resolve(section.title)} slot="te-metrics" sectionId={section.id}>
      {compact.length > 0 && <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">{compact.map(renderField)}</div>}
      {long.length > 0 && <div className="grid gap-4">{long.map(renderField)}</div>}
    </SectionShell>
  )
}

export function IssuesBlock({ section, values, resolve, uiLang, readOnly, errors, onIssues, onFieldError }) {
  const t = useT()
  const list = values.issues[section.id] || []
  const pattern = section.item_title
  const set = (fn) => onIssues(section.id, fn(list))
  const patch = (id, p) => set((l) => l.map((i) => (i.id === id ? { ...i, ...p } : i)))
  return (
    <SectionShell title={resolve(section.title)} slot="te-issues" sectionId={section.id} hint={t('te.issues.hint')}>
      {list.length === 0 && <p className="text-sm text-muted-foreground">{t('te.issues.empty')}</p>}
      <ol className="flex flex-col gap-3">
        {list.map((iss, idx) => {
          const base = `te-iss-${section.id}-${iss.id}`
          // Sunucu hatası sıra numarasıyla gelir; istemci hatası konunun KİMLİĞİ ve alanıyla (müşteri / hata adedi ayrı —
          // biri düzelince diğerinin hatası silinmez; sıralama değişince hata başka konuya kaymaz; inceleme 2026-10-13)
          const err = errors[`issues.${section.id}.${idx + 1}`]
          const fieldErr = (key) => errors[issueErrorKey(section.id, iss.id, key)]
          const shownTitle = issueDisplayTitle(iss, pattern)
          return (
            <li key={iss.id} className="rounded-xl border p-3 sm:p-4" data-slot="te-issue" data-issue={iss.id}
              data-field={`issues.${section.id}.${idx + 1}`}>
              <div className="mb-3 flex items-start justify-between gap-2">
                <p className="min-w-0 text-sm font-semibold leading-snug [overflow-wrap:anywhere]" data-slot="te-issue-title">
                  {idx + 1}. {shownTitle || t('te.issues.untitled')}
                </p>
                {!readOnly && (
                  <div className="flex shrink-0 items-center gap-0.5">
                    <Button type="button" size="icon" variant="ghost" className="size-8 pointer-coarse:size-10" disabled={idx === 0}
                      aria-label={t('te.moveUp', shownTitle || idx + 1)} onClick={() => set((l) => move(l, idx, -1))}><ArrowUp aria-hidden="true" /></Button>
                    <Button type="button" size="icon" variant="ghost" className="size-8 pointer-coarse:size-10" disabled={idx === list.length - 1}
                      aria-label={t('te.moveDown', shownTitle || idx + 1)} onClick={() => set((l) => move(l, idx, 1))}><ArrowDown aria-hidden="true" /></Button>
                    <Button type="button" size="icon" variant="ghost" className="size-8 text-destructive pointer-coarse:size-10"
                      aria-label={t('te.issues.remove', shownTitle || idx + 1)} onClick={() => {
                        onFieldError(issueErrorKey(section.id, iss.id, 'customers'), null)
                        onFieldError(issueErrorKey(section.id, iss.id, 'errors'), null)
                        set((l) => l.filter((x) => x.id !== iss.id))
                      }}>
                      <Trash2 aria-hidden="true" />
                    </Button>
                  </div>
                )}
              </div>
              <div className="grid gap-3 md:grid-cols-2">
                <div className="flex flex-col gap-1.5">
                  <label htmlFor={`${base}-topic`} className="text-sm font-medium">{t('te.issues.topic')}</label>
                  <Input id={`${base}-topic`} className="pointer-coarse:h-10" value={iss.topic || ''} disabled={readOnly} maxLength={LIMITS.topic}
                    placeholder={t('te.issues.topicPlaceholder')}
                    onChange={(e) => {
                      const topic = e.target.value
                      patch(iss.id, iss.title_auto !== false
                        ? { topic, title: resolveIssueTitle(pattern, topic), title_auto: true }
                        : { topic })
                    }} />
                </div>
                <div className="flex flex-col gap-1.5">
                  <div className="flex flex-wrap items-center justify-between gap-1">
                    <label htmlFor={`${base}-title`} className="text-sm font-medium">{t('te.issues.title')}</label>
                    {iss.title_auto !== false ? (
                      <Badge variant="secondary" className="rounded-md overflow-visible">{t('te.issues.auto')}</Badge>
                    ) : !readOnly && (
                      <Button type="button" size="sm" variant="ghost" className="h-7 px-2 text-xs pointer-coarse:h-10"
                        onClick={() => patch(iss.id, { title: resolveIssueTitle(pattern, iss.topic), title_auto: true })}>
                        <Wand2 aria-hidden="true" /> {t('te.issues.fromTopic')}
                      </Button>
                    )}
                  </div>
                  <Input id={`${base}-title`} className="pointer-coarse:h-10" value={iss.title_auto !== false ? resolveIssueTitle(pattern, iss.topic) : (iss.title || '')}
                    disabled={readOnly} maxLength={LIMITS.label}
                    onChange={(e) => patch(iss.id, { title: e.target.value, title_auto: false })} />
                </div>
                <div className="flex flex-col gap-1.5 md:col-span-2">
                  <div data-slot="te-copy-row" className="text-sm leading-snug [overflow-wrap:anywhere]">
                    <label htmlFor={`${base}-desc`} className="inline text-sm font-medium">{resolve(section.description_label)}</label>
                    <CopyTextButton text={resolve(section.description_label)} />
                  </div>
                  <RichTextarea id={`${base}-desc`} rows={4} value={iss.description || ''} disabled={readOnly} maxLength={LIMITS.longText}
                    placeholder={t('te.issues.descriptionPlaceholder')} label={resolve(section.description_label)}
                    invalid={err ? true : undefined} describedBy={err ? `${base}-e` : undefined}
                    onChange={(x) => patch(iss.id, { description: x })} />
                </div>
                {[['customers', section.customers_label], ['errors', section.errors_label]].map(([key, label]) => {
                  const fe = fieldErr(key)
                  return (
                    <div key={key} className="flex flex-col gap-1.5" data-field={issueErrorKey(section.id, iss.id, key)}>
                      <div data-slot="te-copy-row" className="text-sm leading-snug [overflow-wrap:anywhere]">
                        <label htmlFor={`${base}-${key}`} className="inline text-sm font-medium">{resolve(label)}</label>
                        <CopyTextButton text={resolve(label)} />
                      </div>
                      <NumberInput id={`${base}-${key}`} value={iss[key] ?? null} lang={uiLang} disabled={readOnly}
                        invalid={fe || err ? true : undefined} describedBy={fe ? `${base}-${key}-e` : err ? `${base}-e` : undefined}
                        onChange={(n) => patch(iss.id, { [key]: n })} pendingKey={issueErrorKey(section.id, iss.id, key)}
                        onError={(k) => onFieldError(issueErrorKey(section.id, iss.id, key), k ? t(k) : null)} />
                      {fe && <p id={`${base}-${key}-e`} className="text-sm text-destructive" role="alert">{fe}</p>}
                    </div>
                  )
                })}
              </div>
              {err && <p id={`${base}-e`} className="mt-2 text-sm text-destructive" role="alert">{err}</p>}
            </li>
          )
        })}
      </ol>
      {!readOnly && (
        <Button type="button" variant="outline" className="w-fit pointer-coarse:min-h-10" disabled={list.length >= LIMITS.issues}
          onClick={() => set((l) => [...l, newIssue(l)])} data-slot="te-add-issue">
          <Plus aria-hidden="true" /> {t('te.issues.add')}
        </Button>
      )}
    </SectionShell>
  )
}

/** İstemcinin anomali sayı alanı hata anahtarı: bölüm + konu kimliği + alan (sıradan bağımsız). */
export function issueErrorKey(sectionId, issueId, key) {
  return `issue:${sectionId}:${issueId}:${key}`
}

export function TextBlock({ section, values, resolve, resolveText, readOnly, errors, onText }) {
  const t = useT()
  const stored = values.texts[section.id]
  // Varsayılan gövde metin sınırıyla çözülür (başlık sınırıyla değil — PDF ile aynı metin)
  const text = stored != null ? stored : (resolveText || resolve)(section.text || '')
  const err = errors[`text.${section.id}`]
  return (
    <SectionShell title={resolve(section.title)} slot="te-text" sectionId={section.id}>
      <div className="flex flex-col gap-1.5" data-field={`text.${section.id}`}>
        <label htmlFor={`te-t-${section.id}`} className="sr-only">{resolve(section.title)}</label>
        <RichTextarea id={`te-t-${section.id}`} rows={3} value={text} disabled={readOnly} maxLength={MAX_RESOLVED_TEXT}
          label={resolve(section.title)} invalid={err ? true : undefined} describedBy={err ? `te-t-${section.id}-e` : undefined}
          onChange={(x) => onText(section.id, x)} />
        <p className="text-xs text-muted-foreground">{t('te.textHint')}</p>
        {err && <p id={`te-t-${section.id}-e`} className="text-sm text-destructive" role="alert">{err}</p>}
      </div>
    </SectionShell>
  )
}

/** Değer alanına götürür: kaydırır ve alanın ilk odaklanabilir öğesine odaklar (özetin "beklenen" bağlantıları). */
function focusValueField(fieldId) {
  if (typeof document === 'undefined') return
  const el = document.querySelector(`[data-field="values.${fieldId}"]`)
  if (!el) return
  try {
    const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches
    el.scrollIntoView?.({ block: 'center', behavior: reduce ? 'auto' : 'smooth' })
  } catch { /* jsdom */ }
  // Önce giriş öğesi (bir seçici listesi belge sırasındaki İLK eşleşmeyi döndürür: biçim araç çubuğunun "Kalın"
  // düğmesi metin kutusundan önce gelir); düğme yalnız başka bir şey yoksa
  const target = el.querySelector('input:not([type="hidden"]), textarea:not(.hidden), [role="radio"]') || el.querySelector('button')
  try { target?.focus?.({ preventScroll: true }) } catch { /* odaklanamaz */ }
}

/**
 * YÖNETİCİ ÖZETİ (2026-10-14): şablondaki cümle kalıpları girilen rakamlarla CANLI dolar (PDF ve e-postadaki metnin
 * aynısı). Rakamı girilmemiş cümle özete girmez; altta "henüz girmeyen cümleler" listesi her cümlenin beklediği
 * rakamı BAĞLANTI olarak verir (tıklayınca o alana gider). Şablondan silinmiş bir alana bağlı cümle ayrıca uyarılır;
 * "Hayır" cevabıyla muaf rakamın cümlesi bilerek düşer. "Metni elle düzenle" otomatik metni düzenlenebilir kopyaya
 * çevirir; elle düzenlenirken güncel otomatik metin açılır kutuda görülür (farklıysa söylenir); "Otomatik metne dön"
 * kopyayı siler (onayla). Boş bırakılan elle metin kayıtta otomatiğe döner (özet kilitlenmez).
 */
export function SummaryBlock({ section, template, values, channelName, year, month, resolve, readOnly, errors, onText, onClearText }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const override = values.texts[section.id]
  const manual = override != null
  const auto = summaryAutoText(section, template, values.fields, channelName, year, month)
  const status = summaryLineStatus(section, template, values.fields)
  const pending = status.filter((x) => x.state === 'missing')
  const broken = status.filter((x) => x.state === 'broken')
  const err = errors[`text.${section.id}`]
  const id = `te-sum-${section.id}`
  const resolveLine = (x) => resolveTextPlaceholders(x, channelName, year, month, template?.lang)
  const editBtn = useRef(null)
  const [focusEdit, setFocusEdit] = useState(false)
  // Metin kutusu YALNIZ "Elle düzenle" tıklanınca odak alır — elle düzenlenmiş özeti olan rapor açılınca, taslak geri
  // yüklenince ya da şablon uygulanınca odak çalınmaz (inceleme 2026-10-14: telefonda klavye açılıyordu)
  const [justEdited, setJustEdited] = useState(false)
  useEffect(() => {
    if (focusEdit && !manual) { editBtn.current?.focus(); setFocusEdit(false) }
  }, [focusEdit, manual])
  useEffect(() => {
    if (justEdited && manual) setJustEdited(false)
  }, [justEdited, manual])
  const differs = manual && !richBlank(override) && override !== auto
  const errId = err ? `${id}-e` : undefined
  return (
    <SectionShell title={resolve(section.title)} slot="te-summary" sectionId={section.id} hint={t('te.sum.hint')}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Badge variant={manual ? 'outline' : 'secondary'} className="rounded-md overflow-visible" data-slot="te-sum-mode">
          {manual ? <PenLine aria-hidden="true" /> : <Wand2 aria-hidden="true" />}
          {manual ? t('te.sum.manual') : t('te.sum.auto')}
        </Badge>
        {!readOnly && (manual ? (
          <Button type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10" data-slot="te-sum-auto"
            onClick={async () => {
              // Kaybolacak bir şey yoksa (boş ya da otomatik metnin aynısı) onay sorulmaz
              const ok = richBlank(override) || override === auto || await showConfirm({ title: t('te.sum.backToAutoTitle'),
                message: t('te.sum.backToAutoBody'), variant: 'warning', confirmText: t('te.sum.backToAuto'), cancelText: t('te.cancel') })
              if (ok) { onClearText(section.id); setFocusEdit(true) }
            }}>
            <Wand2 aria-hidden="true" /> {t('te.sum.backToAuto')}
          </Button>
        ) : (
          <Button ref={editBtn} type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10" data-slot="te-sum-edit"
            onClick={() => { setJustEdited(true); onText(section.id, auto) }}>
            <PenLine aria-hidden="true" /> {t('te.sum.edit')}
          </Button>
        ))}
      </div>
      {manual ? (
        <div className="flex flex-col gap-1.5" data-field={`text.${section.id}`}>
          <label htmlFor={id} className="sr-only">{resolve(section.title)}</label>
          {!readOnly && <p className="m-0 text-sm text-muted-foreground" data-slot="te-sum-manual-note">{t('te.sum.manualNote')}</p>}
          <RichTextarea id={id} rows={6} value={override} disabled={readOnly} maxLength={MAX_RESOLVED_TEXT} autoFocus={justEdited}
            label={resolve(section.title)} invalid={err ? true : undefined} describedBy={errId} onChange={(x) => onText(section.id, x)} />
          {!readOnly && richBlank(override) && <p className="m-0 text-sm text-muted-foreground" role="status">{t('te.sum.blankNote')}</p>}
          {err && <p id={errId} className="m-0 text-sm text-destructive" role="alert">{err}</p>}
          {!readOnly && (
            <Collapsible className="rounded-lg border" data-slot="te-sum-current">
              <CollapsibleTrigger asChild>
                <Button type="button" variant="ghost" size="sm"
                  className="group h-auto w-full justify-between whitespace-normal px-3 py-2 text-left pointer-coarse:min-h-10">
                  <span className="min-w-0">
                    {t('te.sum.currentAuto')}
                    {differs && <span className="block text-xs font-normal text-amber-800 dark:text-amber-300">{t('te.sum.currentDiffers')}</span>}
                  </span>
                  <ChevronDown aria-hidden="true" className="shrink-0 transition-transform group-data-[state=open]:rotate-180 motion-reduce:transition-none" />
                </Button>
              </CollapsibleTrigger>
              <CollapsibleContent className="border-t px-3 py-2 text-sm">
                {auto ? <RichText text={auto} /> : <p className="m-0 text-muted-foreground">{t('te.sum.empty')}</p>}
              </CollapsibleContent>
            </Collapsible>
          )}
        </div>
      ) : auto ? (
        <RichText text={auto} className="rounded-lg border bg-muted/30 p-3 text-base sm:p-4" slot="te-sum-text" />
      ) : (
        <p className="m-0 rounded-lg border border-dashed p-3 text-sm text-muted-foreground" data-slot="te-sum-empty">{t('te.sum.empty')}</p>
      )}
      {!manual && pending.length > 0 && (
        <div className="flex flex-col gap-1.5" data-slot="te-sum-pending">
          <p className="m-0 text-sm font-medium">{t('te.sum.pending', pending.length)}</p>
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {pending.map((p) => (
              <li key={p.index} className="flex gap-2 text-sm text-muted-foreground">
                <CircleDashed aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
                <span className="min-w-0 [overflow-wrap:anywhere]">
                  {summaryLinePreview(p.line, template, resolve, t('te.sum.removedField'), resolveLine)}{' '}
                  <span className="mt-0.5 flex min-w-0 flex-wrap items-center gap-x-1 gap-y-0.5 text-xs">
                    {/* Boşluk düğümleri flex düzeninde görünmez; kopyalanan / okunan metin "Beklenen: A, B" olur */}
                    <span>{t('te.sum.needsLabel')}</span>{' '}
                    {p.missing.map((f, k) => (
                      <Fragment key={f.id}>
                      <span className="inline-flex min-w-0 max-w-full items-center">
                        {readOnly ? <span>{resolve(f.label)}</span> : (
                          // Button tabanı shrink-0: uzun alan adı sarılsın diye shrink + min-w-0 (390 px'te taşmaz)
                          <Button type="button" variant="link" size="sm" className="h-auto min-h-6 min-w-0 shrink whitespace-normal p-0 text-start text-xs [overflow-wrap:anywhere] pointer-coarse:min-h-10"
                            onClick={() => focusValueField(f.id)} aria-label={t('te.sum.goToField', resolve(f.label))}>
                            {resolve(f.label)}
                          </Button>
                        )}
                        {k < p.missing.length - 1 && <span aria-hidden="true">,</span>}
                      </span>
                      {k < p.missing.length - 1 ? ' ' : null}
                      </Fragment>
                    ))}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
      {!manual && broken.length > 0 && (
        <div className="flex flex-col gap-1" data-slot="te-sum-broken">
          {broken.map((b) => (
            <p key={b.index} className="m-0 flex gap-2 text-xs text-amber-800 dark:text-amber-300 [overflow-wrap:anywhere]">
              <TriangleAlert aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
              <span>{t(`te.sum.broken.${b.reason || 'removed'}`, summaryLinePreview(b.line, template, resolve, t('te.sum.removedField'), resolveLine))}</span>
            </p>
          ))}
        </div>
      )}
    </SectionShell>
  )
}

/**
 * TABLO bölümü (2026-10-14, ör. SLA erişilebilirlik tablosu): satırlar ve sütunlar şablondan; grup satırı kalın ve
 * zeminli, altındaki satırlar (yalnız tabloda grup satırı varsa) içeriden başlar. Kap tablonun en az genişliğine
 * (`tableMinWidth`: sütun sayısı ve türünden) yetiyorsa gerçek tablo, yetmiyorsa her satır bir kart — on sütunlu bir
 * tablo dar ekranda 45 px'lik kutulara sıkışmaz. Her hücrenin adı "Satır — Sütun"dur (iki görünümde de). Hata
 * hücrenin altında; anahtar sunucununkiyle aynı (`tables.<bölüm>.<satır>.<sütun>`).
 */
export function TableBlock({ section, values, resolve, uiLang, tplLang, readOnly, errors, onCell, onFieldError }) {
  const t = useT()
  const [ref, width] = useElementWidth()
  const cols = section.columns || []
  const rows = section.rows || []
  const minWidth = tableMinWidth(cols)
  // Kabın kenarlığı 2 px: tablo ancak kap minWidth + 2 ise kaymadan sığar. Eşikte 24 px tampon: genişlik sınırda gidip
  // gelirken (kaydırma çubuğu, kenar çubuğu) görünüm titremez
  const fit = minWidth + 2
  const layoutRef = useRef(null)
  let cards = layoutRef.current === 'cards'
  // Genişlik 0 = ölçülemedi (kapalı bölüm, ilk çizim): son görünüm korunur — bölümü kapatıp açmak hücreleri yeniden
  // kurup yarım yazımı silmesin (2026-10-16)
  if (width > 0) cards = layoutRef.current === 'cards' ? width < fit + 24 : width < fit
  const layout = cards ? 'cards' : 'table'
  const focusedCell = useRef(null)
  useEffect(() => {
    const prev = layoutRef.current
    layoutRef.current = layout
    if (prev == null || prev === layout) return
    // Görünüm değişince hücreler yeniden kurulur: yarım kalmış (geçersiz) yazım kaybolur, onun istemci hatası da
    // kalkmalı (yoksa son geçerli değerin altında "sayı yazın" kalır ve kaydı engeller); odaklı hücreye odak döner
    for (const r of rows) for (const c of cols) onFieldError(tableCellKey(section.id, r.id, c.id), null)
    const id = focusedCell.current
    const active = typeof document !== 'undefined' ? document.activeElement : null
    if (id && typeof document !== 'undefined' && (!active || active === document.body || !active.isConnected)) {
      document.getElementById(id)?.focus?.({ preventScroll: true })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [layout])
  const onFocusIn = (e) => { if (e.target?.id?.startsWith?.(`te-c-${section.id}-`)) focusedCell.current = e.target.id }
  // Odak başka bir öğeye geçince unutulur. Hedefsiz kayıpta yalnız hücre DOM'dan çıktıysa (görünüm değişimi) hatırlanır;
  // hücre bir sonraki karede hâlâ sayfadaysa kullanıcı boş bir yere tıkladı demektir → unutulur (yoksa sonraki bir
  // döndürme / pencere değişiminde eski hücre odağı ve telefonda klavyeyi geri alırdı; inceleme 2026-10-14)
  const onFocusOut = (e) => {
    if (e.relatedTarget) { focusedCell.current = null; return }
    const el = e.target
    requestAnimationFrame(() => {
      if (el?.isConnected && focusedCell.current === el.id && document.activeElement !== el) focusedCell.current = null
    })
  }
  // İçeriden başlama yalnız bir grup satırının ALTINDAKİ satırlar için (ilk grup satırından önceki satırlar düz)
  const afterGroup = new Set()
  let seenGroup = false
  for (const r of rows) {
    if (seenGroup && !r.group) afterGroup.add(r.id)
    if (r.group) seenGroup = true
  }
  const cell = (r, c) => {
    const key = tableCellKey(section.id, r.id, c.id)
    const id = `te-c-${section.id}-${r.id}-${c.id}`
    const err = errors[key]
    const label = t('te.tbl.cellLabel', resolve(r.label), resolve(c.label))
    const v = values.tables?.[section.id]?.[r.id]?.[c.id]
    // Salt okunur izleyici (AUDIT vb.) değeri METİN olarak görür: kapalı giriş kutusu yarı saydamdı (≈ 3,5:1 kontrast)
    if (readOnly) {
      const shown = v == null || v === '' ? '—' : c.type === 'text' ? String(v) : formatValue({ type: c.type }, v, uiLang)
      const control = (
        <span id={id} data-slot="te-cell-readonly"
          className={cn('block min-h-9 py-2 text-sm leading-snug [overflow-wrap:anywhere]', c.type !== 'text' && 'tabular-nums',
            c.type !== 'text' && !cards && 'text-right', (v == null || v === '') && 'text-muted-foreground')}>{shown}</span>
      )
      return { id, key, err, control }
    }
    const control = c.type === 'text' ? (
      <Input id={id} className="pointer-coarse:h-10" value={v ?? ''} disabled={readOnly} maxLength={LIMITS.cellText}
        aria-label={label} aria-invalid={err ? true : undefined} aria-describedby={err ? `${id}-e` : undefined}
        onChange={(e) => onCell(section.id, r.id, c.id, e.target.value === '' ? null : e.target.value)} />
    ) : (
      <NumberInput id={id} value={v ?? null} lang={uiLang} percent={c.type === 'percent'} negative={c.type === 'percent'}
        disabled={readOnly} ariaLabel={label} invalid={err ? true : undefined} compact={!cards}
        describedBy={err ? `${id}-e` : undefined}
        onChange={(n) => onCell(section.id, r.id, c.id, n)} onError={(k) => onFieldError(key, k ? t(k) : null)} pendingKey={key} />
    )
    return { id, key, err, control }
  }
  return (
    <SectionShell title={resolve(section.title)} slot="te-table" sectionId={section.id} hint={t('te.tbl.hint')}>
      <div ref={ref} className="min-w-0" data-layout={layout} onFocus={onFocusIn} onBlur={onFocusOut}>
        {cards ? (
          <ol className="m-0 flex list-none flex-col gap-3 p-0">
            {rows.map((r) => (
              <li key={r.id} className={cn('rounded-xl border p-3', r.group && 'bg-muted/50')} data-slot="te-table-card" data-row={r.id}>
                <p className={cn('m-0 mb-2 text-sm leading-snug [overflow-wrap:anywhere]', r.group ? 'font-bold' : 'font-semibold')}>
                  {resolve(r.label)}
                </p>
                {/* Sayı hücreleri kısa: telefonda da iki kolon (kart boyu yarıya iner); metin sütunu tam satır */}
                <div className="grid grid-cols-2 gap-x-3 gap-y-3">
                  {cols.map((c) => {
                    const x = cell(r, c)
                    return (
                      <div key={c.id} className={cn('flex min-w-0 flex-col justify-end gap-1', c.type === 'text' && 'col-span-2')} data-field={x.key}>
                        <label htmlFor={x.id} aria-hidden={readOnly ? undefined : 'true'} className="text-xs font-medium text-muted-foreground [overflow-wrap:anywhere]" lang={tplLang || undefined}>{resolve(c.label)}</label>
                        {x.control}
                        {x.err && <p id={`${x.id}-e`} className="m-0 text-sm text-destructive" role="alert">{x.err}</p>}
                      </div>
                    )
                  })}
                </div>
              </li>
            ))}
          </ol>
        ) : (
          <div className="rounded-lg border" data-slot="te-table-scroll">
            <Table style={{ minWidth: `${minWidth}px` }}>
              <TableHeader>
                <TableRow className="bg-muted/60 hover:bg-muted/60">
                  {/* Satır adı sütunu tableMinWidth bütçesindeki 176 px (yüzde genişlik çok sütunda sayılara yer bırakmıyordu) */}
                  <TableHead scope="col" className="w-44 min-w-44 whitespace-normal px-3 py-2 align-bottom">
                    <span className="sr-only">{t('te.tbl.rowHeader')}</span>
                  </TableHead>
                  {cols.map((c) => (
                    <TableHead key={c.id} scope="col" className={cn('h-auto whitespace-normal break-words px-1.5 py-2 align-bottom text-xs font-semibold text-muted-foreground hyphens-auto',
                      c.type === 'text' ? 'min-w-40 text-left' : 'min-w-28 text-right')} lang={tplLang || undefined}>{resolve(c.label)}</TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((r) => (
                  <TableRow key={r.id} className={cn('align-top', r.group && 'bg-muted/40 hover:bg-muted/40')} data-slot="te-table-row" data-row={r.id}>
                    <TableHead scope="row" className={cn('h-auto whitespace-normal px-3 py-2 text-left align-top leading-snug text-foreground [overflow-wrap:anywhere]',
                      r.group ? 'font-bold' : 'font-medium', afterGroup.has(r.id) && 'ps-6')}>{resolve(r.label)}</TableHead>
                    {cols.map((c) => {
                      const x = cell(r, c)
                      return (
                        <TableCell key={c.id} className={cn('whitespace-normal px-1 py-1.5 align-top', c.type !== 'text' && '[&_input]:text-right')} data-field={x.key}>
                          {x.control}
                          {x.err && <p id={`${x.id}-e`} className="m-0 mt-1 text-xs text-destructive" role="alert">{x.err}</p>}
                        </TableCell>
                      )
                    })}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </div>
    </SectionShell>
  )
}
