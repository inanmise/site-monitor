package com.sitemonitor.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test yardımcısı (2026-10-08, "zaman aşımı olmayan servis çağrısı" denetimi): yerel loopback soket sunucusu. Her
 * bağlantıda (istenirse) HTTP istek başlıklarını tüketir, bir önek yazar ve sonra her {@code intervalMs}'de bir bayt
 * DAMLATIR — {@code intervalMs <= 0} ise hiçbir şey yazmadan bağlantıyı açık tutar (TLS el sıkışması dahil her okuma
 * bloklar). Okuma-başı zaman aşımını hiç tetiklemeyen "yavaş sunucu"ya karşı TOPLAM süre sınırlarını sınar.
 *
 * <p>Not (ChainValidationServiceTest ile aynı gözlem): yerel Windows makinesinde uç nokta güvenlik yazılımı loopback'te
 * küçük parçaları istemciye geç/hiç ulaştırmayabilir. Testler bu yüzden okuma-başı zaman aşımını BÜTÇEDEN BÜYÜK kurar:
 * damla ulaşsa da ulaşmasa da çağrı ancak toplam süre bekçisi sayesinde bütçe içinde döner.
 */
public final class DripServer implements AutoCloseable {

    private final ServerSocket ss;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    private DripServer(boolean consumeHttpRequest, String prefix, long intervalMs) throws IOException {
        this.ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!closed) {
                try {
                    Socket s = ss.accept();
                    sockets.add(s);
                    Thread h = new Thread(() -> serve(s, consumeHttpRequest, prefix, intervalMs), "drip-conn");
                    h.setDaemon(true);
                    h.start();
                } catch (IOException e) {
                    return;   // sunucu kapandı
                }
            }
        }, "drip-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** @param prefix  bağlantıda ilk yazılan metin (boş/null = yok) */
    public static DripServer start(boolean consumeHttpRequest, String prefix, long intervalMs) throws IOException {
        return new DripServer(consumeHttpRequest, prefix, intervalMs);
    }

    public int port() {
        return ss.getLocalPort();
    }

    private void serve(Socket s, boolean consumeHttpRequest, String prefix, long intervalMs) {
        try {
            if (consumeHttpRequest) {
                InputStream in = s.getInputStream();
                int state = 0;   // istek başlıklarının sonu (CR LF CR LF)
                while (state < 4) {
                    int c = in.read();
                    if (c < 0) return;
                    state = (c == (state % 2 == 0 ? 13 : 10)) ? state + 1 : (c == 13 ? 1 : 0);
                }
            }
            OutputStream out = s.getOutputStream();
            if (prefix != null && !prefix.isEmpty()) {
                out.write(prefix.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
            for (int i = 0; i < 600 && !closed; i++) {   // en çok ~60 sn
                if (intervalMs > 0) {
                    out.write('a');
                    out.flush();
                    Thread.sleep(intervalMs);
                } else {
                    Thread.sleep(100);
                }
            }
        } catch (Exception ignored) {
            // istemci bağlantıyı kesti — beklenen
        }
    }

    @Override
    public void close() {
        closed = true;
        try { ss.close(); } catch (IOException ignored) { /* kapanıyor */ }
        for (Socket s : sockets) {
            try { s.close(); } catch (IOException ignored) { /* kapanıyor */ }
        }
    }
}
