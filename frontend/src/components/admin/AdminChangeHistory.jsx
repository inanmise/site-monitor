import { useState, useEffect, useMemo, Fragment } from 'react'
import { History, ChevronDown, ChevronRight, RefreshCw, X } from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { EventBadge } from './ToneBadge.jsx'
import DiffTable from './audit/DiffTable.jsx'
import ChangeChipList from '../history/ChangeChipList.jsx'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Toggle } from '@/components/shadcn/toggle'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import MaskedValue from '../ui/MaskedValue.jsx'
import { relTimeOrRaw as relTime } from '../../utils/relativeTime.js'

/** Tablo ölçüleri (shadcn Data Table görünümü: soluk başlık, satır vurgusu) + ikincil metin + dipnot. */
const TH = 'h-9 px-3 text-[0.75em] tracking-wide text-muted-foreground uppercase'
const TD = 'px-3 py-2 align-top whitespace-normal'
const HINT = 'mt-1.5 text-xs text-muted-foreground'
/** Olay türü süzgeç çipi — shadcn Toggle (hap biçimi, basılıyken birincil ton). */
const CHIP = 'h-7 max-sm:h-10 rounded-full px-2.5 text-[0.78em] font-semibold text-muted-foreground hover:bg-transparent hover:text-foreground data-[state=on]:border-primary data-[state=on]:bg-primary/10 data-[state=on]:text-primary'
/** Özet çiplerinde en fazla kaç alan (kalanı "+N"). */
const SUMMARY_LIMIT = 2

/** Olay türü → rozet tonu (`ev-*`, Denetim Kaydı ile aynı dağarcık → ToneBadge.EventBadge). */
function eventKind(action) {
  const a = String(action || '')
  if (a.endsWith('CREATE') || a.endsWith('MEMBER_ADD')) return 'ev-create'
  if (a.endsWith('DELETE') || a.endsWith('MEMBER_REMOVE') || a === 'ACCOUNT_LOCKED') return 'ev-delete'
  if (a.endsWith('UPDATE') || a.includes('UNLOCK') || a.includes('RESET') || a.includes('NOTIFICATIONS') || a.includes('ACCESS')) return 'ev-edit'
  return 'ev-other'
}

function parseChanges(raw) {
  if (!raw) return null
  try { const o = JSON.parse(raw); return o && typeof o === 'object' && !Array.isArray(o) ? o : null } catch { return null }
}
/** Fark biçimi mi ({"alan":{"from":…,"to":…}}) — içerikten karar (eski kayıtlar da doğru okunsun). */
function isDiffShape(obj) {
  const vals = Object.values(obj)
  return vals.length > 0 && vals.every(v => v && typeof v === 'object' && !Array.isArray(v) && ('from' in v || 'to' in v))
}
function fmt(t, v) {
  if (v === true) return t('ng.histYes')
  if (v === false) return t('ng.histNo')
  if (v === null || v === undefined || v === '') return t('ng.histEmptyValue')
  if (Array.isArray(v)) return v.join(', ')
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}
// "3 dk önce" — Aktivite Logu ile aynı `act.rel.*` dağarcığı; tam zaman ipucunda / açılımda. `relTime` ortak
// yardımcıdan (utils/relativeTime.relTimeOrRaw — öneri 29): boş damga "—", çözülemeyen damga olduğu gibi; çıktı aynı.

/**
 * Alan çipleri: fark kaydında "alan eski → yeni", anlık görüntüde yalnız alan adı. Uzun değer kırpılır,
 * tamamı ipucunda (masaüstü) ve satır açılımındaki fark tablosunda (her cihaz). Çizim ortak
 * `history/ChangeChipList` (İzleme Değişiklikleri konsolu ve izlemenin Değişiklikler sekmesiyle aynı çip).
 */
const NO_CHIPS = <span className="text-muted-foreground">—</span>
function ChangeChips({ entries, more, wrap = false }) {
  return <ChangeChipList entries={entries} more={more} wrap={wrap} empty={NO_CHIPS} />
}

