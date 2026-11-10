plugins {
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.4"
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.spring") version "1.9.22"
}

group = "com.panenku.backend"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Spring Web - untuk buat REST API controller (endpoint HTTP)
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Spring Data JPA + Hibernate - untuk akses database PostgreSQL
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // PostgreSQL Driver - koneksi ke database PostgreSQL
    implementation("org.postgresql:postgresql:42.6.0")

    // Spring Security - untuk autentikasi & otorisasi (JWT, role guard)
    implementation("org.springframework.boot:spring-boot-starter-security")

    // Validation - untuk validasi input (@NotBlank, @Email, @Size, dll)
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // JWT - untuk buat & validasi JSON Web Token login
    implementation("io.jsonwebtoken:jjwt-api:0.12.3")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.3")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.3")

    // Flyway - untuk migrasi database (versi skema SQL: V1__, V2__, dst)
    implementation("org.flywaydb:flyway-core")

    // Kotlin stdlib
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}

tasks.withClass<JavaCompile> {
    javaCompiler.configure {
        compilerOptions.configure {
            encoding = "UTF-8"
        }
    }
}

tasks.withClass<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjvm-release=17")
        jvmTarget = "17"
    }
}