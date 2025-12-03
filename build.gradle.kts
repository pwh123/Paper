plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "io.papermc.paper"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    // YAML解析库
    implementation("org.yaml:snakeyaml:2.3")
    
    // Java WebSocket API规范
    implementation("javax.websocket:javax.websocket-api:1.1")
    
    // Jetty服务器核心
    implementation("org.eclipse.jetty:jetty-server:9.4.44.v20210927")
    
    // Jetty Servlet支持
    implementation("org.eclipse.jetty:jetty-servlet:9.4.44.v20210927")
    
    // Jetty WebSocket服务器实现
    implementation("org.eclipse.jetty.websocket:websocket-server:9.4.44.v20210927")
    
    // Jetty WebSocket与Servlet集成
    implementation("org.eclipse.jetty.websocket:websocket-servlet:9.4.44.v20210927")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
    // 生成可执行jar（包含主类信息）
    jar {
        manifest {
            attributes["Main-Class"] = "io.papermc.paper.PaperBootstrap"
        }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    // 生成fatJar（包含所有依赖，可独立运行）
    val fatJar by registering(com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar::class) {
        archiveBaseName.set("server")
        archiveClassifier.set("")
        archiveVersion.set("")
        manifest {
            attributes["Main-Class"] = "io.papermc.paper.PaperBootstrap"
        }
        from(sourceSets.main.get().output)
        configurations = listOf(project.configurations.runtimeClasspath.get())
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    build {
        dependsOn(fatJar)
    }
}
