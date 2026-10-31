import { BellRing, CalendarClock, DatabaseZap, FileCode2, FlaskConical, Gauge, Info, ListChecks, Lock, Mail, Save, ShieldCheck, SlidersHorizontal } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import TagInput from '../ui/TagInput.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { InputGroup, InputGroupAddon, InputGroupInput, InputGroupText } from '@/components/shadcn/input-group'
import { cn } from '@/lib/utils'
import { CheckField, FormField, FormGrid, FormHint, FormSection } from '../monitoring/MonitorForm.jsx'
import { RuleBuilder, ScheduleEditor, SqlEditor, TemplatePicker } from './buildingBlocks.jsx'
import DbQueryTestPanel from './DbQueryTestPanel.jsx'
import BacktestPanel from './BacktestPanel.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
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

/** Bölüm çapası + başlıklı çerçeve (iki sütunu kaplar). Kaydırma payı yapışkan atlama çubuğunun yüksekliği kadar. */
export function DbqSection({ id, title, hint, icon, children }) {
  return (
    <div data-dbq-section={id} className="min-w-0 scroll-mt-16 sm:col-span-2">
      <FormSection title={title} hint={hint} icon={icon}>{children}</FormSection>
    </div>
  )
}

/**
 * Bölüm atlama çubuğu — her düğme bölümüne kaydırır (hareket-azalt tercihine uyar); 40 px dokunma hedefi. 2026-10-31
 * (r177, 2.C): pencere gövdesinde YAPIŞKAN (kaydırınca kaybolmaz); telefonda tek satır, yatay kayar (3 satır kaplamaz),
 * geniş ekranda sarar; hatalı alan taşıyan bölümün düğmesinde hata sayısı (`errorCounts` — `{ bölüm: sayı }`).
 */
export function FormSectionNav({ errorCounts = null }) {
  const t = useT()
  const go = (id) => (e) => {
    const root = e.currentTarget.closest('[data-slot="modal-shell-body"], [data-slot="modal-body"], [role="dialog"]') || document
    const el = root.querySelector(`[data-dbq-section="${id}"]`)
    if (!el) return
    const reduce = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches
    try { el.scrollIntoView?.({ block: 'start', behavior: reduce ? 'auto' : 'smooth' }) } catch { /* jsdom */ }
  }
  return (
    <nav data-slot="dbq-form-nav" aria-label={t('dbq.form.navLabel')}
      className="sticky top-0 z-10 -mx-1 mb-3 flex min-w-0 flex-nowrap gap-1.5 overflow-x-auto bg-background px-1 py-1.5 sm:flex-wrap sm:overflow-x-visible">
      {FORM_SECTIONS.map(({ id, icon: Icon, titleKey }) => {
        const n = Number(errorCounts?.[id]) || 0
        return (
          <Button key={id} type="button" variant="outline" size="sm" data-section={id} data-errors={n || undefined}
            aria-label={n === 1 ? t('dbq.form.navInvalid.one', t(titleKey)) : n ? t('dbq.form.navInvalid', t(titleKey), n) : undefined}
            className={cn('h-8 shrink-0 gap-1.5 px-2.5 text-xs pointer-coarse:h-10', n && 'border-destructive/60 text-destructive')} onClick={go(id)}>
            <Icon aria-hidden="true" className="size-3.5" />{t(titleKey)}
            {n > 0 && (
              <span data-slot="dbq-form-nav-errors" aria-hidden="true"
                className="inline-flex min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] leading-4 font-bold text-white tabular-nums">{n}</span>
            )}
          </Button>
        )
      })}
    </nav>
  )
}

/** Paylaşımla düzenlenen kayıtta kilitli alanların açıklaması (K2) — bölümün başında, neden değiştirilemediğini söyler. */
export function GrantLockNote({ children }) {
  return (
    <p data-slot="dbq-grant-locked" className="m-0 flex min-w-0 items-start gap-1.5 rounded-md border border-dashed bg-muted/40 px-2.5 py-2 text-xs text-muted-foreground">
      <Lock aria-hidden="true" className="mt-px size-3.5 shrink-0" />
      <span className="[overflow-wrap:anywhere]">{children}</span>
    </p>
  )
}

