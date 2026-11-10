package com.panenku.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "app")
data class AppConfig(
    val jwt: JwtConfig = JwtConfig(),
    val minio: MinioConfig = MinioConfig()
) {

    data class JwtConfig(
        val secret: String = "",
        val expiration: Long = 86400000
    )

    data class MinioConfig(
        val endpoint: String = "localhost:9000",
        val accessKey: String = "",
        val secretKey: String = "",
        val bucket: String = "panenku-files"
    )
}