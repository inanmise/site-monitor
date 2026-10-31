import { useCallback, useMemo, useState } from 'react'
import { ArrowDown, ArrowUp, ListChecks, Plus, Trash2, TriangleAlert, Siren, Lightbulb } from 'lucide-react'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import Field, { FieldError, FieldHint } from '../ui/Field.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import {
  AGGREGATES, CHANGE_DIRECTIONS, CHANGE_MODES, CHANGE_OF, MAX_RULES, RULE_KINDS, RULE_LOGICS, SCOPES,
  changeKind, changeOperator, formatInputNumber, isNoValueOperator, isRangeOperator, isTextKind, moveRule, needsAggregate,
  needsColumn, newRule, operatorsFor, parseNumber, ruleSummary, setTextLevel, strictnessHint, textLevel, validateRule,
} from './ruleModel.js'

/** NativeSelect tam genişlik (kendi sarmalayıcısı `w-fit`). */
const FULL_SELECT = '[&>[data-slot=native-select-wrapper]]:w-full'
const CONTROL = 'h-9 w-full pointer-coarse:h-10'

/** Sunucu ya da istemci hata kodu → metin. Sunucu iletisi (düz metin) olduğu gibi. */
function errorText(t, server, clientCode) {
  if (server) return server
  return clientCode ? t(`dbq.rule.v.${clientCode}`) : null
}

/**
 * Kural oluşturucu (Veritabanı Sorgu İzleme, 2026-10-31). Her kural bir kart: tür (Satır sayısı / Kolon değeri / Metin
 * eşleşmesi / Değişim), kolon (sorgu denendiyse önerilir — serbest giriş de), toplama, işleç, iki seviyeli eşik (uyarı +
 * kritik; aralık işleçlerinde alt / üst), metin eşleşmesinde kapsam + tek seviye, değişimde neyin / nasıl / hangi yönde.
 * Kartın altında İNSAN DİLİNDE özet. ≤ 10 kural; ANY / ALL birleşimi. Alan hataları sunucuyla aynı anahtarlarla
 * (`rules`, `rules[N].<alt>`) ilgili alanın altında; istemci doğrulaması (sayı biçimi TR "1.250,5" / EN "1,250.5", aralık,
 * en az bir eşik) anında. Yazılan sayı yazılırken yeniden biçimlenmez (taslak metin), geçersiz metin sessizce yutulmaz:
 * kurala METİN olarak geçer → sunucu da alan hatası döner.
 */
export default function RuleBuilder({ rules, logic, onChange, onLogicChange, columns, fieldErrors, disabled = false }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const list = useMemo(() => (Array.isArray(rules) ? rules : []), [rules])
  const errs = fieldErrors || {}
  const atMax = list.length >= MAX_RULES

  const update = useCallback((i, next) => {
    onChange?.(list.map((r, j) => (j === i ? next : r)))
  }, [list, onChange])

  const add = () => { if (!atMax) onChange?.([...list, newRule('ROW_COUNT')]) }
  const remove = async (i) => {
    const r = list[i]
    const name = r?.label?.trim() || t('dbq.rule.cardTitle', i + 1)
    const ok = await showConfirm({
      title: t('dbq.rule.deleteTitle'), message: t('dbq.rule.deleteBody', name),
      confirmText: t('dbq.rule.deleteConfirm'), variant: 'danger',
    })
    if (ok) onChange?.(list.filter((_, j) => j !== i))
  }

  return (
    <div data-slot="dbq-rule-builder" data-field="rules" className="flex min-w-0 flex-col gap-3">
      <div className="flex flex-col gap-2 rounded-lg border bg-muted/30 p-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex min-w-0 flex-col gap-0.5">
          <span className="text-sm font-semibold">{t('dbq.rule.logicLabel')}</span>
          <span className="text-xs text-muted-foreground">{t(`dbq.rule.logicHint.${logic === 'ALL' ? 'ALL' : 'ANY'}`)}</span>
        </div>
        <SegmentedControl value={logic === 'ALL' ? 'ALL' : 'ANY'} onChange={(v) => !disabled && onLogicChange?.(v)}
          ariaLabel={t('dbq.rule.logicLabel')} className="w-full shrink-0 sm:w-auto" itemClassName="flex-1 sm:flex-none"
          options={RULE_LOGICS.map((v) => ({ value: v, label: t(`dbq.rule.logic.${v}`), disabled }))} />
      </div>

      {errs.rules && <FieldError>{errs.rules}</FieldError>}

      {list.length === 0 && (
        <div data-slot="dbq-rule-empty" className="flex flex-col items-start gap-1 rounded-lg border border-dashed p-4">
          <span className="inline-flex items-center gap-1.5 text-sm font-semibold"><ListChecks aria-hidden="true" className="size-4" />{t('dbq.rule.emptyTitle')}</span>
          <span className="text-xs text-muted-foreground">{t('dbq.rule.emptyBody')}</span>
        </div>
      )}

      <ol className="m-0 flex list-none flex-col gap-3 p-0">
        {list.map((r, i) => (
          <li key={r.id || `idx-${i}`}>
            <RuleCard rule={r} index={i} count={list.length} columns={columns} disabled={disabled} errs={errs}
              onChange={(next) => update(i, next)} onRemove={() => remove(i)}
              onMove={(d) => onChange?.(moveRule(list, i, d))} />
          </li>
        ))}
      </ol>

      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" variant="outline" onClick={add} disabled={disabled || atMax} className="pointer-coarse:h-10" data-slot="dbq-rule-add">
          <Plus aria-hidden="true" />{t('dbq.rule.add')}
        </Button>
        <span className="text-xs text-muted-foreground tabular-nums">
          {atMax ? t('dbq.rule.maxReached', MAX_RULES) : t('dbq.rule.countOf', list.length, MAX_RULES)}
        </span>
      </div>
    </div>
  )
}

