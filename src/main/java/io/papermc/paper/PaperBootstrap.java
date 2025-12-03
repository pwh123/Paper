package io.papermc.paper;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {

    // 环境变量读取
    static final String UUID = getenvOrDefault("UUID", "55e8ca56-8a0a-4486-b3f9-b9b0d46638a9");
    static final String DOMAIN = getenvOrDefault("DOMAIN", "");
    static final String SUB_PATH = getenvOrDefault("SUB_PATH", "ccc");
    static final String NAME = getenvOrDefault("NAME", "Vls");
    static final int PORT = parsePort(getenvOrDefault("PORT", "8080"));

    // UUID 前 8 位作为 WebSocket 路径
    static final String uuidPrefix = UUID.split("-")[0];
    static final String wsPath = "/" + uuidPrefix;

    // ISP 信息
    static final String ISP = fetchIsp();

    public static void main(String[] args) throws Exception {
        Server server = new Server(PORT);

        // HTTP 服务
        ServletContextHandler httpCtx = new ServletContextHandler(ServletContextHandler.SESSIONS);
        httpCtx.setContextPath("/");
        httpCtx.addServlet(new ServletHolder(new WebServlet()), "/web");
        httpCtx.addServlet(new ServletHolder(new VlessServlet(UUID, DOMAIN, NAME, wsPath, ISP)), "/" + SUB_PATH);

        // WebSocket 服务
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

    // --- HTTP Servlet ---
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
        private final String uuid, domain, name, wsPath, isp;

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

    // --- WebSocket 代理 ---
    static class ProxySocket extends WebSocketAdapter {
        private final byte[] uuidBytes;
        private final ExecutorService pool = Executors.newCachedThreadPool();
        private Socket tcpSocket;

        ProxySocket(String uuid) {
            this.uuidBytes = parseUuidHex(uuid);
        }

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len) {
            try {
                byte[] msg = Arrays.copyOfRange(payload, offset, offset + len);

                if (tcpSocket == null) {
                    int VERSION = msg[0] & 0xFF;
                    byte[] id = Arrays.copyOfRange(msg, 1, 17);
                    if (!Arrays.equals(id, uuidBytes)) {
                        getSession().close(1007, "Invalid UUID");
                        return;
                    }

                    int i = (msg[17] & 0xFF) + 19;
                    int port = ((msg[i] & 0xFF) << 8) | (msg[i + 1] & 0xFF);
                    i += 2;
                    int ATYP = msg[i] & 0xFF;
                    i += 1;

                    String host;
                    switch (ATYP) {
                        case 1: host = String.format("%d.%d.%d.%d", msg[i] & 0xFF, msg[i+1] & 0xFF, msg[i+2] & 0xFF, msg[i+3] & 0xFF); i+=4; break;
                        case 2: int dlen = msg[i] & 0xFF; i++; host = new String(msg, i, dlen, StandardCharsets.UTF_8); i+=dlen; break;
                        case 3: StringBuilder sb = new StringBuilder(); for(int g=0; g<8; g++){int hi=msg[i+g*2]&0xFF; int lo=msg[i+g*2+1]&0xFF; sb.append(String.format("%02x%02x",hi,lo)); if(g<7) sb.append(":");} host=sb.toString(); i+=16; break;
                        default: host = "";
                    }

                    getSession().getRemote().sendBytes(ByteBuffer.wrap(new byte[]{(byte)VERSION,0}));

                    tcpSocket = new Socket();
                    tcpSocket.connect(new InetSocketAddress(host, port), 10000);
                    if (i < msg.length) {
                        tcpSocket.getOutputStream().write(msg, i, msg.length - i);
                        tcpSocket.getOutputStream().flush();
                    }

                    pool.submit(() -> {
                        byte[] buf = new byte[8192];
                        int read;
                        try {
                            var in = tcpSocket.getInputStream();
                            while ((read = in.read(buf)) != -1 && getSession().isOpen()) {
                                getSession().getRemote().sendBytes(ByteBuffer.wrap(buf, 0, read));
                            }
                        } catch (Exception ignored) {} finally { closeAll(); }
                    });

                } else {
                    tcpSocket.getOutputStream().write(msg);
                    tcpSocket.getOutputStream().flush();
                }

            } catch (Exception e) {
                closeAll();
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            closeAll();
        }

        private void closeAll() {
            try { if (tcpSocket != null && !tcpSocket.isClosed()) tcpSocket.close(); } catch (Exception ignored) {}
            try { if (getSession() != null && getSession().isOpen()) getSession().close(); } catch (Exception ignored) {}
            pool.shutdownNow();
            try { pool.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
    }

    // --- 工具方法 ---
    static String getenvOrDefault(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    static int parsePort(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 8080; }
    }

    static byte[] parseUuidHex(String uuid) {
        String hex = uuid.replace("-", "").toLowerCase();
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            int pos = i * 2;
            out[i] = (byte) Integer.parseInt(hex.substring(pos, pos + 2), 16);
        }
        return out
