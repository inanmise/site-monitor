import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { X, CheckCircle2, Image as ImageIcon, TriangleAlert } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetClose } from '@/components/shadcn/sheet'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { IssueStatusBadge, SourceBadge, CategoryBadge, ImpactChips } from './IssueBadges.jsx'
import { IssueStepper, IssueConversation, IssueComposer } from './IssueThread.jsx'
import { IssueStatusActions, IssueTechDetails, IssueMailHistory } from './IssueAdminPanel.jsx'
import ImageLightbox from './ImageLightbox.jsx'
import { fmtDate, fmtRelative } from './issuesModel.js'

// Kaydırma kilidi sayaçlı (ModalShell / IncidentDetailSheet ile aynı sözleşme): iç içe pencerede erken açılmaz.
let scrollLocks = 0
let savedOverflow = ''

function H({ children }) {
  return <h3 className="m-0 text-xs font-bold tracking-wide text-muted-foreground uppercase">{children}</h3>
}

/**
 * Sorun bildirimi ayrıntısı — sağdan açılan Sheet (telefonda tam ekran), iki kitle tek tasarım:
 * başlık (referans + kopyala, durum/kaynak/önem, bildirim + son etkinlik) → [yönetici: durum eylemleri + not paneli,
 * kalıcı sil] → durum adımları → çözüm notu → açıklama kartı + hata metni (kopyala) → ekran görüntüsü galerisi
 * (ışık kutusu, ←/→) → [yönetici: teknik ayrıntılar, mail geçmişi] → konuşma (gün ayırıcılı sohbet) → altta sabit yazıcı.
 *
 * <p>`source`: 'mine' (GET/POST /api/issue-reports/mine/{id} — iç not HİÇ gelmez; açılış "görüldü" damgası vurur) |
 * 'admin' (GET /api/admin/login-issues/{id} — iç notlar dâhil). Yetki yoksa sunucu 404 döner → pencere kapanır.
 *
 * <p><b>Neden `modal={false}` + kendi scrim'i:</b> IncidentDetailSheet ile aynı gerekçe — içinden açılan ışık kutusu,
 * onay diyaloğu (kalıcı sil) ve kullanıcı rozeti pencereleri body'ye portal'lanır; Radix modal kipi onları tıklanamaz
 * bırakırdı. Kapatma yolları scrim, X ve Escape. Sol renk şeridi YOK.
 */
