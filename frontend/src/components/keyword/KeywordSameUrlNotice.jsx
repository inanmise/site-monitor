import { useEffect, useRef, useState } from 'react'
import { ExternalLink, Link2 } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import AlertBanner from '../ui/AlertBanner.jsx'
import { normalizeUrl } from '../../utils/normalizeUrl.js'
import { errorInfoOf } from '../../utils/errorMessages.js'

/** URL yazılırken sunucuya sormadan önce beklenen süre (ms) — her tuşta istek gitmesin. */
export const SAME_URL_DELAY_MS = 400

/**
 * Denetlenebilir URL mi: şemalı (http/https) ve host'lu. `{timestamp}` önbellek kırma yer tutucusu ayrıştırmayı bozmasın diye
 * geçici olarak sayıyla değiştirilir (sunucuya giden metin değişmez). Doğrusal; düzenli ifade yok.
 */
export function checkableUrl(raw) {
  const s = normalizeUrl(raw)
  if (!s || s.length > 2000) return null
  try {
    const u = new URL(s.split('{timestamp}').join('0'))
    if ((u.protocol === 'http:' || u.protocol === 'https:') && u.hostname) return s
  } catch { /* geçersiz */ }
  return null
}

/**
 * "Bu URL'yi başka izleme de izliyor" uyarısı (kw kararı 4, sözleşme §14.1). Alarm anahtarı URL + türdür: aynı URL'yi izleyen
 * izlemeler tek olayı paylaşır ve açık bir alarm varken diğerinin sorunu ayrıca bildirilmez — davranış AYNI kalır, form bunu
 * önceden söyler. URL yazılırken {@link SAME_URL_DELAY_MS} gecikmeli `GET /monitoring/keyword/same-url` (sıra korumalı: geç
 * dönen eski yanıt yeni URL'nin uyarısını ezmez; düzenlemede kaydın kendisi `excludeId` ile dışarıda). Görünür izlemeler ad +
 * takım + kelimeyle, YENİ SEKMEDE açılan bağlantıyla (form verisi kaybolmaz) listelenir; göremediği takımlardakiler yalnız SAYI.
 * Okunamazsa "denetlenemedi" + yeniden dene (asla "yok" denmez); 404 / 405 = eski sunucu → hiçbir şey gösterilmez.
 *
 * <p>Test kancaları: `data-slot="kw-same-url"` (+ `data-state` = shared | failed), `kw-same-url-item`, `kw-same-url-hidden`.
 */
export default function KeywordSameUrlNotice({ url, excludeId = null, delayMs = SAME_URL_DELAY_MS }) {
  const t = useT()
  const target = checkableUrl(url)
  const [res, setRes] = useState({ target: null, status: 'idle', data: null })
  const [nonce, setNonce] = useState(0)
  const seq = useRef(0)

  useEffect(() => {
    const my = ++seq.current
    if (!target) return undefined
    const h = setTimeout(async () => {
      try {
        const r = await api.monitoring.keywordSameUrl(target, excludeId ?? undefined)
        if (my !== seq.current) return
        if (r?.success) {
          const d = r.data && typeof r.data === 'object' ? r.data : r
          setRes({ target, status: 'ok', data: d })
          return
        }
        const status = errorInfoOf(r)?.status ?? r?.status
        setRes({ target, status: status === 404 || status === 405 ? 'unsupported' : 'failed', data: null })
      } catch {
        if (my === seq.current) setRes({ target, status: 'failed', data: null })
      }
    }, delayMs)
    return () => clearTimeout(h)
  }, [target, excludeId, nonce, delayMs])

  // Yanıt başka bir URL'ye aitse (yazmaya devam edildi) gösterilmez — bayat uyarı yeni adresi anlatmasın
  if (!target || res.target !== target) return null

  if (res.status === 'failed') {
    return (
      <div data-slot="kw-same-url" data-state="failed" className="min-w-0 sm:col-span-2">
        <AlertBanner tone="warning" icon={Link2} className="mb-0"
          actions={(
            <Button type="button" variant="secondary" size="sm" className="pointer-coarse:h-10"
              onClick={() => setNonce((n) => n + 1)}>{t('keyword.sameUrl.retry')}</Button>
          )}>
          {t('keyword.sameUrl.failed')}
        </AlertBanner>
      </div>
    )
  }
  if (res.status !== 'ok') return null

  const monitors = Array.isArray(res.data?.monitors) ? res.data.monitors.filter((m) => m && m.id != null) : []
  const hidden = Number(res.data?.hidden_count) > 0 ? Number(res.data.hidden_count) : 0
  if (monitors.length === 0 && hidden === 0) return null

  return (
    <div data-slot="kw-same-url" data-state="shared" className="min-w-0 sm:col-span-2">
      <AlertBanner tone="warning" icon={Link2} className="mb-0" title={t('keyword.sameUrl.title')}>
        <p className="m-0">{t('keyword.sameUrl.body')}</p>
        {monitors.length > 0 && (
          <ul className="m-0 mt-1.5 flex list-none flex-col gap-1 p-0">
            {monitors.map((m) => {
              const name = m.name || m.keyword || `#${m.id}`
              return (
                <li key={m.id} data-slot="kw-same-url-item" data-monitor-id={m.id}
                  className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5">
                  <Button asChild variant="link" size="sm"
                    className="h-auto min-h-7 px-0 py-0.5 text-left whitespace-normal [overflow-wrap:anywhere] pointer-coarse:min-h-10">
                    <a href={`?tab=keyword&monitor=${encodeURIComponent(m.id)}`} target="_blank" rel="noopener noreferrer"
                      aria-label={t('keyword.sameUrl.open', name)}>
                      <span className="min-w-0">{name}</span>
                      <ExternalLink aria-hidden="true" className="size-3 shrink-0" />
                    </a>
                  </Button>
                  {m.team_name && <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{m.team_name}</span>}
                  {m.keyword && <span className="font-mono text-xs [overflow-wrap:anywhere]">« {m.keyword} »</span>}
                  {m.active === false && (
                    <Badge variant="outline" className="overflow-visible rounded-md whitespace-normal text-[11px]">{t('keyword.sameUrl.paused')}</Badge>
                  )}
                </li>
              )
            })}
          </ul>
        )}
        {hidden > 0 && (
          <p data-slot="kw-same-url-hidden" className="m-0 mt-1.5 text-xs">
            {hidden === 1 ? t('keyword.sameUrl.hiddenOne') : t('keyword.sameUrl.hidden', hidden)}
          </p>
        )}
      </AlertBanner>
    </div>
  )
}
