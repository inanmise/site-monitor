import { useEffect, useState } from 'react'
import { LifeBuoy, Send, RotateCcw } from 'lucide-react'
import { api } from '../../../api/client'
import { useT, useLanguage } from '../../../i18n/index.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import Field from '../../ui/Field.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { ReportSection, TechnicalDetails, PrivacyNote, ReportSuccess, CharCounter, PHONE_FULLSCREEN } from './ReportParts.jsx'
import ScreenshotField from './ScreenshotField.jsx'
import { MAX_MESSAGE, MAX_ERROR_TEXT, MAX_IMAGES, EMAIL_RE, browserLabel, processImageFiles, imagesFromClipboard, loginHelpReason, loginHelpDetail } from './reportModel.js'

/**
 * Giriş sayfası "Giriş sorunu bildir" penceresi (2026-09-27; eskiden Login.jsx içindeki satır içi Dialog) — oturumsuz
 * LOGIN kaynağı: `POST /api/login-help` (IP oran sınırlı; sunucu IP + tarayıcı + zamanı kendisi kaydeder).
 *
 * <p>Oturum içi pencereyle AYNI sakin düzen: 1 Kim? (kullanıcı adı + e-posta, zorunlu) → 2 Ne oldu? (gördüğünüz
 * hata + açıklama, canlı sayaç) → 3 Ekran görüntüleri (sürükle-bırak / seç / Ctrl+V). Doğrulama satır içi, gönderimde.
 * Hata: NET sebep (ağ / oran / kapalı / sunucu / doğrulama) + "Ayrıntıyı göster" (HTTP kodu) + "Tekrar dene" — yazılanlar
 * korunur. Başarı: referans (kopyala) + sırada ne var. Telefonda tam ekran, altlık sabit.
 * Gönderim gövdesi DEĞİŞMEDİ: { username, email, errorText, message, images }.
 */
