import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ChevronDown, ChevronUp, MessageSquarePlus, RefreshCw } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Spinner } from '@/components/shadcn/spinner'
import AlertBanner from '../ui/AlertBanner.jsx'
import ProblemCard from './ProblemCard.jsx'
import ProblemComposer from './ProblemComposer.jsx'
import { serverText } from './ProblemImages.jsx'

/** Çözülen sorunlar sayfa boyu ("Daha fazla" ile sonraki sayfa). */
export const RESOLVED_PAGE_SIZE = 10
/** Odaklanan kartın vurgusu bu kadar sürer. */
const HIGHLIGHT_MS = 4000

const EMPTY_RESOLVED = Object.freeze({ items: [], total: 0, page: -1, loading: false, failed: null })

function prefersReducedMotion() {
  try {
    return typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches === true
  } catch {
    return false
  }
}

/**
 * AÇIK SORUNLAR PANELİ (2026-11-26) — günün sayfasının (ve devralan nöbetçinin) taşıdığı açık sorunlar. Başlık
 * "Açık sorunlar (n)"; "Genel sorun ekle" (göreve bağlı olmayan sorun) yazma kutusunu panelin ÜSTÜNDE satır içi açar;
 * kartlar (`ProblemCard`); "Çözülenleri göster" çözülen sorunları sayfa sayfa okur (kendi sıra numarası + iptal; okunamazsa
 * hata bandı + yeniden dene — asla "çözülen yok" denmez).
 *
 * Açık liste ÜST bileşenindir (gün sayfasının `open_problems`); panel değişiklikleri geri çağrılarla bildirir:
 *  - `onProblemCreated(veri)` — yazma kutusunun `createProblem` yanıtı (`{problem, item?, progress}`), kayıt anında;
 *  - `onProblemChange(sorun)` — güncellenen sorun (çözüldüyse `status: 'RESOLVED'` → üst bileşen açık listeden düşürür;
 *    yeniden açıldıysa `OPEN` → ekler / günceller; görsel eklendiyse aynı sorun);
 *  - `onProblemDelete(id)` — açık listeden silinen sorun.
 * Çözülenler listesi panelin KENDİ durumudur: oradaki değişiklik yalnız sorun yeniden açılınca üst bileşene gider.
 *
 * `focusProblemId` verilirse o kart bir kez görünür alana kaydırılır ve kısa süre vurgulanır (`or_problem` adres anahtarı).
 *
 * Props: `{ teamId, teamName, date, problems, total, canWork, canCreate, focusProblemId, onProblemCreated, onProblemChange,
 * onProblemDelete }`. `canWork` = çöz / yeniden aç / düzenle (güne bağlı değil); `canCreate` = bu güne sorun yazmak (sunucu
 * gelecek, 7 günden eski ve duraklatılmış günde reddeder — verilmezse `canWork`).
 */
