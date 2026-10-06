import { useId } from 'react'
import { ArrowRight, FilePlus2, Info, RefreshCw } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import { FormField } from '../../monitoring/MonitorForm.jsx'
import TrackingFields from './TrackingFields.jsx'
import { dateOnly } from '../../certcard/certCardModel.js'
import { NOTE_MAX, entryTitle, warningText } from '../manualCertModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { RadioGroup, RadioGroupItem } from '@/components/shadcn/radio-group'
import { Textarea } from '@/components/shadcn/textarea'
import { cn } from '@/lib/utils'

const TOUCH_AREA = 'relative pointer-coarse:after:absolute pointer-coarse:after:-inset-3'
const shortFp = (fp) => (fp ? `${String(fp).slice(0, 8)}…${String(fp).slice(-8)}` : '—')

/** Kip seçimi "seçenek kartı" — RadioGroupItem + başlık (Label) + açıklama; devre dışıysa nedeni. */
function ModeCard({ value, title, desc, Icon, disabled, hint, selected }) {
  const id = useId()
  return (
    <div data-slot="mcert-mode" data-mode={value} data-selected={selected ? 'true' : 'false'}
      className={cn('flex min-w-0 items-start gap-3 rounded-lg border p-3', selected && 'border-primary bg-primary/5 ring-1 ring-primary/40',
        disabled && 'opacity-60')}>
      <RadioGroupItem id={id} value={value} disabled={disabled} className={`mt-0.5 size-5 ${TOUCH_AREA}`} />
      <div className="min-w-0 flex-1">
        <Label htmlFor={id} className="flex items-center gap-1.5 text-sm font-semibold">
          <Icon aria-hidden="true" className="size-4 text-primary" />{title}
        </Label>
        <p className="m-0 mt-0.5 text-xs text-muted-foreground">{desc}</p>
        {hint && <p className="m-0 mt-1 text-xs text-amber-700 dark:text-amber-300">{hint}</p>}
      </div>
    </div>
  )
}

/** Güncel sürüm ↔ yüklenen sertifika karşılaştırması — telefonda satırlar alt alta (etiket · eski → yeni). */
function RenewCompare({ current, entry, cmp }) {
  const t = useT()
  const rows = [
    { key: 'notAfter', label: t('mcert.cmp.notAfter'), old: dateOnly(current.not_after), now: dateOnly(entry.not_after), changed: true, bad: cmp.older },
    { key: 'issuer', label: t('mcert.cmp.issuer'), old: current.issuer || '—', now: entry.issuer || '—', changed: cmp.issuerChanged },
    { key: 'subject', label: t('mcert.cmp.subject'), old: current.subject || '—', now: entry.subject || '—', changed: cmp.subjectChanged },
    { key: 'key', label: t('mcert.cmp.key'), old: cmp.keyOld || '—', now: cmp.keyNew || '—', changed: cmp.keyAlgChanged },
    { key: 'fp', label: t('mcert.cmp.fingerprint'), old: shortFp(current.fingerprint), now: shortFp(entry.ref), changed: !cmp.same, mono: true },
  ]
  return (
    <div data-slot="mcert-compare" className="flex min-w-0 flex-col gap-2 rounded-lg border bg-muted/20 p-3">
      <p className="m-0 flex flex-wrap items-center gap-2 text-sm font-semibold">
        {t('mcert.cmp.title')}
        <Badge variant="outline" className="h-5 rounded-md px-1.5 text-[11px] font-semibold">{t('mcert.versionShort', current.version)}</Badge>
        <ArrowRight aria-hidden="true" className="size-3.5 text-muted-foreground" />
        <Badge variant="secondary" className="h-5 rounded-md px-1.5 text-[11px] font-semibold">{t('mcert.cmp.newVersion')}</Badge>
      </p>
      <dl className="m-0 flex min-w-0 flex-col gap-2">
        {rows.map((r) => (
          <div key={r.key} data-slot="mcert-compare-row" data-key={r.key} data-changed={r.changed ? 'true' : 'false'}
            className="grid min-w-0 grid-cols-1 gap-x-3 gap-y-0.5 sm:grid-cols-[9rem_minmax(0,1fr)]">
            <dt className="text-xs font-semibold text-muted-foreground">{r.label}</dt>
            <dd className={cn('m-0 flex min-w-0 flex-wrap items-center gap-x-1.5 text-[13px] [overflow-wrap:anywhere]', r.mono && 'font-mono text-xs')}>
              <span className="text-muted-foreground">{r.old}</span>
              <ArrowRight aria-hidden="true" className="size-3 shrink-0 text-muted-foreground" />
              <span className={cn(r.changed && 'font-semibold', r.bad && 'text-destructive')}>{r.now}</span>
              {!r.changed && <span className="text-xs text-muted-foreground">({t('mcert.cmp.same')})</span>}
            </dd>
          </div>
        ))}
        {(cmp.sanAdded.length > 0 || cmp.sanRemoved.length > 0) && (
          <div data-slot="mcert-compare-row" data-key="san" className="grid min-w-0 grid-cols-1 gap-x-3 gap-y-0.5 sm:grid-cols-[9rem_minmax(0,1fr)]">
            <dt className="text-xs font-semibold text-muted-foreground">{t('mcert.cmp.san')}</dt>
            <dd className="m-0 flex min-w-0 flex-wrap gap-1">
              {cmp.sanAdded.map((s) => (
                <Badge key={`+${s}`} variant="outline" data-slot="mcert-san-added" className="h-auto rounded-md border-success/40 bg-success/10 px-1.5 py-0.5 font-mono text-[11px] font-normal whitespace-normal text-success [overflow-wrap:anywhere]">+ {s}</Badge>
              ))}
              {cmp.sanRemoved.map((s) => (
                <Badge key={`-${s}`} variant="outline" data-slot="mcert-san-removed" className="h-auto rounded-md border-destructive/40 bg-destructive/10 px-1.5 py-0.5 font-mono text-[11px] font-normal whitespace-normal text-destructive [overflow-wrap:anywhere]">− {s}</Badge>
              ))}
            </dd>
          </div>
        )}
      </dl>
      <p className="m-0 flex items-start gap-1.5 text-xs text-muted-foreground">
        <Info aria-hidden="true" className="mt-px size-3.5 shrink-0" />{t('mcert.cmp.keyNote')}
      </p>
    </div>
  )
}

