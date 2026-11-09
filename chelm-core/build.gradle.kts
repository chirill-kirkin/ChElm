import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
  alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
  explicitApi()
  jvmToolchain(17)

  @OptIn(ExperimentalAbiValidation::class)
  abiValidation()

  jvm()

  sourceSets {
    commonMain.dependencies {
      api(libs.kotlinx.coroutines.core)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}
