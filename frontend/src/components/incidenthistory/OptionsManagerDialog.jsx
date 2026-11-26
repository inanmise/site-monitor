import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { Tags, CircleAlert, RotateCcw, Merge, PencilLine, Info } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import { PHONE_FULLSCREEN } from '../ui/modalClasses.js'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { Skeleton } from '@/components/shadcn/skeleton'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { errorInfoOf } from '../../utils/errorMessages.js'
import { serverFieldErrors } from '../../utils/formErrors.js'
import { foldSearchText } from '../../utils/monitorFilters.js'
import { isUnsureResult } from '../../utils/unsureResult.js'

/** Yönetilebilen seçenek türleri — sunucudaki `IncidentService.OPTION_ATTRIBUTE` anahtarları (aynı sıra). */
export const OPTION_KINDS = Object.freeze(['CHANNEL', 'DOMAIN', 'ERROR_CODE', 'FUNCTION_CODE', 'CHANNEL_CODE'])
/** Virgülle çok değer tutan (CSV) türler — hedef adda virgül olamaz. */
const CSV_KINDS = new Set(['CHANNEL', 'DOMAIN'])
/** Bir işlemde en çok bu kadar kaynak değer (sunucu `MAX_RENAME_SOURCES`). */
export const MAX_RENAME_SOURCES = 20
/** Kullanım okuması bu süreyi aşamaz. */
const USAGE_TIMEOUT_MS = 20_000

/** Eski pod (uç yok): 404 / 405 ya da `/{id}` eşlemesine düşen 400 INVALID_PARAMETER. */
function oldServer(res) {
  const info = errorInfoOf(res)
  const status = Number(res?.status ?? info?.status)
  const code = res?.code ?? info?.code
  return status === 404 || status === 405 || (status === 400 && code === 'INVALID_PARAMETER')
}

/**
 * Hedef değer birleştirme mi (inceleme 2026-11-26) — sunucunun kuralıyla aynı: hedef takımın KAYITLARINDA ayrı bir değer olarak
 * geçiyorsa (kullanım sayısı > 0). Yalnız seçenek satırı olan (0 kayıt) değer birleştirme SAYILMAZ (sunucu yeniden adlandırma
 * der; eskiden onay metni "birleştir", sonuç "yeniden adlandırıldı" diyordu).
 */
export function isMergeTarget(values, selected, target) {
  const v = String(target ?? '').trim().toLowerCase()
  if (!v) return false
  return (values || []).some((x) => !selected.has(x.value) && String(x.value).toLowerCase() === v && Number(x.records) > 0)
}

/**
 * Seçenekleri yönet (2026-11-26, "Bul ve Raporla") — takımın olay kayıtlarındaki kanal / alan adı / kod değerlerini YENİDEN
 * ADLANDIRIR ya da BİRLEŞTİRİR. Yalnız seçenekleri yönetebildiği takımı olan kullanıcıya (`teams[].can_manage_options`;
 * sunucu `incidents.manage` + takım yönetim kapsamını ayrıca uygular). Değer seçilir (birden çoksa birleştirme), yeni ad
 * yazılır, onaydan sonra sunucu takımın kayıtlarında değerin TAM öğesini tek işlemde değiştirir (her kayıt sürüm alır).
 * Genel (takımsız) seçenek satırlarına dokunulmaz — sonuç bunu söyler. Kullanım okuması sunucu tavanını aştıysa
 * (`truncated`, inceleme 2026-11-26) sayılar alt sınırdır: liste "en az N kayıt" yazar ve bir not gösterir.
 */
