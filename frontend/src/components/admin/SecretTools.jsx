import { useState, useEffect } from 'react'
import { Eye, EyeOff, KeyRound, RefreshCw, Copy, LockKeyhole } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { SETTINGS_STACK, helpLabel, SettingsHeader, SettingsSection } from './SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'

/** Tarayıcıda kriptografik olarak güçlü rastgele anahtar üretir (32 bayt → base64). */
function generateKey() {
  const bytes = new Uint8Array(32)
  crypto.getRandomValues(bytes)
  let bin = ''
  for (const b of bytes) bin += String.fromCharCode(b)
  return btoa(bin)
}

/**
 * Anahtar Çözümleme (yalnız admin) — verilen aday SITE_MONITOR_SECRET_KEY ile DB'de şifreli
 * duran alanları (SMTP/LDAP parolaları) çözüp gösterir. Doğru anahtarda plaintext, yanlışta
 * "başarısız". Plaintext varsayılan gizli; göz ikonuyla açılır.
 */
export default function SecretTools() {
  const t = useT()
  const toast = useToast()
  const [key, setKey] = useState('')
  const [rows, setRows] = useState(null)
  const [busy, setBusy] = useState(false)
  const [reveal, setReveal] = useState({})
  const [info, setInfo] = useState(null) // { dev_default_key, secret_key_set }
  const [usedKey, setUsedKey] = useState('') // son çözümlemede kullanılan anahtar (neyle çözüldü)
  const [genKey, setGenKey] = useState('')   // üretilen aday SITE_MONITOR_SECRET_KEY

  async function copyGen() {
    if (!genKey) return
    try { await navigator.clipboard.writeText(genKey); toast.success(t('secret.copied')) }
    catch { /* clipboard izni yoksa kullanıcı elle kopyalar */ }
  }

  useEffect(() => {
    api.admin.secretToolsInfo().then((res) => { if (res?.success) setInfo(res) }).catch(() => {})
  }, [])

  async function run(useKey) {
    const k = (useKey ?? key).trim()
    if (!k) return
    setBusy(true)
    try {
      try {
        const res = await api.admin.decryptSecrets(k)
        if (res?.success) { setRows(res.data || []); setReveal({}); setUsedKey(k) }
        else toast.error(res?.error || t('settings.saveError'))
      } catch {
        toast.error(t('settings.saveError'))
      }
    } finally {
      setBusy(false)
    }
  }

  const MONO = 'font-mono'
  return (
    <div className={SETTINGS_STACK} data-testid="secret-tools">
      <SettingsHeader icon={LockKeyhole} title={t('secret.title')} description={t('secret.desc')}>
        <AlertBanner tone="warning" className="mb-0">{t('secret.warn')}</AlertBanner>
      </SettingsHeader>

      {/* Çözümleme + anahtar üreteci geniş ekranda yan yana */}
      <div className="grid grid-cols-1 items-start gap-5 xl:grid-cols-2">
        <SettingsSection>
          <Field label={<><KeyRound size={13} aria-hidden="true" />{helpLabel(t('secret.keyLabel'), 'help.secret.key')}</>}
            hint={t('secret.keyHint')}>
            {({ id, describedBy }) => (
              <Input id={id} aria-describedby={describedBy} type="password" value={key} autoComplete="off"
                placeholder="SITE_MONITOR_SECRET_KEY" className={MONO}
                onChange={(e) => setKey(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') run() }} />
            )}
          </Field>
          <div className="flex flex-wrap items-center gap-3">
            <Button onClick={() => run()} disabled={busy || !key.trim()} aria-busy={busy || undefined}>
              {busy ? <Spinner size={15} inline decorative /> : null} {busy ? t('secret.decrypting') : t('secret.decrypt')}
            </Button>
          </div>
        </SettingsSection>

        {/* Güçlü anahtar üreteci — SITE_MONITOR_SECRET_KEY için değer üret (tarayıcıda, sunucuya gitmez) */}
        <SettingsSection title={t('secret.genTitle')} description={t('secret.genDesc')} contentClassName="flex flex-col gap-3">
          <div>
            <Button onClick={() => setGenKey(generateKey())}>
              <RefreshCw size={14} /> {t('secret.gen')}
            </Button>
          </div>
          {genKey && (
            <Field label={t('secret.genLabel')} hint={t('secret.genHint')} className="mb-0">
              {({ id, describedBy }) => (
                <InputGroup>
                  <InputGroupInput id={id} aria-describedby={describedBy} type="text" readOnly value={genKey} className={MONO}
                    onFocus={(e) => e.target.select()} />
                  <InputGroupAddon align="inline-end">
                    {/* InputGroupButton ref almaz (forwardRef değil) → ipucu tetiği sarmalayıcı span */}
                    <SimpleTooltip content={t('secret.copy')}>
                      <span className="inline-flex">
                        <InputGroupButton size="icon-xs" onClick={copyGen} aria-label={t('secret.copy')}><Copy /></InputGroupButton>
                      </span>
                    </SimpleTooltip>
                  </InputGroupAddon>
                </InputGroup>
              )}
            </Field>
          )}
        </SettingsSection>
      </div>

      {/* DEV varsayılan anahtarı — kayıtlı parolalar bununla mı şifrelenmiş test et */}
      {info?.dev_default_key && (
        <SettingsSection title={t('secret.devTitle')} description={t('secret.devDesc')} contentClassName="flex flex-col gap-3">
          <Field label={t('secret.devKeyLabel')} className="mb-0 xl:max-w-2xl">
            {({ id }) => (
              <Input id={id} type="text" readOnly value={info.dev_default_key} className={MONO}
                onFocus={(e) => e.target.select()} />
            )}
          </Field>
          <div>
            <Button variant="secondary" disabled={busy}
              onClick={() => { setKey(info.dev_default_key); run(info.dev_default_key) }}>
              {t('secret.devTry')}
            </Button>
          </div>
        </SettingsSection>
      )}

      {rows && (
        <SettingsSection title={t('secret.results')} contentClassName="flex flex-col gap-3.5">
          {usedKey && (
            <p className="text-sm break-all text-muted-foreground">
              {t('secret.usedKey')}: <code className="font-mono font-bold text-foreground">{usedKey}</code>
              {info?.dev_default_key && usedKey === info.dev_default_key && <> — {t('secret.usedKeyDev')}</>}
            </p>
          )}
          {rows.length === 0 && <p className="text-sm text-muted-foreground">{t('secret.none')}</p>}
          <div className="grid grid-cols-1 gap-x-6 xl:grid-cols-2">
            {rows.map((r) => (
              <Field key={r.column} className="mb-3"
                label={<>{r.label} <code className="font-mono text-xs font-normal text-muted-foreground">{r.column}</code></>}>
                {({ id }) => !r.present ? (
                  <span className="text-xs text-muted-foreground">{t('secret.noValue')}</span>
                ) : r.ok ? (
                  <InputGroup>
                    <InputGroupInput id={id} type={reveal[r.column] ? 'text' : 'password'} readOnly value={r.value ?? ''} className={MONO} />
                    <InputGroupAddon align="inline-end">
                      <InputGroupButton size="icon-xs" aria-pressed={!!reveal[r.column]}
                        aria-label={t('a11y.rowAction', reveal[r.column] ? t('secret.hide') : t('secret.show'), r.label)}
                        onClick={() => setReveal((p) => ({ ...p, [r.column]: !p[r.column] }))}>
                        {reveal[r.column] ? <EyeOff /> : <Eye />}
                      </InputGroupButton>
                    </InputGroupAddon>
                  </InputGroup>
                ) : (
                  <span className="text-sm font-semibold text-destructive" data-tone="danger">{t('secret.fail')}</span>
                )}
              </Field>
            ))}
          </div>
        </SettingsSection>
      )}
    </div>
  )
}
