import { useState } from 'react'
import { CheckCircle2, Clock, RefreshCw, ShieldCheck, XCircle } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import Field from '../ui/Field.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Textarea } from '@/components/shadcn/textarea'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/shadcn/dialog'
import { cn } from '@/lib/utils'
import { fmtDateTime } from './TechErrorParts.jsx'

/** Onay durumu → ton (rozet / kart kenarı). Bilinmeyen durum nötr; "stale" uyarı (yeniden onay gerekir). Alıcı özeti de kullanır (R13). */
export const APPROVAL_TONE = Object.freeze({
  none: 'bg-muted text-muted-foreground',
  pending: 'bg-amber-500/15 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300',
  approved: 'bg-green-600/10 text-green-800 dark:bg-green-500/15 dark:text-green-300',
  rejected: 'bg-destructive/10 text-destructive',
  stale: 'bg-amber-500/15 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300',
})
const TONE = APPROVAL_TONE
const ICON = { none: ShieldCheck, pending: Clock, approved: CheckCircle2, rejected: XCircle, stale: RefreshCw }

/**
 * Onay akışı kaldırılmış kanalda (yönetici alıcısı yok, rapor eski bir onay durumu taşıyor) ipucu anahtarı — inceleme
 * 2026-10-18: ŞU ANKİ içeriği gönderilmiş rapor "gönderildi" der ("doğrudan gönderebilirsiniz" değil); bekleyen istek "karar
 * verilemez, alıcılar geri gelirse istek yeniden geçerli" der; reddedilmiş / eskimiş / onaylanıp gönderilmemiş rapor "bekleyen"
 * sözcüğü taşımaz. `contentSent` = {@link currentContentSent} (raporun herhangi bir zamanki son gönderimi DEĞİL).
 */
export function flowRemovedHintKey(state, contentSent) {
  if (contentSent) return 'te.appr.flowRemovedSentHint'
  if (state === 'pending') return 'te.appr.flowRemovedPendingHint'
  return 'te.appr.flowRemovedHint'
}

const ms = (iso) => {
  const v = iso ? Date.parse(iso) : NaN
  return Number.isFinite(v) ? v : null
}

/**
 * Raporun ŞU ANKİ içeriği gönderildi mi (inceleme 2026-10-18, 2. tur): `last_sent_at` raporun HERHANGİ bir zamanki son
 * gönderimidir — önce gönderilip sonra değiştirilen, yeniden onaya sunulan (bekleyen) raporda da doludur; kart onu
 * "gönderildi" diye okuyunca kararı verilemeyen isteği ve gönderilmemiş içeriği gizliyordu. Kaynak raporun liste durumu
 * (`report.status`, sunucu `reportStatus`: yalnız "sent" = son gönderimden sonra içerik değişmedi). Durum gelmezse (eski
 * çağıran) zaman kuralı: son gönderim isteğin kendisinden sonra ve istek eskimemiş. Onaylı raporda ek olarak gönderim
 * KARARDAN sonra olmalı — akış eklenmeden önceki bir takım gönderimi "yöneticilere gönderildi" demez.
 */
export function currentContentSent({ reportStatus, sentAt, approval, state } = {}) {
  const sent = ms(sentAt)
  if (sent == null) return false
  const requested = ms(approval?.requested_at)
  let ok
  if (typeof reportStatus === 'string' && reportStatus) ok = reportStatus === 'sent'
  else ok = state !== 'stale' && (requested == null || sent >= requested)
  if (!ok) return false
  if (state === 'approved') {
    const decided = ms(approval?.decided_at)
    if (decided != null && sent < decided) return false
  }
  return true
}

export function approvalStateOf(approval) {
  const s = approval?.state
  return TONE[s] ? s : 'none'
}

/** Onay durumunun etiket anahtarı — kart rozeti ve rapor başlığındaki alıcı özeti aynı metni kullanır (tek kaynak). */
export function approvalStateLabelKey(approval) {
  return `te.appr.state.${approvalStateOf(approval)}`
}

