plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("maven-publish")
    id("signing")
}

android {
    namespace = "com.flagdash.sdk"
    compileSdk = 35
    defaultConfig { minSdk = 24; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    publishing { singleVariant("release") { withSourcesJar(); withJavadocJar() } }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

afterEvaluate {
    publishing {
        repositories {
            maven {
                name = "centralStaging"
                url = uri(layout.buildDirectory.dir("central-staging"))
            }
        }
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = providers.gradleProperty("GROUP").get()
                artifactId = "flagdash-android"
                version = providers.gradleProperty("VERSION_NAME").get()
                pom {
                    name.set("FlagDash Android SDK")
                    description.set("Official client-side FlagDash SDK for Android")
                    url.set("https://github.com/flagdash/flagdash-android")
                    licenses { license { name.set("MIT"); url.set("https://opensource.org/license/mit") } }
                    developers { developer { id.set("flagdash"); name.set("FlagDash") } }
                    scm { connection.set("scm:git:https://github.com/flagdash/flagdash-android.git"); url.set("https://github.com/flagdash/flagdash-android") }
                }
            }
        }
    }
}

signing {
    val key = providers.environmentVariable("MAVEN_SIGNING_KEY")
    val password = providers.environmentVariable("MAVEN_SIGNING_PASSWORD")
    if (key.isPresent) { useInMemoryPgpKeys(key.get(), password.orNull); sign(publishing.publications) }
}
