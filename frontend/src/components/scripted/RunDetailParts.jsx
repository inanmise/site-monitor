import { useEffect, useRef, useState } from 'react'
import { Gauge, Lock, ExternalLink, PackagePlus, TriangleAlert, History } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { failureOf } from '../checks/checkFailureModel.js'
import { CheckFailurePanel } from '../checks/CheckFailurePanel.jsx'
import { certDaysTone, runContextChips, versionRegression } from './runDetailModel.js'

// Çip / rozet: sarılır, hap köşesi yok (ad ve uzun metin KESİLMEZ).
const CHIP = 'h-auto min-h-5 overflow-visible rounded-md px-1.5 py-0 text-[11px] font-medium whitespace-normal [overflow-wrap:anywhere]'
const CTX_TONE = {
  neutral: 'text-muted-foreground',
  warn: 'border-amber-500/40 bg-amber-500/10 text-amber-800 dark:text-amber-300',
  bad: 'border-destructive/40 bg-destructive/10 text-destructive',
}
const SECTION_TITLE = 'm-0 flex items-center gap-1.5 text-[.92em] font-semibold'

/**
 * Koşumun k6 EŞİKLERİ (2026-10-31, k6r D4): her eşik ifadesi + sonucu; düşen eşik kırmızı ve önce. Sunucu alanı
 * (`thresholds`) yoksa — eski satır, eski sunucu — hiçbir şey çizilmez. Kancalar: `threshold-list`, `threshold-row`
 * + `data-ok`.
 */
export function ThresholdList({ thresholds }) {
  const t = useT()
  if (!Array.isArray(thresholds) || thresholds.length === 0) return null
  const rows = [...thresholds].sort((a, b) => Number(a.ok) - Number(b.ok))
  const failed = rows.filter((x) => !x.ok).length
  return (
    <section data-slot="threshold-list" aria-label={t('scripted.thr.title')} className="flex min-w-0 flex-col gap-1">
      <h4 className={SECTION_TITLE}>
        <Gauge aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />{t('scripted.thr.title')}
        <span className={cn('font-normal tabular-nums', failed ? 'text-destructive' : 'text-muted-foreground')}>
          {failed ? t('scripted.thr.crossedCount', failed, rows.length) : t('scripted.thr.allOk', rows.length)}
        </span>
      </h4>
      <ul className="m-0 flex list-none flex-col gap-1 p-0">
        {rows.map((x, i) => (
          <li key={`${i}:${x.metric}:${x.expr}`} data-slot="threshold-row" data-ok={x.ok ? 'true' : 'false'}
            className={cn('flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 rounded-md border px-2.5 py-1.5 text-xs',
              x.ok ? 'bg-muted/30' : 'border-destructive/30 bg-destructive/5 dark:bg-destructive/10')}>
            <code className="min-w-0 font-mono font-semibold [overflow-wrap:anywhere]">{x.metric}</code>
            {x.expr && <code className="min-w-0 font-mono text-muted-foreground [overflow-wrap:anywhere]">{x.expr}</code>}
            <ToneBadge tone={x.ok ? 'success' : 'danger'} className="ml-auto font-semibold">
              {x.ok ? t('scripted.thr.ok') : t('scripted.thr.crossed')}
            </ToneBadge>
          </li>
        ))}
      </ul>
    </section>
  )
}

/**
 * Koşumun eriştiği HTTPS hostları ve TLS sürümleri; host envanterdeyse sertifikanın kalan günü + sertifika penceresine
 * bağlantı, değilse "sertifika izlenmiyor" (+ envanter yazma izni varsa "Envantere ekle" — envanteri bu adla açar).
 * Sertifika durumu bilinmiyorsa (izleyen kişi o envanter satırını okuyamıyor) nötr "bilinmiyor" — asla yeşil değil.
 * Kancalar: `tls-list`, `tls-row` + `data-state` (tracked|untracked|unknown).
 */
