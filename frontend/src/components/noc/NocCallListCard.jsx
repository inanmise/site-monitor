import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { ArrowDown, ArrowUp, Phone, PhoneCall, PhoneOff, RefreshCw, Trash2 } from 'lucide-react'
import { api } from '../../api/client'
import { useToast } from '../ui/Toast.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import { SettingsSaveBar } from '../admin/SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Label } from '@/components/shadcn/label'
import { MAX_CALL_LIST, moveItem, unwrap } from './nocModel.js'

/**
 * Takım ARAMA LİSTESİ düzenleyicisi (Kapsam sayfası, 2026-09-27). 7/24 ekibi kesintide bu listeyi SIRAYLA arar;
 * e-postada ad · unvan · telefon (AD'den canlı okunur, elle yazılmaz — arayüze telefon HİÇ dönmez, yalnız `has_phone`).
 *
 * <p>Takım seçici (URL `n_ct`) → sıralı liste; yönetebilen (takım müdürü / TEAM_ADMIN / yönetici) yukarı-aşağı taşır,
 * çıkarır, üye ekler, Kaydet'le yazar (kirli izleme + Vazgeç). Diğerleri listeyi OKUR (kimin aranacağını bilsin).
 * AD'de telefonu olmayan kişi rozetle + üstte uyarıyla işaretlenir — 7/24 ekibi onu arayamaz. Liste boşsa e-postada
 * Takım Müdürü gösterilir (not düşülür). Yarış koruması: hızlı takım değişiminde eski yanıt yenisini ezmez (`seq`).
 * Test kancaları: `data-slot="noc-call-list" | "noc-cl-item" (data-user) | "noc-cl-nophone"`.
 */
