import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { CircleAlert, Eraser, Pencil, RefreshCw, Save, Target } from 'lucide-react'
import { api } from '../../../api/client'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { useFormErrors } from '../../../hooks/useFormErrors.js'
import { useToast } from '../../ui/Toast.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import Field from '../../ui/Field.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { LoadingBlock, Spinner } from '../../ui/Progress.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import {
  BUDGET_FIELDS, BUDGET_STATUS_TONE, budgetBand, budgetChanges, budgetDefinedCount, budgetErrorField, budgetFormFrom, budgetStatusOf,
  formatBudgetActual, formatBudgetLimit, isBudgetUnsupported, normalizeBudget, parseBudgetForm,
} from './resourceScanReportModel.js'
import { formatNumber, formatSize, KB, plural } from './resourceScanModel.js'
import { CHIP, TOUCH, WRAP } from './ResourceScanParts.jsx'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupInput, InputGroupText } from '@/components/shadcn/input-group'
import { cn } from '@/lib/utils'

/**
 * Kaynak analizi → PERFORMANS BÜTÇESİ (prs3 F1, 2026-11-04 kullanıcı kararı): izleme başına sınırlar (sayfa boyutu, istek,
 * JS, görsel, tahmini yüklenme, en düşük not, en düşük güvenlik puanı). Sınırlar ANALİZ OKUNURKEN güncel bütçeyle
 * değerlendirilir (geçmiş analizler de güncel bütçeyle işaretlenir); alarm / bildirim YOK — yalnız görünürlük.
 *
 *  - `useResourceBudget`: bütçenin okunması / kaydı (tek kaynak — sekme, eğilim çizgileri ve pencere aynı durumu görür).
 *  - `BudgetBand`: seçili analizin bütçe bandı (aşılan kalemler, sınır ↔ gerçek). Ayrıntıda `budget` yoksa (eski sunucu)
 *    ÇİZİLMEZ; ölçülemeyen kalem "ölçülemedi" — asla "geçti".
 *  - `BudgetStatusBadge`: geçmiş / karşılaştırma işareti.
 *  - `BudgetDialog`: düzenleme izni olana düzenlenebilir, diğerine salt okunur; boş alan = sınır yok; YALNIZ değişen alanlar
 *    gönderilir; alan hatası alanın altında; 404 / 405 → "bu sunucu sürümü desteklemiyor".
 */

/** Bütçenin okunması / kaydı. `fallbackCanEdit` = izleme satırının `can_edit`'i (sunucu `can_edit` göndermezse). */
export function useResourceBudget(monitorId, fallbackCanEdit) {
  const [st, setSt] = useState({ status: 'idle', values: null, canEdit: null, error: null })
  const seq = useRef(0)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])

  const load = useCallback(async () => {
    const my = ++seq.current
    setSt((s) => ({ ...s, status: s.values ? 'refreshing' : 'loading', error: null }))
    let res = null
    let err = null
    try { res = await api.monitoring.resourceBudget(monitorId) } catch (e) { err = e }
    if (!alive.current || my !== seq.current) return null
    if (res?.success) {
      const b = normalizeBudget(res.data)
      // Tanınmayan gövde = okunamadı (sınır yok diye gösterilmez)
      if (!b.known) { setSt((s) => ({ ...s, status: 'error', error: res })); return null }
      setSt({ status: 'ok', values: b.values, canEdit: b.canEdit, error: null })
      return b.values
    }
    if (isBudgetUnsupported(res)) { setSt({ status: 'unsupported', values: null, canEdit: null, error: null }); return null }
    setSt((s) => ({ ...s, status: 'error', error: res || err }))
    return null
  }, [monitorId])

  /** Kaydedilen bütçeyi duruma uygular (yanıt alanları varsa onlar, yoksa gönderilen değerler). */
  const applySaved = useCallback((values, data) => {
    seq.current += 1                                   // uçuştaki eski okuma yeni değeri ezmesin
    const b = data ? normalizeBudget(data) : null
    setSt((s) => ({ status: 'ok', values: b?.fields ? b.values : values, canEdit: b?.canEdit ?? s.canEdit, error: null }))
  }, [])

  const canEdit = st.canEdit ?? (fallbackCanEdit === true)
  return { ...st, canEdit, load, applySaved }
}

/** Kalem adı + birim ipucu ("Sayfa boyutu (KB)"). */
const fieldLabel = (key, t) => t(`prs.budget.f.${key}`)

