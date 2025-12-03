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
    
    // Java WebSocket API规范（编译时依赖）
    implementation("javax.websocket:javax.websocket-api:1.1")
    
    // Jetty服务器核心
    implementation("org.eclipse.jetty:jetty-server:9.4.44.v20210927")
    
    // Jetty Servlet支持（WebSocket依赖）
    implementation("org.eclipse.jetty:jetty-servlet:9.4.44.v20210927")
    
    // Jetty WebSocket服务器实现
    implementation("org.eclipse.jetty.websocket:websocket-server:9.4.44.v20210927")
    
    // Jetty WebSocket Servlet集成
    implementation("org.eclipse.jetty.websocket:websocket-servlet:9.4.44.v20210927")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
    // 确保主类写入Manifest
    jar {
        manifest {
            attributes["Main-Class"] = "io.papermc.paper.PaperBootstrap"
        }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    // 创建fatJar（包含所有依赖，可独立运行）
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