export function TlsList({ tls }) {
  const t = useT()
  const { canEdit } = usePermissions()
  if (!Array.isArray(tls) || tls.length === 0) return null
  const canAdd = canEdit('inventory.crud')
  return (
    <section data-slot="tls-list" aria-label={t('scripted.tls.title')} className="flex min-w-0 flex-col gap-1">
      <h4 className={SECTION_TITLE}>
        <Lock aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />{t('scripted.tls.title')}
      </h4>
      <ul className="m-0 flex list-none flex-col gap-1 p-0">
        {tls.map((x) => (
          <li key={x.host} data-slot="tls-row" data-state={x.state}
            className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 rounded-md border bg-muted/30 px-2.5 py-1.5 text-xs">
            <code className="min-w-0 flex-[1_1_12rem] font-mono font-semibold [overflow-wrap:anywhere]">{x.host}</code>
            {x.version && <Badge variant="outline" data-chip="tls-version" className={cn(CHIP, 'font-mono')}>{x.version}</Badge>}
            {x.requests != null && <span className="text-muted-foreground tabular-nums">{t('scripted.tls.requests', x.requests)}</span>}
            <span className="flex min-w-0 flex-wrap items-center gap-1.5">
              {x.state === 'tracked' && x.cert && (<>
                <ToneBadge tone={certDaysTone(x.cert.days)} data-slot="tls-cert-days" className="font-semibold tabular-nums">
                  {x.cert.days == null ? t('scripted.tls.daysUnknown')
                    : x.cert.days < 0 ? t('scripted.tls.expired') : t('scripted.tls.days', x.cert.days)}
                </ToneBadge>
                <Button type="button" variant="ghost" size="xs" data-slot="tls-open-cert"
                  className="h-auto gap-1 px-1.5 py-0.5 text-xs font-semibold text-primary hover:text-primary pointer-coarse:min-h-10"
                  aria-label={t('a11y.rowAction', x.cert.domain, t('scripted.tls.openCert'))}
                  onClick={() => navigateTo('dashboard', { domain: x.cert.domain, open: 'cert' })}>
                  {t('scripted.tls.openCert')}<ExternalLink aria-hidden="true" className="size-3.5" />
                </Button>
              </>)}
              {x.state === 'untracked' && (<>
                <ToneBadge tone="muted" data-slot="tls-untracked" className="font-medium">{t('scripted.tls.untracked')}</ToneBadge>
                {canAdd && (
                  <Button type="button" variant="ghost" size="xs" data-slot="tls-add-inventory"
                    className="h-auto gap-1 px-1.5 py-0.5 text-xs font-semibold text-primary hover:text-primary pointer-coarse:min-h-10"
                    aria-label={t('a11y.rowAction', x.host, t('scripted.tls.addInventory'))}
                    onClick={() => navigateTo('domains', { i_q: x.host })}>
                    <PackagePlus aria-hidden="true" className="size-3.5" />{t('scripted.tls.addInventory')}
                  </Button>
                )}
              </>)}
              {x.state === 'unknown' && <ToneBadge tone="muted" data-slot="tls-unknown" className="font-medium">{t('scripted.tls.unknown')}</ToneBadge>}
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}

/**
 * Koşum bağlamı çipleri (süreç bütçesi, istek zaman aşımı, çıkış yolu, kurumsal CA, ters bütçe) — sunucunun Türkçe
 * "süreç bütçesi=…" satırının arayüz dilindeki karşılığı. Bağlam yoksa hiçbir şey çizilmez (çağıran o zaman Türkçe
 * satırı hata metninde bugünkü gibi bırakır). Ters bütçede açıklayıcı bir cümle de eklenir. Kanca: `run-context`, çip
 * başına `data-ctx`.
 */
export function RunContextChips({ context, className }) {
  const t = useT()
  const chips = runContextChips(context)
  if (!chips.length) return null
  const inverted = chips.find((c) => c.key === 'inverted')
  return (
    <div data-slot="run-context" className={cn('flex min-w-0 flex-col gap-1', className)}>
      <span className="sr-only">{t('scripted.ctx.title')}</span>
      <div className="flex min-w-0 flex-wrap items-center gap-1">
        {chips.map((c) => (
          <Badge key={c.key} variant="outline" data-ctx={c.key} data-tone={c.tone} className={cn(CHIP, CTX_TONE[c.tone])}>
            {t(`scripted.ctx.${c.key}`, ...c.params)}
          </Badge>
        ))}
      </div>
      {inverted && <p data-slot="run-context-inverted" className="m-0 text-xs text-destructive">{t('scripted.ctx.invertedHint', ...inverted.params)}</p>}
    </div>
  )
}

/**
 * Sentetik koşumun HATA SINIFI paneli — ortak `CheckFailurePanel` (Neden / Etkisi / Ne yapmalı + kayıttaki ayrıntılar).
 * Sunucu kodu (`failure_reason`) varsa o; eski satırda en yakın kod (`legacy`). Eski satır için kod çıkarılamıyorsa
 * (UNKNOWN) panel ÇİZİLMEZ — hata bandı zaten metni ve çıkış kodunu söylüyor; "nedeni bilinmiyor" paneli gürültü olurdu.
 * Ham hata metni bandda olduğu için panelde tekrarlanmaz (`showRaw=false`).
 */
export function ScriptedFailureClass({ check, monitor }) {
  const f = failureOf('scripted', check, monitor)
  if (!f || (f.legacy && f.code === 'UNKNOWN')) return null
  return <CheckFailurePanel type="scripted" check={check} monitor={monitor} showRaw={false} />
}

/**
 * "BU SÜRÜM BOZDU" bandı (2026-10-31, k6r D4): sürüm geçmişinden (`run_count`, `fail_count`) en yeni sürümün hata oranı
 * önceki sürümden belirgin yüksekse (eşikler `runDetailModel.VERSION_REGRESSION`) uyarı + düzenleme yetkisi varsa
 * "önceki sürümü editöre yükle". Yükleme sayfanın MEVCUT işleyicisini çağırır (`onLoad(versionRow, body)` — Sürümler
 * sekmesindeki "Bu sürümü editöre yükle" ile aynı yol; doğrudan kaydetmez, kullanıcı görür ve kaydedince yeni sürüm olur).
 * Sürüm listesi okunamazsa bant ÇİZİLMEZ (bir iddiada bulunulmaz; Sürümler sekmesi okuma hatasını kendisi söyler).
 * Kanca: `version-regression`.
 */
export function VersionRegressionBand({ monitor, canEdit = false, onLoad }) {
  const t = useT()
  const [rows, setRows] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const id = monitor?.id
  const version = monitor?.script_version
  // Geç yanıt koruması (inceleme 6, 2026-10-31): her yükleme bir sıra numarası alır; izleme / sürüm değişince ya da bant
  // ayrılınca numara ilerler → eski yanıt onLoad ÇAĞIRMAZ (başka izlemenin editörü açılmaz, açık form ezilmez).
  const loadSeq = useRef(0)
  const currentId = useRef(id)
  currentId.current = id
  useEffect(() => {
    setRows(null); setError(null); setBusy(false)
    if (id == null || !version) return () => { loadSeq.current++ }
    let alive = true
    Promise.resolve(api.monitoring.getScriptedVersions?.(id)).then((r) => {
      if (alive && r?.success) setRows(r.data?.versions || [])
    }).catch(() => { /* bant isteğe bağlı bir ipucu — okuma hatasında sessiz */ })
    return () => { alive = false; loadSeq.current++ }
  }, [id, version])

  const reg = versionRegression(rows)
  if (!reg) return null
  const pct = (r) => Math.round(r * 100)

  async function load() {
    const my = ++loadSeq.current
    const forId = id
    const prev = reg.prev
    const stale = () => my !== loadSeq.current || currentId.current !== forId
    setBusy(true); setError(null)
    try {
      const r = await api.monitoring.getScriptedVersion(forId, prev.id)
      if (stale()) return
      if (!r?.success) { setError(r?.error || t('scripted.versionBodyLoadFailed')); return }
      onLoad?.(prev, r.data)
    } catch (e) {
      if (!stale()) setError(e?.message || t('scripted.versionBodyLoadFailed'))
    } finally {
      if (!stale()) setBusy(false)
    }
  }

  return (
    <div data-slot="version-regression" data-version={reg.latest.version} data-prev={reg.prev.version}>
      <AlertBanner tone="warning" icon={TriangleAlert} className="mb-0" title={t('scripted.reg.title', reg.latest.version)}
        actions={canEdit && onLoad ? (
          <Button type="button" size="sm" variant="secondary" onClick={load} disabled={busy} aria-busy={busy || undefined}
            data-slot="version-regression-load" className="pointer-coarse:h-10">
            <History aria-hidden="true" />{t('scripted.reg.load', reg.prev.version)}
          </Button>
        ) : null}>
        <span className="[overflow-wrap:anywhere]">
          {t('scripted.reg.body', reg.latest.version, reg.latest.fail_count, reg.latest.run_count, pct(reg.rate),
            reg.prev.version, reg.prev.fail_count, reg.prev.run_count, pct(reg.prevRate))}
        </span>
        {error && <span data-slot="version-regression-error" className="mt-1 block text-destructive">{error}</span>}
      </AlertBanner>
    </div>
  )
}