/**
 * Sihirbaz 3. adım — "Takip" (2026-10-06). Kip: "Yeni takip kaydı" ya da "Mevcut kaydı yenile (yeni sürüm)" (çoklu
 * seçimde yalnız yeni). Yeni: takip adı (sunucunun önerisi doldurulmuş, istemci deseni) + envanter alanları + sürüm notu;
 * çoklu: her girdi için ayrı takip adı + ortak alanlar. Yenile: hedef kayıt (aynı konu adı adayları önde), güncel ↔ yeni
 * karşılaştırma, daha eski bitişte açık onay (OLDER_THAN_CURRENT), aynı sertifikada engel (SAME_CERTIFICATE).
 */
export default function TrackStep({
  entries, multi, mode, onMode, renewFixed, renewOptions, target, onTarget, current, cmp, needsConfirm = false,
  keyValue, onKey, batchKeys, onBatchKey, rowErrors, form, onField, teams, canOpenSettings,
  note, onNote, confirmOlder, onConfirmOlder, fe,
}) {
  const t = useT()
  const first = entries[0]
  const canRenew = !multi && (renewFixed || renewOptions.length > 0)

  const noteField = (
    <FormField label={t('mcert.track.note')} hint={t('mcert.track.noteHint', NOTE_MAX)} full {...fe.fieldProps('note')}>
      {({ id, describedBy, invalid }) => (
        <Textarea id={id} aria-describedby={describedBy} aria-invalid={invalid} value={note} rows={3} maxLength={NOTE_MAX}
          placeholder={t('mcert.track.notePh')} onChange={(e) => { onNote(e.target.value); fe.clear('note') }} />
      )}
    </FormField>
  )

  return (
    <div className="flex min-w-0 flex-col gap-4">
      {!multi && first && (
        <p data-slot="mcert-picked" className="m-0 flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-sm">
          <span className="text-muted-foreground">{t('mcert.track.picked')}</span>
          <strong className="min-w-0 break-all">{entryTitle(first)}</strong>
          <span className="text-xs text-muted-foreground tabular-nums">· {t('mcert.track.expires', dateOnly(first.not_after))}</span>
        </p>
      )}

      {!multi && !renewFixed && (
        <RadioGroup value={mode} onValueChange={onMode} aria-label={t('mcert.track.modeLabel')} className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          <ModeCard value="new" Icon={FilePlus2} selected={mode === 'new'} title={t('mcert.track.modeNew')} desc={t('mcert.track.modeNewDesc')} />
          <ModeCard value="renew" Icon={RefreshCw} selected={mode === 'renew'} disabled={!canRenew}
            title={t('mcert.track.modeRenew')} desc={t('mcert.track.modeRenewDesc')} hint={canRenew ? null : t('mcert.track.noRenewTarget')} />
        </RadioGroup>
      )}

      {mode === 'renew' && !multi ? (
        <div data-slot="mcert-renew" className="flex min-w-0 flex-col gap-3">
          {renewFixed ? (
            <AlertBanner tone="info" icon={RefreshCw} className="mb-0" title={t('mcert.renew.fixedTitle', target?.domain || '—')}>
              {t('mcert.renew.fixedBody')}
            </AlertBanner>
          ) : (
            <FormField label={t('mcert.renew.target')} required {...fe.fieldProps('target')}>
              {({ id }) => (
                <SearchableSelect id={id} value={target?.inventory_id != null ? String(target.inventory_id) : ''} searchThreshold={6}
                  placeholder={t('mcert.renew.targetPh')}
                  onChange={(v) => { onTarget(renewOptions.find((o) => String(o.inventory_id) === String(v)) || null); fe.clear('target') }}
                  options={renewOptions.map((o) => ({
                    value: String(o.inventory_id), label: o.domain, group: o.same ? t('mcert.renew.groupSame') : t('mcert.renew.groupOther'),
                    hint: o.not_after ? t('mcert.track.expires', dateOnly(o.not_after)) : undefined,
                  }))} />
              )}
            </FormField>
          )}

          {target && current?.status === 'loading' && <LoadingBlock label={t('mcert.renew.loadingCurrent')} />}
          {target && current?.status === 'error' && <AlertBanner tone="warning" className="mb-0">{t('mcert.renew.currentError')}</AlertBanner>}
          {target && current?.status === 'ready' && current.version && first && cmp && (
            <RenewCompare current={current.version} entry={first} cmp={cmp} />
          )}
          {cmp?.same && (
            <AlertBanner tone="danger" role="alert" className="mb-0" title={t('mcert.renew.sameTitle')}>{t('mcert.renew.sameBody')}</AlertBanner>
          )}
          {needsConfirm && !cmp?.same && (
            <div data-field="confirm" className="flex min-w-0 flex-col gap-2">
              <AlertBanner tone="warning" className="mb-0" title={t('mcert.renew.olderTitle')}>
                {warningText(t, { code: 'OLDER_THAN_CURRENT', params: { current_date: current?.version?.not_after, new_date: first?.not_after } })}
              </AlertBanner>
              <label className={cn('flex min-h-10 cursor-pointer items-start gap-2.5 rounded-md border px-3 py-2 text-sm', fe.errors.confirm && 'border-destructive/60')}>
                <Checkbox data-slot="mcert-confirm-older" checked={confirmOlder} className="mt-0.5"
                  onCheckedChange={(v) => { onConfirmOlder(v === true); fe.clear('confirm') }} aria-invalid={fe.errors.confirm ? true : undefined} />
                <span className="min-w-0">{t('mcert.renew.confirmOlder')}</span>
              </label>
              {fe.errors.confirm && <p data-slot="field-error" className="m-0 text-xs text-destructive">{fe.errors.confirm}</p>}
            </div>
          )}
          {noteField}
        </div>
      ) : (
        <div data-slot="mcert-new" className="flex min-w-0 flex-col gap-4">
          {multi ? (
            <div data-slot="mcert-batch" className="flex min-w-0 flex-col gap-3 rounded-lg border bg-muted/20 p-3">
              <p className="m-0 text-sm font-semibold">{t('mcert.batch.title', entries.length)}</p>
              <p className="m-0 text-xs text-muted-foreground">{t('mcert.key.hint')}</p>
              {entries.map((e, i) => (
                <FormField key={e.ref} label={entryTitle(e)} required name={`item_${i}`} error={rowErrors[i] || fe.errors[`item_${i}`]}>
                  {({ id, describedBy, invalid }) => (
                    <Input id={id} data-slot="mcert-batch-key" aria-describedby={describedBy} aria-invalid={invalid} value={batchKeys[e.ref] ?? ''}
                      className="font-mono" autoComplete="off" spellCheck={false} autoCapitalize="none"
                      onChange={(ev) => { onBatchKey(e.ref, ev.target.value.toLowerCase(), i); fe.clear(`item_${i}`) }} />
                  )}
                </FormField>
              ))}
            </div>
          ) : (
            <FormField label={t('mcert.key.label')} required hint={t('mcert.key.hint')} {...fe.fieldProps('domain')}>
              {({ id, describedBy, invalid }) => (
                <Input id={id} data-slot="mcert-key" aria-describedby={describedBy} aria-invalid={invalid} value={keyValue}
                  className="font-mono" autoComplete="off" spellCheck={false} autoCapitalize="none" maxLength={253}
                  onChange={(e) => { onKey(e.target.value.toLowerCase()); fe.clear('domain') }} />
              )}
            </FormField>
          )}
          <TrackingFields form={form} onField={onField} teams={teams} fe={fe} canOpenSettings={canOpenSettings} />
          {noteField}
        </div>
      )}
    </div>
  )
}
