import { useRef, useState } from 'react'
import { Send } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useToast } from '../../ui/Toast.jsx'
import Field from '../../ui/Field.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { TestResult } from '../SettingsControls.jsx'
import { isValidEmail } from '../../noc/nocModel.js'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Popover, PopoverContent, PopoverDescription, PopoverHeader, PopoverTitle, PopoverTrigger } from '@/components/shadcn/popover'

/**
 * Başlıktaki "Test e-postası" eylemi — shadcn Popover içinde alıcı + Gönder. Örnek bir anomali raporu GERÇEK e-posta
 * olarak gider (`POST /admin/login-anomaly/test-email`, SMTP doğrulaması; kaydedilmemiş ayar gerekmez).
 *
 * <p>Alıcı açılışta ilk etkin alıcıyla dolar (liste boşsa sistem yöneticisi adresi); kullanıcı değiştirdiyse korunur.
 * Sonuç popover içinde satır içi (başarı / sunucu durumu) + başarıda bildirim. Bayrak `finally`'de iner.
 */
export default function LaTestMail({ defaultRecipient = '' }) {
  const t = useT()
  const toast = useToast()
  const toastRef = useRef(toast)
  toastRef.current = toast
  const [open, setOpen] = useState(false)
  const [to, setTo] = useState('')
  const [touched, setTouched] = useState(false)
  const [sending, setSending] = useState(false)
  const [result, setResult] = useState(null)   // { ok, msg }
  const [fieldError, setFieldError] = useState(null)

  function onOpenChange(next) {
    setOpen(next)
    if (next) {
      if (!touched) setTo(defaultRecipient || '')
      setResult(null)
      setFieldError(null)
    }
  }

  async function send() {
    const addr = to.trim()
    if (!addr) { setFieldError(t('loginAnomaly.testNeedEmail')); return }
    if (!isValidEmail(addr)) { setFieldError(t('loginAnomaly.test.invalid')); return }
    setFieldError(null)
    setResult(null)
    setSending(true)
    try {
      const res = await api.admin.testLoginAnomalyEmail(addr)
      if (res?.success && res.data?.sent) {
        setResult({ ok: true, msg: t('loginAnomaly.testSent') })
        toastRef.current.success(t('loginAnomaly.testSent'))
      } else {
        setResult({ ok: false, msg: res?.data?.status || res?.error || res?.message || t('loginAnomaly.testFailed') })
      }
    } catch (e) {
      setResult({ ok: false, msg: e?.message || t('loginAnomaly.testFailed') })
    } finally {
      setSending(false)
    }
  }

  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>
        <Button type="button" variant="outline" data-action="la-test-open"
          className="h-10 w-full sm:h-9 sm:w-auto sm:pointer-coarse:h-10">
          <Send aria-hidden="true" />{t('loginAnomaly.test.open')}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" collisionPadding={16} data-slot="la-test-popover"
        className="z-(--z-menu) flex w-[min(24rem,calc(100vw-2rem))] flex-col gap-3">
        <PopoverHeader>
          <PopoverTitle>{t('loginAnomaly.testTitle')}</PopoverTitle>
          <PopoverDescription className="text-xs">{t('loginAnomaly.testDesc')}</PopoverDescription>
        </PopoverHeader>
        <form className="flex flex-col gap-2" noValidate
          onSubmit={(e) => { e.preventDefault(); if (!sending) send() }}>
          <Field label={t('loginAnomaly.test.to')} error={fieldError} className="mb-0">
            {({ id, describedBy, invalid }) => (
              <Input id={id} type="email" inputMode="email" autoComplete="email" spellCheck={false}
                aria-describedby={describedBy} aria-invalid={invalid} placeholder={t('loginAnomaly.test.ph')}
                value={to} onChange={(e) => { setTo(e.target.value); setTouched(true); setFieldError(null) }}
                className="h-10 sm:h-9 sm:pointer-coarse:h-10" />
            )}
          </Field>
          <Button type="submit" disabled={sending} aria-busy={sending || undefined}
            className="h-10 w-full sm:h-9 sm:pointer-coarse:h-10">
            {sending ? <Spinner size={15} inline decorative /> : <Send aria-hidden="true" />}
            {t('loginAnomaly.testSend')}
          </Button>
        </form>
        {result && <TestResult ok={result.ok} copyClassName="pointer-coarse:size-10">{result.msg}</TestResult>}
      </PopoverContent>
    </Popover>
  )
}
