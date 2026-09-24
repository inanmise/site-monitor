import { createContext, useContext, useState, useCallback, useRef, useEffect, useId } from 'react'
import { Trash2, AlertTriangle, Info, CheckCircle, XCircle, LogOut, Pencil } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { Textarea } from '@/components/shadcn/textarea'
import {
  AlertDialog, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader,
  AlertDialogMedia, AlertDialogTitle,
} from '@/components/shadcn/alert-dialog'
import { useT } from '../../i18n/index.jsx'

const DialogCtx = createContext(null)

/**
 * Varyant → ikon + ton. Ton dekoratif ikon kutusunu renklendirir; onay düğmesi yıkıcı tonda
 * shadcn `destructive`, diğerlerinde birincil düğmedir.
 */
const VARIANTS = {
  danger:  { Icon: Trash2,        tone: 'destructive' },
  warning: { Icon: AlertTriangle, tone: 'warning' },
  info:    { Icon: Info,          tone: 'primary' },
  success: { Icon: CheckCircle,   tone: 'success' },
  error:   { Icon: XCircle,       tone: 'destructive' },
  logout:  { Icon: LogOut,        tone: 'destructive' },
  prompt:  { Icon: Pencil,        tone: 'primary' },
}

const TONE_MEDIA = {
  destructive: 'bg-destructive/10 text-destructive',
  warning:     'bg-amber-500/15 text-amber-600 dark:text-amber-400',
  success:     'bg-green-600/10 text-green-700 dark:text-green-400',
  primary:     'bg-primary/10 text-primary',
}

/**
 * Gerekçe notu kuralı — backend {@code AlertActionNote} ile AYNI eşikler.
 *
 * <p>Buradaki amaç anında geri bildirim; garanti sunucudadır. İki taraf ayrışırsa kullanıcı
 * arayüzde geçen bir notla 400 yer, o yüzden sayılar bilerek yan yana yazılı.
 */
export const NOTE_RULE = { minWords: 3, minWordLen: 2, minChars: 10 }

export function isNoteValid(note) {
  const t = (note ?? '').trim()
  if (t.length < NOTE_RULE.minChars) return false
  return t.split(/\s+/).filter(w => w.length >= NOTE_RULE.minWordLen).length >= NOTE_RULE.minWords
}

/**
 * Onay / bilgi / metin / gerekçe penceresi — shadcn AlertDialog (Radix).
 *
 * Radix'ten gelenler: odak tuzağı (Tab/Shift+Tab döngüsü, arkadaki sayfaya kaçmaz), Escape =
 * İptal (katman yığınında en üstteki pencere alır — altındaki ModalShell kapanmaz), arka plan
 * kaydırma kilidi, arkadaki içeriğin erişilebilirlik ağacından gizlenmesi.
 *
 * Eski sözleşmeden bilerek KORUNANLAR:
 *   • role: yalnız `alert` tipi ALERTDIALOG (dikkat isteyen kesinti); onay/metin/gerekçe `dialog`.
 *     Radix AlertDialog her içeriği alertdialog yapardı — role bilinçli olarak eziliyor.
 *   • İlk odak ONAY düğmesinde (metin/gerekçe tipinde alanda). Radix varsayılanı İptal'dir.
 *   • Örtüye tıklamak = İptal. Radix AlertDialog dış tıklamayı yutar; örtü olayı elle bağlı.
 *   • Kapanışta odak TETİKLEYİCİYE döner (tetik Radix'in DialogTrigger'ı değil, Radix dönmez).
 *   • Sonuç sözleşmesi: confirm/alert true|false, prompt metin|null, note {confirmed, note}.
 */