/**
 * Sorgu bölümü: şablondan başla, sorgu düzenleyicisi (canlı güvenlik denetimi — yapı taşı), "Sorguyu dene" / "Maliyeti
 * denetle" / "Şablon olarak kaydet" ve deneme sonucu (satır verisi YOK). `locked` (K2 — paylaşımla düzenlenen kayıt):
 * sorgu salt okunur, deneme / maliyet / şablon düğmeleri çizilmez (sunucu bunları yalnız takımın kendi kapsamına açar).
 */
export function QuerySection({
  form, onSqlChange, sqlError, violations, onGuardChange, teamId, dbType,
  onPickTemplate, onSaveTemplate, onTest, onPlanCheck, testing, planChecking, testResult, planResult,
  canRun, runHint, disabled = false, locked = false, planAvailable = true, onColumnAction = null,
}) {
  const t = useT()
  return (
    <DbqSection id="query" icon={FileCode2} title={t('dbq.form.secQuery')} hint={t('dbq.form.secQueryHint')}>
      {locked ? <GrantLockNote>{t('dbq.form.grantLockedQuery')}</GrantLockNote> : (
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <TemplatePicker teamId={teamId} dbType={dbType} onPick={onPickTemplate} disabled={disabled || !teamId} />
          <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={onSaveTemplate}
            disabled={disabled || !teamId || !form.sqlText.trim()} data-slot="dbq-save-template">
            <Save aria-hidden="true" className="size-3.5" />{t('dbq.form.saveTemplate')}
          </Button>
        </div>
      )}
      <SqlEditor value={form.sqlText} onChange={onSqlChange} dbType={dbType} fieldError={sqlError}
        violations={violations} disabled={disabled || locked} onGuardChange={onGuardChange} />
      <FormHint full={false}>
        <ShieldCheck aria-hidden="true" className="mr-1 inline size-3.5 align-[-2px]" />{t('dbq.form.sqlRules')}
      </FormHint>
      {!locked && <div className="flex min-w-0 flex-wrap items-center gap-2">
        <Button type="button" variant="secondary" className="pointer-coarse:h-10" onClick={onTest} data-slot="dbq-test-run"
          aria-busy={testing || undefined} disabled={!canRun || testing} title={!canRun ? runHint : undefined}>
          <FlaskConical aria-hidden="true" className="size-4" />{testing ? t('dbq.form.testing') : t('dbq.form.test')}
        </Button>
        {/* Maliyet denetimi kapalı (ayar OFF) ya da Oracle (desteklenmiyor): düğme çizilmez — "denetlendi, uygun" sanılmasın (2.A) */}
        {planAvailable ? (
          <Button type="button" variant="outline" className="pointer-coarse:h-10" onClick={onPlanCheck} data-slot="dbq-plan-run"
            aria-busy={planChecking || undefined} disabled={!canRun || planChecking} title={!canRun ? runHint : undefined}>
            <Gauge aria-hidden="true" className="size-4" />{planChecking ? t('dbq.form.planChecking') : t('dbq.form.planCheck')}
          </Button>
        ) : (
          <span data-slot="dbq-plan-off" className="text-xs text-muted-foreground">
            {t(dbType === 'ORACLE' ? 'dbq.form.planOracle' : 'dbq.form.planOff')}
          </span>
        )}
        {!canRun && runHint && <span className="text-xs text-muted-foreground">{runHint}</span>}
      </div>}
      {!locked && <DbQueryTestPanel result={testResult} plan={planResult} onColumnAction={onColumnAction}
        maskedColumns={form.maskedColumns} actionsDisabled={disabled} />}
    </DbqSection>
  )
}

