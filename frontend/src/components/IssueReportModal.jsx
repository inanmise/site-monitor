import { useEffect, useRef, useState } from 'react'
import { currentVersion } from '../utils/appVersion.js'
import { Bug, Camera, CheckCircle2, Send, X, ZoomIn } from 'lucide-react'
import { api, getRecentFailures } from '../api/client'
import { useT, useLanguage } from '../i18n/index.jsx'
import { downscaleImage } from '../utils/imageDownscale'
import ModalShell from './ui/ModalShell.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import Field from './ui/Field.jsx'
import SegmentedControl from './ui/SegmentedControl.jsx'
import { Spinner } from './ui/Progress.jsx'

/**
 * Oturum içi "Sorun Bildir" modalı — iki giriş noktasından açılır:
 * (a) ErrorBoundary fallback'i (errorText + linkedReference dolu gelir — otomatik kayda bağlam ekler),
 * (b) Nav'daki kalıcı "Sorun Bildir" (çökme olmayan sorunlar: yanlış veri, yavaşlık, görsel bozukluk).
 *
 * Kural 1 — sistem bildiğini SORMAZ: kim/ne zaman/nerede/nasıl otomatik toplanır ve üstte READONLY
 * "otomatik eklenecekler" özeti olarak gösterilir (şeffaflık). Kullanıcıya yalnız bilinemeyecekler
 * sorulur: açıklama (zorunlu), önem (opsiyonel), ekran görüntüsü (opsiyonel) ve profilde yoksa e-posta.
 *
 * Kural 2 — hiçbir şey SESSİZCE düşmez: sınırı aşan, desteklenmeyen ya da okunamayan dosyalar
 * sayılıp kullanıcıya söylenir. Eskiden fazlalıklar sessizce kırpılıyor, bozuk dosyalar boş
 * catch'e düşüyordu; kullanıcı eklediğini sandığı görselin gitmediğini asla öğrenmiyordu.
 */
const MAX_IMAGES = 5
const ACCEPTED = ['image/png', 'image/jpeg']
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

