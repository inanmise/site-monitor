import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { CalendarOff, CalendarPlus, Check, CircleAlert, Info, Pencil, RefreshCw, Trash2, X } from 'lucide-react'
import { api } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Badge } from '@/components/shadcn/badge'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import Field from '../ui/Field.jsx'
import DateTimeField from '../ui/DateTimeField.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { SETTINGS_STACK, SettingsHeader, SettingsSaveBar, SettingsSection } from './SettingsControls.jsx'
import { useUnsavedWork } from '../../utils/unsavedWork.js'
import { countText } from '../dbquery/dbqueryPageModel.js'
import {
  MAX_DAYS, entryProblems, formatHoliday, normalizeDays, sameDays, toPayload, todayIstanbul, yearsOf,
} from './holidayCalendarModel.js'

/**
 * Ayarlar → Platform → Tatil takvimi (2026-10-31, Veritabanı Sorgu İzleme). Kurum geneli tatil / özel gün listesi —
 * "tatil takviminde çalışma" seçili izlemeler bu günlerde (Europe/Istanbul) çalışmaz. Düzenleme YALNIZ global yönetici
 * (sunucu `can_edit` + kabuğun `readOnly`'si birlikte); diğer yöneticiler salt okunur görür. Yıl süzgeci, gün ekle (tarih +
 * ad), satır içi düzenle / sil, kaydet çubuğu (yalnız değişince; tam liste `PUT`). Okunamazsa hata + tekrar dene — asla
 * "tatil yok" denmez. Tohumlanan resmî tatillerde dini bayramların doğrulanması gerektiği not edilir.
 *
 * Kaydedilmemiş değişiklik korunur (DÜŞÜK 8): "Yenile" kirli taslağı onaysız ezmez; sekmeyi kapatma / yenileme tarayıcı
 * uyarısı verir ve başka pencereden gelen "raporu aç" isteği taslağı sessizce bırakmaz (`useUnsavedWork` — diğer ayar
 * sayfalarının deseni). Sürüm denetimi yok: PUT tam listeyi değiştirir (iki global yönetici aynı anda → son yazan kazanır).
 */