/** Satırın durumu: ok / exceeded / unknown — metin ve ton. */
function rowState(ok) {
  if (ok === true) return { k: 'ok', tone: 'success' }
  if (ok === false) return { k: 'exceeded', tone: 'danger' }
  return { k: 'unknown', tone: 'muted' }
}

/** Seçili analizin bütçe bandı. `budget` yoksa (eski sunucu / eski pod) null. */
export function BudgetBand({ budget, scan, canEdit, onOpen }) {
  const t = useT()
  const locale = useDateLocale()
  const titleId = useId()
  const band = useMemo(() => budgetBand(budget, scan), [budget, scan])
  if (!band) return null
  const head = band.state === 'none'
    ? { tone: 'muted', text: t('prs.budget.band.none') }
    : band.state === 'exceeded'
      ? { tone: 'danger', text: plural(t, 'prs.budget.band.exceeded', band.exceeded, formatNumber(band.exceeded, locale)) }
      : band.state === 'partial'
        ? { tone: 'muted', text: plural(t, 'prs.budget.band.partial', band.unknown, formatNumber(band.unknown, locale)) }
        : { tone: 'success', text: plural(t, 'prs.budget.band.ok', band.rows.length, formatNumber(band.rows.length, locale)) }
  const action = band.state === 'none'
    ? (canEdit ? t('prs.budget.set') : null)
    : (canEdit ? t('prs.budget.edit') : t('prs.budget.view'))
  return (
    <section data-slot="prs-budget-band" data-state={band.state} aria-labelledby={titleId}
      className="flex min-w-0 flex-col gap-2 rounded-xl border bg-card px-4 py-3 shadow-xs">
      <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-2">
        <h4 id={titleId} className="m-0 flex min-w-0 items-center gap-2 text-sm font-semibold text-foreground">
          <Target aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
          <span className={WRAP}>{t('prs.budget.title')}</span>
        </h4>
        <ToneBadge tone={head.tone} data-slot="prs-budget-band-status" className={cn(CHIP, 'font-semibold')}>{head.text}</ToneBadge>
        {action && onOpen && (
          <Button type="button" variant="outline" size="sm" data-slot="prs-budget-open" className={cn('ml-auto', TOUCH)} onClick={onOpen}>
            <Pencil aria-hidden="true" />{action}
          </Button>
        )}
      </div>
      {band.state === 'none' ? (
        <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t(canEdit ? 'prs.budget.band.noneBody' : 'prs.budget.band.noneBodyRead')}</p>
      ) : (
        <>
          <ul data-slot="prs-budget-rows" className="m-0 grid min-w-0 list-none grid-cols-1 gap-1.5 p-0 sm:grid-cols-2">
            {band.rows.map((r) => {
              const s = rowState(r.ok)
              const f = BUDGET_FIELDS.find((x) => x.key === r.key)
              const limit = formatBudgetLimit(r.key, r.limit, locale)
              const actual = formatBudgetActual(r.key, r.actual, locale)
              const cmp = f?.dir === 'min' ? '≥' : '≤'
              return (
                <li key={r.key} data-slot="prs-budget-row" data-key={r.key} data-ok={s.k}
                  className={cn('flex min-w-0 flex-wrap items-center justify-between gap-x-3 gap-y-1 rounded-lg border px-3 py-2',
                    s.k === 'exceeded' ? 'border-destructive/40 bg-destructive/5' : 'bg-muted/20')}>
                  <span className="flex min-w-0 flex-col gap-0.5">
                    <span className={cn('text-sm font-medium text-foreground', WRAP)}>{r.known ? fieldLabel(r.key, t) : t('prs.budget.f.other')}</span>
                    <span className="text-xs text-muted-foreground tabular-nums">
                      {t('prs.budget.row.limitActual', `${cmp} ${limit}`, actual)}
                    </span>
                  </span>
                  <ToneBadge tone={s.tone} className={cn(CHIP, 'font-semibold')} data-slot="prs-budget-row-state">{t(`prs.budget.state.${s.k}`)}</ToneBadge>
                </li>
              )
            })}
          </ul>
          <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('prs.budget.band.note')}</p>
        </>
      )}
    </section>
  )
}

/** Geçmiş / karşılaştırma işareti (`budget_status`); durum yoksa (bütçe tanımsız / eski sunucu) çizilmez. */
export function BudgetStatusBadge({ row, className }) {
  const t = useT()
  const locale = useDateLocale()
  const st = budgetStatusOf(row)
  if (!st) return null
  const n = Number(row?.budget_exceeded)
  const text = st === 'exceeded'
    ? (Number.isFinite(n) && n > 0 ? plural(t, 'prs.budget.hist.exceededN', n, formatNumber(n, locale)) : t('prs.budget.hist.exceeded'))
    : t(`prs.budget.hist.${st}`)
  return <ToneBadge tone={BUDGET_STATUS_TONE[st]} data-slot="prs-budget-status" data-status={st} className={cn(CHIP, className)}>{text}</ToneBadge>
}

