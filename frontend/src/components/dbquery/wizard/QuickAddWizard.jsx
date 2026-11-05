import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowLeft, ArrowRight, CalendarClock, Check, CircleAlert, ClipboardCheck, DatabaseZap, FileCode2, FlaskConical, Layers,
  ListChecks, PanelsTopLeft, Wand2,
} from 'lucide-react'
import { api } from '../../../api/client'
import { useLanguage, useT } from '../../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { Switch } from '@/components/shadcn/switch'
import { Label } from '@/components/shadcn/label'
import { InputGroup, InputGroupAddon, InputGroupInput, InputGroupText } from '@/components/shadcn/input-group'
import { cn } from '@/lib/utils'
import ModalShell from '../../ui/ModalShell.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import TagInput from '../../ui/TagInput.jsx'
import NotifyChannels from '../../ui/NotifyChannels.jsx'
import NocNotifyField from '../../noc/forms/NocNotifyField.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { FormField, FormGrid, FormHint, FormSection } from '../../monitoring/MonitorForm.jsx'
import { ConnectionPicker, RuleBuilder, ScheduleEditor, SqlEditor, TemplatePicker } from '../buildingBlocks.jsx'
import DbQueryTestPanel from '../DbQueryTestPanel.jsx'
import { BulkCreatePanel, defaultBulkPattern, useBulkCreate, useTeamConnections } from '../BulkCreate.jsx'
import { builtinTemplates, dbaTemplates } from '../builtinTemplates.js'
import { MAX_RULES, ruleSummary } from '../ruleModel.js'
import { countTrapIndexes, defaultNewRule, primaryValueColumn, ruleFromColumn } from '../ruleSuggest.js'
import {
  columnsFrom, dbTypeLabel, rateLimitText, retryAfterOf, scheduleSummary, serverFieldErrors, splitFieldErrors,
} from '../dbqueryPageModel.js'
import { bulkPlan, planReady } from '../bulkCreateModel.js'
import {
  PURPOSE_MAX, WIZ_STEPS, canJump, firstErrorStep, firstInvalidStep, stepErrorCounts, suggestName, translateErrors, validateStep,
} from './wizardModel.js'

const STEP_ICON = {
  connection: DatabaseZap, query: FileCode2, test: FlaskConical, rules: ListChecks, schedule: CalendarClock, summary: ClipboardCheck,
}

/**
 * "Hızlı ekle" sihirbazı (B1, 2026-11-05) — Veritabanı Sorgu İzleme'yi adım adım kurar: Bağlantı → Şablon ya da sorgu →
 * Dene → Kural → Zamanlama ve bildirim → Özet. Her adım kendi doğrulamasıyla ilerler; geri gidilebilir, değerler adımlar
 * arasında ve "Gelişmiş görünüm"e (uzun form) geçişte KAYBOLMAZ. Kaydet mevcut create ucunu kullanır; sunucunun alan
 * hataları ilgili adıma döner (sihirbazda alanı olmayan hata → bant + "Gelişmiş görünümde düzelt"). Özet adımında "Birden
 * çok bağlantı için oluştur" (B4) aynı gövdeyi seçilen bağlantılar için SIRAYLA oluşturur.
 *
 * Güvenlik kuralları aynen: deneme satır göstermez (yalnız kolon ADLARI / sayılar), bağlantı takım belgesidir (yalnız
 * kendi takımları; paylaşım takımı seçeneklerde yok), parola hiçbir adımda görünmez.
 *
 * Props: `initialForm` (sayfanın yeni-izleme formu, varsayılanlar uygulanmış), `toPayload(form)` (sayfanın istek gövdesi
 * eşlemesi — iki giriş AYNI gövdeyi kurar), `canPickTeam` / `teamOptions` (yalnız kendi takımları) / `fixedTeamName`,
 * `managerTeams`, `canManageConnections`, `limits` (`/monitoring/defaults`), `globalAdmin`, `onSaved(rows, info)`,
 * `onAdvanced(form)`.
 */
