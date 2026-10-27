import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kotlin.multiplatform.library)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  explicitApi()
  jvmToolchain(17)

  @OptIn(ExperimentalAbiValidation::class)
  abiValidation()

  jvm()

  android {
    namespace = "io.github.chirillkirkin.mvu.savedstate"
    compileSdk = 37
    minSdk = 23
  }

  sourceSets {
    commonMain.dependencies {
      api(project(":mvu-core"))
      api(libs.androidx.lifecycle.viewmodel.savedstate)
      api(libs.kotlinx.serialization.core)
    }
    jvmTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}
