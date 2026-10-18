import { useEffect, useId, useRef, useState } from 'react'
import { RefreshCw, Send } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import Field from '../ui/Field.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Button } from '@/components/shadcn/button'
import { Textarea } from '@/components/shadcn/textarea'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/shadcn/dialog'
import { MAX_EXTRA_CC, MAX_EXTRA_CC_INPUT, parseExtraCc } from './sendModel.js'

/**
 * Gönderim onay penceresi (R18 / C13, 2026-10-18 — kullanıcı onayı). Eski genel onay sorusunun yerine: aynı başlık / metin
 * (takıma ya da yöneticilere), artı isteğe bağlı "Bu gönderime ek bilgi (CC)" alanı — virgülle ayrılmış en çok 10 adres;
 * kanalda saklanmaz, yalnız bu gönderim içindir. İstemci biçim + sayı denetimi (`parseExtraCc`) alanın altında; sunucunun
 * alan hatası (`extra_cc` — ör. Bilgi toplamı 20'yi aşıyor) `serverError` ile aynı yerde. Gönderim sürerken pencere açık
 * kalır (alan hatası gelirse düzeltilsin), kapatılamaz. Alan çok satırlı (yapıştırılan satır satır liste ayrışır — tek satırlı
 * girdi satır sonlarını SİLER ve adresleri birleştirirdi); Ctrl/⌘+Enter formu gönderir, düz Enter yeni satır (gönderim geri
 * alınamaz — inceleme 2026-10-18, 2. tur), IME birleştirmesi sürerken Enter dokunulmaz.
 *
 * `onConfirm(extraCc)`: `extraCc` boşsa `''` — çağıran gövdesiz eski isteği atar.
 *
 * `failure` (inceleme 2026-10-18): `{ kind: 'retry' | 'unsure', title, message, details, check? }` — yeniden denenebilir
 * gönderim hatası (PDF sırası dolu, kişi başı sınır) pencerenin İÇİNDE gösterilir; pencere açık kalır, yazılan ek Bilgi
 * silinmez, odak alana döner.
 *
 * `initialCc` (inceleme 2026-10-18, 4. tur): pencere AÇILIRKEN alana yazılan değer (yoksa boş). Sonucu bilinmeyen gönderimden
 * sonra pencere kapatılıp yeniden açılırsa çağıran o denemenin ek Bilgi'sini verir — bant "ek Bilgi korundu" der; eskiden
 * alan boş açılıyor, "Gönder" raporu ek adreslersiz (geri alınamaz) yolluyordu.
 *
 * Sonucu bilinmeyen hata (`kind: 'unsure'` — ağ hatası, gönderim sürüyor, `MAIL_FAILED` dışı 5xx; inceleme 2026-10-18, 3. tur): "Gönder" ve
 * Ctrl+Enter KİLİTLİ — gönderim gitmiş olabilir, ikinci tıklama raporu yöneticilere ikinci kez yollardı. `check`
 * 'checking' = rapor yeniden okunuyor (düğmede dönen simge), 'unknown' = okunamadı → "Durumu yeniden denetle"
 * (`onRecheck`). Çağıran durumu çözünce bandı değiştirir (gitmemişse `retry`) ya da pencereyi kapatır.
 */
