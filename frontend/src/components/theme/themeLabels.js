/**
 * Tema adları / açıklamaları / şema etiketleri — `t()` çağrıları LİTERAL (i18n-used-keys kapısı dinamik anahtarı
 * göremez; `t('theme.name.' + id)` yazılsaydı eksik çeviri ekranda ham anahtar olarak kalırdı). Yeni tema →
 * `theme/themes.js` + burası + TR/EN sözlük (src/test/theme-catalog-sync.test.js hepsini karşılaştırır).
 */
export function themeName(t, id) {
  switch (id) {
    case 'light': return t('theme.name.light')
    case 'dark': return t('theme.name.dark')
    case 'blueprint': return t('theme.name.blueprint')
    case 'parchment': return t('theme.name.parchment')
    case 'alloy': return t('theme.name.alloy')
    case 'obsidian': return t('theme.name.obsidian')
    case 'slag': return t('theme.name.slag')
    case 'crucible': return t('theme.name.crucible')
    default: return String(id ?? '')
  }
}

export function themeDescription(t, id) {
  switch (id) {
    case 'light': return t('theme.desc.light')
    case 'dark': return t('theme.desc.dark')
    case 'blueprint': return t('theme.desc.blueprint')
    case 'parchment': return t('theme.desc.parchment')
    case 'alloy': return t('theme.desc.alloy')
    case 'obsidian': return t('theme.desc.obsidian')
    case 'slag': return t('theme.desc.slag')
    case 'crucible': return t('theme.desc.crucible')
    default: return ''
  }
}

export function schemeLabel(t, scheme) {
  return scheme === 'dark' ? t('theme.scheme.dark') : t('theme.scheme.light')
}
