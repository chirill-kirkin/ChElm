import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension

plugins {
  alias(libs.plugins.android.kotlin.multiplatform.library) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.detekt) apply false
}

subprojects {
  apply(plugin = rootProject.libs.plugins.detekt.get().pluginId)

  configure<DetektExtension> {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
  }

  dependencies {
    "detektPlugins"(rootProject.libs.detekt.rules.ktlint.wrapper)
  }

  // The plain detekt task only aggregates the analysis with type resolution: per compilation in KMP modules,
  // and across main and test sources otherwise.
  tasks.named<Detekt>("detekt") {
    setSource(files())
    dependsOn(
      tasks.matching { task ->
        if (plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
          task is Detekt && task.name != name && !task.name.endsWith("SourceSet")
        } else {
          task.name == "detektMain" || task.name == "detektTest"
        }
      },
    )
  }

  // Detekt runs manually through the detekt task, so the plugin's check dependency is removed.
  tasks.matching { it.name == LifecycleBasePlugin.CHECK_TASK_NAME }.configureEach {
    setDependsOn(dependsOn.filterNot { (it as? TaskProvider<*>)?.name == "detekt" })
  }
}
