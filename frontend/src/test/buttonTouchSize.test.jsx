import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Button, touchSizeClass } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { InputGroup, InputGroupInput } from '@/components/shadcn/input-group'
import { Switch } from '@/components/shadcn/switch'

/**
 * Dokunmatik boy tabanda (2026-11-05, mweb denetimi): parmakla dokunulan ekranda (`pointer: coarse`) varsayılan düğme,
 * ikon düğme, metin girişi ve giriş grubu 40 px; anahtarın görünmez vuruş alanı 40 × 40 px. jsdom yerleşim yapmaz —
 * burada yalnız sınıf sözleşmesi; gerçek ölçü e2e/mweb-sweep.spec.js'te.
 */
describe('dokunmatik boy — shadcn tabanı', () => {
  it('varsayılan ve ikon boyutlu düğme dokunmatik sınıfını alır', () => {
    expect(touchSizeClass('default', undefined)).toBe('pointer-coarse:h-10')
    expect(touchSizeClass('icon', '')).toBe('pointer-coarse:size-10')
  })

  it('bilinçli küçük boylar dokunmatik sınıfını çağırana bırakır', () => {
    for (const size of ['sm', 'xs', 'icon-sm', 'icon-xs', 'lg', 'icon-lg']) expect(touchSizeClass(size, '')).toBeNull()
  })

  it('çağıran kendi yüksekliğini verdiyse (metin içi bağlantı, sabit boy) dokunulmaz', () => {
    expect(touchSizeClass('default', 'h-auto p-0')).toBeNull()
    expect(touchSizeClass('default', 'w-full h-8')).toBeNull()
    expect(touchSizeClass('default', 'min-h-12 gap-2')).toBeNull()
    expect(touchSizeClass('icon', 'size-7')).toBeNull()
    expect(touchSizeClass('default', '!h-6')).toBeNull()
  })

  it('önekli yükseklik sınıfı (sm:, in-[…]:) çağıranın yüksekliği sayılmaz', () => {
    expect(touchSizeClass('default', 'sm:h-8 in-[.x]:h-7')).toBe('pointer-coarse:h-10')
    expect(touchSizeClass('default', 'shrink-0 whitespace-nowrap')).toBe('pointer-coarse:h-10')
  })

  it('düğme sınıfı çağıranın dokunmatik sınıfıyla birleşir — çağıranınki kazanır', () => {
    render(<>
      <Button>Kaydet</Button>
      <Button size="icon" aria-label="Kapat">x</Button>
      <Button className="pointer-coarse:h-12">Büyük</Button>
      <Button variant="link" className="h-auto p-0">Bağlantı</Button>
      <Button size="sm">Küçük</Button>
    </>)
    expect(screen.getByRole('button', { name: 'Kaydet' }).className).toContain('pointer-coarse:h-10')
    expect(screen.getByRole('button', { name: 'Kapat' }).className).toContain('pointer-coarse:size-10')
    const big = screen.getByRole('button', { name: 'Büyük' }).className
    expect(big).toContain('pointer-coarse:h-12')
    expect(big).not.toContain('pointer-coarse:h-10')
    expect(screen.getByRole('button', { name: 'Bağlantı' }).className).not.toContain('pointer-coarse')
    expect(screen.getByRole('button', { name: 'Küçük' }).className).not.toContain('pointer-coarse')
  })

  it('metin girişi, giriş grubu ve anahtar dokunmatik sınıflarını taşır', () => {
    render(<>
      <Input aria-label="Ad" />
      <InputGroup data-testid="grp"><InputGroupInput aria-label="Ara" /></InputGroup>
      <Switch aria-label="Etkin" />
    </>)
    expect(screen.getByLabelText('Ad').className).toContain('pointer-coarse:h-10')
    expect(screen.getByLabelText('Ara').className).toContain('pointer-coarse:h-10')
    expect(screen.getByTestId('grp').className).toContain('pointer-coarse:min-h-10')
    const sw = screen.getByRole('switch', { name: 'Etkin' }).className
    expect(sw).toContain('relative')
    expect(sw).toContain('pointer-coarse:after:size-10')
  })
})
