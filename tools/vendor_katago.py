"""One-time offline-vendor migration, after the pinned Gradle native build."""
from pathlib import Path
import hashlib
import subprocess
import tarfile
import io

ROOT = Path(__file__).resolve().parents[1]
THIRD = ROOT / "third_party"
CACHE = ROOT / ".native-build"
ENGINE = ROOT / "app/src/main/jniLibs/arm64-v8a/libkatago.so"
MODELS = ROOT / "app/src/main/assets/katago"
GRADLE = ROOT / "app/build.gradle.kts"
REVISIONS = [
    ("KataGo", "KataGo", "6a1fc5de9fc253723ac475a0683bf0b9d9b7bd19"),
    ("eigen-3.4.0", "Eigen", "3147391d946bb4b6c68edd901f2add6ac1f31f8c"),
]
EXPECTED = {
    "model.bin.gz.asset": "f57fddf4672364d385d6ab177364ab819810d1123e229cb2649c4f337a2160b1",
    "kata9x9.bin.gz.asset": "a1298ce1adc1dad7bd868ca962b2384cc8388ed373a00e6bae1114fa6f9e2d61",
}
CHUNK = 32 * 1024 * 1024
assert ENGINE.is_file()
h = ENGINE.read_bytes()
assert h[:6] == bytes([127,69,76,70,2,1]) and h[16:20] == bytes([3,0,183,0])
THIRD.mkdir(exist_ok=True)
for src, dst, rev in REVISIONS:
    source = CACHE / src
    assert subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip() == rev
    target = THIRD / dst
    target.mkdir()
    archive = subprocess.check_output(["git", "-C", str(source), "archive", "--format=tar", rev])
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        tar.extractall(target, filter="data")
    tracked = subprocess.check_output(["git", "-C", str(source), "ls-files", "-z"]).split(b"\0")
    for item in tracked:
        if item:
            path = target / item.decode()
            assert path.exists() or path.is_symlink(), f"Missing source: {path}"
    (THIRD / (dst + ".REVISION")).write_text(rev + "\n")
assert (THIRD / "KataGo/cpp/CMakeLists.txt").is_file()
assert (THIRD / "Eigen/Eigen/Core").is_file()
(THIRD / "KataGo.arm64.sha256").write_text(hashlib.sha256(h).hexdigest() + "\n")

for name, expected in EXPECTED.items():
    path = MODELS / name
    assert hashlib.file_digest(path.open("rb"), "sha256").hexdigest() == expected
    dest = THIRD / "KataGo-models" / name
    dest.mkdir(parents=True)
    with path.open("rb") as stream:
        index = 0
        while data := stream.read(CHUNK):
            (dest / f"{index:04d}.part").write_bytes(data)
            index += 1
    assert index > 0
    print(name, path.stat().st_size, index, "chunks")

old = GRADLE.read_text()
marker = "// The native engine and official networks are generated locally, never checked"
assert marker in old
prefix = old.split(marker, 1)[0].replace("import java.net.URI\n", "").replace('    ndkVersion = "27.3.13750724"\n', "")
GRADLE.write_text(prefix + "// All engine assets are tracked locally. Model chunks are reassembled without\n// any network access, NDK, CMake, or external source checkout.\nval engineFile = file(\"src/main/jniLibs/arm64-v8a/libkatago.so\")\nval engineDigest = rootProject.file(\"third_party/KataGo.arm64.sha256\")\nval chunkRoot = rootProject.file(\"third_party/KataGo-models\")\nval modelDir = file(\"src/main/assets/katago\")\nval models = mapOf(\n    \"model.bin.gz.asset\" to \"f57fddf4672364d385d6ab177364ab819810d1123e229cb2649c4f337a2160b1\",\n    \"kata9x9.bin.gz.asset\" to \"a1298ce1adc1dad7bd868ca962b2384cc8388ed373a00e6bae1114fa6f9e2d61\"\n)\n\nfun sha256(file: File): String {\n    val digest = MessageDigest.getInstance(\"SHA-256\")\n    file.inputStream().buffered().use { input ->\n        val buffer = ByteArray(128 * 1024)\n        while (true) {\n            val n = input.read(buffer)\n            if (n < 0) break\n            digest.update(buffer, 0, n)\n        }\n    }\n    return digest.digest().joinToString(\"\") { \"%02x\".format(it.toInt() and 255) }\n}\n\nval verifyKataGo = tasks.register(\"verifyKataGo\") {\n    group = \"verification\"\n    description = \"Verify the vendored engine and reconstruct both models from Git\"\n    inputs.files(engineFile, engineDigest)\n    inputs.dir(chunkRoot)\n    doLast {\n        check(engineFile.isFile && engineDigest.isFile) { \"Vendored KataGo is missing\" }\n        val h = engineFile.inputStream().use { it.readNBytes(64) }\n        check(h.size == 64 &&\n            h.sliceArray(0..5).contentEquals(byteArrayOf(127, 69, 76, 70, 2, 1)) &&\n            h.sliceArray(16..19).contentEquals(byteArrayOf(3, 0, 183.toByte(), 0))) {\n            \"Vendored KataGo is not an ARM64 PIE executable\"\n        }\n        check(sha256(engineFile) == engineDigest.readText().trim()) { \"KataGo binary checksum mismatch\" }\n        modelDir.mkdirs()\n        for ((name, expected) in models) {\n            val parts = File(chunkRoot, name).listFiles()?.filter { it.isFile }?.sortedBy { it.name }\n                ?: error(\"Missing model chunks: \" + name)\n            check(parts.isNotEmpty()) { \"Missing model chunks: \" + name }\n            check(parts.withIndex().all { (index, part) ->\n                part.name == \"%04d.part\".format(index) && part.length() > 0\n            }) { \"Invalid model chunk sequence: \" + name }\n            val destination = File(modelDir, name)\n            val partial = File(modelDir, name + \".partial\")\n            try {\n                partial.outputStream().buffered().use { out ->\n                    for (part in parts) part.inputStream().buffered().use { it.copyTo(out) }\n                }\n                check(sha256(partial) == expected) { \"Vendored model checksum mismatch: \" + name }\n                Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)\n            } finally {\n                partial.delete()\n            }\n        }\n    }\n}\ntasks.configureEach {\n    if (name == \"preBuild\" ||\n        (name.startsWith(\"merge\") && (name.endsWith(\"Assets\") ||\n            name.endsWith(\"JniLibFolders\") || name.endsWith(\"NativeLibs\")))) {\n        dependsOn(verifyKataGo)\n    }\n}\n")
ignore = ROOT / ".gitignore"
ignore.write_text(ignore.read_text().replace("app/src/main/jniLibs/\n", "app/src/main/jniLibs/arm64-v8a/*.partial\n"))
attrs = ROOT / ".gitattributes"
existing = attrs.read_text() if attrs.exists() else ""
attrs.write_text(existing.rstrip() + "\napp/src/main/jniLibs/arm64-v8a/libkatago.so -text\nthird_party/KataGo-models/** -text\nthird_party/KataGo/** -text\nthird_party/Eigen/** -text\n")