/**
 * PO onayı kartı (2026-10-17, S6). Yalnız onay akışı olan kanallarda (yönetici alıcısı tanımlı) ya da raporun bir onay
 * geçmişi varsa çizilir. Durum rozeti + kim / ne zaman + not; ekip için "Onaya gönder" (eylem çubuğunda da), PO için
 * "Onayla" / "Reddet" (gerekçe zorunlu). Karar sunucuda doğrulanır (PO üyeliği, bekleyen istek, eskimiş istek 409).
 */
/**
 * Onay sonrası yöneticilere gönderimin sonucu → cümle (UIA-E1, 2026-10-22). Eskiden ham kod basılıyordu ("…gönderilemedi
 * (APPROVAL_STALE). Yöneticilere gönder ile yeniden deneyin") — onaydan sonra değişen raporda o düğme yine 409 alır, gönderim
 * sürerken ikinci gönderime çağırıyordu. Anlamlar sunucunun push cümleleriyle aynı (TechErrorPushService.approvedParts).
 * SENT + boş `sent_to` (yöneticilerin hepsi pasif, posta yalnız takım CC'sine gitti) "yalnız takım kopyası" der.
 * @returns {{ tone: 'success'|'info'|'error', text: string }}
 */
export function approvalOutcome(d, t) {
  const code = d?.send_state || d?.send_error || 'FAILED'
  if (code === 'SENT') {
    const to = Array.isArray(d?.sent_to) ? d.sent_to.filter(Boolean) : []
    return to.length ? { tone: 'success', text: t('te.appr.approved', to.join(', ')) } : { tone: 'success', text: t('te.appr.outcome.ccOnly') }
  }
  if (code === 'QUEUED') return { tone: 'info', text: t('te.appr.approvedQueued') }
  if (code === 'SKIPPED') return { tone: 'info', text: t('te.appr.outcome.SKIPPED') }
  if (code === 'APPROVAL_STALE') return { tone: 'error', text: t('te.appr.outcome.APPROVAL_STALE') }
  if (code === 'SEND_IN_PROGRESS') return { tone: 'info', text: t('te.appr.outcome.SEND_IN_PROGRESS') }
  if (code === 'PDF_BUSY') return { tone: 'error', text: t('te.appr.outcome.PDF_BUSY') }
  if (code === 'PDF_TOO_LARGE') return { tone: 'error', text: t('te.appr.outcome.PDF_TOO_LARGE') }
  return { tone: 'error', text: t('te.appr.outcome.other') }
}

/**
 * Onaylayıcı satırı (UIA-E2, 2026-10-22): paylaşımla okuyan (grant) adları görmez, sunucu adsız satırlar + `approver_count`
 * gönderir — eskiden yalnız adlar sayılıyor, "takımda PO yok" deniyordu. Adlar varsa adlar; yoksa sayı > 0 ise "N PO
 * onaylayıcı (adlar paylaşımla gösterilmez)"; yalnız sayı 0 iken "PO yok".
 */
export function approversLine(detail, t) {
  const approvers = Array.isArray(detail?.approvers) ? detail.approvers : []
  const names = approvers.map((a) => a?.name).filter(Boolean)
  if (names.length) return t('te.appr.approvers', names.join(', '))
  const n = Number(detail?.approver_count ?? approvers.length) || 0
  if (n > 0) return t('te.appr.approversHidden', n)
  return t('te.appr.noApprovers')
}