export default function SendDialog({ open, title, message, busy, serverError, failure = null, onConfirm, onCancel, onEdit, onRecheck = null, initialCc = '' }) {
  const t = useT()
  const [value, setValue] = useState('')
  const [clientError, setClientError] = useState(null)
  const inputRef = useRef(null)
  const formId = useId()
  // Sonucu bilinmeyen gönderim: durum bilinene kadar gönderim YOK (düğme + Ctrl+Enter)
  const locked = failure?.kind === 'unsure'
  const checking = locked && failure?.check !== 'unknown'

  // Her açılışta `initialCc` ile (yoksa boş) başlar — ek adresler kanalda saklanmaz; yalnız sonucu bilinmeyen / gitmemiş
  // denemenin adresleri yeniden açılışa taşınır (çağıran verir). Değer AÇILIŞ anında okunur: açıkken değişmesi alanı ezmez
  const initialRef = useRef(initialCc)
  initialRef.current = initialCc
  useEffect(() => {
    if (open) { setValue(String(initialRef.current || '')); setClientError(null) }
  }, [open])

  // Sunucunun alan hatası gelince odak alana (düzeltme yeri)
  useEffect(() => {
    if (serverError) inputRef.current?.focus()
  }, [serverError])

  // Yeniden denenebilir hata: gönderim bitince (alan yeniden açık) odak alana — gönder düğmesinde kalıp Enter'la yanlışlıkla
  // yeniden gönderilmesin; yazılan ek Bilgi orada
  useEffect(() => {
    if (failure && !busy) inputRef.current?.focus()
  }, [failure, busy])

  function submit(e) {
    e?.preventDefault?.()
    if (busy || locked) return
    const parsed = parseExtraCc(value)
    if (parsed.error) {
      setClientError(t(parsed.error.key, ...parsed.error.params))
      inputRef.current?.focus()
      return
    }
    onConfirm(parsed.addresses.join(', '))
  }

  const error = clientError || serverError || null

  // Gönderim GERİ ALINAMAZ ve alan satır başına bir adres alır: düz Enter yeni satırdır, yalnız Ctrl/⌘+Enter gönderir
  // (inceleme 2026-10-18, 2. tur — düz Enter alan doldurulurken postayı yolluyordu). IME birleştirmesi sürerken dokunulmaz.
  function onKeyDown(e) {
    if (e.key !== 'Enter' || !(e.ctrlKey || e.metaKey) || e.shiftKey || e.altKey || e.nativeEvent?.isComposing || e.keyCode === 229) return
    e.preventDefault()
    submit()
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o && !busy) onCancel() }}>
      <DialogContent className="max-h-[92dvh] overflow-y-auto sm:max-w-lg" data-slot="te-send-dialog"
        onInteractOutside={(e) => { if (busy) e.preventDefault() }} onEscapeKeyDown={(e) => { if (busy) e.preventDefault() }}>
        <form id={formId} onSubmit={submit} className="flex flex-col gap-4" noValidate>
          <DialogHeader className="text-left">
            <DialogTitle>{title}</DialogTitle>
            <DialogDescription className="[overflow-wrap:anywhere]">{message}</DialogDescription>
          </DialogHeader>
          <Field label={t('te.send.ccLabel')} hint={t('te.send.ccHint', MAX_EXTRA_CC)} error={error} name="extra_cc">
            {({ id, describedBy, invalid }) => (
              <Textarea ref={inputRef} id={id} rows={2} maxLength={MAX_EXTRA_CC_INPUT} value={value} disabled={busy}
                aria-describedby={describedBy} aria-invalid={invalid} autoComplete="off" spellCheck={false}
                placeholder={t('te.send.ccPlaceholder')} data-slot="te-send-extra-cc" className="min-h-16 [overflow-wrap:anywhere]"
                onKeyDown={onKeyDown}
                onChange={(e) => { setValue(e.target.value); setClientError(null); onEdit?.() }} />
            )}
          </Field>
          {failure && (
            <div data-slot="te-send-failure" data-kind={failure.kind}>
              <AlertBanner tone={failure.kind === 'unsure' ? 'warning' : 'danger'} role="alert" title={failure.title}
                details={failure.kind === 'unsure' ? undefined : (failure.details || undefined)}>
                {failure.message}
              </AlertBanner>
              {locked && !checking && onRecheck && (
                <Button type="button" variant="outline" size="sm" className="mt-2 w-full sm:w-auto pointer-coarse:min-h-10"
                  data-slot="te-send-recheck" onClick={() => onRecheck()}>
                  <RefreshCw aria-hidden="true" /> {t('te.send.recheck')}
                </Button>
              )}
            </div>
          )}
          <DialogFooter className="flex-col-reverse gap-2 sm:flex-row">
            <Button type="button" variant="outline" className="pointer-coarse:min-h-10" disabled={busy} onClick={onCancel}>
              {t('te.cancel')}
            </Button>
            <Button type="submit" className="pointer-coarse:min-h-10" disabled={busy || locked} aria-busy={busy || checking || undefined}
              data-slot="te-send-confirm" data-locked={locked || undefined}>
              {busy || checking ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : <Send aria-hidden="true" />}
              {t('te.sendConfirm')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