export default function QuickAddWizard({
  open, onClose, initialForm, toPayload, canPickTeam = false, teamOptions = [], fixedTeamName = null, managerTeams = [],
  canManageConnections = false, limits = null, globalAdmin = false, onSaved, onAdvanced,
}) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [form, setForm] = useState(initialForm)
  const initialJson = useRef(JSON.stringify(toPayload(initialForm)))
  const [step, setStep] = useState('connection')
  const [furthest, setFurthest] = useState('connection')
  const [errors, setErrors] = useState({})
  const [guard, setGuard] = useState({ status: 'empty' })
  const [sqlViolations, setSqlViolations] = useState(null)
  const [profile, setProfile] = useState(null)
  const [templateName, setTemplateName] = useState('')
  const [testResult, setTestResult] = useState(null)
  const [planResult, setPlanResult] = useState(null)
  const [testing, setTesting] = useState(false)
  const [columns, setColumns] = useState([])
  const [saving, setSaving] = useState(false)
  const [banner, setBanner] = useState(null)   // { message, advanced }
  const [bulkOn, setBulkOn] = useState(false)
  const [teamGroups, setTeamGroups] = useState([])
  const [teamTags, setTeamTags] = useState([])
  const testSeq = useRef(0)
  const headingRef = useRef(null)
  const bodyRef = useRef(null)

  const formTeamId = form.teamId === '' || form.teamId == null ? null : Number(form.teamId)
  const dbType = form.dbType || null
  const idx = WIZ_STEPS.indexOf(step)
  const ctx = { guardStatus: guard?.status, needTeam: true }
  const counts = stepErrorCounts(errors)
  const [connLoad, reloadConns] = useTeamConnections(bulkOn ? formTeamId : null)
  const baseBody = useMemo(() => (bulkOn ? toPayload(form) : null), [bulkOn, form, toPayload])
  const bulk = useBulkCreate({
    create: api.monitoring.createDbQueryMonitor, baseBody, baseName: form.name.trim(), initialProfileId: form.profileId,
    defaultPattern: defaultBulkPattern(t),
  })

  // Takımın grup / etiket önerileri (Özet adımı)
  useEffect(() => {
    if (formTeamId == null) { setTeamGroups([]); setTeamTags([]); return undefined }
    let alive = true
    api.monitoring.listGroups?.(formTeamId, 'dbquery')?.then((r) => { if (alive && r?.success) setTeamGroups(r.data || []) })?.catch(() => {})
    api.monitoring.listTags?.(formTeamId)?.then((r) => { if (alive) setTeamTags(r?.success ? (r.data || []) : []) })?.catch(() => {})
    return () => { alive = false }
  }, [formTeamId])

  // Adım değişince gövde başa, odak adım başlığına (ekran okuyucu yeni adımı duyar)
  useEffect(() => {
    if (bodyRef.current) bodyRef.current.scrollTop = 0
    headingRef.current?.focus?.({ preventScroll: true })
  }, [step])

  const dirty = () => JSON.stringify(toPayload(form)) !== initialJson.current
  const close = async () => {
    if (saving || bulk.running) return
    if (dirty() && !bulk.rows) {
      const ok = await showConfirm({
        title: t('dbq.form.discardTitle'), message: t('dbq.wiz.discardMsg'),
        confirmText: t('dbq.form.discard'), cancelText: t('dbq.form.keepEditing'), variant: 'danger',
      })
      if (!ok) return
    }
    onClose?.()
  }

  const clearKeys = (pred) => setErrors((e) => {
    const next = Object.fromEntries(Object.entries(e).filter(([k]) => !pred(k)))
    return Object.keys(next).length === Object.keys(e).length ? e : next
  })
  const cancelTest = () => { testSeq.current++; setTesting(false) }
  const resetTest = () => { cancelTest(); setTestResult(null); setPlanResult(null) }

  /** Hatalı adıma git + ilk hatalı alana odak. */
  const showErrors = (map, target) => {
    setErrors(map)
    if (target && target !== step && WIZ_STEPS.includes(target)) setStep(target)
    const first = Object.keys(map)[0]
    if (!first) return
    const focus = () => {
      const root = document.querySelector('[data-slot="dbq-wiz"]')
      const base = first.startsWith('rules[') ? 'rules' : first
      const el = root?.querySelector(`[data-field="${base}"] input, [data-field="${base}"] textarea, [data-field="${base}"] [role="combobox"], [data-field="${base}"] button`)
      el?.focus?.()
      try { el?.scrollIntoView?.({ block: 'center' }) } catch { /* jsdom */ }
    }
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(() => requestAnimationFrame(focus)); else focus()
  }

  const validateCurrent = (s = step) => translateErrors(validateStep(s, form, ctx), t)

  const goTo = (target) => {
    const ti = WIZ_STEPS.indexOf(target)
    if (ti < 0 || target === step) return
    if (ti < idx) { setStep(target); return }
    if (!canJump(target, step, furthest)) return
    // İleri atlama: aradaki adımların hepsi geçerli olmalı
    for (let i = idx; i < ti; i++) {
      const errs = translateErrors(validateStep(WIZ_STEPS[i], form, ctx), t)
      if (Object.keys(errs).length) { showErrors(errs, WIZ_STEPS[i]); return }
    }
    prefillName(target)
    setStep(target)
  }
  /** Özete ilk gelişte boş ada öneri (şablon adı ya da "Sorgu · <bağlantı>"). */
  const prefillName = (target) => {
    if (target === 'summary' && !form.name.trim()) {
      setForm((f) => ({ ...f, name: suggestName({ templateName, connectionName: profile?.name, t }).slice(0, 200) }))
    }
  }

  const next = () => {
    const errs = validateCurrent()
    if (Object.keys(errs).length) { showErrors(errs, step); return }
    const target = WIZ_STEPS[idx + 1]
    if (!target) return
    prefillName(target)
    setStep(target)
    if (WIZ_STEPS.indexOf(target) > WIZ_STEPS.indexOf(furthest)) setFurthest(target)
  }
  const back = () => { if (idx > 0) setStep(WIZ_STEPS[idx - 1]) }

  // ── Alan değişimleri ─────────────────────────────────────────────────────────────────────────────────────────────
  const set = (patch, keys = []) => {
    setForm((f) => ({ ...f, ...patch }))
    if (keys.length) clearKeys((k) => keys.includes(k))
  }
  const onTeamChange = (v) => {
    setForm((f) => (String(f.teamId) === String(v) ? f : { ...f, teamId: v, profileId: null, dbType: '', groupName: '' }))
    setProfile(null); resetTest(); bulk.setSelected(new Set())
    clearKeys((k) => k === 'team_id' || k === 'profile_id')
  }
  const onConnectionChange = (profileId, p) => {
    setForm((f) => ({ ...f, profileId: profileId ?? null, dbType: p?.db_type || (profileId == null ? '' : f.dbType) }))
    setProfile(p || null); resetTest()
    if (profileId != null) bulk.setSelected((s) => (s.size ? s : new Set([profileId])))
    clearKeys((k) => k === 'profile_id')
  }
  const onSqlChange = (sql) => {
    setForm((f) => ({ ...f, sqlText: sql }))
    setSqlViolations(null)
    clearKeys((k) => k === 'sql_text')
  }
  const onRulesChange = (rules) => {
    setForm((f) => ({ ...f, rules }))
    clearKeys((k) => k === 'rules' || k.startsWith('rules['))
  }

  /** Şablon (yerleşik / DBA / takım) uygula — dolu sorgu ya da kurulu kurallar değişecekse onay. */
  const applyTemplate = async (tpl) => {
    if (!tpl) return
    const rulesReplaced = Array.isArray(tpl.rules) && form.rules.length > 0 && JSON.stringify(form.rules) !== JSON.stringify(tpl.rules)
    if ((form.sqlText.trim() && form.sqlText !== tpl.sql_text) || rulesReplaced) {
      const ok = await showConfirm({ title: t('dbq.form.tplApplyTitle'), message: t('dbq.form.tplApplyMsg', tpl.name || ''),
        confirmText: t('dbq.form.tplApply'), cancelText: t('dbq.form.keepEditing') })
      if (!ok) return
    }
    setForm((f) => ({
      ...f, sqlText: tpl.sql_text || '',
      purpose: f.purpose.trim() ? f.purpose : (tpl.purpose || ''),
      rules: Array.isArray(tpl.rules) ? tpl.rules : f.rules,
      ruleLogic: tpl.rule_logic === 'ALL' ? 'ALL' : 'ANY',
      // DBA şablonunun önerilen aralığı (B5) — yalnız aralık kipindeyse
      schedule: tpl.interval_seconds > 0 && f.schedule?.schedule_mode !== 'CRON'
        ? { ...f.schedule, schedule_mode: 'INTERVAL', interval_seconds: tpl.interval_seconds } : f.schedule,
    }))
    setTemplateName(tpl.name || '')
    setSqlViolations(null); resetTest()
    clearKeys((k) => k === 'sql_text' || k === 'rules' || k.startsWith('rules['))
    toast.success(t('dbq.wiz.templateApplied', tpl.name || ''))
  }

  // ── Dene ──────────────────────────────────────────────────────────────────────────────────────────────────────────
  const canRun = form.profileId != null && !!form.sqlText.trim()
  const runTest = async () => {
    if (!canRun) return
    const my = ++testSeq.current
    setTesting(true); setTestResult(null)
    try {
      const res = await api.monitoring.testDbQuery(toPayload(form))
      if (my !== testSeq.current) return
      if (res?.success) {
        setTestResult(res.data || {})
        setColumns(columnsFrom(res.data))
        if (Array.isArray(res.data?.guard?.violations) && res.data.guard.violations.length) setSqlViolations(res.data.guard.violations)
      } else {
        const limited = res?.code === 'RATE_LIMITED'
        if (res?.code === 'DBQUERY_SQL_REJECTED') { const { violations } = serverFieldErrors(res); if (violations) setSqlViolations(violations) }
        setTestResult({ error: limited ? rateLimitText(res, t, 'dbq.test.failedGeneric') : (res?.error || t('dbq.test.failedGeneric')),
          code: res?.code || null, retryAfter: limited ? null : retryAfterOf(res) })
      }
    } catch (e) {
      if (my === testSeq.current) setTestResult({ error: e?.message || t('dbq.test.failedGeneric'), code: 'NETWORK_ERROR' })
    } finally {
      if (my === testSeq.current) setTesting(false)
    }
  }
  const onColumnAction = (action, column) => {
    if (!column?.name) return
    if (action === 'mask') {
      setForm((f) => ({ ...f, maskedColumns: [...new Set([...(f.maskedColumns || []), column.name])] }))
      toast.success(t('dbq.form.maskAdded', column.name))
      return
    }
    if (!String(action).startsWith('rule:')) return
    if (form.rules.length >= MAX_RULES) { toast.info(t('dbq.rule.maxReached', MAX_RULES)); return }
    onRulesChange([...form.rules, ruleFromColumn(column, String(action).slice(5))])
    toast.success(t('dbq.wiz.ruleAddedNext', column.name))
  }

  const trapIndexes = useMemo(() => countTrapIndexes(form.rules, { warnings: testResult?.warnings, testResult, sqlText: form.sqlText }),
    [form.rules, testResult, form.sqlText])
  const trapColumn = useMemo(() => primaryValueColumn(columns), [columns])
  const newRuleFactory = useCallback(() => defaultNewRule(testResult, columns), [testResult, columns])
  const suggestedRule = useMemo(() => (form.rules.length === 0 && testResult && !testResult.error ? defaultNewRule(testResult, columns) : null),
    [form.rules.length, testResult, columns])

  // ── Kaydet ────────────────────────────────────────────────────────────────────────────────────────────────────────
  const bulkPlanRows = bulkOn ? bulkPlan(bulk.pattern, form.name.trim(), connLoad.list.filter((p) => bulk.selected.has(p.id))) : []
  const bulkReady = bulkOn && planReady(bulkPlanRows)

  const save = async () => {
    setBanner(null)
    const invalid = firstInvalidStep(form, ctx)
    if (invalid) { showErrors(translateErrors(invalid.errors, t), invalid.step); return }
    if (bulkOn) {
      const results = await bulk.run(connLoad.list)
      if (results) onSaved?.(results.filter((r) => r.status === 'ok').map((r) => ({ id: r.id, name: r.name })), { bulk: results })
      return
    }
    setSaving(true)
    try {
      const res = await api.monitoring.createDbQueryMonitor(toPayload(form))
      if (res?.success) { onSaved?.([res.data], { response: res }); onClose?.(); return }
      const { fields, violations, plan } = serverFieldErrors(res)
      if (violations) setSqlViolations(violations)
      if (plan) setPlanResult(plan)
      const target = firstErrorStep(fields)
      if (fields && target && target !== 'advanced') {
        const visible = Object.fromEntries(Object.entries(fields).filter(([k]) => firstErrorStep({ [k]: 'x' }) !== 'advanced'))
        const hidden = Object.entries(fields).filter(([k]) => firstErrorStep({ [k]: 'x' }) === 'advanced')
        if (hidden.length) setBanner({ message: hidden.map(([, v]) => v).join(' · '), advanced: true })
        showErrors(visible, target)
        return
      }
      setBanner({ message: (fields && Object.values(fields).join(' · ')) || res?.error || t('dbq.form.saveFailed'), advanced: target === 'advanced' })
    } catch (e) {
      setBanner({ message: e?.message || t('dbq.form.saveFailed'), advanced: false })
    } finally {
      setSaving(false)
    }
  }

  const toAdvanced = () => { if (!saving && !bulk.running) onAdvanced?.(form) }

  // ── Görünüm ───────────────────────────────────────────────────────────────────────────────────────────────────────
  const { rules: ruleErrors, schedule: scheduleErrors } = splitFieldErrors(errors)
  const bulkDone = !!bulk.rows && !bulk.running
  const footer = (
    <div data-slot="dbq-wiz-footer" className="flex w-full min-w-0 flex-wrap items-center gap-2">
      <Button type="button" variant="ghost" onClick={toAdvanced} disabled={saving || bulk.running || bulkDone}
        data-slot="dbq-wiz-advanced" className="mr-auto pointer-coarse:h-10">
        <PanelsTopLeft aria-hidden="true" />{t('dbq.wiz.advanced')}
      </Button>
      {bulkDone ? (
        <Button type="button" onClick={() => onClose?.()} data-slot="dbq-wiz-done" className="pointer-coarse:h-10">{t('dbq.bulk.close')}</Button>
      ) : (
        <>
          {bulk.running && <Button type="button" variant="outline" onClick={bulk.stop} className="pointer-coarse:h-10" data-slot="dbq-bulk-stop">{t('dbq.bulk.stop')}</Button>}
          {idx > 0 && (
            <Button type="button" variant="secondary" onClick={back} disabled={saving || bulk.running} data-slot="dbq-wiz-back" className="pointer-coarse:h-10">
              <ArrowLeft aria-hidden="true" />{t('dbq.wiz.back')}
            </Button>
          )}
          {step !== 'summary' ? (
            <Button type="button" onClick={next} data-slot="dbq-wiz-next" className="pointer-coarse:h-10">
              {t('dbq.wiz.next')}<ArrowRight aria-hidden="true" />
            </Button>
          ) : (
            <Button type="button" onClick={save} disabled={saving || bulk.running || (bulkOn && !bulkReady)} aria-busy={saving || bulk.running || undefined}
              data-slot="dbq-wiz-save" className="pointer-coarse:h-10">
              {(saving || bulk.running) ? <Spinner size={14} inline decorative /> : (bulkOn ? <Layers aria-hidden="true" /> : <Check aria-hidden="true" />)}
              {bulkOn ? t('dbq.bulk.run', bulkPlanRows.length) : t('dbq.wiz.save')}
            </Button>
          )}
        </>
      )}
    </div>
  )

  return (
    <ModalShell open={!!open} onClose={close} size="lg" scrollBody icon={Wand2} title={t('dbq.wiz.title')}
      dismissOnBackdrop={false} dismissOnEscape={false} busy={saving || bulk.running} bodyRef={bodyRef} footer={footer}>
      <div data-slot="dbq-wiz" data-step={step} className="flex min-w-0 flex-col gap-4">
        <StepIndicator t={t} step={step} furthest={furthest} counts={counts} onJump={goTo} disabled={saving || bulk.running} />

        <div className="flex min-w-0 flex-col gap-1">
          <h3 ref={headingRef} tabIndex={-1} data-slot="dbq-wiz-heading" className="m-0 text-base font-semibold outline-none">
            {t('dbq.wiz.stepOf', idx + 1, WIZ_STEPS.length)} · {t(`dbq.wiz.step.${step}`)}
          </h3>
          <p className="m-0 text-sm text-muted-foreground [overflow-wrap:anywhere]">{t(`dbq.wiz.stepHint.${step}`)}</p>
        </div>

        {banner && (
          <AlertBanner tone="danger" icon={CircleAlert} title={t('dbq.wiz.saveProblemTitle')} className="mb-0"
            actions={banner.advanced ? <Button type="button" variant="outline" size="sm" onClick={toAdvanced} className="pointer-coarse:h-10">{t('dbq.wiz.fixInAdvanced')}</Button> : null}>
            <span data-slot="dbq-wiz-banner" className="[overflow-wrap:anywhere]">{banner.message}</span>
          </AlertBanner>
        )}

        {step === 'connection' && (
          <div className="flex min-w-0 flex-col gap-3">
            <FormGrid>
              <FormField full label={t('dbq.form.team')} required name="team_id" error={errors.team_id}>
                {({ id }) => canPickTeam
                  ? <SearchableSelect id={id} value={form.teamId} onChange={onTeamChange} options={teamOptions} searchThreshold={2} wrapLabels
                      placeholder={t('dbq.wiz.pickTeam')} />
                  : <Input id={id} value={fixedTeamName || t('dbq.form.noTeam')} disabled />}
              </FormField>
            </FormGrid>
            <ConnectionPicker teamId={formTeamId} value={form.profileId} onChange={onConnectionChange}
              disabled={formTeamId == null} error={errors.profile_id} canManage={canManageConnections} teams={managerTeams} />
            <FormHint full={false}>{t('dbq.wiz.connectionNote')}</FormHint>
          </div>
        )}

        {step === 'query' && (
          <div className="flex min-w-0 flex-col gap-3">
            {!form.sqlText.trim() && <QuickStart t={t} dbType={dbType} onPick={applyTemplate} />}
            <div className="flex min-w-0 flex-wrap items-center gap-2">
              <TemplatePicker teamId={formTeamId} dbType={dbType} onPick={applyTemplate} disabled={formTeamId == null} />
              {templateName && <span data-slot="dbq-wiz-template" className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('dbq.wiz.fromTemplate', templateName)}</span>}
            </div>
            <SqlEditor value={form.sqlText} onChange={onSqlChange} dbType={dbType} fieldError={errors.sql_text}
              violations={sqlViolations} onGuardChange={setGuard} fix={{ columns, rowLimit: form.maxRows, rules: form.rules }} />
          </div>
        )}

        {step === 'test' && (
          <div className="flex min-w-0 flex-col gap-3">
            <div className="flex min-w-0 flex-wrap items-center gap-2">
              <Button type="button" variant="secondary" onClick={runTest} disabled={!canRun || testing} aria-busy={testing || undefined}
                data-slot="dbq-wiz-test" className="pointer-coarse:h-10">
                {testing ? <Spinner size={14} inline decorative /> : <FlaskConical aria-hidden="true" />}{testing ? t('dbq.form.testing') : t('dbq.form.test')}
              </Button>
              {!testResult && !testing && <span className="text-xs text-muted-foreground">{t('dbq.wiz.testOptional')}</span>}
            </div>
            <DbQueryTestPanel result={testResult} plan={planResult} onColumnAction={onColumnAction}
              maskedColumns={form.maskedColumns} actionsDisabled={testing} />
          </div>
        )}

        {step === 'rules' && (
          <div className="flex min-w-0 flex-col gap-3" data-field="rules">
            {suggestedRule && (
              <div data-slot="dbq-wiz-suggested-rule" className="flex min-w-0 flex-col gap-2 rounded-md border border-dashed bg-muted/30 p-3 sm:flex-row sm:items-center sm:justify-between">
                <span className="text-sm [overflow-wrap:anywhere]">{t('dbq.wiz.suggestedRule', ruleSummary(suggestedRule, t, lang))}</span>
                <Button type="button" variant="outline" size="sm" className="shrink-0 pointer-coarse:h-10" onClick={() => onRulesChange([suggestedRule])}>
                  {t('dbq.wiz.addSuggested')}
                </Button>
              </div>
            )}
            <RuleBuilder rules={form.rules} logic={form.ruleLogic} columns={columns} fieldErrors={ruleErrors}
              trapIndexes={trapIndexes} trapColumn={trapColumn} newRuleFactory={newRuleFactory}
              onChange={onRulesChange} onLogicChange={(logic) => set({ ruleLogic: logic })} />
            {form.rules.length === 0 && <FormHint full={false}>{t('dbq.wiz.noRulesHint')}</FormHint>}
            <FormGrid>
              <FormField label={t('dbq.form.slowQuery')} name="slow_query_ms" error={errors.slow_query_ms} hint={t('dbq.form.slowQueryHint')}>
                {({ id, describedBy }) => (
                  <InputGroup className="pointer-coarse:h-10">
                    <InputGroupInput id={id} aria-describedby={describedBy} type="number" min="100" max="600000" step="100" className="pointer-coarse:h-10"
                      value={form.slowQueryMs} placeholder={t('dbq.form.slowQueryOff')}
                      onChange={(e) => set({ slowQueryMs: e.target.value }, ['slow_query_ms'])} />
                    <InputGroupAddon align="inline-end"><InputGroupText>ms</InputGroupText></InputGroupAddon>
                  </InputGroup>
                )}
              </FormField>
            </FormGrid>
          </div>
        )}

        {step === 'schedule' && (
          <div className="flex min-w-0 flex-col gap-4">
            <div data-field="schedule" className="min-w-0">
              <ScheduleEditor value={form.schedule} onChange={(patch) => { setForm((f) => ({ ...f, schedule: { ...f.schedule, ...patch } })); clearKeys((k) => k in scheduleErrors || k === 'schedule') }}
                fieldErrors={scheduleErrors} minIntervalSeconds={limits?.min_interval_seconds} canEditHolidays={globalAdmin} />
            </div>
            <FormGrid>
              <NotifyChannels notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
                alertLevel={form.alertLevel} onAlertLevelChange={(v) => set({ alertLevel: v })}
                onChange={(patch) => set(patch)}
                teamLabel={teamOptions.find((o) => String(o.value) === String(form.teamId))?.label || fixedTeamName || t('dbq.form.noTeam')}
                teamId={form.teamId} groupId={form.notificationGroupId}
                onGroupChange={(v) => set({ notificationGroupId: v })} />
              <FormHint>{t('dbq.form.alertLevelHint')}</FormHint>
              <NocNotifyField type="DBQUERY" checked={form.nocNotify} groupIds={form.nocGroupIds} canOpenSettings={globalAdmin}
                onChange={(patch) => set(patch)} />
            </FormGrid>
          </div>
        )}

        {step === 'summary' && (
          <SummaryStep t={t} lang={lang} form={form} set={set} errors={errors} profile={profile} dbType={dbType} teamGroups={teamGroups} teamTags={teamTags}
            teamLabel={teamOptions.find((o) => String(o.value) === String(form.teamId))?.label || fixedTeamName}
            onEdit={goTo} bulkOn={bulkOn}
            onBulkOn={(v) => {
              setBulkOn(v)
              if (v && form.profileId != null) bulk.setSelected((s) => (s.size ? s : new Set([form.profileId])))
              if (!v) bulk.reset()
              // açılan panel çoğunlukla ekranın altında kalır — görünür alana getir
              if (v && typeof requestAnimationFrame === 'function') {
                requestAnimationFrame(() => {
                  try { document.querySelector('[data-slot="dbq-wiz-bulk"]')?.scrollIntoView?.({ block: 'start', behavior: 'smooth' }) } catch { /* jsdom */ }
                })
              }
            }}
            bulkPanel={bulkOn ? <BulkCreatePanel load={connLoad} onReload={reloadConns} dbType={dbType} baseName={form.name.trim()} bulk={bulk} /> : null}
            bulkLocked={bulk.running || !!bulk.rows} />
        )}
      </div>
    </ModalShell>
  )
}

