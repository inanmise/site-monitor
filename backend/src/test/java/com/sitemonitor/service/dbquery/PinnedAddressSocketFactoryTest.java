package com.sitemonitor.service.dbquery;

import org.junit.jupiter.api.Test;

import javax.net.SocketFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PostgreSQL {@code verify-full} DNS yeniden bağlama savunması (bağımsız inceleme O2): sürücü URL'deki ADI yeniden çözse de
 * soket yalnız SSRF'den geçmiş IP'ye bağlanır; ad URL'de kalır (sertifika adı + SNI). Sürücünün kendi kurulum yolu
 * ({@code SocketFactoryFactory}) bu sınıfı özelliklerden kurar.
 */
class PinnedAddressSocketFactoryTest {

    private static Properties pinnedTo(String ip) {
        Properties p = new Properties();
        p.setProperty("socketFactory", PinnedAddressSocketFactory.class.getName());
        if (ip != null) p.setProperty(PinnedAddressSocketFactory.ARG, ip);
        return p;
    }

    @Test
    void connectsToThePinnedAddressWhateverTheHost() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            PinnedAddressSocketFactory f = new PinnedAddressSocketFactory(pinnedTo("127.0.0.1"));
            // çözülemeyen / başka bir ad: yine kilitli IP'ye gider
            try (Socket s = f.createSocket()) {
                s.connect(InetSocketAddress.createUnresolved("rebind.invalid", port), 2_000);
                assertThat(s.getInetAddress()).isEqualTo(InetAddress.getByName("127.0.0.1"));
                assertThat(s.getPort()).isEqualTo(port);
            }
            try (Socket s = f.createSocket("evil.example", port)) {
                assertThat(s.getInetAddress().getHostAddress()).isEqualTo("127.0.0.1");
            }
            try (Socket s = f.createSocket(InetAddress.getByName("192.0.2.1"), port)) {
                assertThat(s.getInetAddress().getHostAddress()).isEqualTo("127.0.0.1");
            }
        }
    }

    @Test
    void refusesMissingOrNonLiteralAddress() {
        assertThrows(IllegalArgumentException.class, () -> new PinnedAddressSocketFactory(pinnedTo(null)));
        assertThrows(IllegalArgumentException.class, () -> new PinnedAddressSocketFactory(null));
        // ad DNS'e GİTMEZ: yalın IP değilse kurulmaz
        assertThrows(IllegalArgumentException.class, () -> new PinnedAddressSocketFactory(pinnedTo("localhost")));
        assertThrows(IllegalArgumentException.class, () -> new PinnedAddressSocketFactory(pinnedTo("10.0.0.5.example")));
        assertThat(new PinnedAddressSocketFactory(pinnedTo("0:0:0:0:0:0:0:1")).pinned())
                .isEqualTo(InetAddress.ofLiteral("::1"));
    }

    @Test
    void driverBuildsTheFactoryFromTheUrlBuilderProps() throws Exception {
        InetAddress ip = InetAddress.getByName("10.0.0.5");
        DbConnectionSpec vf = new DbConnectionSpec(DbType.POSTGRESQL, "pg.example", 5432, "app", null, null, "u", "p",
                "verify-full", false, false, 1_000);
        DbJdbcUrls.Target t = DbJdbcUrls.build(vf, ip, 10);
        assertThat(t.url()).isEqualTo("jdbc:postgresql://pg.example:5432/app");   // ad: sertifika doğrulaması + SNI
        assertThat(t.props().getProperty("socketFactory")).isEqualTo(PinnedAddressSocketFactory.class.getName());
        assertThat(t.props().getProperty(PinnedAddressSocketFactory.ARG)).isEqualTo("10.0.0.5");
        SocketFactory f = org.postgresql.core.SocketFactoryFactory.getSocketFactory(t.props());
        assertThat(f).isInstanceOf(PinnedAddressSocketFactory.class);
        assertThat(((PinnedAddressSocketFactory) f).pinned()).isEqualTo(ip);
        // IPv6: yalın yazım (köşeli parantez / bölge kimliği yok)
        DbJdbcUrls.Target v6 = DbJdbcUrls.build(vf, InetAddress.getByName("::1"), 10);
        assertThat(v6.props().getProperty(PinnedAddressSocketFactory.ARG)).isEqualTo("0:0:0:0:0:0:0:1");
        // diğer kiplerde IP zaten URL'de: fabrika yok
        for (String mode : new String[] {null, "disable", "allow", "prefer", "require", "verify-ca"}) {
            DbConnectionSpec s = new DbConnectionSpec(DbType.POSTGRESQL, "pg.example", 5432, "app", null, null, "u", "p",
                    mode, false, false, 1_000);
            DbJdbcUrls.Target x = DbJdbcUrls.build(s, ip, 10);
            assertThat(x.url()).as(String.valueOf(mode)).isEqualTo("jdbc:postgresql://10.0.0.5:5432/app");
            assertThat(x.props().getProperty("socketFactory")).as(String.valueOf(mode)).isNull();
            assertThat(x.props().getProperty(PinnedAddressSocketFactory.ARG)).as(String.valueOf(mode)).isNull();
        }
    }
}
