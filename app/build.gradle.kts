import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("com.android.application")
}

android {
    namespace = "com.shinpstudio.igoreview"
    compileSdk = 36
    ndkVersion = "27.3.13750724"
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

// The native engine and official networks are generated locally, never checked
// into Git. Every APK build prepares and validates them, including on Windows.
val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val executableSuffix = if (windows) ".exe" else ""
val ndkRelease = "27.3.13750724"
val cmakeRelease = "3.22.1"
val sdkDirectoryProvider = androidComponents.sdkComponents.sdkDirectory
val nativeCache = rootProject.file(".native-build")
val engineFile = file("src/main/jniLibs/arm64-v8a/libkatago.so")
val modelDir = file("src/main/assets/katago")

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

fun downloadModel(destination: File, url: String, expected: String) {
    if (destination.isFile && sha256(destination) == expected) return
    destination.parentFile.mkdirs()
    val partial = File(destination.parentFile, destination.name + ".partial")
    try {
        logger.lifecycle("Downloading KataGo network: " + destination.name)
        val connection = URI.create(url).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "IgoReviewOverlay-build")
        }
        connection.getInputStream().buffered().use { input ->
            partial.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        check(sha256(partial) == expected) { "KataGo network checksum mismatch: " + destination.name }
        Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
        partial.delete()
    }
}

fun validArm64Elf(file: File): Boolean {
    if (!file.isFile || file.length() < 64) return false
    val header = file.inputStream().use { it.readNBytes(64) }
    return header.size == 64 &&
        header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
        header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
        header[4] == 2.toByte() && header[5] == 1.toByte() &&
        header[18] == 183.toByte() && header[19] == 0.toByte()
}

fun runCommand(args: List<String>, directory: File) {
    logger.lifecycle("KataGo: " + args.joinToString(" "))
    val result = ProcessBuilder(args).directory(directory).inheritIO().start().waitFor()
    check(result == 0) { "KataGo preparation failed (exit " + result + "): " + args.first() }
}

fun gitHead(directory: File): String {
    val process = ProcessBuilder("git", "rev-parse", "HEAD")
        .directory(directory).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
    check(process.waitFor() == 0) { "Cannot read Git revision: " + output }
    return output
}

fun checkoutPinned(directory: File, url: String, tag: String, revision: String) {
    if (!File(directory, ".git").isDirectory) {
        directory.parentFile.mkdirs()
        // Clone to a temporary directory so interrupted downloads can be retried.
        val staging = File(directory.parentFile, directory.name + ".partial")
        if (staging.exists()) check(staging.deleteRecursively())
        runCommand(listOf("git", "clone", "--depth", "1", "--branch", tag, url, staging.absolutePath), directory.parentFile)
        check(gitHead(staging) == revision) { "Unexpected source revision: " + url }
        Files.move(staging.toPath(), directory.toPath())
    }
    check(gitHead(directory) == revision) { "Unexpected cached source revision: " + directory }
}

fun ensureNativeTools(sdk: File): Pair<File, File> {
    val ndk = File(sdk, "ndk/" + ndkRelease)
    val cmake = File(sdk, "cmake/" + cmakeRelease + "/bin")
    val required = mutableListOf<String>()
    if (!File(ndk, "build/cmake/android.toolchain.cmake").isFile) required += "ndk;" + ndkRelease
    if (!File(cmake, "cmake" + executableSuffix).isFile ||
        !File(cmake, "ninja" + executableSuffix).isFile) required += "cmake;" + cmakeRelease
    if (required.isNotEmpty()) {
        val managerName = if (windows) "sdkmanager.bat" else "sdkmanager"
        val managers = listOf(File(sdk, "cmdline-tools/latest/bin")) +
            (File(sdk, "cmdline-tools").listFiles()?.sortedByDescending { it.name }
                ?.map { File(it, "bin") } ?: emptyList()) + File(sdk, "tools/bin")
        val bin = managers.firstOrNull { File(it, managerName).isFile }
            ?: error("Install Android SDK Command-line Tools in Android Studio SDK Manager.")
        val command = if (windows) listOf("cmd.exe", "/d", "/c", managerName)
            else listOf(File(bin, managerName).absolutePath)
        runCommand(command + required, bin)
    }
    check(File(ndk, "build/cmake/android.toolchain.cmake").isFile &&
        File(cmake, "cmake" + executableSuffix).isFile &&
        File(cmake, "ninja" + executableSuffix).isFile) {
        "Android NDK/CMake not installed. Accept Android SDK licenses in SDK Manager and retry."
    }
    return ndk to cmake
}

