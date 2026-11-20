import { useEffect, useRef, useState } from 'react'
import { RefreshCw } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import Field from '../ui/Field.jsx'
import { Button } from '@/components/shadcn/button'
import { Textarea } from '@/components/shadcn/textarea'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/shadcn/dialog'
import { approvalOutcome } from './approvalOutcome.js'

/**
 * PO kararı penceresi (2026-11-20, A2 — ApprovalCard'dan çıkarıldı; onay kartı ve e-posta önizlemesi AYNI pencereyi kullanır).
 * `mode` 'approve' | 'reject' | null (kapalı). Ret gerekçe ister (boşsa alanın altında hata, istek gitmez); onayda not isteğe
 * bağlı. Sunucu kararı doğrular (PO üyeliği, bekleyen istek, eskimiş istek 409). Başarıda ve alan dışı hatada pencere kapanır
 * (`onClose`), sunucunun alan hatası (`field: note`) alanın altında kalır. Her denemeden sonra `onDone({ ok })` (rapor yeniden
 * okunur; `ok` = karar sunucuda kayıtlı) — eski ApprovalCard davranışı. Açılışta not ve hata sıfırlanır.
 */
export default function ApprovalDecideDialog({ reportId, mode, execCount = 0, onClose, onBusy, onDone }) {
  const t = useT()
  const toast = useToast()
  const [note, setNote] = useState('')
  const [noteError, setNoteError] = useState(null)
  const [deciding, setDeciding] = useState(false)
  const decidingRef = useRef(false)
  const reject = mode === 'reject'

  // Her açılış temiz: önceki notu / hatayı taşımaz
  useEffect(() => {
    if (mode) { setNote(''); setNoteError(null) }
  }, [mode])

  async function decide() {
    if (decidingRef.current) return
    const approve = !reject
    const trimmed = note.trim()
    if (!approve && !trimmed) { setNoteError(t('te.appr.noteRequired')); return }
    decidingRef.current = true
    setDeciding(true)
    onBusy?.('approval')
    let ok = false
    try {
      const res = await api.techErrors.decideApproval(reportId, { decision: approve ? 'approve' : 'reject', note: trimmed || null })
      if (res?.success && res.data) {
        const d = res.data
        if (approve) {
          const o = approvalOutcome(d, t)
          if (o.tone === 'success') toast.success(o.text)
          else if (o.tone === 'info') toast.info(o.text, 8000)
          else toast.error(o.text, { duration: 8000 })
        } else toast.success(t('te.appr.rejected'))
        ok = true
        setNote('')
        onClose?.()
      } else if (res?.field === 'note') {
        setNoteError(res.error)
      } else {
        toast.error(res?.error || t('te.appr.decideError'), { details: res?.errorInfo })
        onClose?.()
      }
    } catch (e) {
      toast.error(e?.message || t('te.appr.decideError'))
    } finally {
      decidingRef.current = false
      setDeciding(false)
      onBusy?.(null)
      onDone?.({ ok })
    }
  }

  return (
    <Dialog open={mode != null} onOpenChange={(o) => { if (!o && !deciding) onClose?.() }}>
      <DialogContent className="max-h-[92dvh] overflow-y-auto sm:max-w-lg" data-slot="te-approval-dialog">
        <DialogHeader>
          <DialogTitle>{reject ? t('te.appr.rejectTitle') : t('te.appr.approveTitle')}</DialogTitle>
          <DialogDescription>{reject ? t('te.appr.rejectBody') : t('te.appr.approveBody', execCount)}</DialogDescription>
        </DialogHeader>
        <Field label={reject ? t('te.appr.rejectNote') : t('te.appr.approveNote')} required={reject} error={noteError} name="note">
          {({ id, describedBy, invalid }) => (
            <Textarea id={id} rows={3} maxLength={1000} value={note} aria-describedby={describedBy} aria-invalid={invalid}
              onChange={(e) => { setNote(e.target.value); setNoteError(null) }} />
          )}
        </Field>
        <DialogFooter className="flex-col-reverse gap-2 sm:flex-row">
          <Button type="button" variant="outline" disabled={deciding} onClick={() => onClose?.()} className="pointer-coarse:min-h-10">
            {t('te.cancel')}
          </Button>
          <Button type="button" variant={reject ? 'destructive' : 'default'} disabled={deciding}
            onClick={decide} data-slot="te-approval-confirm" className="pointer-coarse:min-h-10">
            {deciding ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : null}
            {reject ? t('te.appr.reject') : t('te.appr.approve')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