export default function IssueDetailSheet({ id, source = 'mine', onClose, onChanged, canEdit = false, canPurge = false, onPurged }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const admin = source === 'admin'
  const [detail, setDetail] = useState(null)
  const [loading, setLoading] = useState(true)
  const [reply, setReply] = useState('')
  const [internal, setInternal] = useState(false)
  const [sending, setSending] = useState(false)
  const [busy, setBusy] = useState(false)
  const [zoom, setZoom] = useState(null)
  const bodyRef = useRef(null)
  const composerRef = useRef(null)
  const nowMs = Date.now()
  // Yaşam bayrağı: Sheet kapandıktan (unmount) sonra dönen yükleme yanıtı ebeveyne onClose/onChanged çağırmasın —
  // A'nın geç gelen hatası, kullanıcının o arada açtığı B'nin Sheet'ini kapatıyordu. KURULUMDA da true (StrictMode).
  const aliveRef = useRef(true)
  useEffect(() => { aliveRef.current = true; return () => { aliveRef.current = false } }, [])

  const load = useCallback(async ({ quiet = false } = {}) => {
    if (id == null) return
    if (!quiet) setLoading(true)
    try {
      const res = admin ? await api.admin.getLoginIssue(id) : await api.issueReports.mineDetail(id)
      if (!aliveRef.current) return
      if (res?.success) {
        setDetail(res.data)
        if (!admin && !quiet) onChanged?.({ seen: id })   // açılış "görüldü" damgası vurdu → okunmamış noktası söner
      } else {
        toast.error(res?.error || t('settings.loadError'))
        onClose?.()
      }
    } catch (e) {
      if (!aliveRef.current) return
      toast.error(e?.message || t('settings.loadError'))
      onClose?.()
    } finally {
      if (aliveRef.current) setLoading(false)
    }
  }, [id, admin])   // eslint-disable-line react-hooks/exhaustive-deps -- toast/onClose/onChanged kararlı değil; yalnız kayıt/kitle değişince yükle
  useEffect(() => { setDetail(null); setReply(''); setInternal(false); load() }, [load])

  // Odak iadesi: kapanışta tetikleyiciye (satır/kart).
  useEffect(() => {
    const previous = document.activeElement
    return () => { if (previous && typeof previous.focus === 'function' && document.contains(previous)) previous.focus() }
  }, [])
  // Arka plan kaydırma kilidi (sayaçlı).
  useEffect(() => {
    if (scrollLocks === 0) { savedOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden' }
    scrollLocks += 1
    return () => { scrollLocks -= 1; if (scrollLocks === 0) document.body.style.overflow = savedOverflow }
  }, [])

  const scrollToEnd = () => setTimeout(() => { const el = bodyRef.current; if (el) el.scrollTop = el.scrollHeight }, 0)

  async function send() {
    const text = reply.trim()
    if (!text || sending || !detail) return
    setSending(true)
    try {
      if (admin) {
        const res = await api.admin.addLoginIssueComment(detail.id, { body: text, internal })
        if (!res?.success) { toast.error(res?.error || t('loginIssues.commentError')); return }
        setDetail((d) => (d ? { ...d, comments: [...(d.comments || []), res.data].filter(Boolean), lastActivityAt: res.data?.createdAt || d.lastActivityAt } : d))
        toast.success(internal ? t('loginIssues.noteSaved') : t('loginIssues.replySent'))
      } else {
        const res = await api.issueReports.addComment(detail.id, text)
        if (!res?.success) { toast.error(res?.error || t('myIssues.sendError')); return }
        const d = res.data || {}
        setDetail((prev) => (prev ? {
          ...prev,
          status: d.status || prev.status,
          resolvedAt: d.reopened ? null : prev.resolvedAt,
          comments: [...(prev.comments || []), d.comment].filter(Boolean),
          timeline: d.reopened
            ? [...(prev.timeline || []), { status: 'IN_PROGRESS', at: d.comment?.createdAt, by: d.comment?.author, byReporter: true }]
            : prev.timeline,
          lastActivityAt: d.comment?.createdAt || prev.lastActivityAt,
        } : prev))
        toast.success(d.reopened ? t('myIssues.reopened') : t('myIssues.sent'))
      }
      setReply('')
      scrollToEnd()
      onChanged?.({ commented: detail.id })
    } catch (e) {
      toast.error(e?.message || (admin ? t('loginIssues.commentError') : t('myIssues.sendError')))
    } finally {
      setSending(false)
    }
  }

  /** Durum geçişi — mevcut uç; yanıt güncel ayrıntıyı (yorumlar + zaman çizelgesi + mail geçmişi) döndürür. */
  async function changeStatus(status, note) {
    setBusy(true)
    try {
      const res = await api.admin.updateLoginIssueStatus(detail.id, { status, resolutionNote: note || undefined })
      if (res?.success) {
        setDetail(res.data)
        toast.success(t('loginIssues.statusUpdated'))
        onChanged?.({ status: detail.id })
        return true
      }
      toast.error(res?.error || t('loginIssues.statusError'))
      return false
    } catch (e) {
      toast.error(e?.message || t('loginIssues.statusError'))
      return false
    } finally {
      setBusy(false)
    }
  }

  /** Kalıcı silme — onay metni NE kaybedildiğini açıkça söyler (maillerin saklanan kopyaları dâhil). Geri alınamaz. */
  async function purge() {
    const ok = await showConfirm({
      title: t('loginIssues.purgeTitle'),
      message: t('loginIssues.purgeMsg', detail.refCode ?? ''),
      confirmText: t('loginIssues.purgeConfirm'),
      variant: 'danger',
    })
    if (!ok) return
    setBusy(true)
    try {
      const res = await api.admin.purgeLoginIssue(detail.id)
      if (res?.success) { toast.success(t('loginIssues.purgeDone')); onPurged?.(detail.id); onClose?.() }
      else toast.error(res?.error || t('loginIssues.purgeFailed'))
    } finally {
      setBusy(false)
    }
  }

  const d = detail
  const reporterName = admin ? (d?.username || t('loginIssues.reporterLabel')) : null
  const images = d?.images || []

  return (
    <Sheet open modal={false} onOpenChange={(next) => { if (!next && !busy) onClose?.() }}>
      {createPortal(
        <div data-slot="dialog-overlay" aria-hidden="true"
          className="fixed inset-0 z-[1000] bg-black/50 animate-in fade-in-0 motion-reduce:animate-none"
          onClick={(e) => { if (e.target === e.currentTarget && !busy) onClose?.() }} />,
        document.body,
      )}
      <SheetContent side="right" showCloseButton={false} data-slot="issue-detail" data-issue-id={id} data-source={source}
        aria-modal="true" onInteractOutside={(e) => e.preventDefault()} onCloseAutoFocus={(e) => e.preventDefault()}
        className="z-[1001] flex h-[100dvh] w-full flex-col gap-0 p-0 sm:w-[min(46rem,calc(100vw-2rem))] sm:max-w-none">
        <SheetHeader className="shrink-0 gap-1.5 border-b px-4 py-3 text-left">
          <div className="flex min-w-0 items-start justify-between gap-3">
            <div className="flex min-w-0 flex-wrap items-center gap-2">
              <SheetTitle className="font-mono text-base tracking-wide">{d?.refCode || `#${id}`}</SheetTitle>
              {d?.refCode && <CopyButton value={d.refCode} variant="ghost" buttonSize="icon-sm" label={t('issues.copyRef', d.refCode)} copiedLabel={t('irf.copied')} className="text-muted-foreground" />}
              {d && <IssueStatusBadge status={d.status} />}
            </div>
            <SheetClose asChild>
              <Button type="button" variant="ghost" size="icon" className="-mt-1 -mr-2 shrink-0 text-muted-foreground" aria-label={t('app.close')} disabled={busy}>
                <X aria-hidden="true" />
              </Button>
            </SheetClose>
          </div>
          <SheetDescription className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1.5 text-xs">
            {d ? (
              <>
                <SourceBadge source={d.source} />
                <CategoryBadge category={d.category} />
                <span>{t('issues.reportedOn', fmtDate(d.reportedAt))}</span>
                <span aria-hidden="true">·</span>
                <span title={fmtDate(d.lastActivityAt)}>{t('issues.lastActivity', fmtRelative(d.lastActivityAt, t, nowMs))}</span>
              </>
            ) : t('settings.loading')}
          </SheetDescription>
          {admin && d?.username && <div className="text-xs"><UserBadge username={d.username} email={d.reporterEmail} size="sm" /></div>}
        </SheetHeader>

        <div ref={bodyRef} data-slot="issue-detail-body" className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto px-4 py-4">
          {loading && !d && <LoadingBlock label={t('settings.loading')} fullWidth />}
          {d && (
            <>
              {admin && canEdit && (
                <IssueStatusActions detail={d} onChangeStatus={changeStatus} onPurge={purge} canPurge={canPurge} busy={busy} />
              )}

              <IssueStepper detail={d} reporterName={reporterName} />

              {d.status === 'RESOLVED' && (
                <AlertBanner tone="success" icon={CheckCircle2} className="mb-0" title={t('issues.resolvedTitle', d.resolvedBy || '—', fmtDate(d.resolvedAt))}>
                  {d.resolutionNote ? <span className="whitespace-pre-wrap">{d.resolutionNote}</span> : t('issues.resolvedNoNote')}
                </AlertBanner>
              )}

              <section className="flex min-w-0 flex-col gap-2">
                <H>{admin ? t('loginIssues.message') : t('myIssues.yourMessage')}</H>
                <Card data-slot="issue-description" className="gap-0 px-4 py-3">
                  <p className="m-0 text-sm leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere]">{d.message}</p>
                </Card>
                {/* "Ne yaşıyorsunuz?" (2026-09-28): seçilen etkiler tam cümleyle; "Diğer" metni çipte */}
                {Array.isArray(d.impacts) && d.impacts.length > 0 && (
                  <div data-slot="issue-detail-impacts" className="flex min-w-0 flex-col gap-1.5">
                    <span className="text-xs font-semibold text-muted-foreground">{t('issues.impacts')}</span>
                    <ImpactChips row={d} />
                  </div>
                )}
                {d.errorText && (
                  <div className="min-w-0 overflow-hidden rounded-lg border bg-muted/40">
                    <div className="flex items-center justify-between gap-2 border-b px-3 py-1.5">
                      <span className="flex items-center gap-1.5 text-xs font-semibold text-muted-foreground"><TriangleAlert aria-hidden="true" className="size-3.5" />{t('loginIssues.errorText')}</span>
                      <CopyButton value={d.errorText} variant="ghost" buttonSize="icon-xs" size={12} label={t('issues.copyError')} copiedLabel={t('irf.copied')} />
                    </div>
                    <pre data-slot="issue-error-text" className="m-0 max-h-56 overflow-auto px-3 py-2 font-mono text-xs leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere]">{d.errorText}</pre>
                  </div>
                )}
              </section>

              {images.length > 0 && (
                <section className="flex min-w-0 flex-col gap-2">
                  <H><span className="inline-flex items-center gap-1.5"><ImageIcon aria-hidden="true" className="size-3.5" />{t('loginIssues.images')} ({images.length})</span></H>
                  <ul data-slot="issue-gallery" className="m-0 grid list-none grid-cols-[repeat(auto-fill,minmax(120px,1fr))] gap-2 p-0">
                    {images.map((src, i) => (
                      <li key={i} className="min-w-0">
                        <Button type="button" variant="outline" onClick={() => setZoom(i)} aria-label={t('issue.imgZoom', i + 1)} title={t('loginIssues.imageZoomHint')}
                          className="block aspect-[16/10] h-auto w-full cursor-zoom-in overflow-hidden rounded-md p-0 has-[>svg]:p-0">
                          <img src={src} alt="" className="block size-full object-cover" />
                        </Button>
                      </li>
                    ))}
                  </ul>
                </section>
              )}

              {admin && (
                <div className="flex flex-col gap-2">
                  <IssueTechDetails detail={d} />
                  <IssueMailHistory detail={d} onRefresh={() => load({ quiet: true })} />
                </div>
              )}

              <section className="flex min-w-0 flex-col gap-2">
                <H>{t('myIssues.conversation')}</H>
                <IssueConversation detail={d} reporterName={reporterName} admin={admin} />
              </section>
            </>
          )}
        </div>

        {d && (
          <IssueComposer value={reply} onChange={setReply} onSend={send} sending={sending} admin={admin}
            internal={internal} onInternal={setInternal} resolved={d.status === 'RESOLVED'} textareaRef={composerRef} />
        )}
      </SheetContent>
      <ImageLightbox images={images} index={zoom} onIndex={setZoom} onClose={() => setZoom(null)} />
    </Sheet>
  )
}
