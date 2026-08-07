import { useEffect, useRef, useState } from 'react'
import { Bug, Camera, CheckCircle2, X } from 'lucide-react'
import { api, getRecentFailures } from '../api/client'
import { useT, useLanguage } from '../i18n/index.jsx'
import { downscaleImage } from '../utils/imageDownscale'

/**
 * Oturum içi "Sorun Bildir" modalı — iki giriş noktasından açılır:
 * (a) ErrorBoundary fallback'i (errorText + linkedReference dolu gelir — otomatik kayda bağlam ekler),
 * (b) Nav'daki kalıcı "Sorun Bildir" (çökme olmayan sorunlar: yanlış veri, yavaşlık, görsel bozukluk).
 *
 * Kural 1 — sistem bildiğini SORMAZ: kim/ne zaman/nerede/nasıl otomatik toplanır ve üstte READONLY
 * "otomatik eklenecekler" özeti olarak gösterilir (şeffaflık). Kullanıcıya yalnız bilinemeyecekler
 * sorulur: açıklama (zorunlu), önem (opsiyonel), ekran görüntüsü (opsiyonel) ve profilde yoksa e-posta.
 */
const MAX_IMAGES = 5

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
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const [reference, setReference] = useState('')
  const fileRef = useRef(null)

  // Modal açılınca profili taze çek (e-posta kuralı: profilde varsa READONLY gösterilir, sorulmaz).
  useEffect(() => {
    if (!open) return
    setMessage(''); setCategory(''); setEmail(''); setImages([]); setError(''); setReference('')
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
    setError('')
    const files = Array.from(fileList || []).slice(0, MAX_IMAGES - images.length)
    const urls = []
    for (const f of files) {
      try {
        const small = await downscaleImage(f)
        urls.push(await fileToDataUrl(small))
      } catch { /* bozuk dosya → atla */ }
    }
    setImages((prev) => [...prev, ...urls].slice(0, MAX_IMAGES))
  }

  async function submit() {
    setError('')
    if (!message.trim()) { setError(t('issue.msgRequired')); return }
    if (!profileEmail && !email.trim()) { setError(t('issue.emailRequired')); return }
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
        appVersion: typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : '',
        screenSize,
        theme,
        lang,
        failedRequests: failed,
        images: images.length ? images : undefined,
      })
      if (res?.success) {
        setReference(res.reference || '')
      } else {
        setError(res?.error || t('issue.sendFail'))
      }
    } catch {
      setError(t('issue.sendFail'))
    } finally {
      setSending(false)
    }
  }

  const autoRows = [
    [t('issue.autoWho'),     me?.username || '—'],
    [t('issue.autoWhen'),    now.toLocaleString(lang === 'tr' ? 'tr-TR' : 'en-GB')],
    [t('issue.autoWhere'),   `${tabKey} · ${url.length > 60 ? url.slice(0, 60) + '…' : url}`],
    [t('issue.autoVersion'), typeof __APP_VERSION__ !== 'undefined' ? `v${__APP_VERSION__}` : '—'],
    [t('issue.autoBrowser'), browserLabel(navigator.userAgent)],
    [t('issue.autoScreen'),  screenSize],
    [t('issue.autoTheme'),   `${theme} · ${lang.toUpperCase()}`],
  ]

  return (
    <div className="modal-overlay" role="dialog" aria-modal="true" aria-label={t('issue.title')}
         onClick={(e) => { if (e.target === e.currentTarget && !sending) onClose() }}>
      <div className="modal-content" style={{ maxWidth: 560, padding: 24 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
          <h3 style={{ margin: 0, display: 'flex', alignItems: 'center', gap: 8 }}>
            <Bug size={18} /> {t('issue.title')}
          </h3>
          <button type="button" className="btn btn-ghost" onClick={onClose} aria-label={t('issue.close')} disabled={sending}>
            <X size={18} />
          </button>
        </div>

        {reference ? (
          <div style={{ textAlign: 'center', padding: '24px 8px' }}>
            <CheckCircle2 size={36} style={{ color: 'var(--ok, #16a34a)' }} />
            <div style={{ fontSize: '1.1em', fontWeight: 600, margin: '12px 0 6px' }}>{t('issue.thanks')}</div>
            <div style={{ color: 'var(--text-muted, #555)', marginBottom: 16 }}>
              {t('issue.refLabel')}: <strong>{reference}</strong>
            </div>
            <button type="button" className="btn btn-primary" onClick={onClose}>{t('issue.close')}</button>
          </div>
        ) : (
          <>
            {/* Otomatik eklenecekler — READONLY şeffaflık özeti (kural 1: bunlar kullanıcıya SORULMAZ). */}
            <div style={{ border: '1px solid var(--border, #e5e7eb)', borderRadius: 10, padding: '10px 12px', marginBottom: 14 }}>
              <div style={{ fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.05em',
                            color: 'var(--text-muted, #6b7280)', marginBottom: 6 }}>
                {t('issue.autoTitle')}
              </div>
              <table style={{ fontSize: 12.5, lineHeight: 1.7, borderCollapse: 'collapse' }}>
                <tbody>
                  {autoRows.map(([k, v]) => (
                    <tr key={k}>
                      <td style={{ color: 'var(--text-muted, #6b7280)', paddingRight: 10, whiteSpace: 'nowrap', verticalAlign: 'top' }}>{k}</td>
                      <td style={{ wordBreak: 'break-all' }}>{v}</td>
                    </tr>
                  ))}
                  {errorText && (
                    <tr>
                      <td style={{ color: 'var(--text-muted, #6b7280)', paddingRight: 10, verticalAlign: 'top' }}>{t('issue.autoError')}</td>
                      <td style={{ fontFamily: 'monospace', fontSize: 11.5, wordBreak: 'break-all' }}>
                        {errorText.split('\n')[0].slice(0, 120)}
                      </td>
                    </tr>
                  )}
                  {failed.length > 0 && (
                    <tr>
                      <td style={{ color: 'var(--text-muted, #6b7280)', paddingRight: 10, verticalAlign: 'top' }}>{t('issue.autoFailedReqs')}</td>
                      <td style={{ fontFamily: 'monospace', fontSize: 11.5 }}>
                        {failed.map((f, i) => <div key={i}>{f.path} → {f.status || 'AĞ'}</div>)}
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>

            {/* Kullanıcıya sorulanlar — yalnız sistemin bilemeyecekleri. */}
            <label style={{ display: 'block', fontWeight: 600, marginBottom: 6 }}>
              {t('issue.describe')} <span className="req-star">*</span>
            </label>
            <textarea
              value={message} onChange={(e) => setMessage(e.target.value)}
              placeholder={t('issue.describePh')} rows={4}
              style={{ width: '100%', resize: 'vertical', marginBottom: 12 }}
            />

            <label style={{ display: 'block', fontWeight: 600, marginBottom: 6 }}>{t('issue.category')}</label>
            <div style={{ display: 'flex', gap: 8, marginBottom: 12, flexWrap: 'wrap' }}>
              {[['BLOCKER', t('issue.catBlocker')], ['ANNOYANCE', t('issue.catAnnoyance')], ['SUGGESTION', t('issue.catSuggestion')]].map(([val, label]) => (
                <button key={val} type="button"
                        className={category === val ? 'btn btn-primary' : 'btn'}
                        onClick={() => setCategory(category === val ? '' : val)}>
                  {label}
                </button>
              ))}
            </div>

            {profileEmail ? (
              <div style={{ fontSize: 13, color: 'var(--text-muted, #555)', marginBottom: 12 }}>
                {t('issue.emailKnown')}: <strong>{profileEmail}</strong>
              </div>
            ) : (
              <>
                <label style={{ display: 'block', fontWeight: 600, marginBottom: 6 }}>
                  {t('issue.email')} <span className="req-star">*</span>
                </label>
                <input type="email" value={email} onChange={(e) => setEmail(e.target.value)}
                       placeholder={t('issue.emailPh')} style={{ width: '100%', marginBottom: 8 }} />
                <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, marginBottom: 12 }}>
                  <input type="checkbox" checked={saveEmail} onChange={(e) => setSaveEmail(e.target.checked)} />
                  {t('issue.emailSave')}
                </label>
              </>
            )}

            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
              <button type="button" className="btn" onClick={() => fileRef.current?.click()}
                      disabled={images.length >= MAX_IMAGES}>
                <Camera size={15} style={{ marginRight: 6 }} />
                {t('issue.addImage')} ({images.length}/{MAX_IMAGES})
              </button>
              <input ref={fileRef} type="file" accept="image/png,image/jpeg" multiple hidden
                     onChange={(e) => { addFiles(e.target.files); e.target.value = '' }} />
              {images.map((img, i) => (
                <img key={i} src={img} alt={`${t('issue.screenshot')} ${i + 1}`}
                     style={{ height: 36, borderRadius: 6, border: '1px solid var(--border, #e5e7eb)' }} />
              ))}
            </div>

            {error && <div style={{ color: 'var(--danger, #dc2626)', fontSize: 13, marginBottom: 10 }} role="alert">{error}</div>}

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
              <button type="button" className="btn" onClick={onClose} disabled={sending}>{t('issue.cancel')}</button>
              <button type="button" className="btn btn-primary" onClick={submit} disabled={sending}>
                {sending ? t('issue.sending') : t('issue.submit')}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