/** Adım göstergesi — sıralı liste; ziyaret edilmiş adımlar düğme (geri / ileri atlama), etkin adım `aria-current`. */
function StepIndicator({ t, step, furthest, counts, onJump, disabled }) {
  const cur = WIZ_STEPS.indexOf(step), far = WIZ_STEPS.indexOf(furthest)
  return (
    <ol data-slot="dbq-wiz-steps" aria-label={t('dbq.wiz.stepsLabel')}
      className="m-0 grid list-none grid-cols-2 gap-1.5 p-0 min-[420px]:grid-cols-3 lg:grid-cols-6">
      {WIZ_STEPS.map((s, i) => {
        const Icon = STEP_ICON[s]
        const state = i === cur ? 'current' : i < cur || i <= far ? 'done' : 'todo'
        const n = Number(counts?.[s]) || 0
        const reachable = i <= Math.max(cur, far)
        return (
          <li key={s} data-step={s} data-state={state} aria-current={i === cur ? 'step' : undefined} className="min-w-0">
            <Button type="button" variant={i === cur ? 'default' : 'outline'} disabled={disabled || !reachable}
              onClick={() => onJump(s)} data-errors={n || undefined}
              aria-label={n ? t('dbq.wiz.stepInvalid', i + 1, t(`dbq.wiz.step.${s}`), n) : t('dbq.wiz.stepNumbered', i + 1, t(`dbq.wiz.step.${s}`))}
              className={cn('h-auto min-h-9 w-full justify-start gap-1.5 px-2 py-1.5 text-left text-xs whitespace-normal pointer-coarse:min-h-10',
                n && i !== cur && 'border-destructive/60 text-destructive', state === 'todo' && 'text-muted-foreground')}>
              <span aria-hidden="true" className={cn('inline-flex size-5 shrink-0 items-center justify-center rounded-full border text-[10px] font-bold tabular-nums',
                i === cur ? 'border-primary-foreground/60' : 'border-current')}>
                {state === 'done' && i !== cur ? <Check className="size-3" /> : i + 1}
              </span>
              <Icon aria-hidden="true" className="hidden size-3.5 shrink-0 sm:block" />
              <span className="min-w-0 [overflow-wrap:anywhere]">{t(`dbq.wiz.step.${s}`)}</span>
              {n > 0 && (
                <span aria-hidden="true" className="ml-auto inline-flex min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] leading-4 font-bold text-white tabular-nums">{n}</span>
              )}
            </Button>
          </li>
        )
      })}
    </ol>
  )
}

