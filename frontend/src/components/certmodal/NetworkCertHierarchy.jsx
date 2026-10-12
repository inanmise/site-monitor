import { useEffect, useState } from 'react'
import { ChevronDown, Info, ListTree, RefreshCw } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'
import CertHierarchyView from './CertHierarchyView.jsx'
import { hierarchySummary, missingPlaceholderKey, rootLabelKey, rootSourceTone, rootWhyKey } from './networkHierarchyModel.js'

/**
 * Ağdan kontrol edilen sertifikanın TARAYICI GİBİ hiyerarşisi (2026-10-12, kullanıcı isteği: otomatik çekilen sertifikalarda
 * da Root / Intermediate / Leaf net görünsün, her birinin bitiş tarihi ayrı ayrı izlensin). SSL sekmesinde "Hiyerarşi
 * (tarayıcı gibi)" seçilince tembel yüklenir; varsayılan zincir görünümü (SslChainView) değişmez.
 *
 * <p>`GET /api/certificates/{domain}/hierarchy` sunucuya kontrolle AYNI yoldan bağlanıp sunulan zinciri okur ve kökü
 * güven deposundan tamamlar. Sonuç `cache` (WeakMap) içinde önizleme verisine (`cacheKey`) bağlı tutulur: görünümler
 * arasında gidip gelmek yeniden bağlanmaz; "Yeniden kontrol et" yeni önizleme getirince hiyerarşi de yenilenir.
 *
 * <p>Üstte özet kartı: kök nereden (güven deposu — sistem / kurumsal, sunucu, kendinden imzalı, bulunamadı), sunucunun
 * gönderdiği sertifika sayısı, sıra hatası, proxy, okunma anı; altında ağaç (CertHierarchyView — her satırda rol rozeti ve
 * bitiş tarihi); varsa hiyerarşiye girmeyen sertifikalar katlanır listede. Hata → "Yeniden dene"li StatusBlock.
 * Test kancaları: `cert-hierarchy-summary` (+ `data-root-source`), `cert-hierarchy-extras`.
 */
export default function NetworkCertHierarchy({ domain, cache = null, cacheKey = null, route = null }) {
  const t = useT()
  const [state, setState] = useState(() => {
    const hit = cache && cacheKey ? cache.get(cacheKey) : null
    return hit?.data ? { status: 'ready', data: hit.data } : { status: 'loading' }
  })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let alive = true
    const keyed = !!(cache && cacheKey)
    const entry = keyed ? cache.get(cacheKey) : null
    if (entry?.data) { setState({ status: 'ready', data: entry.data }); return undefined }
    setState({ status: 'loading' })
    // Uçuştaki istek paylaşılır (yeniden bağlanma / StrictMode çift etkisi ikinci el sıkışma yapmaz); hata önbelleğe girmez.
    let pending = entry?.promise
    if (!pending) {
      pending = api.certHierarchy(domain, route || undefined).then((res) => {
        if (res?.success && Array.isArray(res.data?.nodes)) {
          if (keyed) cache.set(cacheKey, { data: res.data })
          return { ok: true, data: res.data }
        }
        if (keyed) cache.delete(cacheKey)
        return { ok: false, message: res?.error || null }
      }, (e) => {
        if (keyed) cache.delete(cacheKey)
        return { ok: false, message: e?.message || null }
      })
      if (keyed) cache.set(cacheKey, { promise: pending })
    }
    pending.then((r) => {
      if (!alive) return
      setState(r.ok ? { status: 'ready', data: r.data } : { status: 'error', message: r.message })
    })
    return () => { alive = false }
  }, [domain, cache, cacheKey, attempt]) // eslint-disable-line react-hooks/exhaustive-deps -- route önizlemeyle (cacheKey) birlikte değişir

  if (state.status === 'loading') return <LoadingBlock label={t('chier.net.loading')} fullWidth />
  if (state.status === 'error') {
    return (
      <StatusBlock tone="danger" icon={ListTree} role="alert" title={t('chier.net.loadFailed')}
        description={state.message || t('chier.net.loadFailedHint')}
        actions={(
          <Button type="button" variant="outline" className="h-10 gap-1.5" onClick={() => setAttempt((n) => n + 1)}>
            <RefreshCw aria-hidden="true" />{t('sslv.retry')}
          </Button>
        )} />
    )
  }
  const summary = hierarchySummary(state.data)
  return (
    <div data-slot="network-cert-hierarchy" className="flex min-w-0 flex-col gap-3">
      <Summary summary={summary} t={t} />
      <CertHierarchyView nodes={state.data.nodes}
        missingRootText={t(missingPlaceholderKey(summary))} emptyText={t('chier.net.empty')}
        missingIssuerLabel={summary.missingIssuer} />
      <Extras extras={summary.extras} t={t} />
    </div>
  )
}

