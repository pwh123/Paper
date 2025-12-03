plugins {
    java
    application
}

group = "com.example"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

application {
    mainClass.set("PaperBootstrap") // 替换为你的主类
}

// 编译选项
compileJava {
    options.encoding = "UTF-8"
}

tasks.test {
    systemProperty("file.encoding", "UTF-8")
}