/** Birim eki (KB / istek / ms / puan). */
const unitText = (kind, t) => t(`prs.budget.unit.${kind}`)

/**
 * Bütçe penceresi. `budget` = useResourceBudget sonucu (pencere açılınca taze okunur); `onSaved(values)` kayıttan sonra.
 */
export function BudgetDialog({ open, onClose, monitorId, budget, onSaved }) {
  const t = useT()
  const locale = useDateLocale()
  const toast = useToast()
  const [form, setForm] = useState(() => budgetFormFrom(null))
  const [base, setBase] = useState(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(null)
  const fe = useFormErrors(open)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  const { load } = budget

  // Açılışta taze oku (fark, açılıştaki kayıtlı hâle göre hesaplanır — başka bir kullanıcının kaydı ezilmesin)
  useEffect(() => {
    if (!open) return
    setSaveError(null)
    setBase(null)
    load()
  }, [open, load])
  useEffect(() => {
    if (open && budget.status === 'ok' && base === null) {
      setBase(budget.values)
      setForm(budgetFormFrom(budget.values))
    }
  }, [open, budget.status, budget.values, base])

  const editable = budget.canEdit === true && budget.status === 'ok'
  const parsed = useMemo(() => parseBudgetForm(form), [form])
  // Kaydet: form kayıtlı hâlden farklıysa (geçersiz yazım da — basınca alan hatası görünür)
  const dirty = base !== null && JSON.stringify(form) !== JSON.stringify(budgetFormFrom(base))

  function setField(key, v) {
    setForm((f) => ({ ...f, [key]: v }))
    fe.clear(key)
    setSaveError(null)
  }

  async function save() {
    if (!editable || saving) return
    const { values, errors } = parseBudgetForm(form)
    const map = {}
    for (const f of BUDGET_FIELDS) {
      const e = errors[f.key]
      map[f.key] = e ? (e.code === 'range'
        ? t('prs.budget.err.range', formatNumber(e.args[0], locale), formatNumber(e.args[1], locale), unitText(f.kind, t))
        : t('prs.budget.err.int')) : false
    }
    if (fe.check(map)) return
    const body = budgetChanges(values, base)
    if (!Object.keys(body).length) { onClose?.(); return }
    setSaving(true)
    setSaveError(null)
    let res = null
    try {
      res = await api.monitoring.saveResourceBudget(monitorId, body)
    } catch (e) {
      if (alive.current) setSaveError(e)
      return
    } finally {
      if (alive.current) setSaving(false)
    }
    if (!alive.current) return
    if (res?.success) {
      budget.applySaved(values, res.data)
      onSaved?.(values)
      toast.success(t('prs.budget.saved'))
      onClose?.()
      return
    }
    const field = budgetErrorField(res)
    if (field) { fe.check({ [field]: res.error || t('prs.budget.err.server') }); return }
    if (isBudgetUnsupported(res)) { setSaveError({ unsupported: true }); return }
    setSaveError(res)
  }

  const status = budget.status
  const footer = (
    <div className="flex w-full flex-wrap items-center justify-end gap-2">
      {editable && budgetDefinedCount(parsed.values) > 0 && (
        <Button type="button" variant="ghost" data-slot="prs-budget-clear" className={cn('mr-auto', TOUCH)} disabled={saving}
          onClick={() => { setForm(budgetFormFrom(null)); fe.reset(); setSaveError(null) }}>
          <Eraser aria-hidden="true" />{t('prs.budget.clearAll')}
        </Button>
      )}
      <Button type="button" variant="outline" className={TOUCH} onClick={onClose} disabled={saving}>
        {editable ? t('prs.budget.cancel') : t('prs.budget.close')}
      </Button>
      {editable && (
        <Button type="button" data-slot="prs-budget-save" className={TOUCH} onClick={save} disabled={saving || !dirty}
          aria-busy={saving || undefined}>
          {saving ? <Spinner size={14} inline decorative /> : <Save aria-hidden="true" />}{t('prs.budget.save')}
        </Button>
      )}
    </div>
  )

  return (
    <ModalShell open={open} onClose={onClose} title={t('prs.budget.dialogTitle')} icon={Target} size="md" busy={saving}
      dismissOnBackdrop={false} scrollBody footer={footer}>
      <div data-slot="prs-budget-dialog" data-state={status} data-editable={editable ? 'true' : 'false'} className="flex min-w-0 flex-col gap-3">
        <p className={cn('m-0 text-sm text-muted-foreground', WRAP)}>{t('prs.budget.dialogIntro')}</p>
        {(status === 'loading' || status === 'idle' || (status === 'refreshing' && base === null)) && <LoadingBlock label={t('prs.budget.loading')} className="py-6" />}
        {status === 'unsupported' && (
          <div data-slot="prs-budget-unsupported">
            <StatusBlock tone="info" icon={Target} title={t('prs.budget.unsupportedTitle')} description={t('prs.budget.unsupportedBody')} />
          </div>
        )}
        {status === 'error' && (
          <div data-slot="prs-budget-error">
            <StatusBlock tone="danger" icon={CircleAlert} title={t('prs.budget.unreadable')}
              description={budget.error?.error || budget.error?.message || t('prs.budget.unreadableBody')} details={budget.error || undefined}
              actions={<Button type="button" variant="outline" className={TOUCH} onClick={() => load()}><RefreshCw aria-hidden="true" />{t('prs.retry')}</Button>} />
          </div>
        )}
        {(status === 'ok' || status === 'refreshing') && base !== null && (
          <>
            {!editable && (
              <div data-slot="prs-budget-readonly">
                <AlertBanner tone="info" className="mb-0">{t('prs.budget.readOnly')}</AlertBanner>
              </div>
            )}
            {saveError && (
              <div data-slot="prs-budget-save-error">
                <AlertBanner tone={saveError.unsupported ? 'info' : 'danger'} className="mb-0"
                  details={saveError.unsupported ? undefined : saveError}>
                  {saveError.unsupported ? t('prs.budget.unsupportedBody') : (saveError.error || saveError.message || t('prs.budget.saveFailed'))}
                </AlertBanner>
              </div>
            )}
            <BudgetGroup title={t('prs.budget.groupSize')} keys={['max_page_kb', 'max_js_kb', 'max_image_kb', 'max_requests']}
              form={form} setField={setField} editable={editable} fe={fe} t={t} locale={locale} />
            <BudgetGroup title={t('prs.budget.groupSpeed')} keys={['max_load_ms', 'min_grade', 'min_security_score']}
              form={form} setField={setField} editable={editable} fe={fe} t={t} locale={locale} />
            <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('prs.budget.dialogNote')}</p>
          </>
        )}
      </div>
    </ModalShell>
  )
}