function DialogModal({ dialog, onConfirm, onCancel }) {
  const t = useT()
  const [inputVal, setInputVal] = useState(dialog.defaultValue ?? '')
  const inputRef  = useRef(null)
  const confirmRef = useRef(null)
  // Diyalog acilmadan ONCEKI odak — kapaninca oraya geri verilir.
  const returnFocusRef = useRef(null)
  // Başlık/mesaj/ipucu id'leri: aria-labelledby/describedby bunlara bağlı. Başlık id'si ELLE
  // veriliyor çünkü metin girişi de adını başlıktan alıyor (Radix'in iç id'si dışarı açık değil).
  const uid = useId()
  const titleId = `${uid}-title`
  const messageId = `${uid}-message`
  const noteId = `${uid}-note`
  const hintId = `${uid}-note-hint`
  const v = VARIANTS[dialog.variant] ?? VARIANTS.info
  const isPrompt = dialog.type === 'prompt'
  const isNote = dialog.type === 'note'
  const noteOk = !isNote || isNoteValid(inputVal)

  useEffect(() => {
    // Odagi GERI VERMEK icin nereden geldigimizi sakla. Bu olmadan, tablodaki bir satiri
    // silmeyi onaylayan klavye kullanicisi odagi body'de bulur ve listedeki yerini kaybeder.
    returnFocusRef.current = document.activeElement
    return () => {
      const back = returnFocusRef.current
      // Tetikleyici bu arada DOM'dan kalkmis olabilir (ornegin silinen satirin dugmesi);
      // o zaman geri verme atlanir, hata firlatilmaz.
      if (back && typeof back.focus === 'function' && document.contains(back)) back.focus()
    }
  }, [])

  function focusInitial(e) {
    e.preventDefault()   // Radix'in İptal'e odaklanmasını engelle
    if (isPrompt || isNote) {
      inputRef.current?.focus()
      inputRef.current?.select()
    } else {
      confirmRef.current?.focus()
    }
  }

  function confirm() {
    if (isPrompt) return onConfirm(inputVal || null)
    if (isNote) return onConfirm({ confirmed: true, note: inputVal.trim() })
    onConfirm(true)
  }

  function handleKeyDown(e) {
    if (e.key !== 'Enter' || e.defaultPrevented) return
    // Metin girişinde Enter girişin kendi işleyicisinde; not modalinde Enter ONAYLAMAZ: metin
    // çok satırlı ve Enter yeni satır demek. Ayrıca kural sağlanmadan Enter'la geçilmesi
    // zorunluluğu delerdi.
    if (isPrompt || isNote) return
    // Odak onay DIŞINDAKİ bir düğmedeyse (İptal) Enter o düğmenin kendi etkinleştirmesidir.
    // Eskiden kabuk düzeyindeki dinleyici İptal'e odaklıyken de ONAYLIYORDU — silme onayında
    // "vazgeç" demek isteyen klavye kullanıcısı kaydı sildiriyordu.
    const btn = e.target instanceof Element ? e.target.closest('button') : null
    if (btn && btn !== confirmRef.current) return
    e.preventDefault()   // onay düğmesinin yerel "Enter = tıkla"sı ikinci kez çözmesin
    confirm()
  }

  return (
    <AlertDialog open onOpenChange={(open) => { if (!open) onCancel() }}>
      <AlertDialogContent
        role={dialog.type === 'alert' ? 'alertdialog' : 'dialog'}
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={dialog.message ? messageId : undefined}
        onOpenAutoFocus={focusInitial}
        onCloseAutoFocus={(e) => e.preventDefault()}
        onKeyDown={handleKeyDown}
        overlayProps={{ onClick: onCancel }}
      >
        <AlertDialogHeader>
          {/* Ikon DEKORATIF: anlami zaten baslikta yazili, SR'a iki kez okutmanin faydasi yok. */}
          <AlertDialogMedia aria-hidden="true" className={cn('rounded-full', TONE_MEDIA[v.tone])}>
            <v.Icon />
          </AlertDialogMedia>
          <AlertDialogTitle id={titleId}>{dialog.title}</AlertDialogTitle>
          {dialog.message && (
            <AlertDialogDescription id={messageId} className="whitespace-pre-line">
              {dialog.message}
            </AlertDialogDescription>
          )}
        </AlertDialogHeader>

        {isPrompt && (
          <Input
            ref={inputRef}
            value={inputVal}
            onChange={e => setInputVal(e.target.value)}
            placeholder={dialog.placeholder ?? ''}
            aria-labelledby={titleId}
            onKeyDown={(e) => {
              if (e.key !== 'Enter') return
              e.preventDefault()
              onConfirm(inputVal || null)
            }}
          />
        )}

        {isNote && (
          <div className="grid gap-2">
            <Label htmlFor={noteId}>{dialog.noteLabel ?? t('dlg.noteLabel')}</Label>
            {/* Hazır gerekçeler: metni DOLDURUR, kilitlemez — kullanıcı üzerine yazabilir.
                Çip seçmek tek başına yetmez; kural yine geçerli (çipler kuralı sağlayacak
                uzunlukta yazıldı ama kullanıcı silip kısaltırsa düğme yine pasifleşir). */}
            {dialog.chips?.length > 0 && (
              <div className="flex flex-wrap gap-1.5">
                {dialog.chips.map(c => (
                  <Button key={c} type="button" variant="outline" size="xs"
                    className="h-auto min-h-6 rounded-full py-1 font-normal whitespace-normal"
                    onClick={() => { setInputVal(c); inputRef.current?.focus() }}>
                    {c}
                  </Button>
                ))}
              </div>
            )}
            {/* aria-invalid kırmızı çerçeveyi bilinçli olarak NÖTR bırakır: yazmaya başlamadan
                kırmızı göstermek cezalandırıcı olur; eksik olan aşağıdaki ipucunda yazılı. */}
            <Textarea
              ref={inputRef}
              id={noteId}
              rows={3}
              value={inputVal}
              onChange={e => setInputVal(e.target.value)}
              placeholder={dialog.placeholder ?? ''}
              aria-invalid={!noteOk}
              aria-describedby={hintId}
              className="aria-invalid:border-input aria-invalid:ring-ring/50 dark:aria-invalid:ring-ring/50"
            />
            {/* Neyin eksik olduğu YAZILI — düğmeyi pasif bırakıp sebebini söylememek,
                kullanıcıya "bozuk" hissi verir. min-h: metin değişince pencere zıplamasın. */}
            <p id={hintId}
              className={cn('min-h-4 text-xs', noteOk ? 'text-green-700 dark:text-green-400' : 'text-muted-foreground')}>
              {noteOk ? (dialog.noteOkText ?? '') : (dialog.noteHint ?? '')}
            </p>
          </div>
        )}

        <AlertDialogFooter>
          {dialog.type !== 'alert' && (
            <Button type="button" variant="outline" onClick={onCancel}>
              {dialog.cancelText ?? 'İptal'}
            </Button>
          )}
          <Button
            ref={confirmRef}
            type="button"
            variant={v.tone === 'destructive' ? 'destructive' : 'default'}
            disabled={!noteOk}
            onClick={confirm}
          >
            {dialog.confirmText ?? 'Tamam'}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

export function DialogProvider({ children }) {
  const [dialog, setDialog] = useState(null)

  const _show = useCallback((config) =>
    new Promise(resolve => setDialog({ ...config, resolve })),
  [])

  const showConfirm = useCallback((opts) =>
    _show({ type: 'confirm', variant: 'danger', confirmText: 'Sil', cancelText: 'İptal', ...opts }),
  [_show])

  const showPrompt = useCallback((opts) =>
    _show({ type: 'prompt', variant: 'prompt', confirmText: 'Tamam', cancelText: 'İptal', ...opts }),
  [_show])

  const showAlert = useCallback((opts) =>
    _show({ type: 'alert', variant: 'info', confirmText: 'Tamam', ...opts }),
  [_show])

  /**
   * Zorunlu gerekçe notlu onay — {@code { confirmed, note }} ya da iptalde {@code { confirmed:false }}.
   *
   * <p>Neden AYRI bir metot: {@code showConfirm}'ün onlarca çağrısı var ve hepsi {@code true/false}
   * bekliyor. Dönüş şeklini oraya eklemek her birini riske atardı; yeni yol hiçbirine dokunmuyor.
   */
  const showNoteConfirm = useCallback((opts) =>
    _show({ type: 'note', variant: 'warning', confirmText: 'Onayla', cancelText: 'İptal', ...opts }),
  [_show])

  function handleConfirm(value) {
    const resolve = dialog.resolve
    setDialog(null)
    resolve(value)
  }

  function handleCancel() {
    const resolve = dialog.resolve
    const type    = dialog.type
    setDialog(null)
    if (type === 'prompt') return resolve(null)
    // Not modali her zaman NESNE döner — çağıran `res.confirmed` okuyor; burada `false`
    // dönseydi iptalde `res.confirmed` okuması patlardı.
    if (type === 'note') return resolve({ confirmed: false, note: '' })
    resolve(false)
  }

  return (
    <DialogCtx.Provider value={{ showConfirm, showPrompt, showAlert, showNoteConfirm }}>
      {children}
      {dialog && (
        <DialogModal dialog={dialog} onConfirm={handleConfirm} onCancel={handleCancel} />
      )}
    </DialogCtx.Provider>
  )
}

export function useDialog() {
  const ctx = useContext(DialogCtx)
  if (!ctx) throw new Error('useDialog must be called inside <DialogProvider>')
  return ctx
}
