import { BellRing, CalendarClock, DatabaseZap, FileCode2, FlaskConical, Gauge, Info, ListChecks, Mail, Save, ShieldCheck, SlidersHorizontal } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import TagInput from '../ui/TagInput.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { InputGroup, InputGroupAddon, InputGroupInput, InputGroupText } from '@/components/shadcn/input-group'
import { cn } from '@/lib/utils'
import { CheckField, FormField, FormGrid, FormHint, FormSection } from '../monitoring/MonitorForm.jsx'
import { RuleBuilder, ScheduleEditor, SqlEditor, TemplatePicker } from './buildingBlocks.jsx'
import DbQueryTestPanel from './DbQueryTestPanel.jsx'
import { SUBJECT_PLACEHOLDERS, csvToList, listToCsv } from './dbqueryPageModel.js'

/**
 * Veritabanı Sorgu İzleme formunun TÜRE ÖZGÜ bölümleri (2026-10-31) — sunum bileşenleri; durum sayfada
 * (DbQueryMonitorPage) tutulur. Standart parçalar (ad, takım, grup, etiket, bildirim kanalları, 7/24) sayfa dosyasında
 * kalır: dokuz türün bağlantı kapıları (monitorFormWiring / notifyBlockStandard / sharedAccessSurfaces) onları sayfa
 * kaynağında arar.
 *
 * <p>Uzun form BÖLÜMLERE ayrılır (kart başına bir soru: Temel · Bağlantı · Sorgu · Değerlendirme · Zamanlama · Sınırlar ·
 * Alarm ve bildirim); en üstte bölüm atlama çubuğu (`dbq-form-nav`) — telefonda uzun kaydırmayı kısaltır. Bölümler
 * KATLANMAZ: kapalı bölümdeki hatalı alan görünmez kalır ve ilk hatalıya kaydırma onu bulamazdı.
 *
 * <p>Alan anahtarları SUNUCU adlarıdır (api §1 doğrulama `fields`): sunucu hatası olduğu gibi alanın altına düşer.
 */

/** Bölüm sırası — atlama çubuğu ve kaydırma çapası (`data-dbq-section`). */
export const FORM_SECTIONS = Object.freeze([
  { id: 'basic', icon: Info, titleKey: 'dbq.form.secBasic' },
  { id: 'connection', icon: DatabaseZap, titleKey: 'dbq.form.secConnection' },
  { id: 'query', icon: FileCode2, titleKey: 'dbq.form.secQuery' },
  { id: 'evaluation', icon: ListChecks, titleKey: 'dbq.form.secEvaluation' },
  { id: 'schedule', icon: CalendarClock, titleKey: 'dbq.form.secSchedule' },
  { id: 'limits', icon: SlidersHorizontal, titleKey: 'dbq.form.secLimits' },
  { id: 'alerting', icon: BellRing, titleKey: 'dbq.form.secAlerting' },
])

/** Bölüm çapası + başlıklı çerçeve (iki sütunu kaplar). */
export function DbqSection({ id, title, hint, icon, children }) {
  return (
    <div data-dbq-section={id} className="min-w-0 scroll-mt-4 sm:col-span-2">
      <FormSection title={title} hint={hint} icon={icon}>{children}</FormSection>
    </div>
  )
}

/** Bölüm atlama çubuğu — her düğme bölümüne kaydırır (hareket-azalt tercihine uyar), sarar; 40 px dokunma hedefi. */
export function FormSectionNav() {
  const t = useT()
  const go = (id) => (e) => {
    const root = e.currentTarget.closest('[data-slot="modal-body"], [role="dialog"]') || document
    const el = root.querySelector(`[data-dbq-section="${id}"]`)
    if (!el) return
    const reduce = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches
    try { el.scrollIntoView?.({ block: 'start', behavior: reduce ? 'auto' : 'smooth' }) } catch { /* jsdom */ }
  }
  return (
    <nav data-slot="dbq-form-nav" aria-label={t('dbq.form.navLabel')} className="mb-3 flex min-w-0 flex-wrap gap-1.5">
      {FORM_SECTIONS.map(({ id, icon: Icon, titleKey }) => (
        <Button key={id} type="button" variant="outline" size="sm" className="h-8 gap-1.5 px-2.5 text-xs pointer-coarse:h-10" onClick={go(id)}>
          <Icon aria-hidden="true" className="size-3.5" />{t(titleKey)}
        </Button>
      ))}
    </nav>
  )
}

/**
 * Sorgu bölümü: şablondan başla, sorgu düzenleyicisi (canlı güvenlik denetimi — yapı taşı), "Sorguyu dene" / "Maliyeti
 * denetle" / "Şablon olarak kaydet" ve deneme sonucu (satır verisi YOK).
 */
