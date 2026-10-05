import { useCallback, useEffect, useId, useState } from 'react'
import { CalendarSearch, ListFilter, ShieldQuestion } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import CheckHistoryTab from '../history/CheckHistoryTab.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import CertHistoryInsights from './CertHistoryInsights.jsx'
import { CertCheckDetail, CertHistoryCard, CheckCert, CheckDays, CheckDetailCell, CheckStatus, CheckTime } from './CertHistoryRow.jsx'
import { compareToOlder, findOlder } from './certHistoryModel.js'

/** Sertifika penceresinin aralık ön ayarları (gün) ve varsayılanı — eski satır içi yapılandırmayla aynı. */
export const CERT_HISTORY_PRESETS = [1, 7, 30, 90]
export const CERT_HISTORY_DEFAULT = 7
/**
 * Beş sütunlu tablonun sığdığı en dar KAP (px) — altında kart listesi. Playwright ölçümü (2026-09-28): tablonun en dar hâli
 * (min-content) 571 px; 560 px kapta yatay taşıyor, 600 px'te sığıyor. Pencerede (768 px ekranda kap 687 px) ve çekmecede
 * (768'de 727, 1280'de 779 px) görünüm değişmez — tablo; Uptime detayında iki geçmiş yan yana (768'de 323, 1280'de 427 px) → kart.
 */
export const CERT_TABLE_MIN = 600
const DAY = 86_400_000

/**
 * Sertifika Kontrol Geçmişi (2026-09-28 shadcn + mobil web yeniden tasarımı) — CertificateModal "Kontrol Geçmişi"
 * sekmesinin gövdesi. Kabuk ORTAK `history/CheckHistoryTab` (aralık ön ayarları + özel aralık + URL senkronu + canlı
 * yenileme + CSV + yoğunluk şeridi + alarm zaman çizelgesi + gün ayırıcıları + sunucu sayfalaması); sertifikaya özgü
 * olan dört şey yuvalardan gelir:
 * - `renderSummary` → `CertHistoryInsights`: SSL kutucukları (kontrol · başarısız · başarı oranı · kalan gün ·
 *   yenileme · son hata) + kalan gün eğilimi (yenileme anları işaretli, dokununca o ana iner).
 * - `renderRow` → masaüstü Table hücreleri: saat · durum rozeti · kalan gün (+ önceki kontrole göre değişim) ·
 *   sertifika (bitiş + veren) · hata/teknik özet + "Ayrıntı" (satırın ALTINDA tam hata + sertifika alanları).
 * - `renderCard` → telefon kartı (aynı parçalar, 40 px dokunma hedefleri).
 * - `renderEmpty` → boş aralık: süzgeç açıksa "tümünü göster", değilse aralığı genişlet.
 *
 * `reloadSignal` (başlıktaki Çalıştır / Yenile / yeni kontrol yoklaması) sekmeyi REMOUNT ETMEDEN tazeler: seçili
 * aralık, sayfa ve süzgeç korunur; eğilim ve son-hata verisi de aynı sinyalle yenilenir.
 *
 * <p>2026-09-28 — TEK sertifika geçmişi görünümü: Envanter çekmecesi "Kontroller" sekmesi ve Uptime detayının SSL geçmişi de
 * bunu kullanır (eski düz 4 sütunlu `upt-rt-*` satırlar kalktı). İsteğe bağlı, geriye uyumlu prop'lar (varsayılanlar pencerenin
 * davranışı): `listKey` (sayfa boyutu tercihi), `urlSync` / `live` (çekmece: ikisi de kapalı), `presets` / `defaultPreset`,
 * `range` + `onRangeChange` (kontrollü aralık — Uptime'ın tek seçicisi iki geçmişi sürer: ön ayar çubuğu yok, canlı yenileme
 * yok; yoğunluk şeridi, yenileme anına inme ve "aralığı genişlet" üstteki seçiciyi değiştirir), `cardsBelow` (tablo yerine kart
 * listesine geçilen KAP genişliği; varsayılan `CERT_TABLE_MIN`), `runInHeader` (boş aralık açıklaması "başlıktaki Çalıştır"
 * düğmesini anar — yalnız pencerede var; çekmece ve Uptime'da `false` → yalnız aralığı genişletmeyi önerir).
 */
