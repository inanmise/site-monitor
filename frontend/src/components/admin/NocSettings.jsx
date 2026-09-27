import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { Headset, Plus, RefreshCw } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import Field from '../ui/Field.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import ToneBadge from './ToneBadge.jsx'
import NocGroupModal from './NocGroupModal.jsx'
import NocGroupCard from '../noc/NocGroupCard.jsx'
import { NOC_TYPE_ICON, NocSwitchRow, typeLabel } from '../noc/nocUi.jsx'
import {
  MAX_INSTRUCTIONS, NOC_LEVELS, NOC_TYPES, configBody, normalizeConfig, sortGroups, testOutcome, unwrap,
} from '../noc/nocModel.js'
import { SETTINGS_STACK, helpLabel, SettingsHeader, SettingsSaveBar, SettingsSection } from './SettingsControls.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Switch } from '@/components/shadcn/switch'
import { Textarea } from '@/components/shadcn/textarea'
import { cn } from '@/lib/utils'

/**
 * Ayarlar → Bildirimler → **7/24 İzleme Ekibi** (`?sec=noc`, 2026-09-27; `.migration/noc/CONTRACT.md`).
 *
 * <p>Kurumsal 7/24 izleme ekibi (NOC) gece kesintisinde ilgili takımı TELEFONLA arar. Burada:
 *  • <b>Gruplar</b> — GLOBAL (takıma bağlı değil) ad + e-posta listesi; aktif / varsayılan. Kartlar; ekle/düzenle
 *    penceresi (çoklu e-posta çip girişi), test e-postası, silme onayı (seçen izleme sayısı söylenir). Grup işlemleri
 *    ANINDA yazılır (Platformlar bölümüyle aynı desen).
 *  • <b>Tür anahtarları</b> — 10 tür; kapalı tür o türdeki TÜM izlemelerin 7/24 e-postasını durdurur.
 *  • <b>Gönderim kuralları</b> — en düşük seviye (varsayılan Kritik), "çözüldü" e-postası, arama talimatı (düz metin).
 * Tür/kural alanları tek kayıt çubuğuyla yazılır (kirli izleme + Vazgeç — SMTP/Genel deseni); bir türü KAPATAN kayıt
 * önce onay ister (etkisi geniş).
 *
 * <p>`readOnly` (kapsamlı müdür, globalAdmin=false): her şey görünür ama kilitli; e-posta adresleri HİÇ çizilmez
 * (sunucu 403 ya da maskeli döner — ikisinde de ekran düşmez). Yardım metinleri HelpTip'te (settings-help-coverage).
 */
