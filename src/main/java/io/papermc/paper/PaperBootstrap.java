import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.java_websocket.server.WebSocketServerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import io.github.cdimascio.dotenv.Dotenv;

public class PaperBootstrap {
    // ANSI颜色常量
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_RESET = "\u001B[0m";

    // 配置参数（从.env或环境变量读取）
    private static final Dotenv dotenv = Dotenv.load();
    private static final String UUID = getConfig("UUID", "55e8ca56-8a0a-4486-b3f9-b9b0d46638a9");
    private static final String DOMAIN = getConfig("DOMAIN", "yge.pwhh.dpdns.org");
    private static final String SUB_PATH = getConfig("SUB_PATH", "ccc");
    private static final String NAME = getConfig("NAME", "Vls");
    private static final int PORT = Integer.parseInt(getConfig("PORT", "36500"));
    
    // WebSocket路径（UUID前8位）
    private static final String UUID_PREFIX = UUID.split("-")[0];
    private static final String WS_PATH = "/" + UUID_PREFIX;
    
    // ISP信息
    private static String ISP = "unknown-isp";

    // 配置读取工具方法
    private static String getConfig(String key, String defaultValue) {
        String envValue = System.getenv(key);
        return (envValue != null && !envValue.isEmpty()) ? envValue : dotenv.get(key, defaultValue);
    }

    public static void main(String[] args) throws Exception {
        // 初始化ISP信息
        initIspInfo();

        // 启动续期脚本
        startRenewScript();

        // 启动HTTP服务器
        com.sun.net.httpserver.HttpServer httpServer = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(PORT), 0);
        httpServer.createContext("/web", new WebHandler());
        httpServer.createContext("/" + SUB_PATH, new SubPathHandler());
        httpServer.start();
        System.out.println("HTTP Server started on port " + PORT);