val prepareKataGo = tasks.register("prepareKataGo") {
    group = "build"
    description = "Prepare and verify the offline KataGo engine and both models"
    notCompatibleWithConfigurationCache("Uses local SDK tools and verifies downloaded networks")
    // Intentionally verify on every invocation; successful downloads/builds are reused.
    doLast {
        downloadModel(File(modelDir, "model.bin.gz.asset"),
            "https://katagoarchive.org/g170/neuralnets/g170-b6c96-s175395328-d26788732.bin.gz",
            "f57fddf4672364d385d6ab177364ab819810d1123e229cb2649c4f337a2160b1")
        downloadModel(File(modelDir, "kata9x9.bin.gz.asset"),
            "https://github.com/lightvector/KataGo/releases/download/v1.13.2-kata9x9/kata9x9-b18c384nbt-20231025.bin.gz",
            "a1298ce1adc1dad7bd868ca962b2384cc8388ed373a00e6bae1114fa6f9e2d61")
        if (!validArm64Elf(engineFile)) {
            val (ndk, cmakeBin) = ensureNativeTools(sdkDirectoryProvider.get().asFile)
            val source = File(nativeCache, "KataGo")
            val eigen = File(nativeCache, "eigen-3.4.0")
            // Separate from the legacy shell build's CMake cache.
            val build = File(nativeCache, "gradle-android-arm64")
            checkoutPinned(source, "https://github.com/lightvector/KataGo.git", "v1.17.2",
                "6a1fc5de9fc253723ac475a0683bf0b9d9b7bd19")
            checkoutPinned(eigen, "https://gitlab.com/libeigen/eigen.git", "3.4.0",
                "3147391d946bb4b6c68edd901f2add6ac1f31f8c")
            val cmake = File(cmakeBin, "cmake" + executableSuffix).absolutePath
            runCommand(listOf(cmake, "-S", File(source, "cpp").absolutePath,
                "-B", build.absolutePath, "-G", "Ninja",
                "-DCMAKE_MAKE_PROGRAM=" + File(cmakeBin, "ninja" + executableSuffix).absolutePath,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_TOOLCHAIN_FILE=" + File(ndk, "build/cmake/android.toolchain.cmake").absolutePath,
                "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-34",
                "-DANDROID_STL=c++_static",
                "-DCMAKE_CXX_FLAGS=-DBYTE_ORDER=1234 -DLITTLE_ENDIAN=1234 -DBIG_ENDIAN=4321",
                "-DUSE_BACKEND=EIGEN", "-DEIGEN3_INCLUDE_DIRS=" + eigen.absolutePath,
                "-DNO_GIT_REVISION=1", "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384"),
                nativeCache)
            runCommand(listOf(cmake, "--build", build.absolutePath, "--target", "katago",
                "--parallel", "2"), nativeCache)
            val compiled = File(build, "katago")
            check(validArm64Elf(compiled)) { "KataGo build did not produce an ARM64 Android executable." }
            engineFile.parentFile.mkdirs()
            val partial = File(engineFile.parentFile, engineFile.name + ".partial")
            compiled.copyTo(partial, overwrite = true)
            Files.move(partial.toPath(), engineFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        check(validArm64Elf(engineFile)) { "Missing or invalid KataGo ARM64 executable" }
        logger.lifecycle("Offline KataGo engine and both networks verified.")
    }
}

// Direct dependencies protect the asset/native merge even when invoked alone.
tasks.configureEach {
    if (name == "preBuild" ||
        (name.startsWith("merge") && (name.endsWith("Assets") ||
            name.endsWith("JniLibFolders") || name.endsWith("NativeLibs")))) {
        dependsOn(prepareKataGo)
    }
}