// Rozet tonu → shadcn varyantı (projede "success" varyantı yok; sağlıklı ton ValidityBadge ile aynı sınıflar)
const TONE = {
  success: { variant: 'secondary', className: 'bg-success/15 text-success dark:bg-success/20' },
  warning: { variant: 'warning', className: undefined },
  destructive: { variant: 'destructive', className: undefined },
  secondary: { variant: 'secondary', className: undefined },
}

function Summary({ summary, t }) {
  const why = rootWhyKey(summary)
  const root = summary.rootSource
  const tone = rootSourceTone(summary)
  return (
    <Card data-slot="cert-hierarchy-summary" data-root-source={root} className="min-w-0 gap-2 px-3 py-3 shadow-none sm:px-4">
      <div className="flex min-w-0 flex-wrap items-center gap-1.5">
        {/* Uzun etiket ("Kök: sunucu gönderdi (güven deposunda değil)") telefonda SARAR — hap değil, kırpılmaz */}
        <Badge variant={TONE[tone].variant} className={cn(TONE[tone].className, 'h-auto rounded-md whitespace-normal overflow-visible text-left')}
          data-slot="cert-hierarchy-root-source" data-tone={tone}>
          {t(rootLabelKey(summary))}
        </Badge>
        {summary.presented != null && (
          <Badge variant="secondary" className="tabular-nums">{t('chier.net.presented', summary.presented)}</Badge>
        )}
        {summary.orderOk === false && (
          <Badge variant="warning" data-slot="cert-hierarchy-order">{t('chier.net.orderBad')}</Badge>
        )}
        {summary.viaProxy && <Badge variant="outline">{t('chier.net.viaProxy')}</Badge>}
      </div>
      {why && (
        <p className="m-0 flex min-w-0 items-start gap-1.5 text-[13px] text-muted-foreground">
          <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <span className="min-w-0 [overflow-wrap:anywhere]">
            {t(why)}
            {summary.orderOk === false && <> {t('chier.net.orderBadHint')}</>}
          </span>
        </p>
      )}
      {summary.checkedAt && (
        <p className="m-0 text-xs text-muted-foreground tabular-nums">
          {summary.port != null
            ? t('chier.net.checkedAtPort', formatDate(summary.checkedAt), summary.port)
            : t('chier.net.checkedAt', formatDate(summary.checkedAt))}
        </p>
      )}
    </Card>
  )
}

function Extras({ extras, t }) {
  const [open, setOpen] = useState(false)
  if (!extras.length) return null
  return (
    <Collapsible open={open} onOpenChange={setOpen} data-slot="cert-hierarchy-extras"
      className="min-w-0 rounded-md border border-dashed px-3 py-2">
      <CollapsibleTrigger asChild>
        <Button type="button" variant="ghost" size="sm"
          className="-mx-2 h-auto min-h-10 w-full justify-start gap-1.5 px-2 text-left text-[13px] font-medium whitespace-normal">
          <ChevronDown aria-hidden="true" className={cn('size-4 shrink-0 transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
          <span className="min-w-0 [overflow-wrap:anywhere]">{t('chier.net.extras', extras.length)}</span>
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <ul className="m-0 flex list-none flex-col divide-y p-0">
          {extras.map((e, i) => (
            <li key={`${e.subjectDn || e.label}-${i}`} className="flex min-w-0 flex-col gap-0.5 py-2">
              <span className="text-[13px] font-medium [overflow-wrap:anywhere]">{e.label}</span>
              {e.notAfter && (
                <span className={cn('text-xs tabular-nums', e.expired ? 'text-destructive' : 'text-muted-foreground')}>
                  {t('chier.expiresOn', formatDate(e.notAfter))}
                </span>
              )}
            </li>
          ))}
        </ul>
      </CollapsibleContent>
    </Collapsible>
  )
}