export default function OptionsManagerDialog({ teams = [], onClose, onChanged }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const teamSelId = useId()
  const typeSelId = useId()
  const toId = useId()
  const filterId = useId()
  const [teamId, setTeamId] = useState(() => (teams.length === 1 ? String(teams[0].id) : ''))
  const [type, setType] = useState('CHANNEL')
  // idle | loading | ready | failed | unsupported | forbidden; `key` = takım|tür — başka takımın / türün değerleri yüklenirken
  // gösterilmez (yalnız aynı listenin tazelemesinde eski değerler yerinde kalır)
  const [usage, setUsage] = useState({ state: 'idle', values: [], key: '' })
  const [reload, setReload] = useState(0)
  const [selected, setSelected] = useState(() => new Set())
  const [to, setTo] = useState('')
  const [filter, setFilter] = useState('')
  const [busy, setBusy] = useState(false)
  const [fieldErr, setFieldErr] = useState({})
  const [failure, setFailure] = useState(null)
  const seq = useRef(0)
  const abortRef = useRef(null)
  const lastKey = useRef('')
  useEffect(() => () => { abortRef.current?.abort() }, [])

  useEffect(() => {
    const key = teamId ? `${teamId}|${type}` : ''
    setSelected(new Set()); setTo(''); setFieldErr({})
    // Başka takım / tür → eski hata iletisi de gider; aynı listenin yeniden okunması (sonucu bilinmeyen işlemden sonra) iletiyi tutar
    if (lastKey.current !== key) setFailure(null)
    lastKey.current = key
    if (!teamId) { setUsage({ state: 'idle', values: [], key: '' }); return }
    const my = ++seq.current
    abortRef.current?.abort()
    const ctl = typeof AbortController === 'function' ? new AbortController() : null
    abortRef.current = ctl
    setUsage((u) => ({ state: 'loading', values: u.key === key ? u.values : [], key }))
    Promise.resolve()
      .then(() => api.incidents.optionUsage(type, teamId, { timeoutMs: USAGE_TIMEOUT_MS, signal: ctl?.signal }))
      .then((res) => {
        if (my !== seq.current || ctl?.signal?.aborted) return
        if (res?.success && Array.isArray(res.data?.values)) {
          setUsage({ state: 'ready', values: res.data.values, key, truncated: res.data.truncated === true,
            rowsRead: Number.isFinite(Number(res.data.rows_read)) ? Number(res.data.rows_read) : null })
        }
        else if (oldServer(res)) setUsage({ state: 'unsupported', values: [], key })
        else if (Number(res?.status ?? errorInfoOf(res)?.status) === 403) setUsage({ state: 'forbidden', values: [], key })
        else setUsage({ state: 'failed', values: [], key, error: res?.error || null })
      })
      .catch(() => { if (my === seq.current) setUsage({ state: 'failed', values: [], key }) })
  }, [teamId, type, reload])

  const shown = useMemo(() => {
    const f = foldSearchText(filter.trim())
    return f ? usage.values.filter((v) => foldSearchText(String(v.value)).includes(f)) : usage.values
  }, [usage.values, filter])

  const toggle = (value) => setSelected((s) => {
    const n = new Set(s)
    if (n.has(value)) n.delete(value)
    else if (n.size < MAX_RENAME_SOURCES) n.add(value)
    return n
  })
  const from = [...selected]
  const merge = from.length > 1 || isMergeTarget(usage.values, selected, to)
  const affected = usage.values.filter((v) => selected.has(v.value)).reduce((n, v) => n + (Number(v.records) || 0), 0)

  function clientError() {
    const v = to.trim()
    if (from.length === 0) return { from: t('inc.opt.errPick') }
    if (!v) return { to: t('inc.opt.errTo') }
    if (CSV_KINDS.has(type) && v.includes(',')) return { to: t('inc.opt.errComma') }
    if (from.length === 1 && from[0] === v) return { to: t('inc.opt.errSame') }
    return null
  }

  async function submit() {
    if (busy) return
    const err = clientError()
    setFieldErr(err || {})
    setFailure(null)
    if (err) return
    const target = to.trim()
    const ok = await showConfirm({
      title: merge ? t('inc.opt.confirmMergeTitle') : t('inc.opt.confirmRenameTitle'),
      message: t('inc.opt.confirmBody', from.join(', '), target, affected),
      confirmText: merge ? t('inc.opt.merge') : t('inc.opt.rename'), cancelText: t('inc.cancel'),
    })
    if (!ok) return
    setBusy(true)
    let res = null
    try { res = await api.incidents.renameOptions({ type, team_id: Number(teamId), from, to: target }) } catch { res = null } finally { setBusy(false) }
    if (!res?.success) {
      if (oldServer(res)) { setFailure(t('inc.opt.unsupported')); return }
      // Yanıt yok / zaman aşımı / ağ geçidi: değişiklik sunucuda uygulanmış OLABİLİR — liste yeniden okunur, körlemesine
      // yeniden denenmez (kullanıcı güncel kullanım sayılarına bakıp karar verir)
      if (isUnsureResult(res)) {
        setFailure(t('inc.opt.unsure'))
        setReload((k) => k + 1)
        onChanged?.()
        return
      }
      const fields = serverFieldErrors(res) || {}
      if (fields.to || fields.from) { setFieldErr(fields); return }
      setFailure(res?.error || t('inc.opt.failed'))
      return
    }
    const d = res.data || {}
    const kept = Array.isArray(d.global_kept) ? d.global_kept : []
    toast.success(t(d.mode === 'merge' ? 'inc.opt.mergedN' : 'inc.opt.renamedN', Number(d.records) || 0, d.to ?? target)
      + (kept.length ? ' ' + t('inc.opt.globalKept', kept.join(', ')) : ''))
    setReload((k) => k + 1)
    onChanged?.()
  }

  const kindLabel = (k) => t('inc.opt.kind.' + k)
  const body = (() => {
    if (!teamId) return <p className="m-0 text-sm text-muted-foreground" data-slot="ih-opt-pick-team">{t('inc.opt.pickTeam')}</p>
    if (usage.state === 'unsupported') return <StatusBlock tone="neutral" icon={CircleAlert} title={t('inc.opt.unsupported')} />
    if (usage.state === 'forbidden') return <StatusBlock tone="neutral" icon={CircleAlert} title={t('inc.opt.forbidden')} />
    if (usage.state === 'failed') {
      return (
        <StatusBlock tone="danger" icon={CircleAlert} role="alert" title={t('inc.opt.loadFailed')} description={usage.error || undefined}
          actions={<Button type="button" variant="outline" className="pointer-coarse:min-h-10" onClick={() => setReload((k) => k + 1)}>
            <RotateCcw aria-hidden="true" />{t('inc.retry')}</Button>} />
      )
    }
    if (usage.state === 'loading' && usage.values.length === 0) {
      return (
        <div className="flex flex-col gap-2" role="status">
          <span className="sr-only">{t('inc.loading')}</span>
          <Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" />
        </div>
      )
    }
    if (usage.values.length === 0) return <p className="m-0 rounded-md border border-dashed p-3 text-sm text-muted-foreground" data-slot="ih-opt-empty">{t('inc.opt.none')}</p>
    return (
      <div className="flex min-w-0 flex-col gap-2">
        {usage.truncated && (
          <p data-slot="ih-opt-truncated" role="status" className="m-0 flex items-start gap-1.5 rounded-md border border-dashed px-2.5 py-1.5 text-xs text-muted-foreground">
            <Info aria-hidden="true" className="mt-px size-3.5 shrink-0" />
            <span className="min-w-0 [overflow-wrap:anywhere]">{t('inc.opt.truncated', usage.rowsRead ?? 0)}</span>
          </p>
        )}
        <Input id={filterId} type="search" value={filter} onChange={(e) => setFilter(e.target.value)} aria-label={t('inc.opt.filter')}
          placeholder={t('inc.opt.filter')} className="pointer-coarse:h-10" />
        <ul data-slot="ih-opt-values" aria-label={t('inc.opt.valuesLabel', kindLabel(type))}
          className="m-0 flex max-h-72 list-none flex-col gap-1 overflow-y-auto rounded-md border p-1">
          {shown.map((v) => {
            const id = `${filterId}-v-${encodeURIComponent(v.value)}`
            const on = selected.has(v.value)
            return (
              <li key={v.value} data-slot="ih-opt-value" data-value={v.value}
                className="flex min-h-10 items-center gap-2 rounded px-2 py-1 hover:bg-muted/50">
                <Checkbox id={id} checked={on} disabled={!on && selected.size >= MAX_RENAME_SOURCES} onCheckedChange={() => toggle(v.value)} />
                <Label htmlFor={id} className="min-w-0 flex-1 cursor-pointer text-sm font-normal [overflow-wrap:anywhere]">{v.value}</Label>
                <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
                  {t(usage.truncated ? 'inc.opt.recordsAtLeast' : 'inc.opt.records', Number(v.records) || 0)}
                </span>
                {v.team_option === true && <Badge variant="outline" className="shrink-0 text-[11px]">{t('inc.opt.teamOption')}</Badge>}
                {v.global_option === true && <Badge variant="secondary" className="shrink-0 text-[11px]">{t('inc.opt.globalOption')}</Badge>}
              </li>
            )
          })}
          {shown.length === 0 && <li className="p-2 text-sm text-muted-foreground">{t('inc.opt.noMatch')}</li>}
        </ul>
        {fieldErr.from && <p role="alert" className="m-0 text-sm text-destructive" data-field="from">{fieldErr.from}</p>}
        <div className="flex min-w-0 flex-col gap-1.5">
          <Label htmlFor={toId} className="text-sm font-medium">{merge ? t('inc.opt.toMerge') : t('inc.opt.to')}</Label>
          <Input id={toId} value={to} maxLength={CSV_KINDS.has(type) ? 150 : 100} onChange={(e) => { setTo(e.target.value); setFieldErr((f) => ({ ...f, to: undefined })) }}
            aria-invalid={fieldErr.to ? true : undefined} aria-describedby={fieldErr.to ? `${toId}-err` : undefined}
            className="pointer-coarse:h-10" data-slot="ih-opt-to" />
          {fieldErr.to && <p id={`${toId}-err`} role="alert" className="m-0 text-sm text-destructive" data-field="to">{fieldErr.to}</p>}
          <p className="m-0 text-xs text-muted-foreground">{t('inc.opt.hint')}</p>
        </div>
        {failure && <p role="alert" className="m-0 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive" data-slot="ih-opt-failure">{failure}</p>}
      </div>
    )
  })()

  const footer = (
    <div className="flex w-full flex-wrap items-center justify-end gap-2">
      <span className="mr-auto text-xs text-muted-foreground" data-slot="ih-opt-selected">
        {from.length > 0 ? t('inc.opt.selectedN', from.length, affected) : ''}
      </span>
      <Button type="button" variant="outline" className="pointer-coarse:min-h-10" onClick={onClose} disabled={busy}>{t('app.close')}</Button>
      <Button type="button" className="pointer-coarse:min-h-10" data-slot="ih-opt-submit" disabled={busy || from.length === 0 || usage.state !== 'ready'}
        aria-busy={busy || undefined} onClick={submit}>
        {merge ? <Merge aria-hidden="true" /> : <PencilLine aria-hidden="true" />}{merge ? t('inc.opt.merge') : t('inc.opt.rename')}
      </Button>
    </div>
  )

  return (
    <ModalShell open onClose={onClose} title={t('inc.opt.title')} icon={Tags} size="md" scrollBody busy={busy}
      className={PHONE_FULLSCREEN} footer={footer}>
      <div className="flex min-w-0 flex-col gap-4" data-slot="ih-options-manager">
        <p className="m-0 text-sm text-muted-foreground">{t('inc.opt.desc')}</p>
        <div className="grid min-w-0 grid-cols-1 gap-3 sm:grid-cols-2">
          <div className="flex min-w-0 flex-col gap-1.5">
            <Label htmlFor={teamSelId} className="text-sm font-medium">{t('inc.colTeam')}</Label>
            <NativeSelect id={teamSelId} value={teamId} className="w-full pointer-coarse:h-10" disabled={busy} onChange={(e) => setTeamId(e.target.value)} data-slot="ih-opt-team">
              <NativeSelectOption value="">{t('inc.opt.teamPh')}</NativeSelectOption>
              {teams.map((tm) => <NativeSelectOption key={tm.id} value={String(tm.id)}>{tm.name}</NativeSelectOption>)}
            </NativeSelect>
          </div>
          <div className="flex min-w-0 flex-col gap-1.5">
            <Label htmlFor={typeSelId} className="text-sm font-medium">{t('inc.opt.type')}</Label>
            <NativeSelect id={typeSelId} value={type} className="w-full pointer-coarse:h-10" disabled={busy} onChange={(e) => setType(e.target.value)} data-slot="ih-opt-type">
              {OPTION_KINDS.map((k) => <NativeSelectOption key={k} value={k}>{kindLabel(k)}</NativeSelectOption>)}
            </NativeSelect>
          </div>
        </div>
        {body}
      </div>
    </ModalShell>
  )
}