export default function ProblemsPanel({
  teamId, teamName, date, problems = [], total = 0, canWork = false, canCreate = null, focusProblemId = null,
  onProblemCreated = () => {}, onProblemChange = () => {}, onProblemDelete = () => {},
}) {
  const t = useT()
  const mayCreate = canCreate == null ? canWork : canCreate
  const list = useMemo(() => (Array.isArray(problems) ? problems : []), [problems])
  const openCount = Math.max(Number(total) || 0, list.length)
  const [composerOpen, setComposerOpen] = useState(false)
  const [resolvedOpen, setResolvedOpen] = useState(false)
  const [resolved, setResolved] = useState(EMPTY_RESOLVED)
  const [highlightId, setHighlightId] = useState(null)
  const rootRef = useRef(null)
  const seq = useRef(0)
  const abortRef = useRef(null)
  const focusedRef = useRef(null)
  const addBtnRef = useRef(null)

  // Takım değişince çözülenler listesi ve yazma kutusu sıfırlanır (başka takımın verisi görünmesin)
  useEffect(() => {
    seq.current += 1
    abortRef.current?.abort()
    setResolved(EMPTY_RESOLVED)
    setResolvedOpen(false)
    setComposerOpen(false)
  }, [teamId])
  useEffect(() => () => { seq.current += 1; abortRef.current?.abort() }, [])

  const loadResolved = useCallback(async (page) => {
    const my = ++seq.current
    abortRef.current?.abort()
    const ctrl = typeof AbortController === 'function' ? new AbortController() : null
    abortRef.current = ctrl
    setResolved((r) => ({ ...(page === 0 ? EMPTY_RESOLVED : r), loading: true, failed: null }))
    let res
    try {
      res = await api.oncallRoutines.problems(teamId, { status: 'resolved', page, size: RESOLVED_PAGE_SIZE, signal: ctrl?.signal })
    } catch {
      res = { success: false, status: 0, code: 'NETWORK_ERROR' }
    }
    if (my !== seq.current) return            // geç yanıt (takım değişti / yeni istek) düşer
    if (res === null) { setResolved((r) => ({ ...r, loading: false })); return }
    if (!res?.success || !Array.isArray(res.data?.items)) {
      setResolved((r) => ({ ...r, loading: false, failed: { text: serverText(res, t, 'or.prob.resolvedUnreadable'), res } }))
      return
    }
    const items = res.data.items
    setResolved((r) => {
      const base = page === 0 ? [] : r.items
      const seen = new Set(base.map((x) => x.id))
      return {
        items: [...base, ...items.filter((x) => !seen.has(x.id))],
        total: Number(res.data.total) || 0,
        page,
        loading: false,
        failed: null,
      }
    })
  }, [teamId, t])

  function toggleResolved() {
    const next = !resolvedOpen
    setResolvedOpen(next)
    if (next && resolved.page < 0 && !resolved.loading) loadResolved(0)
  }

  // Odak: istenen kart listede belirince bir kez görünür alana kaydırılır ve vurgulanır
  useEffect(() => {
    if (focusProblemId == null || String(focusedRef.current) === String(focusProblemId)) return
    const id = String(focusProblemId).replace(/[^0-9A-Za-z_-]/g, '')
    if (!id) return
    const el = rootRef.current?.querySelector(`[data-slot="or-problem"][data-id="${id}"]`)
    if (!el) return
    focusedRef.current = focusProblemId
    try { el.scrollIntoView?.({ block: 'center', behavior: prefersReducedMotion() ? 'auto' : 'smooth' }) } catch { /* yok */ }
    setHighlightId(id)
  }, [focusProblemId, list])
  // Vurgu kısa süre sonra söner (ayrı etki: liste yenilenince zamanlayıcı silinmesin)
  useEffect(() => {
    if (highlightId == null) return undefined
    const tm = setTimeout(() => setHighlightId(null), HIGHLIGHT_MS)
    return () => clearTimeout(tm)
  }, [highlightId])

  /** Açık listedeki kart değişti: üst bileşene; çözüldüyse ve çözülenler okunmuşsa oraya da eklenir. */
  const onOpenChange = useCallback((p) => {
    onProblemChange(p)
    // Çözülenler okunmuşsa onu da tutarlı tut: çözülen başa eklenir; "Geri al" ile yeniden açılan oradan düşer
    setResolved((r) => {
      if (r.page < 0 || !p) return r
      const has = r.items.some((x) => x.id === p.id)
      if (p.status === 'RESOLVED') return { ...r, items: [p, ...r.items.filter((x) => x.id !== p.id)], total: r.total + (has ? 0 : 1) }
      if (has) return { ...r, items: r.items.filter((x) => x.id !== p.id), total: Math.max(0, r.total - 1) }
      return r
    })
  }, [onProblemChange])

  /** Çözülenler listesindeki kart değişti: yeniden açıldıysa oradan düşer ve üst bileşene gider; yoksa yerinde güncellenir. */
  const onResolvedChange = useCallback((p) => {
    if (p?.status === 'OPEN') {
      setResolved((r) => ({ ...r, items: r.items.filter((x) => x.id !== p.id), total: Math.max(0, r.total - 1) }))
      onProblemChange(p)
      return
    }
    setResolved((r) => ({ ...r, items: r.items.map((x) => (x.id === p.id ? p : x)) }))
  }, [onProblemChange])

  const onResolvedDelete = useCallback((id) => {
    setResolved((r) => ({ ...r, items: r.items.filter((x) => x.id !== id), total: Math.max(0, r.total - 1) }))
  }, [])

  // Yazma kutusu kapanınca odak "Genel sorun ekle"ye döner (düğme kutu açıkken çizilmez — odak kapandıktan SONRA)
  const focusAddRef = useRef(false)
  const closeComposer = useCallback(() => {
    focusAddRef.current = true
    setComposerOpen(false)
  }, [])
  useEffect(() => {
    if (composerOpen || !focusAddRef.current) return
    focusAddRef.current = false
    addBtnRef.current?.focus()
  }, [composerOpen])

  const moreResolved = resolved.page >= 0 && resolved.items.length < resolved.total

  return (
    <Card ref={rootRef} data-slot="or-problems" className="@container min-w-0 gap-3 rounded-xl py-4">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2 px-4">
        <h3 className="flex items-center gap-2 text-sm font-semibold">
          {t('or.prob.panelTitle')}
          <Badge variant={openCount > 0 ? 'secondary' : 'outline'} className="rounded-md tabular-nums" data-slot="or-problems-count">
            {openCount}
          </Badge>
        </h3>
        {mayCreate && !composerOpen && (
          <Button ref={addBtnRef} type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10"
            data-slot="or-problem-add-general" onClick={() => setComposerOpen(true)}>
            <MessageSquarePlus aria-hidden="true" />{t('or.prob.addGeneral')}
          </Button>
        )}
      </div>

      <div className="flex min-w-0 flex-col gap-3 px-4">
        {composerOpen && mayCreate && (
          <ProblemComposer teamId={teamId} date={date} onCreated={onProblemCreated} onImagesDone={onProblemChange}
            onClose={closeComposer} />
        )}

        {list.length > 0 ? (
          <ul className="flex min-w-0 flex-col gap-2" data-slot="or-problems-open">
            {list.map((p) => (
              <li key={p.id} className="min-w-0">
                <ProblemCard teamId={teamId} teamName={teamName} problem={p} canWork={canWork}
                  highlighted={highlightId != null && String(p.id) === highlightId}
                  onChange={onOpenChange} onDelete={onProblemDelete} />
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted-foreground" data-slot="or-problems-empty">{t('or.prob.noneOpen')}</p>
        )}
        {openCount > list.length && (
          <p className="text-xs text-muted-foreground" data-slot="or-problems-more-open">
            {t('or.prob.moreOpen', openCount - list.length, list.length)}
          </p>
        )}

        <div className="flex min-w-0 flex-col gap-2 border-t pt-3">
          <Button type="button" size="sm" variant="ghost" className="self-start pointer-coarse:min-h-10"
            aria-expanded={resolvedOpen} data-slot="or-problems-resolved-toggle" onClick={toggleResolved}>
            {resolvedOpen ? <ChevronUp aria-hidden="true" /> : <ChevronDown aria-hidden="true" />}
            {resolvedOpen ? t('or.prob.hideResolved') : t('or.prob.showResolved')}
          </Button>
          {resolvedOpen && (
            <div className="flex min-w-0 flex-col gap-2" data-slot="or-problems-resolved">
              {resolved.items.length > 0 && (
                <ul className="flex min-w-0 flex-col gap-2">
                  {resolved.items.map((p) => (
                    <li key={p.id} className="min-w-0">
                      <ProblemCard teamId={teamId} teamName={teamName} problem={p} canWork={canWork}
                        onChange={onResolvedChange} onDelete={onResolvedDelete} />
                    </li>
                  ))}
                </ul>
              )}
              {resolved.loading && resolved.items.length === 0 && (
                <div className="flex flex-col gap-2" data-slot="or-problems-resolved-loading" aria-busy="true">
                  <Skeleton className="h-20 w-full rounded-lg" />
                  <Skeleton className="h-20 w-full rounded-lg" />
                </div>
              )}
              {resolved.failed && (
                <AlertBanner tone="danger" role="alert" details={resolved.failed.res}
                  title={t('or.prob.resolvedUnreadableTitle')}
                  actions={(
                    <Button type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10"
                      data-slot="or-problems-resolved-retry"
                      onClick={() => loadResolved(resolved.page < 0 ? 0 : resolved.page + (resolved.items.length ? 1 : 0))}>
                      <RefreshCw aria-hidden="true" />{t('or.prob.retry')}
                    </Button>
                  )}>
                  {resolved.failed.text}
                </AlertBanner>
              )}
              {!resolved.loading && !resolved.failed && resolved.page >= 0 && resolved.items.length === 0 && (
                <p className="text-sm text-muted-foreground" data-slot="or-problems-resolved-empty">{t('or.prob.noneResolved')}</p>
              )}
              {moreResolved && !resolved.failed && (
                <Button type="button" size="sm" variant="outline" className="self-start pointer-coarse:min-h-10"
                  disabled={resolved.loading} data-slot="or-problems-resolved-more" onClick={() => loadResolved(resolved.page + 1)}>
                  {resolved.loading && <Spinner />}
                  {t('or.prob.moreResolved', resolved.total - resolved.items.length)}
                </Button>
              )}
            </div>
          )}
        </div>
      </div>
    </Card>
  )
}
