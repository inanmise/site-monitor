import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { Copy, Globe, LayoutTemplate, LibraryBig } from 'lucide-react'
import { api } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import Field from '../ui/Field.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { RadioGroup, RadioGroupItem } from '@/components/shadcn/radio-group'
import { FieldContent, FieldDescription, FieldLabel, FieldTitle, Field as ShField } from '@/components/shadcn/field'
import { cn } from '@/lib/utils'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/shadcn/dialog'
import { LIMITS } from './techErrorModel.js'
import { useTeamLibrary } from './TemplateLibrary.jsx'
import { defaultEntry, initialSource, libraryOptions } from './templateLibraryModel.js'

/** Sunucuyla aynı teslim edilebilirlik kuralı (TechErrorReportSender.DELIVERABLE). */
const EMAIL_RE = /^[^\s@,;]+@[^\s@,;]+\.[^\s@,;]{2,}$/

/**
 * Kanal aç / düzenle. Açarken şablon üç yoldan gelir: varsayılan (Türkçe), varsayılan (İngilizce) ya da görülebilen
 * başka bir kanalın şablonunun KOPYASI — seçim, erişim paylaşımı penceresiyle aynı shadcn "seçim kartları" (RadioGroup +
 * Field; ikon, başlık, açıklama; 2026-10-17, kullanıcı isteği: düz radyo düğmeleri proje geneline uymuyordu). Seçili gelen
 * varsayılan arayüz dilinin şablonudur (2026-10-17, D20: İngilizce arayüz → İngilizce varsayılan, aksi Türkçe). Düzenleme
 * yalnız değişen alanları gönderir (ad / açıklama).
 *
 * Takım şablon kütüphanesi (2026-10-18, D17): dördüncü kart "Takım kütüphanesinden" seçilen takımın kütüphanesini listeler;
 * takımın VARSAYILANI varsa ilk kaynak odur (kullanıcı elle başka bir kaynak seçmediyse). Takım değişince kütüphane yeniden
 * okunur; kart her durumda çizilir (yükleniyor / okunamadı + yeniden dene / boş) — okunamayan kütüphane "yok" sayılmaz,
 * diğer kaynaklar çalışır. Gönderim `library_template_id`; sunucu kaydın AÇILAN kanalın takımına ait olduğunu denetler.
 *
 * İnceleme (2026-10-18): kaynağa "dokunuldu" sayılması yalnız DEĞİŞİMLE değil her kart tıklaması / klavye seçimiyle olur
 * (seçili TR kartına bilerek basan kullanıcının seçimi kütüphane gelince ezilmez). Kullanıcı kaynağa dokunmadıysa ve
 * kütüphane henüz okunuyorsa "Kanalı aç" bekler (takımın varsayılanı gelmeden sistem şablonuyla açılmasın). "Takım
 * varsayılanı seçili geldi" yalnız otomatik seçim duruyorken söylenir. Sunucu kütüphane şablonunu uyguladığını yanıtta
 * (`template_source`) onaylamazsa — yayılım sırasında eski pod alanı tanımaz — kullanıcı uyarılır.
 */
