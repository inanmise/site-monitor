import { useState, useRef, useEffect, useCallback } from 'react'
import { api, getRecentFailures } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Search, Globe, ShieldCheck, Copy, Check, History, X, ExternalLink, ClipboardCopy } from 'lucide-react'
import DomainExpiryTrace, { expirySummaryText } from '../DomainExpiryTrace.jsx'
import { Spinner } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import ToneBadge from './ToneBadge.jsx'
import { SettingsSection } from './SettingsControls.jsx'
import RateLimitBanner from '../diagnostics/RateLimitBanner.jsx'
import { normaliseDomainInput, isValidDomain, readRecent, pushRecent, clearRecent, wasRateLimited } from '../diagnostics/diagModel.js'
import { copyText } from '../../utils/copyText.js'
import { navigateTo } from '../../utils/navigate.js'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { cn } from '@/lib/utils'

/**
 * Ayarlar → Alan Adı Tanılama — alan adı süre bitişi sorgusu (PSL → IANA → RDAP → rdap.org → WHOIS izi) +
 * proxy CA zinciri yakalama (yapıştırılmaya hazır PEM).
 *
 * <p>2026-09-26 zenginleştirme: sorgu formu `ui/Field` ile anında doğrulama (URL yapıştırılırsa sunucu adına
 * indirgenir), son sorgular (localStorage, en fazla 8), sonuç başlığında hızlı eylemler (envanterde aç —
 * kayıt varsa; özeti kopyala), 429 → geri sayımlı şerit, özet kartı ve zincir şeridi `DomainExpiryTrace`'te.
 * Telefonda tek sütun; her dokunma hedefi ≥ 40 px. Backend uçları değişmedi.
 */
