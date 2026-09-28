import { useState, useEffect, useRef, useCallback } from 'react'
import {
  Eye, EyeOff, KeyRound, RefreshCw, LockKeyhole, ShieldCheck, X, Eraser, DatabaseZap,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import Field from '../ui/Field.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import CopyableRef from '../ui/CopyableRef.jsx'
import { Spinner, ProgressBar } from '../ui/Progress.jsx'
import ToneBadge from './ToneBadge.jsx'
import { SETTINGS_STACK, helpLabel, SettingsHeader, SettingsSection } from './SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { ItemGroup, Item, ItemTitle, ItemDescription, ItemActions } from '@/components/shadcn/item'

/** Açılan değer bu kadar saniye sonra kendiliğinden gizlenir (görünür geri sayımla). */
export const REVEAL_SECONDS = 30

/** Tarayıcıda kriptografik olarak güçlü rastgele anahtar üretir (32 bayt → base64, 44 karakter). */
function generateKey() {
  const bytes = new Uint8Array(32)
  crypto.getRandomValues(bytes)
  let bin = ''
  for (const b of bytes) bin += String.fromCharCode(b)
  return btoa(bin)
}

/**
 * Şifreli sütun → kullanıldığı yer. Sütun adları backend `SecretToolsService.decryptWithKey` ile aynı;
 * bilinmeyen sütunda satır yalnız sütun adını gösterir. Literal t() çağrıları i18n-used-keys kapısına görünür.
 */
const USAGE = {
  'smtp_settings.password_enc': (t) => t('secret.usageSmtp', t('settings.navSmtp')),
  'ldap_settings.bind_password_enc': (t) => t('secret.usageLdap', t('settings.navLdap')),
}

/**
 * Doğrulama sonucu: kayıtlı (present) değerlerin kaçı bu anahtarla çözüldü.
 * empty = doğrulanacak değer yok · match = hepsi · mismatch = hiçbiri · partial = bir kısmı.
 */
export function summarise(rows) {
  const present = rows.filter((r) => r.present)
  const ok = present.filter((r) => r.ok).length
  let kind = 'partial'
  if (present.length === 0) kind = 'empty'
  else if (ok === present.length) kind = 'match'
  else if (ok === 0) kind = 'mismatch'
  return { kind, total: present.length, ok }
}

/** Satır içi sonuç — anahtar kartında, Doğrula düğmesinin hemen altında. */
function OutcomeBanner({ outcome, t }) {
  if (!outcome) return null
  const { kind, total, ok, dev } = outcome
  let tone = 'info'
  let title = null
  let body = null
  let role = 'status'
  if (kind === 'error') {
    tone = 'danger'; role = 'alert'
    title = t('secret.errTitle'); body = outcome.message || t('secret.unreachable')
  } else if (kind === 'match' && dev) {
    tone = 'warning'; title = t('secret.devMatchTitle'); body = t('secret.devMatchBody')
  } else if (kind === 'match') {
    tone = 'success'; title = t('secret.okTitle'); body = total === 1 ? t('secret.okBodyOne') : t('secret.okBody', total)
  } else if (kind === 'mismatch' && dev) {
    tone = 'info'; title = t('secret.devNoMatchTitle'); body = t('secret.devNoMatchBody')
  } else if (kind === 'mismatch') {
    tone = 'danger'; role = 'alert'; title = t('secret.badTitle'); body = t('secret.badBody', total)
  } else if (kind === 'partial') {
    tone = 'warning'; title = t('secret.partialTitle'); body = t('secret.partialBody', ok, total)
  } else {
    title = t('secret.emptyTitle'); body = t('secret.emptyBody')
  }
  return (
    <AlertBanner tone={tone} role={role} title={title} className="mb-0">
      {body}
      {dev && kind !== 'error' && <span className="mt-1 block text-xs opacity-80">{t('secret.withDev')}</span>}
    </AlertBanner>
  )
}

/** Tek şifreli sütun — telefonda yığılmış kart; değer varsayılan GİZLİ ve gizliyken DOM'da hiç yok. */
function SecretRow({ row, left, onToggle, t }) {
  const usage = USAGE[row.column]?.(t)
  const open = left != null
  const status = !row.present ? 'absent' : row.ok ? 'ok' : 'fail'
  return (
    <Item role="listitem" variant="outline" data-slot="secret-row" data-column={row.column} data-status={status}
      className="min-w-0 flex-col flex-nowrap items-stretch gap-3 bg-card p-3 sm:p-4">
      <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex min-w-0 flex-col gap-0.5">
          <ItemTitle role="heading" aria-level={5} className="w-auto font-semibold">{row.label}</ItemTitle>
          <code translate="no" className="font-mono text-xs break-all text-muted-foreground">{row.column}</code>
        </div>
        <div className="flex flex-wrap gap-1.5 sm:shrink-0 sm:justify-end">
          <ToneBadge tone={row.present ? 'info' : 'muted'}>{row.present ? t('secret.present') : t('secret.absent')}</ToneBadge>
          {row.present && (
            <ToneBadge tone={row.ok ? 'success' : 'danger'}>{row.ok ? t('secret.decrypted') : t('secret.notDecrypted')}</ToneBadge>
          )}
        </div>
      </div>

      {usage && (
        <ItemDescription className="line-clamp-none text-xs text-pretty">
          <span className="font-semibold text-foreground">{t('secret.usedIn')}:</span> {usage}
        </ItemDescription>
      )}

      {!row.present && <p className="text-sm text-muted-foreground">{t('secret.noValue')}</p>}
      {row.present && !row.ok && <p className="text-sm font-medium text-destructive">{t('secret.rowFail')}</p>}

      {row.present && row.ok && (
        <div className="flex min-w-0 flex-col gap-2">
          <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-center">
            <div className="min-w-0 flex-1">
              {open ? (
                <code data-slot="secret-value" translate="no"
                  className="block rounded-md border bg-muted px-3 py-2 font-mono text-sm break-all whitespace-pre-wrap text-foreground">
                  {row.value ?? ''}
                </code>
              ) : (
                <div data-slot="secret-mask" className="rounded-md border border-dashed bg-muted/40 px-3 py-2 text-sm text-muted-foreground">
                  <span aria-hidden="true" className="font-mono tracking-widest">••••••••••••</span>
                  <span className="sr-only">{t('secret.hiddenValue')}</span>
                </div>
              )}
            </div>
            <ItemActions className="shrink-0">
              <Button type="button" variant="outline" size="sm" aria-pressed={open}
                aria-label={t('a11y.rowAction', open ? t('secret.hide') : t('secret.show'), row.label)}
                onClick={() => onToggle(row.column)} className="max-sm:h-10 max-sm:flex-1">
                {open ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
                {open ? t('secret.hideShort') : t('secret.showShort')}
              </Button>
              <CopyButton value={row.value ?? ''} size={15} buttonSize="icon-sm" className="max-sm:size-10"
                label={t('a11y.rowAction', t('secret.copyValue'), row.label)}
                copiedLabel={t('a11y.rowAction', t('secret.copied'), row.label)} />
            </ItemActions>
          </div>
          {open && (
            <div data-slot="secret-countdown" className="flex items-center gap-3">
              <div className="min-w-0 flex-1"><ProgressBar value={left} max={REVEAL_SECONDS} size="sm" decorative /></div>
              <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{t('secret.hidesIn', left)}</span>
            </div>
          )}
        </div>
      )}
    </Item>
  )
}

/**
 * Anahtar Çözümleme (yalnız global yönetici) — aday SITE_MONITOR_SECRET_KEY'in DB'deki şifreli alanlarla
 * (SMTP/LDAP parolaları) eşleşip eşleşmediğini doğrular; eşleşiyorsa değerleri isteğe bağlı gösterir.
 *
 * <p>Oturum hijyeni (2026-09-28 yeniden tasarım):
 *  • Anahtar ve çözülen değerler YALNIZ bileşen durumunda — tarayıcı depolaması yok, URL'ye yazılmaz, `<form>` yok
 *    (GET gönderimi anahtarı adres çubuğuna koyardı). Bölümden çıkınca (AdminSettings bölümü koşullu çizer →
 *    unmount) hepsi gider; geri sayım zamanlayıcısı temizlenir, geç gelen yanıt yok sayılır.
 *  • Değer varsayılan gizli ve gizliyken DOM'da HİÇ yok (maske); "Göster" {@link REVEAL_SECONDS} sn açar, sekme
 *    arka plana geçince hepsi hemen gizlenir.
 *  • Anahtar alanı `autoComplete="new-password"`: Chrome parola alanında "off"u yok sayıp kayıtlı hesabın kullanıcı
 *    adını Ayarlar menü aramasına yazıyordu (kapı `passwordAutocomplete.test.js`).
 */
export default function SecretTools() {
  const t = useT()
  const [key, setKey] = useState('')
  const [showKey, setShowKey] = useState(false)
  const [info, setInfo] = useState(null)            // { dev_default_key, secret_key_set }
  const [infoState, setInfoState] = useState('loading')   // loading | ready | error
  const [infoError, setInfoError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [rows, setRows] = useState(null)            // null = henüz doğrulanmadı
  const [outcome, setOutcome] = useState(null)      // { kind, total, ok, dev } | { kind: 'error', message }
  const [revealed, setRevealed] = useState({})      // sütun → kalan saniye
  const [genKey, setGenKey] = useState('')
  const alive = useRef(true)
  const seq = useRef(0)

  // Kurulumda da true (StrictMode kur → temizle → kur); temizlikte geç yanıtlar düşsün diye tur da ilerler.
  useEffect(() => {
    alive.current = true
    return () => { alive.current = false; seq.current += 1 }
  }, [])

  const loadInfo = useCallback(async () => {
    setInfoState('loading')
    try {
      const res = await api.admin.secretToolsInfo()
      if (!alive.current) return
      if (res?.success) { setInfo(res); setInfoError(null); setInfoState('ready') }
      else { setInfo(null); setInfoError(res?.error || null); setInfoState('error') }
    } catch {
      if (alive.current) { setInfo(null); setInfoError(null); setInfoState('error') }
    }
  }, [])

  useEffect(() => { loadInfo() }, [loadInfo])

  // Geri sayım: açık değer varken tek zamanlayıcı; süresi dolan satır gizlenir, hepsi kapanınca zamanlayıcı durur.
  const anyRevealed = Object.keys(revealed).length > 0
  useEffect(() => {
    if (!anyRevealed) return undefined
    const id = setInterval(() => {
      setRevealed((prev) => {
        const next = {}
        for (const [col, s] of Object.entries(prev)) if (s > 1) next[col] = s - 1
        return next
      })
    }, 1000)
    return () => clearInterval(id)
  }, [anyRevealed])

  // Sekme arka plana geçince (başka sekme, ekran kilidi, uygulama değiştirme) açık değerler ve anahtar gizlenir.
  useEffect(() => {
    const onVisibility = () => {
      if (document.hidden) { setRevealed({}); setShowKey(false) }
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => document.removeEventListener('visibilitychange', onVisibility)
  }, [])

  async function verify(candidate, { dev = false } = {}) {
    const k = String(candidate ?? '').trim()
    if (!k || busy) return
    const turn = ++seq.current
    setBusy(true)
    setRevealed({})
    try {
      const res = await api.admin.decryptSecrets(k)
      if (turn !== seq.current) return
      if (res?.success) {
        const data = Array.isArray(res.data) ? res.data : []
        setRows(data)
        setOutcome({ ...summarise(data), dev: dev || (!!info?.dev_default_key && k === info.dev_default_key) })
      } else {
        setRows(null)
        setOutcome({ kind: 'error', message: res?.error || null })
      }
    } catch {
      if (turn === seq.current) { setRows(null); setOutcome({ kind: 'error', message: null }) }
    } finally {
      setBusy(false)
    }
  }

  function toggle(column) {
    setRevealed((prev) => {
      const next = { ...prev }
      if (next[column] != null) delete next[column]
      else next[column] = REVEAL_SECONDS
      return next
    })
  }

  // Temizle düğmesi anahtar boşalınca kaybolur → odak girdiye döner (InputGroupInput ref almaz, forwardRef değil).
  function clearKey(e) {
    setKey('')
    setShowKey(false)
    e?.currentTarget?.closest('[data-slot="input-group"]')?.querySelector('input')?.focus()
  }

  function clearResults() {
    seq.current += 1          // uçuştaki yanıt artık yazılmaz
    setRows(null)
    setOutcome(null)
    setRevealed({})
  }

  const trimmedLen = key.trim().length
  const padded = trimmedLen > 0 && key !== key.trim()
  const hint = (
    <>
      {t('secret.keyHintV2')}
      {trimmedLen > 0 && <> <span className="font-medium tabular-nums">{t('secret.keyLength', trimmedLen)}</span></>}
      {padded && <> · {t('secret.keyTrimmed')}</>}
    </>
  )

  const meta = infoState === 'ready' ? (
    info?.secret_key_set
      ? <ToneBadge tone="success">{t('secret.keySet')}</ToneBadge>
      : <ToneBadge tone="warning">{t('secret.keyNotSet')}</ToneBadge>
  ) : infoState === 'error' ? <ToneBadge tone="danger">{t('secret.infoError')}</ToneBadge> : null

  return (
    <div className={`${SETTINGS_STACK} @container/secret`} data-testid="secret-tools">
      <SettingsHeader icon={LockKeyhole} title={t('secret.title')} description={t('secret.descV2')} meta={meta}>
        <AlertBanner tone="warning" title={t('secret.riskTitle')} className="mb-0">
          <ul className="ml-4 list-disc space-y-1">
            <li>{t('secret.riskServer')}</li>
            <li>{t('secret.riskBrowser')}</li>
            <li>{t('secret.riskLogs')}</li>
            <li>{t('secret.riskReveal', REVEAL_SECONDS)}</li>
          </ul>
        </AlertBanner>
        {infoState === 'error' && (
          <AlertBanner tone="danger" title={t('secret.infoError')} className="mb-0"
            actions={<Button type="button" variant="outline" size="sm" onClick={loadInfo} className="max-sm:h-10">
              <RefreshCw aria-hidden="true" /> {t('secret.retry')}
            </Button>}>
            {infoError || t('secret.unreachable')}
          </AlertBanner>
        )}
      </SettingsHeader>

      {/* 1 — Anahtar gir + doğrula; sonuç hemen altında */}
      <SettingsSection title={t('secret.verifyTitle')} description={t('secret.verifyDesc')} contentClassName="flex flex-col gap-4">
        <Field className="mb-0" hint={hint} hintTone={padded ? 'warn' : undefined}
          label={<><KeyRound size={13} aria-hidden="true" />{helpLabel(t('secret.keyLabel'), 'help.secret.key')}</>}>
          {({ id, describedBy }) => (
            <InputGroup className="max-sm:h-11">
              <InputGroupInput id={id} aria-describedby={describedBy} type={showKey ? 'text' : 'password'} value={key}
                autoComplete="new-password" data-1p-ignore data-lpignore="true" data-form-type="other"
                spellCheck={false} autoCapitalize="off" autoCorrect="off" translate="no"
                placeholder="SITE_MONITOR_SECRET_KEY" className="font-mono max-sm:h-10"
                onChange={(e) => setKey(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); verify(key) } }} />
              <InputGroupAddon align="inline-end">
                {key && (
                  <InputGroupButton size="icon-sm" className="max-sm:size-10" aria-label={t('secret.clearKey')} onClick={clearKey}>
                    <X aria-hidden="true" />
                  </InputGroupButton>
                )}
                <InputGroupButton size="icon-sm" className="max-sm:size-10" aria-pressed={showKey}
                  aria-label={showKey ? t('secret.hideKey') : t('secret.showKey')} onClick={() => setShowKey((v) => !v)}>
                  {showKey ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
                </InputGroupButton>
              </InputGroupAddon>
            </InputGroup>
          )}
        </Field>

        <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
          <Button type="button" onClick={() => verify(key)} disabled={busy || !key.trim()} aria-busy={busy || undefined}
            className="max-sm:h-10 max-sm:w-full">
            {busy ? <Spinner size={15} inline decorative /> : <ShieldCheck aria-hidden="true" />}
            {' '}{busy ? t('secret.verifying') : t('secret.verify')}
          </Button>
          {info?.dev_default_key && (
            <Button type="button" variant="outline" disabled={busy} className="max-sm:h-10 max-sm:w-full"
              onClick={() => verify(info.dev_default_key, { dev: true })}>
              {t('secret.devVerify')}
            </Button>
          )}
        </div>

        <OutcomeBanner outcome={outcome} t={t} />
      </SettingsSection>

      {/* 2 — Şifreli değerler: ad, kullanıldığı yer, kayıtlı mı, çözüldü mü; göster (30 sn) + kopyala */}
      <Card data-slot="secret-results">
        <CardHeader className="border-b border-border px-4 sm:px-6 [.border-b]:pb-4">
          <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div className="flex min-w-0 flex-col gap-1.5">
              <CardTitle role="heading" aria-level={4}>{t('secret.valuesTitle')}</CardTitle>
              <CardDescription>{t('secret.valuesDesc')}</CardDescription>
            </div>
            {rows?.length > 0 && (
              <div className="flex flex-wrap gap-2 sm:shrink-0 sm:justify-end">
                <Button type="button" variant="outline" size="sm" disabled={!anyRevealed} onClick={() => setRevealed({})}
                  className="max-sm:h-10 max-sm:flex-1">
                  <EyeOff aria-hidden="true" /> {t('secret.hideAll')}
                </Button>
                <Button type="button" variant="ghost" size="sm" onClick={clearResults} className="max-sm:h-10 max-sm:flex-1">
                  <Eraser aria-hidden="true" /> {t('secret.clearResults')}
                </Button>
              </div>
            )}
          </div>
        </CardHeader>
        <CardContent className="px-4 sm:px-6">
          {rows == null ? (
            <StatusBlock icon={KeyRound} title={t('secret.pendingTitle')} description={t('secret.pendingBody')} className="py-6 md:py-6" />
          ) : rows.length === 0 ? (
            <StatusBlock icon={DatabaseZap} title={t('secret.noneTitle')} description={t('secret.none')} className="py-6 md:py-6" />
          ) : (
            <ItemGroup aria-label={t('secret.valuesTitle')} className="grid grid-cols-1 gap-3 @3xl/secret:grid-cols-2">
              {rows.map((r) => (
                <SecretRow key={r.column} row={r} left={revealed[r.column]} onToggle={toggle} t={t} />
              ))}
            </ItemGroup>
          )}
        </CardContent>
      </Card>

      {/* 3 — Yardımcılar: güçlü anahtar üreteci + DEV varsayılan anahtarı */}
      <div className="grid grid-cols-1 items-start gap-5 @3xl/secret:grid-cols-2">
        <SettingsSection title={t('secret.genTitle')} description={t('secret.genDesc')} contentClassName="flex flex-col gap-3">
          <div>
            <Button type="button" variant="outline" onClick={() => setGenKey(generateKey())} className="max-sm:h-10 max-sm:w-full">
              <RefreshCw aria-hidden="true" /> {t('secret.gen')}
            </Button>
          </div>
          {genKey && (
            <div data-slot="secret-generated" className="flex min-w-0 flex-col gap-1.5">
              <span className="text-xs font-semibold text-muted-foreground">{t('secret.genLabel')}</span>
              <CopyableRef value={genKey} buttonClassName="max-sm:size-10" codeClassName="px-2 py-1 font-medium"
                copyLabel={t('a11y.rowAction', t('secret.copy'), t('secret.genLabel'))} copiedLabel={t('secret.copied')} />
              <p className="text-xs text-muted-foreground">{t('secret.genHint')}</p>
            </div>
          )}
        </SettingsSection>

        {info?.dev_default_key && (
          <SettingsSection title={t('secret.devTitle')} description={t('secret.devDesc')} contentClassName="flex flex-col gap-1.5">
            <span className="text-xs font-semibold text-muted-foreground">{t('secret.devKeyLabel')}</span>
            <CopyableRef value={info.dev_default_key} buttonClassName="max-sm:size-10" codeClassName="px-2 py-1 font-medium"
              copyLabel={t('a11y.rowAction', t('secret.copy'), t('secret.devKeyLabel'))} copiedLabel={t('secret.copied')} />
          </SettingsSection>
        )}
      </div>
    </div>
  )
}
