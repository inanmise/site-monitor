import { useCallback, useMemo, useState } from 'react'
import { ArrowDown, ArrowUp, ListChecks, Plus, Trash2, TriangleAlert, Siren, Lightbulb, Wand2 } from 'lucide-react'
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
  AGE_UNITS, CHANGE_BASELINES, CHANGE_DIRECTIONS, CHANGE_MODES, CHANGE_OF, MAX_RULES, RULE_KINDS, RULE_LOGICS, SCOPES,
  ageUnitFor, aggregatesFor, changeBaseline, changeKind, changeOperator, formatInputNumber, isFreshnessKind, isNoValueOperator,
  isRangeOperator, isTextKind, moveRule, needsAggregate, needsColumn, newRule, operatorsFor, parseNumber, ruleSummary, setTextLevel,
  strictnessHint, textLevel, unitSeconds, validateRule,
} from './ruleModel.js'
import { toColumnValueRule } from './ruleSuggest.js'

/** NativeSelect tam genişlik (kendi sarmalayıcısı `w-fit`). */
const FULL_SELECT = '[&>[data-slot=native-select-wrapper]]:w-full'
const CONTROL = 'h-9 w-full pointer-coarse:h-10'

/** Sunucu alan hatası değeri → metin: düz ileti olduğu gibi; `{ message, code }` biçiminde gelirse ileti. */
export function serverText(v) {
  if (v == null || v === '') return null
  if (typeof v === 'string') return v
  if (typeof v === 'object') return v.message || v.error || v.code || null
  return String(v)
}

/** Sunucu ya da istemci hata kodu → metin. Sunucu iletisi ("Kural N+1 …", istek dilinde) önceliklidir. */
function errorText(t, server, clientCode) {
  const sv = serverText(server)
  if (sv) return sv
  return clientCode ? t(`dbq.rule.v.${clientCode}`) : null
}

/**
 * Kartta bir alanın altında GÖSTERİLEN alt anahtarlar (`rules[N].<alt>`). Burada olmayan sunucu hataları (kuralın kendisi
 * `rules[N]` — DBQUERY_RULE_INVALID, kimlik `rules[N].id` — DBQUERY_RULE_ID, şu an çizilmeyen bir alanın hatası) kartın
 * başında listelenir: sunucunun söylediği hiçbir şey kaybolmaz.
 */
export function renderedRuleFields(rule) {
  const out = new Set(['kind', 'label', 'operator'])
  if (!rule || !RULE_KINDS.includes(rule.kind)) return out
  if (needsColumn(rule)) out.add('column')
  if (needsAggregate(rule)) out.add('aggregate')
  if (rule.kind === 'CHANGE') out.add('change')
  if (isTextKind(rule.kind)) {
    out.add('scope')
    if (!isNoValueOperator(rule.operator)) out.add(textLevel(rule))
  } else {
    out.add('warning'); out.add('critical')
  }
  return out
}

/**
 * Kural oluşturucu (Veritabanı Sorgu İzleme, 2026-10-31). Her kural bir kart: tür (Satır sayısı / Kolon değeri / Metin
 * eşleşmesi / Değişim), kolon (sorgu denendiyse önerilir — serbest giriş de), toplama, işleç, iki seviyeli eşik (uyarı +
 * kritik; aralık işleçlerinde alt / üst), metin eşleşmesinde kapsam + tek seviye, değişimde neyin / nasıl / hangi yönde.
 * Kartın altında İNSAN DİLİNDE özet. ≤ 10 kural; ANY / ALL birleşimi. Alan hataları sunucuyla aynı anahtarlarla
 * (`rules`, `rules[N].<alt>`) ilgili alanın altında; istemci doğrulaması (sayı biçimi TR "1.250,5" / EN "1,250.5", aralık,
 * en az bir eşik) anında. Yazılan sayı yazılırken yeniden biçimlenmez (taslak metin), geçersiz metin sessizce yutulmaz:
 * kurala METİN olarak geçer → sunucu da alan hatası döner.
 *
 * <p>2026-10-31 (r177): Tazelik (FRESHNESS — zaman damgası kolonunun yaşı, eşik süre birimiyle), değişimde taban seçimi
 * (önceki çalışma / dün aynı saat / geçen hafta aynı saat) ve COUNT(*) tuzağı: `trapIndexes` (sayfanın hesapladığı —
 * sunucu uyarısı `ROW_COUNT_ON_AGGREGATE` ya da deneme kanıtı) içindeki "satır sayısı" kuralının kartında uyarı + "Kolon
 * değeri kuralına çevir" (`trapColumn` — denemedeki sayısal kolon; yoksa kolonu kullanıcı seçer). `newRuleFactory`:
 * "Kural ekle"nin varsayılanı (verilmezse satır sayısı).
 */