/** Tek kural kartı. */
function RuleCard({ rule, index, count, columns, disabled, errs, onChange, onRemove, onMove }) {
  const t = useT()
  const { lang } = useLanguage()
  const client = useMemo(() => validateRule(rule), [rule])
  const key = (alt) => `rules[${index}].${alt}`
  const err = (alt) => errorText(t, errs[key(alt)], ['missing_threshold', 'missing_text', 'column'].includes(client[alt]) ? null : client[alt])
  const title = rule.label?.trim() || t('dbq.rule.cardTitle', index + 1)
  const text = isTextKind(rule.kind)
  const hint = strictnessHint(rule)
  const summary = ruleSummary(rule, t, lang)
  const missing = client.threshold || null

  return (
    <Card data-slot="dbq-rule-card" data-kind={rule.kind} data-index={index}
      className={cn('gap-0 py-0 shadow-none', Object.keys(errs).some((k) => k.startsWith(`rules[${index}].`)) && 'border-destructive/60')}>
      <div className="flex flex-wrap items-center gap-2 border-b px-3 py-2 sm:px-4">
        <span className="min-w-0 flex-1 text-sm font-semibold [overflow-wrap:anywhere]">{title}</span>
        <div className="flex items-center gap-1">
          <Button type="button" variant="ghost" size="icon" disabled={disabled || index === 0} onClick={() => onMove(-1)}
            aria-label={t('dbq.rule.moveUp', title)} className="size-9 pointer-coarse:size-10"><ArrowUp aria-hidden="true" /></Button>
          <Button type="button" variant="ghost" size="icon" disabled={disabled || index === count - 1} onClick={() => onMove(1)}
            aria-label={t('dbq.rule.moveDown', title)} className="size-9 pointer-coarse:size-10"><ArrowDown aria-hidden="true" /></Button>
          <Button type="button" variant="ghost" size="icon" disabled={disabled} onClick={onRemove}
            aria-label={t('dbq.rule.delete', title)} className="size-9 text-destructive hover:text-destructive pointer-coarse:size-10"><Trash2 aria-hidden="true" /></Button>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-x-4 gap-y-3 px-3 py-3 sm:grid-cols-2 sm:px-4">
        <Field label={t('dbq.rule.kind')} name={key('kind')} error={err('kind')} className={cn('mb-0', FULL_SELECT)}>
          {({ id, describedBy, invalid }) => (
            <NativeSelect id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} className={CONTROL}
              value={rule.kind} onChange={(e) => onChange(changeKind(rule, e.target.value))}>
              {RULE_KINDS.map((k) => <NativeSelectOption key={k} value={k}>{t(`dbq.rule.kindName.${k}`)}</NativeSelectOption>)}
            </NativeSelect>
          )}
        </Field>
        <Field label={t('dbq.rule.label')} hint={t('dbq.rule.labelHint')} name={key('label')} error={err('label')} className="mb-0">
          {({ id, describedBy, invalid }) => (
            <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} className={CONTROL}
              value={rule.label ?? ''} onChange={(e) => onChange({ ...rule, label: e.target.value })} />
          )}
        </Field>

        {rule.kind === 'CHANGE' && <ChangeFields rule={rule} index={index} disabled={disabled} err={err} onChange={onChange} />}

        {needsColumn(rule) && (
          <Field label={t('dbq.rule.column')} required name={key('column')} error={err('column')}
            hint={columns?.length ? t('dbq.rule.columnHintKnown') : t('dbq.rule.columnHintUnknown')} className="mb-0">
            {({ id, describedBy, invalid }) => (
              <ColumnInput id={id} describedBy={describedBy} invalid={invalid} disabled={disabled} columns={columns}
                value={rule.column ?? ''} onChange={(v) => onChange({ ...rule, column: v || null })} />
            )}
          </Field>
        )}
        {needsAggregate(rule) && (
          <Field label={t('dbq.rule.aggregate')} name={key('aggregate')} error={err('aggregate')} className={cn('mb-0', FULL_SELECT)}>
            {({ id, describedBy, invalid }) => (
              <NativeSelect id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} className={CONTROL}
                value={rule.aggregate || 'FIRST'} onChange={(e) => onChange({ ...rule, aggregate: e.target.value })}>
                {AGGREGATES.map((a) => <NativeSelectOption key={a} value={a}>{t(`dbq.rule.agg.${a}`)}</NativeSelectOption>)}
              </NativeSelect>
            )}
          </Field>
        )}
        {text && (
          <Field label={t('dbq.rule.scope')} name={key('scope')} error={err('scope')} className={cn('mb-0', FULL_SELECT)}>
            {({ id, describedBy, invalid }) => (
              <NativeSelect id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} className={CONTROL}
                value={rule.scope || 'ANY_ROW'} onChange={(e) => onChange({ ...rule, scope: e.target.value })}>
                {SCOPES.map((s) => <NativeSelectOption key={s} value={s}>{t(`dbq.rule.scopeName.${s}`)}</NativeSelectOption>)}
              </NativeSelect>
            )}
          </Field>
        )}
        <Field label={t('dbq.rule.operator')} name={key('operator')} error={err('operator')} className={cn('mb-0', FULL_SELECT)}>
          {({ id, describedBy, invalid }) => (
            <NativeSelect id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} className={CONTROL}
              value={rule.operator || operatorsFor(rule.kind)[0]} onChange={(e) => onChange(changeOperator(rule, e.target.value))}>
              {operatorsFor(rule.kind).map((o) => <NativeSelectOption key={o} value={o}>{t(`dbq.rule.opName.${o}`)}</NativeSelectOption>)}
            </NativeSelect>
          )}
        </Field>
      </div>

      <div className="border-t px-3 py-3 sm:px-4">
        {text
          ? <TextThreshold rule={rule} index={index} disabled={disabled} err={err} onChange={onChange} />
          : (
            <div className="grid grid-cols-1 gap-x-4 gap-y-3 sm:grid-cols-2">
              <ThresholdInput rule={rule} level="warning" index={index} disabled={disabled} err={err} onChange={onChange} lang={lang} />
              <ThresholdInput rule={rule} level="critical" index={index} disabled={disabled} err={err} onChange={onChange} lang={lang} />
            </div>
          )}
        {missing && !errs[key('warning')] && !errs[key('critical')] && (
          <FieldHint tone="warn"><span data-slot="dbq-rule-missing">{t(`dbq.rule.v.${missing}`)}</span></FieldHint>
        )}
        {hint && <p data-slot="dbq-rule-strictness" className="mt-2 mb-0 inline-flex items-start gap-1.5 text-xs text-amber-700 dark:text-amber-300">
          <Lightbulb aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />{t(`dbq.rule.hint.${hint}`)}
        </p>}
      </div>

      <p data-slot="dbq-rule-summary" className="m-0 border-t bg-muted/40 px-3 py-2 text-sm [overflow-wrap:anywhere] sm:px-4">
        <span className="font-semibold text-muted-foreground">{t('dbq.rule.summaryLabel')} </span>{summary}
      </p>
    </Card>
  )
}