export default function NocCallListCard({ t, teamOptions = [], teamId, onTeamChange, canEditFor }) {
  const toast = useToast()
  const pickId = useId()
  const addId = useId()
  const [list, setList] = useState(null)       // sıralı üyeler
  const [loaded, setLoaded] = useState(null)   // kaydedilmiş anlık görüntü
  const [members, setMembers] = useState([])   // ekleme seçicisi
  const [error, setError] = useState(null)
  const [saving, setSaving] = useState(false)
  const seq = useRef(0)
  const teamRef = useRef(teamId)   // kayıt sürerken takım değişirse yanıt YENİ takımın listesine yazılmasın
  teamRef.current = teamId
  // Sunucu yazmayı 403'le reddettiyse (istemci tahmini yanlış: ör. kapsamlı müdürün yönetim listesi istemcide yok) bu
  // takım salt okunur kalır ve neden söylenir. Takım değişince sıfırlanır.
  const [deniedFor, setDeniedFor] = useState(null)
  const denied = teamId != null && deniedFor === String(teamId)
  const editable = teamId != null && !denied && !!canEditFor?.(teamId)
  // Üye listesi (ekleme seçicisi) hangi takım için istendi — takım rehberi GEÇ gelip düzenleme hakkı sonradan
  // doğarsa liste yeniden yüklenmeden yalnız üyeler istenir; aynı takım için ikinci kez istenmez.
  const membersFor = useRef(null)

  const applyMembers = (id, mem) => {
    if (id !== seq.current || mem == null) return
    const m = unwrap(mem)
    setMembers(m.ok && Array.isArray(m.data) ? m.data : [])
  }

  async function load(tid) {
    const id = ++seq.current
    setList(null); setError(null); setMembers([])
    membersFor.current = tid != null && canEditFor?.(tid) ? String(tid) : null
    if (tid == null) return
    try {
      const [cl, mem] = await Promise.all([
        api.noc.getCallList(tid),
        membersFor.current != null ? api.noc.teamMembers(tid) : Promise.resolve(null),
      ])
      if (id !== seq.current) return
      const r = unwrap(cl)
      if (!r.ok) { setError(cl?.status === 403 ? t('noc.clForbiddenView') : (r.error || t('noc.clLoadError'))); return }
      const rows = Array.isArray(r.data) ? r.data : []
      setList(rows); setLoaded(rows)
      applyMembers(id, mem)
    } catch (e) {
      if (id === seq.current) setError(e?.message || t('noc.clLoadError'))
    }
  }
  useEffect(() => { load(teamId) }, [teamId]) // eslint-disable-line react-hooks/exhaustive-deps
  // Düzenleme hakkı yükleme SONRASI doğdu (takım rehberi geç geldi → lider/müdür anlaşıldı): üyeleri şimdi iste.
  useEffect(() => {
    if (!editable || teamId == null || membersFor.current === String(teamId)) return
    membersFor.current = String(teamId)
    const id = seq.current
    Promise.resolve().then(() => api.noc.teamMembers(teamId)).then((mem) => applyMembers(id, mem)).catch(() => {})
  }, [editable, teamId])

  const ids = (l) => (l || []).map((m) => String(m.user_id))
  const dirty = !!list && !!loaded && ids(list).join(',') !== ids(loaded).join(',')
  const noPhone = (list || []).filter((m) => !m.has_phone).length
  const full = (list || []).length >= MAX_CALL_LIST   // sunucu tavanı: en fazla 25 kişi
  const candidates = useMemo(() => {
    const taken = new Set(ids(list))
    return members.filter((m) => !taken.has(String(m.user_id))).map((m) => ({
      value: String(m.user_id),
      label: [m.display_name, m.title].filter(Boolean).join(' — ') + (m.has_phone ? '' : ` (${t('noc.clNoPhone')})`),
    }))
  }, [members, list, t])

  function add(v) {
    const m = members.find((x) => String(x.user_id) === String(v))
    if (m) setList((l) => ((l || []).length >= MAX_CALL_LIST ? l : [...(l || []), m]))
  }

  async function save() {
    const tid = teamId
    setSaving(true)
    try {
      const res = await api.noc.saveCallList(tid, (list || []).map((m) => m.user_id))
      const r = unwrap(res)
      if (String(tid) !== String(teamRef.current)) return
      if (!r.ok && res?.status === 403) {
        // Yetki yok: düzenleme kilitlenir, kaydedilmemiş sıra geri alınır, neden kartta kalıcı yazılır
        setDeniedFor(String(tid)); setList(loaded)
        toast.error(t('noc.clForbiddenEdit'))
        return
      }
      if (!r.ok) { toast.error(r.error || t('noc.clSaveError')); return }
      // Sunucu güncel listeyi dönerse o esas (sıra/üyelik sunucuda doğrulanır)
      const next = Array.isArray(r.data) ? r.data : list
      setList(next); setLoaded(next)
      toast.success(t('noc.clSaved'))
    } catch (e) {
      toast.error(e?.message || t('noc.clSaveError'))
    } finally {
      setSaving(false)
    }
  }

  const teamName = teamOptions.find((o) => String(o.value) === String(teamId))?.label || ''

  return (
    <Card data-slot="noc-call-list" className="gap-3 py-4" id="noc-call-list">
      <CardHeader className="gap-1 px-4 sm:px-5">
        <CardTitle role="heading" aria-level={3} className="flex items-center gap-2 text-base">
          <PhoneCall aria-hidden="true" className="size-4 text-primary" />{t('noc.clTitle')}
        </CardTitle>
        <CardDescription>{t('noc.clDesc')}</CardDescription>
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-3 px-4 sm:px-5">
        <div className="flex flex-col gap-1.5 sm:max-w-sm [&_[role=combobox]]:h-10 sm:[&_[role=combobox]]:h-9 sm:pointer-coarse:[&_[role=combobox]]:h-10">
          <Label htmlFor={pickId} className="text-xs font-semibold text-muted-foreground">{t('noc.clTeam')}</Label>
          <SearchableSelect id={pickId} value={teamId != null ? String(teamId) : ''} options={teamOptions}
            placeholder={t('noc.clTeamPh')} onChange={(v) => onTeamChange?.(v)} />
        </div>

        {denied && (
          <div data-slot="noc-cl-denied">
            <AlertBanner tone="warning" className="mb-0">{t('noc.clForbiddenEdit')}</AlertBanner>
          </div>
        )}
        {teamId != null && !editable && !denied && <p className="text-xs text-muted-foreground" data-slot="noc-cl-readonly">{t('noc.clReadOnly')}</p>}
        {error && (
          <AlertBanner tone="danger" title={t('noc.clLoadError')} className="mb-0"
            actions={<Button type="button" variant="outline" size="sm" className="h-10 sm:h-8 sm:pointer-coarse:h-10" onClick={() => load(teamId)}><RefreshCw aria-hidden="true" />{t('noc.retry')}</Button>}>
            {String(error)}
          </AlertBanner>
        )}
        {teamId != null && list === null && !error && <LoadingBlock label={t('noc.clLoading')} className="justify-start px-0 py-3" />}

        {list && list.length === 0 && (
          <AlertBanner tone="info" className="mb-0" title={t('noc.clEmptyTitle', teamName)}>{t('noc.clEmpty')}</AlertBanner>
        )}
        {list && noPhone > 0 && (
          <AlertBanner tone="warning" className="mb-0" title={t('noc.clNoPhoneTitle', noPhone)}>{t('noc.clNoPhoneBody')}</AlertBanner>
        )}

        {list && list.length > 0 && (
          <ol data-slot="noc-cl-list" aria-label={t('noc.clListLabel', teamName)} className="flex list-none flex-col gap-2">
            {list.map((m, i) => (
              <li key={m.user_id} data-slot="noc-cl-item" data-user={m.user_id}
                className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-2 rounded-lg border px-3 py-2">
                <span aria-hidden="true" className="inline-flex size-7 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-bold text-primary tabular-nums">{i + 1}</span>
                <div className="min-w-[9rem] flex-1">
                  <p className="truncate text-sm font-medium">{m.display_name}</p>
                  {m.title && <p className="truncate text-xs text-muted-foreground">{m.title}</p>}
                </div>
                {m.has_phone
                  ? <ToneBadge tone="success" className="gap-1"><Phone aria-hidden="true" />{t('noc.clHasPhone')}</ToneBadge>
                  : <ToneBadge tone="warning" className="gap-1" data-slot="noc-cl-nophone"><PhoneOff aria-hidden="true" />{t('noc.clNoPhone')}</ToneBadge>}
                {/* Sunucu `is_member:false`: listede kalmış ama takımdan ayrılmış kişi — kayıt reddedilir, çıkarılmalı */}
                {m.is_member === false && <ToneBadge tone="danger" data-slot="noc-cl-notmember">{t('noc.clNotMember')}</ToneBadge>}
                {editable && (
                  <div className="ml-auto flex shrink-0 gap-1">
                    <Button type="button" variant="ghost" size="icon" className="size-10 sm:size-8 sm:pointer-coarse:size-10" disabled={i === 0 || saving}
                      aria-label={t('noc.clMoveUp', m.display_name)} title={t('noc.clMoveUp', m.display_name)}
                      onClick={() => setList((l) => moveItem(l, i, -1))}><ArrowUp aria-hidden="true" /></Button>
                    <Button type="button" variant="ghost" size="icon" className="size-10 sm:size-8 sm:pointer-coarse:size-10" disabled={i === list.length - 1 || saving}
                      aria-label={t('noc.clMoveDown', m.display_name)} title={t('noc.clMoveDown', m.display_name)}
                      onClick={() => setList((l) => moveItem(l, i, 1))}><ArrowDown aria-hidden="true" /></Button>
                    <Button type="button" variant="ghost" size="icon" className="size-10 text-destructive hover:bg-destructive/10 hover:text-destructive sm:size-8 sm:pointer-coarse:size-10" disabled={saving}
                      aria-label={t('noc.clRemove', m.display_name)} title={t('noc.clRemove', m.display_name)}
                      onClick={() => setList((l) => l.filter((x) => x.user_id !== m.user_id))}><Trash2 aria-hidden="true" /></Button>
                  </div>
                )}
              </li>
            ))}
          </ol>
        )}

        {editable && list && (
          <div className="flex flex-col gap-1.5 sm:max-w-sm [&_[role=combobox]]:h-10 sm:[&_[role=combobox]]:h-9 sm:pointer-coarse:[&_[role=combobox]]:h-10">
            <Label htmlFor={addId} className="text-xs font-semibold text-muted-foreground">{t('noc.clAdd')}</Label>
            <SearchableSelect id={addId} value="" options={candidates} disabled={full || !candidates.length || saving}
              placeholder={full ? t('noc.clFull', MAX_CALL_LIST) : candidates.length ? t('noc.clAddPh') : t('noc.clAddNone')} onChange={add} />
          </div>
        )}

        {editable && list && (
          <SettingsSaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={() => loaded && setList(loaded)} saveLabel={t('noc.clSave')} />
        )}
      </CardContent>
    </Card>
  )
}
