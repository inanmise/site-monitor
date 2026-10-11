import { useState } from 'react'
import { ArrowDown, ArrowUp, Eye, Plus, ShieldCheck, Trash2, TriangleAlert } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import Field from '../../ui/Field.jsx'
import { SettingsSection, ToggleRow } from '../SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'
import LdapRoleMappingPreview from './LdapRoleMappingPreview.jsx'
import {
  FALLBACK_DERIVED, MAX_RULES, NEW_ROW_ROLE, ROLES, cleanRules, isDnSpec, mappingKey, moveRow, ruleError,
} from './ldapRoleMappingModel.js'

/**
 * Ayarlar → LDAP → Grup → rol eşlemesi (2026-10-11, kullanıcı kararı: "açılırsa, elle değişeni ezmez").
 *
 * - Anahtar KAPALI (varsayılan): roller bugünkü kuralla türetilir, kurallar yalnız saklanır.
 * - Kural listesi SIRALIDIR (ilk eşleşen kazanır) — yukarı / aşağı düğmeleri; telefonda satır kart gibi sarar.
 * - "Eşleşme yoksa": bugünkü kural ya da sabit rol (sabit rol elle verilmiş ADMIN/AUDIT'i düşürmez).
 * - "Kim ne olacak?": formdaki (kaydedilmemiş) kurallarla sunucu önizlemesi; anahtar açılırken önizleme yoksa uyarı.
 *
 * `form` / `set` üst bileşenin (LdapSettings) form durumudur; kayıt üstte yapılır.
 */
