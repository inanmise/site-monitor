import { useId, useState } from 'react'
import { Archive, Braces, KeyRound, ListFilter, ScrollText, ShieldBan, SlidersHorizontal, TriangleAlert } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import Field from '../../ui/Field.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import MultiTeamSelect from '../../ui/MultiTeamSelect.jsx'
import TokenChipsInput, { TOO_MANY } from '../loginmethods/TokenChipsInput.jsx'
import { SettingsSaveBar } from '../SettingsControls.jsx'
import { LEVELS, MAIL_SEPARATORS, RCI_MODES, RESOLVED_MODES, isValidIpEntry, normalizeIp, numText, typeLabel } from './alarmFeedModel.js'
import { SectionCard, TOUCH } from './AlarmFeedParts.jsx'
import { Button } from '@/components/shadcn/button'
import { Field as ShField, FieldContent, FieldDescription, FieldLabel, FieldTitle } from '@/components/shadcn/field'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { RadioGroup, RadioGroupItem } from '@/components/shadcn/radio-group'
import { Switch } from '@/components/shadcn/switch'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'

/** Alt başlıklı alan grubu (Kapsam / Biçim / Erişim / Günlük). */
function Group({ icon: Icon, title, slot, children }) {
  const id = useId()
  return (
    <section aria-labelledby={id} data-slot={slot} className="flex min-w-0 flex-col gap-1 rounded-lg border px-3 pt-3 pb-1 sm:px-4">
      <h5 id={id} className="m-0 mb-2 flex items-center gap-2 text-sm font-semibold">
        {Icon && <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />}{title}
      </h5>
      {children}
    </section>
  )
}

/** Açık / kapalı satırı — shadcn Switch + bağlı etiket + açıklama (dokunmatikte satır ≥ 40 px). */
function SwitchRow({ checked, onChange, label, hint, disabled, slot }) {
  const id = useId()
  const hintId = `${id}-h`
  return (
    <div className="mb-3.5 flex min-w-0 items-start gap-3" data-slot={slot} data-on={checked ? 'true' : 'false'}>
      <Switch id={id} checked={checked} onCheckedChange={(v) => onChange(v === true)} disabled={disabled}
        aria-describedby={hint ? hintId : undefined} className="mt-0.5" />
      <div className="flex min-w-0 flex-col gap-0.5">
        <Label htmlFor={id} className="min-h-5 cursor-pointer font-semibold pointer-coarse:min-h-10">{label}</Label>
        {hint && <span id={hintId} className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{hint}</span>}
      </div>
    </div>
  )
}

/** Seçim kartları (RadioGroup) — TeamAccessGrantDialog deseni. */
function ChoiceCards({ value, onChange, options, labelledBy, slot, disabled, error, errorId }) {
  return (
    <RadioGroup value={value} onValueChange={onChange} aria-labelledby={labelledBy} data-slot={slot} disabled={disabled}
      aria-describedby={error ? errorId : undefined} aria-invalid={error ? true : undefined}
      className="grid grid-cols-1 gap-2 @2xl:grid-cols-2">
      {options.map((o) => {
        const rid = `${labelledBy}-${o.value}`
        return (
          <FieldLabel key={o.value} htmlFor={rid} className="w-full cursor-pointer">
            <ShField orientation="horizontal" data-value={o.value} className="min-h-10 items-start gap-2 px-3! py-2!">
              <RadioGroupItem value={o.value} id={rid} className="mt-0.5" />
              <FieldContent className="min-w-0 gap-0.5">
                <FieldTitle className="text-sm [overflow-wrap:anywhere]">{o.title}</FieldTitle>
                {o.hint && <FieldDescription className="m-0 text-xs leading-snug [overflow-wrap:anywhere]">{o.hint}</FieldDescription>}
                {o.example && (
                  <code className="mt-1 block rounded bg-muted px-1.5 py-1 font-mono text-xs whitespace-normal [overflow-wrap:anywhere]" data-slot="af-choice-example">
                    {o.example}
                  </code>
                )}
              </FieldContent>
            </ShField>
          </FieldLabel>
        )
      })}
    </RadioGroup>
  )
}

