package io.papermc.paper;

import org.yaml.snakeyaml.Yaml;
import java.util.*;
import java.net.*;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

public class PaperBootstrap {

    private static final String DEFAULT_PORT = "8080";
    private static final String DEFAULT_HOST = "example.com";
    private static String uuid;
    private static String port;
    private static String wsPath;
    private static String host;

    public static void main(String[] args) {
        try {
            // 1. 优先从config.yml读取配置
            loadConfigFromYml();

            // 2. 若config.yml未配置，从环境变量获取
            loadConfigFromEnv();

            // 3. 若仍未配置，从控制台输入获取
            loadConfigFromConsole();

            // 4. 校验UUID格式
            if (!isValidUUID(uuid)) {
                throw new RuntimeException("UUID格式错误（应为8-4-4-4-12位，如：xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx）");
            }

            // 5. 输出节点信息
            System.out.println("\n=== VLESS-WS 节点配置（无TLS） ===");
            printVlessLink(host, port, wsPath);

            // 6. 输出配置要点
            System.out.println("\n[提示] 无需证书和额外程序，可直接使用上述链接配置客户端");
            System.out.println("WebSocket配置要点：");
            System.out.println("- 传输协议：ws（非加密）");
            System.out.println("- 路径：" + wsPath);
            System.out.println("- 主机头：" + host);

        } catch (Exception e) {
            System.err.println("错误：" + e.getMessage());
        }
    }

    // 从config.yml读取配置
    private static void loadConfigFromYml() {
        Yaml yaml = new Yaml();
        try (InputStream in = new FileInputStream("config.yml")) {
            Map<String, String> config = yaml.load(in);
            if (config != null) {
                uuid = config.get("uuid");
                port = config.get("port");
                wsPath = config.get("wsPath");
                host = config.get("host");
                System.out.println("已从config.yml加载配置");
            }
        } catch (FileNotFoundException e) {
            System.out.println("未找到config.yml，将尝试其他方式获取配置");
        } catch (Exception e) {
            System.err.println("读取config.yml失败：" + e.getMessage());
        }
    }

    // 从环境变量读取配置（补充config.yml未配置的项）
    private static void loadConfigFromEnv() {
        if (uuid == null || uuid.trim().isEmpty()) {
            uuid = System.getenv("UUID");
        }
        if (port == null || port.trim().isEmpty()) {
            port = System.getenv("PORT");
        }
        if (wsPath == null || wsPath.trim().isEmpty()) {
            wsPath = System.getenv("WS_PATH");
        }
        if (host == null || host.trim().isEmpty()) {
            host = System.getenv("HOST");
        }
    }

    // 从控制台输入获取配置（补充未配置的项）
    private static void loadConfigFromConsole() {
        Scanner scanner = new Scanner(System.in);

        // 获取UUID（必填）
        while (uuid == null || uuid.trim().isEmpty()) {
            System.out.print("请输入UUID（必填）: ");
            uuid = scanner.nextLine().trim();
        }

        // 获取端口（默认8080）
        if (port == null || port.trim().isEmpty()) {
            System.out.print("请输入端口（默认" + DEFAULT_PORT + "）: ");
            port = scanner.nextLine().trim();
            if (port.isEmpty()) {
                port = DEFAULT_PORT;
            }
        }

        // 获取WebSocket路径（默认UUID前8位）
        if (wsPath == null || wsPath.trim().isEmpty()) {
            String defaultWsPath = "/" + uuid.split("-")[0];
            System.out.print("请输入WebSocket路径（默认" + defaultWsPath + "）: ");
            wsPath = scanner.nextLine().trim();
            if (wsPath.isEmpty()) {
                wsPath = defaultWsPath;
            }
        }

        // 获取主机名（默认example.com）
        if (host == null || host.trim().isEmpty()) {
            System.out.print("请输入主机名（默认" + DEFAULT_HOST + "）: ");
            host = scanner.nextLine().trim();
            if (host.isEmpty()) {
                host = DEFAULT_HOST;
            }
        }

        scanner.close();
    }

    // 输出VLESS链接（无TLS）
    private static void printVlessLink(String host, String port, String wsPath) {
        String vlessLink = String.format(
            "vless://%s@%s:%s?encryption=none&security=none&type=ws&host=%s&path=%s#VLESS-WS(无加密)",
            uuid, host, port, host, wsPath
        );
        System.out.println("节点链接：");
        System.out.println(vlessLink);
    }

    // UUID格式校验
    private static boolean isValidUUID(String u) {
        return u.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }
}
