import { Fragment, useId } from 'react'
import { Lock } from 'lucide-react'
import Field from '../../ui/Field.jsx'
import { helpLabel } from '../SettingsControls.jsx'
import { InputGroup, InputGroupAddon, InputGroupInput, InputGroupText } from '@/components/shadcn/input-group'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { FIELDS } from './laModel.js'

/**
 * Login Anomali sayfasının sunum yardımcıları. Metin anahtarları LİTERAL `t('…')` çağrıları — i18n-used-keys kapısı
 * dinamik anahtarı (`'x.' + k`) göremez; her eşleme burada tek yerde.
 */

/** Kural kodu → kısa ad (olay rozetleri + alan rozetleri); bilinmeyen kod olduğu gibi. */
export function ruleLabel(t, code) {
  switch (code) {
    case 'GLOBAL_VOLUME': return t('loginAnomaly.rule.GLOBAL_VOLUME')
    case 'ACCOUNT_TARGETED': return t('loginAnomaly.rule.ACCOUNT_TARGETED')
    case 'IP_BRUTE_FORCE': return t('loginAnomaly.rule.IP_BRUTE_FORCE')
    case 'IP_CREDENTIAL_STUFFING': return t('loginAnomaly.rule.IP_CREDENTIAL_STUFFING')
    case 'DISTRIBUTED': return t('loginAnomaly.rule.DISTRIBUTED')
    case 'RELATIVE_SPIKE': return t('loginAnomaly.rule.RELATIVE_SPIKE')
    default: return code
  }
}

export function unitLabel(t, unit) {
  switch (unit) {
    case 'attempts': return t('loginAnomaly.unit.attempts')
    case 'accounts': return t('loginAnomaly.unit.accounts')
    case 'ips': return t('loginAnomaly.unit.ips')
    case 'times': return t('loginAnomaly.unit.times')
    case 'hours': return t('loginAnomaly.unit.hours')
    case 'minutes': return t('loginAnomaly.unit.minutes')
    case 'days': return t('loginAnomaly.unit.days')
    default: return ''
  }
}

export function fieldLabel(t, key) {
  switch (key) {
    case 'threshold_per_account': return t('loginAnomaly.lbl.threshold_per_account')
    case 'threshold_per_ip': return t('loginAnomaly.lbl.threshold_per_ip')
    case 'threshold_total': return t('loginAnomaly.lbl.threshold_total')
    case 'threshold_distinct_users_per_ip': return t('loginAnomaly.lbl.threshold_distinct_users_per_ip')
    case 'threshold_distinct_ips_per_account': return t('loginAnomaly.lbl.threshold_distinct_ips_per_account')
    case 'relative_multiplier': return t('loginAnomaly.lbl.relative_multiplier')
    case 'baseline_hours': return t('loginAnomaly.lbl.baseline_hours')
    case 'relative_floor': return t('loginAnomaly.lbl.relative_floor')
    case 'window_minutes': return t('loginAnomaly.lbl.window_minutes')
    case 'cooldown_minutes': return t('loginAnomaly.lbl.cooldown_minutes')
    case 'catchup_cap_minutes': return t('loginAnomaly.lbl.catchup_cap_minutes')
    case 'retention_days': return t('loginAnomaly.lbl.retention_days')
    default: return key
  }
}

/** Alan ipucu — pencereye bağlı eşiklerde güncel pencere süresi ({0}) metne girer. */
export function fieldHint(t, key, windowMinutes) {
  const w = windowMinutes || '—'
  switch (key) {
    case 'threshold_per_account': return t('loginAnomaly.hint.threshold_per_account', w)
    case 'threshold_per_ip': return t('loginAnomaly.hint.threshold_per_ip', w)
    case 'threshold_total': return t('loginAnomaly.hint.threshold_total', w)
    case 'threshold_distinct_users_per_ip': return t('loginAnomaly.hint.threshold_distinct_users_per_ip')
    case 'threshold_distinct_ips_per_account': return t('loginAnomaly.hint.threshold_distinct_ips_per_account')
    case 'relative_multiplier': return t('loginAnomaly.hint.relative_multiplier')
    case 'baseline_hours': return t('loginAnomaly.hint.baseline_hours')
    case 'relative_floor': return t('loginAnomaly.hint.relative_floor')
    case 'window_minutes': return t('loginAnomaly.hint.window_minutes')
    case 'cooldown_minutes': return t('loginAnomaly.hint.cooldown_minutes')
    case 'catchup_cap_minutes': return t('loginAnomaly.hint.catchup_cap_minutes')
    case 'retention_days': return t('loginAnomaly.hint.retention_days')
    default: return null
  }
}