/** Değerlendirme: kural oluşturucu (yapı taşı) + yavaş sorgu eşiği. `locked` (K2): kurallar, birleşim ve eşik salt okunur. */
export function EvaluationSection({
  form, setForm, ruleErrors, columns, slowError, onRulesChange, clear, disabled: off = false, locked = false,
  trapIndexes = null, trapColumn = null, newRuleFactory = null, backtestMonitorId = null,
}) {
  const t = useT()
  const disabled = off || locked
  return (
    <DbqSection id="evaluation" icon={ListChecks} title={t('dbq.form.secEvaluation')} hint={t('dbq.form.secEvaluationHint')}>
      {locked && <GrantLockNote>{t('dbq.form.grantLockedRules')}</GrantLockNote>}
      <RuleBuilder rules={form.rules} logic={form.ruleLogic} columns={columns} fieldErrors={ruleErrors} disabled={disabled}
        trapIndexes={trapIndexes} trapColumn={trapColumn} newRuleFactory={newRuleFactory}
        onChange={onRulesChange} onLogicChange={(logic) => setForm((f) => ({ ...f, ruleLogic: logic }))} />
      {/* Geriye dönük eşik denemesi (2.D) — yalnız kayıtlı izlemede (saklı ölçüm gerekir) ve kurallar düzenlenebilirken */}
      {backtestMonitorId != null && !locked && (
        <BacktestPanel monitorId={backtestMonitorId} rules={form.rules} ruleLogic={form.ruleLogic} disabled={off} />
      )}
      <FormGrid>
        <FormField label={t('dbq.form.slowQuery')} name="slow_query_ms" error={slowError} hint={t('dbq.form.slowQueryHint')}>
          {({ id, describedBy }) => (
            <InputGroup>
              <InputGroupInput id={id} aria-describedby={describedBy} type="number" min="100" max="600000" step="100"
                value={form.slowQueryMs} placeholder={t('dbq.form.slowQueryOff')} disabled={disabled}
                onChange={(e) => { setForm((f) => ({ ...f, slowQueryMs: e.target.value })); clear?.('slow_query_ms') }} />
              <InputGroupAddon align="inline-end"><InputGroupText>ms</InputGroupText></InputGroupAddon>
            </InputGroup>
          )}
        </FormField>
      </FormGrid>
    </DbqSection>
  )
}

/** Zamanlama: aralık / cron + pencereler + tatil (yapı taşı; sonraki çalışmaları kendisi önizler). */
export function ScheduleSection({ value, onChange, fieldErrors, disabled = false, minIntervalSeconds, canEditHolidays = false }) {
  const t = useT()
  return (
    <DbqSection id="schedule" icon={CalendarClock} title={t('dbq.form.secSchedule')} hint={t('dbq.form.secScheduleHint')}>
      <div data-field="schedule" className="min-w-0">
        <ScheduleEditor value={value} onChange={onChange} fieldErrors={fieldErrors} disabled={disabled}
          minIntervalSeconds={minIntervalSeconds} canEditHolidays={canEditHolidays} />
      </div>
    </DbqSection>
  )
}

/** Sınırlar: zaman aşımı + en çok satır (tavanlar /defaults'tan; sunucu da aynısını uygular). `locked` (K2): salt okunur. */
export function LimitsSection({ form, setForm, errors, limits, clear, disabled: off = false, locked = false }) {
  const t = useT()
  const maxTimeout = limits?.max_timeout_seconds ?? 60
  const maxRows = limits?.max_rows_cap ?? 5000
  const disabled = off || locked
  return (
    <DbqSection id="limits" icon={SlidersHorizontal} title={t('dbq.form.secLimits')} hint={t('dbq.form.secLimitsHint')}>
      {locked && <GrantLockNote>{t('dbq.form.grantLockedLimits')}</GrantLockNote>}
      <FormGrid>
        <FormField label={t('dbq.form.timeout')} name="query_timeout_seconds" error={errors.query_timeout_seconds}
          hint={t('dbq.form.timeoutHint', maxTimeout)}>
          {({ id, describedBy }) => (
            <InputGroup>
              <InputGroupInput id={id} aria-describedby={describedBy} type="number" min="5" max={maxTimeout} value={form.queryTimeoutSeconds}
                disabled={disabled} onChange={(e) => { setForm((f) => ({ ...f, queryTimeoutSeconds: e.target.value })); clear?.('query_timeout_seconds') }} />
              <InputGroupAddon align="inline-end"><InputGroupText>{t('dbq.form.unitSec')}</InputGroupText></InputGroupAddon>
            </InputGroup>
          )}
        </FormField>
        <FormField label={t('dbq.form.maxRows')} name="max_rows" error={errors.max_rows} hint={t('dbq.form.maxRowsHint', maxRows)}>
          {({ id, describedBy }) => (
            <Input id={id} aria-describedby={describedBy} type="number" min="1" max={maxRows} value={form.maxRows}
              disabled={disabled} onChange={(e) => { setForm((f) => ({ ...f, maxRows: e.target.value })); clear?.('max_rows') }} />
          )}
        </FormField>
      </FormGrid>
    </DbqSection>
  )
}