export function QuerySection({
  form, onSqlChange, sqlError, violations, onGuardChange, teamId, dbType,
  onPickTemplate, onSaveTemplate, onTest, onPlanCheck, testing, planChecking, testResult, planResult,
  canRun, runHint, disabled = false,
}) {
  const t = useT()
  return (
    <DbqSection id="query" icon={FileCode2} title={t('dbq.form.secQuery')} hint={t('dbq.form.secQueryHint')}>
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <TemplatePicker teamId={teamId} dbType={dbType} onPick={onPickTemplate} disabled={disabled || !teamId} />
        <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={onSaveTemplate}
          disabled={disabled || !teamId || !form.sqlText.trim()} data-slot="dbq-save-template">
          <Save aria-hidden="true" className="size-3.5" />{t('dbq.form.saveTemplate')}
        </Button>
      </div>
      <SqlEditor value={form.sqlText} onChange={onSqlChange} dbType={dbType} fieldError={sqlError}
        violations={violations} disabled={disabled} onGuardChange={onGuardChange} />
      <FormHint full={false}>
        <ShieldCheck aria-hidden="true" className="mr-1 inline size-3.5 align-[-2px]" />{t('dbq.form.sqlRules')}
      </FormHint>
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <Button type="button" variant="secondary" className="pointer-coarse:h-10" onClick={onTest} data-slot="dbq-test-run"
          aria-busy={testing || undefined} disabled={!canRun || testing} title={!canRun ? runHint : undefined}>
          <FlaskConical aria-hidden="true" className="size-4" />{testing ? t('dbq.form.testing') : t('dbq.form.test')}
        </Button>
        <Button type="button" variant="outline" className="pointer-coarse:h-10" onClick={onPlanCheck} data-slot="dbq-plan-run"
          aria-busy={planChecking || undefined} disabled={!canRun || planChecking} title={!canRun ? runHint : undefined}>
          <Gauge aria-hidden="true" className="size-4" />{planChecking ? t('dbq.form.planChecking') : t('dbq.form.planCheck')}
        </Button>
        {!canRun && runHint && <span className="text-xs text-muted-foreground">{runHint}</span>}
      </div>
      <DbQueryTestPanel result={testResult} plan={planResult} />
    </DbqSection>
  )
}

/** Değerlendirme: kural oluşturucu (yapı taşı) + yavaş sorgu eşiği. */
export function EvaluationSection({ form, setForm, ruleErrors, columns, slowError, onRulesChange, disabled = false }) {
  const t = useT()
  return (
    <DbqSection id="evaluation" icon={ListChecks} title={t('dbq.form.secEvaluation')} hint={t('dbq.form.secEvaluationHint')}>
      <RuleBuilder rules={form.rules} logic={form.ruleLogic} columns={columns} fieldErrors={ruleErrors} disabled={disabled}
        onChange={onRulesChange} onLogicChange={(logic) => setForm((f) => ({ ...f, ruleLogic: logic }))} />
      <FormGrid>
        <FormField label={t('dbq.form.slowQuery')} name="slow_query_ms" error={slowError} hint={t('dbq.form.slowQueryHint')}>
          {({ id, describedBy }) => (
            <InputGroup>
              <InputGroupInput id={id} aria-describedby={describedBy} type="number" min="100" max="600000" step="100"
                value={form.slowQueryMs} placeholder={t('dbq.form.slowQueryOff')} disabled={disabled}
                onChange={(e) => setForm((f) => ({ ...f, slowQueryMs: e.target.value }))} />
              <InputGroupAddon align="inline-end"><InputGroupText>ms</InputGroupText></InputGroupAddon>
            </InputGroup>
          )}
        </FormField>
      </FormGrid>
    </DbqSection>
  )
}

/** Zamanlama: aralık / cron + pencereler + tatil (yapı taşı; sonraki çalışmaları kendisi önizler). */
export function ScheduleSection({ value, onChange, fieldErrors, disabled = false }) {
  const t = useT()
  return (
    <DbqSection id="schedule" icon={CalendarClock} title={t('dbq.form.secSchedule')} hint={t('dbq.form.secScheduleHint')}>
      <div data-field="schedule" className="min-w-0">
        <ScheduleEditor value={value} onChange={onChange} fieldErrors={fieldErrors} disabled={disabled} />
      </div>
    </DbqSection>
  )
}

/** Sınırlar: zaman aşımı + en çok satır (tavanlar /defaults'tan; sunucu da aynısını uygular). */
export function LimitsSection({ form, setForm, errors, limits, disabled = false }) {
  const t = useT()
  const maxTimeout = limits?.max_timeout_seconds ?? 60
  const maxRows = limits?.max_rows_cap ?? 5000
  return (
    <DbqSection id="limits" icon={SlidersHorizontal} title={t('dbq.form.secLimits')} hint={t('dbq.form.secLimitsHint')}>
      <FormGrid>
        <FormField label={t('dbq.form.timeout')} name="query_timeout_seconds" error={errors.query_timeout_seconds}
          hint={t('dbq.form.timeoutHint', maxTimeout)}>
          {({ id, describedBy }) => (
            <InputGroup>
              <InputGroupInput id={id} aria-describedby={describedBy} type="number" min="5" max={maxTimeout} value={form.queryTimeoutSeconds}
                disabled={disabled} onChange={(e) => setForm((f) => ({ ...f, queryTimeoutSeconds: e.target.value }))} />
              <InputGroupAddon align="inline-end"><InputGroupText>{t('dbq.form.unitSec')}</InputGroupText></InputGroupAddon>
            </InputGroup>
          )}
        </FormField>
        <FormField label={t('dbq.form.maxRows')} name="max_rows" error={errors.max_rows} hint={t('dbq.form.maxRowsHint', maxRows)}>
          {({ id, describedBy }) => (
            <Input id={id} aria-describedby={describedBy} type="number" min="1" max={maxRows} value={form.maxRows}
              disabled={disabled} onChange={(e) => setForm((f) => ({ ...f, maxRows: e.target.value }))} />
          )}
        </FormField>
      </FormGrid>
    </DbqSection>
  )
}

