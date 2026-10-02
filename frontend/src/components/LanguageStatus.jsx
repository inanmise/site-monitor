import { useEffect, useRef } from 'react'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useToast } from './ui/Toast.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import BrandLogo from './BrandLogo.jsx'

/**
 * Dil sözlüğü yükleme yüzeyleri (2026-10-02, performans önerisi 22 — İngilizce sözlük ayrı/lazy chunk).
 * İkisi de main.jsx'te bağlanır; i18n/index.jsx bileşen import etmez (shadcn Spinner useT kullandığı için
 * döngüsel import olurdu) — açılış ekranı LangProvider'a `fallback` olarak verilir.
 */

/**
 * Açılış ekranı: saklı dil İngilizce ve sözlük henüz inmemişken uygulama ağacının yerine çizilir (genellikle
 * bir an). App'in oturum denetimi ekranıyla aynı kimlik: soluk logo + "Loading...". Metinler i18n `EN_BOOT`
 * açılış sözlüğünden gelir — İngilizce kullanıcı Türkçe metin ya da ham anahtar görmez. role="status" duyurur.
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
 * İndirme hatası bildirimi: LangProvider sözlüğü indiremezse (açılışta ya da TR→EN geçişinde) arayüz Türkçe kalır
 * ve `loadFailures` artar; bu bileşen her artışta bir kez hata bildirimi gösterir. ToastProvider'ın İÇİNDE durmalı
 * (LangProvider ondan yukarıda olduğu için bildirimi kendisi gösteremez). Görünür çıktısı yok.
 */
export function LanguageLoadNotice() {
  const t = useT()
  const toast = useToast()
  const { loadFailures = 0 } = useLanguage()
  const shown = useRef(0)
  useEffect(() => {
    if (!loadFailures || loadFailures === shown.current) return
    shown.current = loadFailures
    toast.error(t('lang.loadFailed'), 8000)
  }, [loadFailures, t, toast])
  return null
}
