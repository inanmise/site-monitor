import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { CircleAlert, Import, X } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import {
  Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/shadcn/dialog'

const collator = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })

/**
 * Seçilebilecek kaynak kanallar: kullanıcının OKUYABİLDİĞİ kanallar (kanal listesiyle aynı `overview` yanıtı — yeni kanal
 * penceresinin "başka bir kanalın şablonunu kopyala" seçicisiyle aynı veri yolu), bu kanal hariç; ad A→Z (Türkçe), sonra takım.
 * Etiket "<kanal> — <takım>".
 */
export function importOptions(channels, currentId) {
  return (channels || [])
    .filter((c) => c && c.id != null && String(c.id) !== String(currentId))
    .sort((a, b) => collator.compare(a.name || '', b.name || '') || collator.compare(a.team_name || '', b.team_name || ''))
    .map((c) => ({ value: String(c.id), label: c.team_name ? `${c.name} — ${c.team_name}` : (c.name || `#${c.id}`), channel: c }))
}

/**
 * BAŞKA KANALDAN ŞABLON AL (2026-10-17, kullanıcı onayı D16). Kanal açılırken yapılan kopyalamanın düzenleyicideki karşılığı:
 * seçilen kanalın şablonu (`GET …/channels/{id}/template`) bu düzenleyiciye KAYDEDİLMEMİŞ TASLAK olarak yüklenir — sürüm
 * geçmişindeki "düzenleyiciye yükle" ile aynı yol (`onLoad`): taban sürüm bu kanalın ŞU ANKİ sürümü kalır, araya başkası
 * kaydederse kayıt 409 alır. Şablon kendi dilini korur. Okuma başarısızsa yarım şablon yüklenmez, sunucu iletisi gösterilir.
 * Yalnız düzenleyebilen görür (çağıran çizer).
 *
 * @param onLoad `(template, channelName) => Promise<boolean>` — true: yüklendi (pencere kapanır), false: kullanıcı vazgeçti
 */
export default function TemplateImportDialog({ open, channelId, onClose, onLoad, returnFocusRef }) {
  const t = useT()
  const toast = useToast()
  const [list, setList] = useState({ loading: false, error: null, channels: null })
  const [selected, setSelected] = useState('')
  const [busy, setBusy] = useState(false)
  const seq = useRef(0)

  const load = useCallback(async () => {
    const my = ++seq.current
    setList({ loading: true, error: null, channels: null })
    try {
      const res = await api.techErrors.overview()
      if (my !== seq.current) return
      if (res?.success && res.data) setList({ loading: false, error: null, channels: Array.isArray(res.data.channels) ? res.data.channels : [] })
      else setList({ loading: false, error: res?.error || t('te.imp.listUnavailable'), channels: null })
    } catch (e) {
      if (my === seq.current) setList({ loading: false, error: e?.message || t('te.imp.listUnavailable'), channels: null })
    }
  }, [t])

  useEffect(() => {
    if (open) { setSelected(''); load() } else seq.current++
  }, [open, load])

  const options = useMemo(() => importOptions(list.channels, channelId), [list.channels, channelId])
  const chosen = options.find((o) => o.value === selected)?.channel || null

  async function submit() {
    if (!chosen || busy) return
    setBusy(true)
    try {
      const res = await api.techErrors.template(chosen.id)
      const tpl = res?.success ? res.data?.template : null
      // Yarım şablon yüklenmez: bölüm dizisi olmayan yanıt okunamamış sayılır
      if (!tpl || typeof tpl !== 'object' || !Array.isArray(tpl.sections)) {
        toast.error(res?.error || t('te.imp.readProblem'), { details: res?.errorInfo })
        return
      }
      if (await onLoad(tpl, chosen.name || '')) onClose()
    } catch (e) {
      toast.error(e?.message || t('te.imp.readProblem'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o && !busy) onClose() }}>
      <DialogContent className="flex max-h-[92dvh] w-[calc(100vw-1.5rem)] flex-col gap-0 overflow-hidden p-0 sm:max-w-lg"
        data-slot="te-tpl-import-dialog" showCloseButton={false}
        onCloseAutoFocus={(e) => { if (returnFocusRef?.current) { e.preventDefault(); returnFocusRef.current.focus() } }}>
        <DialogClose asChild>
          <Button type="button" variant="ghost" size="icon" className="absolute top-2.5 right-2.5 size-8 pointer-coarse:size-10"
            aria-label={t('app.close')} disabled={busy}>
            <X aria-hidden="true" />
          </Button>
        </DialogClose>
        <DialogHeader className="border-b p-4 pe-14 text-left">
          <DialogTitle className="flex items-center gap-2"><Import aria-hidden="true" className="size-4 shrink-0" /> {t('te.imp.title')}</DialogTitle>
          <DialogDescription>{t('te.imp.desc')}</DialogDescription>
        </DialogHeader>
        <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-4" data-slot="te-tpl-import-body">
          {list.loading && (
            <div className="flex flex-col gap-2" role="status" aria-live="polite">
              <span className="sr-only">{t('te.loading')}</span>
              <Skeleton className="h-4 w-40 motion-reduce:animate-none" />
              <Skeleton className="h-10 w-full motion-reduce:animate-none" />
            </div>
          )}
          {list.error && (
            <StatusBlock tone="danger" icon={CircleAlert} title={t('te.imp.listUnavailableTitle')} description={list.error}
              actions={<Button type="button" variant="outline" onClick={load} className="pointer-coarse:min-h-10">{t('te.retry')}</Button>} />
          )}
          {list.channels && options.length === 0 && (
            <p className="m-0 text-sm text-muted-foreground" data-slot="te-tpl-import-empty">{t('te.imp.none')}</p>
          )}
          {list.channels && options.length > 0 && (
            <div className="flex min-w-0 flex-col gap-1.5" data-touch-picker="">
              <span id="te-tpl-import-label" className="text-sm font-semibold">{t('te.imp.source')}</span>
              <SearchableSelect ariaLabelledBy="te-tpl-import-label" value={selected} options={options} wrapLabels disabled={busy}
                placeholder={t('te.imp.placeholder')} onChange={(v) => setSelected(String(v))} />
              {chosen && (
                <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-tpl-import-meta">
                  {t('te.imp.meta', chosen.lang === 'en' ? t('te.tpl.langEn') : t('te.tpl.langTr'),
                    chosen.section_count ?? '—', chosen.field_count ?? '—', chosen.slot_count ?? '—')}
                </p>
              )}
            </div>
          )}
        </div>
        <DialogFooter className="flex-col-reverse gap-2 border-t p-4 sm:flex-row">
          <Button type="button" variant="outline" onClick={onClose} disabled={busy} className="pointer-coarse:min-h-10">{t('te.cancel')}</Button>
          <Button type="button" onClick={submit} disabled={!chosen || busy} className="pointer-coarse:min-h-10" data-slot="te-tpl-import-load">
            <Import aria-hidden="true" /> {busy ? t('te.loading') : t('te.imp.load')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
