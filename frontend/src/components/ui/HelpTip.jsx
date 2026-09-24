import { useId, useState } from 'react'
import { CircleHelp } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverTitle,
  PopoverTrigger,
} from '@/components/shadcn/popover'

/**
 * Alan-bazlı açıklama baloncuğu — Ayarlar ekranlarındaki HER yapılandırma değerinin
 * "ne işe yarar / faydası / önerilen değer" metnini SAYFAYA YAZMADAN taşır.
 * İç uygulama shadcn Popover (Radix); dış API (helpKey, label) değişmedi.
 *
 * Neden Popover (Tooltip/HoverCard değil):
 *  • Metin sayfada görünür dursaydı 200'den fazla ayar taşıyan Genel Ayarlar okunamaz
 *    bir duvara dönerdi; ipucu isteğe bağlı olmalı ve TIKLAYINCA açılır. Tooltip yalnız
 *    hover/odakta açılır ve tetiğe basınca KAPANIR; HoverCard klavye/dokunmatikle hiç açılmaz.
 *  • İçerik zengin (başlık + üç satırlık metin) ve kullanıcı metni seçebilmeli.
 *  • Radix Popover body'ye PORTAL'lar ve konumu kendisi hesaplar (çarpışmada çevirir,
 *    kaydırmada izler): `.threshold-field`, `.up-group-card` ya da `.admin-table-wrap` gibi
 *    `overflow` taşıyan ataların hiçbiri onu kırpamaz. Escape ve dışarı basış kapatır.
 *  • Metin `whitespace-pre-line` ile çizilir; sözlükteki `\n` gerçek satır olur.
 *
 * Açık/kapalı durumu DENETİMLİ (open/onOpenChange): tetiğin tıklaması `preventDefault` ile
 * etiket davranışını keser (aşağıya bakın) ve Radix kendi toggle'ını varsayılanı engellenmiş
 * olayda çalıştırmaz — bu yüzden toggle bizde; Escape/dışarı kapanışı Radix'ten gelir.
 *
 * ÇEVİRİ YOKSA HİÇBİR ŞEY ÇİZİLMEZ: `useT` eksik anahtarda anahtarın KENDİSİNİ döndürür
 * (i18n/index.jsx `dict[key] ?? key`), yani kontrolsüz bir ikon kullanıcıya
 * "help.set.site.monitor.x" ham anahtarını gösterirdi. Tetikleyici de çizilmez —
 * boş baloncuk açan bir ikon, yardımın var olmadığından daha kötüdür.
 */
export default function HelpTip({ helpKey, label }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const popId = useId()

  const text = helpKey ? t(helpKey) : ''
  // Eksik çeviri: t() anahtarın kendisini döndürür → ham anahtar göstermek yerine hiç çizme.
  if (!helpKey || !text || text === helpKey) return null

  const aria = label ? `${t('helptip.aria')}: ${label}` : t('helptip.aria')

  const toggle = (e) => {
    // Tetikleyici bir <label> İÇİNDE de durabiliyor; varsayılan davranış tıklamayı
    // etiketli kontrole iletir ve odağı kaçırırdı.
    e.preventDefault()
    e.stopPropagation()
    setOpen((o) => !o)
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button asChild variant="ghost" size="icon-xs"
          className="ml-[5px] size-[18px] cursor-pointer rounded-full align-[-3px] text-muted-foreground hover:bg-transparent hover:text-primary data-[state=open]:text-primary dark:hover:bg-transparent">
          {/* <button> DEĞİL, role="button" taşıyan <span>: tetikleyici çoğu ekranda kontrolü
              SARAN bir <label> içinde duruyor ve <button> "labelable" bir elemandır — etiketin
              kontrolü sessizce bu düğme olur, asıl input ERİŞİLEBİLİR ADINI KAYBEDER ve etikete
              tıklamak alana odaklanmak yerine baloncuğu açardı. <span> labelable değildir;
              erişilebilirlik ağacında yine bir düğmedir (role + aria-label + aria-expanded).
              Radix'in eklediği type/aria-haspopup="dialog" düşürülür: içerik bir açıklama
              (role="tooltip" + aria-describedby), diyalog değil. */}
          <span
            role="button"
            tabIndex={0}
            type={undefined}
            aria-label={aria}
            aria-expanded={open}
            aria-haspopup={undefined}
            aria-controls={open ? popId : undefined}
            aria-describedby={open ? popId : undefined}
            onClick={toggle}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ' || e.key === 'Spacebar') toggle(e)
            }}
          >
            <CircleHelp className="size-3.5" aria-hidden="true" />
          </span>
        </Button>
      </PopoverTrigger>
      <PopoverContent
        id={popId}
        role="tooltip"
        side="bottom"
        align="start"
        sideOffset={6}
        collisionPadding={8}
        // Odak tetikte kalır (eski davranış): ipucu okunacak metin, içinde odaklanacak bir şey yok;
        // Enter/Space ile yeniden kapatılabilir, Escape de kapatır.
        onOpenAutoFocus={(e) => e.preventDefault()}
        className="z-(--z-dialog) w-[300px] max-w-[calc(100vw-16px)] px-3 py-2.5 text-left text-xs leading-relaxed font-normal"
      >
        {label ? <PopoverTitle className="mb-1 font-semibold text-foreground">{label}</PopoverTitle> : null}
        <PopoverDescription className="whitespace-pre-line">{text}</PopoverDescription>
      </PopoverContent>
    </Popover>
  )
}
