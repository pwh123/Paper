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
    private static String tuicPassword;
    private static Process singboxProcess;

    public static void main(String[] args) {
        try {
            System.out.println("config.yml 加载中...");
            Map<String, Object> config = loadConfig();

            // 从config.yml读取UUID和密码（必填项）
            uuid = trim((String) config.get("uuid"));
            tuicPassword = trim((String) config.get("tuic_password"));
            
            // 校验必填配置
            if (uuid.isEmpty() || !isValidUUID(uuid)) {
                throw new RuntimeException("❌ config.yml中uuid配置无效（格式应为标准UUID）");
            }
            if (tuicPassword.isEmpty()) {
                throw new RuntimeException("❌ config.yml中未配置tuic_password");
            }
            System.out.println("已加载UUID: " + uuid);

            // 读取TUIC端口（必填）
            String tuicPort = trim((String) config.get("tuic_port"));
            if (tuicPort.isEmpty()) {
                throw new RuntimeException("❌ config.yml中未配置tuic_port");
            }

            String sni = (String) config.getOrDefault("sni", "www.bing.com");
            Path baseDir = Paths.get("/tmp/.singbox");
            Files.createDirectories(baseDir);
            Path configJson = baseDir.resolve("config.json");
            Path cert = baseDir.resolve("cert.pem");
            Path key = baseDir.resolve("private.key");
            Path bin = baseDir.resolve("sing-box");

            System.out.println("✅ config.yml 加载成功");

            generateSelfSignedCert(cert, key);
            String version = fetchLatestSingBoxVersion();
            safeDownloadSingBox(version, bin, baseDir);

            // 生成仅含TUIC的配置
            generateSingBoxConfig(configJson, tuicPort, sni, cert, key);

            // 启动sing-box并设置每日重启
            singboxProcess = startSingBox(bin, configJson);
            scheduleDailyRestart(bin, configJson);

            String host = detectPublicIP();
            printTUICLink(host, tuicPort, sni);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { deleteDirectory(baseDir); } catch (IOException ignored) {}
            }));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 生成仅TUIC的配置
    private static void generateSingBoxConfig(Path configFile, String tuicPort,
                                              String sni, Path cert, Path key) throws IOException {

        String tuicInbound = """
          {
            "type": "tuic",
            "listen": "::",
            "listen_port": %s,
            "users": [{"uuid": "%s", "password": "%s"}],
            "congestion_control": "bbr",
            "tls": {
              "enabled": true,
              "alpn": ["h3"],
              "certificate_path": "%s",
              "key_path": "%s"
            }
          }
        """.formatted(tuicPort, uuid, tuicPassword, cert, key);

        String json = """
        {
          "log": { "level": "info" },
          "inbounds": [%s],
          "outbounds": [{"type": "direct"}]
        }
        """.formatted(tuicInbound);

        Files.writeString(configFile, json);
        System.out.println("✅ sing-box 配置生成完成（仅TUIC）");
    }

    // 输出TUIC链接
    private static void printTUICLink(String host, String port, String sni) {
        System.out.println("\n=== ✅ 已部署TUIC节点 ===");
        System.out.printf("TUIC:\ntuic://%s:%s@%s:%s?sni=%s&alpn=h3&congestion_control=bbr&allowInsecure=1#TUIC\n",
                uuid, tuicPassword, host, port, sni);
    }

    // UUID格式校验
    private static boolean isValidUUID(String u) {
        return u != null && u.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    // 工具方法
    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static Map<String, Object> loadConfig() throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(Paths.get("config.yml"))) {
            Object o = yaml.load(in);
            if (o instanceof Map) return (Map<String, Object>) o;
            return new HashMap<>();
        }
    }

    private static void generateSelfSignedCert(Path cert, Path key) throws IOException, InterruptedException {
        if (Files.exists(cert) && Files.exists(key)) {
            System.out.println("🔑 证书已存在，跳过生成");
            return;
        }
        System.out.println("🔨 正在生成 EC 自签证书...");
        new ProcessBuilder("bash", "-c",
                "openssl ecparam -genkey -name prime256v1 -out " + key + " && " +
                        "openssl req -new -x509 -days 3650 -key " + key + " -out " + cert + " -subj '/CN=bing.com'")
                .inheritIO().start().waitFor();
        System.out.println("✅ 已生成自签证书");
    }

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
                    System.out.println("🔍 最新版本: " + v);
                    return v;
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ 获取版本失败，使用回退版本 " + fallback);
        }
        return fallback;
    }

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

    private static String detectArch() {
        String a = System.getProperty("os.arch").toLowerCase();
        if (a.contains("aarch") || a.contains("arm")) return "arm64";
        return "amd64";
    }

    private static Process startSingBox(Path bin, Path cfg) throws IOException, InterruptedException {
        System.out.println("正在启动 sing-box...");
        ProcessBuilder pb = new ProcessBuilder(bin.toString(), "run", "-c", cfg.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        Thread.sleep(1500);
        System.out.println("sing-box 已启动，PID: " + p.pid());
        return p;
    }

    private static String detectPublicIP() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream()))) {
            return br.readLine();
        } catch (Exception e) {
            return "your-server-ip";
        }
    }

    private static void scheduleDailyRestart(Path bin, Path cfg) {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        Runnable restartTask = () -> {
            System.out.println("\n[定时重启Sing-box] 北京时间 00:03，准备重启 sing-box...");

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

        System.out.printf("[定时重启Sing-box] 已计划每日 00:03 重启（首次执行：%s）%n",
                next.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
}
