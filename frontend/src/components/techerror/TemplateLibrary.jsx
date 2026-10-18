import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { BookmarkPlus, CircleAlert, LibraryBig, Pencil, RefreshCw, Star, StarOff, Trash2, X } from 'lucide-react'
import { Spinner } from '@/components/shadcn/spinner'
import { api } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import Field from '../ui/Field.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import { useElementWidthState } from '../../hooks/useElementWidth.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Input } from '@/components/shadcn/input'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Label } from '@/components/shadcn/label'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Card, CardContent, CardHeader } from '@/components/shadcn/card'
import { RadioGroup, RadioGroupItem } from '@/components/shadcn/radio-group'
import { FieldContent, FieldDescription, FieldLabel, FieldTitle, Field as ShField } from '@/components/shadcn/field'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import {
  Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/shadcn/dialog'
import { cn } from '@/lib/utils'
import { errorInfoOf } from '../../utils/errorMessages.js'
import { fmtDateTime } from './TechErrorParts.jsx'
import {
  LIBRARY_MAX, LIBRARY_NAME_MAX, LIBRARY_TABLE_MIN_PX, defaultEntry, entryCounts, libraryEntries, libraryNameError,
  libraryOptions, nameTaken,
} from './templateLibraryModel.js'

/**
 * TAKIM ŞABLON KÜTÜPHANESİ (2026-10-18, kullanıcı kararı D17: "takım şablon kütüphanesi / takım varsayılanı — yeni kanal
 * onu alır; 'varsayılana dön' de seçenek sunar"). Bu dosyada: kütüphane okuma kancası, "Kütüphaneye kaydet" penceresi
 * (şablon düzenleyicisi) ve kütüphane yönetim penceresi (kanal listesi). Kütüphane takımın KENDİ işidir — sunucu
 * paylaşımla görene 404 döner; yazma düğmeleri yalnız satırın `can_edit`'i ile çizilir.
 */

/**
 * Takımın kütüphanesini okur (sıra numarasıyla geç yanıt düşer; takım değişince eski liste gösterilmez). `enabled`
 * false iken istek yok. Okuma başarısızsa `error` dolu ve `entries` boş — çağıran "yok" DEMEZ, "okunamadı" der.
 *
 * `ready`: BU takımın listesi elde (2026-10-18 inceleme) — AYNI takımın yeniden okunması sürerken de doğru kalır, liste
 * ekranda durur (yeniden adlandır / varsayılan yap / sil sonrası liste iskelete dönüp yeniden kuruluyordu: menü tetiği
 * yok oluyor, odak gövdeye düşüyor, ekran titriyordu). `refreshing`: liste elde ve yeniden okunuyor (küçük gösterge).
 */
export function useTeamLibrary(teamId, { enabled = true } = {}) {
  const t = useT()
  const tRef = useRef(t)
  tRef.current = t
  const [state, setState] = useState({ loading: false, error: null, status: null, data: null, teamId: null })
  const seq = useRef(0)
  const reload = useCallback(async () => {
    const my = ++seq.current
    if (!enabled || teamId == null || teamId === '') {
      setState({ loading: false, error: null, status: null, data: null, teamId: null })
      return
    }
    setState((s) => ({ loading: true, error: null, status: null, data: String(s.teamId) === String(teamId) ? s.data : null, teamId }))
    try {
      const res = await api.techErrors.teamTemplates(teamId)
      if (my !== seq.current) return
      if (res?.success) setState({ loading: false, error: null, status: null, data: res.data || {}, teamId })
      else setState({ loading: false, error: res?.error || tRef.current('te.lib.loadError'), status: res?.status ?? null, data: null, teamId })
    } catch (e) {
      if (my !== seq.current) return
      setState({ loading: false, error: e?.message || tRef.current('te.lib.loadError'), status: 0, data: null, teamId })
    }
  }, [teamId, enabled])
  useEffect(() => { reload() }, [reload])
  useEffect(() => () => { seq.current++ }, [])
  const entries = useMemo(() => libraryEntries(state.data), [state.data])
  const ready = !state.error && state.data != null && String(state.teamId) === String(teamId)
  const refreshing = ready && state.loading
  return { ...state, entries, ready, refreshing, max: Number(state.data?.max) || LIBRARY_MAX, canEdit: !!state.data?.can_edit, reload }
}

function LangCounts({ entry, className }) {
  const t = useT()
  const c = entryCounts(entry)
  const lang = c.lang === 'en' ? t('te.tpl.langEn') : c.lang === 'tr' ? t('te.tpl.langTr') : '—'
  return (
    <span className={cn('text-xs text-muted-foreground [overflow-wrap:anywhere]', className)} data-slot="te-lib-counts">
      {t('te.imp.meta', lang, c.sections, c.fields, c.slots)}
    </span>
  )
}

/** "Takım varsayılanı" rozeti — ad sarar, kırpılmaz. */
export function DefaultBadge() {
  const t = useT()
  return (
    <Badge className="rounded-md overflow-visible whitespace-normal bg-primary/10 text-primary" data-slot="te-lib-default">
      <Star aria-hidden="true" className="size-3" /> {t('te.lib.defaultBadge')}
    </Badge>
  )
}

/** Sunucu yanıtından alan / bant hatası (kütüphane uçları). */
function libraryFailure(res, t) {
  const info = errorInfoOf(res)
  const code = res?.code || info?.code
  const status = res?.status ?? info?.status
  if (code === 'TEMPLATE_NAME_EXISTS' || res?.field === 'name') return { field: 'name', message: res?.error || t('te.lib.err.nameExists') }
  if (code === 'TEMPLATE_LIBRARY_LIMIT') return { banner: res?.error || t('te.lib.err.limit', LIBRARY_MAX) }
  if (code === 'CONCURRENT_MODIFICATION') return { banner: t('te.lib.err.conflict'), reload: true }
  if (res?.field === 'template') return { banner: t('te.lib.err.template', res?.error || '') }
  if (code === 'TEAM_NOT_FOUND') return { banner: res?.error || t('te.lib.err.team') }
  if (status === 404) return { gone: true, banner: t('te.lib.err.gone'), reload: true }
  return { toast: res?.error || t('te.lib.saveError'), details: res?.errorInfo }
}

/**
 * KÜTÜPHANEYE KAYDET (şablon düzenleyicisi): düzenleyicideki GÜNCEL taslak (kaydedilmemiş olabilir) takımın kütüphanesine
 * yeni kayıt olarak ya da var olan bir kaydın üzerine yazılır; isteğe bağlı "takım varsayılanı yap". Kanal şablonu
 * KAYDEDİLMEZ (o ayrı). Alan hataları alanın altında; sınır / çakışma / şablon hatası pencere içinde bant.
 */
export function SaveToLibraryDialog({ open, teamId, teamName, draft, defaultName = '', onClose, onSaved, returnFocusRef }) {
  const t = useT()
  const toast = useToast()
  const lib = useTeamLibrary(teamId, { enabled: open })
  const [mode, setMode] = useState('new')
  const [name, setName] = useState('')
  const [targetId, setTargetId] = useState('')
  const [makeDefault, setMakeDefault] = useState(false)
  const [errors, setErrors] = useState({})
  const [banner, setBanner] = useState(null)
  const [busy, setBusy] = useState(false)
  const modeLabelId = useId()
  const nameId = useId()

  useEffect(() => {
    if (!open) return
    setMode('new')
    setName(defaultName || '')
    setTargetId('')
    setMakeDefault(false)
    setErrors({})
    setBanner(null)
  }, [open, defaultName])

  const options = useMemo(() => libraryOptions(lib.entries, t('te.lib.defaultSuffix')), [lib.entries, t])
  const target = lib.entries.find((e) => String(e.id) === String(targetId)) || null
  const full = lib.ready && lib.entries.length >= lib.max
  const canOverwrite = lib.ready && lib.entries.length > 0

  function chooseTarget(v) {
    setTargetId(String(v))
    const e = lib.entries.find((x) => String(x.id) === String(v))
    setMakeDefault(!!e?.is_default)
    setErrors((x) => ({ ...x, target: undefined }))
  }

  async function submit(ev) {
    ev?.preventDefault?.()
    if (busy || !draft) return
    const e = {}
    if (mode === 'new') {
      const key = libraryNameError(name)
      if (key) e.name = t(key, LIBRARY_NAME_MAX)
      else if (lib.ready && nameTaken(lib.entries, name)) e.name = t('te.lib.err.nameExistsHint')
    } else if (!target) e.target = t('te.lib.err.targetRequired')
    setErrors(e)
    if (Object.keys(e).length) return
    if (mode === 'new' && full) { setBanner(t('te.lib.err.limit', lib.max)); return }
    setBusy(true)
    setBanner(null)
    try {
      let res
      if (mode === 'new') {
        res = await api.techErrors.createTeamTemplate(teamId, { name: name.replace(/\s+/g, ' ').trim(), template: draft, default: makeDefault })
      } else {
        const body = { template: draft, version: target.version }
        if (makeDefault !== !!target.is_default) body.default = makeDefault
        res = await api.techErrors.updateTeamTemplate(teamId, target.id, body)
      }
      if (res?.success) {
        const saved = res.data || {}
        if (mode === 'overwrite' && saved.changed === false) toast.info(t('te.lib.unchanged', saved.name || target.name))
        else toast.success(t('te.lib.saved', saved.name || name.trim()))
        onSaved?.(saved)
        onClose()
        return
      }
      const f = libraryFailure(res, t)
      if (f.field === 'name' && mode === 'new') setErrors({ name: f.message })
      else if (f.gone) { setErrors({ target: f.banner }); setTargetId('') }
      else if (f.banner) setBanner(f.banner)
      else toast.error(f.toast, { details: f.details })
      if (f.reload) lib.reload()
    } catch (err) {
      toast.error(err?.message || t('te.lib.saveError'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o && !busy) onClose() }}>
      <DialogContent className="flex max-h-[92dvh] w-[calc(100vw-1.5rem)] flex-col gap-0 overflow-hidden p-0 sm:max-w-lg"
        data-slot="te-lib-save" showCloseButton={false}
        onCloseAutoFocus={(e) => { if (returnFocusRef?.current) { e.preventDefault(); returnFocusRef.current.focus() } }}>
        <DialogClose asChild>
          <Button type="button" variant="ghost" size="icon" className="absolute top-2.5 right-2.5 size-8 pointer-coarse:size-10"
            aria-label={t('app.close')} disabled={busy}>
            <X aria-hidden="true" />
          </Button>
        </DialogClose>
        <form onSubmit={submit} noValidate className="flex min-h-0 flex-1 flex-col">
          <DialogHeader className="border-b p-4 pe-14 text-left">
            <DialogTitle className="flex items-center gap-2"><BookmarkPlus aria-hidden="true" className="size-4 shrink-0" /> {t('te.lib.saveTitle')}</DialogTitle>
            <DialogDescription className="[overflow-wrap:anywhere]">{t('te.lib.saveDesc', teamName || '—')}</DialogDescription>
          </DialogHeader>
          <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-4" data-slot="te-lib-save-body">
            {banner && <AlertBanner tone="danger" className="mb-0"><span data-slot="te-lib-banner">{banner}</span></AlertBanner>}
            {lib.loading && !lib.ready && (
              <div className="flex flex-col gap-2" role="status" aria-live="polite">
                <span className="sr-only">{t('te.loading')}</span>
                <Skeleton className="h-4 w-48 motion-reduce:animate-none" />
              </div>
            )}
            {lib.error && (
              <AlertBanner tone="warning" className="mb-0"
                actions={<Button type="button" size="sm" variant="outline" onClick={lib.reload} className="pointer-coarse:min-h-10">{t('te.retry')}</Button>}>
                <span data-slot="te-lib-save-list-error">{t('te.lib.listUnavailableSave')}</span>
              </AlertBanner>
            )}
            {lib.ready && (
              <p className="m-0 text-xs text-muted-foreground" data-slot="te-lib-count">{t('te.lib.count', lib.entries.length, lib.max)}</p>
            )}
            <div className="flex min-w-0 flex-col gap-2">
              <p id={modeLabelId} className="m-0 text-sm font-semibold">{t('te.lib.mode')}</p>
              <RadioGroup value={mode} onValueChange={(v) => { setMode(v); setErrors({}); setBanner(null) }}
                aria-labelledby={modeLabelId} className="grid grid-cols-1 gap-2 sm:grid-cols-2" data-slot="te-lib-mode">
                {[
                  { value: 'new', title: t('te.lib.modeNew'), hint: full ? t('te.lib.modeNewFull', lib.max) : t('te.lib.modeNewHint'), disabled: false },
                  { value: 'overwrite', title: t('te.lib.modeOverwrite'), hint: canOverwrite ? t('te.lib.modeOverwriteHint') : t('te.lib.modeOverwriteNone'), disabled: !canOverwrite },
                ].map((o) => {
                  const rid = `${modeLabelId}-${o.value}`
                  return (
                    <FieldLabel key={o.value} htmlFor={rid} className={cn('w-full', o.disabled ? 'cursor-not-allowed opacity-70' : 'cursor-pointer')}>
                      <ShField orientation="horizontal" data-mode={o.value} className="min-h-10 items-start gap-2 px-3! py-2!">
                        <RadioGroupItem value={o.value} id={rid} disabled={o.disabled} className="mt-0.5" />
                        <FieldContent className="gap-0.5">
                          <FieldTitle className="text-sm">{o.title}</FieldTitle>
                          <FieldDescription className="m-0 text-xs leading-snug">{o.hint}</FieldDescription>
                        </FieldContent>
                      </ShField>
                    </FieldLabel>
                  )
                })}
              </RadioGroup>
            </div>
            {mode === 'new' ? (
              <Field label={t('te.lib.name')} required error={errors.name} name="name" hint={t('te.lib.nameHint')}>
                {({ id, describedBy, invalid }) => (
                  <Input id={id} className="pointer-coarse:h-10" value={name} maxLength={LIBRARY_NAME_MAX + 20}
                    aria-describedby={describedBy} aria-invalid={invalid} autoFocus data-slot="te-lib-name"
                    onChange={(e) => { setName(e.target.value); setErrors((x) => ({ ...x, name: undefined })) }} />
                )}
              </Field>
            ) : (
              <Field label={t('te.lib.target')} required error={errors.target} name="target">
                {({ id }) => (
                  <div data-touch-picker="" data-slot="te-lib-target">
                    <SearchableSelect id={id} value={targetId} options={options} wrapLabels ariaLabel={t('te.lib.target')}
                      placeholder={t('te.lib.targetPlaceholder')} disabled={busy} onChange={chooseTarget} />
                  </div>
                )}
              </Field>
            )}
            {mode === 'overwrite' && target && (
              <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-lib-target-meta">
                {t('te.lib.overwriteNote', target.name)}
              </p>
            )}
            <div className="flex min-h-10 items-start gap-2 rounded-md border px-3 py-2" data-slot="te-lib-default-row">
              <Checkbox id={nameId} checked={makeDefault} className="mt-0.5" data-slot="te-lib-make-default"
                onCheckedChange={(v) => setMakeDefault(v === true)} disabled={busy} />
              <Label htmlFor={nameId} className="flex min-w-0 flex-col items-start gap-0.5 text-sm font-normal leading-snug">
                <span className="font-medium">{t('te.lib.makeDefault')}</span>
                <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.lib.makeDefaultHint')}</span>
              </Label>
            </div>
          </div>
          <DialogFooter className="flex-col-reverse gap-2 border-t p-4 sm:flex-row">
            <Button type="button" variant="outline" onClick={onClose} disabled={busy} className="pointer-coarse:min-h-10">{t('te.cancel')}</Button>
            <Button type="submit" disabled={busy || !draft} className="pointer-coarse:min-h-10" data-slot="te-lib-save-submit">
              <BookmarkPlus aria-hidden="true" /> {busy ? t('te.saving') : t('te.lib.saveSubmit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * KÜTÜPHANE YÖNETİMİ (kanal listesi araç çubuğu): takımın kütüphanesindeki şablonlar — ad (+ varsayılan rozeti), dil ve
 * sayımlar, son güncelleyen / zaman; yeniden adlandır, takım varsayılanı yap / kaldır, sil (onay kaydın adını söyler).
 * Kullanıcı birden çok takımın kütüphanesini görebiliyorsa takım seçilir. Kap genişliği ≥ {@link LIBRARY_TABLE_MIN_PX}
 * tablo, aksi kart. Kayıt EKLEME şablon düzenleyicisinin "Kütüphaneye kaydet"inden yapılır (burada söylenir).
 */
export function LibraryManagerDialog({ open, teams = [], defaultTeam = null, onClose, returnFocusRef }) {
  const t = useT()
  const { lang: uiLang } = useLanguage()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const readable = useMemo(() => (teams || []).filter((tm) => tm && tm.library_read !== false), [teams])
  const pickInitial = useCallback(() => {
    const pref = readable.find((tm) => String(tm.id) === String(defaultTeam))
    return String((pref || readable.find((tm) => tm.can_edit) || readable[0])?.id ?? '')
  }, [readable, defaultTeam])
  const [teamId, setTeamId] = useState(pickInitial)
  // Takım seçimi yalnız pencere AÇILIRKEN başa alınır (açıkken takım listesi yenilense de kullanıcının seçimi kalır); seçili
  // takım artık listede değilse ilk uygun takıma geçilir
  const wasOpen = useRef(false)
  useEffect(() => {
    if (open && !wasOpen.current) setTeamId(pickInitial())
    else if (open && !readable.some((tm) => String(tm.id) === String(teamId))) setTeamId(pickInitial())
    wasOpen.current = open
  }, [open, pickInitial, readable, teamId])
  const lib = useTeamLibrary(teamId, { enabled: open && !!teamId })
  const [busy, setBusy] = useState(null)
  const [rename, setRename] = useState(null)       // { entry, name, error }
  // Odak dönüşü (2026-10-18 inceleme): işlemden sonra satırın menü tetiğine (silmede bir sonraki / önceki satırınkine,
  // liste boşaldıysa kapat düğmesine) döner — liste yeniden okunurken yerinde kalır, odak gövdeye düşmez
  const bodyRef = useRef(null)
  const closeRef = useRef(null)
  const focusAfter = useRef(null)                  // işlem + yeniden okuma bitince odaklanacak satır kimlikleri (sırayla denenir)
  const renameIdRef = useRef(null)                 // yeniden adlandırma penceresi kapanınca dönülecek satır
  const [width, setListEl] = useElementWidthState()
  const cards = width > 0 && width < LIBRARY_TABLE_MIN_PX
  const teamOptions = useMemo(() => readable.map((tm) => ({ value: String(tm.id), label: tm.name || `#${tm.id}` })), [readable])
  const teamName = readable.find((tm) => String(tm.id) === String(teamId))?.name || ''
  const def = defaultEntry(lib.entries)

  const triggerOf = useCallback((ids) => {
    const root = bodyRef.current
    if (!root) return null
    for (const id of ids || []) {
      if (id == null) continue
      const row = [...root.querySelectorAll('[data-slot="te-lib-entry"]')].find((el) => el.getAttribute('data-entry') === String(id))
      const trigger = row?.querySelector('[data-kebab]')
      if (trigger) return trigger
    }
    return null
  }, [])

  const focusEntryTrigger = useCallback((ids) => {
    const trigger = triggerOf(ids)
    if (trigger) { trigger.focus(); return true }
    return false
  }, [triggerOf])

  // Bekleyen odak dönüşü: işlem menüsü kapanış canlandırması sürerken odaklamak işe yaramaz (Radix kapanan menü odağı geri
  // alır, sonra gövdeye bırakır) — menü DOM'dan çıkana kadar kısa aralıklarla beklenir (en çok ~1 sn). Odak gerçekten
  // kaybolduysa (gövde / pencere kabı / kopmuş öğe) hedefe konur; kullanıcı başka bir yere geçtiyse dokunulmaz
  const focusTimer = useRef(null)
  useEffect(() => () => clearTimeout(focusTimer.current), [])
  const restoreFocus = useCallback((ids) => {
    clearTimeout(focusTimer.current)
    let tries = 0
    const tick = () => {
      const root = bodyRef.current
      if (!root || !root.isConnected) return
      if (document.querySelector('[data-slot="dropdown-menu-content"]') && tries++ < 40) {
        focusTimer.current = setTimeout(tick, 25)
        return
      }
      const target = triggerOf(ids) || closeRef.current
      const a = document.activeElement
      if (!target || a === target) return
      const lost = !a || a === document.body || !a.isConnected || a === root.closest('[role="dialog"]')
      if (lost) target.focus()
    }
    tick()
  }, [triggerOf])

  // İşlem ve yeniden okuma bittiğinde (meşgul değil, liste elde, okuma sürmüyor, ad penceresi kapalı) bekleyen odak hedefi
  useEffect(() => {
    const want = focusAfter.current
    if (!want || busy || !lib.ready || lib.loading || rename) return
    focusAfter.current = null
    restoreFocus(want)
  }, [busy, lib.ready, lib.loading, lib.entries, rename, restoreFocus])

  async function run(key, call, success, focusIds) {
    if (busy) return false
    setBusy(key)
    try {
      const res = await call()
      if (res?.success) {
        if (success) toast.success(success(res.data || {}))
        if (focusIds) focusAfter.current = focusIds
        await lib.reload()
        return true
      }
      const f = libraryFailure(res, t)
      if (f.field === 'name') return { field: f.message }
      if (f.banner) toast.error(f.banner)
      else toast.error(f.toast, { details: f.details })
      if (focusIds) focusAfter.current = focusIds
      if (f.reload) lib.reload()
      return false
    } catch (e) {
      toast.error(e?.message || t('te.lib.saveError'))
      return false
    } finally {
      setBusy(null)
    }
  }

  const toggleDefault = (e) => run(`d${e.id}`,
    () => api.techErrors.updateTeamTemplate(teamId, e.id, { default: !e.is_default, version: e.version }),
    () => (e.is_default ? t('te.lib.defaultRemoved', e.name) : t('te.lib.defaultSet', e.name)), [e.id])

  async function remove(e) {
    const ok = await showConfirm({
      title: t('te.lib.deleteTitle'),
      message: t('te.lib.deleteBody', e.name) + (e.is_default ? `\n\n${t('te.lib.deleteDefault')}` : ''),
      variant: 'danger', confirmText: t('te.delete'), cancelText: t('te.cancel'),
    })
    if (!ok) { restoreFocus([e.id]); return }   // vazgeç: odak o satırın menüsüne (ref atamak efekti tetiklemezdi)
    // Silinen satırın yerine bir sonraki, yoksa önceki satırın menüsü; silme başarısızsa aynı satır (ilk aday)
    const i = lib.entries.findIndex((x) => String(x.id) === String(e.id))
    const next = lib.entries[i + 1]?.id
    const prev = i > 0 ? lib.entries[i - 1]?.id : undefined
    await run(`x${e.id}`, () => api.techErrors.deleteTeamTemplate(teamId, e.id), () => t('te.lib.deleted', e.name), [e.id, next, prev])
  }

  async function submitRename(ev) {
    ev?.preventDefault?.()
    if (!rename || busy) return
    const key = libraryNameError(rename.name)
    if (key) { setRename((r) => ({ ...r, error: t(key, LIBRARY_NAME_MAX) })); return }
    if (nameTaken(lib.entries, rename.name, rename.entry.id)) { setRename((r) => ({ ...r, error: t('te.lib.err.nameExistsHint') })); return }
    const clean = rename.name.replace(/\s+/g, ' ').trim()
    if (clean === rename.entry.name) { setRename(null); return }
    const r = await run(`r${rename.entry.id}`,
      () => api.techErrors.updateTeamTemplate(teamId, rename.entry.id, { name: clean, version: rename.entry.version }),
      () => t('te.lib.renamed', clean), [rename.entry.id])
    if (r && r.field) setRename((x) => (x ? { ...x, error: r.field } : x))
    else setRename(null)        // kaydedildi ya da çakışma / bulunamadı: bildirim gösterildi, liste yeniden okundu
  }

  const actionsFor = (e) => (lib.canEdit && e.can_edit !== false ? [
    { label: t('te.lib.rename'), icon: <Pencil aria-hidden="true" />, ariaLabel: t('te.lib.renameOf', e.name),
      disabled: !!busy, onClick: () => { renameIdRef.current = e.id; setRename({ entry: e, name: e.name, error: null }) } },
    { label: e.is_default ? t('te.lib.unsetDefault') : t('te.lib.setDefault'),
      icon: e.is_default ? <StarOff aria-hidden="true" /> : <Star aria-hidden="true" />,
      ariaLabel: e.is_default ? t('te.lib.unsetDefaultOf', e.name) : t('te.lib.setDefaultOf', e.name),
      disabled: !!busy, onClick: () => toggleDefault(e) },
    { label: t('te.delete'), icon: <Trash2 aria-hidden="true" />, ariaLabel: t('te.lib.deleteOf', e.name), danger: true,
      disabled: !!busy, onClick: () => remove(e) },
  ] : [])

  const updated = (e) => (e.updated_at ? t('te.lib.updatedLine', fmtDateTime(e.updated_at, uiLang), e.updated_by_name || '—') : '—')

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o && !busy) onClose() }}>
      <DialogContent className="flex max-h-[92dvh] w-[calc(100vw-1.5rem)] flex-col gap-0 overflow-hidden p-0 sm:max-w-3xl"
        data-slot="te-lib-manager" showCloseButton={false}
        onCloseAutoFocus={(e) => { if (returnFocusRef?.current) { e.preventDefault(); returnFocusRef.current.focus() } }}>
        <DialogClose asChild>
          <Button ref={closeRef} type="button" variant="ghost" size="icon" className="absolute top-2.5 right-2.5 size-8 pointer-coarse:size-10"
            aria-label={t('app.close')}>
            <X aria-hidden="true" />
          </Button>
        </DialogClose>
        <DialogHeader className="border-b p-4 pe-14 text-left">
          <DialogTitle className="flex items-center gap-2"><LibraryBig aria-hidden="true" className="size-4 shrink-0" /> {t('te.lib.title')}</DialogTitle>
          <DialogDescription>{t('te.lib.desc')}</DialogDescription>
        </DialogHeader>
        <div ref={bodyRef} className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-4" data-slot="te-lib-manager-body">
          {readable.length > 1 && (
            <div className="flex min-w-0 flex-col gap-1.5 sm:max-w-md" data-touch-picker="">
              <span id="te-lib-team-label" className="text-sm font-semibold">{t('te.field.team')}</span>
              <SearchableSelect ariaLabelledBy="te-lib-team-label" value={teamId} options={teamOptions} wrapLabels disabled={!!busy}
                onChange={(v) => setTeamId(String(v))} />
            </div>
          )}
          {readable.length === 1 && (
            <p className="m-0 text-sm text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-lib-team">
              {t('te.field.team')}: <span className="font-medium text-foreground">{teamName || '—'}</span>
            </p>
          )}
          {readable.length === 0 && (
            <StatusBlock tone="neutral" icon={LibraryBig} title={t('te.lib.noTeamTitle')} description={t('te.lib.noTeamBody')} />
          )}
          {lib.loading && !lib.ready && (
            <div className="flex flex-col gap-2" role="status" aria-live="polite">
              <span className="sr-only">{t('te.loading')}</span>
              <Skeleton className="h-14 w-full rounded-lg motion-reduce:animate-none" />
              <Skeleton className="h-14 w-full rounded-lg motion-reduce:animate-none" />
            </div>
          )}
          {lib.error && (
            <StatusBlock tone={lib.status === 403 || lib.status === 404 ? 'neutral' : 'danger'} icon={CircleAlert}
              title={t('te.lib.loadErrorTitle')} description={lib.error}
              actions={<Button type="button" variant="outline" onClick={lib.reload} className="pointer-coarse:min-h-10"><RefreshCw aria-hidden="true" /> {t('te.retry')}</Button>} />
          )}
          {lib.ready && (
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
              <span data-slot="te-lib-count">{t('te.lib.count', lib.entries.length, lib.max)}</span>
              <span className="[overflow-wrap:anywhere]" data-slot="te-lib-default-line">
                {def ? t('te.lib.defaultIs', def.name) : t('te.lib.noDefault')}
              </span>
              {!lib.canEdit && <span data-slot="te-lib-readonly">{t('te.lib.readOnly')}</span>}
              {/* Liste yerinde kalır; yeniden okunduğu küçük bir göstergeyle söylenir (canlı bölge hep çizili, içerik değişir) */}
              <span className="inline-flex items-center gap-1.5 text-xs" role="status" aria-live="polite" data-slot="te-lib-refreshing"
                data-state={lib.refreshing ? 'on' : 'off'}>
                {lib.refreshing && <><Spinner aria-hidden="true" className="size-3.5" /> {t('te.lib.refreshing')}</>}
              </span>
            </div>
          )}
          {lib.ready && lib.entries.length === 0 && (
            <StatusBlock tone="neutral" icon={LibraryBig} title={t('te.lib.emptyTitle')} description={t('te.lib.emptyBody')} />
          )}
          {lib.ready && lib.entries.length > 0 && (
            <div ref={setListEl} data-slot="te-lib-list" data-layout={cards ? 'cards' : 'table'}>
              {cards ? (
                <ul className="m-0 grid list-none gap-2 p-0">
                  {lib.entries.map((e) => (
                    <li key={e.id}>
                      <Card className="gap-2 py-3" data-slot="te-lib-entry" data-entry={e.id} data-default={e.is_default ? 'true' : 'false'}>
                        <CardHeader className="gap-1.5 px-3">
                          <div className="flex min-w-0 items-start justify-between gap-2">
                            <div className="flex min-w-0 flex-col gap-1">
                              <span className="font-semibold [overflow-wrap:anywhere]" data-slot="te-lib-name-text">{e.name}</span>
                              {e.is_default && <span><DefaultBadge /></span>}
                            </div>
                            <KebabMenu variant="labeled" items={actionsFor(e)} label={t('te.col.actions')} rowLabel={e.name} />
                          </div>
                        </CardHeader>
                        <CardContent className="flex flex-col gap-1 px-3">
                          <LangCounts entry={e} />
                          <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{updated(e)}</span>
                        </CardContent>
                      </Card>
                    </li>
                  ))}
                </ul>
              ) : (
                <div className="overflow-hidden rounded-xl border">
                  <Table className="table-fixed">
                    <TableHeader>
                      <TableRow>
                        <TableHead className="whitespace-normal">{t('te.lib.colTemplate')}</TableHead>
                        <TableHead className="w-56 whitespace-normal">{t('te.lib.colUpdated')}</TableHead>
                        <TableHead className="w-44 text-right whitespace-normal">{t('te.col.actions')}</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {lib.entries.map((e) => (
                        <TableRow key={e.id} data-slot="te-lib-entry" data-entry={e.id} data-default={e.is_default ? 'true' : 'false'}>
                          <TableCell className="align-top whitespace-normal">
                            <div className="flex min-w-0 flex-col gap-1">
                              <span className="font-semibold [overflow-wrap:anywhere]" data-slot="te-lib-name-text">{e.name}</span>
                              {e.is_default && <span><DefaultBadge /></span>}
                              <LangCounts entry={e} />
                            </div>
                          </TableCell>
                          <TableCell className="align-top text-xs text-muted-foreground whitespace-normal [overflow-wrap:anywhere]">{updated(e)}</TableCell>
                          <TableCell className="align-top text-right">
                            <div className="flex justify-end">
                              <KebabMenu variant="labeled" items={actionsFor(e)} label={t('te.col.actions')} rowLabel={e.name} />
                            </div>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              )}
            </div>
          )}
          {lib.ready && <p className="m-0 text-xs text-muted-foreground" data-slot="te-lib-how">{t('te.lib.howToAdd')}</p>}
        </div>
        <Dialog open={!!rename} onOpenChange={(o) => { if (!o && !busy) setRename(null) }}>
          <DialogContent className="max-h-[92dvh] w-[calc(100vw-1.5rem)] overflow-y-auto sm:max-w-md" data-slot="te-lib-rename"
            onCloseAutoFocus={(e) => {
              // Açan menü öğesi artık yok: odak satırın menü tetiğine döner (kayıt / vazgeç / çakışma — hepsinde)
              const id = renameIdRef.current
              renameIdRef.current = null
              e.preventDefault()
              if (!focusEntryTrigger([id])) closeRef.current?.focus()
            }}>
            <form onSubmit={submitRename} noValidate className="flex flex-col gap-3">
              <DialogHeader className="text-left">
                <DialogTitle>{t('te.lib.renameTitle')}</DialogTitle>
                <DialogDescription className="[overflow-wrap:anywhere]">{t('te.lib.renameDesc', rename?.entry?.name || '')}</DialogDescription>
              </DialogHeader>
              <Field label={t('te.lib.name')} required error={rename?.error} name="rename">
                {({ id, describedBy, invalid }) => (
                  <Input id={id} className="pointer-coarse:h-10" value={rename?.name || ''} maxLength={LIBRARY_NAME_MAX + 20} autoFocus
                    aria-describedby={describedBy} aria-invalid={invalid} data-slot="te-lib-rename-input"
                    onChange={(e) => { const v = e.target.value; setRename((r) => (r ? { ...r, name: v, error: null } : r)) }} />
                )}
              </Field>
              <DialogFooter className="flex-col-reverse gap-2 sm:flex-row">
                <Button type="button" variant="outline" onClick={() => setRename(null)} disabled={!!busy} className="pointer-coarse:min-h-10">{t('te.cancel')}</Button>
                <Button type="submit" disabled={!!busy} className="pointer-coarse:min-h-10" data-slot="te-lib-rename-save">{t('te.save')}</Button>
              </DialogFooter>
            </form>
          </DialogContent>
        </Dialog>
      </DialogContent>
    </Dialog>
  )
}