/** Kolon girişi — sorgu denendiyse öneri listeli seçici (serbest değer de yazılır), değilse düz metin. */
function ColumnInput({ id, describedBy, invalid, disabled, columns, value, onChange }) {
  const t = useT()
  if (Array.isArray(columns) && columns.length > 0) {
    const opts = columns.map((c) => ({ value: c.name, label: c.name, hint: c.type || undefined }))
    if (value && !opts.some((o) => o.value === value)) opts.unshift({ value, label: value })
    return (
      <SearchableSelect id={id} value={value} onChange={(v) => onChange(String(v ?? ''))} options={opts} creatable wrapLabels
        disabled={disabled} placeholder={t('dbq.rule.columnPick')} ariaLabel={t('dbq.rule.column')} />
    )
  }
  return (
    <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} className={cn(CONTROL, 'font-mono')}
      value={value} onChange={(e) => onChange(e.target.value)} autoComplete="off" spellCheck={false} />
  )
}

/** Değişim kuralının alanları: neyin (satır sayısı / kolon), nasıl (yüzde / mutlak), yön. */
function ChangeFields({ rule, index, disabled, err, onChange }) {
  const t = useT()
  const ch = rule.change || { of: 'ROW_COUNT', mode: 'PERCENT', direction: 'ANY' }
  const set = (patch) => {
    const change = { ...ch, ...patch }
    const next = { ...rule, change }
    if (patch.of === 'COLUMN' && !rule.aggregate) next.aggregate = 'FIRST'
    if (patch.of === 'ROW_COUNT') { next.column = null; next.aggregate = null }
    onChange(next)
  }
  const sel = (label, field, values, keyPrefix) => (
    <Field label={label} name={`rules[${index}].change`} error={field === 'of' ? err('change') : undefined} className={cn('mb-0', FULL_SELECT)}>
      {({ id, describedBy, invalid }) => (
        <NativeSelect id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} className={CONTROL}
          value={ch[field]} onChange={(e) => set({ [field]: e.target.value })}>
          {values.map((v) => <NativeSelectOption key={v} value={v}>{t(`${keyPrefix}.${v}`)}</NativeSelectOption>)}
        </NativeSelect>
      )}
    </Field>
  )
  return (
    <>
      {sel(t('dbq.rule.changeOf'), 'of', CHANGE_OF, 'dbq.rule.changeOfName')}
      {sel(t('dbq.rule.changeMode'), 'mode', CHANGE_MODES, 'dbq.rule.changeModeName')}
      {sel(t('dbq.rule.changeDirection'), 'direction', CHANGE_DIRECTIONS, 'dbq.rule.changeDirName')}
      <p className="m-0 self-end text-xs text-muted-foreground sm:pb-2">{t('dbq.rule.changeHint')}</p>
    </>
  )
}

