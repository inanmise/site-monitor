import { MessageSquare } from 'lucide-react'
import { Input } from '@/components/shadcn/input'
import { Field, FieldDescription, FieldLabel } from '@/components/shadcn/field'

/**
 * "Bu değişikliği neden yaptınız?" — opsiyonel not alanı (düzenleme formlarının SON alanı).
 *
 * <p>Geçmiş satırı neyin değiştiğini gösterir ama NEDEN değiştiğini gösteremez: "kontrol sıklığı
 * 5 dk → 1 dk" satırı, altı ay sonra bakan kişiye "bir olay mı vardı, deneme miydi" sorusunu
 * cevaplamaz. Bir cümlelik not bunu cevaplar ve zorunlu değildir — zorunlu olsaydı insanlar
 * "guncelleme" yazıp geçerdi ve alan gürültüye dönüşürdü.
 *
 * <p>Yalnız DÜZENLEMEDE gösterilir: yeni kayıtta "neden" sorusunun cevabı zaten kaydın kendisi.
 *
 * <p>Çizim shadcn Field + Input: alan formun ızgarasının DIŞINDA (gövdenin son satırı) durduğu için
 * görünümünü kendisi taşımalı — eskiden sınıfsız input tarayıcı varsayılanına düşüyordu. `id`
 * çağırandan gelir (sayfa testleri alanı bu kimlikle bulur); etiket ve ipucu ona bağlıdır.
 */
export default function ChangeNoteField({ t, value, onChange, id }) {
  const hintId = `${id}-hint`
  return (
    <Field role={undefined} data-slot="change-note" className="mt-3.5 gap-1.5">
      <FieldLabel htmlFor={id} className="gap-1.5 font-semibold">
        <MessageSquare size={13} aria-hidden="true" />{t('chg.changeNote')}
      </FieldLabel>
      <Input id={id} type="text" maxLength={300} value={value} aria-describedby={hintId}
        placeholder={t('chg.changeNotePlaceholder')}
        onChange={(e) => onChange(e.target.value)} />
      <FieldDescription id={hintId} className="text-xs">{t('chg.changeNoteHint')}</FieldDescription>
    </Field>
  )
}
