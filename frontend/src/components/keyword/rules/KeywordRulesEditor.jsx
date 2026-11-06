import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AlertTriangle, ArrowDown, ArrowUp, Check, Info, ListChecks, Lock, Plus, Siren, Trash2 } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Switch } from '@/components/shadcn/switch'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import Field, { FieldError } from '../../ui/Field.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import { CheckField } from '../../monitoring/MonitorForm.jsx'
import {
  OPERATORS, RULE_KINDS, RULE_LIMITS, RULE_LOGICS, RULE_MODES, SCOPE_TYPES,
  applyMode, criticalPossible, defaultCritical, describeRule, family, modeOf, moveRule, newRule, ruleProblemText, validateRules,
} from './ruleModel.js'

/** NativeSelect tam genişlik (kendi sarmalayıcısı `w-fit`). */
const FULL_SELECT = '[&>[data-slot=native-select-wrapper]]:w-full'
const CONTROL = 'h-9 w-full pointer-coarse:h-10'
const ICON_BTN = 'size-9 pointer-coarse:size-10'

/** Alanın sunucu hatası (dizge — istek dilinde) önceliklidir; yoksa istemci kodu (yalnız dokunulmuş alan / kayıt denemesi). */
function fieldText(t, serverMap, clientMap, key, show) {
  const sv = serverMap?.[key]
  if (sv != null && sv !== '') return typeof sv === 'string' ? sv : (sv.message || sv.error || null)
  return show ? ruleProblemText(clientMap?.[key], t) : null
}

/**
 * Anahtar kelime izlemesinin KURAL DÜZENLEYİCİSİ (kw paket C, 2026-11-05). En çok 10 kural kartı: tür (metin / düzenli
 * ifade), aranan metin ya da desen, harf duyarlılığı, koşul (içermeli / içermemeli / adet koşulu), isteğe bağlı İKİNCİ
 * (kritik) eşik, kapsam (tüm sayfa / CSS seçici / JSON alanı), etiket; her kartın altında İNSAN DİLİNDE canlı açıklama
 * (sağlıklı durum + alarm cümleleri — tümü sözlükten, B11). Üstte VE / VEYA seçimi (2+ kuralda) ve tek cümle özet.
 *
 * <p>Sıralama yalnız yukarı / aşağı düğmeleriyle (klavye ve dokunmatikte çalışır; sürükle-bırak YOK) — taşınan kartın
 * düğmesi odakta kalır. Silme onaylıdır. Alan hataları sunucuyla aynı anahtarlarla (`rules[i].<alan>`, `data-field`
 * kancası — `focusFormError` ilk hatalıya kaydırır): sunucu iletisi önce; istemci denetimi (sınırlar, B10, iki eşik ailesi,
 * RE2'nin desteklemediği yapılar) dokunulmuş alanda ya da `showErrors` (kayıt denemesi) açıkken görünür.
 *
 * @param {Array}    rules        kural listesi (API biçimi, snake_case)
 * @param {string}   logic        'AND' | 'OR'
 * @param {Function} onChange     (rules, logic) — her değişiklikte
 * @param {object}   errors       sunucu alan hataları (`{ 'rules[0].pattern': 'ileti' }`)
 * @param {boolean}  showErrors   istemci hatalarını dokunulmamış alanlarda da göster (kayıt denemesinden sonra)
 * @param {string}   monitorLevel izlemenin alarm seviyesi (kritik eşiksiz kuralın alarm cümlesi için)
 * @param {boolean}  disabled     salt okunur
 * @param {string[]} softCodes    bu istemci kodları HATA değil bilgi notu olarak gösterilir (değişmemiş saklı kural — B10,
 *                                son inceleme O3); null = hepsi hata
 * @param {boolean}  criticalLocked  kritik eşik kilitli (paylaşımla düzenleme: eşik alarm SEVİYESİNİ belirler — sunucu
 *                                `KEYWORD_SHARED_LOCKED`, alan `rules`). Kritik eşikli kuralda eşiği DOLAYLI düşüren eylemler de
 *                                kilitlidir (hata avı 2026-11-06): koşul kipi değişimi ("İçermeli" kritiği siler), işlecin yön
 *                                ailesini değiştirmek (kritik geçersiz kalırdı) ve kuralı silmek — not ile söylenir
 */