export default function CertCheckHistory({
  domain, reloadSignal = 0, listKey = 'cert-ssl-history', urlSync = true, live = true,
  presets = CERT_HISTORY_PRESETS, defaultPreset = CERT_HISTORY_DEFAULT,
  range = null, onRangeChange = null, cardsBelow = CERT_TABLE_MIN, runInHeader = true,
  // Hata teşhisi paneli → "Bu kontrolü tanıla" (2026-10-05): verilirse başarısız satırın panelinde tanılama penceresini
  // açar (CertificateModal / Uptime: diagnostics.run + kendi kaydı); verilmezse düğme hiç çizilmez (eski görünüm).
  onDiagnose = undefined,
}) {
  const t = useT()
  const baseId = useId()
  const [open, setOpen] = useState(() => new Set())
  // Pencere kalıcı mount'lu, yalnız alan adı değişir: önceki kaydın açık satırları yeni kayda taşınmasın.
  useEffect(() => { setOpen(new Set()) }, [domain])
  const toggle = useCallback((k) => setOpen((s) => {
    const n = new Set(s)
    if (n.has(k)) n.delete(k)
    else n.add(k)
    return n
  }), [])

  const keyOf = (c) => String(c?.id ?? c?.checked_at ?? '')
  const panelIdOf = (k) => `${baseId}-cert-hist-${k}`.replace(/[^A-Za-z0-9_-]/g, '')
  // Süzgeç açıkken (yalnız başarısız) komşular ardışık DEĞİL → değişim işareti uydurulmaz.
  const cmpOf = (c, rc) => (rc?.filtered ? null : compareToOlder(c, findOlder(rc?.items, rc?.index ?? -1)))

  const renderRow = (c, rc) => {
    const k = keyOf(c)
    const isOpen = open.has(k)
    const cmp = cmpOf(c, rc)
    return (<>
      <CheckTime item={c} />
      <CheckStatus item={c} t={t} />
      <CheckDays item={c} cmp={cmp} t={t} />
      <CheckCert item={c} t={t} />
      <CheckDetailCell item={c} open={isOpen} onToggle={() => toggle(k)} panelId={panelIdOf(k)} t={t} />
      {/* Sütun sayısını aşan hücre = satırın altında tam genişlik ek satır (CheckHistoryTab sözleşmesi). */}
      {isOpen && <CertCheckDetail id={panelIdOf(k)} item={c} cmp={cmp} t={t} onDiagnose={onDiagnose} />}
    </>)
  }

  const renderCard = (c, rc) => {
    const k = keyOf(c)
    return (
      <CertHistoryCard item={c} cmp={cmpOf(c, rc)} open={open.has(k)} onToggle={() => toggle(k)} panelId={panelIdOf(k)} t={t}
        onDiagnose={onDiagnose} />
    )
  }

  const renderEmpty = (ctx) => {
    if (ctx.status !== 'all') {
      return (
        <StatusBlock tone="success" icon={ListFilter} title={t('certh.emptyFailTitle')} description={t('certh.emptyFailDesc')}
          className="rounded-lg border border-dashed"
          actions={<Button type="button" variant="outline" className="pointer-coarse:h-10" onClick={() => ctx.setStatus('all')}>{t('certh.clearFilter')}</Button>} />
      )
    }
    const widest = Math.max(...(ctx.presets || CERT_HISTORY_PRESETS))
    // Kontrollü aralıkta ön ayar yok: seçili aralık en geniş ön ayardan (1 sa pay) kısaysa genişletilebilir.
    const fixedSpan = ctx.fixedRange ? new Date(ctx.fixedRange.to) - new Date(ctx.fixedRange.from) : null
    const canWiden = fixedSpan != null ? fixedSpan < widest * DAY - DAY / 24
      : ctx.preset === 'custom' || Number(ctx.preset) < widest
    return (
      <StatusBlock tone="neutral" icon={ShieldQuestion} title={t('certh.emptyTitle')} description={t(runInHeader ? 'certh.emptyDesc' : 'certh.emptyDescRange')}
        className="rounded-lg border border-dashed"
        actions={canWiden ? (
          <Button type="button" variant="outline" className="gap-1.5 pointer-coarse:h-10" onClick={() => ctx.setPreset(widest)}>
            <CalendarSearch aria-hidden="true" />{t('certh.widen', widest)}
          </Button>
        ) : null} />
    )
  }

  return (
    <CheckHistoryTab
      kind="uptime-ssl"
      monitorId={domain}
      listKey={listKey}
      presets={presets}
      defaultPreset={defaultPreset}
      reloadSignal={reloadSignal}
      urlSync={urlSync}
      live={live}
      range={range}
      onRangeChange={onRangeChange}
      cardsBelow={cardsBelow}
      columns={[t('certh.colTime'), t('certh.colStatus'), t('certh.colDays'), t('certh.colCert'), t('certh.colDetail')]}
      renderRow={renderRow}
      renderCard={renderCard}
      renderEmpty={renderEmpty}
      renderSummary={(ctx) => <CertHistoryInsights ctx={ctx} domain={domain} reloadSignal={reloadSignal} />} />
  )
}
