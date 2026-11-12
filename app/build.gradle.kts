plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Set when a key folder is in play but a key is missing; fails release builds only.
var missingKey: String? = null

android {
    namespace = "app.etchosts"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.etchosts"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        // CI appends +g<sha7> on main pushes (rolling "latest" channel), nothing on tags.
        versionName = "0.2.2" + ((project.findProperty("amaritVersionSuffix") as String?) ?: "")
    }

    // Release builds are signed with the stable key. A stable certificate is what
    // lets an installed app upgrade in place instead of demanding an uninstall.
    // CI passes the key in the ETCHOSTS_* variables (repository secrets). Locally it
    // comes from the key folder, see "Signing key" in the README.
    val stable: Map<String, String>? = run {
        System.getenv("ETCHOSTS_KEYSTORE")?.let { return@run System.getenv() }
        val kosh = System.getenv("KEYKOSH_ROOT")?.takeIf { it.isNotEmpty() }
        val keys = if (kosh != null) File(kosh, "etchosts") else rootProject.file(".keys")
        val jks = File(keys, "etchosts-release.jks")
        val env = File(keys, "secrets.env")
        if (kosh == null && !keys.exists()) return@run null // no keys at all: debug key
        listOf(jks, env).firstOrNull { !it.isFile }?.let {
            missingKey = "missing signing key: $it" + if (kosh != null) " (run keykosh unlock)" else ""
            return@run null
        }
        env.readLines().map { it.trim() }.filter { "=" in it && !it.startsWith("#") }
            .associate { it.substringBefore("=") to it.substringAfter("=") } +
            ("ETCHOSTS_KEYSTORE" to jks.path)
    }
    signingConfigs {
        create("stable") {
            if (stable != null) {
                storeFile = file(stable.getValue("ETCHOSTS_KEYSTORE"))
                storePassword = stable["ETCHOSTS_KEYSTORE_PASSWORD"]
                keyAlias = stable["ETCHOSTS_KEY_ALIAS"]
                keyPassword = stable["ETCHOSTS_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (stable != null) signingConfigs.getByName("stable")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/amarit/assets"))
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst { missingKey?.let { throw GradleException(it) } }
}

// amarit.json is the repository file, carried into the APK so the app hardcodes nothing.
val amaritProjectFile = tasks.register<Copy>("amaritProjectFile") {
    from(rootProject.file("amarit.json"))
    into(layout.buildDirectory.dir("generated/amarit/assets"))
}
tasks.named("preBuild") { dependsOn(amaritProjectFile) }

dependencies {
    implementation("com.github.sudiptadeb:amarit:v0.6.0") // update prompts from releases.json
    testImplementation("junit:junit:4.13.2")
}