/**
 * Alarm mailinin içeriği: konu şablonu (yer tutucu ipucu — tıklayınca eklenir), satırları maile ekle, mail kolonları,
 * maskelenecek kolonlar. Kolon önerileri son denemenin kolon ADLARINDAN (satır verisi değil).
 */
export function MailFields({ form, setForm, errors, columns = [], clear, disabled = false }) {
  const t = useT()
  const names = columns.map((c) => c.name)
  const insert = (ph) => { setForm((f) => ({ ...f, mailSubjectTemplate: `${f.mailSubjectTemplate || ''}${f.mailSubjectTemplate && !/\s$/.test(f.mailSubjectTemplate) ? ' ' : ''}${ph}` })); clear?.('mail_subject_template') }
  return (
    <div data-slot="dbq-mail" className="flex min-w-0 flex-col gap-3 sm:col-span-2">
      <FormSection title={t('dbq.form.mailTitle')} icon={Mail} hint={t('dbq.form.mailHint')} boxed={false}>
        <FormGrid>
          <FormField full label={t('dbq.form.subject')} name="mail_subject_template" error={errors.mail_subject_template}
            hint={t('dbq.form.subjectHint')}>
            {({ id, describedBy }) => (
              <Input id={id} aria-describedby={describedBy} value={form.mailSubjectTemplate} maxLength={200}
                placeholder={t('dbq.form.subjectPh')} disabled={disabled}
                onChange={(e) => { setForm((f) => ({ ...f, mailSubjectTemplate: e.target.value })); clear?.('mail_subject_template') }} />
            )}
          </FormField>
          <div data-slot="dbq-subject-placeholders" className="flex min-w-0 flex-wrap items-center gap-1.5 sm:col-span-2">
            <span className="text-xs text-muted-foreground">{t('dbq.form.placeholders')}</span>
            {SUBJECT_PLACEHOLDERS.map((ph) => (
              <Button key={ph} type="button" variant="outline" size="sm" disabled={disabled}
                className="h-7 px-2 font-mono text-[11px] pointer-coarse:h-10" onClick={() => insert(ph)}
                aria-label={t('dbq.form.insertPlaceholder', ph, t(`dbq.form.ph.${ph.slice(1, -1)}`))}
                title={t(`dbq.form.ph.${ph.slice(1, -1)}`)}>{ph}</Button>
            ))}
          </div>
          <CheckField full checked={form.mailIncludeRows} disabled={disabled}
            onCheckedChange={(v) => setForm((f) => ({ ...f, mailIncludeRows: v }))}
            label={t('dbq.form.includeRows')} hint={t('dbq.form.includeRowsHint')} />
          <div className={cn('flex min-w-0 flex-col gap-1.5 sm:col-span-2', !form.mailIncludeRows && 'opacity-80')} data-field="mail_columns">
            <span className="text-sm font-medium">{t('dbq.form.mailColumns')}</span>
            <TagInput value={listToCsv(form.mailColumns)} disabled={disabled || !form.mailIncludeRows} suggestions={names}
              placeholder={t('dbq.form.mailColumnsPh')}
              onChange={(v) => { setForm((f) => ({ ...f, mailColumns: csvToList(v) })); clear?.('mail_columns') }} />
            <FormHint full={false}>{t('dbq.form.mailColumnsHint')}</FormHint>
            {errors.mail_columns && <p role="alert" className="text-xs text-destructive">{errors.mail_columns}</p>}
          </div>
          <div className="flex min-w-0 flex-col gap-1.5 sm:col-span-2" data-field="masked_columns">
            <span className="text-sm font-medium">{t('dbq.form.maskedColumns')}</span>
            <TagInput value={listToCsv(form.maskedColumns)} disabled={disabled} suggestions={names}
              placeholder={t('dbq.form.maskedColumnsPh')}
              onChange={(v) => { setForm((f) => ({ ...f, maskedColumns: csvToList(v) })); clear?.('masked_columns') }} />
            <FormHint full={false}>{t('dbq.form.maskedColumnsHint')}</FormHint>
            {errors.masked_columns && <p role="alert" className="text-xs text-destructive">{errors.masked_columns}</p>}
          </div>
        </FormGrid>
      </FormSection>
    </div>
  )
}
