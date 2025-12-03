package io.papermc.paper;

import java.util.*;
import java.net.*;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

public class PaperBootstrap {

    private static final String DEFAULT_PORT = "8080"; // 非加密建议用80/8080
    private static String uuid;
    private static String port;
    private static String wsPath;
    private static String host;

    public static void main(String[] args) {
        try {
            // 从环境变量或输入获取配置（简化版，无需文件）
            uuid = getConfig("UUID", "请输入UUID（必填）: ");
            port = getConfig("PORT", DEFAULT_PORT, "请输入端口（默认" + DEFAULT_PORT + "）: ");
            wsPath = getConfig("WS_PATH", "/" + uuid.split("-")[0], "请输入WebSocket路径（默认/" + uuid.split("-")[0] + "）: ");
            host = getConfig("HOST", "example.com", "请输入主机名（默认example.com）: ");

            // 校验UUID格式
            if (!isValidUUID(uuid)) {
                throw new RuntimeException("UUID格式错误（应为8-4-4-4-12位）");
            }

            // 输出节点信息
            System.out.println("\n=== VLESS-WS 节点配置（无TLS） ===");
            printVlessLink(host, port, wsPath);

            // 模拟启动提示（无需下载程序）
            System.out.println("\n[提示] 无需证书和额外程序，可直接使用上述链接配置客户端");
            System.out.println("WebSocket配置要点：");
            System.out.println("- 传输协议：ws（非加密）");
            System.out.println("- 路径：" + wsPath);
            System.out.println("- 主机头：" + host);

        } catch (Exception e) {
            System.err.println("错误：" + e.getMessage());
        }
    }

    // 获取配置（优先环境变量，无则手动输入）
    private static String getConfig(String envKey, String prompt) {
        String value = System.getenv(envKey);
        if (value == null || value.trim().isEmpty()) {
            System.out.print(prompt);
            try {
                return new Scanner(System.in).nextLine().trim();
            } catch (Exception e) {
                return "";
            }
        }
        return value.trim();
    }

    private static String getConfig(String envKey, String defaultValue, String prompt) {
        String value = System.getenv(envKey);
        if (value == null || value.trim().isEmpty()) {
            System.out.print(prompt);
            try {
                value = new Scanner(System.in).nextLine().trim();
                return value.isEmpty() ? defaultValue : value;
            } catch (Exception e) {
                return defaultValue;
            }
        }
        return value.trim();
    }

    // 输出VLESS链接（无TLS）
    private static void printVlessLink(String host, String port, String wsPath) {
        // 无TLS时security=none，无需证书相关配置
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
