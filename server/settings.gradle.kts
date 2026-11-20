pluginManagement {
    plugins {
        id("org.springframework.boot") version "4.1.2"
        id("io.spring.dependency-management") version "1.1.7"
    }
}

rootProject.name = "tabnit-server"

include("common", "core", "pack", "link", "rules", "api")
