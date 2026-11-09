import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kotlin.multiplatform.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.maven.publish)
}

kotlin {
  explicitApi()
  jvmToolchain(17)

  @OptIn(ExperimentalAbiValidation::class)
  abiValidation()

  jvm()

  android {
    namespace = "io.github.chirillkirkin.chelm.savedstate"
    compileSdk = 37
    minSdk = 23
  }

  sourceSets {
    commonMain.dependencies {
      api(project(":chelm-core"))
      api(libs.androidx.lifecycle.viewmodel.savedstate)
      api(libs.kotlinx.serialization.core)
    }
    jvmTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}