export default function RuleBuilder({
  rules, logic, onChange, onLogicChange, columns, fieldErrors, disabled = false,
  trapIndexes = null, trapColumn = null, newRuleFactory = null,
}) {
  const t = useT()
  const { showConfirm } = useDialog()
  const list = useMemo(() => (Array.isArray(rules) ? rules : []), [rules])
  const errs = fieldErrors || {}
  const atMax = list.length >= MAX_RULES
  const traps = useMemo(() => new Set(trapIndexes ? [...trapIndexes] : []), [trapIndexes])

  const update = useCallback((i, next) => {
    onChange?.(list.map((r, j) => (j === i ? next : r)))
  }, [list, onChange])

  const add = () => { if (!atMax) onChange?.([...list, (newRuleFactory && newRuleFactory()) || newRule('ROW_COUNT')]) }
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

      {serverText(errs.rules) && <FieldError>{serverText(errs.rules)}</FieldError>}

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
              trap={traps.has(i) && r?.kind === 'ROW_COUNT'} trapColumn={trapColumn}
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
function RuleCard({ rule, index, count, columns, disabled, errs, onChange, onRemove, onMove, trap = false, trapColumn = null }) {
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
  const shownFields = renderedRuleFields(rule)
  const prefix = `rules[${index}]`
  const orphanErrors = Object.entries(errs)
    .filter(([k]) => k === prefix || (k.startsWith(`${prefix}.`) && !shownFields.has(k.slice(prefix.length + 1))))
    .map(([k, v]) => [k, serverText(v)])
    .filter(([, v]) => v)

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

      {trap && (
        // COUNT(*) tuzağı (2.A / 2.C): tek satır dönen özet sorguda "satır sayısı" hep 1 — kural hiç tetiklenmez
        <div data-slot="dbq-rule-count-trap" role="note"
          className="flex min-w-0 flex-col gap-2 border-b border-amber-500/40 bg-amber-500/5 px-3 py-2.5 text-sm sm:flex-row sm:items-start sm:px-4 dark:bg-amber-500/10">
          <p className="m-0 flex min-w-0 flex-1 items-start gap-1.5 text-amber-800 dark:text-amber-200">
            <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
            <span className="[overflow-wrap:anywhere]">{t(trapColumn ? 'dbq.rule.trap.bodyColumn' : 'dbq.rule.trap.body', trapColumn || '')}</span>
          </p>
          <Button type="button" variant="outline" size="sm" disabled={disabled} data-slot="dbq-rule-convert"
            className="shrink-0 self-start pointer-coarse:h-10"
            onClick={() => onChange(toColumnValueRule(rule, trapColumn))}>
            <Wand2 aria-hidden="true" />{t('dbq.rule.trap.convert')}
          </Button>
        </div>
      )}

      {orphanErrors.length > 0 && (
        <div data-slot="dbq-rule-server-errors" className="flex flex-col gap-1 border-b px-3 py-2 sm:px-4">
          {orphanErrors.map(([k, v]) => <div key={k} data-field={k}><FieldError>{v}</FieldError></div>)}
        </div>
      )}

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
            hint={isFreshnessKind(rule.kind) ? t('dbq.rule.columnHintTime')
              : columns?.length ? t('dbq.rule.columnHintKnown') : t('dbq.rule.columnHintUnknown')} className="mb-0">
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
                value={rule.aggregate || aggregatesFor(rule.kind)[0]} onChange={(e) => onChange({ ...rule, aggregate: e.target.value })}>
                {aggregatesFor(rule.kind).map((a) => (
                  <NativeSelectOption key={a} value={a}>{t(isFreshnessKind(rule.kind) ? `dbq.rule.aggFresh.${a}` : `dbq.rule.agg.${a}`)}</NativeSelectOption>
                ))}
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
          : isFreshnessKind(rule.kind)
            ? <FreshnessThresholds rule={rule} index={index} disabled={disabled} err={err} onChange={onChange} lang={lang} />
            : (
            <div className="grid grid-cols-1 gap-x-4 gap-y-3 sm:grid-cols-2">
              <ThresholdInput rule={rule} level="warning" index={index} disabled={disabled} err={err} onChange={onChange} lang={lang} />
              <ThresholdInput rule={rule} level="critical" index={index} disabled={disabled} err={err} onChange={onChange} lang={lang} />
            </div>
          )}
        {missing && !serverText(errs[key('warning')]) && !serverText(errs[key('critical')]) && (
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
        disabled={disabled} placeholder={t('dbq.rule.columnPick')} ariaLabel={t('dbq.rule.column')}
        ariaDescribedBy={describedBy} ariaInvalid={invalid} />
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
  const sel = (label, field, values, keyPrefix, { value = ch[field], hint } = {}) => (
    <Field label={label} name={`rules[${index}].change`} error={field === 'of' ? err('change') : undefined} hint={hint}
      className={cn('mb-0', FULL_SELECT)}>
      {({ id, describedBy, invalid }) => (
        <NativeSelect id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} className={CONTROL}
          value={value} onChange={(e) => set({ [field]: e.target.value })} data-change-field={field}>
          {values.map((v) => <NativeSelectOption key={v} value={v}>{t(`${keyPrefix}.${v}`)}</NativeSelectOption>)}
        </NativeSelect>
      )}
    </Field>
  )
  const baseline = changeBaseline(rule)
  return (
    <>
      {sel(t('dbq.rule.changeOf'), 'of', CHANGE_OF, 'dbq.rule.changeOfName')}
      {sel(t('dbq.rule.changeMode'), 'mode', CHANGE_MODES, 'dbq.rule.changeModeName')}
      {sel(t('dbq.rule.changeDirection'), 'direction', CHANGE_DIRECTIONS, 'dbq.rule.changeDirName')}
      {/* Mevsimsel karşılaştırma (2.D): taban = hedef zamana en yakın zamanlanmış başarılı çalışma (±yarım aralık, ≤ 30 dk) */}
      {sel(t('dbq.rule.baseline'), 'baseline', CHANGE_BASELINES, 'dbq.rule.baselineName', {
        value: baseline, hint: t(`dbq.rule.baselineHint.${baseline}`),
      })}
      <p className="m-0 self-end text-xs text-muted-foreground sm:col-span-2 sm:pb-2">{t('dbq.rule.changeHint')}</p>
    </>
  )
}

/**
 * Tazelik eşikleri (2.D): kural SANİYE taşır; kutular seçilen birimle (sn / dk / sa / gün) gösterir. Başlangıç birimi
 * eşiklerin hepsini tam bölen en büyük birim (900 + 3600 → dk); yazılan değer birimle çarpılıp tam saniyeye yuvarlanır.
 * Yaş = veritabanı saatine göre (kolonun saat dilimi yoksa veritabanının saat dilimiyle yorumlanır).
 */
function FreshnessThresholds({ rule, index, disabled, err, onChange, lang }) {
  const t = useT()
  const [unit, setUnit] = useState(() => ageUnitFor(rule))
  const factor = unitSeconds(unit)
  const toDisplay = (v) => (typeof v === 'number' && Number.isFinite(v) ? v / factor : v)
  const commit = (level) => (x) => onChange({ ...rule, [level]: typeof x === 'number' ? Math.round(x * factor) : x })
  return (
    <div data-slot="dbq-rule-freshness" className="flex min-w-0 flex-col gap-3">
      <Field label={t('dbq.rule.ageUnit')} name={`rules[${index}].unit`} hint={t('dbq.rule.ageHint')} className={cn('mb-0 sm:max-w-xs', FULL_SELECT)}>
        {({ id, describedBy }) => (
          <NativeSelect id={id} aria-describedby={describedBy} disabled={disabled} className={CONTROL} value={unit}
            onChange={(e) => setUnit(e.target.value)} data-slot="dbq-rule-age-unit">
            {AGE_UNITS.map((u) => <NativeSelectOption key={u.key} value={u.key}>{t(`dbq.rule.unit.${u.key}`)}</NativeSelectOption>)}
          </NativeSelect>
        )}
      </Field>
      <Field label={t('dbq.rule.timezone')} name={`rules[${index}].timezone`} error={err('timezone')} hint={t('dbq.rule.timezoneHint')}
        className="mb-0 sm:max-w-xs">
        {({ id, describedBy }) => (
          <Input id={id} aria-describedby={describedBy} disabled={disabled} className={CONTROL} data-slot="dbq-rule-timezone"
            value={rule.timezone ?? ''} placeholder="Europe/Istanbul" autoComplete="off" spellCheck={false}
            onChange={(e) => {
              const v = e.target.value
              const next = { ...rule }
              if (v.trim()) next.timezone = v.trim(); else delete next.timezone
              onChange(next)
            }} />
        )}
      </Field>
      <div className="grid grid-cols-1 gap-x-4 gap-y-3 sm:grid-cols-2">
        {['warning', 'critical'].map((level) => (
          <Field key={level} name={`rules[${index}].${level}`} error={err(level)} hint={t('dbq.rule.numberHint')} className="mb-0"
            label={(
              <span className="inline-flex items-center gap-1.5">
                {level === 'critical'
                  ? <Siren aria-hidden="true" className="size-3.5 text-destructive" />
                  : <TriangleAlert aria-hidden="true" className="size-3.5 text-amber-600 dark:text-amber-400" />}
                {t(level === 'critical' ? 'dbq.rule.critical' : 'dbq.rule.warning')}
                <span className="font-normal text-muted-foreground">({t(`dbq.rule.unit.${unit}`)})</span>
              </span>
            )}>
            {({ id, describedBy, invalid }) => (
              <NumberInput id={id} describedBy={describedBy} invalid={invalid} disabled={disabled} lang={lang}
                placeholder={t('dbq.rule.thresholdPlaceholder')} value={toDisplay(rule[level])} onCommit={commit(level)} />
            )}
          </Field>
        ))}
      </div>
    </div>
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
