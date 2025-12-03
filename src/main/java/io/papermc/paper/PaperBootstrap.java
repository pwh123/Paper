import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.java_websocket.server.WebSocketServerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.UUID;

public class PaperBootstrap {
    // 配置参数（可从环境变量或配置文件读取）
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    private static final String WS_PATH = System.getenv().getOrDefault("WS_PATH", "/vless");
    private static final String UUID = System.getenv().getOrDefault("UUID", "00000000-0000-0000-0000-000000000000");
    private static final String ISP = System.getenv().getOrDefault("ISP", "default-isp");

    public static void main(String[] args) throws Exception {
        System.out.println("Starting VLESS WebSocket Server...");
        System.out.println("Port: " + PORT);
        System.out.println("WebSocket Path: " + WS_PATH);
        System.out.println("UUID: " + UUID);
        System.out.println("ISP: " + ISP);

        // 启动WebSocket服务器
        WebSocketServer wsServer = new VlessWebSocketServer(new InetSocketAddress(PORT), WS_PATH);
        wsServer.start();
        System.out.println("Server started successfully!");

        // 注册关闭钩子，优雅停止服务器
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Stopping server...");
            try {
                wsServer.stop(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println("Server stopped.");
        }));
    }

    // 自定义WebSocket服务器实现
    static class VlessWebSocketServer extends WebSocketServer {
        private final String cleanUuid; // 去除横线的UUID，用于验证

        public VlessWebSocketServer(InetSocketAddress address, String path) {
            // 通过工厂模式指定WebSocket路径
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
            System.out.println("New connection from: " + conn.getRemoteSocketAddress());
            // 可在此处添加UUID验证逻辑
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            System.out.println("Connection closed: " + conn.getRemoteSocketAddress() + " (" + reason + ")");
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            // 处理文本消息（VLESS通常使用二进制消息，此处仅作示例）
            System.out.println("Received text: " + message);
            conn.send("Echo: " + message);
        }

        @Override
        public void onMessage(WebSocket conn, ByteBuffer message) {
            // 处理二进制消息，转发到目标服务器（示例逻辑）
            try {
                // 实际场景中需替换为真实目标地址
                Socket targetSocket = new Socket("localhost", 8081);
                forwardFromWsToSocket(conn, targetSocket);
                forwardFromSocketToWs(targetSocket, conn);
            } catch (IOException e) {
                System.err.println("Failed to connect to target: " + e.getMessage());
                conn.close(1011, "Target connection failed");
            }
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            System.err.println("Error in connection: " + ex.getMessage());
            if (conn != null) {
                // 处理连接错误
            }
        }

        @Override
        public void onStart() {
            System.out.println("Server started on port: " + getPort());
        }

        // 从WebSocket转发数据到目标Socket
        private void forwardFromWsToSocket(WebSocket ws, Socket socket) {
            try (OutputStream out = socket.getOutputStream()) {
                // 直接在消息回调中处理转发（替代addMessageListener）
                ws.setAttachment(socket); // 保存Socket引用，用于关闭
                new Thread(() -> {
                    try {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = socket.getInputStream().read(buffer)) != -1) {
                            ws.send(ByteBuffer.wrap(buffer, 0, bytesRead));
                        }
                    } catch (IOException e) {
                        if (!socket.isClosed()) {
                            System.err.println("Socket to WS forward error: " + e.getMessage());
                        }
                        try {
                            ws.close(1011, "Forward error");
                        } catch (IOException ex) {
                            // 忽略关闭错误
                        }
                    }
                }).start();
            } catch (IOException e) {
                System.err.println("WS to Socket init error: " + e.getMessage());
            }
        }

        // 从目标Socket转发数据到WebSocket
        private void forwardFromSocketToWs(Socket socket, WebSocket ws) {
            // 已在forwardFromWsToSocket中通过线程处理
        }
    }
}

                        out.flush();
                    } catch (IOException e) {
                        System.err.println("WS到Socket转发错误: " + e.getMessage());
                        ws.close(1011, "转发失败");
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
