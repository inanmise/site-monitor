import { useEffect, useMemo, useState } from 'react'
import { CalendarClock } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import BulkPreviewDialog from '../admin/bulk/BulkPreviewDialog.jsx'
import { summarizeResult } from '../admin/bulk/bulkModel.js'
import Field from '../ui/Field.jsx'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'
import { expiryKey, fmtExpiry } from './domainCardModel.js'

/** Sunucunun tek seferde kabul ettiği en çok izleme (MonitoringController.DOMAIN_PLAN_BULK_MAX). */
export const DOMAIN_PLAN_BULK_MAX = 200
const NOTE_MAX = 500

/**
 * Toplu yenileme planı ÖNİZLEMESİ (saf — test edilir): seçili satırların TAHMİNİ sonucu. Karar sunucunundur (satır yeniden
 * okunur); burada yalnız ekranın bildiği veriyle "değişecek / atlanacak (neden)" gösterilir. `set`: bitişi bilinmeyen
 * `no_expiry`, aynı tarih + not `unchanged`; `clear`: planı olmayan `unchanged`.
 */
export function bulkPlanPreview(rows, action, date, note) {
  const n = String(note ?? '').trim() || null
  const out = (Array.isArray(rows) ? rows : []).slice(0, DOMAIN_PLAN_BULK_MAX).map((m) => {
    const base = { id: m.id, name: m.name || m.domain, domain: m.domain, plannedAt: m.renewal_planned_at || null, expiry: m.expiry_date || null }
    if (action === 'clear') {
      return m.renewal_planned_at ? { ...base, state: 'change' } : { ...base, state: 'skip', reason: 'unchanged' }
    }
    if (!expiryKey(m)) {
      // Sunucu kuralı (sırası da aynı: önce bitiş, sonra "değişmedi"): kaynağı NONE OLMAYAN en son kontrolün bitişi (inceleme
      // LOW 4). Satırın bitişi EN SON kontrolden gelir; o kontrol hiçbir kaynaktan yanıt alamadıysa (NONE) daha eski bir kontrol
      // bitişi bilebilir → "sunucu denetleyecek". Kontrol hiç yoksa ya da en son kaynaklı kontrol bitişsizse sunucu da atlar.
      return m.source === 'NONE' ? { ...base, state: 'check' } : { ...base, state: 'skip', reason: 'no_expiry' }
    }
    if (date && m.renewal_planned_at === date && (m.renewal_planned_note || null) === n) return { ...base, state: 'skip', reason: 'unchanged' }
    return { ...base, state: 'change' }
  })
  return {
    rows: out,
    change: out.filter((r) => r.state === 'change').length,
    skip: out.filter((r) => r.state === 'skip').length,
    check: out.filter((r) => r.state === 'check').length,
  }
}

const TOUCH = 'pointer-coarse:h-10'

/**
 * Seçili alan adı izlemelerine aynı yenileme planını KOY ya da KALDIR (2026-11-15, D1) — paylaşılan toplu işlem penceresi
 * (önizleme → TEK istek → satır satır sonuç). Yalnız düzenleyebildiği satırlar gelir (sayfa `canManageRow` ile süzer);
 * sunucu her satırı yeniden okur ve kapsamı yine denetler. Alan hatası (tarih / not / seçim) alanın altında.
 */
