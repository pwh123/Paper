package io.papermc.paper;

import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.regex.*;

public class PaperBootstrap {

    private static String uuid;
    private static Process singboxProcess;

    public static void main(String[] args) {
        try {
            System.out.println("config.yml 加载中...");
            Map<String, Object> config = loadConfig();

            // 从config.yml读取UUID（必填项）
            uuid = trim((String) config.get("uuid"));
            
            // 校验必填配置
            if (uuid.isEmpty() || !isValidUUID(uuid)) {
                throw new RuntimeException("❌ config.yml中uuid配置无效（格式应为标准UUID）");
            }
            System.out.println("已加载UUID: " + uuid);

            // 读取VLESS-WS端口（必填）
            String vlessPort = trim((String) config.get("vless_port"));
            if (vlessPort.isEmpty()) {
                throw new RuntimeException("❌ config.yml中未配置vless_port");
            }

            // 读取WebSocket路径（默认使用UUID前8位）
            String wsPath = trim((String) config.get("ws_path"));
            if (wsPath.isEmpty()) {
                wsPath = "/" + uuid.split("-")[0]; // 默认路径
                System.out.println("未配置ws_path，使用默认值: " + wsPath);
            }

            // 读取主机名（用于生成链接，默认自动检测公网IP）
            String host = trim((String) config.get("host"));
            if (host.isEmpty()) {
                host = detectPublicIP();
                System.out.println("未配置host，自动检测公网IP: " + host);
            }

            Path baseDir = Paths.get("/tmp/.singbox");
            Files.createDirectories(baseDir);
            Path configJson = baseDir.resolve("config.json");
            Path bin = baseDir.resolve("sing-box");

            System.out.println("✅ config.yml 加载成功");

            // 获取并下载最新sing-box
            String version = fetchLatestSingBoxVersion();
            safeDownloadSingBox(version, bin, baseDir);

            // 生成VLESS-WS（无TLS）配置
            generateSingBoxConfig(configJson, vlessPort, wsPath);

            // 启动sing-box并设置每日重启
            singboxProcess = startSingBox(bin, configJson);
            scheduleDailyRestart(bin, configJson);

            // 输出VLESS链接
            printVlessLink(host, vlessPort, wsPath);

            // 注册进程退出钩子，清理临时文件
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { deleteDirectory(baseDir); } catch (IOException ignored) {}
            }));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 生成VLESS-WS（无TLS）配置
    private static void generateSingBoxConfig(Path configFile, String port, String wsPath) throws IOException {
        // VLESS入站配置（无TLS，WebSocket传输）
        String vlessInbound = """
          {
            "type": "vless",
            "listen": "::",
            "listen_port": %s,
            "users": [{"uuid": "%s", "flow": ""}],
            "network": "ws",
            "ws": {
              "path": "%s",
              "headers": {
                "Host": "example.com"  // 可自定义Host头
              }
            },
            "tls": {
              "enabled": false  // 禁用TLS
            }
          }
        """.formatted(port, uuid, wsPath);

        // 完整配置
        String json = """
        {
          "log": { "level": "info" },
          "inbounds": [%s],
          "outbounds": [{"type": "direct"}]
        }
        """.formatted(vlessInbound);

        Files.writeString(configFile, json);
        System.out.println("✅ sing-box 配置生成完成（VLESS-WS 无TLS）");
    }

    // 输出VLESS链接
    private static void printVlessLink(String host, String port, String wsPath) {
        System.out.println("\n=== ✅ 已部署VLESS-WS节点（无TLS） ===");
        // VLESS链接格式：vless://uuid@host:port?encryption=none&security=none&type=ws&path=wsPath#备注
        String link = String.format(
            "vless://%s@%s:%s?encryption=none&security=none&type=ws&path=%s#VLESS-WS(无TLS)",
            uuid, host, port, wsPath
        );
        System.out.println("节点链接：");
        System.out.println(link);
    }

    // UUID格式校验
    private static boolean isValidUUID(String u) {
        return u != null && u.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    // 工具方法：字符串修剪（处理null和空值）
    private static String trim(String s) { return s == null ? "" : s.trim(); }

    // 加载config.yml配置
    private static Map<String, Object> loadConfig() throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(Paths.get("config.yml"))) {
            Object o = yaml.load(in);
            if (o instanceof Map) return (Map<String, Object>) o;
            return new HashMap<>();
        } catch (FileNotFoundException e) {
            throw new RuntimeException("❌ 未找到config.yml文件，请创建并配置");
        }
    }

    // 获取最新sing-box版本
    private static String fetchLatestSingBoxVersion() {
        String fallback = "1.12.12";
        try {
            URL url = new URL("https://api.github.com/repos/SagerNet/sing-box/releases/latest");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String json = br.lines().reduce("", (a, b) -> a + b);
                int i = json.indexOf("\"tag_name\":\"v");
                if (i != -1) {
                    String v = json.substring(i + 13, json.indexOf("\"", i + 13));
                    System.out.println("🔍 最新sing-box版本: " + v);
                    return v;
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ 获取版本失败，使用回退版本 " + fallback);
        }
        return fallback;
    }

    // 下载并解压sing-box
    private static void safeDownloadSingBox(String version, Path bin, Path dir) throws IOException, InterruptedException {
        if (Files.exists(bin)) return;
        String arch = detectArch();
        String file = "sing-box-" + version + "-linux-" + arch + ".tar.gz";
        String url = "https://github.com/SagerNet/sing-box/releases/download/v" + version + "/" + file;

        System.out.println("⬇️ 下载 sing-box: " + url);
        Path tar = dir.resolve(file);
        new ProcessBuilder("bash", "-c", "curl -L -o " + tar + " \"" + url + "\"").inheritIO().start().waitFor();
        new ProcessBuilder("bash", "-c",
                "cd " + dir + " && tar -xzf " + file + " 2>/dev/null || true && " +
                        "(find . -type f -name 'sing-box' -exec mv {} ./sing-box \\; ) && chmod +x sing-box || true")
                .inheritIO().start().waitFor();

        if (!Files.exists(bin)) throw new IOException("未找到 sing-box 可执行文件！");
        System.out.println("✅ 成功解压 sing-box 可执行文件");
    }

    // 检测系统架构（amd64/arm64）
    private static String detectArch() {
        String a = System.getProperty("os.arch").toLowerCase();
        if (a.contains("aarch") || a.contains("arm")) return "arm64";
        return "amd64";
    }

    // 启动sing-box进程
    private static Process startSingBox(Path bin, Path cfg) throws IOException, InterruptedException {
        System.out.println("正在启动 sing-box...");
        ProcessBuilder pb = new ProcessBuilder(bin.toString(), "run", "-c", cfg.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD); // 静默运行（可改为日志文件）
        Process p = pb.start();
        Thread.sleep(1500); // 等待启动
        System.out.println("sing-box 已启动，PID: " + p.pid());
        return p;
    }

    // 检测公网IP
    private static String detectPublicIP() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream()))) {
            return br.readLine();
        } catch (Exception e) {
            return "your-server-ip"; // 失败时返回占位符
        }
    }

    // 定时每日重启sing-box
    private static void scheduleDailyRestart(Path bin, Path cfg) {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        Runnable restartTask = () -> {
            System.out.println("\n[定时重启] 北京时间 00:03，准备重启 sing-box...");

            if (singboxProcess != null && singboxProcess.isAlive()) {
                System.out.println("正在停止旧进程 (PID: " + singboxProcess.pid() + ")...");
                singboxProcess.destroy();
                try {
                    if (!singboxProcess.waitFor(10, TimeUnit.SECONDS)) {
                        System.out.println("进程未响应，强制终止...");
                        singboxProcess.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            try {
                ProcessBuilder pb = new ProcessBuilder(bin.toString(), "run", "-c", cfg.toString());
                pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                singboxProcess = pb.start();
                System.out.println("sing-box 重启成功，新 PID: " + singboxProcess.pid());
            } catch (Exception e) {
                System.err.println("重启失败: " + e.getMessage());
                e.printStackTrace();
            }
        };

        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDateTime next = now.withHour(0).withMinute(3).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);

        long initialDelay = Duration.between(now, next).getSeconds();
        scheduler.scheduleAtFixedRate(restartTask, initialDelay, 86_400, TimeUnit.SECONDS);

        System.out.printf("[定时重启] 已计划每日 00:03 重启（首次执行：%s）%n",
                next.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }

    // 递归删除目录
    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
}