export default function ApprovalCard({ reportId, detail, approval, uiLang, busy, onBusy, onRequest, onChanged, sentAt, sentTo, reportStatus }) {
  const t = useT()
  const toast = useToast()
  const [dialog, setDialog] = useState(null)       // 'approve' | 'reject'
  const [note, setNote] = useState('')
  const [noteError, setNoteError] = useState(null)
  const [deciding, setDeciding] = useState(false)

  const state = approvalStateOf(approval)
  const required = !!detail?.required
  if (!required && state === 'none') return null
  // Kanalın yönetici alıcıları kaldırıldı (inceleme 2026-10-18): eski onay durumu bir şey beklemez — karar verilemez (sunucu
  // 409 APPROVAL_FLOW_REMOVED), rapor takım adresine doğrudan gönderilir. Rozet "Onay akışı kaldırıldı" der (eski "Onay
  // bekliyor" yanıltıyordu), önceki durum ayrı bir satırda yalnız bilgi; ipucu duruma göre (`flowRemovedHintKey`)
  const flowRemoved = !required && state !== 'none'
  const Icon = flowRemoved ? ShieldCheck : ICON[state] || ShieldCheck
  const execCount = Number(detail?.executive_count) || 0

  async function decide(approve) {
    const trimmed = note.trim()
    if (!approve && !trimmed) { setNoteError(t('te.appr.noteRequired')); return }
    setDeciding(true)
    onBusy?.('approval')
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
        setDialog(null)
        setNote('')
      } else if (res?.field === 'note') {
        setNoteError(res.error)
      } else {
        toast.error(res?.error || t('te.appr.decideError'), { details: res?.errorInfo })
        setDialog(null)
      }
    } catch (e) {
      toast.error(e?.message || t('te.appr.decideError'))
    } finally {
      setDeciding(false)
      onBusy?.(null)
      onChanged?.()
    }
  }

  // "Gönderildi" yalnız ŞU ANKİ içerik gönderildiyse (inceleme 2026-10-18, 2. tur) — eski bir gönderim bekleyen isteği gizlemez
  const contentSent = currentContentSent({ reportStatus, sentAt, approval, state })
  const hint = flowRemoved ? t(flowRemovedHintKey(state, contentSent))
    : state === 'pending' ? t('te.appr.pendingHint')
    : state === 'rejected' ? t('te.appr.rejectedHint')
      : state === 'stale' ? t('te.appr.state.stale')
        : state === 'approved' ? (contentSent ? t('te.appr.sentHint') : t('te.appr.waitingSend')) : null

  return (
    <section aria-labelledby="te-approval-title" data-slot="te-approval" data-state={state}
      className="flex flex-col gap-3 rounded-xl border bg-card p-4 shadow-xs">
      <div className="flex flex-wrap items-center gap-2">
        <h2 id="te-approval-title" className="m-0 flex items-center gap-2 text-base font-semibold">
          <ShieldCheck aria-hidden="true" className="size-4 text-muted-foreground" /> {t('te.appr.title')}
        </h2>
        <Badge className={cn('rounded-md overflow-visible whitespace-normal', TONE[flowRemoved ? 'none' : state])} data-slot="te-approval-state"
          data-flow={flowRemoved ? 'removed' : undefined}>
          <Icon aria-hidden="true" className="size-3.5" /> {t(flowRemoved ? 'te.appr.state.flowRemoved' : `te.appr.state.${state}`)}
        </Badge>
        {required && <Badge variant="outline" className="rounded-md overflow-visible whitespace-normal">{t('te.appr.execCount', execCount)}</Badge>}
      </div>
      <p className="m-0 text-sm text-muted-foreground [overflow-wrap:anywhere]">{t('te.appr.desc')}</p>
      {(detail?.requested_by_name || approval?.requested_at) && state !== 'none' && (
        <p className="m-0 text-sm [overflow-wrap:anywhere]">{t('te.appr.requestedBy', detail?.requested_by_name || '—', fmtDateTime(approval?.requested_at, uiLang))}</p>
      )}
      {approval?.decided_at && (state === 'approved' || state === 'rejected' || state === 'stale') && (
        <p className="m-0 text-sm [overflow-wrap:anywhere]">{t('te.appr.decidedBy', detail?.decided_by_name || '—', fmtDateTime(approval?.decided_at, uiLang))}</p>
      )}
      {approval?.note && (
        <blockquote className="m-0 rounded-md border-l-0 bg-muted/60 px-3 py-2 text-sm [overflow-wrap:anywhere]" data-slot="te-approval-note">
          <span className="font-semibold">{t('te.appr.note')}: </span>{approval.note}
        </blockquote>
      )}
      {flowRemoved && (
        <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-approval-prev">
          {t('te.appr.prevState', t(state === 'stale' ? 'te.appr.state.staleShort' : `te.appr.state.${state}`))}
        </p>
      )}
      {hint && <p className="m-0 text-sm text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-approval-hint">{hint}</p>}
      {(state === 'approved' || flowRemoved) && sentAt && (
        <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.lastSent', fmtDateTime(sentAt, uiLang), sentTo || '—')}</p>
      )}
      {required && (
        <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-approval-approvers">
          {approversLine(detail, t)}
        </p>
      )}
      {(detail?.can_request || detail?.can_decide) && (
        <div className="flex flex-wrap items-center gap-2">
          {detail?.can_request && (
            <Button type="button" variant="outline" className="pointer-coarse:min-h-10" disabled={busy != null} onClick={onRequest}
              data-slot="te-approval-request">
              {busy === 'approval' ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : <ShieldCheck aria-hidden="true" />}
              {detail?.resend ? t('te.appr.resend') : t('te.appr.request')}
            </Button>
          )}
          {detail?.can_decide && (
            <>
              <span className="text-xs text-muted-foreground">{t('te.appr.youCanDecide')}</span>
              <Button type="button" className="pointer-coarse:min-h-10" disabled={busy != null}
                onClick={() => { setNote(''); setNoteError(null); setDialog('approve') }} data-slot="te-approval-approve">
                <CheckCircle2 aria-hidden="true" /> {t('te.appr.approve')}
              </Button>
              <Button type="button" variant="destructive" className="pointer-coarse:min-h-10" disabled={busy != null}
                onClick={() => { setNote(''); setNoteError(null); setDialog('reject') }} data-slot="te-approval-reject">
                <XCircle aria-hidden="true" /> {t('te.appr.reject')}
              </Button>
            </>
          )}
        </div>
      )}

      <Dialog open={dialog != null} onOpenChange={(o) => { if (!o && !deciding) setDialog(null) }}>
        <DialogContent className="max-h-[92dvh] overflow-y-auto sm:max-w-lg" data-slot="te-approval-dialog">
          <DialogHeader>
            <DialogTitle>{dialog === 'reject' ? t('te.appr.rejectTitle') : t('te.appr.approveTitle')}</DialogTitle>
            <DialogDescription>{dialog === 'reject' ? t('te.appr.rejectBody') : t('te.appr.approveBody', execCount)}</DialogDescription>
          </DialogHeader>
          <Field label={dialog === 'reject' ? t('te.appr.rejectNote') : t('te.appr.approveNote')} required={dialog === 'reject'}
            error={noteError} name="note">
            {({ id, describedBy, invalid }) => (
              <Textarea id={id} rows={3} maxLength={1000} value={note} aria-describedby={describedBy} aria-invalid={invalid}
                onChange={(e) => { setNote(e.target.value); setNoteError(null) }} />
            )}
          </Field>
          <DialogFooter className="flex-col-reverse gap-2 sm:flex-row">
            <Button type="button" variant="outline" disabled={deciding} onClick={() => setDialog(null)}>{t('te.cancel')}</Button>
            <Button type="button" variant={dialog === 'reject' ? 'destructive' : 'default'} disabled={deciding}
              onClick={() => decide(dialog !== 'reject')} data-slot="te-approval-confirm">
              {deciding ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : null}
              {dialog === 'reject' ? t('te.appr.reject') : t('te.appr.approve')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  )
}