export default function NocSettings({ readOnly = false }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const typeIdBase = useId()

  const [groups, setGroups] = useState(null)
  const [groupsError, setGroupsError] = useState(null)
  const [form, setForm] = useState(null)        // yapılandırma formu
  const [loaded, setLoaded] = useState(null)    // kaydedilmiş anlık görüntü (kirli durum bundan)
  const [cfgError, setCfgError] = useState(null)
  const [saving, setSaving] = useState(false)
  const [modal, setModal] = useState(null)      // { group: null (yeni) | grup } | null
  const [testingId, setTestingId] = useState(null)
  const groupsSeq = useRef(0)
  const cfgSeq = useRef(0)
  const alive = useRef(true)

  async function loadGroups() {
    const id = ++groupsSeq.current
    try {
      const r = unwrap(await api.admin.noc.listGroups())
      if (!alive.current || id !== groupsSeq.current) return
      if (r.ok) { setGroups(Array.isArray(r.data) ? r.data : []); setGroupsError(null) }
      else setGroupsError(r.error || t('noc.loadError'))
    } catch (e) {
      if (alive.current && id === groupsSeq.current) setGroupsError(e?.message || t('noc.loadError'))
    }
  }
  async function loadConfig() {
    const id = ++cfgSeq.current
    try {
      const r = unwrap(await api.admin.noc.getConfig())
      if (!alive.current || id !== cfgSeq.current) return
      if (r.ok) { const c = normalizeConfig(r.data); setForm(c); setLoaded(c); setCfgError(null) }
      else setCfgError(r.error || t('noc.loadError'))
    } catch (e) {
      if (alive.current && id === cfgSeq.current) setCfgError(e?.message || t('noc.loadError'))
    }
  }
  useEffect(() => {
    alive.current = true
    loadGroups(); loadConfig()
    return () => { alive.current = false }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const sorted = useMemo(() => sortGroups(groups || []), [groups])
  const activeCount = sorted.filter((g) => g.active).length
  const dirty = !!form && !!loaded && JSON.stringify(form) !== JSON.stringify(loaded)
  const offTypes = form ? NOC_TYPES.filter((k) => !form.enabled_types[k]) : []

  const setType = (k, on) => setForm((f) => ({ ...f, enabled_types: { ...f.enabled_types, [k]: on } }))
  const setAllTypes = (on) => setForm((f) => ({ ...f, enabled_types: Object.fromEntries(NOC_TYPES.map((k) => [k, on])) }))

  async function saveConfig() {
    if (!form) return
    // Kayıt YENİ bir türü kapatıyorsa önce onay: etkisi o türdeki TÜM izlemeler (izleme "bildir" dese bile).
    const newlyOff = NOC_TYPES.filter((k) => loaded?.enabled_types[k] && !form.enabled_types[k])
    if (newlyOff.length) {
      const ok = await showConfirm({
        title: t('noc.cfgOffConfirmTitle'), variant: 'warning', confirmText: t('noc.cfgOffConfirmBtn'),
        message: t('noc.cfgOffConfirmMsg', newlyOff.map((k) => typeLabel(t, k)).join(', ')),
      })
      if (!ok) return
    }
    setSaving(true)
    try {
      const r = unwrap(await api.admin.noc.saveConfig(configBody(form)))
      if (!r.ok) { toast.error(r.error || t('settings.saveError')); return }
      const next = r.data && typeof r.data === 'object' && 'enabled_types' in r.data ? normalizeConfig(r.data) : form
      setForm(next); setLoaded(next)
      toast.success(t('noc.cfgSaved'))
    } catch (e) {
      toast.error(e?.message || t('settings.saveError'))
    } finally {
      setSaving(false)
    }
  }

  async function removeGroup(g) {
    // Silinince yalnız bu grubu AÇIKÇA seçmiş izlemeler varsayılana düşer (sunucu `explicit_monitor_count`);
    // `monitor_count` varsayılan üzerinden kullananları da sayar. Alan yoksa (eski yanıt) toplam sayı.
    const n = Number(g.explicit_monitor_count ?? g.monitor_count) || 0
    const ok = await showConfirm({
      title: t('noc.gDeleteTitle'), variant: 'danger', confirmText: t('noc.gDelete'),
      message: n > 0 ? t('noc.gDeleteMsg', g.name, n) : t('noc.gDeleteMsgNone', g.name),
    })
    if (!ok) return
    try {
      const r = unwrap(await api.admin.noc.deleteGroup(g.id))
      if (!r.ok) { toast.error(r.error || t('noc.gDeleteError')); return }
      const affected = Number(r.data?.affected_monitors ?? n) || 0
      toast.success(affected > 0 ? t('noc.gDeletedToast', g.name, affected) : t('noc.gDeletedToastNone', g.name))
      loadGroups()
    } catch (e) {
      toast.error(e?.message || t('noc.gDeleteError'))
    }
  }

  async function testGroup(g) {
    setTestingId(g.id)
    try {
      const r = unwrap(await api.admin.noc.testGroup(g.id))
      if (!r.ok) { toast.error(r.error || t('noc.gTestFail', g.name)); return }
      const { sent, failed, disabled } = testOutcome(r.data, (g.emails || []).length)
      if (disabled) toast.error(t('noc.gTestDisabled'))   // genel e-posta kapalı: tek, anlaşılır neden
      else if (!failed.length) toast.success(t('noc.gTestOk', sent, g.name))
      else {
        const list = failed.slice(0, 3).join(', ') + (failed.length > 3 ? ` +${failed.length - 3}` : '')
        toast.error(t('noc.gTestPartial', sent, failed.length, list))
      }
    } catch (e) {
      toast.error(e?.message || t('noc.gTestFail', g.name))
    } finally {
      setTestingId(null)
    }
  }

  const addButton = !readOnly && (
    <Button type="button" className="h-10 w-full sm:h-9 sm:w-auto sm:pointer-coarse:h-10" onClick={() => setModal({ group: null })} data-action="noc-group-add">
      <Plus aria-hidden="true" />{t('noc.gAdd')}
    </Button>
  )
  const instrLen = form?.call_instructions.length ?? 0

  return (
    <div className={SETTINGS_STACK} data-testid="noc-settings">
      <SettingsHeader icon={Headset} title={t('noc.settingsTitle')} description={t('noc.settingsDesc')}
        meta={<>
          {groups && <Badge variant="outline" className="font-normal text-muted-foreground">{t('noc.metaGroups', groups.length)}</Badge>}
          {groups && groups.length > 0 && (
            <ToneBadge tone={activeCount ? 'success' : 'danger'} className="font-semibold">{t('noc.metaActive', activeCount)}</ToneBadge>
          )}
          {form && offTypes.length > 0 && <ToneBadge tone="warning">{t('noc.metaTypesOff', offTypes.length)}</ToneBadge>}
          {readOnly && <ToneBadge tone="muted" data-slot="noc-readonly">{t('noc.readOnlyBadge')}</ToneBadge>}
        </>}>
        {readOnly && (
          <AlertBanner tone="info" title={t('noc.roTitle')} className="mb-0">{t('noc.roBody')}</AlertBanner>
        )}
      </SettingsHeader>

      {/* ── Gruplar ── */}
      <SettingsSection title={helpLabel(t('noc.groupsTitle'), 'help.noc.groups')} description={t('noc.groupsDesc')}
        contentClassName="flex flex-col gap-4">
        {groupsError && (
          <AlertBanner tone="danger" role="alert" title={t('noc.groupsLoadError')} className="mb-0"
            actions={<Button type="button" variant="outline" size="sm" className="h-10 sm:h-8 sm:pointer-coarse:h-10" onClick={loadGroups}><RefreshCw aria-hidden="true" />{t('noc.retry')}</Button>}>
            {String(groupsError)}
          </AlertBanner>
        )}
        {!groups && !groupsError && <LoadingBlock label={t('settings.loading')} className="justify-start px-0 py-4" />}
        {groups && groups.length === 0 && (
          <StatusBlock tone="info" icon={Headset} title={t('noc.emptyTitle')} className="rounded-xl border border-dashed"
            description={readOnly ? t('noc.emptyDescRo') : t('noc.emptyDesc')}
            actions={!readOnly && (
              <Button type="button" className="h-10 sm:h-9 sm:pointer-coarse:h-10" onClick={() => setModal({ group: null })} data-action="noc-group-add-first">
                <Plus aria-hidden="true" />{t('noc.emptyCta')}
              </Button>
            )} />
        )}
        {groups && groups.length > 0 && (
          <>
            {activeCount === 0 && (
              <AlertBanner tone="warning" title={t('noc.noActiveTitle')} className="mb-0">{t('noc.noActiveBody')}</AlertBanner>
            )}
            {addButton && <div className="flex justify-end">{addButton}</div>}
            <ul data-slot="noc-group-list" className="grid list-none grid-cols-[repeat(auto-fill,minmax(min(340px,100%),1fr))] gap-4">
              {sorted.map((g) => (
                <li key={g.id} className="flex min-w-0 flex-col [&>[data-slot=card]]:flex-1">
                  <NocGroupCard group={g} readOnly={readOnly} testing={testingId === g.id}
                    onTest={testGroup} onEdit={(x) => setModal({ group: x })} onDelete={removeGroup} />
                </li>
              ))}
            </ul>
          </>
        )}
      </SettingsSection>

      {cfgError && (
        <AlertBanner tone="danger" role={groupsError ? 'status' : 'alert'} title={t('noc.cfgLoadError')} className="mb-0"
          actions={<Button type="button" variant="outline" size="sm" className="h-10 sm:h-8 sm:pointer-coarse:h-10" onClick={loadConfig}><RefreshCw aria-hidden="true" />{t('noc.retry')}</Button>}>
          {String(cfgError)}
        </AlertBanner>
      )}
      {!form && !cfgError && <LoadingBlock label={t('settings.loading')} className="justify-start px-0 py-4" />}

      {form && (
        <>
          {/* ── Tür anahtarları ── */}
          <SettingsSection title={helpLabel(t('noc.typesTitle'), 'help.noc.enabledTypes')} description={t('noc.typesDesc')}
            contentClassName="flex flex-col gap-3">
            <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
              <p className="text-sm text-muted-foreground" data-slot="noc-types-count">{t('noc.typesOnCount', NOC_TYPES.length - offTypes.length, NOC_TYPES.length)}</p>
              {!readOnly && (
                <div className="flex gap-2">
                  <Button type="button" variant="outline" size="sm" className="h-10 flex-1 sm:h-8 sm:flex-none sm:pointer-coarse:h-10"
                    disabled={offTypes.length === 0} onClick={() => setAllTypes(true)}>{t('noc.typesAllOn')}</Button>
                  <Button type="button" variant="outline" size="sm" className="h-10 flex-1 sm:h-8 sm:flex-none sm:pointer-coarse:h-10"
                    disabled={offTypes.length === NOC_TYPES.length} onClick={() => setAllTypes(false)}>{t('noc.typesAllOff')}</Button>
                </div>
              )}
            </div>
            {/* Sütun sayısı KAP genişliğinden: kenar çubuğu açık tablette içerik ~300 px — görünüm alanına göre 2 sütun adları kırpıyordu */}
            <ul data-slot="noc-type-list" className="grid list-none grid-cols-[repeat(auto-fill,minmax(min(14.5rem,100%),1fr))] gap-2">
              {NOC_TYPES.map((k) => {
                const Icon = NOC_TYPE_ICON[k]
                const on = !!form.enabled_types[k]
                const id = `${typeIdBase}-${k}`
                return (
                  <li key={k} className="min-w-0">
                    <Label htmlFor={id} data-slot="noc-type-row" data-type={k} data-on={on ? 'true' : 'false'}
                      className={cn('flex min-h-12 w-full cursor-pointer items-center gap-3 rounded-lg border px-3 py-2 font-normal transition-colors',
                        on ? 'border-primary/30 bg-primary/5 dark:bg-primary/10' : 'bg-muted/40',
                        readOnly && 'cursor-default')}>
                      {Icon && <Icon aria-hidden="true" className={cn('size-4 shrink-0', on ? 'text-primary' : 'text-muted-foreground')} />}
                      <span className="min-w-0 flex-1 truncate text-sm font-medium">{typeLabel(t, k)}</span>
                      <span aria-hidden="true" className={cn('text-xs', on ? 'text-foreground' : 'font-semibold text-amber-700 dark:text-amber-300')}>
                        {on ? t('noc.typeOn') : t('noc.typeOff')}
                      </span>
                      <Switch id={id} checked={on} disabled={readOnly} onCheckedChange={(v) => setType(k, v)} />
                    </Label>
                  </li>
                )
              })}
            </ul>
            {offTypes.length > 0 && (
              <AlertBanner tone="warning" title={t('noc.typesOffTitle')} className="mb-0">
                {t('noc.typesOffBody', offTypes.map((k) => typeLabel(t, k)).join(', '))}
              </AlertBanner>
            )}
          </SettingsSection>

          {/* ── Gönderim kuralları ── */}
          {/* Alt alta, sınırlı genişlik: yan yana ızgara dar kapta (tablet + kenar çubuğu) anahtar metnini kırıyordu */}
          <SettingsSection title={t('noc.rulesTitle')} description={t('noc.rulesDesc')} contentClassName="flex flex-col gap-2">
            <div className="flex max-w-2xl flex-col">
              <Field label={helpLabel(t('noc.minLevel'), 'help.noc.minLevel')} hint={t('noc.minLevelHint')}
                className="[&>[data-slot=native-select-wrapper]]:w-full sm:[&>[data-slot=native-select-wrapper]]:max-w-sm">
                {({ id, describedBy }) => (
                  <NativeSelect id={id} aria-describedby={describedBy} value={form.min_level} disabled={readOnly}
                    className="h-10 w-full sm:h-9 sm:pointer-coarse:h-10" onChange={(e) => setForm((f) => ({ ...f, min_level: e.target.value }))}>
                    {NOC_LEVELS.map((lv) => <NativeSelectOption key={lv} value={lv}>{t(`noc.level.${lv}`)}</NativeSelectOption>)}
                  </NativeSelect>
                )}
              </Field>
              <div className="mb-3.5">
                <NocSwitchRow checked={!!form.send_resolve} onChange={(v) => setForm((f) => ({ ...f, send_resolve: v }))}
                  label={t('noc.sendResolve')} helpKey="help.noc.sendResolve" disabled={readOnly} hint={t('noc.sendResolveHint')} />
              </div>
            </div>
            <Field label={helpLabel(t('noc.callInstr'), 'help.noc.callInstructions')} className="max-w-3xl"
              hint={<>
                {t('noc.callInstrHint')}{' '}
                <span data-slot="noc-instr-count" className={cn('tabular-nums', instrLen >= MAX_INSTRUCTIONS * 0.9 && 'font-semibold text-amber-700 dark:text-amber-300')}>
                  {t('noc.charCount', instrLen, MAX_INSTRUCTIONS)}
                </span>
              </>}>
              {({ id, describedBy }) => (
                <Textarea id={id} aria-describedby={describedBy} rows={5} maxLength={MAX_INSTRUCTIONS} disabled={readOnly}
                  value={form.call_instructions} placeholder={t('noc.callInstrPh')} className="resize-y"
                  onChange={(e) => setForm((f) => ({ ...f, call_instructions: e.target.value.slice(0, MAX_INSTRUCTIONS) }))} />
              )}
            </Field>
          </SettingsSection>

          {!readOnly && (
            <SettingsSaveBar dirty={dirty} saving={saving} onSave={saveConfig} onDiscard={() => loaded && setForm(loaded)}
              saveLabel={t('noc.cfgSave')} />
          )}
        </>
      )}

      {modal && (
        <NocGroupModal open group={modal.group} onClose={() => setModal(null)}
          takenNames={(groups || []).filter((g) => g.id !== modal.group?.id).map((g) => g.name)}
          onSaved={() => { setModal(null); loadGroups() }} />
      )}
    </div>
  )
}
