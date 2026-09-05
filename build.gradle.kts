plugins {
    java
    `jvm-test-suite`
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.10.2"
}

group = "com.careercompass"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // API 문서 (dev/local 프로파일에서만 노출 — issue #8)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")

    // JWT (M1 인증)
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.security:spring-security-test")
            }
        }

        // 실제 PostgreSQL(Testcontainers)을 쓰는 통합 테스트 레인 — issue #9
        val integrationTest by registering(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.boot:spring-boot-testcontainers")
                implementation("org.springframework:spring-jdbc")
                implementation("org.testcontainers:postgresql")
                implementation("org.testcontainers:junit-jupiter")
                implementation("org.springframework.security:spring-security-test")
                runtimeOnly("org.postgresql:postgresql")
            }
            targets {
                all {
                    testTask.configure {
                        shouldRunAfter(test)
                    }
                }
            }
        }
    }
}

// 실행 jar 만 남긴다 (Dockerfile 의 COPY *.jar 가 유일해야 함)
tasks.jar {
    enabled = false
}

// jar 를 껐으므로 integrationTest 는 메인 클래스·의존성을 소스셋으로 직접 물려받는다
sourceSets {
    named("integrationTest") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

configurations {
    named("integrationTestImplementation") { extendsFrom(configurations.implementation.get()) }
    named("integrationTestRuntimeOnly") { extendsFrom(configurations.runtimeOnly.get()) }
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat()
        formatAnnotations()
    }
}
