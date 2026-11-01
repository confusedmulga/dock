"""
Builds Dock's minimal ONNX Runtime AAR: only the operators Dock's models use
(required_operators.config, from tools/models/to_ort.py), ORT format models only, no telemetry,
no NNAPI / XNNPACK / WebGPU. Follows onnxruntime's
tools/ci_build/github/android/build_aar_package.py, minus Microsoft's internal vcpkg cache and
the symlinks (copies instead, so it also runs on Windows).

    git clone --depth 1 --branch v1.31.0 --recurse-submodules https://github.com/microsoft/onnxruntime.git
    python build_minimal.py onnxruntime <build dir>

Needs Python 3.10+, CMake 3.28+ and Ninja on PATH, JDK 17, the Android SDK and NDK 28.2
(ANDROID_HOME, ANDROID_NDK_HOME and JAVA_HOME, or the defaults below). Takes about half an hour
per ABI. The AAR is then copied into maven/ next to this script, where Gradle picks it up.
"""
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
VERSION = "1.31.0"
ABIS = ["arm64-v8a", "x86_64"]
CONFIG = "MinSizeRel"


def main(src, build_dir):
    src, build_dir = os.path.abspath(src), os.path.abspath(build_dir)
    env = os.environ.copy()
    sdk = env.setdefault("ANDROID_HOME", os.path.expandvars(os.path.join("%LOCALAPPDATA%", "Android", "Sdk")))
    ndk = env.setdefault("ANDROID_NDK_HOME", os.path.join(sdk, "ndk", "28.2.13676358"))
    if "JAVA_HOME" in env:
        env["PATH"] = os.path.join(env["JAVA_HOME"], "bin") + os.pathsep + env["PATH"]

    params = [
        "--android", f"--android_sdk_path={sdk}", f"--android_ndk_path={ndk}", "--android_api=26",
        "--cmake_generator=Ninja", "--parallel", "--build_shared_lib", "--build_java", "--skip_tests",
        f"--config={CONFIG}",
        # Only what the models need: ORT format models, their operators and types, no classic ML ops.
        "--minimal_build", f"--include_ops_by_config={os.path.join(HERE, 'required_operators.config')}",
        "--enable_reduced_operator_type_support", "--disable_ml_ops", "--enable_lto",
        # Telemetry (Microsoft's 1DS client) is on by default since 1.31; Dock sends nothing anywhere.
        "--no_telemetry",
    ]
    jnilibs = os.path.join(build_dir, "intermediates", "jnilibs", CONFIG)
    headers = ""
    for abi in ABIS:
        abi_dir = os.path.join(build_dir, "intermediates", abi)
        subprocess.run([sys.executable, os.path.join(src, "tools", "ci_build", "build.py"), *params,
                        f"--android_abi={abi}", f"--build_dir={abi_dir}"], env=env, check=True, cwd=src)
        os.makedirs(os.path.join(jnilibs, abi), exist_ok=True)
        for lib in ["libonnxruntime.so", "libonnxruntime4j_jni.so"]:
            shutil.copyfile(os.path.join(abi_dir, CONFIG, lib), os.path.join(jnilibs, abi, lib))
        headers = headers or os.path.join(abi_dir, CONFIG, "android", "headers")

    java = os.path.join(src, "java")
    publish = os.path.join(build_dir, "aar_out", CONFIG)
    gradle = [os.path.join(java, "gradlew.bat" if os.name == "nt" else "gradlew"), "--no-daemon",
              "-b=build-android.gradle", "-c=settings-android.gradle",
              f"-DjniLibsDir={jnilibs}", f"-DbuildDir={os.path.join(build_dir, 'intermediates', 'aar', CONFIG)}",
              f"-DheadersDir={headers}", f"-DpublishDir={publish}", "-DminSdkVer=26", "-DtargetSdkVer=34",
              "-DENABLE_TRAINING_APIS=0", "-DreleaseVersionSuffix="]
    for task in ["clean", "build", "publish"]:
        subprocess.run([*gradle, task], env=env, check=True, cwd=java)

    aar = os.path.join(publish, "com", "microsoft", "onnxruntime", "onnxruntime-android", VERSION,
                       f"onnxruntime-android-{VERSION}.aar")
    target = os.path.join(HERE, "maven", "com", "dock", "thirdparty", "onnxruntime-android-minimal", VERSION)
    os.makedirs(target, exist_ok=True)
    shutil.copyfile(aar, os.path.join(target, f"onnxruntime-android-minimal-{VERSION}.aar"))
    print("Copied", aar, "to", target)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
