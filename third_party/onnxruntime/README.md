# ONNX Runtime, minimal build

Dock runs its three models (text detection, text recognition, page corners; see
[tools/models](../../tools/models)) with [ONNX Runtime](https://github.com/microsoft/onnxruntime)
1.31.0 (MIT License, © Microsoft Corporation; [LICENSE](LICENSE), third-party notices in
[app/src/main/assets/licenses](../../app/src/main/assets/licenses/onnxruntime-ThirdPartyNotices.txt)).

The published `onnxruntime-android` AAR carries every operator, NNAPI, XNNPACK and WebGPU:
about 34 MB of native code per device (13 MB compressed). Dock builds its own from the
v1.31.0 source (commit `402979ac1bef89dcd55add41eaa7277c407599eb`) with only the operators and
types in [required_operators.config](required_operators.config), and loads its models in ONNX
Runtime's own format: 3.3 MB of native code on arm64 (1.4 MB compressed), and the whole AAR is
3 MB instead of 55 MB. Nothing in ONNX Runtime's code is changed.

`maven/` holds the result as a local Maven repository (`com.dock.thirdparty:onnxruntime-android-minimal`),
wired up in `settings.gradle.kts`. It contains `arm64-v8a` and `x86_64`, like the rest of the app.

## Rebuilding

After changing a model, regenerate `required_operators.config` with `tools/models/to_ort.py`, then:

```bash
git clone --depth 1 --branch v1.31.0 --recurse-submodules https://github.com/microsoft/onnxruntime.git
python build_minimal.py onnxruntime build
```

[build_minimal.py](build_minimal.py) lists the requirements and the exact build flags:
`--minimal_build`, `--include_ops_by_config`, `--enable_reduced_operator_type_support`,
`--disable_ml_ops`, `--enable_lto`, `--no_telemetry`, `MinSizeRel`, Android API 26, NDK 28.2.
Telemetry (Microsoft's 1DS client, on by default since 1.31) is left out entirely, so the library
contains no code that collects or sends anything. C++ exceptions stay on, so a failing model call
becomes a Java exception instead of a crash.
