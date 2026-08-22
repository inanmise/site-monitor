import { MessageSquare } from 'lucide-react'

/**
 * "Bu değişikliği neden yaptınız?" — opsiyonel not alanı (düzenleme formlarının SON alanı).
 *
 * <p>Geçmiş satırı neyin değiştiğini gösterir ama NEDEN değiştiğini gösteremez: "kontrol sıklığı
 * 5 dk → 1 dk" satırı, altı ay sonra bakan kişiye "bir olay mı vardı, deneme miydi" sorusunu
 * cevaplamaz. Bir cümlelik not bunu cevaplar ve zorunlu değildir — zorunlu olsaydı insanlar
 * "guncelleme" yazıp geçerdi ve alan gürültüye dönüşürdü.
 *
 * <p>Yalnız DÜZENLEMEDE gösterilir: yeni kayıtta "neden" sorusunun cevabı zaten kaydın kendisi.
 */
export default function ChangeNoteField({ t, value, onChange, id }) {
  return (
    <label className="full-width chg-note-field" htmlFor={id}>
      <span><MessageSquare size={13} aria-hidden="true" /> {t('chg.changeNote')}</span>
      <input id={id} type="text" maxLength={300} value={value}
        placeholder={t('chg.changeNotePlaceholder')}
        onChange={(e) => onChange(e.target.value)} />
      <span className="field-hint">{t('chg.changeNoteHint')}</span>
    </label>
  )
}
