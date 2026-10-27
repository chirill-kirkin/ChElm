plugins {
  alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
  explicitApi()
  jvmToolchain(17)

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