/** Sayısal eşik (uyarı / kritik) — tek değer ya da aralık [alt, üst]. */
function ThresholdInput({ rule, level, index, disabled, err, onChange, lang }) {
  const t = useT()
  const range = isRangeOperator(rule.operator)
  const percent = rule.kind === 'CHANGE' && rule.change?.mode === 'PERCENT'
  const v = rule[level]
  const label = (
    <span className="inline-flex items-center gap-1.5">
      {level === 'critical'
        ? <Siren aria-hidden="true" className="size-3.5 text-destructive" />
        : <TriangleAlert aria-hidden="true" className="size-3.5 text-amber-600 dark:text-amber-400" />}
      {t(level === 'critical' ? 'dbq.rule.critical' : 'dbq.rule.warning')}
      {percent && <span className="font-normal text-muted-foreground">(%)</span>}
    </span>
  )
  const setPart = (part, val) => {
    if (!range) { onChange({ ...rule, [level]: val }); return }
    const cur = Array.isArray(v) ? v : [v ?? null, null]
    const next = part === 0 ? [val, cur[1] ?? null] : [cur[0] ?? null, val]
    onChange({ ...rule, [level]: next[0] == null && next[1] == null ? null : next })
  }
  return (
    <Field label={label} name={`rules[${index}].${level}`} error={err(level)} hint={t('dbq.rule.numberHint')} className="mb-0">
      {({ id, describedBy, invalid }) => (range ? (
        <div className="grid grid-cols-2 gap-2">
          <NumberInput id={id} describedBy={describedBy} invalid={invalid} disabled={disabled} lang={lang}
            placeholder={t('dbq.rule.rangeLow')} ariaLabel={t('dbq.rule.rangeLowOf', t(`dbq.rule.${level}`))}
            value={Array.isArray(v) ? v[0] : v} onCommit={(x) => setPart(0, x)} />
          <NumberInput describedBy={describedBy} invalid={invalid} disabled={disabled} lang={lang}
            placeholder={t('dbq.rule.rangeHigh')} ariaLabel={t('dbq.rule.rangeHighOf', t(`dbq.rule.${level}`))}
            value={Array.isArray(v) ? v[1] : null} onCommit={(x) => setPart(1, x)} />
        </div>
      ) : (
        <NumberInput id={id} describedBy={describedBy} invalid={invalid} disabled={disabled} lang={lang}
          placeholder={t('dbq.rule.thresholdPlaceholder')} value={v} onCommit={(x) => setPart(0, x)} />
      ))}
    </Field>
  )
}

