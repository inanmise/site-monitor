import { forwardRef, useCallback, useId, useRef } from 'react'
import { SendHorizontal } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Kbd, KbdGroup } from '@/components/shadcn/kbd'
import { Label } from '@/components/shadcn/label'
import { Textarea } from '@/components/shadcn/textarea'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'
import { NOTE_CATEGORIES, NOTE_MAX_LENGTH, NOTE_NEAR_LIMIT } from './notesModel.js'
import { CAT_ICON, isMacLike, isSubmitCombo, useEscapeGuard } from './notesParts.jsx'

/**
 * Not yazma kartı — kategori (shadcn ToggleGroup, tek seçim, simgeli çipler) · kendiliğinden büyüyen Textarea
 * (field-sizing, tavanlı) · Ctrl/⌘+Enter ile kaydet · karakter sayacı (sunucu sınırı 5000; %90'da uyarı tonu) ·
 * meşgul durumu (düğmede Spinner, `aria-busy`) · satır içi sunucu hatası. Escape: taslak varken pencereyi KAPATMAZ,
 * yalnız yazı alanından çıkar (bkz. useEscapeGuard). Test kancası: `data-slot="cert-note-form"`.
 *
 * `ref` → yazı alanı (boş durumdaki "İlk notu yaz" düğmesi buraya odaklanır).
 */
const CertNoteComposer = forwardRef(function CertNoteComposer({ value, onChange, category, onCategoryChange, onSubmit, saving, error }, ref) {
  const t = useT()
  const catId = useId()
  const bodyId = useId()
  const hintId = useId()
  const wrapRef = useRef(null)
  const len = value.length
  const near = len >= NOTE_MAX_LENGTH * NOTE_NEAR_LIMIT
  const canSubmit = !saving && value.trim().length > 0 && len <= NOTE_MAX_LENGTH
  const blur = useCallback(() => { try { document.activeElement?.blur?.() } catch { /* yok say */ } }, [])
  useEscapeGuard(wrapRef, blur, value.length > 0)

  return (
    // Kap `div`: shadcn Card ref iletmez (React 18 işlev bileşeni) — Escape koruması odağın kartın içinde olduğunu buradan okur.
    <div ref={wrapRef} className="min-w-0">
    <Card data-slot="cert-note-form" className="min-w-0 gap-3 px-3 py-3 shadow-none sm:px-4">
      <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-center">
        <span id={catId} className="text-sm font-medium">{t('notes.categoryLabel')}</span>
        <ToggleGroup type="single" variant="outline" size="sm" spacing={1} value={category} aria-labelledby={catId}
          onValueChange={(v) => { if (v) onCategoryChange(v) }} className="flex-wrap sm:ml-auto">
          {NOTE_CATEGORIES.map((c) => {
            const Icon = CAT_ICON[c]
            return (
              <ToggleGroupItem key={c} value={c} data-slot="cert-note-cat-option"
                className="gap-1.5 text-[13px] max-sm:h-10 data-[state=on]:border-primary/50 data-[state=on]:bg-primary/10 data-[state=on]:text-primary">
                <Icon aria-hidden="true" className="size-3.5" />
                {t(`notes.cat.${c}`)}
              </ToggleGroupItem>
            )
          })}
        </ToggleGroup>
      </div>

      <Label htmlFor={bodyId} className="sr-only">{t('cnote.bodyLabel')}</Label>
      <Textarea
        ref={ref}
        id={bodyId}
        value={value}
        maxLength={NOTE_MAX_LENGTH}
        aria-describedby={hintId}
        placeholder={t('cnote.placeholder')}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => { if (isSubmitCombo(e)) { e.preventDefault(); if (canSubmit) onSubmit() } }}
        className="max-h-72 min-h-24 resize-y"
      />

      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <span id={hintId} className="inline-flex items-center gap-1 text-xs text-muted-foreground max-sm:sr-only">
          <KbdGroup><Kbd>{isMacLike() ? '⌘' : 'Ctrl'}</Kbd><Kbd>Enter</Kbd></KbdGroup>
          {t('cnote.toSave')}
        </span>
        <span data-slot="cert-note-counter" data-near={near ? 'true' : undefined}
          className={cn('ml-auto text-xs tabular-nums text-muted-foreground', near && 'font-semibold text-amber-700 dark:text-amber-400')}>
          {t('cnote.counter', len, NOTE_MAX_LENGTH)}
        </span>
        <Button type="button" size="sm" className="gap-1.5 max-sm:h-10" onClick={onSubmit} disabled={!canSubmit} aria-busy={saving || undefined}>
          {saving ? <Spinner size={14} inline decorative /> : <SendHorizontal aria-hidden="true" className="size-4" />}
          {saving ? t('notes.saving') : t('cnote.add')}
        </Button>
      </div>

      {error && <AlertBanner tone="danger" role="alert" className="mb-0">{error}</AlertBanner>}
    </Card>
    </div>
  )
})

export default CertNoteComposer