export default function KeywordRulesEditor({
  rules, logic = 'AND', onChange, errors = null, showErrors = false, monitorLevel = 'WARNING', disabled = false,
  softCodes = null, criticalLocked = false,
}) {
  const t = useT()
  const { showConfirm } = useDialog()
  const list = useMemo(() => (Array.isArray(rules) ? rules : []), [rules])
  const client = useMemo(() => validateRules(list), [list])
  const [touched, setTouched] = useState(() => new Set())
  const rootRef = useRef(null)
  const pendingFocus = useRef(null)
  const atMax = list.length >= RULE_LIMITS.maxRules
  const lg = logic === 'OR' ? 'OR' : 'AND'

  // Taşıma / ekleme / silmeden sonra odak: React düğümü yeniden sıralayınca tarayıcı odağı düşürür — kart kimliğiyle geri ver
  useEffect(() => {
    const want = pendingFocus.current
    if (!want || !rootRef.current) return
    pendingFocus.current = null
    const el = rootRef.current.querySelector(want.selector)
    const target = el && !el.disabled ? el : (want.fallback ? rootRef.current.querySelector(want.fallback) : null)
    target?.focus?.()
  })

  const emit = useCallback((next, nextLogic = lg) => onChange?.(next, nextLogic), [onChange, lg])
  const touch = useCallback((key) => setTouched((s) => (s.has(key) ? s : new Set(s).add(key))), [])
  const update = (i, next) => emit(list.map((r, j) => (j === i ? next : r)))

  const add = () => {
    if (atMax || disabled) return
    const r = newRule(list.map((x) => x.id))
    pendingFocus.current = { selector: `[data-rule-id="${r.id}"] [data-slot="kw-rule-pattern"]` }
    emit([...list, r])
  }
  const move = (i, d) => {
    const r = list[i]
    const dir = d < 0 ? 'up' : 'down'
    pendingFocus.current = {
      selector: `[data-rule-id="${r.id}"] [data-move="${dir}"]`,
      fallback: `[data-rule-id="${r.id}"] [data-move="${dir === 'up' ? 'down' : 'up'}"]`,
    }
    emit(moveRule(list, i, d))
  }
  const remove = async (i) => {
    const r = list[i]
    const name = r?.label?.trim() || t('keyword.rule.cardTitle', i + 1)
    const ok = await showConfirm({
      title: t('keyword.rule.deleteTitle'), message: t('keyword.rule.deleteBody', name),
      confirmText: t('keyword.rule.deleteConfirm'), variant: 'danger',
    })
    if (!ok) return
    const next = list.filter((_, j) => j !== i)
    const neighbour = next[Math.min(i, next.length - 1)]
    pendingFocus.current = neighbour
      ? { selector: `[data-rule-id="${neighbour.id}"] [data-slot="kw-rule-title"]` }
      : { selector: '[data-slot="kw-rule-add"]' }
    emit(next)
  }

  return (
    <div ref={rootRef} data-slot="kw-rules-editor" data-field="rules" className="flex min-w-0 flex-col gap-3">
      {list.length > 1 && (
        <div data-slot="kw-rule-logic" className="flex flex-col gap-2 rounded-lg border bg-muted/30 p-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex min-w-0 flex-col gap-0.5">
            <span className="text-sm font-semibold">{t('keyword.rule.logicLabel')}</span>
            <span data-slot="kw-rule-logic-hint" className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t(`keyword.rule.logicHint.${lg}`)}</span>
          </div>
          <SegmentedControl value={lg} onChange={(v) => !disabled && emit(list, v)} ariaLabel={t('keyword.rule.logicLabel')}
            className="w-full shrink-0 sm:w-auto" itemClassName="flex-1 sm:flex-none"
            options={RULE_LOGICS.map((v) => ({ value: v, label: t(`keyword.rule.logic.${v}`), disabled }))} />
        </div>
      )}

      {fieldText(t, errors, client, 'rules', true) && (
        <FieldError>{fieldText(t, errors, client, 'rules', true)}</FieldError>
      )}

      <ol className="m-0 flex list-none flex-col gap-3 p-0">
        {list.map((r, i) => (
          <li key={r.id || `idx-${i}`} data-rule-id={r.id}>
            <RuleCard rule={r} index={i} count={list.length} disabled={disabled} monitorLevel={monitorLevel}
              serverErrors={errors} client={client} showErrors={showErrors} touched={touched} onTouch={touch}
              softCodes={softCodes} criticalLocked={criticalLocked}
              onChange={(next) => update(i, next)} onRemove={() => remove(i)} onMove={(d) => move(i, d)} />
          </li>
        ))}
      </ol>

      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" variant="outline" onClick={add} disabled={disabled || atMax} data-slot="kw-rule-add" className="pointer-coarse:h-10">
          <Plus aria-hidden="true" />{t('keyword.rule.add')}
        </Button>
        <span data-slot="kw-rule-count" className="text-xs text-muted-foreground tabular-nums">
          {atMax ? t('keyword.rule.maxReached', RULE_LIMITS.maxRules) : t('keyword.rule.countOf', list.length, RULE_LIMITS.maxRules)}
        </span>
      </div>
    </div>
  )
}