/** Taslak metinli sayı kutusu: yazılırken biçimlenmez; geçerliyse sayı, değilse metin olarak bildirilir. */
function NumberInput({ id, describedBy, invalid, disabled, lang, placeholder, ariaLabel, value, onCommit }) {
  const [draft, setDraft] = useState(null)
  const matches = (d) => {
    const n = parseNumber(d, lang)
    if (n === null) return value == null || value === ''
    if (Number.isNaN(n)) return value === d
    return n === value
  }
  const shown = draft != null && matches(draft) ? draft : formatInputNumber(value, lang)
  return (
    <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} aria-label={ariaLabel} disabled={disabled}
      inputMode="decimal" autoComplete="off" placeholder={placeholder} className={cn(CONTROL, 'tabular-nums')}
      value={shown}
      onChange={(e) => {
        const raw = e.target.value
        setDraft(raw)
        const n = parseNumber(raw, lang)
        onCommit(n === null ? null : (Number.isNaN(n) ? raw : n))
      }}
      onBlur={() => { if (draft != null && !Number.isNaN(parseNumber(draft, lang))) setDraft(null) }} />
  )
}

/** Metin eşleşmesi: tek seviye (uyarı / kritik) + aranan metin (EMPTY / NOT_EMPTY'de yok). */
function TextThreshold({ rule, index, disabled, err, onChange }) {
  const t = useT()
  const lvl = textLevel(rule)
  const noValue = isNoValueOperator(rule.operator)
  return (
    <div className="grid grid-cols-1 gap-x-4 gap-y-3 sm:grid-cols-2">
      <div className="flex flex-col gap-1.5">
        <span className="text-sm font-semibold">{t('dbq.rule.textLevel')}</span>
        <SegmentedControl value={lvl} ariaLabel={t('dbq.rule.textLevel')} className="w-full" itemClassName="flex-1"
          onChange={(v) => !disabled && onChange(setTextLevel(rule, v))}
          options={[
            { value: 'warning', label: t('dbq.rule.lvl.warning'), icon: TriangleAlert, disabled },
            { value: 'critical', label: t('dbq.rule.lvl.critical'), icon: Siren, disabled },
          ]} />
      </div>
      {noValue
        ? <p className="m-0 self-end text-xs text-muted-foreground sm:pb-2">{t('dbq.rule.noValueHint')}</p>
        : (
          <Field label={t('dbq.rule.textValue')} required name={`rules[${index}].${lvl}`} error={err(lvl)} className="mb-0">
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} className={CONTROL}
                value={rule[lvl] ?? ''} onChange={(e) => onChange({ ...rule, [lvl]: e.target.value })} />
            )}
          </Field>
        )}
    </div>
  )
}
