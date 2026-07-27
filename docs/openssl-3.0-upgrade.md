# Upgrading the SoftEther module's OpenSSL to 3.0.21

The `SoftEtherClient` native module links against **prebuilt static libraries**
(`libssl.a` / `libcrypto.a`), not something Gradle compiles from source
automatically. Bumping the `openssl` submodule's git tag alone does **not**
change the actual binaries the app links against - you must recompile them
with the Android NDK. This can't be done from this chat environment (no NDK
here), so here's how to do it yourself.

## 1. Update the submodule source

In Git Bash (or WSL), from the project root:

```bash
cd SoftEtherClient/src/main/cpp/openssl
git fetch origin --tags
git checkout openssl-3.0.21
cd ..
rm -rf openssl-build   # clear any old build cache, if present
```

## 2. Find your installed NDK path

In Android Studio: **File → Settings → Languages & Frameworks → Android SDK →
SDK Tools tab** → make sure "NDK (Side by side)" is checked/installed, then
note the version number shown there. The NDK itself lives under your SDK
folder, typically:

- Windows: `C:\Users\<you>\AppData\Local\Android\Sdk\ndk\<version>`
- macOS: `~/Library/Android/sdk/ndk/<version>`
- Linux: `~/Android/Sdk/ndk/<version>`

You can also check `local.properties` in the project root for `sdk.dir`, then
look inside `<sdk.dir>/ndk/`.

No specific NDK version is pinned in this project's `build.gradle` (only
CMake `3.22.1` is), so whichever NDK version you have installed should work.

## 3. Run the build script

Still in Git Bash, from `SoftEtherClient/src/main/cpp/`:

```bash
export ANDROID_NDK_ROOT="C:/Users/<you>/AppData/Local/Android/Sdk/ndk/<version>"
./build-openssl-android.sh
```

This cross-compiles OpenSSL for all four ABIs (`armeabi-v7a`, `arm64-v8a`,
`x86`, `x86_64`) - expect it to take a few minutes. When it finishes, the new
`libssl.a` / `libcrypto.a` files should be in place under `jniLibs/<ABI>/`,
replacing the old 1.1.1w-compiled ones.

## 4. Build the app as usual

Sync Gradle and build normally in Android Studio. If CMake/native linking
errors show up, they'll most likely be about a changed OpenSSL 3.x API used
somewhere in the SoftEtherVPN C sources (opaque struct fields, removed ENGINE
APIs, etc.) - send the exact error text and it can be worked through from
there.

## Why 3.0.21 and not something newer

The official SoftEtherVPN project has used OpenSSL 3.0.x since 2022, so it's
proven compatible in practice. OpenSSL 4.0 (released April 2026) removes
more legacy APIs (the whole `ENGINE` API, for instance) and hasn't been
tested against this codebase - jumping straight there carries more risk of a
native build failure than moving to the 3.0 LTS line does.