/**
 * Yönetim Paneli "Değişiklik Geçmişi" (2026-09-20 v2; 2026-09-26 yeniden tasarım): eşik / eskalasyon kişisi /
 * takım / kullanıcı. Kullanıcılar sekmesinde kullanıcı listesinin altında; diğer kaynaklarda da aynı bileşen.
 *
 * <p>Masaüstü: shadcn Table (Data Table görünümü) — zaman (göreli, tam zaman ipucunda) · kim (avatar + ad) ·
 * işlem (renkli rozet) · hedef · değişiklik çipleri (eski → yeni). Telefon (< md, `useIsMobile`): küçük Card
 * listesi — TEK varyant çizilir (jsdom medya sorgusu uygulamaz; iki varyantı CSS ile gizlemek testlerde ikisini
 * birden gösterirdi). Satır/kart açılınca tam fark tablosu. Yükleme Skeleton, boş durum StatusBlock, etkin
 * kayıt süzgeci X'li rozet. Sunucu sayfalı: standart useServerPagination + PaginationBar (panel ön ayarı).
 * KAPALI başlar: her açılışta denetim sorgusu atmak ekranı asıl işi için açan kullanıcıya bedava yük bindirir.
 */
export default function AdminChangeHistory({ resource, filter = null, onClearFilter, canView = true }) {
  const t = useT()
  const isMobile = useIsMobile()
  const [open, setOpen] = useState(!!filter)
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [types, setTypes] = useState([])        // seçili olay türleri (boş = hepsi)
  const [expanded, setExpanded] = useState(null)
  const [nonce, setNonce] = useState(0)
  // Kaynak / kayıt süzgeci / tür değişince sayfa 1 (değer karşılaştırmalı, aynı render'da). API 0-tabanlı.
  const sp = useServerPagination({ listKey: 'admin-history', preset: 'panel', resetDeps: [resource, filter?.id ?? null, types], apiBase: 0 })
  const { apiPage, pageSize } = sp

  useEffect(() => { if (filter) setOpen(true) }, [filter?.id])   // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!open || !canView) return
    let cancelled = false
    setLoading(true); setError(null)
    api.admin.history(resource, filter?.id ?? null, { page: apiPage, size: pageSize, types })
      .then(res => {
        if (cancelled) return
        if (res?.success) { setData(res); sp.bind(res) }
        else setError(res?.error ?? t('ng.histError'))
      })
      .catch(e => { if (!cancelled) setError(e?.message ?? String(e)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [open, resource, filter?.id, canView, apiPage, pageSize, types, nonce])   // eslint-disable-line react-hooks/exhaustive-deps

  const allTypes = useMemo(() => data?.types || [], [data])
  const items = data?.items || []
  const total = Number(data?.total ?? 0)

  if (!canView) return null

  const actLabel = (a) => { const k = `hist.act.${a}`; const s = t(k); return s === k ? String(a || '').toLowerCase().replace(/_/g, ' ') : s }
  const fieldLabel = (f) => { const k = `hist.f.${f}`; const s = t(k); return s === k ? f : s }
  const toggleType = (ty) => setTypes(prev => prev.includes(ty) ? prev.filter(x => x !== ty) : [...prev, ty])
  const now = Date.now()

  /** Satır modeli: özet çipleri + tam açılım (fark tablosu ya da anlık görüntü). */
  const model = (r) => {
    const parsed = parseChanges(r.changes)
    const diff = parsed && isDiffShape(parsed) ? parsed : null
    const all = diff
      ? Object.entries(diff).map(([f, c]) => {
        const from = fmt(t, c.from), to = fmt(t, c.to)
        return { key: f, label: fieldLabel(f), diff: true, from, to, full: `${fieldLabel(f)}: ${from} → ${to}` }
      })
      : parsed ? Object.keys(parsed).map(f => ({ key: f, label: fieldLabel(f), diff: false, full: `${fieldLabel(f)}: ${fmt(t, parsed[f])}` })) : []
    const limit = diff ? SUMMARY_LIMIT : SUMMARY_LIMIT + 1
    return { parsed, diff, chips: all.slice(0, limit), more: Math.max(0, all.length - limit) }
  }

  const detail = (r, m) => (m.diff ? (
    <DiffTable fieldLabel={t('audit.diffField')} fromLabel={t('audit.diffFrom')} toLabel={t('audit.diffTo')}
      rows={Object.entries(m.diff).map(([f, c]) => [f, fieldLabel(f), fmt(t, c.from), fmt(t, c.to)])} />
  ) : (
    <div className="flex flex-col gap-1 text-xs">
      <div className="text-muted-foreground">{String(r.action).endsWith('DELETE') ? t('ng.histSnapshotOld') : t('ng.histSnapshotNew')}</div>
      <ul className="mt-1 ml-1 flex list-none flex-col gap-0.5">
        {Object.entries(m.parsed).map(([f, v]) => (
          <li key={f} className="flex flex-wrap gap-2"><span className="font-semibold whitespace-nowrap">{fieldLabel(f)}</span><span className="font-mono break-all">{fmt(t, v)}</span></li>
        ))}
      </ul>
    </div>
  ))

  const target = (r) => (
    <span className="inline-flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5">
      <span className="font-semibold break-words">{r.name || `#${r.resource_id}`}</span>
      {r.team_name && <TeamBadge teamId={r.team_id} teamName={r.team_name} size={11} />}
    </span>
  )
  // "system" aktörü: UserBadge'in sabit Türkçe yedeği yerine i18n etiketi (Denetim Kaydı ile aynı)
  const actor = (r) => (r.actor ? <UserBadge username={r.actor} inline size="sm" systemLabel={t('audit.systemActor')} /> : <span className="text-muted-foreground">—</span>)
  const toggleRow = (r, m) => { if (m.parsed) setExpanded(prev => (prev === r.id ? null : r.id)) }

  const showSkeleton = loading && items.length === 0 && !error
  const emptyNow = !loading && !error && data && items.length === 0

  const desktop = (
    <div className={cn('relative mb-2.5 overflow-hidden rounded-lg border bg-card border-border', loading && items.length > 0 && 'opacity-70')}
      aria-busy={loading || undefined}>
      <Table className="text-[0.82em]">
        <TableHeader className="bg-muted/50">
          <TableRow className="hover:bg-transparent">
            <TableHead className={cn(TH, 'w-9')}><span className="sr-only">{t('audit.colDetail')}</span></TableHead>
            <TableHead className={TH}>{t('audit.colTime')}</TableHead>
            <TableHead className={TH}>{t('audit.colActor')}</TableHead>
            <TableHead className={TH}>{t('hist.colAction')}</TableHead>
            <TableHead className={TH}>{t('hist.colTarget')}</TableHead>
            <TableHead className={TH}>{t('hist.colChange')}</TableHead>
            <TableHead className={cn(TH, 'hidden lg:table-cell')} data-col="ip">{t('audit.colIp')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {showSkeleton && Array.from({ length: 4 }, (_, i) => (
            <TableRow key={`sk${i}`} className="hover:bg-transparent" data-skeleton>
              {[4, 16, 24, 16, 28, 40].map((w, j) => (
                <TableCell key={j} className={TD}><Skeleton className="h-4" style={{ width: `${w * 4}px`, maxWidth: '100%' }} /></TableCell>
              ))}
              <TableCell className={cn(TD, 'hidden lg:table-cell')}><Skeleton className="h-4 w-20" /></TableCell>
            </TableRow>
          ))}
          {items.map(r => {
            const m = model(r)
            const isOpen = expanded === r.id
            return (
              <Fragment key={r.id}>
                <TableRow aria-expanded={m.parsed ? isOpen : undefined}
                  data-state={isOpen ? 'selected' : undefined}
                  tabIndex={m.parsed ? 0 : undefined}
                  aria-label={m.parsed ? t('a11y.toggleRow', formatDateSec(r.at)) : undefined}
                  onClick={() => toggleRow(r, m)}
                  onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); toggleRow(r, m) } }}
                  className={cn('outline-none focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary',
                    'data-[state=selected]:bg-primary/10', m.parsed ? 'cursor-pointer' : 'cursor-default')}>
                  <TableCell className={cn(TD, 'w-9 text-muted-foreground')}>{m.parsed ? (isOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />) : null}</TableCell>
                  <TableCell className={cn(TD, 'whitespace-nowrap tabular-nums')}>
                    <SimpleTooltip content={formatDateSec(r.at)}>
                      <time dateTime={r.at} className="cursor-default">{relTime(r.at, t, now)}</time>
                    </SimpleTooltip>
                  </TableCell>
                  <TableCell className={cn(TD, 'max-w-[14rem]')}>{actor(r)}</TableCell>
                  <TableCell className={TD}><EventBadge kind={eventKind(r.action)} title={r.event_type}>{actLabel(r.action)}</EventBadge></TableCell>
                  <TableCell className={cn(TD, 'max-w-[16rem]')}>{target(r)}</TableCell>
                  <TableCell className={cn(TD, 'max-w-[34rem]')}><ChangeChips entries={m.chips} more={m.more} /></TableCell>
                  <TableCell className={cn(TD, 'hidden font-mono text-muted-foreground lg:table-cell')} data-col="ip">{r.identity_masked === true ? <MaskedValue /> : (r.ip || '—')}</TableCell>
                </TableRow>
                {isOpen && m.parsed && (
                  <TableRow className="hover:bg-transparent">
                    <TableCell colSpan={7} className="bg-muted/40 py-2.5 pr-3.5 pb-3 pl-10 whitespace-normal">{detail(r, m)}</TableCell>
                  </TableRow>
                )}
              </Fragment>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )

  // Telefon: kart listesi — üstte zaman + kim, sonra işlem rozeti + hedef, çipler sarar; açılım düğmesi 40 px.
  const mobile = (
    <ul className={cn('mb-2.5 flex list-none flex-col gap-2 p-0', loading && items.length > 0 && 'opacity-70')} aria-busy={loading || undefined}>
      {showSkeleton && Array.from({ length: 3 }, (_, i) => (
        <li key={`sk${i}`} data-skeleton>
          <Card className="gap-2 px-3 py-3 shadow-none">
            <div className="flex justify-between gap-2"><Skeleton className="h-3.5 w-20" /><Skeleton className="h-3.5 w-28" /></div>
            <Skeleton className="h-5 w-40" />
            <Skeleton className="h-5 w-full" />
          </Card>
        </li>
      ))}
      {items.map(r => {
        const m = model(r)
        const isOpen = expanded === r.id
        return (
          <li key={r.id}>
            <Card data-state={isOpen ? 'selected' : undefined} className="gap-2 px-3 py-2.5 shadow-none data-[state=selected]:border-primary/40">
              <div className="flex min-w-0 items-center justify-between gap-2 text-xs text-muted-foreground">
                <time dateTime={r.at} className="shrink-0 tabular-nums">{relTime(r.at, t, now)}</time>
                <span className="min-w-0 truncate">{actor(r)}</span>
              </div>
              <div className="flex min-w-0 flex-wrap items-center gap-2 text-sm">
                <EventBadge kind={eventKind(r.action)} title={r.event_type}>{actLabel(r.action)}</EventBadge>
                {target(r)}
              </div>
              <ChangeChips entries={m.chips} more={m.more} wrap />
              {m.parsed && (
                <Button type="button" variant="ghost" size="sm" className="-ml-2 h-10 self-start text-muted-foreground"
                  aria-expanded={isOpen} aria-label={t('a11y.toggleRow', formatDateSec(r.at))} onClick={() => toggleRow(r, m)}>
                  {isOpen ? <ChevronDown aria-hidden="true" /> : <ChevronRight aria-hidden="true" />} {t('hist.colChange')}
                </Button>
              )}
              {isOpen && m.parsed && (
                <div className="flex min-w-0 flex-col gap-2 border-t pt-2 text-xs">
                  <span className="text-muted-foreground tabular-nums">{formatDateSec(r.at)}{r.identity_masked === true ? <> · <MaskedValue /></> : r.ip ? ` · ${r.ip}` : ''}</span>
                  <div className="min-w-0 overflow-x-auto">{detail(r, m)}</div>
                </div>
              )}
            </Card>
          </li>
        )
      })}
    </ul>
  )

  return (
    <div className="admin-section" data-testid="admin-history">
      <div className="mt-[22px] mb-3 flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h3 className="m-0 text-base font-semibold">{t('hist.title')}</h3>
          <p className="m-0 text-sm text-muted-foreground">{t(`hist.desc.${resource}`)}</p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {open && (
            <SimpleTooltip content={t('hist.refresh')}>
              <Button variant="secondary" size="icon-sm" className="max-sm:size-10" onClick={() => setNonce(n => n + 1)} aria-label={t('hist.refresh')}><RefreshCw size={14} /></Button>
            </SimpleTooltip>
          )}
          <Button variant="secondary" className="max-sm:h-10" onClick={() => { if (open) { setOpen(false); onClearFilter?.() } else setOpen(true) }}>
            <History size={15} aria-hidden="true" /> {open ? t('ng.histHide') : t('ng.histShow')}
          </Button>
        </div>
      </div>

      {open && (
        <>
          {(filter || allTypes.length > 0) && (
            <div className="mb-2.5 flex flex-wrap items-center gap-2">
              {filter && (
                // Etkin kayıt süzgeci: X'li rozet (kaldırınca tüm kayıtlar)
                <Badge variant="outline" data-testid="history-filter" className="h-7 gap-1 rounded-full border-primary/40 bg-primary/10 pr-0.5 pl-2.5 text-primary">
                  <History aria-hidden="true" className="size-3" />
                  <span className="max-w-[16rem] truncate">{t('hist.filterBadge', filter.name)}</span>
                  {/* Görünür boyut küçük; dokunma alanı görünmez ::after ile 40 px'e genişler */}
                  <Button type="button" variant="ghost" size="icon-xs" className="relative rounded-full text-primary after:absolute after:-inset-2 after:content-[''] hover:bg-primary/15"
                    aria-label={t('ng.histFilterClear')} onClick={onClearFilter}><X aria-hidden="true" /></Button>
                </Badge>
              )}
              {allTypes.length > 0 && (
                <div className="flex flex-wrap gap-1.5" role="group" aria-label={t('hist.typeFilter')}>
                  <Toggle variant="outline" size="sm" className={CHIP} pressed={types.length === 0}
                    onPressedChange={() => setTypes([])}>{t('hist.allTypes')}</Toggle>
                  {allTypes.map(ty => (
                    <Toggle key={ty} variant="outline" size="sm" className={CHIP} pressed={types.includes(ty)}
                      onPressedChange={() => toggleType(ty)}>
                      {actLabel(ty.replace(/^(THRESHOLD|CONTACT|TEAM|USER)_/, ''))}
                    </Toggle>
                  ))}
                </div>
              )}
            </div>
          )}

          {error && <AlertBanner tone="danger" title={t('ng.histError')}>{error}</AlertBanner>}

          {emptyNow ? (
            <StatusBlock tone="neutral" icon={History} title={filter ? t('ng.histEmptyGroup') : t('ng.histEmpty')} className="mb-2.5" />
          ) : (!error || items.length > 0) && (isMobile ? mobile : desktop)}

          {total > 0 && <PaginationBar {...sp.bar} />}
          {data?.truncated && <p className={HINT}>{t('ng.histTruncated').replace('{n}', total)}</p>}
          {Number(data?.hidden) > 0 && <p className={HINT}>{t('ng.histHidden').replace('{n}', data.hidden)}</p>}
          <p className={HINT}>{t('ng.histRetentionNote')}</p>
        </>
      )}
    </div>
  )
}
