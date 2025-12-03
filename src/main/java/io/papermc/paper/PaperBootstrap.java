package io.papermc.paper;

import org.yaml.snakeyaml.Yaml;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerContainer;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.*;
import org.eclipse.jetty.websocket.servlet.*;

@ServerEndpoint("/{wsPath}") // WebSocket端点，动态路径
public class PaperBootstrap {
    // 配置参数
    private static String uuid;
    private static int port;
    private static String wsPath;
    private static String host;
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    // 主方法：启动WebSocket服务器
    public static void main(String[] args) throws Exception {
        // 加载配置
        loadConfig();

        // 启动Jetty服务器（提供WebSocket支持）
        Server server = new Server(port);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        // 配置WebSocket
        WebSocketServletRegistration registration = context.addServlet(WebSocketServlet.class, "/" + wsPath);
        registration.setInitParameter("javax.websocket.server.ServerContainer", 
            "io.papermc.paper.PaperBootstrap");

        // 启动服务
        server.start();
        System.out.println("✅ VLESS-WS节点已部署");
        System.out.println("地址：ws://" + host + ":" + port + "/" + wsPath);
        System.out.println("VLESS链接：" + generateVlessLink());
        server.join();
    }

    // 加载配置（从config.yml、环境变量或默认值）
    private static void loadConfig() {
        try {
            // 1. 从config.yml读取
            Map<String, String> config = loadFromYml();
            // 2. 环境变量补充
            uuid = getConfig(config, "uuid", System.getenv("UUID"), "请输入UUID（必填）: ");
            port = Integer.parseInt(getConfig(config, "port", System.getenv("PORT"), "8080", "请输入端口（默认8080）: "));
            wsPath = getConfig(config, "wsPath", System.getenv("WS_PATH"), "/" + uuid.split("-")[0], "请输入WS路径: ");
            host = getConfig(config, "host", System.getenv("HOST"), "0.0.0.0", "请输入主机名: ");

            // 校验UUID
            if (!isValidUUID(uuid)) {
                throw new RuntimeException("UUID格式错误");
            }
        } catch (Exception e) {
            System.err.println("配置错误：" + e.getMessage());
            System.exit(1);
        }
    }

    // WebSocket连接建立时触发
    @OnOpen
    public void onOpen(Session session, EndpointConfig config) {
        System.out.println("新连接：" + session.getId());
    }

    // 接收客户端数据（核心：转发到目标服务器）
    @OnMessage
    public void onMessage(Session session, ByteBuffer buffer) {
        executor.submit(() -> {
            try {
                // 解析VLESS协议头部（简化版）
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                if (!verifyUuid(data)) {
                    session.close(new CloseReason(CloseReason.CloseCodes.POLICY_VIOLATION, "UUID错误"));
                    return;
                }

                // 解析目标地址和端口（VLESS协议格式）
                String targetHost = parseHost(data);
                int targetPort = parsePort(data);

                // 建立到目标服务器的TCP连接
                SocketChannel targetChannel = SocketChannel.open(new InetSocketAddress(targetHost, targetPort));
                targetChannel.configureBlocking(false);

                // 转发数据：客户端→目标服务器
                executor.submit(() -> forward(session, targetChannel));
                // 转发数据：目标服务器→客户端
                executor.submit(() -> forward(targetChannel, session));

            } catch (Exception e) {
                try { session.close(); } catch (Exception ignored) {}
            }
        });
    }

    // 连接关闭时清理资源
    @OnClose
    public void onClose(Session session) {
        System.out.println("连接关闭：" + session.getId());
    }

    // 数据转发：WebSocket→目标服务器
    private void forward(Session session, SocketChannel targetChannel) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (session.isOpen() && targetChannel.isOpen()) {
                // 读取客户端数据并转发
                if (session.getBasicRemote().recv(buffer) > 0) {
                    buffer.flip();
                    targetChannel.write(buffer);
                    buffer.clear();
                }
            }
        } catch (Exception e) {
            close(session, targetChannel);
        }
    }

    // 数据转发：目标服务器→WebSocket客户端
    private void forward(SocketChannel targetChannel, Session session) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (session.isOpen() && targetChannel.isOpen()) {
                // 读取目标服务器数据并转发给客户端
                if (targetChannel.read(buffer) > 0) {
                    buffer.flip();
                    session.getBasicRemote().sendBinary(buffer);
                    buffer.clear();
                }
            }
        } catch (Exception e) {
            close(session, targetChannel);
        }
    }

    // 关闭连接
    private void close(Session session, SocketChannel channel) {
        try { session.close(); } catch (Exception ignored) {}
        try { channel.close(); } catch (Exception ignored) {}
    }

    // 生成VLESS链接
    private static String generateVlessLink() {
        return String.format(
            "vless://%s@%s:%d?encryption=none&security=none&type=ws&host=%s&path=%s#VLESS-WS",
            uuid, host, port, host, wsPath
        );
    }

    // 校验UUID（VLESS协议头部）
    private boolean verifyUuid(byte[] data) {
        if (data.length < 17) return false;
        byte[] receivedUuid = Arrays.copyOfRange(data, 1, 17);
        byte[] expectedUuid = uuidToBytes(uuid);
        return Arrays.equals(receivedUuid, expectedUuid);
    }

    // UUID转字节数组
    private byte[] uuidToBytes(String uuid) {
        String hex = uuid.replace("-", "");
        byte[] bytes = new byte[16];
        for (int i = 0; i < 16; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i*2, i*2+2), 16);
        }
        return bytes;
    }

    // 解析目标主机（简化版VLESS协议）
    private String parseHost(byte[] data) {
        // 实际应根据VLESS协议规范解析，这里简化处理
        return "example.com"; // 实际场景需从data中解析
    }

    // 解析目标端口
    private int parsePort(byte[] data) {
        // 实际应根据VLESS协议规范解析
        return 80;
    }

    // 工具方法：读取配置
    private static String getConfig(Map<String, String> config, String key, String envVal, String prompt) {
        String val = config.getOrDefault(key, envVal);
        if (val == null || val.trim().isEmpty()) {
            System.out.print(prompt);
            val = new Scanner(System.in).nextLine().trim();
        }
        return val;
    }

    private static String getConfig(Map<String, String> config, String key, String envVal, String defVal, String prompt) {
        String val = config.getOrDefault(key, envVal);
        if (val == null || val.trim().isEmpty()) {
            System.out.print(prompt);
            val = new Scanner(System.in).nextLine().trim();
            if (val.isEmpty()) val = defVal;
        }
        return val;
    }

    // 从YAML读取配置
    private static Map<String, String> loadFromYml() {
        try (InputStream in = new FileInputStream("config.yml")) {
            return new Yaml().load(in);
        } catch (FileNotFoundException e) {
            return new HashMap<>(); // 无文件则返回空
        } catch (Exception e) {
            System.err.println("读取config.yml失败，使用默认配置");
            return new HashMap<>();
        }
    }

    // UUID校验
    private static boolean isValidUUID(String uuid) {
        return uuid.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    // WebSocketServlet：绑定端点
    public static class WebSocketServlet extends javax.websocket.server.ServerEndpointConfig.Configurator {
        @Override
        public <T> T getEndpointInstance(Class<T> clazz) {
            return (T) new PaperBootstrap();
        }
    }
}
