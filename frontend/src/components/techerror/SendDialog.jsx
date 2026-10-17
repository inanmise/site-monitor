import { useEffect, useId, useRef, useState } from 'react'
import { RefreshCw, Send } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import Field from '../ui/Field.jsx'
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
 * girdi satır sonlarını SİLER ve adresleri birleştirirdi); Enter formu gönderir (birincil eylem), Shift+Enter yeni satır,
 * IME birleştirmesi sürerken Enter dokunulmaz (inceleme 2026-10-18).
 *
 * `onConfirm(extraCc)`: `extraCc` boşsa `''` — çağıran gövdesiz eski isteği atar.
 */
export default function SendDialog({ open, title, message, busy, serverError, onConfirm, onCancel, onEdit }) {
  const t = useT()
  const [value, setValue] = useState('')
  const [clientError, setClientError] = useState(null)
  const inputRef = useRef(null)
  const formId = useId()

  // Her açılışta boş başlar (ek adresler kaydedilmez — bir sonraki gönderime taşınmaz)
  useEffect(() => {
    if (open) { setValue(''); setClientError(null) }
  }, [open])

  // Sunucunun alan hatası gelince odak alana (düzeltme yeri)
  useEffect(() => {
    if (serverError) inputRef.current?.focus()
  }, [serverError])

  function submit(e) {
    e?.preventDefault?.()
    if (busy) return
    const parsed = parseExtraCc(value)
    if (parsed.error) {
      setClientError(t(parsed.error.key, ...parsed.error.params))
      inputRef.current?.focus()
      return
    }
    onConfirm(parsed.addresses.join(', '))
  }

  const error = clientError || serverError || null

  function onKeyDown(e) {
    if (e.key !== 'Enter' || e.shiftKey || e.altKey || e.nativeEvent?.isComposing || e.keyCode === 229) return
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
          <DialogFooter className="flex-col-reverse gap-2 sm:flex-row">
            <Button type="button" variant="outline" className="pointer-coarse:min-h-10" disabled={busy} onClick={onCancel}>
              {t('te.cancel')}
            </Button>
            <Button type="submit" className="pointer-coarse:min-h-10" disabled={busy} aria-busy={busy || undefined} data-slot="te-send-confirm">
              {busy ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : <Send aria-hidden="true" />}
              {t('te.sendConfirm')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
