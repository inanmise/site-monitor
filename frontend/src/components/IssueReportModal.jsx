import { useEffect, useId, useState } from 'react'
import { currentVersion } from '../utils/appVersion.js'
import { Bug, Send, Mail, ArrowRight, RotateCcw, ArrowRightLeft } from 'lucide-react'
import { api, getRecentFailures } from '../api/client'
import { useT, useLanguage } from '../i18n/index.jsx'
import { navigateTo } from '../utils/navigate.js'
import ModalShell from './ui/ModalShell.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import Field from './ui/Field.jsx'
import { Spinner } from './ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { Textarea } from '@/components/shadcn/textarea'
import { Badge } from '@/components/shadcn/badge'
import TeamBadge from './ui/TeamBadge.jsx'
import { composeTransferRequest, JUSTIFICATION_MAX } from './inventory/domainConflictModel.js'
import { ReportSection, CategoryCards, ImpactPicker, TechnicalDetails, PrivacyNote, ReportSuccess, CharCounter, PHONE_FULLSCREEN } from './issues/report/ReportParts.jsx'
import ScreenshotField from './issues/report/ScreenshotField.jsx'
import { MAX_MESSAGE, EMAIL_RE, browserLabel, processImageFiles, imagesFromClipboard, reportHref, reportIdOf } from './issues/report/reportModel.js'

/**
 * Oturum içi "Sorun Bildir" penceresi (2026-09-27 yeniden tasarım) — dört giriş noktası: kullanıcı menüsü,
 * komut paleti, Sorun Bildirimleri sayfasının "Sorun bildir" düğmesi ve ErrorBoundary (errorText + linkedReference
 * dolu gelir — otomatik çökme kaydına bağlam eklenir, mükerrer kayıt açılmaz).
 *
 * <p>Sakin, bölümlü akış: 1 Ne oldu? (zorunlu, canlı sayaç) → 2 Etkisi (önem kartları, opsiyonel) → 3 Ekran
 * görüntüleri (sürükle-bırak / seç / Ctrl+V yapıştır) → 4 Size nasıl ulaşalım (profilde e-posta varsa SORULMAZ).
 * Kural 1 — sistem bildiğini sormaz: sayfa/zaman/sürüm/tarayıcı/ekran/son başarısız istekler katlanır "eklenecek
 * teknik ayrıntılar"da salt-okunur; gizlilik notu neyin ASLA eklenmediğini söyler. Kural 2 — hiçbir dosya sessizce
 * düşmez. Doğrulama satır içi (gönderimde; hatalı alana odak). Hata: yazılanlar korunur, "Tekrar dene".
 * Başarı: referans (kopyala) + sırada ne var + "Bildirimimi görüntüle" (derin bağlantı `ir_id`).
 * Telefonda tam ekran, altlık sabit (ModalShell scrollBody). Props sözleşmesi değişmedi.
 *
 * <p>`domainTransfer` (2026-09-28, isteğe bağlı): envanterde başka ekipte kayıtlı alan adının AKTARIM TALEBİ. Pencere
 * aynı akışı kullanır ama bölüm 1 salt-okunur özet (alan adı, mevcut / istenen ekip) + zorunlu "Gerekçe" olur; önem
 * kartları ve ekran görüntüsü bölümü çizilmez. Gönderimde tür `DOMAIN_TRANSFER`, ileti `composeTransferRequest` —
 * talep global yöneticilerin Sorun Bildirimleri ekranına bu türle düşer (tür süzgeci). Şekil:
 * `{ domain, inventoryId, fromTeam:{id,name}, toTeam:{id,name} }` (2026-10-07: `deleted` kalktı — silme kalıcı, çöp kutusu yok).
 */
