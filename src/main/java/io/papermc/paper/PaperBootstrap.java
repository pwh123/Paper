import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class V2RayProxy {

    // 配置参数（从环境变量读取或使用默认值）
    private static final String UUID = System.getenv("UUID") != null ? System.getenv("UUID") : "55e8ca56-8a0a-4486-b3f9-b9b0d46638a9";
    private static final String DOMAIN = System.getenv("DOMAIN") != null ? System.getenv("DOMAIN") : "";
    private static final String SUB_PATH = System.getenv("SUB_PATH") != null ? System.getenv("SUB_PATH") : "ccc";
    private static final String NAME = System.getenv("NAME") != null ? System.getenv("NAME") : "Vls";
    private static final int PORT = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : ;

    // 获取ISP信息（模拟）
    private static String getISP() {
        return "Unknown-ISP";
    }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/web", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                String response = "Hello, World\n";
                exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        });

        server.createContext("/" + SUB_PATH, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                String vlessURL = String.format(
                        "vless://%s@www.visa.com.tw:443?encryption=none&security=tls&sni=%s&type=ws&host=%s&path=/%s#%s-%s",
                        UUID, DOMAIN, DOMAIN, SUB_PATH, NAME, getISP()
                );
                String base64Content = Base64.getEncoder().encodeToString(vlessURL.getBytes(StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(200, base64Content.getBytes(StandardCharsets.UTF_8).length + 1);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(base64Content.getBytes(StandardCharsets.UTF_8));
                    os.write('\n');
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        });

        // WebSocket处理（简化版，实际需用Netty等库实现完整协议）
        server.createContext("/ws", exchange -> {
            if ("GET".equals(exchange.getRequestMethod()) && "websocket".equals(exchange.getRequestHeaders().getFirst("Upgrade"))) {
                // 这里仅示意，实际WebSocket需用第三方库如Tyrus或Netty
                exchange.sendResponseHeaders(426, -1); // 需要Netty等支持
                exchange.close();
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        });

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Server is running on port " + PORT);
    }
}
parsePort(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 8080; }
    }

    static byte[] parseUuidHex(String uuid) {
        String hex = uuid.replace("-", "").toLowerCase();
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            int pos = i * 2;
            out[i] = (byte) Integer.parseInt(hex.substring(pos, pos + 2), 16);
        }
        return out; // 补充缺失的闭合括号
    }

    // 获取ISP信息（通过Cloudflare API）
    static String fetchIsp() {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://speed.cloudflare.com/meta"))
                    .timeout(10, TimeUnit.SECONDS)
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String[] parts = response.body().split("\"");
                if (parts.length >= 26) {
                    return parts[25] + "-" + parts[17].replace(" ", "_");
                }
            }
        } catch (Exception e) {
            System.err.println("获取ISP信息失败: " + e.getMessage());
        }
        return "unknown-isp";
    }
}