        // 启动WebSocket服务器
        WebSocketServer wsServer = new VlessWebSocketServer(new InetSocketAddress(PORT), WS_PATH);
        wsServer.start();
        System.out.println("WebSocket Server started on path: " + WS_PATH);
    }

    // 启动续期脚本 renew.sh
    private static void startRenewScript() {
        File renewScript = new File("renew.sh");
        if (renewScript.exists() && renewScript.isFile()) {
            try {
                new ProcessBuilder("bash", "renew.sh")
                        .inheritIO()
                        .start();
                System.out.println(ANSI_GREEN + "renew.sh 已启动（自动续期中）" + ANSI_RESET);
            } catch (IOException e) {
                System.err.println(ANSI_RED + "执行 renew.sh 失败: " + e.getMessage() + ANSI_RESET);
            }
        } else {
            System.err.println(ANSI_RED + "renew.sh 未找到，跳过执行" + ANSI_RESET);
        }
    }

    // 从Cloudflare API获取ISP信息
    private static void initIspInfo() {
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .build();
            Request request = new Request.Builder()
                    .url("https://speed.cloudflare.com/meta")
                    .get()
                    .build();
            Response response = client.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                String meta = response.body().string();
                String[] parts = meta.split("\"");
                if (parts.length >= 26) {
                    ISP = parts[25] + "-" + parts[17];
                    ISP = ISP.replace(" ", "_");
                }
            }
        } catch (Exception e) {
            System.err.println("获取ISP信息失败: " + e.getMessage());
        }
    }

    // HTTP处理器：处理/web路径
    static class WebHandler implements com.sun.net.httpserver.HttpHandler {
        @Override
        public void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            String response = "Hello, World\n";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    // HTTP处理器：生成VLESS节点订阅
    static class SubPathHandler implements com.sun.net.httpserver.HttpHandler {
        @Override
        public void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            String encodedWsPath = URLEncoder.encode(WS_PATH, StandardCharsets.UTF_8.name());
            
            String vlessUrl = String.format(
                    "vless://%s@%s:443?encryption=none&security=tls&sni=%s&type=ws&host=%s&path=%s#%s-%s",
                    UUID, DOMAIN, DOMAIN, DOMAIN, encodedWsPath, NAME, ISP
            );
            
            String base64Content = Base64.getEncoder().encodeToString(vlessUrl.getBytes(StandardCharsets.UTF_8));
            
            exchange.sendResponseHeaders(200, base64Content.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(base64Content.getBytes());
            }
        }
    }

    // WebSocket服务器：处理VLESS代理
    static class VlessWebSocketServer extends WebSocketServer {
        private final String cleanUuid;

        public VlessWebSocketServer(InetSocketAddress address, String path) {
            super(createServerFactory(path), address);
            this.cleanUuid = UUID.replace("-", "");
        }

        // 创建WebSocket服务器工厂，指定路径
        private static WebSocketServerFactory createServerFactory(String path) {
            return new DefaultSSLWebSocketServerFactory() {
                @Override
                public String getWebSocketPath() {
                    return path;
                }
            };
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            System.out.println("新WebSocket连接: " + conn.getRemoteSocketAddress());
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            System.out.println("WebSocket连接关闭: " + conn.getRemoteSocketAddress());
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            // 忽略文本消息
        }

        @Override
        public void onMessage(WebSocket conn, ByteBuffer message) {
            try {
                byte[] msgBytes = message.array();
                if (msgBytes.length < 18) {
                    conn.close(1007, "无效消息长度");
                    return;
                }

                if (!validateUuid(msgBytes)) {
                    conn.close(1007, "UUID验证失败");
                    return;
                }

                int port = ((msgBytes[18] & 0xFF) << 8) | (msgBytes[19] & 0xFF);
                int atyp = msgBytes[20] & 0xFF;
                String targetHost = parseHost(msgBytes, atyp, 21);

                conn.send(ByteBuffer.wrap(new byte[]{msgBytes[0], 0x00}));

                Socket targetSocket = new Socket(targetHost, port);
                System.out.println("已连接目标服务器: " + targetHost + ":" + port);

                new Thread(() -> forwardFromSocketToWs(targetSocket, conn)).start();
                new Thread(() -> forwardFromWsToSocket(conn, targetSocket)).start();

            } catch (Exception e) {
                System.err.println("WebSocket消息处理错误: " + e.getMessage());
                conn.close(1011, "处理消息失败");
            }
        }

        private boolean validateUuid(byte[] msgBytes) {
            byte[] uuidBytes = new byte[16];
            for (int i = 0; i < 16; i++) {
                uuidBytes[i] = (byte) Integer.parseInt(cleanUuid.substring(i * 2, i * 2 + 2), 16);
            }
            for (int i = 0; i < 16; i++) {
                if (msgBytes[i + 1] != uuidBytes[i]) {
                    return false;
                }
            }
            return true;
        }

        private String parseHost(byte[] msgBytes, int atyp, int startIndex) throws IOException {
            switch (atyp) {
                case 1: // IPv4
                    return String.format("%d.%d.%d.%d",
                            msgBytes[startIndex] & 0xFF,
                            msgBytes[startIndex + 1] & 0xFF,
                            msgBytes[startIndex + 2] & 0xFF,
                            msgBytes[startIndex + 3] & 0xFF);
                case 2: // 域名
                    int domainLen = msgBytes[startIndex] & 0xFF;
                    return new String(msgBytes, startIndex + 1, domainLen, StandardCharsets.UTF_8);
                case 3: // IPv6
                    byte[] ipv6Bytes = new byte[16];
                    System.arraycopy(msgBytes, startIndex, ipv6Bytes, 0, 16);
                    return "[" + java.net.Inet6Address.getByAddress(ipv6Bytes).getHostAddress() + "]";
                default:
                    throw new IOException("不支持的地址类型: " + atyp);
            }
        }

        private void forwardFromSocketToWs(Socket socket, WebSocket ws) {
            try (java.io.InputStream in = socket.getInputStream()) {
                byte[] buffer = new byte[4096];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    ws.send(ByteBuffer.wrap(buffer, 0, len));
                }
            } catch (Exception e) {
                if (!socket.isClosed()) {
                    System.err.println("Socket到WS转发错误: " + e.getMessage());
                }
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