/**
 * Alarm mailinin içeriği: konu şablonu (yer tutucu ipucu — tıklayınca eklenir), satırları maile ekle, mail kolonları,
 * maskelenecek kolonlar. Kolon önerileri son denemenin kolon ADLARINDAN (satır verisi değil). Sınır ipuçları ayarlı
 * değerlerden (`limits.mail_max_rows` / `mail_max_columns`, `/monitoring/defaults`; DÜŞÜK 9). `locked` (K2): salt okunur.
 */
export function MailFields({ form, setForm, errors, columns = [], clear, limits, disabled: off = false, locked = false, suggestedMasks = [] }) {
  const t = useT()
  const names = columns.map((c) => c.name)
  const mailMaxRows = positiveInt(limits?.mail_max_rows, 50)
  const mailMaxColumns = positiveInt(limits?.mail_max_columns, 20)
  const disabled = off || locked
  const selectedMode = form.mailColumnsMode === 'SELECTED'
  // Sunucunun hassas bulduğu (adından) ama henüz maskelenmemiş kolonlar (2.B) — tek tıkla maskeye
  const maskedLower = new Set((form.maskedColumns || []).map((c) => String(c).toLowerCase()))
  const pendingMasks = [...new Set((Array.isArray(suggestedMasks) ? suggestedMasks : []).map((c) => String(c)).filter(Boolean))]
    .filter((c) => !maskedLower.has(c.toLowerCase()))
  const addMasks = (cols) => {
    setForm((f) => ({ ...f, maskedColumns: csvToList(listToCsv([...(f.maskedColumns || []), ...cols])) }))
    clear?.('masked_columns')
  }
  const insert = (ph) => { setForm((f) => ({ ...f, mailSubjectTemplate: `${f.mailSubjectTemplate || ''}${f.mailSubjectTemplate && !/\s$/.test(f.mailSubjectTemplate) ? ' ' : ''}${ph}` })); clear?.('mail_subject_template') }
  return (
    <div data-slot="dbq-mail" className="flex min-w-0 flex-col gap-3 sm:col-span-2">
      <FormSection title={t('dbq.form.mailTitle')} icon={Mail} hint={t('dbq.form.mailHint')} boxed={false}>
        {locked && <GrantLockNote>{t('dbq.form.grantLockedMail')}</GrantLockNote>}
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
            label={t('dbq.form.includeRows')} hint={t('dbq.form.includeRowsHint', mailMaxRows)} />
          {/* Mailde hangi kolonlar (2.B): "Seçtiğim kolonlar" (yeni izlemede varsayılan — liste boşsa satır tablosu YOK, sayı
              var) / "İlk N kolon" (önceki davranış). Mevcut kayıtlar alan yoksa ilk N olarak açılır. */}
          <div className={cn('flex min-w-0 flex-col gap-1.5 sm:col-span-2', !form.mailIncludeRows && 'opacity-80')} data-field="mail_columns_mode">
            <span className="text-sm font-semibold">{t('dbq.form.mailColumnsMode')}</span>
            <SegmentedControl value={selectedMode ? 'SELECTED' : 'FIRST_N'} ariaLabel={t('dbq.form.mailColumnsMode')}
              className="w-full sm:w-auto" itemClassName="flex-1 sm:flex-none"
              onChange={(v) => { if (!(disabled || !form.mailIncludeRows)) { setForm((f) => ({ ...f, mailColumnsMode: v })); clear?.('mail_columns_mode') } }}
              options={[
                { value: 'SELECTED', label: t('dbq.form.mailModeSelected'), disabled: disabled || !form.mailIncludeRows },
                { value: 'FIRST_N', label: t('dbq.form.mailModeFirstN', mailMaxColumns), disabled: disabled || !form.mailIncludeRows },
              ]} />
            <FormHint full={false}>
              <span data-slot="dbq-mail-mode-hint">
                {selectedMode
                  ? t((form.mailColumns || []).length ? 'dbq.form.mailModeSelectedHint' : 'dbq.form.mailModeSelectedEmpty')
                  : t('dbq.form.mailModeFirstNHint', mailMaxColumns)}
              </span>
            </FormHint>
          </div>
          {/* Başlık TagInput'un kendi <label>'ı — giriş kutusunun erişilebilir adı (iki kutu aynı yer tutucuyu taşır; 10a) */}
          <div className={cn('flex min-w-0 flex-col gap-1.5 sm:col-span-2', !form.mailIncludeRows && 'opacity-80')} data-field="mail_columns">
            <TagInput label={t('dbq.form.mailColumns')} value={listToCsv(form.mailColumns)} disabled={disabled || !form.mailIncludeRows} suggestions={names}
              placeholder={t('dbq.form.mailColumnsPh')}
              onChange={(v) => { setForm((f) => ({ ...f, mailColumns: csvToList(v) })); clear?.('mail_columns') }} />
            <FormHint full={false}>{t(selectedMode ? 'dbq.form.mailColumnsHintSelected' : 'dbq.form.mailColumnsHint', mailMaxColumns)}</FormHint>
            {errors.mail_columns && <p role="alert" className="text-xs text-destructive">{errors.mail_columns}</p>}
          </div>
          <div className="flex min-w-0 flex-col gap-1.5 sm:col-span-2" data-field="masked_columns">
            <TagInput label={t('dbq.form.maskedColumns')} value={listToCsv(form.maskedColumns)} disabled={disabled} suggestions={names}
              placeholder={t('dbq.form.maskedColumnsPh')}
              onChange={(v) => { setForm((f) => ({ ...f, maskedColumns: csvToList(v) })); clear?.('masked_columns') }} />
            <FormHint full={false}>{t('dbq.form.maskedColumnsHint')}</FormHint>
            {pendingMasks.length > 0 && !disabled && (
              <div data-slot="dbq-suggested-masks" role="note"
                className="flex min-w-0 flex-col gap-1.5 rounded-md border border-amber-500/40 bg-amber-500/5 px-2.5 py-2 text-xs dark:bg-amber-500/10">
                <span className="text-amber-800 dark:text-amber-200">{t('dbq.form.suggestedMasks')}</span>
                <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                  {pendingMasks.map((c) => (
                    <Button key={c} type="button" variant="outline" size="sm" onClick={() => addMasks([c])} data-slot="dbq-suggested-mask"
                      aria-label={t('dbq.form.addMaskOf', c)} className="h-7 max-w-full px-2 font-mono text-[11px] whitespace-normal [overflow-wrap:anywhere] pointer-coarse:h-10">
                      + {c}
                    </Button>
                  ))}
                  {pendingMasks.length > 1 && (
                    <Button type="button" variant="secondary" size="sm" onClick={() => addMasks(pendingMasks)} data-slot="dbq-suggested-mask-all"
                      className="h-7 px-2 text-[11px] pointer-coarse:h-10">{t('dbq.form.addAllMasks')}</Button>
                  )}
                </div>
              </div>
            )}
            {errors.masked_columns && <p role="alert" className="text-xs text-destructive">{errors.masked_columns}</p>}
          </div>
        </FormGrid>
      </FormSection>
    </div>
  )
}

/** Ayarlı sınır → pozitif tamsayı; yoksa / bozuksa varsayılan. */
function positiveInt(v, def) {
  const n = Number(v)
  return Number.isInteger(n) && n > 0 ? n : def
}
