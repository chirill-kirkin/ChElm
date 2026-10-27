plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  jvmToolchain(17)

  jvm()

  sourceSets {
    commonMain.dependencies {
      implementation(project(":mvu-savedstate"))
    }
  }
}