export default function LdapRoleMappingSection({ form, set }) {
  const t = useT()
  const [preview, setPreview] = useState(null)        // { data, key } — hangi form hâlinin önizlemesi
  const [previewOpen, setPreviewOpen] = useState(false)
  const [previewing, setPreviewing] = useState(false)
  const [previewError, setPreviewError] = useState(null)
  const rows = form.role_mappings || []
  const enabled = !!form.role_mapping_enabled
  const fallback = form.role_mapping_fallback || FALLBACK_DERIVED
  const currentKey = mappingKey(form)
  const previewFresh = preview && preview.key === currentKey

  const setRows = (next) => set('role_mappings', next)
  const update = (i, key, value) => setRows(rows.map((m, idx) => (idx === i ? { ...m, [key]: value } : m)))

  async function runPreview() {
    setPreviewing(true); setPreviewError(null); setPreviewOpen(true)
    try {
      const res = await api.admin.previewLdapRoleMapping({ role_mappings: cleanRules(rows), role_mapping_fallback: fallback })
      if (res?.success) setPreview({ data: res.data, key: currentKey })
      else { setPreview(null); setPreviewError(res?.error || t('ldap.rm.previewFailed')) }
    } catch (e) {
      setPreview(null); setPreviewError(e?.message || t('ldap.rm.previewFailed'))
    } finally {
      setPreviewing(false)
    }
  }

  return (
    <SettingsSection title={t('ldap.roleMapping')} description={t('ldap.rm.desc')}>
      <div data-slot="ldap-role-mapping" className="flex flex-col gap-4">
        {/* Anahtar + durum */}
        <div className={cn('flex flex-col gap-2 rounded-lg border p-3 sm:p-4', enabled ? 'border-primary/40 bg-primary/5' : 'bg-muted/30')}>
          <ToggleRow major touch checked={enabled} onChange={(v) => set('role_mapping_enabled', v)} label={t('ldap.rm.enable')} />
          <p className="text-sm text-muted-foreground">{enabled ? t('ldap.rm.onHint') : t('ldap.rm.offHint')}</p>
          {enabled && !previewFresh && (
            <div data-slot="ldap-rm-needs-preview">
              <AlertBanner tone="warning" icon={TriangleAlert} className="mb-0">{t('ldap.rm.needsPreview')}</AlertBanner>
            </div>
          )}
        </div>

        {/* Kurallar — sıralı liste */}
        <div className="flex flex-col gap-2">
          <p className="text-sm text-muted-foreground">{t('ldap.rm.firstWins')}</p>
          {rows.length === 0 ? (
            <p className="rounded-lg border border-dashed p-4 text-center text-sm text-muted-foreground">{t('ldap.noMappings')}</p>
          ) : (
            <ol data-slot="ldap-rm-rules" className="flex flex-col gap-2" aria-label={t('ldap.roleMapping')}>
              {rows.map((m, i) => {
                const label = m.group?.trim() || String(i + 1)
                const err = ruleError(m)
                return (
                  <li key={i} data-slot="ldap-rm-rule" className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-start gap-2 rounded-lg border bg-card p-2.5 sm:grid-cols-[auto_minmax(0,1fr)_11rem_auto] sm:items-center">
                    <div className="flex flex-col items-center gap-0.5 sm:flex-row">
                      <span className="w-6 text-center text-xs font-semibold text-muted-foreground tabular-nums" aria-hidden="true">{i + 1}</span>
                      <div className="flex flex-col sm:flex-row">
                        <Button type="button" variant="ghost" size="icon-sm" className="pointer-coarse:size-10 max-sm:size-10"
                          disabled={i === 0} aria-label={t('a11y.rowAction', label, t('ldap.rm.moveUp'))}
                          onClick={() => setRows(moveRow(rows, i, -1))}><ArrowUp aria-hidden="true" /></Button>
                        <Button type="button" variant="ghost" size="icon-sm" className="pointer-coarse:size-10 max-sm:size-10"
                          disabled={i === rows.length - 1} aria-label={t('a11y.rowAction', label, t('ldap.rm.moveDown'))}
                          onClick={() => setRows(moveRow(rows, i, 1))}><ArrowDown aria-hidden="true" /></Button>
                      </div>
                    </div>
                    <div className="flex min-w-0 flex-col gap-1">
                      <Input type="text" value={m.group} placeholder={t('ldap.rm.groupPh')} maxLength={512}
                        aria-invalid={err ? true : undefined} className="max-sm:h-10 pointer-coarse:h-10"
                        aria-label={t('a11y.rowAction', String(i + 1), t('ldap.mapGroup'))}
                        onChange={(e) => update(i, 'group', e.target.value)} />
                      {m.group?.trim() && (
                        <span className="text-xs text-muted-foreground">{isDnSpec(m.group) ? t('ldap.rm.kindDn') : t('ldap.rm.kindCn')}</span>
                      )}
                      {err && <span className="text-xs text-destructive">{t(err)}</span>}
                    </div>
                    <div className="col-span-2 col-start-2 sm:col-span-1 sm:col-start-auto">
                      <NativeSelect value={(m.role || NEW_ROW_ROLE).toUpperCase()} className="w-full max-sm:h-10 pointer-coarse:h-10"
                        aria-label={t('a11y.rowAction', label, t('ldap.mapRole'))}
                        onChange={(e) => update(i, 'role', e.target.value)}>
                        {ROLES.map((r) => <NativeSelectOption key={r} value={r}>{t('ldap.rm.role.' + r)}</NativeSelectOption>)}
                      </NativeSelect>
                    </div>
                    <Button type="button" variant="ghost" size="icon-sm" className="row-start-1 col-start-3 text-destructive pointer-coarse:size-10 max-sm:size-10 sm:col-start-4"
                      aria-label={t('a11y.rowAction', label, t('ldap.removeMapping'))}
                      onClick={() => setRows(rows.filter((_, idx) => idx !== i))}><Trash2 aria-hidden="true" /></Button>
                  </li>
                )
              })}
            </ol>
          )}
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" variant="outline" className="max-sm:h-10" disabled={rows.length >= MAX_RULES}
              onClick={() => setRows([...rows, { group: '', role: NEW_ROW_ROLE }])}>
              <Plus aria-hidden="true" /> {t('ldap.addMapping')}
            </Button>
            <Button type="button" variant={enabled && !previewFresh ? 'default' : 'secondary'} className="max-sm:h-10"
              onClick={runPreview} disabled={previewing} aria-busy={previewing || undefined} data-slot="ldap-rm-preview-btn">
              <Eye aria-hidden="true" /> {t('ldap.rm.preview')}
            </Button>
            {previewFresh && (
              <span className="inline-flex items-center gap-1 text-xs font-medium text-success">
                <ShieldCheck aria-hidden="true" className="size-3.5" /> {t('ldap.rm.previewed')}
              </span>
            )}
          </div>
        </div>

        {/* Eşleşme yoksa */}
        <Field className="sm:max-w-xl" label={t('ldap.rm.fallback')} hint={t('ldap.rm.fallbackHint')}>
          {({ id, describedBy }) => (
            <NativeSelect id={id} aria-describedby={describedBy} value={fallback} className="w-full max-sm:h-10 pointer-coarse:h-10"
              onChange={(e) => set('role_mapping_fallback', e.target.value)}>
              <NativeSelectOption value={FALLBACK_DERIVED}>{t('ldap.rm.fallbackDerived')}</NativeSelectOption>
              {ROLES.map((r) => <NativeSelectOption key={r} value={r}>{t('ldap.rm.role.' + r)}</NativeSelectOption>)}
            </NativeSelect>
          )}
        </Field>
        <p className="text-xs text-muted-foreground">{t('ldap.rm.adminNote')}</p>
      </div>

      <LdapRoleMappingPreview open={previewOpen} onClose={() => setPreviewOpen(false)}
        loading={previewing} error={previewError} data={previewFresh ? preview.data : null} onRetry={runPreview} />
    </SettingsSection>
  )
}
