import { useMemo, useRef, useState } from 'react'
import { Braces, Check, MessageSquareText, RotateCcw, Send, TriangleAlert } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useToast } from '../../ui/Toast.jsx'
import Field from '../../ui/Field.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'
import { pushSafeReport } from '../../../utils/pushSafeText.js'
import { SettingsSection, helpLabel } from '../SettingsControls.jsx'
import PushPhonePreview from './PushPhonePreview.jsx'
import {
  DEFAULT_MESSAGE_MAX, LANGS, PLACEHOLDERS, TITLE_MAX, insertAtCursor, messageKey, phoneView, placeholdersIn,
  titleKey, titleLength, worstCaseLength,
} from './pushTemplateModel.js'

/** Sunucu göndermezse (eski sürüm) yerleşik metinler — backend OtpPushTemplate ile aynı. */
const FALLBACK_DEFAULTS = {
  tr: { title: 'SiteMonitor giriş kodu', message: 'SiteMonitor giriş kodunuz: {kod} - {sure} sn geçerli. Bu isteği siz yapmadıysanız dikkate almayın.' },
  en: { title: 'SiteMonitor sign-in code', message: 'Your SiteMonitor sign-in code: {kod} - valid for {sure} s. If you did not request it, ignore this message.' },
}

/** Şu anın İstanbul saati (HH:mm) — önizlemenin {saat} örneği ve kilit ekranı saati. */
function istanbulClock() {
  try {
    return new Intl.DateTimeFormat('tr-TR', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Europe/Istanbul' }).format(new Date())
  } catch {
    return '12:00'
  }
}

/** Sayaç: değer / sınır — sınır aşılınca yıkıcı ton. */
function Counter({ id, value, max, label }) {
  const over = value > max
  return (
    <span id={id} data-slot="lm-push-counter" data-over={over ? 'true' : undefined}
      className={cn('text-xs tabular-nums', over ? 'font-semibold text-destructive' : 'text-muted-foreground')}>
      {label}
    </span>
  )
}

/**
 * Giriş Yöntemleri → "Push mesajı" düzenleyicisi (2026-10-03, kullanıcı isteği: "push metnini login settings sayfasında
 * değiştirebilmem lazım"). TR / EN sekmeleri (shadcn Tabs; içerikler `forceMount` + `hidden` → doğrulama hatası diğer
 * dildeyse alan DOM'da kalır, kabuk sekmeyi o dile çevirir), Başlık (Input) + Mesaj (Textarea), imlece eklenen yer
 * tutucu çipleri ({kod} zaten varsa pasif), canlı sayaçlar (başlık 60; mesaj EN KÖTÜ dolumla push tavanına karşı),
 * "Varsayılana dön", telefonda değişecek / düşecek karakterler listesi, kilit ekranı önizlemesi ve "Kendime test
 * gönder" (ağ geçidi yoksa nedenini söyleyerek pasif). Kayıt sayfanın ortak kaydet çubuğundan; hatalar ALANIN altında.
 *
 * Test kancaları: `data-slot="lm-push-template"`, `lm-push-chip` (`data-token`), `lm-push-reset`, `lm-push-chars`,
 * `lm-push-test`, `lm-push-preview`.
 */
export default function PushTemplateEditor({ form, onField, fe, template, lang, onLangChange, gateway, ttl }) {
  const t = useT()
  const toast = useToast()
  const [testing, setTesting] = useState(false)
  const messageRefs = useRef({})
  const clock = useMemo(() => istanbulClock(), [])
  const defaults = template?.defaults || FALLBACK_DEFAULTS
  const max = Number(template?.message_max) || DEFAULT_MESSAGE_MAX
  const titleMax = Number(template?.title_max) || TITLE_MAX
  const invalid = template?.stored_invalid || {}
  const sampleTtl = Number(ttl) > 0 ? Number(ttl) : 45

  function insert(l, token) {
    const key = messageKey(l)
    const el = messageRefs.current[l]
    const { value, caret } = insertAtCursor(form?.[key] ?? '', token, el?.selectionStart, el?.selectionEnd)
    onField(key, value)
    // Değer yazıldıktan sonra imleç jetonun ARKASINA (kullanıcı yazmaya devam etsin)
    const restore = () => { try { el?.focus(); el?.setSelectionRange(caret, caret) } catch { /* jsdom */ } }
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(restore); else restore()
  }

  async function sendTest(l) {
    if (testing || !gateway) return
    setTesting(true)
    try {
      const r = await api.loginMethodsAdmin.pushTest({ lang: l, title: form?.[titleKey(l)] ?? '', message: form?.[messageKey(l)] ?? '' })
      if (r?.field) {
        fe.check({ [r.field]: r.error || t('lm.push.tpl.testFailed') })
      } else if (r?.success) {
        toast.success(r.message || t('lm.push.tpl.testSent'))
      } else {
        toast.error(r?.error || t('lm.push.tpl.testFailed'))
      }
    } catch (e) {
      toast.error(e?.message || t('lm.push.tpl.testFailed'))
    } finally {
      setTesting(false)
    }
  }

  const langLabel = (l) => (l === 'en' ? t('lm.push.tpl.langEn') : t('lm.push.tpl.langTr'))
  const isCustom = (l) => {
    const d = defaults[l] || FALLBACK_DEFAULTS[l]
    const tv = String(form?.[titleKey(l)] ?? '').trim()
    const mv = String(form?.[messageKey(l)] ?? '').trim()
    return (tv !== '' && tv !== d.title) || (mv !== '' && mv !== d.message)
  }
  const hasError = (l) => !!(fe.errors[titleKey(l)] || fe.errors[messageKey(l)])

  return (
    <SettingsSection
      title={<span className="inline-flex items-center gap-2"><MessageSquareText aria-hidden="true" className="size-4" />{t('lm.push.tpl.title')}</span>}
      description={t('lm.push.tpl.desc')} contentClassName="flex min-w-0 flex-col gap-4">
      <div data-slot="lm-push-template" data-lang={lang} className="@container/pte flex min-w-0 flex-col gap-4">
        {LANGS.some((l) => invalid[l]) && (
          <div data-slot="lm-push-stored-invalid">
            <AlertBanner tone="warning" className="mb-0">
              {t('lm.push.tpl.storedInvalid', LANGS.filter((l) => invalid[l]).map(langLabel).join(', '))}
            </AlertBanner>
          </div>
        )}
        <Tabs value={lang} onValueChange={onLangChange} className="min-w-0 gap-4">
          <TabsList className="h-auto! w-full flex-row! sm:w-fit">
            {LANGS.map((l) => (
              <TabsTrigger key={l} value={l} data-lang={l} className="min-h-10 w-auto! flex-1 justify-center! gap-1.5 px-3 sm:min-h-8 sm:flex-none">
                {langLabel(l)}
                {hasError(l)
                  ? <TriangleAlert aria-hidden="true" className="size-3.5 text-destructive" />
                  : isCustom(l) && <Badge variant="secondary" className="px-1.5 py-0 text-[10px]">{t('lm.push.tpl.custom')}</Badge>}
              </TabsTrigger>
            ))}
          </TabsList>
          {LANGS.map((l) => {
            const d = defaults[l] || FALLBACK_DEFAULTS[l]
            const tKey = titleKey(l)
            const mKey = messageKey(l)
            const title = form?.[tKey] ?? ''
            const message = form?.[mKey] ?? ''
            const tLen = titleLength(title.trim() ? title : d.title)
            const mLen = worstCaseLength(message.trim() ? message : d.message)
            const hasCode = placeholdersIn(message).includes('kod')
            const report = pushSafeReport(`${title}\n${message}`)
            const changes = report.converted.length + report.dropped.length
            const view = phoneView({ title, message, defaults: d, samples: { kod: '123456', sure: sampleTtl, saat: clock }, max })
            const atDefault = title.trim() === d.title && message.trim() === d.message
            return (
              <TabsContent key={l} value={l} forceMount hidden={lang !== l} className="min-w-0">
                <div className="grid min-w-0 grid-cols-1 gap-5 @3xl/pte:grid-cols-[minmax(0,1fr)_minmax(0,20rem)]">
                  <div className="flex min-w-0 flex-col gap-1">
                    <Field label={helpLabel(t('lm.push.tpl.titleLabel'), l === 'en' ? 'help.set.site.monitor.login.otp.push.title-en' : 'help.set.site.monitor.login.otp.push.title-tr')} hint={t('lm.push.tpl.titleHint')} {...fe.fieldProps(tKey)}>
                      {({ id, describedBy, invalid: inv }) => (
                        <div className="flex min-w-0 flex-col gap-1">
                          <Input id={id} value={title} aria-describedby={[describedBy, `${id}-cnt`].filter(Boolean).join(' ')}
                            aria-invalid={inv} className="h-10" placeholder={d.title}
                            onChange={(e) => onField(tKey, e.target.value)} />
                          <Counter id={`${id}-cnt`} value={tLen} max={titleMax} label={t('lm.push.tpl.counterTitle', tLen, titleMax)} />
                        </div>
                      )}
                    </Field>
                    <Field label={helpLabel(t('lm.push.tpl.messageLabel'), l === 'en' ? 'help.set.site.monitor.login.otp.push.message-en' : 'help.set.site.monitor.login.otp.push.message-tr')} hint={t('lm.push.tpl.messageHint')} {...fe.fieldProps(mKey)}>
                      {({ id, describedBy, invalid: inv }) => (
                        <div className="flex min-w-0 flex-col gap-1">
                          <Textarea id={id} rows={4} value={message} aria-invalid={inv}
                            aria-describedby={[describedBy, `${id}-cnt`].filter(Boolean).join(' ')}
                            ref={(el) => { messageRefs.current[l] = el }} placeholder={d.message} className="min-h-24"
                            onChange={(e) => onField(mKey, e.target.value)} />
                          <Counter id={`${id}-cnt`} value={mLen} max={max} label={t('lm.push.tpl.counterMessage', mLen, max)} />
                        </div>
                      )}
                    </Field>
                    <div className="flex min-w-0 flex-col gap-2" data-slot="lm-push-chips">
                      <span className="inline-flex items-center gap-1.5 text-xs font-semibold text-muted-foreground">
                        <Braces aria-hidden="true" className="size-3.5" />{t('lm.push.tpl.insert')}
                      </span>
                      <div role="group" aria-label={t('lm.push.tpl.insert')} className="flex flex-wrap gap-2">
                        {PLACEHOLDERS.map((p) => {
                          const token = `{${p}}`
                          const done = p === 'kod' && hasCode
                          return (
                            <Button key={p} type="button" variant="outline" size="sm" data-slot="lm-push-chip" data-token={token}
                              disabled={done} title={done ? t('lm.push.tpl.codePresent') : undefined}
                              aria-label={t('lm.push.tpl.insertAria', token, t(`lm.push.tpl.ph.${p}`))}
                              className="min-h-10 gap-1.5 font-mono sm:min-h-8" onClick={() => insert(l, token)}>
                              {done && <Check aria-hidden="true" />}{token}
                            </Button>
                          )
                        })}
                      </div>
                      <ul className="m-0 flex list-none flex-wrap gap-x-4 gap-y-1 p-0 text-xs text-muted-foreground">
                        {PLACEHOLDERS.map((p) => (
                          <li key={p}><span className="font-mono text-foreground">{`{${p}}`}</span> {t(`lm.push.tpl.ph.${p}`)}</li>
                        ))}
                      </ul>
                    </div>
                    {changes > 0 && (
                      <div data-slot="lm-push-chars" className="mt-2">
                        <AlertBanner tone="info" className="mb-0" title={t('lm.push.tpl.charsTitle')}>
                          <p className="m-0 text-xs">{t('lm.push.tpl.charsDesc')}</p>
                          <ul className="m-0 mt-2 flex list-none flex-wrap gap-1.5 p-0">
                            {report.converted.map((c) => (
                              <li key={`c-${c.from}`}>
                                <Badge variant="outline" data-kind="converted" className="gap-1 font-mono font-normal">
                                  {t('lm.push.tpl.convertedItem', c.from, c.to)}{c.count > 1 ? ` ×${c.count}` : ''}
                                </Badge>
                              </li>
                            ))}
                            {report.dropped.map((c) => (
                              <li key={`d-${c.ch}`}>
                                <Badge variant="outline" data-kind="dropped" className="gap-1 font-normal text-destructive">
                                  {t('lm.push.tpl.droppedItem', c.ch)}{c.count > 1 ? ` ×${c.count}` : ''}
                                </Badge>
                              </li>
                            ))}
                          </ul>
                        </AlertBanner>
                      </div>
                    )}
                    <div className="mt-3 flex flex-wrap items-center gap-2">
                      <Button type="button" variant="outline" data-slot="lm-push-reset" disabled={atDefault}
                        className="min-h-10 sm:min-h-9"
                        onClick={() => { onField(tKey, d.title); onField(mKey, d.message) }}>
                        <RotateCcw aria-hidden="true" />{t('lm.push.tpl.reset')}
                      </Button>
                    </div>
                  </div>
                  <div className="flex min-w-0 flex-col gap-3">
                    <span className="text-xs font-semibold text-muted-foreground">{t('lm.push.tpl.preview')}</span>
                    <PushPhonePreview title={view.title} message={view.message} clock={clock} lang={l} />
                    <p className="m-0 text-xs text-muted-foreground">{t('lm.push.tpl.previewDesc', sampleTtl, clock)}</p>
                    <div className="flex min-w-0 flex-col gap-1.5">
                      <Button type="button" variant="secondary" data-slot="lm-push-test" disabled={!gateway || testing}
                        aria-busy={testing || undefined} className="min-h-10 w-full sm:w-auto sm:self-start"
                        aria-describedby={`lm-push-test-hint-${l}`} onClick={() => sendTest(l)}>
                        {testing ? <Spinner size={14} inline decorative /> : <Send aria-hidden="true" />}{t('lm.push.tpl.test')}
                      </Button>
                      <p id={`lm-push-test-hint-${l}`} data-slot="lm-push-test-hint" data-reason={gateway ? 'ok' : 'no-gateway'}
                        className={cn('m-0 text-xs', gateway ? 'text-muted-foreground' : 'font-medium text-warning')}>
                        {gateway ? t('lm.push.tpl.testHint') : t('lm.push.tpl.testNoGateway')}
                      </p>
                    </div>
                  </div>
                </div>
              </TabsContent>
            )
          })}
        </Tabs>
      </div>
    </SettingsSection>
  )
}
