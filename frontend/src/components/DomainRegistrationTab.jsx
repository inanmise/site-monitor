import { useState, useEffect, useCallback, useRef } from 'react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import { Button } from '@/components/shadcn/button'
import { usePagination } from '../hooks/usePagination.js'
import DomainRegistrationPanel from './domain/detail/DomainRegistrationPanel.jsx'

/**
 * Domain Kaydı sekmesi — anlık RDAP/WHOIS (live) + hatırlatmalar. Sunum 2026-09-28'den beri
 * `domain/detail/DomainRegistrationPanel` (shadcn bölümleri: kayıt kuruluşu, tarihler zaman çizelgesi, ad sunucuları
 * ve DNS, koruma, EPP kodları, hatırlatmalar, kayıt verisi JSON); veri sözleşmesi DEĞİŞMEDİ:
 *
 * <p>Açılışta SON KAYDEDİLEN bilgi okunur (`live=false`, yan etkisiz). Anlık sorgu (`live=true`: dış RDAP/WHOIS + elle
 * kayıt + yalnız kapanış değerlendirmesi — yazma/çalıştırma yetkisi ister) YALNIZ kullanıcı "Yenile"ye basınca yapılır
 * (2026-09-29, K-1): sekmeyi açmak eskiden canlı sorgu yapıyordu ve o elle kayıt DOMAINMON_CHANGED tabanını tüketip
 * nameserver/registrar/DNSSEC değişikliği alarmını yutabiliyordu (sunucu artık elle kaydı `manual=true` işaretler;
 * açılışta sorgu da yok). Anlık sorgu başarısızsa (yetki, ağ, kaynak) DB'deki son bilgiye düşülür ve "anlık sorgu
 * başarısız" uyarısı gösterilir.
 *
 * <p>`onLiveRecord` (isteğe bağlı): anlık sorgu BAŞARIYLA döndüğünde taze satır (enrichDomain biçimi — liste satırıyla
 * aynı) sayfaya verilir; sayfa açık detayın başlığını ve kartı günceller (başlık "son kontrol 3 gün önce" derken sekme
 * az önce sorgulamış olmasın). Yedek (DB) veri bildirilmez — zaten sayfadakiyle aynı ya da daha eskidir.
 */
export default function DomainRegistrationTab({ monitor, onLiveRecord }) {
  const t = useT()
  const id = monitor?.id
  const [reg, setReg] = useState(null)
  const [loading, setLoading] = useState(true)
  const [stale, setStale] = useState(false)          // live başarısız → DB'deki son bilgi gösteriliyor
  const [err, setErr] = useState(null)
  const [rem, setRem] = useState(null)   // hatırlatmalar (2026-09-22, E): { thresholds, items }
  const [remError, setRemError] = useState(false)
  // Geri çağrı ref'te: sayfa her saniye yeniden çizilir (geri sayım) — satır içi işlev load'u her seferinde yeniden
  // kurup sorguyu tekrarlatmasın.
  const onLiveRef = useRef(onLiveRecord)
  useEffect(() => { onLiveRef.current = onLiveRecord }, [onLiveRecord])

  const load = useCallback(async (live = true) => {
    if (!id) return
    setLoading(true); setErr(null); setStale(false)
    try {
      try {
        const res = await api.monitoring.getDomainRegistration(id, { live })
        if (res?.success) {
          setReg(res.data)
          if (live && res.data) onLiveRef.current?.(res.data)
        } else if (live) {
          // live başarısız → DB'deki son bilgiye düş
          const fb = await api.monitoring.getDomainRegistration(id)
          if (fb?.success) { setReg(fb.data); setStale(true) } else setErr(res?.error || t('dreg.error'))
        } else setErr(res?.error || t('dreg.error'))
      } catch {
        try {
          const fb = await api.monitoring.getDomainRegistration(id)
          if (fb?.success) { setReg(fb.data); setStale(true) } else setErr(t('dreg.error'))
        } catch { setErr(t('dreg.error')) }
      }
    } finally {
      setLoading(false)
    }
  }, [id, t])

  useEffect(() => { load(false) }, [load])   // açılış: kayıtlı bilgi (canlı sorgu yalnız "Yenile" ile)
  useEffect(() => {
    let alive = true
    setRemError(false)
    Promise.resolve()
      .then(() => api.monitoring.getDomainReminders?.(monitor.id))
      .then((r) => { if (!alive) return; if (r?.success) setRem(r.data); else if (r) setRemError(true) })
      .catch(() => { if (alive) setRemError(true) })
    return () => { alive = false }
  }, [monitor.id])
  // Hatırlatma geçmişi eskiden `slice(0, 10)` ile SESSİZCE kırpılıyordu (11. kayıt ve sonrası hiç görünmüyordu).
  // Standart: pencere içi liste → modal ön ayarı + compact çubuk (hook erken dönüşlerin ÜSTÜNDE — hook sırası).
  const remPager = usePagination(rem?.items || [], { listKey: 'dreg-reminders', preset: 'modal', resetDeps: [monitor.id] })

  if (loading && !reg) return <LoadingBlock label={t('dreg.loading')} className="upt-modal-loading" size={16} />
  if (err && !reg) {
    return (
      <AlertBanner tone="danger" title={t('dreg.error')}
        actions={<Button type="button" variant="secondary" size="sm" onClick={() => load(false)}>{t('hist.retry')}</Button>}>
        {err !== t('dreg.error') ? err : null}
      </AlertBanner>
    )
  }
  if (!reg) return null

  return (
    <DomainRegistrationPanel reg={reg} rem={rem} remError={remError} remPager={remPager}
      stale={stale} loading={loading} onRefresh={() => load(true)} />
  )
}