# Remove the old external download/build entry points after migration.
for name in ["tools/build_katago_android.sh", "tools/prepare_model.sh", "tools/vendor_katago.py"]:
    (ROOT / name).unlink()

readme = ROOT / "README.md"
r = readme.read_text()
start = r.index("本プロジェクトはKataGo本体とモデルをGit管理せず")
end = r.index("\n\n", start)
r = r[:start] + "KataGo ARM64実行ファイル、対応するKataGo/Eigenソース、標準・9路専用ネットワークはすべてGitに同梱済みです。モデルは32MiB以下の分割ファイルをローカルで結合し、元のSHA-256で検証します。WindowsではGit、JDK 17以上、Android SDKが必要です。通常ビルドにNDK/CMake、外部エンジン・モデル取得、Git LFS、GitHub Actionsは不要です。" + r[end:]
start = r.index("Linux/macOSでは")
end = r.index("\n\n", start)
r = r[:start] + "Linux/macOSでは `./gradlew :app:installDebug`。初回から同梱済みファイルを利用し、欠落・破損時には外部取得にフォールバックせずビルドを中止します。通常のGradle・Android SDK依存関係は別途必要です。GitHub ActionsのAPKは任意です。" + r[end:]
readme.write_text(r)

doc = ROOT / "docs/OFFLINE_ENGINE.md"
doc.write_text(doc.read_text() + """
## Git完全同梱構成

- `app/src/main/jniLibs/arm64-v8a/libkatago.so` : ARM64 PIE実行ファイル
- `third_party/KataGo`, `third_party/Eigen` : 固定リビジョン対応ソースとライセンス
- `third_party/KataGo-models` : 両公式モデルの32MiB以下の分割ファイル（通常Git）
- `third_party/KataGo.arm64.sha256` : バイナリの検証用SHA-256

通常Gradleは同梱ファイルだけを検証し、モデルの元のgzipバイト列を復元してAPKに格納する。ネットワーク取得、NDK/CMakeは不要。モデル取り込み機能は従来通り。欠落・破損時にはビルド失敗とし、取得し直さない。

### 明示的なエンジン再ビルド（通常は不要）

同梱ソースとNDK 27.3.13750724 / CMake 3.22.1 / Ninjaを用いる。
`cmake -S third_party/KataGo/cpp -B .native-build/rebuild -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE=<NDK>/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34 -DANDROID_STL=c++_static "-DCMAKE_CXX_FLAGS=-DBYTE_ORDER=1234 -DLITTLE_ENDIAN=1234 -DBIG_ENDIAN=4321" -DUSE_BACKEND=EIGEN -DEIGEN3_INCLUDE_DIRS=<absolute path>/third_party/Eigen -DNO_GIT_REVISION=1 -DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384`
次に `cmake --build .native-build/rebuild --target katago --parallel 2`。
生成されたkatagoをlibkatago.soに格納し、SHA-256を更新する。通常のビルドやCIからはこの処理を実行しない。
""")
print("KataGo vendoring staged.")