export default function DomainDiagnostics() {
  const t = useT()
  const toast = useToast()
  const [domain, setDomain] = useState('')
  const [invalid, setInvalid] = useState(false)
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [rateLimited, setRateLimited] = useState(false)
  const [recent, setRecent] = useState(() => readRecent())
  const [inv, setInv] = useState(null)        // { item } | { item: null } — envanter eşleşmesi (hızlı eylem)

  // Proxy CA zinciri yakalama
  const [caLoading, setCaLoading] = useState(false)
  const [ca, setCa] = useState(null)      // { ok, chain, ca_pem, ca_count, error, error_class }
  const [copied, setCopied] = useState(false)
  // Kopyalama geri bildirimi zamanlayıcısı ref'te + unmount temizliği (CopyButton.jsx deseni):
  // panel 2 sn dolmadan kapanırsa zamanlayıcı ayakta kalmasın.
  const copyTimer = useRef(null)
  useEffect(() => () => clearTimeout(copyTimer.current), [])

  const run = useCallback(async (raw) => {
    const d = normaliseDomainInput(raw ?? domain)
    if (!d) return
    if (!isValidDomain(d)) { setInvalid(true); return }
    setInvalid(false)
    setDomain(d)
    setLoading(true); setError(null); setResult(null); setRateLimited(false); setInv(null)
    try {
      try {
        const res = await api.admin.runDomainExpiryDiagnostics(d)
        if (res?.success) {
          setResult(res.data)
          setRecent(pushRecent(d))
          lookupInventory(res.data?.registrable || d)
        } else if (wasRateLimited(res, '/admin/diagnostics/domain-expiry', getRecentFailures())) {
          setRateLimited(true)
        } else {
          setError(res?.error || t('dexp.error')); if (res?.error) toast.error(res.error)
        }
      } catch (e) {
        setError(e?.message || t('dexp.error'))
      }
    } finally {
      setLoading(false)
    }
  }, [domain, t]) // eslint-disable-line react-hooks/exhaustive-deps

  /** Envanterde bu kayıtlı alan adı var mı (hızlı eylem için; en iyi çaba — hata sorguyu bozmaz). */
  async function lookupInventory(registrable) {
    try {
      const res = await api.admin.getInventoryByDomain(registrable)
      setInv({ item: res?.success && res.data ? res.data : null })
    } catch { setInv({ item: null }) }
  }

  async function copySummary() {
    if (!result) return
    if (await copyText(expirySummaryText(result, t))) toast.success(t('dexp.summaryCopied'))
    else toast.error(t('dexp.copyFailed'))
  }

  async function captureCa() {
    setCaLoading(true); setCa(null); setCopied(false)
    try {
      try {
        const res = await api.admin.captureProxyCaChain()   // varsayılan host: data.iana.org
        setCa(res?.success ? res.data : { ok: false, error: res?.error || t('dexp.caError') })
      } catch (e) {
        setCa({ ok: false, error: e?.message || t('dexp.caError') })
      }
    } finally {
      setCaLoading(false)
    }
  }

  async function copyPem() {
    if (!ca?.ca_pem) return
    try {
      await navigator.clipboard.writeText(ca.ca_pem)
      setCopied(true)
      clearTimeout(copyTimer.current)
      copyTimer.current = setTimeout(() => setCopied(false), 2000)
      toast.success(t('dexp.caCopied'))
    } catch { toast.error(t('dexp.caCopyErr')) }
  }

  const shortDn = (dn) => {
    if (!dn) return '—'
    const cn = /CN=([^,]+)/i.exec(dn)
    return cn ? cn[1] : dn
  }

  const canQuery = !loading && normaliseDomainInput(domain).length > 0

  return (
    <div className="flex max-w-[920px] flex-col gap-6" data-testid="domain-diagnostics">
      <SettingsSection level={3}
        title={<span className="inline-flex items-center gap-2"><Globe size={18} aria-hidden="true" /> {t('dexp.title')}</span>}
        description={t('dexp.desc')} contentClassName="flex flex-col gap-3">
        {/* @container: form satırı KABIN genişliğine göre kırılır — Ayarlar 768 px'te dar bir sütun bırakır (2026-09-27) */}
        <div className="@container">
          <form className="flex flex-col gap-2 @md:flex-row @md:items-start" onSubmit={(e) => { e.preventDefault(); run() }}>
            <Field label={t('dexp.inputLabel')} hint={t('dexp.inputHint')} error={invalid ? t('dexp.invalid') : null} className="mb-0 min-w-0 flex-1 @md:max-w-md">
              {({ id, describedBy, invalid: inv }) => (
                <Input id={id} type="text" inputMode="url" autoComplete="off" spellCheck={false} value={domain} placeholder="example.com"
                  aria-describedby={describedBy} aria-invalid={inv}
                  onChange={(e) => { setDomain(e.target.value); if (invalid) setInvalid(false) }}
                  disabled={loading} />
              )}
            </Field>
            <Button type="submit" disabled={!canQuery} aria-busy={loading || undefined} className="shrink-0 @md:mt-[26px]">
              {loading ? <Spinner size={14} inline decorative /> : <Search size={14} />}
              {loading ? t('dexp.running') : t('dexp.query')}
            </Button>
          </form>
        </div>

        {recent.length > 0 && (
          <div data-slot="dexp-recent" className="flex flex-wrap items-center gap-1.5">
            <span className="inline-flex items-center gap-1 text-xs text-muted-foreground"><History aria-hidden="true" className="size-3.5" /> {t('dexp.recent')}:</span>
            {recent.map((d) => (
              <Button key={d} type="button" variant="outline" size="xs" className="h-7 max-w-full rounded-full font-mono font-normal pointer-coarse:h-10"
                onClick={() => run(d)} disabled={loading} aria-label={t('dexp.recentRun', d)}>
                <span className="truncate">{d}</span>
              </Button>
            ))}
            <Button type="button" variant="ghost" size="icon-xs" className="text-muted-foreground pointer-coarse:size-10"
              onClick={() => setRecent(clearRecent())} aria-label={t('dexp.recentClear')} title={t('dexp.recentClear')}>
              <X aria-hidden="true" />
            </Button>
          </div>
        )}

        {rateLimited && <RateLimitBanner onRetry={() => run()} className="mb-0" />}
        {error && <AlertBanner tone="danger" role="alert" className="mb-0">{error}</AlertBanner>}

        {result && (
          <div className="@container flex min-w-0 flex-col gap-3" data-slot="dexp-lookup">
            <div className="flex flex-col gap-2 @md:flex-row @md:items-center @md:justify-between">
              <h4 className="min-w-0 text-sm font-semibold break-all">{t('dexp.lookupTitle', result.domain || domain)}</h4>
              <div className="flex flex-wrap gap-2">
                <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10"
                  disabled={!inv?.item} title={inv && !inv.item ? t('dexp.notInInventory') : undefined}
                  onClick={() => navigateTo('domains', { i_q: inv?.item?.domain || result.registrable })}>
                  <ExternalLink aria-hidden="true" /> {t('dexp.openInventory')}
                  {inv && !inv.item && <span className="sr-only"> — {t('dexp.notInInventory')}</span>}
                </Button>
                <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={copySummary}>
                  <ClipboardCopy aria-hidden="true" /> {t('dexp.copySummary')}
                </Button>
              </div>
            </div>
            {inv && !inv.item && <p className="text-xs text-muted-foreground" data-slot="dexp-not-in-inventory">{t('dexp.notInInventory')}</p>}
            <DomainExpiryTrace data={result} />
          </div>
        )}
      </SettingsSection>

      {/* ── Proxy CA zinciri yakalama ── */}
      <SettingsSection level={3}
        title={<span className="inline-flex items-center gap-2"><ShieldCheck size={18} aria-hidden="true" /> {t('dexp.caTitle')}</span>}
        description={t('dexp.caDesc')} contentClassName="flex flex-col gap-3">
        <div>
          <Button variant="secondary" onClick={captureCa} disabled={caLoading} aria-busy={caLoading || undefined}>
            {caLoading ? <Spinner size={14} inline decorative /> : <ShieldCheck size={14} />}
            {caLoading ? t('dexp.caCapturing') : t('dexp.caCapture')}
          </Button>
        </div>

        {ca && !ca.ok && (
          <AlertBanner tone="danger" className="mb-0">
            {ca.error_class ? `[${ca.error_class}] ` : ''}{ca.error}
          </AlertBanner>
        )}

        {ca && ca.ok && (
          <>
            {/* Zincir özeti — her sertifika bir satır; rolü rozetle (sol renk şeridi YOK) */}
            <ul className="flex flex-col gap-2" data-testid="dexp-chain">
              {(ca.chain ?? []).map((c, i) => (
                <li key={i} data-leaf={c.leaf ? 'true' : undefined}
                  className={cn('flex gap-2.5 rounded-lg border px-3 py-2.5', c.leaf && 'opacity-75')}>
                  <span className="mt-0.5 shrink-0" aria-hidden="true">
                    {c.leaf ? <span className="text-muted-foreground">•</span> : <Check size={16} className="text-success" />}
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <strong className="break-all">{shortDn(c.subject)}</strong>
                      {c.leaf && <ToneBadge tone="muted">{t('dexp.caLeaf')}</ToneBadge>}
                      {c.self_signed && <ToneBadge tone="success">{t('dexp.caRoot')}</ToneBadge>}
                      {!c.leaf && !c.self_signed && <ToneBadge tone="info">{t('dexp.caIntermediate')}</ToneBadge>}
                    </div>
                    <div className="mt-0.5 text-[0.84em] break-words text-muted-foreground">↳ {t('dexp.caIssuer')}: {shortDn(c.issuer)}</div>
                  </div>
                </li>
              ))}
            </ul>

            {ca.ca_count > 0 ? (
              <div className="flex flex-col gap-2">
                <div className="flex flex-wrap items-center gap-2">
                  <strong>{t('dexp.caPem')}</strong>
                  <span className="text-[0.78em] text-muted-foreground">{t('dexp.caCount', ca.ca_count)}</span>
                  <Button variant="secondary" size="sm" className="ml-auto pointer-coarse:h-10" onClick={copyPem}>
                    {copied ? <Check size={13} /> : <Copy size={13} />}{copied ? t('dexp.caCopied') : t('dexp.caCopy')}
                  </Button>
                </div>
                <Textarea readOnly rows={8} value={ca.ca_pem} aria-label={t('dexp.caPem')}
                  className="resize-y font-mono text-[11px] md:text-[11px]" onFocus={e => e.target.select()} />
                <AlertBanner tone="info" className="mb-0">{t('dexp.caPaste')}</AlertBanner>
              </div>
            ) : (
              <AlertBanner tone="danger" className="mb-0">{t('dexp.caNoCa')}</AlertBanner>
            )}
          </>
        )}
      </SettingsSection>
    </div>
  )
}