export default function IssueReportModal({ open, onClose, errorText = '', linkedReference = '', onSubmitted, domainTransfer = null }) {
  const t = useT()
  const transfer = domainTransfer?.domain ? domainTransfer : null
  const { lang } = useLanguage()
  const [me, setMe] = useState(null)
  const [message, setMessage] = useState('')
  const [category, setCategory] = useState('')
  const [impacts, setImpacts] = useState([])            // "Ne yaşıyorsunuz?" çoklu etki (2026-09-28), kanonik sıra
  const [impactOther, setImpactOther] = useState('')    // "Diğer" kısa metni (yalnız OTHER seçiliyken gönderilir)
  const [email, setEmail] = useState('')
  const [saveEmail, setSaveEmail] = useState(true)
  const [images, setImages] = useState([])          // data-URL listesi
  const [imageNotice, setImageNotice] = useState('')
  const [sending, setSending] = useState(false)
  const [errors, setErrors] = useState({})          // alan-bazlı: { message, email }
  const [formError, setFormError] = useState('')    // gönderim/sunucu hatası
  const [reference, setReference] = useState('')
  const saveEmailId = useId()
  const counterId = useId()

  function reset() {
    setMessage(''); setCategory(''); setImpacts([]); setImpactOther(''); setEmail(''); setImages([]); setImageNotice('')
    setErrors({}); setFormError(''); setReference('')
  }

  // Açılınca form sıfırlanır ve profil taze çekilir (e-posta kuralı: profilde varsa SORULMAZ).
  useEffect(() => {
    if (!open) return
    reset()
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
  const dirty = !!(message.trim() || images.length || impacts.length)
  const maxMessage = transfer ? JUSTIFICATION_MAX : MAX_MESSAGE

  async function addFiles(fileList) {
    const { urls, notices } = await processImageFiles(fileList, images.length, t)
    if (urls.length) setImages((prev) => [...prev, ...urls].slice(0, 5))
    setImageNotice(notices.join(' '))
  }
  function onPaste(e) {
    const files = imagesFromClipboard(e)
    if (!files.length) return            // metin yapıştırma olduğu gibi
    e.preventDefault()
    addFiles(files)
  }

  function validate() {
    const next = {}
    if (!message.trim()) next.message = transfer ? t('dupx.reqWhyRequired') : t('issue.msgRequired')
    if (!profileEmail) {
      if (!email.trim()) next.email = t('issue.emailRequired')
      else if (!EMAIL_RE.test(email.trim())) next.email = t('issue.emailInvalid')
    }
    setErrors(next)
    // İlk hatalı alana odak (satır içi doğrulama — kullanıcı aramak zorunda kalmasın)
    const firstId = next.message ? 'irf-message' : next.email ? 'irf-email' : null
    if (firstId) setTimeout(() => document.querySelector(`[data-irf="${firstId}"]`)?.focus(), 0)
    return Object.keys(next).length === 0
  }

  async function submit() {
    if (sending) return
    setFormError('')
    if (!validate()) return
    setSending(true)
    try {
      const res = await api.sendIssueReport({
        message: transfer ? composeTransferRequest(transfer, message, t) : message.trim(),
        category: transfer ? 'DOMAIN_TRANSFER' : (category || undefined),
        // Etkiler aktarım talebinde sorulmaz; "Diğer" metni yalnız OTHER seçiliyken (sunucu da aynı kuralı uygular)
        impacts: !transfer && impacts.length ? impacts : undefined,
        impactOther: !transfer && impacts.includes('OTHER') && impactOther.trim() ? impactOther.trim() : undefined,
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
        onSubmitted?.(res.reference || '')
      } else {
        setFormError(res?.error || t('issue.sendFail'))
      }
    } catch {
      setFormError(t('issue.sendFail'))
    } finally {
      setSending(false)
    }
  }

  /** "Bildirimimi görüntüle": uygulama içinde sekmeye gider; çökme bağlamında (ErrorBoundary) tam sayfa yüklenir. */
  const href = reportHref(reference)
  function viewReport(e) {
    if (!href || e.metaKey || e.ctrlKey || e.shiftKey || e.button === 1) return
    if (errorText || linkedReference) return   // çökmüş ekrandan: temiz bir yükleme daha güvenli
    e.preventDefault()
    onClose?.()
    navigateTo('login-issues', { ir_id: reportIdOf(reference) })
  }

  const techRows = [
    [t('issue.autoWho'), me?.username || '—'],
    [t('issue.autoWhen'), now.toLocaleString(lang === 'tr' ? 'tr-TR' : 'en-GB')],
    [t('issue.autoWhere'), `${tabKey} · ${url.length > 70 ? url.slice(0, 70) + '…' : url}`, true],
    [t('issue.autoVersion'), currentVersion() ? `v${currentVersion()}` : '—'],
    [t('issue.autoBrowser'), browserLabel(navigator.userAgent)],
    [t('issue.autoScreen'), screenSize],
    [t('issue.autoTheme'), `${theme} · ${lang.toUpperCase()}`],
    ...(errorText ? [[t('issue.autoError'), errorText.split('\n')[0].slice(0, 160), true]] : []),
    ...(failed.length ? [[t('issue.autoFailedReqs'), failed.map((f) => `${f.path} → ${f.status || t('issue.netError')}`).join('\n'), true]] : []),
  ]

  const nextSteps = [
    t('irf.nextNotified'),
    profileEmail || email.trim() ? t('irf.nextEmail', profileEmail || email.trim()) : null,
    t('irf.nextFollow'),
  ].filter(Boolean)

  const footer = reference
    ? (
      <div className="flex w-full flex-col-reverse gap-2 sm:flex-row sm:items-center sm:justify-between">
        <Button type="button" variant="ghost" onClick={reset}><RotateCcw aria-hidden="true" />{t('irf.another')}</Button>
        <div className="flex flex-col-reverse gap-2 sm:flex-row">
          <Button type="button" variant="outline" onClick={onClose}>{t('issue.close')}</Button>
          {href && (
            <Button asChild>
              <a href={href} onClick={viewReport}>{t('irf.viewReport')}<ArrowRight aria-hidden="true" /></a>
            </Button>
          )}
        </div>
      </div>
    )
    : (
      <div className="flex w-full flex-col-reverse gap-2 sm:flex-row sm:items-center sm:justify-between">
        <span className="hidden text-xs text-muted-foreground sm:inline">{t('irf.sendHint')}</span>
        <div className="flex flex-col-reverse gap-2 sm:flex-row">
          <Button type="button" variant="outline" onClick={onClose} disabled={sending}>{t('issue.cancel')}</Button>
          <Button type="button" onClick={submit} disabled={sending} aria-busy={sending || undefined}>
            {sending ? <Spinner size={15} inline decorative /> : <Send aria-hidden="true" />}
            {sending ? t('issue.sending') : t('irf.send')}
          </Button>
        </div>
      </div>
    )

  return (
    <ModalShell open={open} onClose={onClose} busy={sending} title={transfer ? t('dupx.reqTitle') : t('issue.title')} icon={transfer ? ArrowRightLeft : Bug}
      closeLabel={t('issue.close')} size="lg" scrollBody footer={footer} className={PHONE_FULLSCREEN}
      dismissOnBackdrop={!dirty || !!reference}>
      {/* Dropzone dışına bırakılan dosya tarayıcıyı o dosyaya yönlendirir ve form kaybolur — gövde genelinde yutulur.
          Yapıştırma (Ctrl+V) pencerenin her yerinde çalışır: panodaki görsel ekran görüntüsü olarak eklenir. */}
      <div data-slot="issue-report" className="flex min-w-0 flex-col gap-6 pb-1"
        onDragOver={(e) => e.preventDefault()} onDrop={(e) => e.preventDefault()} onPaste={reference ? undefined : onPaste}
        onKeyDown={(e) => { if (!reference && (e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); submit() } }}>
        {reference ? (
          <ReportSuccess reference={reference} steps={nextSteps} />
        ) : (
          <>
            <p className="m-0 text-sm text-muted-foreground">{transfer ? t('dupx.reqIntro') : errorText ? t('irf.introCrash') : t('irf.intro')}</p>

            <ReportSection n={1} title={transfer ? t('dupx.reqSec') : t('irf.secWhat')}>
              {transfer && <TransferSummary transfer={transfer} t={t} />}
              <Field label={transfer ? t('dupx.reqWhy') : t('issue.describe')} required error={errors.message} className="mb-0">
                {({ id, describedBy, invalid }) => (
                  <>
                    <Textarea id={id} data-irf="irf-message" aria-describedby={[describedBy, counterId].filter(Boolean).join(' ')} aria-invalid={invalid}
                      className="max-h-72 min-h-28 resize-y" rows={transfer ? 4 : 5} maxLength={maxMessage}
                      value={message} placeholder={transfer ? t('dupx.reqWhyPh') : t('issue.describePh')}
                      onChange={(e) => setMessage(e.target.value)} />
                    <div className="mt-1 flex justify-end"><CharCounter id={counterId} value={message} max={maxMessage} /></div>
                  </>
                )}
              </Field>
              {errorText && (
                <div className="min-w-0 rounded-md border bg-muted/40 px-3 py-2">
                  <div className="mb-1 text-xs font-semibold text-muted-foreground">{t('irf.crashAttached')}</div>
                  <pre className="m-0 max-h-24 overflow-auto font-mono text-xs whitespace-pre-wrap [overflow-wrap:anywhere]">{errorText.split('\n').slice(0, 4).join('\n')}</pre>
                </div>
              )}
            </ReportSection>

            {/* Aktarım talebinde önem ve ekran görüntüsü sorulmaz — tür sabit (DOMAIN_TRANSFER), bağlam özette. */}
            {!transfer && (
              <ReportSection n={2} title={t('irf.secImpact')} optional>
                {/* "Ne yaşıyorsunuz?" (çoklu) + "Ne kadar etkiliyor?" (tekli önem) — 2026-09-28 zenginleştirme */}
                <div data-slot="report-impacts" className="flex min-w-0 flex-col gap-2">
                  <p className="m-0 text-sm font-medium">{t('irf.impactsLabel')} <span className="text-xs font-normal text-muted-foreground">{t('irf.impactsHint')}</span></p>
                  <ImpactPicker value={impacts} onChange={setImpacts} other={impactOther} onOther={setImpactOther} disabled={sending} />
                </div>
                <div data-slot="report-severity" className="flex min-w-0 flex-col gap-2">
                  <p className="m-0 text-sm font-medium">{t('irf.severityLabel')}</p>
                  <CategoryCards value={category} onChange={setCategory} disabled={sending} />
                </div>
              </ReportSection>
            )}

            {!transfer && (
              <ReportSection n={3} title={t('irf.secShots')} hint={t('irf.shotsHint')} optional>
                <ScreenshotField images={images} onFiles={addFiles} disabled={sending}
                  onRemove={(i) => { setImages((prev) => prev.filter((_, k) => k !== i)); setImageNotice('') }}
                  notice={imageNotice} onDismissNotice={() => setImageNotice('')} />
              </ReportSection>
            )}

            <ReportSection n={transfer ? 2 : 4} title={t('irf.secContact')}>
              {profileEmail ? (
                <p className="m-0 flex min-w-0 items-center gap-2 text-sm text-muted-foreground">
                  <Mail aria-hidden="true" className="size-4 shrink-0" />
                  <span className="min-w-0 [overflow-wrap:anywhere]">{t('issue.emailKnown')}: <strong className="text-foreground">{profileEmail}</strong></span>
                </p>
              ) : (
                <>
                  <Field label={t('issue.email')} required error={errors.email} className="mb-0">
                    {({ id, describedBy, invalid }) => (
                      <Input id={id} data-irf="irf-email" aria-describedby={describedBy} aria-invalid={invalid} type="email" autoComplete="email"
                        value={email} placeholder={t('issue.emailPh')} onChange={(e) => setEmail(e.target.value)} />
                    )}
                  </Field>
                  <div className="flex items-center gap-2">
                    <Checkbox id={saveEmailId} checked={saveEmail} onCheckedChange={(v) => setSaveEmail(v === true)} />
                    <Label htmlFor={saveEmailId} className="text-[13px] font-normal">{t('issue.emailSave')}</Label>
                  </div>
                </>
              )}
            </ReportSection>

            <div className="flex flex-col gap-3">
              <TechnicalDetails rows={techRows} />
              <PrivacyNote>{t('irf.privacy')}</PrivacyNote>
            </div>

            {formError && (
              // Düğme metnin ALTINDA: yan `actions` yuvası telefonda metni dar bir sütuna sıkıştırıyordu.
              <AlertBanner tone="danger" role="alert" title={t('irf.failTitle')} className="mb-0">
                <div>{formError} {t('irf.failKept')}</div>
                <div className="mt-2 flex flex-wrap gap-2">
                  <Button type="button" size="sm" variant="outline" onClick={submit} disabled={sending}><RotateCcw aria-hidden="true" />{t('irf.retry')}</Button>
                </div>
              </AlertBanner>
            )}
          </>
        )}
      </div>
    </ModalShell>
  )
}

/**
 * Aktarım talebinin salt-okunur özeti (2026-09-28): talep türü, alan adı, mevcut ve istenen ekip (TeamBadge — dokununca
 * üyeler), çöp kutusu notu. Telefonda etiket üstte / değer altta (tek sütun); sm+ iki sütun.
 */
function TransferSummary({ transfer, t }) {
  const row = 'grid min-w-0 grid-cols-1 gap-0.5 sm:grid-cols-[10rem_1fr] sm:items-center sm:gap-3'
  const dt = 'text-xs font-semibold text-muted-foreground sm:text-sm sm:font-normal'
  const team = (tm) => (tm?.name
    ? <TeamBadge teamId={tm.id} teamName={tm.name} />
    : <span className="text-sm text-muted-foreground">{t('dupx.noTeam')}</span>)
  return (
    <dl data-slot="transfer-summary" className="m-0 flex min-w-0 flex-col gap-2 rounded-md border bg-muted/40 px-3 py-2.5 text-sm">
      <div className={row}><dt className={dt}>{t('dupx.reqType')}</dt>
        <dd className="m-0"><Badge variant="secondary" data-slot="transfer-type">{t('issue.catDomainTransfer')}</Badge></dd></div>
      <div className={row}><dt className={dt}>{t('dupx.reqDomain')}</dt>
        <dd className="m-0 min-w-0 font-mono text-[13px] font-semibold [overflow-wrap:anywhere]">{transfer.domain}</dd></div>
      <div className={row}><dt className={dt}>{t('dupx.reqFrom')}</dt><dd className="m-0 min-w-0">{team(transfer.fromTeam)}</dd></div>
      <div className={row}><dt className={dt}>{t('dupx.reqTo')}</dt><dd className="m-0 min-w-0">{team(transfer.toTeam)}</dd></div>
    </dl>
  )
}
