import { ChevronDown, FileUp, Globe, Plus } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { ButtonGroup } from '@/components/shadcn/button-group'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/shadcn/dropdown-menu'
import { cn } from '@/lib/utils'

/**
 * "Domain Ekle" bölünmüş düğmesi (2026-10-06): ana düğme BUGÜNKÜ akış (ağ üzerinden okunan alan adı — envanter formu),
 * yanındaki ok ikinci seçeneği AYRI bir seçenek olarak sunar: "Dosyadan sertifika ekle" (ağdan erişilemeyen sertifika
 * yükleme sihirbazı). Pano ve Envanter başlığında aynı bileşen; ana düğmenin adı ve `data-slot`'u değişmez.
 *
 * <p>Telefonda (PageHeader eylem satırı) grup KENDİ satırında tam genişlik — üç düğme yan yana paylaşınca ana düğmenin
 * etiketi kırpılıyordu ("Add Dom…"); izleme sayfalarındaki birincil "Yeni Monitör" ile aynı düzen. Ok düğmesi 40 px.
 * Menü `z-(--z-menu)`, `modal={false}`.
 */
export default function AddCertSplitButton({ onAddDomain, onAddFromFile, label, slot, className }) {
  const t = useT()
  const main = label || t('inv.addBtn')
  return (
    <ButtonGroup aria-label={t('mcert.add.group', main)} data-slot="add-cert-split" className={cn('min-w-0 max-sm:min-w-full', className)}>
      <Button type="button" data-slot={slot} className="min-w-0 flex-1 sm:flex-none" onClick={onAddDomain}>
        <Plus aria-hidden="true" /> {main}
      </Button>
      <DropdownMenu modal={false}>
        <DropdownMenuTrigger asChild>
          <Button type="button" size="icon" data-slot="add-cert-more" aria-label={t('mcert.add.more')} title={t('mcert.add.more')}
            className="shrink-0 border-l border-primary-foreground/25 max-sm:min-w-10 pointer-coarse:size-10">
            <ChevronDown aria-hidden="true" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="z-(--z-menu) w-[min(300px,calc(100vw-2rem))]">
          <DropdownMenuItem className="items-start gap-2.5 py-2" onSelect={onAddDomain}>
            <Globe aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
            <span className="flex min-w-0 flex-col gap-0.5">
              <span className="font-semibold">{t('mcert.add.network')}</span>
              <span className="text-xs text-muted-foreground">{t('mcert.add.networkHint')}</span>
            </span>
          </DropdownMenuItem>
          <DropdownMenuItem data-slot="add-cert-file" className="items-start gap-2.5 py-2" onSelect={onAddFromFile}>
            <FileUp aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
            <span className="flex min-w-0 flex-col gap-0.5">
              <span className="font-semibold">{t('mcert.add.file')}</span>
              <span className="text-xs text-muted-foreground">{t('mcert.add.fileHint')}</span>
            </span>
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </ButtonGroup>
  )
}
