package app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.JettyWebSocketCreator;
import org.eclipse.jetty.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {

    // Read env with defaults; you can load .env via java-dotenv if desired
    static final String UUID = getenvOrDefault("UUID", "55e8ca56-8a0a-4486-b3f9-b9b0d46638a9");
    static final String DOMAIN = getenvOrDefault("DOMAIN", "");
    static final String SUB_PATH = getenvOrDefault("SUB_PATH", "ccc");
    static final String NAME = getenvOrDefault("NAME", "Vls");
    static final int PORT = parsePort(getenvOrDefault("PORT", "8080")); // fixed default

    // UUID prefix-based path
    static final String uuidPrefix = UUID.split("-")[0];
    static final String wsPath = "/" + uuidPrefix;

    // ISP name (from Cloudflare meta, fallback to "UnknownISP")
    static final String ISP = fetchIsp();

    public static void main(String[] args) throws Exception {
        Server server = new Server(PORT);

        // HTTP context
        ServletContextHandler httpCtx = new ServletContextHandler(ServletContextHandler.SESSIONS);
        httpCtx.setContextPath("/");
        // /web -> Hello, World
        httpCtx.addServlet(new ServletHolder(new WebServlet()), "/web");
        // /{SUB_PATH} -> base64 VLESS URL
        httpCtx.addServlet(new ServletHolder(new VlessServlet(UUID, DOMAIN, NAME, wsPath, ISP)), "/" + SUB_PATH);

        // WebSocket context mounted at root; bind creator only on wsPath
        JettyWebSocketServletContainerInitializer.configure(httpCtx, (context, container) -> {
            JettyWebSocketServerContainer jettyContainer = (JettyWebSocketServerContainer) container;
            jettyContainer.addMapping(wsPath, new JettyWebSocketCreator() {
                @Override
                public Object createWebSocket(org.eclipse.jetty.websocket.server.JettyServerUpgradeRequest req,
                                              org.eclipse.jetty.websocket.server.JettyServerUpgradeResponse resp) {
                    return new ProxySocket(UUID);
                }
            });
        });

        ContextHandlerCollection handlers = new ContextHandlerCollection();
        handlers.addHandler(httpCtx);
        server.setHandler(handlers);

        server.start();
        System.out.println("Server running on port " + PORT);
        System.out.println("WebSocket路径: " + wsPath);
        server.join();
    }

    // --- HTTP servlets ---

    static class WebServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
            try {
                resp.setStatus(200);
                resp.setContentType("text/plain");
                resp.getWriter().println("Hello, World");
            } catch (Exception ignored) {}
        }
    }

    static class VlessServlet extends HttpServlet {
        private final String uuid;
        private final String domain;
        private final String name;
        private final String wsPath;
        private final String isp;

        VlessServlet(String uuid, String domain, String name, String wsPath, String isp) {
            this.uuid = uuid;
            this.domain = domain;
            this.name = name;
            this.wsPath = wsPath;
            this.isp = isp;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
            try {
                resp.setStatus(200);
                resp.setContentType("text/plain");

                // Build VLESS URL
                String pathEnc = java.net.URLEncoder.encode(wsPath, StandardCharsets.UTF_8);
                String vlessURL = String.format(
                        "vless://%s@%s:443?encryption=none&security=tls&sni=%s&type=ws&host=%s&path=%s#%s-%s",
                        uuid, domain, domain, domain, pathEnc, name, isp
                );
                String base64 = Base64.getEncoder().encodeToString(vlessURL.getBytes(StandardCharsets.UTF_8));
                resp.getWriter().println(base64);
            } catch (Exception ignored) {}
        }
    }

    // --- WebSocket proxy ---

    static class ProxySocket extends WebSocketAdapter {
        private final byte[] uuidBytes; // 16 bytes parsed from UUID (hex)
        private final ExecutorService pool = Executors.newCachedThreadPool();
        private Socket tcpSocket;

        ProxySocket(String uuid) {
            this.uuidBytes = parseUuidHex(uuid);
        }

        @Override
        public void onWebSocketConnect(Session sess) {
            super.onWebSocketConnect(sess);
        }

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len) {
            try {
                byte[] msg = Arrays.copyOfRange(payload, offset, offset + len);

                // First frame contains handshake and target info in your custom format
                if (tcpSocket == null) {
                    // VERSION
                    int VERSION = msg[0] & 0xFF;

                    // id bytes: msg[1..16]
                    byte[] id = Arrays.copyOfRange(msg, 1, 17);
                    if (!Arrays.equals(id, uuidBytes)) {
                        getSession().close(1007, "Invalid UUID");
                        return;
                    }

                    // i = msg[17] + 19
                    int i = (msg[17] & 0xFF) + 19;

                    // port (UInt16BE)
                    int port = ((msg[i] & 0xFF) << 8) | (msg[i + 1] & 0xFF);
                    i += 2;

                    // ATYP
                    int ATYP = msg[i] & 0xFF;
                    i += 1;

                    // host parsing
                    String host;
                    switch (ATYP) {
                        case 1: // IPv4
                            host = String.format("%d.%d.%d.%d", msg[i] & 0xFF, msg[i + 1] & 0xFF,
                                    msg[i + 2] & 0xFF, msg[i + 3] & 0xFF);
                            i += 4;
                            break;
                        case 2: // domain
                            int dlen = msg[i] & 0xFF;
                            i += 1;
                            host = new String(msg, i, dlen, StandardCharsets.UTF_8);
                            i += dlen;
                            break;
                        case 3: // IPv6
                            // 16 bytes -> 8 groups
                            StringBuilder sb = new StringBuilder();
                            for (int g = 0; g < 8; g++) {
                                int hi = msg[i + g * 2] & 0xFF;
                                int lo = msg[i + g * 2 + 1] & 0xFF;
                                sb.append(String.format("%02x%02x", hi, lo));
                                if (g < 7) sb.append(":");
                            }
                            host = sb.toString();
                            i += 16;
                            break;
                        default:
                            host = "";
                    }

                    // respond [VERSION, 0]
                    getSession().getRemote().sendBytes(ByteBuffer.wrap(new byte[]{(byte) VERSION, 0}));

                    // connect TCP and start piping
                    tcpSocket = new Socket();
                    tcpSocket.connect(new InetSocketAddress(host, port), 10_000);
                    // write remaining initial bytes (if any)
                    if (i < msg.length) {
                        tcpSocket.getOutputStream().write(msg, i, msg.length - i);
                        tcpSocket.getOutputStream().flush();
                    }

                    // TCP -> WS
                    pool.submit(() -> {
                        byte[] buf = new byte[8192];
                        int read;
                        try {
                            var in = tcpSocket.getInputStream();
                            while ((read = in.read(buf)) != -1 && getSession().isOpen()) {
                                getSession().getRemote().sendBytes(ByteBuffer.wrap(buf, 0, read));
                            }
                        } catch (Exception ignored) {
                        } finally {
                            closeAll();
                        }
                    });

                } else {
                    // Subsequent frames: forward to TCP
                    tcpSocket.getOutputStream().write(msg);
                    tcpSocket.getOutputStream().flush();
                }

            } catch (Exception e) {
                closeAll();
            }
        }

        @Override
        public void onWebSocketText(String message) {
            // Not used; protocol expects binary
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            closeAll();
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            closeAll();
        }

        private void closeAll() {
            try {
                if (tcpSocket != null && !tcpSocket.isClosed()) tcpSocket.close();
            } catch (Exception ignored) {}
            try {
                if (getSession() != null && getSession().isOpen()) getSession().close();
            } catch (Exception ignored) {}
            pool.shutdownNow();
            try {
                pool.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
        }
    }

    // --- Utilities ---

    static String getenvOrDefault(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    static int parsePort(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 8080;
        }
    }

    static byte[] parseUuidHex(String uuid) {
        String hex = uuid.replace("-", "").toLowerCase();
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            int pos = i * 2;
            out[i] = (byte) Integer.parseInt(hex.substring(pos, pos + 2), 16);
        }
        return out;
    }

    static String fetchIsp() {
        // Try Cloudflare JSON instead of shell/awk for cross-platform reliability
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://speed.cloudflare.com/meta"))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(resp.body());
                // Combine ASN and organization if available
                String asn = opt(root, "clientAsn");
                String org = opt(root, "clientAsnName");
                String region = opt(root, "region");
                String city = opt(root, "city");
                String merged = String.join("-", Arrays.stream(new String[]{org, asn, region, city})
                        .filter(s -> s != null && !s.isBlank()).toArray(String[]::new));
                return merged.isBlank() ? "UnknownISP" : merged.replace(' ', '_');
            }
        } catch (Exception ignored) {}
        return "UnknownISP";
    }

    static String opt(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n != null && !n.isNull() ? n.asText() : null;
    }
}           }
            } finally {
                try {
                    socket.close();
                    ws.close(1000, "目标连接关闭");
                } catch (IOException e) {
                    // 忽略关闭错误
                }
            }
        }

        private void forwardFromWsToSocket(WebSocket ws, Socket socket) {
            try (OutputStream out = socket.getOutputStream()) {
                ws.addWebSocketListener(new WebSocket.Listener() {
                    @Override
                    public void onMessage(WebSocket conn, ByteBuffer message) {
                        try {
                            out.write(message.array(), 0, message.limit());
                            out.flush();
                        } catch (IOException e) {
                            System.err.println("WS到Socket转发错误: " + e.getMessage());
                            try {
                                ws.close(1011, "转发失败");
                            } catch (IOException ex) {
                                // 忽略
                            }
                        }
                    }
                });
            } catch (Exception e) {
                if (!socket.isClosed()) {
                    System.err.println("WS到Socket转发初始化错误: " + e.getMessage());
                }
            }
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            System.err.println("WebSocket错误: " + ex.getMessage());
        }

        @Override
        public void onStart() {
            System.out.println("WebSocket服务器启动完成");
        }
    }
}