export default function DomainBulkPlanDialog({ open, rows, onClose, onFinished }) {
  const t = useT()
  const [action, setAction] = useState('set')
  const [date, setDate] = useState('')
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState({})

  useEffect(() => { if (open) { setAction('set'); setDate(''); setNote(''); setErrors({}) } }, [open])

  const plan = useMemo(() => bulkPlanPreview(rows, action, date, note), [rows, action, date, note])
  const dateMissing = action === 'set' && !/^\d{4}-\d{2}-\d{2}$/.test(date)
  const noteTooLong = action === 'set' && note.trim().length > NOTE_MAX

  async function confirm() {
    if (dateMissing || noteTooLong) {
      setErrors({ date: dateMissing ? t('dom.bulkPlan.dateRequired') : null, note: noteTooLong ? t('dom.bulkPlan.noteTooLong', NOTE_MAX) : null })
      return false
    }
    const ids = plan.rows.map((r) => r.id)
    const body = action === 'set' ? { action, ids, date, note: note.trim() || null } : { action, ids }
    const res = await api.monitoring.domainRenewalPlanBulk(body)
    if (!res?.success) {
      if (res?.field === 'date' || res?.field === 'note') { setErrors({ [res.field]: res.error }); return false }
      throw new Error(res?.error || t('adm.bulk.requestFailed'))
    }
    return summarizeResult(res.data)
  }

  const extraRowInfo = (r) => (
    <span data-slot="domain-bulk-plan-row-info" className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
      {r.domain !== r.name && <>{r.domain} · </>}
      {r.expiry ? t('dom.bulkPlan.expiry', fmtExpiry(r.expiry)) : t('dom.bulkPlan.expiryUnknown')}
      {r.plannedAt && <> · {t('dom.bulkPlan.current', r.plannedAt)}</>}
    </span>
  )

  return (
    <BulkPreviewDialog open={open} onClose={onClose} onFinished={onFinished} icon={CalendarClock}
      title={t('dom.bulkPlan.title', plan.rows.length)} description={t('dom.bulkPlan.desc')}
      plan={plan} extraRowInfo={extraRowInfo} keepOrder={false}
      confirmLabel={action === 'set' ? t('dom.bulkPlan.confirmSet') : t('dom.bulkPlan.confirmClear')}
      destructive={action === 'clear'} onConfirm={confirm}>
      <div data-slot="domain-bulk-plan-form" className="flex min-w-0 flex-col gap-2">
        <ToggleGroup type="single" variant="outline" value={action} aria-label={t('dom.bulkPlan.action')}
          onValueChange={(v) => { if (v) { setAction(v); setErrors({}) } }} className="w-full sm:w-auto">
          <ToggleGroupItem value="set" className={cn(TOUCH, 'flex-1 sm:flex-none')} data-slot="domain-bulk-plan-set">
            {t('dom.bulkPlan.set')}
          </ToggleGroupItem>
          <ToggleGroupItem value="clear" className={cn(TOUCH, 'flex-1 sm:flex-none')} data-slot="domain-bulk-plan-clear">
            {t('dom.bulkPlan.clear')}
          </ToggleGroupItem>
        </ToggleGroup>
        {action === 'set' && (
          <div className="grid min-w-0 gap-x-3 sm:grid-cols-2">
            <Field label={t('dom.bulkPlan.date')} required error={errors.date} name="date">
              {({ id, describedBy, invalid }) => (
                <Input id={id} type="date" value={date} aria-describedby={describedBy} aria-invalid={invalid}
                  className={TOUCH} onChange={(e) => { setDate(e.target.value); setErrors((x) => ({ ...x, date: null })) }} />
              )}
            </Field>
            <Field label={t('dom.bulkPlan.note')} hint={t('dom.bulkPlan.noteHint', NOTE_MAX)} error={errors.note} name="note">
              {({ id, describedBy, invalid }) => (
                <Textarea id={id} rows={2} value={note} aria-describedby={describedBy} aria-invalid={invalid}
                  onChange={(e) => { setNote(e.target.value); setErrors((x) => ({ ...x, note: null })) }} />
              )}
            </Field>
          </div>
        )}
        {(rows?.length || 0) > DOMAIN_PLAN_BULK_MAX && (
          <p data-slot="domain-bulk-plan-cap" className="text-xs text-muted-foreground">{t('dom.bulkPlan.cap', DOMAIN_PLAN_BULK_MAX)}</p>
        )}
      </div>
    </BulkPreviewDialog>
  )
}
