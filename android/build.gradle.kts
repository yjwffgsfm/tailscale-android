import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.ktfmt)
}

repositories {
  google()
  mavenCentral()
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

android {
  ndkVersion = "23.1.7779620"
  compileSdk = providers.gradleProperty("androidCompileSdk").get().toInt()

  defaultConfig {
    minSdk = 26
    targetSdk = providers.gradleProperty("androidApiLevel").get().toInt()

    versionCode = computeVersionCode()
    versionName = getVersionProperty("VERSION_LONG")
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    buildConfigField(
        "boolean",
        "USE_GOOGLE_DNS_FALLBACK",
        getLocalProperty("tailscale.useGoogleDnsFallback", "true"),
    )
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint { warningsAsErrors = true }

  buildFeatures {
    buildConfig = true
    compose = true
  }

  flavorDimensions += "version"
  namespace = "com.tailscale.ipn"

  // 本地/CI 发布签名：由 AGP 在构建时签名，不在构建后做 zip -d + jarsigner 重签
  // （那样会删掉 META-INF/MANIFEST.MF，重签失败时安装会提示「该安装包未包含任何证书」）。
  // 密钥路径由环境变量 ANDROID_KEYSTORE_PATH 提供；未提供时回落为 debug 签名，
  // 产物可以侧载安装，但无法覆盖安装官方签名版本。
  signingConfigs {
    create("releaseFromEnv") {
      val storePath = System.getenv("ANDROID_KEYSTORE_PATH")
      if (storePath != null && File(storePath).exists()) {
        storeFile = File(storePath)
        storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "tailscale"
        keyPassword =
            System.getenv("ANDROID_KEY_PASSWORD")
                ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
      }
    }
  }

  buildTypes {
    debug { manifestPlaceholders["leanbackRequired"] = isTV() }

    release {
      manifestPlaceholders["leanbackRequired"] = isTV()
      isMinifyEnabled = true
      isShrinkResources = true

      // 没有显式 signingConfig 时，assembleRelease 产出的是「未签名」APK，装不上手机。
      signingConfig =
          if (System.getenv("ANDROID_KEYSTORE_PATH") != null)
              signingConfigs.getByName("releaseFromEnv")
          else signingConfigs.getByName("debug")

      proguardFiles(
          getDefaultProguardFile("proguard-android-optimize.txt"),
          "proguard-rules.pro",
      )
    }
  }
}

dependencies {
  // Android dependencies.
  implementation(libs.androidx.fragment)
  implementation(libs.androidx.core)
  implementation(libs.androidx.coreKtx)
  implementation(libs.androidx.browser)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.work.runtimeKtx)

  // Kotlin dependencies.
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
  runtimeOnly(libs.kotlinx.coroutines.android)
  implementation(libs.kotlin.stdlib)
  implementation(libs.kotlin.reflect)

  // Compose dependencies.
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons)
  implementation(libs.androidx.compose.ui)
  debugImplementation(libs.androidx.compose.ui.tooling)
  implementation(libs.androidx.compose.ui.toolingPreview)
  implementation(libs.androidx.lifecycle.viewmodel.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.androidx.compose.animation)

  // Navigation dependencies.
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.navigation.ui)

  // Supporting libraries.
  implementation(libs.coil.compose)
  implementation(libs.coil.network.okhttp)
  implementation(libs.zxing.core)
  implementation(libs.vico.compose)
  implementation(libs.vico.composeM3)

  // Tailscale dependencies.
  implementation(files("libs/libtailscale.aar"))

  // Integration tests.
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.ext.junitKtx)
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.junit)

  // Unit tests.
  testImplementation(libs.junit)
  testImplementation(libs.mockito.core)
  testImplementation(libs.mockito.kotlin)
  testImplementation(libs.kotlinx.coroutines.test)
}

fun getLocalProperty(key: String, defaultValue: String): String {
  return try {
    val properties = Properties()
    project.file("local.properties").inputStream().use(properties::load)
    properties.getProperty(key) ?: defaultValue
  } catch (_: Throwable) {
    defaultValue
  }
}

fun getVersionProperty(key: String): String {
  val versionProperties = Properties()
  project.file("../tailscale.version").inputStream().use(versionProperties::load)

  return versionProperties.getProperty(key).replace(Regex("^\"|\"$"), "")
}

fun computeVersionCode(): Int {
  val baseProperty = project.findProperty("VERSION_CODE_BASE")

  val base = baseProperty?.toString()?.toLong() ?: ((System.currentTimeMillis() / 60_000L) * 10L)

  val platform = if (isTV()) 1 else 0
  return (base + platform).toInt()
}

fun isTV(): Boolean {
  return project.findProperty("PLATFORM")?.toString() == "tv"
}
