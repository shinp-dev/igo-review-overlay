import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("com.android.application")
}

android {
    namespace = "com.shinpstudio.igoreview"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.shinpstudio.igoreview"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/libkatago.so"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// All engine assets are tracked locally. Model chunks are reassembled without
// any network access, NDK, CMake, or external source checkout.
val engineFile = file("src/main/jniLibs/arm64-v8a/libkatago.so")
val engineDigest = rootProject.file("third_party/KataGo.arm64.sha256")
val chunkRoot = rootProject.file("third_party/KataGo-models")
val modelDir = file("src/main/assets/katago")
val models = mapOf(
    "model.bin.gz.asset" to "f57fddf4672364d385d6ab177364ab819810d1123e229cb2649c4f337a2160b1",
    "kata9x9.bin.gz.asset" to "a1298ce1adc1dad7bd868ca962b2384cc8388ed373a00e6bae1114fa6f9e2d61"
)

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

val verifyKataGo = tasks.register("verifyKataGo") {
    group = "verification"
    description = "Verify the vendored engine and reconstruct both models from Git"
    inputs.files(engineFile, engineDigest)
    inputs.dir(chunkRoot)
    doLast {
        check(engineFile.isFile && engineDigest.isFile) { "Vendored KataGo is missing" }
        val h = engineFile.inputStream().use { it.readNBytes(64) }
        check(h.size == 64 &&
            h.sliceArray(0..5).contentEquals(byteArrayOf(127, 69, 76, 70, 2, 1)) &&
            h.sliceArray(16..19).contentEquals(byteArrayOf(3, 0, 183.toByte(), 0))) {
            "Vendored KataGo is not an ARM64 PIE executable"
        }
        check(sha256(engineFile) == engineDigest.readText().trim()) { "KataGo binary checksum mismatch" }
        modelDir.mkdirs()
        for ((name, expected) in models) {
            val parts = File(chunkRoot, name).listFiles()?.filter { it.isFile }?.sortedBy { it.name }
                ?: error("Missing model chunks: " + name)
            check(parts.isNotEmpty()) { "Missing model chunks: " + name }
            check(parts.withIndex().all { (index, part) ->
                part.name == "%04d.part".format(index) && part.length() > 0
            }) { "Invalid model chunk sequence: " + name }
            val destination = File(modelDir, name)
            val partial = File(modelDir, name + ".partial")
            try {
                partial.outputStream().buffered().use { out ->
                    for (part in parts) part.inputStream().buffered().use { it.copyTo(out) }
                }
                check(sha256(partial) == expected) { "Vendored model checksum mismatch: " + name }
                Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                partial.delete()
            }
        }
    }
}
tasks.configureEach {
    if (name == "preBuild" ||
        (name.startsWith("merge") && (name.endsWith("Assets") ||
            name.endsWith("JniLibFolders") || name.endsWith("NativeLibs")))) {
        dependsOn(verifyKataGo)
    }
}