/** Doğrulama sonucu (`laModel.validateField`) → metin. */
export function errorText(t, e) {
  if (!e) return null
  switch (e.code) {
    case 'required': return t('loginAnomaly.err.required')
    case 'number': return t('loginAnomaly.err.number')
    case 'integer': return t('loginAnomaly.err.integer')
    case 'min': return t('loginAnomaly.err.min', e.arg)
    case 'max': return t('loginAnomaly.err.max', e.arg)
    case 'recipientsInvalid': return t('loginAnomaly.err.recipientsInvalid')
    case 'recipientsTooMany': return t('loginAnomaly.err.recipientsTooMany', e.arg)
    default: return null
  }
}

/** Alanlar-arası uyarı (`laModel.fieldWarnings`) → metin. */
export function warnText(t, w) {
  if (!w) return null
  switch (w.code) {
    case 'catchupBelowWindow': return t('loginAnomaly.warn.catchupBelowWindow', ...w.args)
    case 'baselineBelowWindow': return t('loginAnomaly.warn.baselineBelowWindow')
    case 'aboveTotal': return t('loginAnomaly.warn.aboveTotal', ...w.args)
    case 'floorZero': return t('loginAnomaly.warn.floorZero')
    default: return null
  }
}

/**
 * Şablon metnindeki `{n}` yer tutucularını React düğümleriyle doldurur (sayılar kalın yazılsın diye).
 * `t(anahtar)` argümansız çağrıldığında şablonu olduğu gibi döndürür.
 */
export function richText(template, values) {
  const parts = String(template ?? '').split(/\{(\d+)\}/)
  return parts.map((p, i) => (i % 2 === 1
    ? <strong key={i} className="font-semibold text-foreground tabular-nums">{values[Number(p)]}</strong>
    : <Fragment key={i}>{p}</Fragment>))
}

/** Tetiklenen kural rozeti — olay satırlarında ve alan etiketinde aynı ad. */
export function RuleBadge({ t, code, className = '' }) {
  return (
    <Badge variant="outline" data-slot="la-rule-badge" data-rule={code}
      className={cn('max-w-full rounded-md font-medium whitespace-normal text-foreground', className)}>
      {ruleLabel(t, code)}
    </Badge>
  )
}

/**
 * Sayısal ayar alanı — ui/Field (etiket + yardım + ipucu + satır içi hata) + InputGroup (sağda birim).
 * Birim metni `aria-describedby`'a bağlı (ekran okuyucu "10, deneme" okur). Uyarı (kaydı engellemez) ipucunun
 * yerine sarı tonda çizilir; hata varsa hata önce gelir. Telefonda 40 px yükseklik.
 * `readOnly`: kilitli ama odaklanabilir/okunabilir; kilit simgesi birimin yanında.
 */
export function NumberField({ t, name, value, onChange, error, warning, windowMinutes, readOnly = false, hint: hintOverride }) {
  const def = FIELDS[name]
  const unitId = useId()
  const label = fieldLabel(t, name)
  const hint = warning || hintOverride || fieldHint(t, name, windowMinutes)
  return (
    <Field label={helpLabel(label, def.help)} error={error} hint={hint} hintTone={warning && !error ? 'warn' : undefined}
      className="mb-0 min-w-0">
      {({ id, describedBy, invalid }) => (
        <InputGroup data-la="number" data-field={name} data-readonly={readOnly ? 'true' : undefined}
          className={cn('h-10 sm:h-9 sm:pointer-coarse:h-10', readOnly && 'bg-muted/50')}>
          <InputGroupInput id={id} type="number" inputMode={def.int ? 'numeric' : 'decimal'} name={name}
            min={def.min} max={def.max} step={def.step} value={value} readOnly={readOnly} aria-readonly={readOnly || undefined}
            aria-describedby={[describedBy, unitId].filter(Boolean).join(' ')} aria-invalid={invalid}
            className="h-full tabular-nums"
            onChange={(e) => { if (!readOnly) onChange(name, e.target.value) }} />
          <InputGroupAddon align="inline-end">
            {readOnly && <Lock aria-hidden="true" />}
            <InputGroupText id={unitId}>{unitLabel(t, def.unit)}</InputGroupText>
          </InputGroupAddon>
        </InputGroup>
      )}
    </Field>
  )
}