/** Hızlı başlangıç — sorgu boşken yerleşik uygulama ve DBA şablonları tek dokunuşla (bağlantının türünde SQL). */
function QuickStart({ t, dbType, onPick }) {
  const groups = [
    { key: 'app', title: t('dbq.tpl.groupBuiltin'), list: builtinTemplates(t, dbType) },
    { key: 'dba', title: t('dbq.tpl.groupDba'), list: dbaTemplates(t, dbType) },
  ]
  return (
    <section data-slot="dbq-wiz-quickstart" aria-labelledby="dbq-wiz-qs-h" className="flex min-w-0 flex-col gap-2 rounded-md border bg-muted/30 p-3">
      <h4 id="dbq-wiz-qs-h" className="m-0 text-sm font-semibold">{t('dbq.wiz.quickStart')}</h4>
      <p className="m-0 text-xs text-muted-foreground">{t(dbType ? 'dbq.wiz.quickStartHint' : 'dbq.wiz.quickStartNoType', dbType ? dbTypeLabel(dbType) : '')}</p>
      {groups.map((g) => (
        <div key={g.key} data-group={g.key} className="flex min-w-0 flex-col gap-1.5">
          <span className="text-[11px] font-bold tracking-wide text-muted-foreground uppercase">{g.title}</span>
          <div className="grid min-w-0 grid-cols-1 gap-1.5 sm:grid-cols-2">
            {g.list.map((tpl) => (
              <Button key={tpl.id} type="button" variant="outline" onClick={() => onPick(tpl)} data-slot="dbq-wiz-qs-item" data-key={tpl.key}
                className="h-auto min-h-10 justify-start px-3 py-2 text-left text-sm font-normal whitespace-normal [overflow-wrap:anywhere]">
                {tpl.name}
              </Button>
            ))}
          </div>
        </div>
      ))}
    </section>
  )
}

