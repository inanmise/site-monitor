import { useCallback, useEffect, useRef, useState } from 'react'
import { CircleAlert, Mail, MailCheck, Monitor, Paperclip, RefreshCw, Send, Smartphone } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/shadcn/dialog'
import { cn } from '@/lib/utils'
import { fmtDateTime } from './TechErrorParts.jsx'
import { mailPreviewSrcDoc } from '../../utils/mailPreview.js'

/** Önizleme genişlikleri: e-postanın kendi 1000 px kartı (masaüstü istemci) ve telefon tek sütunu. */
export const PREVIEW_WIDTHS = Object.freeze({ desktop: 1040, phone: 400 })

/**
 * E-posta önizlemesi (2026-10-17, 10. sürüm — kullanıcı isteği: "mail göndermeden önce overview edebilmeliyiz"). Sunucu
 * gönderimle AYNI gövdeyi üretir (`GET /reports/{id}/mail-preview`; talep yok, posta yok); pencere konuyu, teslim yolunu
 * (takım / yöneticiler), alıcıları (paylaşımla okuyan yalnız sayıyı görür), eki ve görsel sayımını gösterir, gövdeyi
 * kumsandık (sandbox) iframe'de çizer; masaüstü / telefon genişliği seçilir. "Gönder" yalnız çağıran izin verdiyse
 * (`onSend`): pencereyi kapatır ve mevcut gönderim akışını (onay sorusuyla) başlatır.
 */
