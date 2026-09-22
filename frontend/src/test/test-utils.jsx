import { render, fireEvent, act } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'
import { ThemeProvider } from '../i18n/theme.jsx'
import { DialogProvider } from '../components/ui/Dialog.jsx'
import { ToastProvider } from '../components/ui/Toast.jsx'

function AllProviders({ children }) {
  return (
    <ThemeProvider>
      <LangProvider>
        <ToastProvider>
          <DialogProvider>{children}</DialogProvider>
        </ToastProvider>
      </LangProvider>
    </ThemeProvider>
  )
}

function renderWithProviders(ui, options = {}) {
  return render(ui, { wrapper: AllProviders, ...options })
}

export * from '@testing-library/react'
export { renderWithProviders as render }

/**
 * Grup + etiket zorunlu (2026-09-18): dokuz izleme formu ve envanter formu Kaydet'te ikisini de
 * ister. Yeni-kayıt testleri Kaydet'ten önce bunu çağırır; Kopyala/Düzenle testleri fixture'a
 * group_name + tags koyar. Grup kutusu: yıldızlı "Grup/Group" etiketinin altındaki creatable
 * SearchableSelect (trigger mouseDown → arama → "+ ekle" mouseDown). Etiket: TagInput (yaz + Enter).
 */
export async function fillGroupAndTags({ group = 'Grup A', tag = 't1', root } = {}) {
  const scope = root || document.querySelector('.modal-box') || document
  const groupLabel = [...scope.querySelectorAll('label > span:first-child')]
    .find((sp) => /^(Grup|Group)\s*\*?$/.test(sp.textContent.trim()))
  if (!groupLabel) throw new Error('fillGroupAndTags: grup etiketi bulunamadı')
  const wrap = groupLabel.parentElement.querySelector('.ss-wrap')
  if (!wrap) throw new Error('fillGroupAndTags: grup SearchableSelect bulunamadı')
  fireEvent.mouseDown(wrap.querySelector('.ss-trigger'))
  const search = wrap.querySelector('.ss-search-input')
  if (!search) throw new Error('fillGroupAndTags: grup arama kutusu açılmadı')
  fireEvent.change(search, { target: { value: group } })
  const existing = [...wrap.querySelectorAll('.ss-option:not(.ss-create)')].find((o) => o.textContent.trim() === group)
  fireEvent.mouseDown(existing || wrap.querySelector('.ss-create'))
  // handleCreate async: onCreate() await'inden sonra select() koşar — bir mikro-görev bekle.
  await act(async () => {})
  // TagInput kutusu artık öneri listesi için bir .tag-input-wrap içinde (2026-09-22) — doğrudan çocuk değil, torun
  const tagInput = [...scope.querySelectorAll('label.full-width input.input')]
    .find((i) => /etiket|tag|enter/i.test(i.placeholder || ''))
  if (!tagInput) throw new Error('fillGroupAndTags: etiket kutusu bulunamadı')
  fireEvent.change(tagInput, { target: { value: tag } })
  fireEvent.keyDown(tagInput, { key: 'Enter' })
}