/** Sayı / metin girdisi — ui/Field bağlarıyla. */
function TextField({ label, hint, name, fe, value, onChange, disabled, numeric = false, placeholder, mono = false }) {
  return (
    <Field label={label} hint={hint} {...fe.fieldProps(name)}>
      {({ id, describedBy, invalid }) => (
        <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} value={value ?? ''} disabled={disabled}
          inputMode={numeric ? 'numeric' : undefined} autoComplete="off" spellCheck={false} placeholder={placeholder}
          onChange={(e) => onChange(name, e.target.value)} data-slot={`af-f-${name}`}
          className={cn('h-10', mono && 'font-mono')} />
      )}
    </Field>
  )
}

/**
 * Ayarlar formu — dört grup: Kapsam, Biçim, Erişim, Günlük. Yalnız değişen alanlar kaydedilir; alan hatası (istemci ya da
 * sunucunun `field`'ı) alanın altında. Akışı AÇMAK kayıt anında onay ister (kök bileşen).
 */
export function SettingsCard({ data, form, dirty, saving, fe, limits, lang, onChange, onSave, onDiscard, onGoRetention }) {
  const t = useT()
  const [ipEntryError, setIpEntryError] = useState(null)
  const resolvedId = useId()
  const rciId = useId()
  const typesId = useId()
  const teams = Array.isArray(data?.teams) ? data.teams : []
  const teamIds = new Set(teams.map((x) => Number(x.id)))
  const teamOptions = [
    ...teams.map((x) => ({ value: Number(x.id), label: x.name || t('af.teamUnnamed') })),
    // Takım dizini okunamadıysa (`teams: null`) seçili kimlik "silinmiş" sayılmaz — adı bilinmiyor
    ...(form.team_ids || []).filter((id) => !teamIds.has(id))
      .map((id) => ({ value: id, label: Array.isArray(data?.teams) ? t('af.teamMissing') : t('af.teamUnnamed') })),
  ]
  const types = Array.isArray(data?.types) ? data.types : []
  const typeKeys = new Set(types.map((x) => x.key))
  const typeItems = [
    ...types.map((x) => ({ key: x.key, label: typeLabel(x.key, x.label, t) })),
    ...(form.types || []).filter((k) => !typeKeys.has(k)).map((k) => ({ key: k, label: k })),
  ]
  const levelOptions = LEVELS.includes(form.min_level) || !form.min_level ? LEVELS : [...LEVELS, form.min_level]
  const sepOptions = MAIL_SEPARATORS.includes(form.mail_separator) || !form.mail_separator ? MAIL_SEPARATORS : [...MAIL_SEPARATORS, form.mail_separator]
  const rciChanged = data?.settings?.rci_mode != null && form.rci_mode !== data.settings.rci_mode
  const [exemptEntryError, setExemptEntryError] = useState(null)
  const ipError = ipEntryError || fe.errors.allowed_ips
  const exemptError = exemptEntryError || fe.errors.block_exempt_ips
  const retention = data?.log_retention_days
  const bodyRetention = data?.body_retention_days
  // Eski sunucu engel alanlarını göndermez → grup çizilmez (boş sayı alanları "değişti" sayılmasın)
  const blockSupported = !!data?.settings && Object.prototype.hasOwnProperty.call(data.settings, 'block_threshold')
  const set = (k) => (v) => onChange(k, v)

  return (
    <SectionCard slot="af-settings" icon={SlidersHorizontal} title={t('af.settings.title')} description={t('af.settings.desc')}>
      <Group icon={ListFilter} title={t('af.grp.scope')} slot="af-grp-scope">
        <SwitchRow checked={form.enabled} onChange={set('enabled')} disabled={saving} slot="af-f-enabled"
          label={t('af.f.enabled')} hint={t('af.f.enabledHint')} />
        <div className="grid min-w-0 grid-cols-1 gap-x-4 @2xl:grid-cols-2">
          <Field label={t('af.f.minLevel')} hint={t('af.f.minLevelHint')} {...fe.fieldProps('min_level')}>
            {({ id, describedBy, invalid }) => (
              <NativeSelect id={id} aria-describedby={describedBy} aria-invalid={invalid} value={form.min_level} disabled={saving}
                onChange={(e) => onChange('min_level', e.target.value)} className="h-10 w-full" data-slot="af-f-min_level">
                {!form.min_level && <NativeSelectOption value="">—</NativeSelectOption>}
                {levelOptions.map((l) => (
                  <NativeSelectOption key={l} value={l}>{LEVELS.includes(l) ? t(`af.level.${l}`) : l}</NativeSelectOption>
                ))}
              </NativeSelect>
            )}
          </Field>
          <div data-touch-picker="" className="min-w-0">
            <Field label={t('af.f.teams')} hint={!form.team_ids?.length ? t('af.f.teamsHintAll') : form.team_ids.length === 1 ? t('af.f.teamsHintSome.one') : t('af.f.teamsHintSome', form.team_ids.length)}
              {...fe.fieldProps('team_ids')}>
              {({ id }) => (
                <>
                  <MultiTeamSelect id={id} value={form.team_ids} options={teamOptions} disabled={saving} ariaLabel={t('af.f.teams')}
                    placeholder={t('af.f.teamsAll')} onChange={(ids) => onChange('team_ids', ids)} />
                  {/* Seçici tetiği uzun AD takım adını tek satırda kısaltır — seçilenler burada TAM adla, sararak */}
                  {form.team_ids?.length > 0 && (
                    <ul className="m-0 flex list-none flex-wrap gap-1.5 p-0" data-slot="af-f-teams-selected" aria-label={t('af.f.teamsSelected')}>
                      {form.team_ids.map((tid) => (
                        <li key={tid} className="min-w-0 max-w-full rounded-md border bg-muted/50 px-2 py-0.5 text-xs [overflow-wrap:anywhere]">
                          {teamOptions.find((o) => o.value === tid)?.label || t('af.teamMissing')}
                        </li>
                      ))}
                    </ul>
                  )}
                </>
              )}
            </Field>
          </div>
        </div>

        <div className="mb-3.5 flex min-w-0 flex-col gap-1.5" data-field="types" data-slot="af-f-types">
          <span id={typesId} className="text-sm font-semibold">{t('af.f.types')}</span>
          <ToggleGroup type="multiple" variant="outline" spacing={2} value={form.types} disabled={saving}
            onValueChange={(v) => onChange('types', v)} aria-labelledby={typesId} className="w-full flex-wrap">
            {typeItems.map((x) => (
              <ToggleGroupItem key={x.key} value={x.key} data-key={x.key}
                className="h-auto min-h-9 px-3 whitespace-normal data-[state=on]:border-primary data-[state=on]:bg-primary/10 data-[state=on]:text-primary pointer-coarse:min-h-10">
                {x.label}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
          <span className="text-xs text-muted-foreground" data-slot="af-f-types-hint">
            {!form.types?.length ? t('af.f.typesHintAll') : form.types.length === 1 ? t('af.f.typesHintSome.one') : t('af.f.typesHintSome', form.types.length)}
          </span>
          {fe.errors.types && <span className="text-xs text-destructive">{fe.errors.types}</span>}
        </div>

        <div className="grid min-w-0 grid-cols-1 gap-x-4 @2xl:grid-cols-2">
          <TextField label={t('af.f.resolvedMinutes')} hint={t('af.f.resolvedMinutesHint', limits.resolved_minutes[0], limits.resolved_minutes[1])}
            name="resolved_minutes" fe={fe} value={form.resolved_minutes} onChange={onChange} disabled={saving} numeric />
          <TextField label={t('af.f.maxRows')} hint={t('af.f.maxRowsHint', limits.max_rows[0], limits.max_rows[1])}
            name="max_rows" fe={fe} value={form.max_rows} onChange={onChange} disabled={saving} numeric />
        </div>
      </Group>

      <Group icon={Braces} title={t('af.grp.format')} slot="af-grp-format">
        <p className="m-0 mb-2 text-xs text-muted-foreground">{t('af.f.severityHint')}</p>
        <div className="grid min-w-0 grid-cols-1 gap-x-4 @2xl:grid-cols-3">
          <TextField label={t('af.f.sevCritical')} name="severity_critical" fe={fe} value={form.severity_critical} onChange={onChange}
            disabled={saving} />
          <TextField label={t('af.f.sevHigh')} name="severity_high" fe={fe} value={form.severity_high} onChange={onChange}
            disabled={saving} />
          <TextField label={t('af.f.sevWarning')} name="severity_warning" fe={fe} value={form.severity_warning} onChange={onChange}
            disabled={saving} />
        </div>

        <div className="mb-3.5 flex min-w-0 flex-col gap-2" data-field="resolved_mode">
          <span id={resolvedId} className="text-sm font-semibold">{t('af.f.resolvedMode')}</span>
          <ChoiceCards value={form.resolved_mode} onChange={set('resolved_mode')} labelledBy={resolvedId} slot="af-f-resolved_mode"
            disabled={saving} error={fe.errors.resolved_mode} errorId={`${resolvedId}-e`}
            options={RESOLVED_MODES.map((m) => ({
              value: m, title: t(`af.resolved.${m}`), hint: t(`af.resolved.${m}Hint`),
              example: m === 'clear' ? t('af.resolved.clearExample', form.clear_text || '—') : t('af.resolved.statusExample', form.severity_critical || '—'),
            }))} />
          {fe.errors.resolved_mode && <span id={`${resolvedId}-e`} className="text-xs text-destructive">{fe.errors.resolved_mode}</span>}
        </div>
        {form.resolved_mode === 'clear' && (
          <TextField label={t('af.f.clearText')} hint={t('af.f.clearTextHint')} name="clear_text" fe={fe} value={form.clear_text}
            onChange={onChange} disabled={saving} />
        )}

        <div className="mb-3.5 flex min-w-0 flex-col gap-2" data-field="rci_mode">
          <span id={rciId} className="text-sm font-semibold">{t('af.f.rciMode')}</span>
          <ChoiceCards value={form.rci_mode} onChange={set('rci_mode')} labelledBy={rciId} slot="af-f-rci_mode" disabled={saving}
            error={fe.errors.rci_mode} errorId={`${rciId}-e`}
            options={RCI_MODES.map((m) => ({ value: m, title: t(`af.rci.${m}`), hint: t(`af.rci.${m}Hint`), example: t(`af.rci.${m}Example`) }))} />
          {fe.errors.rci_mode && <span id={`${rciId}-e`} className="text-xs text-destructive">{fe.errors.rci_mode}</span>}
          <div data-slot="af-rci-eventkey" data-changed={rciChanged ? 'true' : 'false'}>
            <AlertBanner tone={rciChanged ? 'warning' : 'info'} className="mb-0" icon={TriangleAlert}>{t('af.rci.eventKey')}</AlertBanner>
          </div>
        </div>

        <div className="grid min-w-0 grid-cols-1 gap-x-4 @2xl:grid-cols-3">
          <TextField label={t('af.f.source')} hint={t('af.f.sourceHint')} name="source" fe={fe} value={form.source} onChange={onChange}
            disabled={saving} />
          <TextField label={t('af.f.rcitype')} hint={t('af.f.rcitypeHint')} name="rcitype" fe={fe} value={form.rcitype} onChange={onChange}
            disabled={saving} placeholder={t('af.f.rcitypePlaceholder')} />
          <Field label={t('af.f.mailSeparator')} hint={t('af.f.mailSeparatorHint')} {...fe.fieldProps('mail_separator')}>
            {({ id, describedBy, invalid }) => (
              <NativeSelect id={id} aria-describedby={describedBy} aria-invalid={invalid} value={form.mail_separator} disabled={saving}
                onChange={(e) => onChange('mail_separator', e.target.value)} className="h-10 w-full" data-slot="af-f-mail_separator">
                {!form.mail_separator && <NativeSelectOption value="">—</NativeSelectOption>}
                {sepOptions.map((m) => (
                  <NativeSelectOption key={m} value={m}>{MAIL_SEPARATORS.includes(m) ? t(`af.sep.${m}`) : m}</NativeSelectOption>
                ))}
              </NativeSelect>
            )}
          </Field>
        </div>
      </Group>

      <Group icon={KeyRound} title={t('af.grp.access')} slot="af-grp-access">
        <Field label={t('af.f.allowedIps')} hint={form.allowed_ips?.length ? t('af.f.allowedIpsHint', limits.allowed_ips_max) : t('af.f.allowedIpsEmpty')}
          name="allowed_ips" error={ipError || undefined}>
          {({ id, describedBy, invalid }) => (
            <TokenChipsInput id={id} values={form.allowed_ips} slot="af-f-allowed_ips" describedBy={describedBy} invalid={invalid}
              disabled={saving} max={limits.allowed_ips_max} placeholder={t('af.f.allowedIpsPlaceholder')} inputMode="text"
              validate={(v) => (isValidIpEntry(v) ? null : { entry: v })} normalize={normalizeIp}
              removeLabel={(v) => t('af.f.removeIp', v)}
              onEntryError={(bad) => setIpEntryError(bad == null ? null
                : bad.key === TOO_MANY ? t('af.v.ipMax', bad.params[0]) : t('af.v.ip', bad.entry))}
              onChange={(list) => { onChange('allowed_ips', list); setIpEntryError(null) }} />
          )}
        </Field>
        <div className="grid min-w-0 grid-cols-1 gap-x-4 @2xl:grid-cols-2">
          <TextField label={t('af.f.ratePerMinute')} hint={t('af.f.ratePerMinuteHint', limits.rate_per_minute[0], limits.rate_per_minute[1])}
            name="rate_per_minute" fe={fe} value={form.rate_per_minute} onChange={onChange} disabled={saving} numeric />
        </div>
      </Group>

      {blockSupported && (
        <Group icon={ShieldBan} title={t('af.grp.block')} slot="af-grp-block">
          <SwitchRow checked={form.block_enabled} onChange={set('block_enabled')} disabled={saving} slot="af-f-block_enabled"
            label={t('af.f.blockEnabled')} hint={t('af.f.blockEnabledHint')} />
          <div className="grid min-w-0 grid-cols-1 gap-x-4 @2xl:grid-cols-2">
            <TextField label={t('af.f.blockThreshold')} name="block_threshold" fe={fe} value={form.block_threshold} onChange={onChange}
              disabled={saving} numeric
              hint={t('af.f.blockThresholdHint', form.rate_per_minute || '—', limits.block_threshold[0], limits.block_threshold[1])} />
            <TextField label={t('af.f.blockMinutes')} name="block_minutes" fe={fe} value={form.block_minutes} onChange={onChange}
              disabled={saving} numeric hint={t('af.f.blockMinutesHint', limits.block_minutes[0], limits.block_minutes[1])} />
          </div>
          <SwitchRow checked={form.block_escalate} onChange={set('block_escalate')} disabled={saving} slot="af-f-block_escalate"
            label={t('af.f.blockEscalate')} hint={t('af.f.blockEscalateHint')} />
          <Field label={t('af.f.blockExempt')} hint={t('af.f.blockExemptHint', limits.allowed_ips_max)} name="block_exempt_ips"
            error={exemptError || undefined}>
            {({ id, describedBy, invalid }) => (
              <TokenChipsInput id={id} values={form.block_exempt_ips || []} slot="af-f-block_exempt_ips" describedBy={describedBy}
                invalid={invalid} disabled={saving} max={limits.allowed_ips_max} placeholder={t('af.f.allowedIpsPlaceholder')}
                inputMode="text" validate={(v) => (isValidIpEntry(v) ? null : { entry: v })} normalize={normalizeIp}
                removeLabel={(v) => t('af.f.removeIp', v)}
                onEntryError={(bad) => setExemptEntryError(bad == null ? null
                  : bad.key === TOO_MANY ? t('af.v.ipMax', bad.params[0]) : t('af.v.ip', bad.entry))}
                onChange={(list) => { onChange('block_exempt_ips', list); setExemptEntryError(null) }} />
            )}
          </Field>
          <p className="m-0 mb-3 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="af-block-summary"
            data-on={form.block_enabled ? 'true' : 'false'}>
            {form.block_enabled
              ? t('af.f.blockSummary', form.rate_per_minute || '—', form.block_threshold || '—', form.block_minutes || '—')
              : t('af.f.blockSummaryOff', form.rate_per_minute || '—')}
          </p>
        </Group>
      )}

      <Group icon={ScrollText} title={t('af.grp.log')} slot="af-grp-log">
        <SwitchRow checked={form.store_body} onChange={set('store_body')} disabled={saving} slot="af-f-store_body"
          label={t('af.f.storeBody')} hint={t('af.f.storeBodyHint')} />
        <div className="mb-3.5 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-sm" data-slot="af-retention">
          <span>{retention == null || !Number.isFinite(Number(retention)) ? t('af.f.retentionUnknown') : Number(retention) === 1 ? t('af.f.retention.one') : t('af.f.retention', numText(retention, lang))}</span>
          {bodyRetention != null && Number.isFinite(Number(bodyRetention)) && (
            <span data-slot="af-body-retention">
              {Number(bodyRetention) === 1 ? t('af.f.bodyRetention.one') : t('af.f.bodyRetention', numText(bodyRetention, lang))}
            </span>
          )}
          <Button type="button" variant="link" size="sm" onClick={onGoRetention} className={cn('h-auto px-0', TOUCH)} data-slot="af-go-retention">
            <Archive aria-hidden="true" /> {t('af.f.retentionLink')}
          </Button>
        </div>
      </Group>

      <SettingsSaveBar dirty={dirty} saving={saving} onSave={onSave} onDiscard={onDiscard} />
    </SectionCard>
  )
}