/** Bayt → MB (bir ondalık, arayüz dilinin ayracı). */
export function fmtMb(bytes, lang) {
  const n = Number(bytes)
  if (!Number.isFinite(n) || n < 0) return '—'
  return new Intl.NumberFormat(lang === 'en' ? 'en-GB' : 'tr-TR', { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(n / 1048576)
}

/**
 * PDF posta sınırını aşıyor mu (inceleme 2026-10-17): sunucu ayrı `pdf_too_large` bayrağını yollar — `problem` alıcı sorununu
 * önde söyler, PDF'i gizleyebilir. Eski sunucu (bayrak yok): `problem` ya da PDF boyutu > sınır; boyut ya da sınır
 * bilinmiyorsa yalnız sunucunun `problem`'ine güvenilir.
 */
export function pdfTooLargeOf(d) {
  if (!d) return false
  if (typeof d.pdf_too_large === 'boolean') return d.pdf_too_large
  if (d.problem === 'PDF_TOO_LARGE') return true
  const bytes = Number(d.pdf_bytes)
  const max = Number(d.max_mail_mb)
  return d.pdf_bytes != null && d.max_mail_mb != null && Number.isFinite(bytes) && Number.isFinite(max) && bytes > max * 1048576
}

/** Onay durumu → i18n anahtarı (bilinmeyen durum "henüz onaya gönderilmedi" sayılır — ham anahtar asla görünmez). */
const APPROVAL_TEXT_KEYS = Object.freeze({ pending: 'te.mail.appr.pending', none: 'te.mail.appr.none', rejected: 'te.mail.appr.rejected', stale: 'te.mail.appr.stale' })

export default function MailPreviewDialog({ reportId, open, onClose, onSend, sendLabel, uiLang }) {
  const t = useT()
  const toast = useToast()
  const [state, setState] = useState({ loading: true, failed: false, error: null, data: null })
  const [width, setWidth] = useState('desktop')
  const [testing, setTesting] = useState(false)
  const seq = useRef(0)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])

  // "Bana test gönder" (R13): önizlenen posta YALNIZ bana — sunucu gönderimle aynı gövdeyi üretir; rapor "gönderildi" olmaz.
  // Pencere kapanırsa cevap yine toast olarak gelir (toast penceresiz yaşar); durum yalnız pencere açıkken yazılır.
  const sendTest = useCallback(async () => {
    setTesting(true)
    try {
      const res = await api.techErrors.mailPreviewTest(reportId)
      if (res?.success && res.data) {
        if (res.data.status === 'QUEUED') toast.info(t('te.mail.testQueued', res.data.sent_to || '—'))
        else toast.success(t('te.mail.testDone', res.data.sent_to || '—'))
      } else {
        toast.error(res?.error || t('te.mail.testNotSent'), { details: res?.errorInfo })
      }
    } catch (e) {
      toast.error(e?.message || t('te.mail.testNotSent'))
    } finally {
      if (alive.current) setTesting(false)
    }
  }, [reportId, toast, t])

  // Bir açılışta bir okuma (sunucu PDF'i de üretir — dil değişimi yeniden üretmez); geç cevap düşer (sıra)
  const load = useCallback(() => {
    const my = ++seq.current
    setState({ loading: true, failed: false, error: null, data: null })
    api.techErrors.mailPreview(reportId).then((res) => {
      if (my !== seq.current) return
      if (res?.success && res.data) setState({ loading: false, failed: false, error: null, data: res.data })
      else setState({ loading: false, failed: true, error: res?.error || null, data: null })
    }).catch((e) => {
      if (my !== seq.current) return
      setState({ loading: false, failed: true, error: e?.message || null, data: null })
    })
  }, [reportId])

  useEffect(() => {
    if (!open) return undefined
    load()
    return () => { seq.current++ }
  }, [open, load])

  const d = state.data
  // Alıcı sorunu ile PDF sınırı ayrı şeritler (ikisi birden olabilir); test düğmesi yalnız PDF sınırına bakar
  const pdfTooLarge = pdfTooLargeOf(d)
  const recipientProblem = d?.problem && d.problem !== 'PDF_TOO_LARGE' ? d.problem : null
  const recipients =(list, count) => (d?.recipients_hidden ? t('te.mail.hiddenCount', count) : (list || []).join(', ') || '—')
  // Kimlik (10. sürüm): oluşturan / son düzenleyen / onaylayan — gönderilen postanın bandında da aynı satırlar yazar
  const pv = d?.provenance || null
  const who = (name, at) => [name || '—', at ? fmtDateTime(at, uiLang) : null].filter(Boolean).join(' · ')
  const updatedShown = pv && pv.updated_by && (pv.updated_by !== pv.created_by || (pv.updated_at && pv.updated_at !== pv.created_at))

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogContent className="flex max-h-[94dvh] w-[calc(100vw-1rem)] max-w-[1120px]! flex-col gap-3 overflow-hidden p-4 sm:p-6" data-slot="te-mail-preview">
        <DialogHeader className="shrink-0 text-left">
          <DialogTitle className="flex items-center gap-2"><Mail aria-hidden="true" className="size-5 text-muted-foreground" /> {t('te.mail.previewTitle')}</DialogTitle>
          <DialogDescription>{t('te.mail.previewHint')}</DialogDescription>
        </DialogHeader>

        {/* Gövde tek kaydırma alanı (inceleme 2026-10-18): 20 Kime + 20 Bilgi + üç şerit 390 px telefonda üst bloğu büyütüp
            alt düğmeleri pencerenin dışına itiyordu (overflow-hidden, shrink-0). Başlık ve alt çubuk sabit, arası kayar;
            önizleme çerçevesi sabit yükseklikte (kendi içinde kayar). */}
        <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto overscroll-contain" data-slot="te-mail-body">
          {state.loading && (
            <div role="status" aria-live="polite" className="flex flex-col gap-2">
              <span className="sr-only">{t('te.loading')}</span>
              <Skeleton className="h-5 w-2/3 motion-reduce:animate-none" />
              <Skeleton className="h-5 w-1/2 motion-reduce:animate-none" />
              <Skeleton className="h-64 w-full motion-reduce:animate-none" />
            </div>
          )}
          {!state.loading && state.failed && (
            <StatusBlock tone="danger" icon={CircleAlert} title={t('te.mail.unavailableTitle')} description={state.error || t('te.mail.loadError')}
              actions={<Button type="button" variant="outline" className="pointer-coarse:min-h-10" onClick={load}>{t('te.retry')}</Button>} />
          )}
          {!state.loading && d && (
            <>
              <dl className="grid shrink-0 grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm" data-slot="te-mail-meta">
                <dt className="text-muted-foreground">{t('te.mail.subject')}</dt>
                <dd className="m-0 font-medium [overflow-wrap:anywhere]" data-slot="te-mail-subject">{d.subject || '—'}</dd>
                <dt className="text-muted-foreground">{t('te.mail.to')}</dt>
                <dd className="m-0 [overflow-wrap:anywhere]" data-slot="te-mail-to">
                  {recipients(d.to, d.to_count)}
                  <Badge variant="secondary" className="ml-2 rounded-md overflow-visible align-middle" data-slot="te-mail-delivery">
                    {d.delivery === 'EXECUTIVE' ? t('te.mail.deliveryExecutive') : t('te.mail.deliveryTeam')}
                  </Badge>
                </dd>
                {(d.cc_count > 0) && (
                  <>
                    <dt className="text-muted-foreground">{t('te.mail.cc')}</dt>
                    <dd className="m-0 [overflow-wrap:anywhere]" data-slot="te-mail-cc">{recipients(d.cc, d.cc_count)}</dd>
                  </>
                )}
                <dt className="text-muted-foreground">{t('te.mail.attachment')}</dt>
                <dd className="m-0 flex flex-wrap items-center gap-x-2 [overflow-wrap:anywhere]">
                  <span className="inline-flex items-center gap-1"><Paperclip aria-hidden="true" className="size-3.5" /> {d.file_name || '—'}</span>
                  {d.pdf_bytes != null && <span className="text-muted-foreground" data-slot="te-mail-pdf-size">{t('te.mail.attachmentSize', fmtMb(d.pdf_bytes, uiLang))}</span>}
                  <span className="text-muted-foreground">{t('te.mail.images', d.images_embedded ?? 0, d.images_skipped ?? 0)}</span>
                </dd>
                {pv && (
                  <>
                    <dt className="text-muted-foreground">{t('te.mail.createdBy')}</dt>
                    <dd className="m-0 [overflow-wrap:anywhere]" data-slot="te-mail-created">{who(pv.created_by, pv.created_at)}</dd>
                    {updatedShown && (
                      <>
                        <dt className="text-muted-foreground">{t('te.mail.updatedBy')}</dt>
                        <dd className="m-0 [overflow-wrap:anywhere]" data-slot="te-mail-updated">{who(pv.updated_by, pv.updated_at)}</dd>
                      </>
                    )}
                    {pv.approval_state && (pv.approval_state === 'approved' ? (
                      <>
                        <dt className="text-muted-foreground">{t('te.mail.approvedBy')}</dt>
                        <dd className="m-0 [overflow-wrap:anywhere]" data-slot="te-mail-approved">{who(pv.approved_by, pv.approved_at)}</dd>
                      </>
                    ) : (
                      <>
                        <dt className="text-muted-foreground">{t('te.mail.approval')}</dt>
                        <dd className="m-0 [overflow-wrap:anywhere]" data-slot="te-mail-approval" data-state={pv.approval_state}>
                          {t(APPROVAL_TEXT_KEYS[pv.approval_state] || APPROVAL_TEXT_KEYS.none)}
                        </dd>
                      </>
                    ))}
                  </>
                )}
              </dl>
              {/* A3 (2026-10-17): takım adresinde 20'den fazla alıcı — fazlası sessizce düşüyordu; posta yine 20 adrese gider, pencere söyler */}
              {d.to_dropped > 0 && (
                <div data-slot="te-mail-dropped" data-count={d.to_dropped}>
                  <AlertBanner tone="warning" title={t('te.mail.droppedTitle', d.to_dropped)}>
                    {t('te.mail.dropped', d.to_dropped)}
                  </AlertBanner>
                </div>
              )}
              {/* Yöneticilere gönderimde Bilgi (takım adresi) de en çok 20 — fazlası sessizce düşüyordu (inceleme 2026-10-17) */}
              {d.cc_dropped > 0 && (
                <div data-slot="te-mail-cc-dropped" data-count={d.cc_dropped}>
                  <AlertBanner tone="warning" title={t('te.mail.ccDroppedTitle', d.cc_dropped)}>
                    {t('te.mail.ccDropped', d.cc_dropped)}
                  </AlertBanner>
                </div>
              )}
              {recipientProblem && (
                <div data-slot="te-mail-problem" data-problem={recipientProblem}>
                  <AlertBanner tone="warning" title={t('te.mail.noRecipientsTitle')}>
                    {recipientProblem === 'EXEC_RECIPIENTS_MISSING' ? t('te.mail.noExecRecipients') : t('te.mail.noTeamEmail')}
                  </AlertBanner>
                </div>
              )}
              {pdfTooLarge && (
                <div data-slot="te-mail-pdf-too-large">
                  <AlertBanner tone="warning" title={t('te.mail.pdfTooLargeTitle')}>
                    {t('te.mail.pdfTooLarge', fmtMb(d.pdf_bytes, uiLang), d.max_mail_mb ?? '—')}
                  </AlertBanner>
                </div>
              )}
              <div className="flex shrink-0 flex-wrap items-center justify-between gap-2">
                <SegmentedControl value={width} onChange={setWidth} ariaLabel={t('te.mail.width')}
                  options={[{ value: 'desktop', label: t('te.mail.widthDesktop'), icon: Monitor }, { value: 'phone', label: t('te.mail.widthPhone'), icon: Smartphone }]} />
                <span className="text-xs text-muted-foreground">{t('te.mail.sandboxNote')}</span>
              </div>
              <div className="shrink-0 overflow-x-auto rounded-xl border bg-muted/40 p-2 sm:p-4" data-slot="te-mail-frame-box">
                {/* Proje kuralı (mail-preview-guard): her önizleme HTML'i mailPreviewSrcDoc'tan geçer — <base target="_blank"> +
                    marka logosu cid: çözümü (sunucu da data: URI'ye çevirir; burada yalnız güvence) */}
                <iframe title={t('te.mail.previewTitle')} sandbox="" srcDoc={mailPreviewSrcDoc(d.html || '')} data-slot="te-mail-frame" data-width={width}
                  className={cn('mx-auto block h-[60dvh] min-h-[320px] rounded-md border bg-white', width === 'phone' ? 'w-[400px] max-w-full' : 'w-full')}
                  style={width === 'desktop' ? { maxWidth: `${PREVIEW_WIDTHS.desktop}px` } : undefined} />
              </div>
            </>
          )}
        </div>

        <DialogFooter className="shrink-0 flex-col-reverse gap-2 sm:flex-row">
          <Button type="button" variant="outline" className="pointer-coarse:min-h-10" onClick={onClose}>{t('te.close')}</Button>
          {/* Yalnız kendine test (R13): her okuyana (paylaşımla okuyan da — posta yalnız kendine); PDF sınırı aşıyorsa
              gönderim gibi test de gidemez → düğme yok (ayrı bayrak: alıcı sorunu da varken `problem` PDF'i söylemez).
              Alıcı eksikliği (takım / yönetici) testi engellemez. */}
          {d && !pdfTooLarge && (
            <Button type="button" variant="secondary" className="pointer-coarse:min-h-10" data-slot="te-mail-test" disabled={testing}
              aria-busy={testing || undefined} onClick={sendTest}>
              {testing ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : <MailCheck aria-hidden="true" />}
              {testing ? t('te.mail.testSending') : t('te.mail.test')}
            </Button>
          )}
          {onSend && d && !d.problem && !pdfTooLarge && (
            <Button type="button" className="pointer-coarse:min-h-10" data-slot="te-mail-send" disabled={testing} onClick={() => { onClose(); onSend() }}>
              <Send aria-hidden="true" /> {sendLabel || t('te.send')}
            </Button>
          )}
          {state.loading && <Button type="button" disabled className="pointer-coarse:min-h-10"><RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> {t('te.loading')}</Button>}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
