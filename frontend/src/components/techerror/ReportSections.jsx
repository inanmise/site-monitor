import { useEffect, useState } from 'react'
import { ArrowDown, ArrowUp, Plus, Trash2, Wand2 } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent, CardHeader } from '@/components/shadcn/card'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'
import {
  LIMITS, MAX_RESOLVED_TEXT, changePercent, fieldExempt, formatNumber, formatPercent, formatValue, isIncompleteNumber,
  issueDisplayTitle, move, newIssue, parseNumber, resolveIssueTitle,
} from './techErrorModel.js'
import { periodLabel } from './TechErrorParts.jsx'

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
 * kayıt) metin yenilenir.
 */
export function NumberInput({ id, value, lang, percent = false, negative = false, onChange, onError, describedBy, invalid, disabled }) {
  const [text, setText] = useState(() => numberText(value, lang, percent))
  useEffect(() => {
    if (isIncompleteNumber(text)) return     // yazılmakta olan metin (ör. "-") dış değerle ezilmez
    const parsed = parseNumber(text, lang, { percent })
    if (!(parsed === value || (parsed == null && value == null))) setText(numberText(value, lang, percent))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value, lang])
  return (
    <div className="relative">
      {percent && lang !== 'en' && (
        <span aria-hidden="true" className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-sm text-muted-foreground">%</span>
      )}
      <Input id={id} inputMode="decimal" value={text} disabled={disabled} aria-describedby={describedBy} aria-invalid={invalid}
        className={cn(percent && lang !== 'en' && 'pl-7', percent && lang === 'en' && 'pr-7', 'tabular-nums pointer-coarse:h-10')}
        onBlur={() => {
          // Yarım kalan yazım alan bırakılınca hata olur (kayıt öncesi düğmeye basmak alanı bırakır)
          if (isIncompleteNumber(text)) onError('te.err.notNumber')
        }}
        onChange={(e) => {
          const raw = e.target.value
          setText(raw)
          const n = parseNumber(raw, lang, { percent })
          if (n == null) { onError(null); onChange(null); return }
          if (Number.isNaN(n) && isIncompleteNumber(raw)) { onError(null); return }
          if (Number.isNaN(n)) { onError('te.err.notNumber'); return }
          if (!negative && n < 0) { onError('te.err.negative'); return }
          onError(null)
          onChange(n)
        }} />
      {percent && lang === 'en' && (
        <span aria-hidden="true" className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-sm text-muted-foreground">%</span>
      )}
    </div>
  )
}

function SectionShell({ title, children, slot, sectionId, hint }) {
  return (
    <Card className="gap-3" data-slot={slot} data-section={sectionId}>
      <CardHeader>
        <h3 className="text-base font-semibold leading-snug [overflow-wrap:anywhere]">{title}</h3>
        {hint && <p className="text-sm text-muted-foreground">{hint}</p>}
      </CardHeader>
      <CardContent className="flex flex-col gap-4">{children}</CardContent>
    </Card>
  )
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

export function MetricsBlock({ section, values, resolve, uiLang, previous, readOnly, errors, onField, onFieldError, templateFields }) {
  const t = useT()
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
    const describedBy = [hintId, errId].filter(Boolean).join(' ') || undefined
    const v = values.fields[f.id]
    let control
    if (f.type === 'number' || f.type === 'percent') {
      control = (
        <NumberInput id={id} value={v ?? null} lang={uiLang} percent={f.type === 'percent'} negative={f.type === 'percent'}
          disabled={readOnly} describedBy={describedBy} invalid={err ? true : undefined}
          onChange={(n) => onField(f.id, n)} onError={(k) => onFieldError(`values.${f.id}`, k ? t(k) : null)} />
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
          onChange={(e) => onField(f.id, e.target.value === '' ? null : e.target.value)} />
      )
    } else {
      control = (
        <Textarea id={id} value={v ?? ''} disabled={readOnly} rows={3} maxLength={LIMITS.longText} aria-describedby={describedBy}
          onChange={(e) => onField(f.id, e.target.value === '' ? null : e.target.value)} />
      )
    }
    return (
      <div key={f.id} className={cn('flex min-w-0 flex-col gap-1.5', f.type === 'textarea' && 'sm:col-span-2 lg:col-span-3')}
        data-field={`values.${f.id}`} data-slot="te-value">
        <label htmlFor={f.type === 'yesno' ? undefined : id} id={`${id}-l`} className="text-sm font-semibold leading-snug [overflow-wrap:anywhere]">
          {resolve(f.label)}
        </label>
        {control}
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
                  <label htmlFor={`${base}-desc`} className="text-sm font-medium">{resolve(section.description_label)}</label>
                  <Textarea id={`${base}-desc`} rows={4} value={iss.description || ''} disabled={readOnly} maxLength={LIMITS.longText}
                    placeholder={t('te.issues.descriptionPlaceholder')}
                    onChange={(e) => patch(iss.id, { description: e.target.value })} />
                </div>
                {[['customers', section.customers_label], ['errors', section.errors_label]].map(([key, label]) => {
                  const fe = fieldErr(key)
                  return (
                    <div key={key} className="flex flex-col gap-1.5" data-field={issueErrorKey(section.id, iss.id, key)}>
                      <label htmlFor={`${base}-${key}`} className="text-sm font-medium [overflow-wrap:anywhere]">{resolve(label)}</label>
                      <NumberInput id={`${base}-${key}`} value={iss[key] ?? null} lang={uiLang} disabled={readOnly}
                        invalid={fe || err ? true : undefined} describedBy={fe ? `${base}-${key}-e` : err ? `${base}-e` : undefined}
                        onChange={(n) => patch(iss.id, { [key]: n })}
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
        <Textarea id={`te-t-${section.id}`} rows={3} value={text} disabled={readOnly} maxLength={MAX_RESOLVED_TEXT}
          onChange={(e) => onText(section.id, e.target.value)} />
        <p className="text-xs text-muted-foreground">{t('te.textHint')}</p>
        {err && <p className="text-sm text-destructive" role="alert">{err}</p>}
      </div>
    </SectionShell>
  )
}