export default function HolidayCalendarSettings({ readOnly: readOnlyProp = false }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [load, setLoad] = useState({ state: 'loading', message: null })
  const [saved, setSaved] = useState([])
  const [draft, setDraft] = useState([])
  const [canEdit, setCanEdit] = useState(false)
  const [year, setYear] = useState(() => Number(todayIstanbul().slice(0, 4)))
  const [saving, setSaving] = useState(false)
  const [banner, setBanner] = useState(null)
  const [rowErrors, setRowErrors] = useState({})
  const seq = useRef(0)

  const reload = useCallback(async () => {
    const n = ++seq.current
    setLoad({ state: 'loading', message: null })
    try {
      const res = await api.admin.getHolidays()
      if (n !== seq.current) return
      if (res?.success && res.data && Array.isArray(res.data.days)) {
        const list = normalizeDays(res.data.days)
        setSaved(list); setDraft(list); setCanEdit(res.data.can_edit === true); setRowErrors({}); setBanner(null)
        setLoad({ state: 'ok', message: null })
      } else {
        setLoad({ state: 'error', message: res?.error || res?.message || t('dbq.hol.loadFailedBody') })
      }
    } catch (e) {
      if (n !== seq.current) return
      setLoad({ state: 'error', message: e?.message || t('dbq.hol.loadFailedBody') })
    }
  }, [t])
  useEffect(() => { reload() }, [reload])

  const editable = canEdit && !readOnlyProp
  const dirty = load.state === 'ok' && !sameDays(saved, draft)

  useUnsavedWork(dirty)   // başka pencereden gelen "raporu aç" isteği değişikliği sessizce bırakmasın
  useEffect(() => {
    if (!dirty) return undefined
    const warn = (e) => { e.preventDefault(); e.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  /** "Yenile": kaydedilmemiş taslak varsa önce onay — kabul edilmezse hiçbir şey olmaz. */
  const refresh = async () => {
    if (dirty) {
      const ok = await showConfirm({
        title: t('dbq.hol.refreshDirtyTitle'), message: t('dbq.hol.refreshDirtyBody'),
        confirmText: t('dbq.hol.refreshDirtyConfirm'), cancelText: t('dbq.form.keepEditing'), variant: 'danger',
      })
      if (!ok) return
    }
    reload()
  }
  const years = useMemo(() => yearsOf(draft), [draft])
  const today = todayIstanbul()
  const shown = useMemo(() => (year === 'all' ? draft : draft.filter((d) => d.day.startsWith(`${year}-`))), [draft, year])

  const save = async () => {
    setBanner(null)
    setSaving(true)
    const body = toPayload(draft)
    let res
    try { res = await api.admin.saveHolidays(body) } catch (e) { res = { success: false, error: e?.message } } finally { setSaving(false) }
    if (res?.success) {
      const list = normalizeDays(res.data?.days ?? body)
      setSaved(list); setDraft(list); setRowErrors({})
      if (res.data && typeof res.data.can_edit === 'boolean') setCanEdit(res.data.can_edit)
      toast.success(t('dbq.hol.saved'))
      return
    }
    // Sunucu alan hataları `days[N].<alt>` — N gönderilen (sıralı) listenin sırası
    const errs = {}
    for (const [k, v] of Object.entries(res?.fields || {})) {
      const m = /^days\[(\d+)\]/.exec(k)
      if (m && body[Number(m[1])]) errs[body[Number(m[1])].day] = String(v)
    }
    setRowErrors(errs)
    setBanner(res?.error || res?.message || t('dbq.hol.saveFailedBody'))
  }

  const remove = async (d) => {
    const ok = await showConfirm({
      title: t('dbq.hol.deleteTitle'), message: t('dbq.hol.deleteBody', d.name, formatHoliday(d.day, lang)),
      confirmText: t('dbq.hol.deleteConfirm'), variant: 'danger',
    })
    if (ok) setDraft((list) => list.filter((x) => x.day !== d.day))
  }

  return (
    <div data-slot="holiday-settings" className={SETTINGS_STACK}>
      <SettingsHeader icon={CalendarOff} title={t('dbq.hol.title')} description={t('dbq.hol.desc')}
        meta={load.state === 'ok' ? <Badge variant="outline" className="font-normal">{countText(t, 'dbq.hol.total', draft.length)}</Badge> : null}
        actions={(
          <Button type="button" variant="outline" onClick={refresh} disabled={load.state === 'loading' || saving} className="pointer-coarse:h-10">
            <RefreshCw aria-hidden="true" />{t('dbq.hol.refresh')}
          </Button>
        )} />

      {load.state === 'ok' && !editable && (
        <AlertBanner tone="info" title={t('dbq.hol.readOnlyTitle')} className="mb-0"><span data-slot="holiday-readonly">{t('dbq.hol.readOnlyBody')}</span></AlertBanner>
      )}
      <AlertBanner tone="warning" icon={Info} title={t('dbq.hol.verifyTitle')} className="mb-0">{t('dbq.hol.verifyBody')}</AlertBanner>

      {load.state === 'loading' && (
        <div className="flex flex-col gap-2" aria-busy="true"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      )}
      {load.state === 'error' && (
        <StatusBlock tone="danger" icon={CircleAlert} title={t('dbq.hol.loadFailedTitle')} description={load.message}
          actions={<Button type="button" variant="outline" onClick={reload} className="pointer-coarse:h-10"><RefreshCw aria-hidden="true" />{t('dbq.hol.retry')}</Button>} />
      )}

      {load.state === 'ok' && (
        <>
          {editable && <AddForm list={draft} lang={lang} max={draft.length >= MAX_DAYS}
            onAdd={(d) => { setDraft((l) => normalizeDays([...l, d])); setYear(Number(d.day.slice(0, 4))) }} />}

          <SettingsSection title={t('dbq.hol.listTitle')} description={t('dbq.hol.listDesc')}>
            <div className="mb-3 flex min-w-0 overflow-x-auto pb-1">
              <SegmentedControl value={year} onChange={setYear} ariaLabel={t('dbq.hol.yearFilter')}
                options={[...years.map((y) => ({ value: y, label: String(y) })), { value: 'all', label: t('dbq.hol.allYears') }]} />
            </div>
            {banner && <AlertBanner tone="danger" title={t('dbq.hol.saveFailedTitle')}>{banner}</AlertBanner>}
            {shown.length === 0 ? (
              <p data-slot="holiday-empty" className="m-0 rounded-md border border-dashed px-3 py-3 text-sm text-muted-foreground">
                {year === 'all' ? t('dbq.hol.emptyAll') : t('dbq.hol.emptyYear', year)}
              </p>
            ) : (
              <ul data-slot="holiday-list" className="m-0 flex list-none flex-col divide-y rounded-md border p-0">
                {shown.map((d) => (
                  <HolidayRow key={d.day} d={d} lang={lang} past={d.day < today} today={d.day === today} editable={editable}
                    list={draft} error={rowErrors[d.day]}
                    onSave={(next) => {
                      setDraft((l) => normalizeDays(l.map((x) => (x.day === d.day ? next : x))))
                      setRowErrors((e) => { const c = { ...e }; delete c[d.day]; return c })
                    }}
                    onRemove={() => remove(d)} />
                ))}
              </ul>
            )}
          </SettingsSection>

          {editable && (
            <SettingsSaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={() => { setDraft(saved); setRowErrors({}); setBanner(null) }} />
          )}
        </>
      )}
    </div>
  )
}

/** Gün ekleme formu — tarih + ad; hatalar alanın altında. */
function AddForm({ list, lang, max, onAdd }) {
  const t = useT()
  const [day, setDay] = useState('')
  const [name, setName] = useState('')
  const [errs, setErrs] = useState({})
  const add = () => {
    const p = entryProblems(day, name, list)
    if (max) p.day = 'too_many'
    setErrs(p)
    if (Object.keys(p).length) return
    onAdd({ day, name: name.trim() })
    setDay(''); setName('')
  }
  return (
    <SettingsSection title={t('dbq.hol.addTitle')} description={t('dbq.hol.addDesc')}>
      <div data-slot="holiday-add" className="grid grid-cols-1 items-start gap-x-4 sm:grid-cols-[14rem_minmax(0,1fr)_auto]">
        <Field label={t('dbq.hol.date')} required name="holiday_day" error={errs.day ? t(`dbq.hol.v.${errs.day}`, MAX_DAYS) : undefined} className="mb-3 sm:mb-0">
          {({ id, describedBy, invalid }) => (
            <DateTimeField dateOnly id={id} describedBy={describedBy} invalid={invalid} value={day}
              onChange={(v) => { setDay(v); setErrs((e) => ({ ...e, day: undefined })) }} placeholder={t('dbq.hol.pickDate')} />
          )}
        </Field>
        <Field label={t('dbq.hol.name')} required name="holiday_name" error={errs.name ? t(`dbq.hol.v.${errs.name}`, 120) : undefined}
          hint={day ? formatHoliday(day, lang) : undefined} className="mb-3 sm:mb-0">
          {({ id, describedBy, invalid }) => (
            <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} value={name} className="h-9 pointer-coarse:h-10"
              placeholder={t('dbq.hol.namePlaceholder')} onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add() } }}
              onChange={(e) => { setName(e.target.value); setErrs((s) => ({ ...s, name: undefined })) }} />
          )}
        </Field>
        <Button type="button" onClick={add} className="sm:mt-[1.6rem] pointer-coarse:h-10" data-slot="holiday-add-btn">
          <CalendarPlus aria-hidden="true" />{t('dbq.hol.add')}
        </Button>
      </div>
    </SettingsSection>
  )
}

