import { useMemo } from 'react'

/**
 * Tembel Markdown editör parçasının ortak gövdesi (2026-10-09, açılış paketi küçültme).
 *
 * Çağıranlar `@uiw/react-md-editor`'ı artık STATİK içe aktarmaz (aktarsaydı 1 MB'lık editör o ekranın ön-yükleme
 * grafiğine girerdi). Araç çubuğu komutları kütüphanenin `commands` nesnesinden kurulduğu için çağıran komut
 * DİZİSİ yerine bir KURUCU verir: `commands={(md) => [md.bold, …]}`. Kurucu burada, kütüphane yüklendikten sonra
 * çağrılır. Kimlik sözleşmesi korunur: editör `commands` / `extraCommands` prop'unun kimliği değişince komutları
 * yeniden kaydeder — kurucunun kimliği aynı kalırsa (çağıranın useCallback'i) dizi de aynı kalır; her çizimde yeni
 * kurucu veren çağıran (eskiden her çizimde yeni dizi veriyordu) her çizimde yeni dizi alır. Davranış birebir aynı.
 * Dizi verilirse olduğu gibi geçer.
 */
export function createMdEditor(MDEditor, mdCommands) {
  function MdEditorImpl({ commands, extraCommands, ...rest }) {
    const cmds = useMemo(() => (typeof commands === 'function' ? commands(mdCommands) : commands), [commands])
    const extra = useMemo(() => (typeof extraCommands === 'function' ? extraCommands(mdCommands) : extraCommands), [extraCommands])
    // undefined → kütüphanenin varsayılan komut seti (prop hiç verilmemiş gibi)
    return <MDEditor {...rest} commands={cmds} extraCommands={extra} />
  }
  return MdEditorImpl
}
