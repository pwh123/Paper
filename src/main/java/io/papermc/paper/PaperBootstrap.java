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
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

// WebSocket端点定义（路径从配置读取，通过参数传递）
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

        // 配置WebSocket（Jetty 9.4.x 正确方式）
        JettyWebSocketServletContainerInitializer.configure(context, (servletContext, container) -> {
            // 设置最大消息大小
            container.setMaxTextMessageSize(65536);
            // 注册WebSocket端点，路径与@ServerEndpoint一致
            container.addEndpoint(PaperBootstrap.class);
        });

        // 启动服务
        server.start();
        System.out.println("✅ VLESS-WS节点已部署");
        System.out.println("地址：ws://" + host + ":" + port + "/" + wsPath);
        System.out.println("VLESS链接：" + generateVlessLink());
        server.join();
    }

    // 加载配置
    private static void loadConfig() {
        try {
            // 从config.yml读取
            Map<String, String> config = loadFromYml();
            // 环境变量补充
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

    // WebSocket连接建立时触发（带路径参数）
    @OnOpen
    public void onOpen(Session session, @PathParam("wsPath") String path) {
        System.out.println("新连接：" + session.getId() + "，路径：" + path);
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
                    // 修正CloseCodes枚举值
                    session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "UUID错误"));
                    return;
                }

                // 解析目标地址和端口（示例）
                String targetHost = "example.com"; // 实际应从data解析
                int targetPort = 80;

                // 建立到目标服务器的连接
                SocketChannel targetChannel = SocketChannel.open(new InetSocketAddress(targetHost, targetPort));
                targetChannel.configureBlocking(false);

                // 双向转发
                forwardFromClient(session, targetChannel);
                forwardToClient(targetChannel, session);

            } catch (Exception e) {
                try { session.close(); } catch (Exception ignored) {}
            }
        });
    }

    // 从客户端转发到目标服务器
    private void forwardFromClient(Session session, SocketChannel targetChannel) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (session.isOpen() && targetChannel.isOpen()) {
                // WebSocket数据通过onMessage接收，此处循环读取目标服务器响应
                int bytesRead = targetChannel.read(buffer);
                if (bytesRead > 0) {
                    buffer.flip();
                    session.getBasicRemote().sendBinary(buffer);
                    buffer.clear();
                } else if (bytesRead == -1) {
                    break; // 连接关闭
                }
            }
        } catch (Exception e) {
            close(session, targetChannel);
        }
    }

    // 从目标服务器转发到客户端
    private void forwardToClient(SocketChannel targetChannel, Session session) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (session.isOpen() && targetChannel.isOpen()) {
                // 读取客户端数据（通过onMessage触发，此处仅处理发送）
                // 实际应在onMessage中直接写入targetChannel
            }
        } catch (Exception e) {
            close(session, targetChannel);
        }
    }

    // 连接关闭时清理
    @OnClose
    public void onClose(Session session, CloseReason reason) {
        System.out.println("连接关闭：" + session.getId() + "，原因：" + reason.getReasonPhrase());
    }

    // 关闭资源
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

    // 校验UUID
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

    // 配置读取工具
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
            return new HashMap<>();
        } catch (Exception e) {
            System.err.println("读取config.yml失败，使用默认配置");
            return new HashMap<>();
        }
    }

    // UUID格式校验
    private static boolean isValidUUID(String uuid) {
        return uuid.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }
}