function browserLabel(ua) {
  if (!ua) return '—'
  if (/edg\//i.test(ua)) return 'Edge'
  if (/chrome\//i.test(ua)) return 'Chrome'
  if (/firefox\//i.test(ua)) return 'Firefox'
  if (/safari\//i.test(ua)) return 'Safari'
  return ua.slice(0, 40)
}

function fileToDataUrl(file) {
  return new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => resolve(r.result)
    r.onerror = reject
    r.readAsDataURL(file)
  })
}

export default function IssueReportModal({ open, onClose, errorText = '', linkedReference = '' }) {
  const t = useT()
  const { lang } = useLanguage()
  const [me, setMe] = useState(null)
  const [message, setMessage] = useState('')
  const [category, setCategory] = useState('')
  const [email, setEmail] = useState('')
  const [saveEmail, setSaveEmail] = useState(true)
  const [images, setImages] = useState([])          // data-URL listesi
  const [imageNotice, setImageNotice] = useState('')
  const [zoom, setZoom] = useState(null)            // büyütülen görselin indeksi
  const [dragging, setDragging] = useState(false)
  const [sending, setSending] = useState(false)
  const [errors, setErrors] = useState({})          // alan-bazlı: { message, email }
  const [formError, setFormError] = useState('')    // gönderim/sunucu hatası
  const [reference, setReference] = useState('')
  const fileRef = useRef(null)

  // Modal açılınca profili taze çek (e-posta kuralı: profilde varsa READONLY gösterilir, sorulmaz).
  useEffect(() => {
    if (!open) return
    setMessage(''); setCategory(''); setEmail(''); setImages([]); setImageNotice('')
    setZoom(null); setDragging(false); setErrors({}); setFormError(''); setReference('')
    api.getMe().then((res) => { if (res?.success) setMe(res) }).catch(() => {})
  }, [open])

  if (!open) return null

  const profileEmail = me?.email && String(me.email).trim() ? String(me.email).trim() : null
  const now = new Date()
  const url = window.location.href
  const tabKey = new URLSearchParams(window.location.search).get('tab') || 'dashboard'
  const theme = document.documentElement.getAttribute('data-theme') || 'light'
  const screenSize = `${window.screen?.width || 0}x${window.screen?.height || 0}`
  const failed = getRecentFailures()

  async function addFiles(fileList) {
    const incoming = Array.from(fileList || [])
    if (!incoming.length) return
    const notices = []

    // downscaleImage görsel OLMAYAN dosyayı olduğu gibi geri döndürüyor; giriş filtresi
    // olmasa sürüklenen bir PDF sessizce data-URL olarak yüklenirdi.
    const supported = incoming.filter((f) => ACCEPTED.includes(f.type))
    const unsupported = incoming.length - supported.length
    if (unsupported > 0) notices.push(t('issue.imgUnsupported', unsupported))

    const room = Math.max(0, MAX_IMAGES - images.length)
    const take = supported.slice(0, room)
    if (supported.length > take.length) notices.push(t('issue.imgTooMany', MAX_IMAGES))

    const urls = []
    let unreadable = 0
    for (const f of take) {
      try {
        const small = await downscaleImage(f)
        urls.push(await fileToDataUrl(small))
      } catch { unreadable += 1 }
    }
    if (unreadable > 0) notices.push(t('issue.imgFailed', unreadable))

    if (urls.length) setImages((prev) => [...prev, ...urls].slice(0, MAX_IMAGES))
    setImageNotice(notices.join(' '))
  }

  function removeImage(index) {
    setImages((prev) => prev.filter((_, i) => i !== index))
    setImageNotice('')
  }

  function onDrop(e) {
    e.preventDefault()
    setDragging(false)
    addFiles(e.dataTransfer?.files)
  }

  function validate() {
    const next = {}
    if (!message.trim()) next.message = t('issue.msgRequired')
    if (!profileEmail) {
      if (!email.trim()) next.email = t('issue.emailRequired')
      else if (!EMAIL_RE.test(email.trim())) next.email = t('issue.emailInvalid')
    }
    setErrors(next)
    return Object.keys(next).length === 0
  }

  async function submit() {
    setFormError('')
    if (!validate()) return
    setSending(true)
    try {
      const res = await api.sendIssueReport({
        message: message.trim(),
        category: category || undefined,
        email: profileEmail ? undefined : email.trim(),
        saveEmailToProfile: profileEmail ? undefined : saveEmail,
        errorText: errorText || undefined,
        linkedReference: linkedReference || undefined,
        url,
        tabKey,
        appVersion: currentVersion(),
        screenSize,
        theme,
        lang,
        failedRequests: failed,
        images: images.length ? images : undefined,
      })
      if (res?.success) {
        setReference(res.reference || '')
      } else {
        setFormError(res?.error || t('issue.sendFail'))
      }
    } catch {
      setFormError(t('issue.sendFail'))
    } finally {
      setSending(false)
    }
  }

  const autoRows = [
    [t('issue.autoWho'),     me?.username || '—'],
    [t('issue.autoWhen'),    now.toLocaleString(lang === 'tr' ? 'tr-TR' : 'en-GB')],
    [t('issue.autoWhere'),   `${tabKey} · ${url.length > 60 ? url.slice(0, 60) + '…' : url}`],
    [t('issue.autoVersion'), currentVersion() ? `v${currentVersion()}` : '—'],
    [t('issue.autoBrowser'), browserLabel(navigator.userAgent)],
    [t('issue.autoScreen'),  screenSize],
    [t('issue.autoTheme'),   `${theme} · ${lang.toUpperCase()}`],
  ]

  const footer = reference
    ? <button type="button" className="btn btn-primary" onClick={onClose}>{t('issue.close')}</button>
    : (
      <>
        <button type="button" className="btn" onClick={onClose} disabled={sending}>{t('issue.cancel')}</button>
        <button type="button" className="btn btn-primary" onClick={submit} disabled={sending} aria-busy={sending}>
          {sending ? <Spinner size={15} inline decorative /> : <Send size={15} />}
          {sending ? t('issue.sending') : t('issue.submit')}
        </button>
      </>
    )

  return (
    <ModalShell
      open={open}
      onClose={onClose}
      busy={sending}
      title={t('issue.title')}
      icon={Bug}
      closeLabel={t('issue.close')}
      size="md"
      footer={footer}
    >
      {/* Dropzone dışına bırakılan dosya tarayıcıyı o dosyaya yönlendirir ve form kaybolur;
          modal gövdesi genelinde yutuluyor. */}
      <div
        className="issue-root"
        onDragOver={(e) => e.preventDefault()}
        onDrop={(e) => e.preventDefault()}
      >
        {reference ? (
          <div className="issue-done" role="status">
            <CheckCircle2 size={36} className="issue-done-icon" aria-hidden="true" />
            <div className="issue-done-title">{t('issue.thanks')}</div>
            <div className="issue-done-ref">{t('issue.refLabel')}: <strong>{reference}</strong></div>
          </div>
        ) : (
          <>
            {/* Otomatik eklenecekler — READONLY şeffaflık özeti (kural 1: bunlar kullanıcıya SORULMAZ). */}
            <div className="issue-auto">
              <div className="issue-auto-hdr">{t('issue.autoTitle')}</div>
              <table className="issue-auto-table">
                <tbody>
                  {autoRows.map(([k, v]) => (
                    <tr key={k}>
                      <th scope="row" className="issue-auto-k">{k}</th>
                      <td className="issue-auto-v">{v}</td>
                    </tr>
                  ))}
                  {errorText && (
                    <tr>
                      <th scope="row" className="issue-auto-k">{t('issue.autoError')}</th>
                      <td className="issue-auto-v issue-auto-mono">{errorText.split('\n')[0].slice(0, 120)}</td>
                    </tr>
                  )}
                  {failed.length > 0 && (
                    <tr>
                      <th scope="row" className="issue-auto-k">{t('issue.autoFailedReqs')}</th>
                      <td className="issue-auto-v issue-auto-mono">
                        {failed.map((f, i) => <div key={i}>{f.path} → {f.status || t('issue.netError')}</div>)}
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>

            {/* Kullanıcıya sorulanlar — yalnız sistemin bilemeyecekleri. */}
            <Field label={t('issue.describe')} required error={errors.message}>
              {({ id, describedBy, invalid }) => (
                <textarea
                  id={id} aria-describedby={describedBy} aria-invalid={invalid}
                  className="input issue-textarea" rows={4}
                  value={message} placeholder={t('issue.describePh')}
                  onChange={(e) => setMessage(e.target.value)}
                />
              )}
            </Field>

            <div className="form-field">
              <span className="form-field-label">{t('issue.category')}</span>
              {/* SegmentedControl seçimi kaldırmaz; eski "tekrar tıkla → boşalt" davranışının
                  yerine açık bir "Belirtmedim" seçeneği var. */}
              <SegmentedControl
                value={category}
                onChange={setCategory}
                ariaLabel={t('issue.category')}
                options={[
                  { value: '',           label: t('issue.catNone') },
                  { value: 'BLOCKER',    label: t('issue.catBlocker') },
                  { value: 'ANNOYANCE',  label: t('issue.catAnnoyance') },
                  { value: 'SUGGESTION', label: t('issue.catSuggestion') },
                ]}
              />
            </div>

            {profileEmail ? (
              <div className="issue-email-known">
                {t('issue.emailKnown')}: <strong>{profileEmail}</strong>
              </div>
            ) : (
              <>
                <Field label={t('issue.email')} required error={errors.email}>
                  {({ id, describedBy, invalid }) => (
                    <input
                      id={id} aria-describedby={describedBy} aria-invalid={invalid}
                      type="email" className="input"
                      value={email} placeholder={t('issue.emailPh')}
                      onChange={(e) => setEmail(e.target.value)}
                    />
                  )}
                </Field>
                <label className="issue-checkbox">
                  <input type="checkbox" checked={saveEmail} onChange={(e) => setSaveEmail(e.target.checked)} />
                  {t('issue.emailSave')}
                </label>
              </>
            )}

            {/* Ekran görüntüleri: tıklayarak veya sürükleyip bırakarak. */}
            <div
              className={`issue-dropzone${dragging ? ' is-dragging' : ''}`}
              onDragEnter={(e) => { e.preventDefault(); setDragging(true) }}
              onDragOver={(e) => { e.preventDefault(); if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy' }}
              onDragLeave={(e) => { if (e.currentTarget === e.target) setDragging(false) }}
              onDrop={onDrop}
            >
              <div className="issue-dropzone-row">
                <button type="button" className="btn btn-sm" onClick={() => fileRef.current?.click()}
                        disabled={images.length >= MAX_IMAGES}>
                  <Camera size={15} style={{ marginRight: 6 }} />
                  {t('issue.addImage')} ({images.length}/{MAX_IMAGES})
                </button>
                <span className="hint">{t('issue.dropHint')}</span>
              </div>
              <input ref={fileRef} type="file" accept="image/png,image/jpeg" multiple hidden
                     onChange={(e) => { addFiles(e.target.files); e.target.value = '' }} />
              {images.length > 0 && (
                <div className="issue-thumbs">
                  {images.map((img, i) => (
                    <div className="issue-thumb" key={i}>
                      <button type="button" className="issue-thumb-open" onClick={() => setZoom(i)}
                              aria-label={t('issue.imgZoom', i + 1)}>
                        <img src={img} alt={`${t('issue.screenshot')} ${i + 1}`} />
                        <ZoomIn size={13} className="issue-thumb-zoom" aria-hidden="true" />
                      </button>
                      <button type="button" className="issue-thumb-del" onClick={() => removeImage(i)}
                              aria-label={t('issue.imgRemove', i + 1)}>
                        <X size={12} />
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {imageNotice && (
              <AlertBanner tone="warning" onDismiss={() => setImageNotice('')} dismissLabel={t('issue.close')}>
                {imageNotice}
              </AlertBanner>
            )}

            {formError && <AlertBanner tone="danger" role="alert">{formError}</AlertBanner>}
          </>
        )}

        {/* Görsel büyütme — iç içe kabuk: Escape ve odak iadesi kabuktan gelir. */}
        {zoom !== null && images[zoom] && (
          <ModalShell
            open
            onClose={() => setZoom(null)}
            title={`${t('issue.screenshot')} ${zoom + 1}`}
            closeLabel={t('issue.close')}
            size="full"
          >
            <img className="issue-lightbox-img" src={images[zoom]}
                 alt={`${t('issue.screenshot')} ${zoom + 1}`} />
          </ModalShell>
        )}
      </div>
    </ModalShell>
  )
}
