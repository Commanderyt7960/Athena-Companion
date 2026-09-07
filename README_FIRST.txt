ATHENA — V15 CLEAN REBUILD

This package is a fresh Android/Windows rebuild of Athena.

ANDROID
- Local Gemma 3 1B LiteRT model is downloaded by GitHub Actions during the build.
- Microphone permission is handled only from the visible Activity.
- The app never intentionally closes because microphone access is denied.
- Always-on listening is enabled from Tools after permission is granted.
- Windows PC discovery remains automatic over the local Wi-Fi network.

WINDOWS
- Local Gemma 3 1B GGUF + llama.cpp runtime are bundled by GitHub Actions.

BUILD
Push this repository to GitHub and run the GitHub Actions workflow manually. The workflow produces Athena-Android.apk and Athena.exe as artifacts.
