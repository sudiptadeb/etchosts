plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.etchosts"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.etchosts"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        // CI appends +g<sha7> on main pushes (rolling "latest" channel), nothing on tags.
        versionName = "0.2.1" + ((project.findProperty("amaritVersionSuffix") as String?) ?: "")
    }

    // Release builds are signed with the stable key when the ETCHOSTS_* variables
    // are set (CI, from repository secrets); otherwise with the debug key, so a
    // local `assembleRelease` still works. A stable certificate is what lets an
    // installed app upgrade in place instead of demanding an uninstall.
    val stableKeystore = System.getenv("ETCHOSTS_KEYSTORE")
    signingConfigs {
        create("stable") {
            if (stableKeystore != null) {
                storeFile = file(stableKeystore)
                storePassword = System.getenv("ETCHOSTS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ETCHOSTS_KEY_ALIAS")
                keyPassword = System.getenv("ETCHOSTS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (stableKeystore != null) signingConfigs.getByName("stable")
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

// amarit.json is the repository file, carried into the APK so the app hardcodes nothing.
val amaritProjectFile = tasks.register<Copy>("amaritProjectFile") {
    from(rootProject.file("amarit.json"))
    into(layout.buildDirectory.dir("generated/amarit/assets"))
}
tasks.named("preBuild") { dependsOn(amaritProjectFile) }

dependencies {
    implementation("com.github.sudiptadeb:amarit:5ad17cb") // update prompts from releases.json
    testImplementation("junit:junit:4.13.2")
}