/** Özet adımı — ad / amaç / grup / etiket alanları, adımların özeti ("Düzenle" ile o adıma), toplu oluşturma anahtarı. */
function SummaryStep({ t, lang, form, set, errors, profile, dbType, teamGroups, teamTags, teamLabel, onEdit, bulkOn, onBulkOn, bulkPanel, bulkLocked }) {
  const purposeLen = form.purpose.length
  const groupOptions = [{ value: '', label: t('dbq.form.noGroup') }, ...teamGroups.map((g) => ({ value: g.name, label: g.name }))]
  const rules = Array.isArray(form.rules) ? form.rules : []
  const sqlLines = String(form.sqlText || '').split('\n')
  const scheduleRow = {
    schedule_mode: form.schedule?.schedule_mode, interval_seconds: form.schedule?.interval_seconds, cron_expression: form.schedule?.cron_expression,
    windows: form.schedule?.windows, blackouts: form.schedule?.blackouts, skip_holidays: form.schedule?.skip_holidays,
  }
  const channels = [form.notifyEmail && t('dbq.wiz.chEmail'), form.notifyWebhook && t('dbq.wiz.chWebhook'), form.nocNotify && t('dbq.wiz.chNoc')].filter(Boolean)
  return (
    <div className="flex min-w-0 flex-col gap-4">
      <FormGrid>
        <FormField full label={t('dbq.form.name')} required name="name" error={errors.name} hint={t('dbq.wiz.nameHint')}>
          {({ id, describedBy }) => (
            <Input id={id} aria-describedby={describedBy} value={form.name} maxLength={200} disabled={bulkLocked} className="pointer-coarse:h-10"
              onChange={(e) => set({ name: e.target.value }, ['name'])} />
          )}
        </FormField>
        <FormField full label={t('dbq.form.purpose')} required name="purpose" error={errors.purpose}
          hint={t('dbq.form.purposeHint', purposeLen, PURPOSE_MAX)}>
          {({ id, describedBy }) => (
            <Textarea id={id} aria-describedby={describedBy} rows={3} maxLength={PURPOSE_MAX} value={form.purpose} disabled={bulkLocked}
              placeholder={t('dbq.form.purposePh')} onChange={(e) => set({ purpose: e.target.value }, ['purpose'])} />
          )}
        </FormField>
        <FormField label={t('dbq.form.group')} required name="group_name" error={errors.group_name}>
          {({ id }) => (
            <SearchableSelect id={id} value={form.groupName} onChange={(v) => set({ groupName: v }, ['group_name'])} options={groupOptions}
              creatable onCreate={() => {}} searchThreshold={2} placeholder={t('dbq.form.noGroup')} disabled={bulkLocked} wrapLabels />
          )}
        </FormField>
        <FormSection title={t('dbq.form.tags')} required boxed={false} name="tags" error={errors.tags}>
          <TagInput value={form.tags} disabled={bulkLocked} onChange={(v) => set({ tags: v }, ['tags'])} placeholder={t('dbq.form.tagsPh')} suggestions={teamTags} />
        </FormSection>
        <div className="flex min-h-9 items-center gap-2 sm:col-span-2 pointer-coarse:min-h-10">
          <Switch id="dbq-wiz-active" checked={form.active} disabled={bulkLocked} onCheckedChange={(v) => set({ active: v === true })} />
          <Label htmlFor="dbq-wiz-active" className="cursor-pointer font-normal">{t('dbq.form.active')}</Label>
        </div>
      </FormGrid>

      <dl data-slot="dbq-wiz-summary" className="m-0 flex min-w-0 flex-col divide-y rounded-lg border">
        <SummaryRow t={t} step="connection" onEdit={onEdit} label={t('dbq.wiz.step.connection')} locked={bulkLocked}>
          <span className="[overflow-wrap:anywhere]">{teamLabel || '—'}</span>
          <span className="font-semibold [overflow-wrap:anywhere]">{profile?.name || t('dbq.card.noConnection')}{dbType ? ` · ${dbTypeLabel(dbType)}` : ''}</span>
        </SummaryRow>
        <SummaryRow t={t} step="query" onEdit={onEdit} label={t('dbq.wiz.step.query')} locked={bulkLocked}>
          <pre className="m-0 max-h-32 overflow-auto rounded-md bg-muted/50 px-2 py-1.5 font-mono text-xs whitespace-pre-wrap [overflow-wrap:anywhere]">
            {sqlLines.slice(0, 6).join('\n')}{sqlLines.length > 6 ? '\n…' : ''}
          </pre>
        </SummaryRow>
        <SummaryRow t={t} step="rules" onEdit={onEdit} label={t('dbq.wiz.step.rules')} locked={bulkLocked}>
          {rules.length ? (
            <ul className="m-0 flex list-disc flex-col gap-0.5 pl-5">
              {rules.map((r, i) => <li key={r.id || i} className="[overflow-wrap:anywhere]">{ruleSummary(r, t, lang)}</li>)}
            </ul>
          ) : <span className="text-muted-foreground">{t('dbq.wiz.noRules')}</span>}
        </SummaryRow>
        <SummaryRow t={t} step="schedule" onEdit={onEdit} label={t('dbq.wiz.step.schedule')} locked={bulkLocked}>
          <span className="[overflow-wrap:anywhere]">{scheduleSummary(scheduleRow, t)}</span>
          <span className="text-muted-foreground [overflow-wrap:anywhere]">
            {t('dbq.wiz.alertSummary', t(`dbq.wiz.level.${form.alertLevel || 'WARNING'}`), channels.length ? channels.join(', ') : t('dbq.wiz.noChannels'))}
          </span>
        </SummaryRow>
        <div className="flex min-w-0 flex-col gap-1 px-3 py-2.5">
          <dt className="text-[10.5px] font-semibold tracking-wider text-muted-foreground uppercase">{t('dbq.wiz.mailRows')}</dt>
          <dd className="m-0 text-sm text-muted-foreground [overflow-wrap:anywhere]">{t('dbq.wiz.mailRowsHint')}</dd>
        </div>
      </dl>

      <div data-slot="dbq-wiz-bulk" className="flex min-w-0 flex-col gap-3 rounded-lg border p-3">
        <div className="flex min-h-9 items-start gap-2 pointer-coarse:min-h-10">
          <Switch id="dbq-wiz-bulk" checked={bulkOn} disabled={bulkLocked || form.profileId == null} onCheckedChange={(v) => onBulkOn(v === true)} className="mt-0.5" />
          <Label htmlFor="dbq-wiz-bulk" className="flex cursor-pointer flex-col items-start gap-0.5 font-normal">
            <span className="font-semibold">{t('dbq.bulk.toggle')}</span>
            <span className="text-xs text-muted-foreground">{t('dbq.bulk.toggleHint')}</span>
          </Label>
        </div>
        {bulkPanel}
      </div>
    </div>
  )
}

function SummaryRow({ t, step, label, onEdit, locked, children }) {
  return (
    <div data-slot="dbq-wiz-summary-row" data-step={step} className="flex min-w-0 flex-col gap-1.5 px-3 py-2.5 sm:flex-row sm:items-start">
      <dt className="text-[10.5px] font-semibold tracking-wider text-muted-foreground uppercase sm:w-36 sm:shrink-0 sm:pt-1">{label}</dt>
      {/* Düzenle düğmesi `dd` İÇİNDE — `dl > div` yalnız dt / dd taşıyabilir */}
      <dd className="m-0 flex min-w-0 flex-1 flex-col gap-1.5 text-sm sm:flex-row sm:items-start">
        <div className="flex min-w-0 flex-1 flex-col gap-1">{children}</div>
        <Button type="button" variant="ghost" size="sm" onClick={() => onEdit(step)} disabled={locked}
          aria-label={t('dbq.wiz.editStep', label)} className="self-start pointer-coarse:h-10">{t('dbq.wiz.edit')}</Button>
      </dd>
    </div>
  )
}