export default function LoginHelpDialog({ open, onClose, initialUsername = '' }) {
  const t = useT()
  const { lang } = useLanguage()
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [errorText, setErrorText] = useState('')
  const [message, setMessage] = useState('')
  const [images, setImages] = useState([])
  const [imageNotice, setImageNotice] = useState('')
  const [errors, setErrors] = useState({})
  const [sending, setSending] = useState(false)
  const [failure, setFailure] = useState(null)       // { reason, detail }
  const [showDetail, setShowDetail] = useState(false)
  const [reference, setReference] = useState(null)   // null = gönderilmedi; '' = referanssız başarı

  useEffect(() => {
    if (!open) return
    setUsername(initialUsername || ''); setEmail(''); setErrorText(''); setMessage(''); setImages([]); setImageNotice('')
    setErrors({}); setFailure(null); setShowDetail(false); setReference(null)
  }, [open, initialUsername])

  if (!open) return null
  const sent = reference !== null

  async function addFiles(fileList) {
    const { urls, notices } = await processImageFiles(fileList, images.length, t)
    if (urls.length) setImages((prev) => [...prev, ...urls].slice(0, MAX_IMAGES))
    setImageNotice(notices.join(' '))
  }
  function onPaste(e) {
    const files = imagesFromClipboard(e)
    if (!files.length) return
    e.preventDefault()
    addFiles(files)
  }

  function validate() {
    const next = {}
    if (!username.trim()) next.username = t('login.helpUsernameReq')
    if (!email.trim()) next.email = t('login.helpEmailReq')
    else if (!EMAIL_RE.test(email.trim())) next.email = t('login.helpEmailInvalid')
    if (!message.trim()) next.message = t('issue.msgRequired')
    setErrors(next)
    const first = ['username', 'email', 'message'].find((k) => next[k])
    if (first) setTimeout(() => document.querySelector(`[data-lhd="${first}"]`)?.focus(), 0)
    return !first
  }

  async function submit() {
    if (sending) return
    setFailure(null); setShowDetail(false)
    if (!validate()) return
    setSending(true)
    try {
      const r = await api.sendLoginHelp({
        username: username.trim(),
        email: email.trim(),
        errorText: errorText.trim(),
        message: message.trim(),
        images,
      })
      if (r?.success) setReference(r.reference || '')
      else setFailure({ reason: loginHelpReason(r, t), detail: loginHelpDetail(r) })
    } catch (e) {
      setFailure({ reason: loginHelpReason({ networkError: true }, t), detail: String(e?.message || e) })
    } finally {
      setSending(false)
    }
  }

  const techRows = [
    [t('issue.autoWhen'), new Date().toLocaleString(lang === 'tr' ? 'tr-TR' : 'en-GB')],
    [t('issue.autoBrowser'), browserLabel(navigator.userAgent)],
    [t('irf.techIp'), t('irf.techIpValue')],
  ]
  const dirty = !!(message.trim() || errorText.trim() || images.length)

  const footer = sent
    ? <Button type="button" onClick={onClose}>{t('login.helpClose')}</Button>
    : (
      <div className="flex w-full flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button type="button" variant="outline" onClick={onClose} disabled={sending}>{t('issue.cancel')}</Button>
        <Button type="button" onClick={submit} disabled={sending} aria-busy={sending || undefined}>
          {sending ? <Spinner size={15} inline decorative /> : <Send aria-hidden="true" />}
          {sending ? t('login.helpSending') : t('login.helpSend')}
        </Button>
      </div>
    )

  return (
    <ModalShell open={open} onClose={onClose} busy={sending} title={t('login.helpTitle')} icon={LifeBuoy}
      closeLabel={t('login.helpClose')} size="md" scrollBody footer={footer} className={PHONE_FULLSCREEN}
      dismissOnBackdrop={!dirty || sent}>
      <div data-slot="login-help" className="flex min-w-0 flex-col gap-6 pb-1"
        onDragOver={(e) => e.preventDefault()} onDrop={(e) => e.preventDefault()} onPaste={sent ? undefined : onPaste}>
        {sent ? (
          <ReportSuccess reference={reference} steps={[t('irf.nextNotified'), t('irf.nextEmail', email.trim()), t('irf.nextLogin')]} />
        ) : (
          <>
            <p className="m-0 text-sm text-muted-foreground">{t('irf.loginIntro')}</p>

            <ReportSection n={1} title={t('irf.secWho')}>
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <Field label={t('login.helpUsername')} required error={errors.username} className="mb-0">
                  {({ id, describedBy, invalid }) => (
                    <Input id={id} data-lhd="username" aria-describedby={describedBy} aria-invalid={invalid} autoComplete="username"
                      maxLength={100} value={username} onChange={(e) => setUsername(e.target.value)} />
                  )}
                </Field>
                <Field label={t('login.helpEmail')} required error={errors.email} className="mb-0">
                  {({ id, describedBy, invalid }) => (
                    <Input id={id} data-lhd="email" aria-describedby={describedBy} aria-invalid={invalid} type="email" autoComplete="email"
                      maxLength={255} value={email} placeholder={t('login.helpEmailPlaceholder')} onChange={(e) => setEmail(e.target.value)} />
                  )}
                </Field>
              </div>
            </ReportSection>

            <ReportSection n={2} title={t('irf.secWhat')}>
              <Field label={t('login.helpErrorText')} className="mb-0">
                {({ id, describedBy }) => (
                  <Textarea id={id} aria-describedby={describedBy} rows={2} maxLength={MAX_ERROR_TEXT}
                    className="min-h-14 resize-y font-mono text-sm md:text-xs" value={errorText}
                    placeholder={t('login.helpErrorTextPlaceholder')} onChange={(e) => setErrorText(e.target.value)} />
                )}
              </Field>
              <Field label={t('login.helpDesc')} required error={errors.message} className="mb-0">
                {({ id, describedBy, invalid }) => (
                  <>
                    <Textarea id={id} data-lhd="message" aria-describedby={describedBy} aria-invalid={invalid} rows={5} maxLength={MAX_MESSAGE}
                      className="max-h-72 min-h-28 resize-y" value={message} placeholder={t('login.helpMsgPlaceholder')}
                      onChange={(e) => setMessage(e.target.value)} />
                    <div className="mt-1 flex justify-end"><CharCounter value={message} max={MAX_MESSAGE} /></div>
                  </>
                )}
              </Field>
            </ReportSection>

            <ReportSection n={3} title={t('irf.secShots')} hint={t('irf.shotsHint')} optional>
              <ScreenshotField images={images} onFiles={addFiles} disabled={sending}
                onRemove={(i) => { setImages((prev) => prev.filter((_, k) => k !== i)); setImageNotice('') }}
                notice={imageNotice} onDismissNotice={() => setImageNotice('')} />
            </ReportSection>

            <div className="flex flex-col gap-3">
              <TechnicalDetails rows={techRows} />
              <PrivacyNote>{t('irf.privacyLogin')}</PrivacyNote>
            </div>

            {failure && (
              // Düğmeler metnin ALTINDA (sarar): AlertBanner'ın yan `actions` yuvası telefonda metni ~70 px sütuna sıkıştırıyordu.
              <AlertBanner tone="danger" role="alert" title={t('irf.failTitle')} className="mb-0">
                <div>{failure.reason} {t('irf.failKept')}</div>
                {showDetail && failure.detail && <div className="mt-1.5 font-mono text-xs [overflow-wrap:anywhere] opacity-85">{failure.detail}</div>}
                <div className="mt-2 flex flex-wrap gap-2">
                  <Button type="button" size="sm" variant="outline" onClick={submit} disabled={sending}><RotateCcw aria-hidden="true" />{t('irf.retry')}</Button>
                  {failure.detail && (
                    <Button type="button" size="sm" variant="ghost" onClick={() => setShowDetail((v) => !v)} aria-expanded={showDetail}>
                      {showDetail ? t('login.helpErrDetailHide') : t('login.helpErrDetailShow')}
                    </Button>
                  )}
                </div>
              </AlertBanner>
            )}
          </>
        )}
      </div>
    </ModalShell>
  )
}
