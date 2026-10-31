package com.sitemonitor.service.scripted;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * CIDR aralığından TEK bir adresi çıkaran saf yardımcı (2026-10-31, k6 inceleme A6).
 *
 * <p>Neden: k6 {@code --blacklist-ip} vekil kullanılırken HEDEFE değil VEKİLE bağlanır; kurumsal vekil iç ağdaysa kara
 * liste vekili de keser. Eskiden çözüm vekili KAPSAYAN aralığın TAMAMINI düşürmekti — vekil 10.1.2.3 ise bütün
 * {@code 10.0.0.0/8} kara listeden çıkıyor, betik iç ağın tamamına erişebiliyordu. Artık yalnız vekilin kendi adresi
 * (/32, /128) açılır: aralık, o adresi DIŞLAYAN alt bloklara bölünür (en çok önek uzunluğu farkı kadar blok: IPv4'te
 * ≤ 32, IPv6'da ≤ 128).
 */
public final class CidrBlocks {

    private CidrBlocks() {}

    /** Ayrıştırılmış CIDR: ağ adresi baytları + önek. */
    public record Cidr(byte[] network, int prefix) {
        public int bits() { return network.length * 8; }

        @Override
        public String toString() {
            try {
                return InetAddress.getByAddress(network).getHostAddress() + "/" + prefix;
            } catch (Exception e) {
                return "?/" + prefix;
            }
        }
    }

    /** {@code "10.0.0.0/8"} → {@link Cidr}; ayrıştırılamıyorsa null. Ağ adresinin önek dışı bitleri sıfırlanır. */
    public static Cidr parse(String cidr) {
        if (cidr == null) return null;
        int slash = cidr.indexOf('/');
        if (slash <= 0) return null;
        try {
            String host = cidr.substring(0, slash).trim();
            // yalnız sayısal adres (DNS'e gitmesin)
            if (!host.matches("[0-9a-fA-F:.]{2,45}")) return null;
            byte[] net = InetAddress.getByName(host).getAddress();
            int prefix = Integer.parseInt(cidr.substring(slash + 1).trim());
            if (prefix < 0 || prefix > net.length * 8) return null;
            return new Cidr(mask(net, prefix), prefix);
        } catch (Exception e) {
            return null;
        }
    }

    /** Aralık adresi kapsıyor mu (aile farklıysa hayır)? */
    public static boolean contains(Cidr c, byte[] addr) {
        if (c == null || addr == null || addr.length != c.network().length) return false;
        byte[] m = mask(addr, c.prefix());
        return java.util.Arrays.equals(m, c.network());
    }

    /**
     * {@code range} aralığından {@code addr} adresini çıkarır. Adres aralıkta değilse aralık AYNEN döner; aralık zaten tek
     * adresse ve o adresse boş liste döner. Sonuç aralığı kapsayıp yalnız {@code addr}'ı dışarıda bırakan, örtüşmeyen
     * blokların listesidir (küçükten büyüğe değil, bölünme sırasıyla).
     */
    public static List<Cidr> exclude(Cidr range, byte[] addr) {
        List<Cidr> out = new ArrayList<>();
        if (range == null) return out;
        if (!contains(range, addr)) { out.add(range); return out; }
        int bits = range.bits();
        for (int level = range.prefix(); level < bits; level++) {
            // addr'ın level+1 önekli bloğunun KARDEŞİ: aynı ilk level bit, (level)'inci bit ters
            byte[] sibling = mask(addr, level + 1);
            flipBit(sibling, level);
            out.add(new Cidr(sibling, level + 1));
        }
        return out;
    }

    /** CIDR metin listesinden adresleri çıkarır; ayrıştırılamayan girdi aynen kalır. Sıra korunur. */
    public static List<String> excludeAll(List<String> cidrs, List<byte[]> addrs) {
        List<String> out = new ArrayList<>();
        for (String text : cidrs) {
            Cidr c = parse(text);
            if (c == null) { out.add(text); continue; }
            List<Cidr> parts = List.of(c);
            for (byte[] a : addrs) {
                List<Cidr> next = new ArrayList<>();
                for (Cidr p : parts) next.addAll(exclude(p, a));
                parts = next;
            }
            if (parts.size() == 1 && parts.get(0).prefix() == c.prefix()
                    && java.util.Arrays.equals(parts.get(0).network(), c.network())) {
                out.add(text);                       // dokunulmadı: özgün yazımı koru
            } else {
                for (Cidr p : parts) out.add(p.toString());
            }
        }
        return out;
    }

    static byte[] mask(byte[] addr, int prefix) {
        byte[] out = addr.clone();
        for (int i = 0; i < out.length; i++) {
            int bitsHere = Math.max(0, Math.min(8, prefix - i * 8));
            int m = bitsHere == 0 ? 0 : (0xFF << (8 - bitsHere)) & 0xFF;
            out[i] = (byte) (out[i] & m);
        }
        return out;
    }

    private static void flipBit(byte[] b, int bitIndex) {
        int byteIdx = bitIndex / 8;
        int bit = 7 - (bitIndex % 8);
        b[byteIdx] = (byte) (b[byteIdx] ^ (1 << bit));
    }
}