function BudgetGroup({ title, keys, form, setField, editable, fe, t, locale }) {
  return (
    <fieldset className="m-0 flex min-w-0 flex-col gap-1 border-0 p-0">
      <legend className="mb-1 p-0 text-xs font-semibold tracking-[.04em] text-muted-foreground uppercase">{title}</legend>
      <div className="grid min-w-0 grid-cols-1 gap-x-3 sm:grid-cols-2">
        {keys.map((key) => {
          const f = BUDGET_FIELDS.find((x) => x.key === key)
          const raw = form[key] ?? ''
          const n = /^\d{1,9}$/.test(String(raw).trim()) ? Number(raw) : null
          const human = n !== null && f.kind === 'kb' && n >= 1024 ? ` · ${t('prs.budget.equals', formatSize(n * KB, { locale }))}` : ''
          const hint = `${t('prs.budget.range', formatNumber(f.min, locale), formatNumber(f.max, locale), unitText(f.kind, t))}${human} · ${t(`prs.budget.h.${key}`)}`
          return (
            <Field key={key} label={fieldLabel(key, t)} hint={hint} {...fe.fieldProps(key)} className="mb-2">
              {({ id, describedBy, invalid }) => (
                <InputGroup className="pointer-coarse:h-10" data-disabled={!editable || undefined}>
                  <InputGroupInput id={id} aria-describedby={describedBy} aria-invalid={invalid} inputMode="numeric" autoComplete="off"
                    data-slot="prs-budget-input" data-key={key} value={raw} disabled={!editable}
                    placeholder={t('prs.budget.placeholder')} onChange={(e) => setField(key, e.target.value)} />
                  <InputGroupAddon align="inline-end"><InputGroupText>{unitText(f.kind, t)}</InputGroupText></InputGroupAddon>
                </InputGroup>
              )}
            </Field>
          )
        })}
      </div>
    </fieldset>
  )
}
