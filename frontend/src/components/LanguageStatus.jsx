import { useEffect, useRef } from 'react'
import { dictLang, useT, useLanguage } from '../i18n/index.jsx'
import { reloader } from '../utils/chunkReloadGuard.js'
import { useToast } from './ui/Toast.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import BrandLogo from './BrandLogo.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * Dil sözlüğü yükleme yüzeyleri (2026-10-02, performans önerisi 22 — İngilizce sözlük ayrı/lazy chunk; 2026-11-06'dan beri
 * Türkçe sözlük de). Üçü de main.jsx'te bağlanır; i18n/index.jsx bileşen import etmez (shadcn Spinner useT kullandığı için
 * döngüsel import olurdu) — açılış ve hata ekranı LangProvider'a `fallback` / `failure` olarak verilir.
 */

/**
 * Açılış ekranı: saklı dilin sözlüğü henüz inmemişken uygulama ağacının yerine çizilir (genellikle bir an). App'in oturum
 * denetimi ekranıyla aynı kimlik: soluk logo + "Loading..." / "Yükleniyor...". Metinler i18n `EN_BOOT` / `TR_BOOT`
 * açılış metinlerinden gelir — kullanıcı başka dilde metin ya da ham anahtar görmez. role="status" duyurur.
 */
export function LanguageBootSplash() {
  const t = useT()
  return (
    <div data-slot="lang-boot" className="mt-20 flex flex-col items-center px-4">
      <BrandLogo status="muted" size={64} />
      <LoadingBlock label={t('app.loading')} className="py-3" />
    </div>
  )
}

/**
 * Sözlüksüz açılış hata ekranı (2026-11-06): açılışta ne saklı dilin ne de öbür dilin sözlüğü indi (yenileme hakkı da
 * kullanılmış). Boş sayfa ya da sonsuz "Yükleniyor..." yerine sabit metinli bir uyarı ve "Yeniden yükle" (44 px) — metinler
 * açılış metinlerinden (EN_BOOT / TR_BOOT, `tr.js` / `en.js` değerlerinin birebir kopyası), kullanıcının dilinde; ham anahtar
 * yok. Kullanıcının kendi yenilemesi sayaçsızdır (public/boot-guard.js ile aynı kural). Test kancası `lang-boot-failure`.
 */
export function LanguageBootFailure() {
  const t = useT()
  const { lang } = useLanguage()
  return (
    <div data-slot="lang-boot-failure" lang={dictLang(lang)}
      className="mx-auto mt-[12vh] flex w-full max-w-xl flex-col items-center px-4 text-center [overflow-wrap:anywhere]">
      <BrandLogo status="muted" size={64} />
      <StatusBlock tone="danger" role="alert" title={t('lang.bootFailed.title')} description={t('lang.bootFailed.body')}
        className="py-6 md:py-6"
        actions={(
          <Button type="button" className="min-h-11 min-w-11 px-5" onClick={() => { try { reloader.reload() } catch { /* yoksay */ } }}>
            {t('lang.bootReload')}
          </Button>
        )} />
    </div>
  )
}

/**
 * İndirme hatası bildirimi: LangProvider bir sözlüğü indiremezse (açılışta öbür dile düştüğünde ya da çalışırken TR↔EN
 * geçişinde) arayüz öbür / mevcut dilde kalır ve `loadFailures` artar; bu bileşen her artışta bir kez hata bildirimi
 * gösterir. Hata her zaman "gösterilen dilin DIŞINDAKİ sözlük inmedi" demektir: etkin dil İngilizce ise Türkçe dosya,
 * Türkçe ise İngilizce dosya yüklenemedi. ToastProvider'ın İÇİNDE durmalı (LangProvider ondan yukarıda olduğu için
 * bildirimi kendisi gösteremez). Görünür çıktısı yok.
 */
export function LanguageLoadNotice() {
  const t = useT()
  const toast = useToast()
  const { lang, loadFailures = 0 } = useLanguage()
  const shown = useRef(0)
  useEffect(() => {
    if (!loadFailures || loadFailures === shown.current) return
    shown.current = loadFailures
    toast.error(t(dictLang(lang) === 'en' ? 'lang.loadFailedTr' : 'lang.loadFailed'), 8000)
  }, [loadFailures, lang, t, toast])
  // Açılış hatasında bu bileşen ToastProvider ile AYNI işlemede doğar (dil hazır olunca ağaç ilk kez çizilir). React
  // StrictMode (geliştirme) efektleri bir kez söküp yeniden kurar: sağlayıcının sökümü ilk bildirimi kapatıyor, sayaç
  // "gösterildi" dediği için ikinci kurulumda bildirim yeniden açılmıyordu (2026-10-23, e2e rolling-update — Linux
  // CI'da yarışlı). Söküm sayacı sıfırlar: yeniden kurulum bildirimi yeniden açar; üretimde (tek kurulum) davranış aynı.
  useEffect(() => () => { shown.current = 0 }, [])
  return null
}