export default function ChannelDialog({ mode, channel = null, teams = [], channels = [], defaultTeam = null, onClose, onSaved }) {
  const t = useT()
  const { lang: uiLang } = useLanguage()
  const toast = useToast()
  const editable = teams.filter((tm) => tm.can_edit && tm.active !== false)
  const [teamId, setTeamId] = useState(() => {
    if (mode === 'edit') return String(channel?.team_id ?? '')
    const pref = editable.find((tm) => String(tm.id) === String(defaultTeam))
    return String(pref?.id ?? editable[0]?.id ?? '')
  })
  const [name, setName] = useState(channel?.name || '')
  const [description, setDescription] = useState(channel?.description || '')
  // Yönetici (executive) alıcıları (2026-10-17, S6): yalnız takımı yönetenler yazar; doluysa gönderim PO onayına bağlı
  const [execEmails, setExecEmails] = useState(channel?.executive_emails || '')
  // Varsayılan şablon dili arayüz dilinden (2026-10-17, D20): İngilizce arayüzde İngilizce varsayılan, aksi Türkçe; değiştirilebilir
  const [source, setSource] = useState(() => (uiLang === 'en' ? 'en' : 'tr'))
  const [copyFrom, setCopyFrom] = useState('')
  // Takım şablon kütüphanesi (2026-10-18, D17): seçilen takımın kütüphanesi; takımın VARSAYILANI varsa ilk kaynak odur
  // (kullanıcı bir kaynağı elle seçmediyse). Okunamazsa kart "okunamadı" der, diğer kaynaklar çalışır
  const [libraryId, setLibraryId] = useState('')
  const sourceTouched = useRef(false)
  // Görünür durum için aynası (Kanalı aç düğmesi ve not); efektler ref'i okur
  const [touched, setTouched] = useState(false)
  // Otomatik seçilen takım varsayılanı (kimlik) — kullanıcı kaynağı ya da kaydı değiştirince silinir
  const [autoDefaultId, setAutoDefaultId] = useState('')
  const markTouched = () => {
    if (!sourceTouched.current) { sourceTouched.current = true; setTouched(true) }
  }
  const lib = useTeamLibrary(teamId, { enabled: mode === 'create' && !!teamId })
  const sourceLabelId = useId()
  const [errors, setErrors] = useState({})
  const [busy, setBusy] = useState(false)

  const teamOptions = useMemo(() => editable.map((tm) => ({ value: String(tm.id), label: tm.name || `#${tm.id}` })), [editable])
  const canManage = mode === 'edit' ? !!channel?.can_manage : !!editable.find((tm) => String(tm.id) === String(teamId))?.can_manage
  const copyOptions = useMemo(() => channels.map((c) => ({
    value: String(c.id), label: c.team_name ? `${c.name} — ${c.team_name}` : c.name,
  })), [channels])
  const libraryOpts = useMemo(() => libraryOptions(lib.entries, t('te.lib.defaultSuffix')), [lib.entries, t])
  const libraryUsable = lib.ready && lib.entries.length > 0
  const libraryDefault = defaultEntry(lib.entries)
  // "Takım varsayılanı seçili geldi" yalnız otomatik seçim hâlâ duruyorsa; kullanıcı başka kayda / kaynağa geçince nötr sayı
  const autoChoiceStands = !!libraryDefault && source === 'library' && autoDefaultId !== ''
    && String(libraryId) === String(autoDefaultId) && String(libraryDefault.id) === String(autoDefaultId)
  const libraryHint = lib.error ? t('te.source.libraryError')
    : !lib.ready ? t('te.source.libraryLoading')
      : lib.entries.length === 0 ? t('te.source.libraryEmpty')
        : autoChoiceStands ? t('te.source.libraryHintDefault', lib.entries.length, libraryDefault.name)
          : t('te.source.libraryHint', lib.entries.length)
  // Kullanıcı kaynağa dokunmadı ve takımın kütüphanesi (varsayılanı) henüz okunuyor: "Kanalı aç" bekler — yoksa hızlı bir
  // gönderim takımın varsayılanı varken sistem şablonuyla kanal açardı. Okuma başarısızsa beklenmez (diğer kaynaklar çalışır)
  const waitingDefault = mode === 'create' && !!teamId && !touched && !lib.error && !lib.ready
  const waitingId = useId()

  // Kütüphane (yeniden) okununca: elle seçilmemişse takım varsayılanı ilk kaynak, yoksa arayüz dilinin varsayılanı. Elle
  // "kütüphane" seçildiyse ve yeni takımın kütüphanesinde o kayıt yoksa seçim temizlenir (başka takımın kaydı gitmesin)
  useEffect(() => {
    if (mode !== 'create' || !lib.ready) return
    if (!sourceTouched.current) {
      const init = initialSource(lib.entries, uiLang)
      setSource(init.source)
      setLibraryId(init.libraryId)
      setAutoDefaultId(init.source === 'library' ? init.libraryId : '')
      return
    }
    setLibraryId((cur) => (cur && lib.entries.some((e) => String(e.id) === String(cur)) ? cur : ''))
  }, [mode, lib.ready, lib.entries, uiLang])
  // Takım değişti (ya da okuma başarısız), yeni kütüphane henüz yok: otomatik seçilmiş eski takımın kaydı gönderilmesin. AYNI
  // takımın yeniden okunması (ör. sunucu kaydı reddedince) bunu tetiklemez — kaynak ve hata yerinde kalır (inceleme 2026-10-18)
  const libTeamMatches = lib.data != null && String(lib.teamId) === String(teamId)
  useEffect(() => {
    if (mode !== 'create' || lib.ready || libTeamMatches) return
    if (!sourceTouched.current && source === 'library') setSource(uiLang === 'en' ? 'en' : 'tr')
    setLibraryId((cur) => (lib.entries.some((e) => String(e.id) === String(cur)) ? cur : ''))
    setAutoDefaultId('')
  }, [mode, lib.ready, libTeamMatches, lib.entries, source, uiLang])

  function validate() {
    const e = {}
    if (mode === 'create' && !teamId) e.team_id = t('te.err.teamRequired')
    if (!name.trim()) e.name = t('te.err.nameRequired')
    else if (name.trim().length > LIMITS.channelName) e.name = t('te.err.nameTooLong')
    if (description.trim().length > LIMITS.description) e.description = t('te.err.descriptionTooLong')
    if (mode === 'create' && source === 'copy' && !copyFrom) e.copy_from = t('te.err.copyRequired')
    if (mode === 'create' && source === 'library' && !libraryId) e.library_template_id = t('te.lib.err.pickRequired')
    if (canManage && execEmails.trim()) {
      const parts = execEmails.split(/[,;]/).map((p) => p.trim()).filter(Boolean)
      const bad = parts.find((p) => !EMAIL_RE.test(p))
      if (bad) e.executive_emails = t('te.err.execEmailInvalid', bad)
      else if (parts.length > 20) e.executive_emails = t('te.err.execEmailsTooMany')
    }
    setErrors(e)
    return Object.keys(e).length === 0
  }

  async function submit(ev) {
    ev?.preventDefault?.()
    if (busy || waitingDefault || !validate()) return
    setBusy(true)
    try {
      let res
      if (mode === 'create') {
        const body = { team_id: Number(teamId), name: name.trim(), description: description.trim() || null }
        if (source === 'copy') body.copy_from = Number(copyFrom)
        else if (source === 'library') body.library_template_id = Number(libraryId)
        else body.lang = source
        if (canManage && execEmails.trim()) body.executive_emails = execEmails.trim()
        res = await api.techErrors.createChannel(body)
      } else {
        const body = {}
        if (name.trim() !== (channel?.name || '')) body.name = name.trim()
        if ((description.trim() || '') !== (channel?.description || '')) body.description = description.trim() || null
        if (canManage && execEmails.trim() !== (channel?.executive_emails || '')) body.executive_emails = execEmails.trim() || null
        if (Object.keys(body).length === 0) { onClose(); return }
        res = await api.techErrors.updateChannel(channel.id, body)
      }
      if (res?.success) {
        toast.success(mode === 'create' ? t('te.channelCreated', name.trim()) : t('te.channelSaved'))
        // Sunucu kütüphane şablonunu uyguladığını onaylamadı (yayılım sırasında eski pod library_template_id'yi tanımaz,
        // kanalı sistem şablonuyla açar): kullanıcı "kütüphane şablonuyla açıldı" sanmasın
        if (mode === 'create' && source === 'library' && res.data?.template_source?.kind !== 'library') {
          toast.error(t('te.lib.notApplied'), { duration: 12000 })
        }
        onSaved(res.data)
        return
      }
      if (res?.code === 'CHANNEL_EXISTS') setErrors({ name: res.error })
      else if (res?.field && ['name', 'description', 'team_id', 'copy_from', 'executive_emails', 'library_template_id'].includes(res.field)) {
        setErrors({ [res.field]: res.error })
        // Kütüphane kaydı silinmiş / değişmiş olabilir: liste yeniden okunur (seçim geçersizse temizlenir). Kaynak artık
        // kullanıcının seçimi sayılır — yeniden okuma onu sessizce başka bir varsayılana / sistem şablonuna çevirmesin
        if (res.field === 'library_template_id') {
          markTouched()
          setAutoDefaultId('')
          lib.reload()
        }
      } else toast.error(res?.error || t('te.saveError'), { details: res?.errorInfo })
    } catch (e) {
      toast.error(e?.message || t('te.saveError'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o && !busy) onClose() }}>
      <DialogContent className="max-h-[92dvh] overflow-y-auto sm:max-w-xl" data-slot="te-channel-dialog">
        <form onSubmit={submit} noValidate className="flex flex-col gap-1">
          <DialogHeader className="mb-3">
            <DialogTitle>{mode === 'create' ? t('te.newChannel') : t('te.editChannel')}</DialogTitle>
            <DialogDescription>{mode === 'create' ? t('te.newChannelHint') : t('te.editChannelHint')}</DialogDescription>
          </DialogHeader>

          {mode === 'create' ? (
            <Field label={t('te.field.team')} required error={errors.team_id} name="team_id">
              {({ id }) => (
                <SearchableSelect id={id} value={teamId} options={teamOptions} wrapLabels ariaLabel={t('te.field.team')}
                  onChange={(v) => { setTeamId(String(v)); setErrors((x) => ({ ...x, team_id: undefined })) }} />
              )}
            </Field>
          ) : (
            <p className="mb-3 text-sm text-muted-foreground [overflow-wrap:anywhere]">
              {t('te.field.team')}: <span className="font-medium text-foreground">{channel?.team_name || '—'}</span>
            </p>
          )}

          <Field label={t('te.field.channelName')} required error={errors.name} name="name" hint={t('te.field.channelNameHint')}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} className="pointer-coarse:h-10" value={name} maxLength={LIMITS.channelName + 20} aria-describedby={describedBy} aria-invalid={invalid}
                autoFocus onChange={(e) => { setName(e.target.value); setErrors((x) => ({ ...x, name: undefined })) }} />
            )}
          </Field>

          <Field label={t('te.field.description')} error={errors.description} name="description">
            {({ id, describedBy, invalid }) => (
              <Textarea id={id} value={description} rows={2} aria-describedby={describedBy} aria-invalid={invalid}
                onChange={(e) => { setDescription(e.target.value); setErrors((x) => ({ ...x, description: undefined })) }} />
            )}
          </Field>

          {canManage ? (
            <Field label={t('te.ch.execEmails')} hint={t('te.ch.execEmailsHint')} error={errors.executive_emails} name="executive_emails">
              {({ id, describedBy, invalid }) => (
                <Textarea id={id} value={execEmails} rows={2} aria-describedby={describedBy} aria-invalid={invalid}
                  placeholder={t('te.ch.execPlaceholder')}
                  onChange={(e) => { setExecEmails(e.target.value); setErrors((x) => ({ ...x, executive_emails: undefined })) }} />
              )}
            </Field>
          ) : mode === 'edit' && channel?.executive_emails ? (
            <p className="mb-3 text-sm text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-exec-locked">
              {t('te.ch.execEmails')}: <span className="font-medium text-foreground">{channel.executive_emails}</span> · {t('te.ch.execEmailsLocked')}
            </p>
          ) : null}

          {mode === 'create' && (
            <div className="mb-3 flex min-w-0 flex-col gap-2" data-field="copy_from" data-slot="te-source">
              <p id={sourceLabelId} className="m-0 text-sm font-semibold">{t('te.field.templateSource')}</p>
              <RadioGroup value={source} onValueChange={(v) => {
                markTouched()
                setSource(v)
                setAutoDefaultId('')
                setErrors((x) => ({ ...x, copy_from: undefined, library_template_id: undefined }))
              }}
                aria-labelledby={sourceLabelId} className="grid grid-cols-1 gap-2 sm:grid-cols-2" data-slot="te-source-options">
                {[
                  { value: 'tr', Icon: LayoutTemplate, title: t('te.source.tr'), hint: t('te.source.trHint') },
                  { value: 'en', Icon: Globe, title: t('te.source.en'), hint: t('te.source.enHint') },
                  // Kütüphane kartı HER ZAMAN çizilir (okunamadı / boş / yükleniyor da söylenir — sessizce gizlenmez)
                  { value: 'library', Icon: LibraryBig, title: t('te.source.library'), hint: libraryHint, disabled: !libraryUsable, wide: true },
                  ...(copyOptions.length ? [{ value: 'copy', Icon: Copy, title: t('te.source.copy'), hint: t('te.source.copyHint'), wide: true }] : []),
                ].map((o) => {
                  const rid = `${sourceLabelId}-${o.value}`
                  return (
                    <FieldLabel key={o.value} htmlFor={rid}
                      className={cn('w-full', o.disabled ? 'cursor-not-allowed' : 'cursor-pointer', o.wide && 'sm:col-span-2')}>
                      <ShField orientation="horizontal" data-source={o.value} data-disabled={o.disabled ? 'true' : undefined}
                        className="min-h-10 items-start gap-2 px-3! py-2!">
                        {/* Tıklama / Boşluk / ok tuşu seçimi (seçili karta bilerek basmak dahil) kaynağı kullanıcının yapar —
                            Radix aynı değerde onValueChange çağırmaz, bu yüzden tıklamada işaretlenir */}
                        <RadioGroupItem value={o.value} id={rid} className="mt-0.5" disabled={!!o.disabled} onClick={markTouched} />
                        <FieldContent className="gap-0.5">
                          <FieldTitle className="flex items-center gap-1.5 text-sm">
                            <o.Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />{o.title}
                          </FieldTitle>
                          <FieldDescription className="m-0 text-xs leading-snug [overflow-wrap:anywhere]" data-slot={o.value === 'library' ? 'te-source-library-hint' : undefined}>
                            {o.hint}
                          </FieldDescription>
                        </FieldContent>
                      </ShField>
                    </FieldLabel>
                  )
                })}
              </RadioGroup>
              {lib.error && (
                <div className="flex flex-wrap items-center gap-2" data-slot="te-source-library-error">
                  <p className="m-0 text-xs text-destructive">{t('te.source.libraryErrorShort')}</p>
                  <Button type="button" size="sm" variant="outline" onClick={lib.reload} className="pointer-coarse:min-h-10">{t('te.retry')}</Button>
                </div>
              )}
              {source === 'library' && (
                <div className="mt-1 flex flex-col gap-1.5" data-slot="te-source-library" data-touch-picker="">
                  <SearchableSelect value={libraryId} options={libraryOpts} wrapLabels ariaLabel={t('te.source.libraryPick')}
                    placeholder={t('te.lib.targetPlaceholder')} disabled={!libraryUsable}
                    onChange={(v) => {
                      markTouched()
                      if (String(v) !== String(autoDefaultId)) setAutoDefaultId('')
                      setLibraryId(String(v))
                      setErrors((x) => ({ ...x, library_template_id: undefined }))
                    }} />
                  {errors.library_template_id && <p className="m-0 text-sm text-destructive" role="alert">{errors.library_template_id}</p>}
                </div>
              )}
              {source === 'copy' && (
                <div className="mt-1 flex flex-col gap-1.5" data-slot="te-source-copy">
                  <SearchableSelect value={copyFrom} options={copyOptions} wrapLabels ariaLabel={t('te.field.copyFrom')}
                    placeholder={t('te.field.copyFromPlaceholder')}
                    onChange={(v) => { setCopyFrom(String(v)); setErrors((x) => ({ ...x, copy_from: undefined })) }} />
                  {errors.copy_from && <p className="m-0 text-sm text-destructive" role="alert">{errors.copy_from}</p>}
                </div>
              )}
            </div>
          )}

          {waitingDefault && (
            <p id={waitingId} className="m-0 mt-1 text-xs text-muted-foreground [overflow-wrap:anywhere]" role="status"
              data-slot="te-source-waiting">
              {t('te.source.libraryWaiting')}
            </p>
          )}
          <DialogFooter className="mt-2 flex-col-reverse gap-2 sm:flex-row">
            <Button type="button" variant="outline" onClick={onClose} disabled={busy} className="pointer-coarse:min-h-10">{t('te.cancel')}</Button>
            <Button type="submit" disabled={busy || waitingDefault} data-slot="te-channel-save" className="pointer-coarse:min-h-10"
              aria-describedby={waitingDefault ? waitingId : undefined}>
              {mode === 'create' ? t('te.createChannel') : t('te.save')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
