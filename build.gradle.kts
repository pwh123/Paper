plugins {
    id 'java'
    id 'application'
}

// 主类名（不含包）
mainClassName = 'V2RayProxy'

// Java 版本
sourceCompatibility = 1.8
targetCompatibility = 1.8

// 项目信息
group = 'com.example'
version = '1.0.0'

// 编译与打包编码
compileJava.options.encoding = 'UTF-8'
compileTest.options.encoding = 'UTF-8'

// 发布可执行 jar
jar {
    manifest {
        attributes 'Main-Class': mainClassName
    }
    // 把 class 文件打包进 jar
    from {
        configurations.runtimeClasspath.collect { it.isDirectory() ? it : zipTree(it) }
    }
}
