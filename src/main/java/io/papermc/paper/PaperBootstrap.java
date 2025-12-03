package io.papermc.paper;

import org.yaml.snakeyaml.Yaml;
import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

// WebSocket端点定义
@ServerEndpoint("/{wsPath}")
public class PaperBootstrap {
    // 配置参数
    private static String uuid;
    private static int port;
    private static String wsPath;
    private static String host;
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    // 主方法：启动服务器
    public static void main(String[] args) throws Exception {
        // 加载配置
        loadConfig();

        // 启动Jetty服务器
        Server server = new Server(port);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        // 注册WebSocket Servlet（Jetty 9.4.x兼容方式）
        ServletHolder holder = new ServletHolder("VLESS-WS-Servlet", MyWebSocketServlet.class);
        context.addServlet(holder, "/" + wsPath + "/*");

        // 启动服务
        server.start();
        System.out.println("✅ VLESS-WS节点已部署");
        System.out.println("服务地址：ws://" + host + ":" + port + "/" + wsPath);
        System.out.println("VLESS链接：" + generateVlessLink());
        server.join();
    }

    // 内部类：WebSocket Servlet适配器（适配Jetty 9.4.x）
    public static class MyWebSocketServlet extends WebSocketServlet {
        @Override
        public void configure(WebSocketServletFactory factory) {
            // 注册WebSocket端点类
            factory.register(PaperBootstrap.class);
            // 设置最大消息大小（防止过大消息）
            factory.getPolicy().setMaxBinaryMessageSize(1024 * 1024); // 1MB
        }
    }

    // 加载配置（从config.yml、环境变量或控制台输入）
    private static void loadConfig() {
        try {
            // 1. 从config.yml读取
            Map<String, String> config = loadFromYml();
            // 2. 环境变量补充
            uuid = getConfig(config, "uuid", System.getenv("UUID"), "请输入UUID（必填）: ");
            port = Integer.parseInt(getConfig(config, "port", System.getenv("PORT"), "8080", "请输入端口（默认8080）: "));
            wsPath = getConfig(config, "wsPath", System.getenv("WS_PATH"), "/" + uuid.split("-")[0], "请输入WS路径（默认UUID前8位）: ");
            host = getConfig(config, "host", System.getenv("HOST"), "0.0.0.0", "请输入主机名（默认0.0.0.0）: ");

            // 校验UUID格式
            if (!isValidUUID(uuid)) {
                throw new RuntimeException("UUID格式错误（应为8-4-4-4-12位，如：xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx）");
            }
        } catch (Exception e) {
            System.err.println("配置错误：" + e.getMessage());
            System.exit(1);
        }
    }

    // WebSocket连接建立时触发
    @OnOpen
    public void onOpen(Session session, @PathParam("wsPath") String path) {
        System.out.println("新连接：" + session.getId() + "，路径：" + path);
    }

    // 接收客户端数据（核心转发逻辑）
    @OnMessage
    public void onMessage(Session session, ByteBuffer buffer) {
        executor.submit(() -> {
            try {
                // 读取客户端数据
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);

                // 验证UUID（VLESS协议简易校验）
                if (!verifyUuid(data)) {
                    session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "UUID验证失败"));
                    return;
                }

                // 解析目标地址和端口（示例：实际应按VLESS协议规范解析）
                String targetHost = "example.com"; // 替换为真实解析逻辑
                int targetPort = 80;

                // 建立到目标服务器的连接
                SocketChannel targetChannel = SocketChannel.open(new InetSocketAddress(targetHost, targetPort));
                targetChannel.configureBlocking(false);

                // 启动双向转发
                forwardClientToTarget(session, targetChannel);
                forwardTargetToClient(targetChannel, session);

            } catch (Exception e) {
                try { session.close(); } catch (Exception ignored) {}
            }
        });
    }

    // 客户端 → 目标服务器 数据转发
    private void forwardClientToTarget(Session session, SocketChannel targetChannel) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (session.isOpen() && targetChannel.isOpen()) {
                // 读取目标服务器响应并转发给客户端
                int bytesRead = targetChannel.read(buffer);
                if (bytesRead > 0) {
                    buffer.flip();
                    session.getBasicRemote().sendBinary(buffer);
                    buffer.clear();
                } else if (bytesRead == -1) {
                    break; // 目标服务器关闭连接
                }
            }
        } catch (Exception e) {
            closeResources(session, targetChannel);
        }
    }

    // 目标服务器 → 客户端 数据转发
    private void forwardTargetToClient(SocketChannel targetChannel, Session session) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (session.isOpen() && targetChannel.isOpen()) {
                // 此处通过onMessage接收客户端数据后直接写入目标服务器
                // （实际应在onMessage中处理写入逻辑）
            }
        } catch (Exception e) {
            closeResources(session, targetChannel);
        }
    }

    // 连接关闭时清理资源
    @OnClose
    public void onClose(Session session, CloseReason reason) {
        System.out.println("连接关闭：" + session.getId() + "，原因：" + reason.getReasonPhrase());
    }

    // 关闭会话和通道资源
    private void closeResources(Session session, SocketChannel channel) {
        try { session.close(); } catch (Exception ignored) {}
        try { channel.close(); } catch (Exception ignored) {}
    }

    // 生成VLESS节点链接
    private static String generateVlessLink() {
        return String.format(
            "vless://%s@%s:%d?encryption=none&security=none&type=ws&host=%s&path=%s#VLESS-WS节点",
            uuid, host, port, host, wsPath
        );
    }

    // 验证UUID（VLESS协议头部校验）
    private boolean verifyUuid(byte[] data) {
        if (data.length < 17) return false; // VLESS头部至少17字节（版本+UUID）
        byte[] receivedUuid = Arrays.copyOfRange(data, 1, 17); // 跳过版本字节（第1位）
        byte[] expectedUuid = uuidToBytes(uuid);
        return Arrays.equals(receivedUuid, expectedUuid);
    }

    // UUID转字节数组（用于协议校验）
    private byte[] uuidToBytes(String uuid) {
        String hex = uuid.replace("-", "");
        byte[] bytes = new byte[16];
        for (int i = 0; i < 16; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i*2, i*2+2), 16);
        }
        return bytes;
    }

    // 配置读取工具方法
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

    // 从config.yml读取配置
    private static Map<String, String> loadFromYml() {
        try (InputStream in = new FileInputStream("config.yml")) {
            return new Yaml().load(in);
        } catch (FileNotFoundException e) {
            System.out.println("未找到config.yml，将使用环境变量或手动输入");
            return new HashMap<>();
        } catch (Exception e) {
            System.err.println("读取config.yml失败，使用默认配置：" + e.getMessage());
            return new HashMap<>();
        }
    }

    // UUID格式校验
    private static boolean isValidUUID(String uuid) {
        return uuid.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }
}