/** Liste satırı — görünüm ya da satır içi düzenleme (tarih + ad). */
function HolidayRow({ d, lang, past, today, editable, list, error, onSave, onRemove }) {
  const t = useT()
  const [edit, setEdit] = useState(false)
  const [day, setDay] = useState(d.day)
  const [name, setName] = useState(d.name)
  const [errs, setErrs] = useState({})
  const start = () => { setDay(d.day); setName(d.name); setErrs({}); setEdit(true) }
  const commit = () => {
    const p = entryProblems(day, name, list, d.day)
    setErrs(p)
    if (Object.keys(p).length) return
    onSave({ day, name: name.trim() })
    setEdit(false)
  }
  if (edit) {
    return (
      <li data-slot="holiday-row" data-day={d.day} data-editing="true" className="flex flex-col gap-2 p-3">
        <div className="grid grid-cols-1 gap-x-3 sm:grid-cols-[14rem_minmax(0,1fr)]">
          <Field label={t('dbq.hol.date')} required name={`holiday_day_${d.day}`} error={errs.day ? t(`dbq.hol.v.${errs.day}`, MAX_DAYS) : undefined} className="mb-2 sm:mb-0">
            {({ id, describedBy, invalid }) => (
              <DateTimeField dateOnly id={id} describedBy={describedBy} invalid={invalid} value={day} onChange={(v) => setDay(v)} />
            )}
          </Field>
          <Field label={t('dbq.hol.name')} required name={`holiday_name_${d.day}`} error={errs.name ? t(`dbq.hol.v.${errs.name}`, 120) : undefined} className="mb-0">
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} value={name} className="h-9 pointer-coarse:h-10"
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); commit() } if (e.key === 'Escape') setEdit(false) }}
                onChange={(e) => setName(e.target.value)} />
            )}
          </Field>
        </div>
        <div className="flex flex-wrap justify-end gap-2">
          <Button type="button" variant="outline" size="sm" onClick={() => setEdit(false)} className="pointer-coarse:h-10"><X aria-hidden="true" />{t('dbq.hol.cancelEdit')}</Button>
          <Button type="button" size="sm" onClick={commit} className="pointer-coarse:h-10"><Check aria-hidden="true" />{t('dbq.hol.applyEdit')}</Button>
        </div>
      </li>
    )
  }
  return (
    <li data-slot="holiday-row" data-day={d.day} data-past={past ? 'true' : undefined}
      className={cn('flex min-w-0 flex-col gap-2 p-3 sm:flex-row sm:items-center', past && 'text-muted-foreground')}>
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="font-semibold [overflow-wrap:anywhere]">{d.name || t('dbq.hol.unnamed')}</span>
        <span className="flex flex-wrap items-center gap-1.5 text-xs tabular-nums">
          {formatHoliday(d.day, lang)}
          {today && <Badge variant="outline" className="font-normal">{t('dbq.hol.today')}</Badge>}
          {past && <Badge variant="outline" className="font-normal text-muted-foreground">{t('dbq.hol.past')}</Badge>}
        </span>
        {error && <span className="text-xs text-destructive [overflow-wrap:anywhere]">{error}</span>}
      </div>
      {editable && (
        <div className="flex gap-1 sm:shrink-0">
          <Button type="button" variant="ghost" size="sm" onClick={start} aria-label={t('dbq.hol.editOf', d.name || d.day)} className="pointer-coarse:h-10">
            <Pencil aria-hidden="true" />{t('dbq.hol.edit')}
          </Button>
          <Button type="button" variant="ghost" size="sm" onClick={onRemove} aria-label={t('dbq.hol.deleteOf', d.name || d.day)}
            className="text-destructive hover:text-destructive pointer-coarse:h-10">
            <Trash2 aria-hidden="true" />{t('dbq.hol.delete')}
          </Button>
        </div>
      )}
    </li>
  )
}
