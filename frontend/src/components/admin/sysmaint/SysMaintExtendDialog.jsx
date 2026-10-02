import { useEffect, useId, useState } from 'react'
import { TimerReset } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useToast } from '../../ui/Toast.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import Field from '../../ui/Field.jsx'
import DateTimeField from '../../ui/DateTimeField.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { useFormErrors } from '../../../hooks/useFormErrors.js'
import { timeText } from '../../../utils/systemMaintenance.js'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { DEFAULT_OPTIONS, fieldOf, istanbulToMs, toIstanbulParts } from './sysmaintModel.js'

/**
 * "Uzat" (2026-10-02, kullanıcı kararı) — süren bakımın bitişini +15 / +30 / +60 dk ya da yeni bir saate İLERİ alır.
 * Saat değiştiği için duyuru e-postası gittiyse düzeltme e-postası tetiklenir (sunucu sürüm artırır). Ayarlar sayfası ve
 * global yöneticinin bakım şeridi aynı pencereyi açar. Test kancası: `data-slot="sysmaint-extend-form"`.
 */
export default function SysMaintExtendDialog({ open, onClose, onSaved, window: w, options = DEFAULT_OPTIONS }) {
  const t = useT()
  const toast = useToast()
  const fe = useFormErrors(open)
  const [mode, setMode] = useState(30)   // dakika ya da 'custom'
  const [date, setDate] = useState('')
  const [time, setTime] = useState('')
  const [busy, setBusy] = useState(false)
  const timeId = useId()

  useEffect(() => {
    if (!open) return
    setMode(30)
    const end = Date.parse(w?.end_at || '')
    const p = toIstanbulParts(Number.isFinite(end) ? end + 60 * 60_000 : Date.now() + 60 * 60_000)
    setDate(p.date)
    setTime(p.time)
  }, [open, w?.end_at])

  const choices = [...(options.extend_minutes || DEFAULT_OPTIONS.extend_minutes).map((m) => ({ value: m, label: `+${t('sysmaint.minutesShort', m)}` })),
    { value: 'custom', label: t('sysmaint.extend.custom') }]

  async function submit() {
    let body
    if (mode === 'custom') {
      const ms = istanbulToMs(date, time)
      const cur = Date.parse(w?.end_at || '')
      if (fe.check({ end_local: ms == null ? t('sysmaint.err.endRequired') : (Number.isFinite(cur) && ms <= cur) ? t('sysmaint.err.extendEarlier') : false })) return
      body = { end_local: `${date}T${time}` }
    } else {
      body = { minutes: Number(mode) }
    }
    setBusy(true)
    try {
      const res = await api.systemMaintenance.extend(w.id, body)
      if (res?.success) {
        toast.success(t('sysmaint.toast.extended', timeText(res.data?.end_at)))
        onSaved?.(res.data)
      } else {
        const field = fieldOf(res?.field)
        if (field === 'end_local' && mode === 'custom') fe.check({ end_local: res?.error || t('sysmaint.err.generic') })
        else toast.error(res?.error || t('sysmaint.err.generic'))
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <ModalShell open={open} onClose={onClose} busy={busy} size="sm" icon={TimerReset} title={t('sysmaint.extend.title')}
      footer={(
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={busy} className="min-h-10">{t('app.cancel')}</Button>
          <Button type="button" onClick={submit} disabled={busy} aria-busy={busy || undefined} data-slot="sysmaint-extend-submit" className="min-h-10">
            {busy ? <Spinner size={15} inline decorative /> : <TimerReset aria-hidden="true" />}{t('sysmaint.extend.submit')}
          </Button>
        </>
      )}>
      <div data-slot="sysmaint-extend-form" className="flex min-w-0 flex-col gap-3">
        <p className="m-0 text-sm text-muted-foreground">{t('sysmaint.extend.current', timeText(w?.end_at))}</p>
        <SegmentedControl value={mode} onChange={setMode} options={choices} ariaLabel={t('sysmaint.extend.title')} />
        {mode === 'custom' && (
          <div className="grid min-w-0 grid-cols-[minmax(0,1fr)_auto] items-start gap-x-3">
            <Field label={t('sysmaint.extend.newEnd')} required hint={t('sysmaint.form.istanbulHint')} className="min-w-0" {...fe.fieldProps('end_local')}>
              {({ id, describedBy, invalid }) => (
                <DateTimeField dateOnly id={id} value={date} onChange={(v) => { setDate(v); fe.clear('end_local') }}
                  invalid={invalid} describedBy={describedBy} />
              )}
            </Field>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor={timeId} className="font-semibold">{t('sysmaint.form.time')}</Label>
              <Input id={timeId} type="time" step={60} value={time} className="h-10 w-[7.5rem]"
                onChange={(e) => { setTime(e.target.value); fe.clear('end_local') }} />
            </div>
          </div>
        )}
      </div>
    </ModalShell>
  )
}
