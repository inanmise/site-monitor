import { useState, useEffect } from 'react'
import { Search, Plus, Trash2, KeyRound } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import SecretKeyWarning from './SecretKeyWarning.jsx'
import { Spinner, LoadingBlock } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import HelpTip from '../ui/HelpTip.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import Field from '../ui/Field.jsx'
import ToneBadge from './ToneBadge.jsx'
import {
  FIELD_GRID as GRID, FIELD_GRID_3, SETTINGS_STACK, helpLabel, MasterToggleCard, SettingsHeader, SettingsSaveBar,
  SettingsSection, TestResult, ToggleRow,
} from './SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'

// Role values match AppUser.systemRole tokens (used when provisioning is wired in a later phase).
const ROLES = ['ADMIN', 'TEAM_ADMIN', 'USER', 'AUDIT']

/**
 * LDAP / Active Directory ayarları. Tam sayfa yeniden tasarım (2026-09-27): SettingsHeader + ana anahtar +
 * konu kartları (bağlantı / kullanıcı arama / grup / rol eşleme / dizin sorgusu) + alt kayıt çubuğu
 * (kirli durum kaydedilmiş anlık görüntüden; bind parolası kutusuna yazılan her şey kirli sayılır).
 */
export default function LdapSettings() {
  const t = useT()
  const toast = useToast()

  const [form, setForm] = useState(null)
  const [loaded, setLoaded] = useState(null)
  const [loadError, setLoadError] = useState(null)
  const [bindPw, setBindPw] = useState('')
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState(null)

  const [queryName, setQueryName] = useState('')
  const [queryAttr, setQueryAttr] = useState('') // '' = configured userAttribute
  const [querying, setQuerying] = useState(false)
  const [queryResult, setQueryResult] = useState(null)
  const [secretKeySet, setSecretKeySet] = useState(true)

  useEffect(() => { load() }, [])

  const normalise = (d) => ({ ...d, role_mappings: d.role_mappings || [] })

  async function load() {
    // AG HATASI DA GORUNUR OLMALI: api/client.js request() ag hatasinda {success:false}
    // DONDURMEZ, throw eder. try/catch olmadan promise reject oluyor ve ekran sonsuza
    // kadar yukleniyor durumunda kaliyordu (yalnizca konsolda unhandled rejection).
    try {
      const res = await api.admin.getLdapSettings()
      if (res?.success) {
        const d = normalise(res.data)
        setForm(d); setLoaded(d)
        setSecretKeySet(res.secret_key_set !== false)
        setLoadError(null)
      } else {
        const msg = res?.error || t('settings.loadError')
        toast.error(msg); setLoadError(msg)
      }
    } catch (e) {
      setLoadError(e?.message || t('settings.loadError'))
    }
  }

  function set(key, value) {
    setForm((f) => ({ ...f, [key]: value }))
  }

  // ── role mappings ──────────────────────────────────────────────────────────
  function addMapping() {
    set('role_mappings', [...(form.role_mappings || []), { group: '', role: 'ADMIN' }])
  }
  function updateMapping(i, key, value) {
    const next = form.role_mappings.map((m, idx) => (idx === i ? { ...m, [key]: value } : m))
    set('role_mappings', next)
  }
  function removeMapping(i) {
    set('role_mappings', form.role_mappings.filter((_, idx) => idx !== i))
  }

  // ── actions ────────────────────────────────────────────────────────────────
  async function save() {
    setSaving(true)
    try {
      const dto = { ...form }
      if (bindPw.trim()) dto.bind_password = bindPw
      const res = await api.admin.saveLdapSettings(dto)
      if (res?.success) {
        toast.success(res.message || t('settings.saved'))
        setBindPw('')
        const d = normalise(res.data)
        setForm(d); setLoaded(d)
      } else {
        toast.error(res?.error || t('settings.saveError'))
      }
    } finally {
      setSaving(false)
    }
  }

  /** Vazgeç: kaydedilmiş hâle dön (bind parolası kutusu da boşalır). */
  function discard() {
    if (loaded) setForm({ ...loaded })
    setBindPw('')
  }

  // verify=true → kayıtlı "doğrulamayı atla" ayarı DEĞİŞMEDEN sertifika doğrulaması açık denenir;
  // yeşil dönerse ayar güvenle kapatılabilir (aksi halde tüm LDAP girişleri kırılırdı).
  async function test(verify = false) {
    setTesting(true)
    try {
      setTestResult(null)
      const res = await api.admin.testLdap(verify)
      setTestResult(res)
      if (res?.success) toast.success(res.message || t('ldap.testOk'))
      else toast.error(res?.error || t('ldap.testFail'))
    } finally {
      setTesting(false)
    }
  }

  async function runQuery() {
    if (!queryName.trim()) return
    setQuerying(true)
    try {
      setQueryResult(null)
      const res = await api.admin.queryLdapUser(queryName.trim(), queryAttr || null)
      setQueryResult(res)
    } finally {
      setQuerying(false)
    }
  }

  if (!form) {
    // Yukleme BASARISIZ olduysa spinner sonsuza kadar donerdi: load() try/catch tasimadigi
    // icin ag hatasinda promise reject oluyor, hicbir durum guncellenmiyordu. Artik ayni
    // yerde hatanin KENDISI gosteriliyor (SystemHealth.jsx:163 loadErrors deseninin esdegeri).
    if (loadError) {
      return <AlertBanner tone="danger" title={t('settings.loadError')} role="alert">{String(loadError)}</AlertBanner>
    }
    return <LoadingBlock label={t('settings.loading')} className="justify-start px-0 py-6" />
  }

  const dirty = bindPw !== '' || (loaded != null && JSON.stringify(form) !== JSON.stringify(loaded))

  // Metin alanı (Field + Input) — etiket yardım balonlu, ipucu aria-describedby ile bağlı.
  const textField = (key, labelKey, helpKey, placeholder, hintKey) => (
    <Field label={helpLabel(t(labelKey), helpKey)} hint={hintKey ? t(hintKey) : undefined}>
      {({ id, describedBy }) => (
        <Input id={id} aria-describedby={describedBy} type="text" value={form[key] || ''} placeholder={placeholder}
          onChange={(e) => set(key, e.target.value)} />
      )}
    </Field>
  )

  return (
    <div className={SETTINGS_STACK} data-testid="ldap-settings">
      <SettingsHeader icon={KeyRound} title={t('ldap.title')} description={t('ldap.desc')}
        hint={form.updated_at ? t('ldap.lastUpdated', form.updated_at, form.updated_by || '—') : undefined}
        meta={<>
          <ToneBadge tone={form.enabled ? 'success' : 'muted'} className="font-semibold">{form.enabled ? t('general.on') : t('general.off')}</ToneBadge>
          {form.host && <Badge variant="outline" className="max-w-full font-mono font-normal break-all whitespace-normal">{form.host}{form.port ? `:${form.port}` : ''}</Badge>}
        </>}>
        {!secretKeySet && <SecretKeyWarning />}
      </SettingsHeader>

      {/* Enable */}
      <MasterToggleCard checked={!!form.enabled} onChange={(v) => set('enabled', v)}
        label={t('ldap.enable')} helpKey="help.ldap.enable" hint={t('ldap.enableHint')} />

      {/* Connection */}
      <SettingsSection title={t('ldap.connection')}>
        <div className={GRID}>
          {textField('host', 'ldap.host', 'help.ldap.host', 'ldap.corp.example.com')}
          <Field label={helpLabel(t('ldap.port'), 'help.ldap.port')} hint={t('ldap.portHint')}>
            {({ id, describedBy }) => (
              <Input id={id} aria-describedby={describedBy} type="number" value={form.port ?? ''} placeholder="636"
                onChange={(e) => set('port', e.target.value === '' ? null : +e.target.value)} />
            )}
          </Field>
        </div>
        <div className="my-3 flex flex-wrap gap-x-6 gap-y-2.5">
          <ToggleRow checked={!!form.use_ldaps} onChange={(v) => set('use_ldaps', v)}
            label={t('ldap.useLdaps')} helpKey="help.ldap.useLdaps" />
          <ToggleRow checked={!!form.start_tls} onChange={(v) => set('start_tls', v)}
            label={t('ldap.startTls')} helpKey="help.ldap.startTls" />
          <ToggleRow checked={!!form.skip_cert_verification} onChange={(v) => set('skip_cert_verification', v)}
            label={t('ldap.skipCert')} helpKey="help.ldap.skipCert" />
        </div>
        {/* Trust-all açıkken zincir+hostname hiç doğrulanmaz; bind ve kullanıcı parolaları MITM'e açık. */}
        {form.skip_cert_verification && (
          <AlertBanner tone="warning">
            {t('ldap.skipCertWarn')}
            {form.ca_cert_pem ? ' ' + t('ldap.skipCertPemIgnored') : ''}
          </AlertBanner>
        )}
        <Field label={helpLabel(t('ldap.caCert'), 'help.ldap.caCert')} hint={t('ldap.caCertHint')}>
          {({ id, describedBy }) => (
            <Textarea id={id} aria-describedby={describedBy} rows={4} className="min-h-0 resize-y font-mono"
              value={form.ca_cert_pem || ''} placeholder="-----BEGIN CERTIFICATE-----"
              onChange={(e) => set('ca_cert_pem', e.target.value)} />
          )}
        </Field>
        <div className={GRID}>
          {textField('bind_dn', 'ldap.bindDn', 'help.ldap.bindDn', 'CN=svc,OU=Service Accounts,DC=corp,DC=com', 'ldap.bindDnHint')}
          <Field label={helpLabel(t('ldap.bindPassword'), 'help.ldap.bindPassword')} hint={t('ldap.bindPwHint')}>
            {({ id, describedBy }) => (
              <Input id={id} aria-describedby={describedBy} type="password" value={bindPw} autoComplete="new-password"
                placeholder={form.bind_password_set ? t('ldap.bindPwSet') : t('ldap.bindPwEmpty')}
                onChange={(e) => setBindPw(e.target.value)} />
            )}
          </Field>
        </div>
      </SettingsSection>

      {/* User search */}
      <SettingsSection title={t('ldap.userSearch')}>
        <div className={GRID}>
          {textField('base_dn', 'ldap.baseDn', 'help.ldap.baseDn', 'DC=corp,DC=com', 'ldap.baseDnHint')}
          {textField('user_search_filter', 'ldap.userFilter', 'help.ldap.userFilter', '(objectclass=person)', 'ldap.userFilterHint')}
        </div>
        <div className={FIELD_GRID_3}>
          {textField('user_attribute', 'ldap.userAttr', 'help.ldap.userAttr', 'sAMAccountName')}
          {textField('email_attribute', 'ldap.emailAttr', 'help.ldap.emailAttr', 'mail')}
          {textField('display_attribute', 'ldap.displayAttr', 'help.ldap.displayAttr', 'displayName')}
        </div>
      </SettingsSection>

      {/* Group lookup */}
      <SettingsSection title={t('ldap.groupLookup')} description={t('ldap.groupLookupDesc')}>
        <div className={GRID}>
          {textField('group_search_base', 'ldap.groupSearchBase', 'help.ldap.groupSearchBase', 'OU=Groups,DC=corp,DC=com')}
          {textField('group_filter', 'ldap.groupFilter', 'help.ldap.groupFilter', '(objectclass=group)')}
        </div>
        <ToggleRow checked={!!form.skip_member_of} onChange={(v) => set('skip_member_of', v)}
          label={t('ldap.skipMemberOf')} helpKey="help.ldap.skipMemberOf" />
        <p className="mt-1.5 text-xs text-muted-foreground">{t('ldap.skipMemberOfHint')}</p>
      </SettingsSection>

      {/* Group → role mapping */}
      <SettingsSection title={t('ldap.roleMapping')} description={t('ldap.roleMappingDesc')}>
        {/* Bu bölümün İKİ kontrolü de (eşlemeler ve varsayılan rol) kaydediliyor ama giriş
            yolunda HİÇ okunmuyor: rol dizin niteliklerinden türetiliyor
            (LdapProvisioningService — yönetici/ürün sahibi → TEAM_ADMIN, diğerleri → USER).
            Eski yardım metni "hiçbir grup eşleşmediğinde uygulanır" diyordu; bu YANLIŞ bir
            vaatti ve güvenlik açısından yanıltıcıydı: "Varsayılan rol: ADMIN" seçen bir
            yönetici, eşleşmeyen herkesin admin olduğunu sanabilirdi. */}
        <AlertBanner tone="warning" title={t('ldap.roleMappingInactiveTitle')}>
          {t('ldap.roleMappingInactive')}
        </AlertBanner>
        <div className="overflow-hidden rounded-lg border border-border">
          <Table>
            <TableHeader className="bg-muted/50">
              <TableRow>
                <TableHead>{t('ldap.mapGroup')}<HelpTip helpKey="help.ldap.mapGroup" label={t('ldap.mapGroup')} /></TableHead>
                <TableHead className="w-[180px]">{t('ldap.mapRole')}<HelpTip helpKey="help.ldap.mapRole" label={t('ldap.mapRole')} /></TableHead>
                <TableHead className="w-12"><span className="sr-only">{t('ldap.removeMapping')}</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(form.role_mappings || []).length === 0 && (
                <TableRow><TableCell colSpan={3} className="p-3.5 text-center text-muted-foreground">{t('ldap.noMappings')}</TableCell></TableRow>
              )}
              {(form.role_mappings || []).map((m, i) => {
                const rowName = m.group || String(i + 1)
                return (
                  <TableRow key={i}>
                    <TableCell>
                      <Input type="text" value={m.group} placeholder="CN=CertAdmins"
                        aria-label={t('a11y.rowAction', String(i + 1), t('ldap.mapGroup'))}
                        onChange={(e) => updateMapping(i, 'group', e.target.value)} />
                    </TableCell>
                    <TableCell>
                      <NativeSelect value={m.role} className="min-w-[150px]"
                        aria-label={t('a11y.rowAction', rowName, t('ldap.mapRole'))}
                        onChange={(e) => updateMapping(i, 'role', e.target.value)}>
                        {ROLES.map((r) => <NativeSelectOption key={r} value={r}>{r}</NativeSelectOption>)}
                      </NativeSelect>
                    </TableCell>
                    <TableCell>
                      <SimpleTooltip content={t('ldap.removeMapping')}>
                        <Button variant="ghost" size="icon-sm" className="text-destructive" onClick={() => removeMapping(i)}
                          aria-label={t('a11y.rowAction', rowName, t('ldap.removeMapping'))}>
                          <Trash2 size={15} />
                        </Button>
                      </SimpleTooltip>
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </div>
        <Button variant="secondary" className="mt-2.5" onClick={addMapping}>
          <Plus size={14} /> {t('ldap.addMapping')}
        </Button>
        <Field className="mt-3.5 sm:max-w-xs" label={helpLabel(t('ldap.defaultRole'), 'help.ldap.defaultRole')}
          hint={t('ldap.defaultRoleHint')}>
          {({ id, describedBy }) => (
            <NativeSelect id={id} aria-describedby={describedBy} value={form.default_role || 'ADMIN'}
              className="min-w-[200px]" onChange={(e) => set('default_role', e.target.value)}>
              {ROLES.map((r) => <NativeSelectOption key={r} value={r}>{r}</NativeSelectOption>)}
            </NativeSelect>
          )}
        </Field>
      </SettingsSection>

      {/* Directory user lookup — what AD returns for a username */}
      <SettingsSection title={t('ldap.lookupTitle')} description={t('ldap.lookupDesc')}>
        <div className="flex flex-col gap-2.5 sm:max-w-3xl sm:flex-row sm:items-stretch">
          <NativeSelect value={queryAttr} className="w-full sm:w-[190px]" aria-label={t('ldap.lookupAttr')}
            onChange={(e) => setQueryAttr(e.target.value)}>
            <NativeSelectOption value="">{t('ldap.searchByDefault')}</NativeSelectOption>
            <NativeSelectOption value="sAMAccountName">sAMAccountName</NativeSelectOption>
            <NativeSelectOption value="mail">{t('ldap.searchByMail')}</NativeSelectOption>
            <NativeSelectOption value="cn">cn</NativeSelectOption>
            <NativeSelectOption value="displayName">{t('ldap.searchByDisplay')}</NativeSelectOption>
            <NativeSelectOption value="userPrincipalName">userPrincipalName</NativeSelectOption>
            <NativeSelectOption value="memberOf">{t('ldap.searchByMemberOf')}</NativeSelectOption>
            <NativeSelectOption value="_raw_">{t('ldap.searchByRaw')}</NativeSelectOption>
          </NativeSelect>
          <Input type="text" value={queryName} aria-label={t('ldap.lookupQuery')} className="min-w-0 flex-1"
            placeholder={queryAttr === '_raw_' ? t('ldap.lookupRawPlaceholder')
              : queryAttr === 'memberOf' ? t('ldap.lookupMemberOfPlaceholder')
              : t('ldap.lookupPlaceholder')}
            onChange={(e) => setQueryName(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') runQuery() }} />
          <Button className="shrink-0" onClick={runQuery} disabled={querying || !queryName.trim()} aria-busy={querying || undefined}>
            {querying ? <Spinner size={15} inline decorative /> : <Search size={15} />} {t('ldap.lookupBtn')}
          </Button>
        </div>

        {queryResult && <LookupResult result={queryResult} t={t} />}
      </SettingsSection>

      {/* Save / Test — kirliyken alta yapışır */}
      <SettingsSaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={discard}
        result={testResult && (
          <TestResult ok={!!testResult.success}>
            {testResult.success ? (testResult.message || t('ldap.testOk')) : (testResult.error || t('ldap.testFail'))}
          </TestResult>
        )}>
        <Button type="button" variant="outline" onClick={() => test(false)} disabled={testing} aria-busy={testing || undefined}>
          {testing ? <Spinner size={15} inline decorative /> : null} {t('ldap.testConnection')}
        </Button>
        {/* Ayarı kapatmadan önce doğrulamalı deneme — yeşilse "atla" güvenle kapatılabilir. */}
        {form.skip_cert_verification && (
          <Button type="button" variant="outline" onClick={() => test(true)} disabled={testing}
            title={t('ldap.testVerifiedHint')}>
            {testing ? <Spinner size={15} inline decorative /> : null} {t('ldap.testVerified')}
          </Button>
        )}
      </SettingsSaveBar>
    </div>
  )
}

/** Sonuç tablosu kabı — kenarlıklı, köşeli kutu; kaydırma shadcn Table kabında. */
const TABLE_BOX = 'overflow-hidden rounded-lg border border-border'
const MONO = 'font-mono text-[0.92em]'

function LookupResult({ result, t }) {
  if (!result.success) {
    return <AlertBanner tone="danger" className="mt-4">{result.error || t('ldap.lookupError')}</AlertBanner>
  }
  const data = result.data || {}
  if (!data.found) {
    return <AlertBanner tone="info" className="mt-4">{t('ldap.lookupNotFound')}{data.filter ? ` (${data.filter})` : ''}</AlertBanner>
  }
  // Multiple matches (e.g. memberOf group-membership search) → compact member list.
  if ((data.count ?? (data.matches?.length ?? 0)) > 1) {
    return (
      <div className="mt-4">
        <div className="mb-2.5 text-sm break-all">{t('ldap.matchCount', data.count)}</div>
        <div className={TABLE_BOX}>
          <Table>
            <TableHeader className="bg-muted/50">
              <TableRow><TableHead>sAMAccountName</TableHead><TableHead>{t('ldap.colDisplay')}</TableHead><TableHead>mail</TableHead><TableHead>DN</TableHead></TableRow>
            </TableHeader>
            <TableBody>
              {data.matches.map((m, i) => (
                <TableRow key={i}>
                  <TableCell className={`${MONO} font-bold`}>{m.username || '—'}</TableCell>
                  <TableCell>{m.displayName || '—'}</TableCell>
                  <TableCell>{m.email || '—'}</TableCell>
                  <TableCell className="whitespace-normal break-words"><code className={MONO}>{m.dn}</code></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </div>
    )
  }
  const attrs = data.attributes || {}
  const keys = Object.keys(attrs).sort((a, b) => a.localeCompare(b))
  return (
    <div className="mt-4">
      <div className="mb-2.5 text-sm break-all"><strong>DN:</strong> <code className={MONO}>{data.dn}</code></div>
      <div className={TABLE_BOX}>
        <Table>
          <TableHeader className="bg-muted/50">
            <TableRow><TableHead>{t('ldap.attribute')}</TableHead><TableHead>{t('ldap.value')}</TableHead></TableRow>
          </TableHeader>
          <TableBody>
            {keys.map((k) => (
              <TableRow key={k}>
                <TableCell className={`${MONO} align-top font-bold`}>{k}</TableCell>
                <TableCell className="align-top whitespace-normal break-words">{renderVal(attrs[k])}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {Array.isArray(data.groups) && data.groups.length > 0 && (
        <div className="mt-3.5 text-sm">
          <strong>{t('ldap.groups')} ({data.groups.length})</strong>
          <ul className="mt-1.5 list-disc pl-[18px]">
            {data.groups.map((g, i) => <li key={i} className="my-0.5 break-all"><code className={MONO}>{g}</code></li>)}
          </ul>
        </div>
      )}
    </div>
  )
}

function renderVal(v) {
  if (Array.isArray(v)) {
    return <ul className="list-disc pl-[18px]">{v.map((x, i) => <li key={i} className="my-px">{String(x)}</li>)}</ul>
  }
  return <span>{String(v)}</span>
}