/** Düğme grubu satırı — görünür başlık + seçim (SegmentedControl kendi erişilebilir adını taşır; etiket `for` bağlamaz). */
function ChoiceRow({ label, field, error, children }) {
  return (
    <div data-field={field} className="flex min-w-0 flex-col gap-1.5">
      <span className="text-sm font-semibold">{label}</span>
      {children}
      {error && <FieldError>{error}</FieldError>}
    </div>
  )
}

/** Tek kural kartı. */
function RuleCard({
  rule, index, count, disabled, monitorLevel, serverErrors, client, showErrors, touched, onTouch, onChange, onRemove, onMove,
  softCodes = null, criticalLocked = false,
}) {
  const t = useT()
  // Koşul kipi kullanıcı seçimidir (ör. "Adet koşulu"nda GTE 1 yazmak kipi "İçermeli"ye çevirmemeli) — ilk değer kuraldan
  const [mode, setMode] = useState(() => modeOf(rule))
  const key = (alt) => `rules[${index}].${alt}`
  // Bilgi tonundaki istemci kodu (O3): hata olarak çizilmez, kartın uyarı kenarlığını yakmaz — sunucu iletisi yine önce gelir
  const isSoft = (k) => !!(softCodes && client?.[k] && softCodes.includes(client[k].code) && !serverErrors?.[k])
  const err = (alt) => (isSoft(key(alt)) ? null : fieldText(t, serverErrors, client, key(alt), showErrors || touched.has(key(alt))))
  const softNotes = softCodes
    ? Object.keys(client || {}).filter((k) => k.startsWith(`rules[${index}].`) && isSoft(k)).map((k) => ruleProblemText(client[k], t))
    : []
  const critDisabled = disabled || criticalLocked
  // Kritik eşikli kural paylaşımla düzenlenirken eşiği dolaylı düşüren eylemler kilitli: kip, işleç ailesi, silme
  const critHeld = criticalLocked && !!rule.critical
  const blur = (alt) => () => onTouch(key(alt))
  const title = rule.label?.trim() || t('keyword.rule.cardTitle', index + 1)
  const titleId = `kw-rule-${rule.id}-title`
  const desc = describeRule(rule, t, monitorLevel)
  const fam = family(rule.operator)
  const showCritical = !!rule.critical || criticalPossible(rule)
  const scopeType = rule.scope?.type || 'PAGE'
  const hasErr = Object.keys(serverErrors || {}).some((k) => k.startsWith(`${key('')}`))
    || (showErrors && Object.keys(client).some((k) => k.startsWith(`rules[${index}].`) && !isSoft(k)))
  // Kartın çizmediği bir alana ait sunucu hatası (kimlik, kural nesnesinin kendisi) kartın başında listelenir — kaybolmasın
  const drawn = new Set(['kind', 'pattern', 'count', 'operator', 'critical', 'scope', 'label', 'case_sensitive'])
  const orphan = Object.entries(serverErrors || {})
    .filter(([k, v]) => v && (k === `rules[${index}]` || (k.startsWith(`rules[${index}].`) && !drawn.has(k.slice(`rules[${index}].`.length)))))
  const clientId = (showErrors || touched.has(key('id'))) && client[key('id')] ? ruleProblemText(client[key('id')], t) : null

  const setKind = (kind) => onChange({ ...rule, kind })
  const pickMode = (m) => { setMode(m); onChange(applyMode(rule, m)) }
  const setOperator = (op) => {
    if (critHeld && family(op) !== family(rule.operator)) return   // kilitli kritik eşik dolaylı düşürülmez
    // Aile değişince kritik eşik geçersiz kalırdı — aynı ailedeki karşılığına çevrilir (yön korunur)
    const crit = rule.critical && family(op) !== family(rule.operator) ? null : rule.critical
    onChange({ ...rule, operator: op, critical: op === 'EQ' ? null : crit })
  }
  const toggleCritical = (on) => onChange({ ...rule, critical: on ? defaultCritical(rule) : null })
  const setScope = (type) => onChange({ ...rule, scope: type === 'PAGE' ? null : { type, value: rule.scope?.type === type ? rule.scope.value : '' } })
  const critOps = fam === 'upper' ? ['LTE', 'LT'] : ['GTE', 'GT']

  return (
    <Card role="group" aria-labelledby={titleId} data-slot="kw-rule-card" data-index={index} data-kind={rule.kind}
      className={cn('gap-0 py-0 shadow-none', hasErr && 'border-destructive/60')}>
      <div className="flex flex-wrap items-center gap-2 border-b px-3 py-2 sm:px-4">
        <span id={titleId} data-slot="kw-rule-title" tabIndex={-1} className="min-w-0 flex-1 text-sm font-semibold [overflow-wrap:anywhere] focus-visible:outline-none">
          {title}
        </span>
        <div className="flex items-center gap-1">
          <Button type="button" variant="ghost" size="icon" data-move="up" disabled={disabled || index === 0} onClick={() => onMove(-1)}
            aria-label={t('keyword.rule.moveUp', title)} className={ICON_BTN}><ArrowUp aria-hidden="true" /></Button>
          <Button type="button" variant="ghost" size="icon" data-move="down" disabled={disabled || index === count - 1} onClick={() => onMove(1)}
            aria-label={t('keyword.rule.moveDown', title)} className={ICON_BTN}><ArrowDown aria-hidden="true" /></Button>
          <Button type="button" variant="ghost" size="icon" data-slot="kw-rule-delete" disabled={disabled || count <= 1 || critHeld} onClick={onRemove}
            aria-label={t('keyword.rule.delete', title)} className={cn(ICON_BTN, 'text-destructive hover:text-destructive')}><Trash2 aria-hidden="true" /></Button>
        </div>
      </div>

      {(orphan.length > 0 || clientId) && (
        <div data-slot="kw-rule-orphan-errors" className="flex flex-col gap-1 border-b px-3 py-2 sm:px-4">
          {orphan.map(([k, v]) => <div key={k} data-field={k}><FieldError>{typeof v === 'string' ? v : (v.message || v.error)}</FieldError></div>)}
          {clientId && <div data-field={key('id')}><FieldError>{clientId}</FieldError></div>}
        </div>
      )}

      <div className="grid grid-cols-1 gap-x-4 gap-y-3 px-3 py-3 sm:grid-cols-2 sm:px-4">
        <ChoiceRow label={t('keyword.rule.kind')} field={key('kind')} error={err('kind')}>
          <SegmentedControl value={rule.kind} onChange={(v) => !disabled && setKind(v)} ariaLabel={t('keyword.rule.kind')}
            className="w-full" itemClassName="flex-1"
            options={RULE_KINDS.map((k) => ({ value: k, label: t(`keyword.rule.kindName.${k}`), disabled }))} />
        </ChoiceRow>
        <ChoiceRow label={t('keyword.rule.condition')} field={key('mode')}>
          {/* Uzun EN etiketleri (640–767 px yarım sütun) kırpılmaz — satır kaydırır */}
          <SegmentedControl value={mode} onChange={(v) => !disabled && !critHeld && pickMode(v)} ariaLabel={t('keyword.rule.condition')}
            className="w-full" itemClassName="h-auto min-h-7 flex-1 px-2 py-1 leading-tight whitespace-normal"
            options={RULE_MODES.map((m) => ({ value: m, label: t(`keyword.rule.mode.${m}`), disabled: disabled || (critHeld && m !== mode) }))} />
        </ChoiceRow>
        {critHeld && (
          <p data-slot="kw-rule-critical-held" className="m-0 flex min-w-0 items-start gap-1.5 text-xs text-muted-foreground sm:col-span-2 [overflow-wrap:anywhere]">
            <Lock aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />{t('keyword.rule.criticalHeld')}
          </p>
        )}

        <Field label={t(`keyword.rule.pattern.${rule.kind}`)} required hint={t(`keyword.rule.patternHint.${rule.kind}`)}
          name={key('pattern')} error={err('pattern')} className="mb-0 sm:col-span-2">
          {({ id, describedBy, invalid }) => (
            <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} data-slot="kw-rule-pattern"
              className={cn(CONTROL, 'font-mono')} value={rule.pattern ?? ''} autoComplete="off" spellCheck={false}
              placeholder={rule.kind === 'REGEX' ? 'Sipariş no: \\d{6}' : 'SUCCESS'}
              onChange={(e) => onChange({ ...rule, pattern: e.target.value })} onBlur={blur('pattern')} />
          )}
        </Field>
        <CheckField checked={!!rule.case_sensitive} disabled={disabled} label={t('keyword.rule.caseSensitive')}
          onCheckedChange={(v) => onChange({ ...rule, case_sensitive: v })} className="sm:col-span-2" />

        {mode === 'count' && (
          <>
            <Field label={t('keyword.rule.operator')} name={key('operator')} error={err('operator')} className={cn('mb-0', FULL_SELECT)}>
              {({ id, describedBy, invalid }) => (
                <NativeSelect id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} className={CONTROL}
                  value={rule.operator} onChange={(e) => setOperator(e.target.value)}>
                  {/* Kritik eşik kilitliyken yalnız aynı yön ailesinin işleçleri (kritik eşik geçerli kalır) */}
                  {OPERATORS.filter((o) => !critHeld || family(o) === fam).map((o) => (
                    <NativeSelectOption key={o} value={o}>{t(`keyword.rule.op.${o}`)}</NativeSelectOption>
                  ))}
                </NativeSelect>
              )}
            </Field>
            <Field label={t('keyword.rule.count')} name={key('count')} error={err('count')} className="mb-0">
              {({ id, describedBy, invalid }) => (
                <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} inputMode="numeric"
                  className={cn(CONTROL, 'tabular-nums')} value={rule.count ?? ''} data-slot="kw-rule-count-input"
                  onChange={(e) => onChange({ ...rule, count: e.target.value })} onBlur={blur('count')} />
              )}
            </Field>
          </>
        )}
        {mode !== 'count' && err('count') && (
          <div data-field={key('count')} className="sm:col-span-2"><FieldError>{err('count')}</FieldError></div>
        )}
        {softNotes.length > 0 && (
          <p data-slot="kw-rule-notice" className="m-0 flex min-w-0 items-start gap-1.5 text-xs text-muted-foreground sm:col-span-2 [overflow-wrap:anywhere]">
            <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
            <span>{softNotes.join(' ')} {t('keyword.rule.softNote')}</span>
          </p>
        )}

        {showCritical && (
          <div data-slot="kw-rule-critical" data-field={key('critical')} className="flex min-w-0 flex-col gap-2 rounded-lg border bg-muted/30 p-3 sm:col-span-2">
            <label className="flex min-w-0 cursor-pointer items-start gap-3">
              <Switch checked={!!rule.critical} disabled={critDisabled} onCheckedChange={toggleCritical} aria-label={t('keyword.rule.critical')}
                data-slot="kw-rule-critical-toggle" className="mt-0.5 shrink-0" />
              <span className="flex min-w-0 flex-col gap-0.5">
                <span className="inline-flex items-center gap-1.5 text-sm font-semibold"><Siren aria-hidden="true" className="size-4 shrink-0 text-destructive" />{t('keyword.rule.critical')}</span>
                <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('keyword.rule.criticalHint')}</span>
              </span>
            </label>
            {criticalLocked && (
              <p data-slot="kw-rule-critical-locked" className="m-0 flex min-w-0 items-start gap-1.5 text-xs text-muted-foreground [overflow-wrap:anywhere]">
                <Lock aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />{t('keyword.rule.criticalLocked')}
              </p>
            )}
            {rule.critical && (
              <div className="grid grid-cols-1 gap-x-4 gap-y-3 sm:grid-cols-2">
                <Field label={t('keyword.rule.criticalOperator')} className={cn('mb-0', FULL_SELECT)}>
                  {({ id }) => (
                    <NativeSelect id={id} disabled={critDisabled} className={CONTROL} value={critOps.includes(rule.critical.operator) ? rule.critical.operator : critOps[0]}
                      onChange={(e) => onChange({ ...rule, critical: { ...rule.critical, operator: e.target.value } })}>
                      {critOps.map((o) => <NativeSelectOption key={o} value={o}>{t(`keyword.rule.op.${o}`)}</NativeSelectOption>)}
                    </NativeSelect>
                  )}
                </Field>
                <Field label={t('keyword.rule.criticalCount')} error={err('critical')} className="mb-0">
                  {({ id, describedBy, invalid }) => (
                    <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={critDisabled} inputMode="numeric"
                      className={cn(CONTROL, 'tabular-nums')} value={rule.critical.count ?? ''} data-slot="kw-rule-critical-count"
                      onChange={(e) => onChange({ ...rule, critical: { ...rule.critical, count: e.target.value } })} onBlur={blur('critical')} />
                  )}
                </Field>
              </div>
            )}
            {!rule.critical && err('critical') && <FieldError>{err('critical')}</FieldError>}
          </div>
        )}

        <Field label={t('keyword.rule.scope')} name={scopeType === 'PAGE' ? key('scope') : undefined} error={scopeType === 'PAGE' ? err('scope') : null} className={cn('mb-0', FULL_SELECT)}>
          {({ id, describedBy, invalid }) => (
            <NativeSelect id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} className={CONTROL}
              value={scopeType} onChange={(e) => setScope(e.target.value)} data-slot="kw-rule-scope">
              {SCOPE_TYPES.map((s) => <NativeSelectOption key={s} value={s}>{t(`keyword.rule.scopeName.${s}`)}</NativeSelectOption>)}
            </NativeSelect>
          )}
        </Field>
        {scopeType !== 'PAGE' ? (
          <Field label={t(`keyword.rule.scopeValue.${scopeType}`)} required hint={t(`keyword.rule.scopeHint.${scopeType}`)}
            name={key('scope')} error={err('scope')} className="mb-0">
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} data-slot="kw-rule-scope-value"
                className={cn(CONTROL, 'font-mono')} value={rule.scope?.value ?? ''} autoComplete="off" spellCheck={false}
                placeholder={scopeType === 'CSS' ? '#durum' : '$.status'}
                onChange={(e) => onChange({ ...rule, scope: { type: scopeType, value: e.target.value } })} onBlur={blur('scope')} />
            )}
          </Field>
        ) : (
          <Field label={t('keyword.rule.label')} hint={t('keyword.rule.labelHint')} name={key('label')} error={err('label')} className="mb-0">
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} className={CONTROL}
                value={rule.label ?? ''} data-slot="kw-rule-label"
                onChange={(e) => onChange({ ...rule, label: e.target.value })} onBlur={blur('label')} />
            )}
          </Field>
        )}
        {scopeType !== 'PAGE' && (
          <Field label={t('keyword.rule.label')} hint={t('keyword.rule.labelHint')} name={key('label')} error={err('label')} className="mb-0 sm:col-span-2">
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} className={CONTROL}
                value={rule.label ?? ''} data-slot="kw-rule-label"
                onChange={(e) => onChange({ ...rule, label: e.target.value })} onBlur={blur('label')} />
            )}
          </Field>
        )}
      </div>

      <div data-slot="kw-rule-desc" role="note" className="flex flex-col gap-1 border-t bg-muted/40 px-3 py-2 text-sm sm:px-4">
        {desc.healthy && (
          <p className="m-0 flex min-w-0 items-start gap-1.5"><Check aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 text-success" />
            <span data-slot="kw-rule-desc-healthy" className="min-w-0 [overflow-wrap:anywhere]">{desc.healthy}</span></p>
        )}
        {desc.warning && (
          <p className="m-0 flex min-w-0 items-start gap-1.5"><AlertTriangle aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 text-amber-600 dark:text-amber-400" />
            <span data-slot="kw-rule-desc-warning" className="min-w-0 [overflow-wrap:anywhere]">{desc.warning}</span></p>
        )}
        {desc.critical && (
          <p className="m-0 flex min-w-0 items-start gap-1.5"><Siren aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 text-destructive" />
            <span data-slot="kw-rule-desc-critical" className="min-w-0 [overflow-wrap:anywhere]">{desc.critical}</span></p>
        )}
        {!desc.healthy && !desc.warning && (
          <p className="m-0 flex items-center gap-1.5 text-muted-foreground"><ListChecks aria-hidden="true" className="size-3.5 shrink-0" />{t('keyword.rule.descPending')}</p>
        )}
      </div>
    </Card>
  )
}
