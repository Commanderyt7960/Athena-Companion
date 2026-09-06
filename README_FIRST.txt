ATHENA — FULL RELEASE / GITHUB READY
====================================

This repository builds the Android APK and Windows EXE with Athena local AI included in both releases.

ANDROID
- Gemma 3 1B IT LiteRT model is bundled into the APK by the GitHub build.
- No Gemini API key is required.
- Voice recognition, wake mode, TTS, local memory, approved device controls and Windows pairing are included.

WINDOWS
- Gemma 3 1B IT GGUF and the llama.cpp CPU runtime are bundled into the one-file EXE by the GitHub build.
- No Gemini, Ollama or API key is required.
- Local memory, deterministic PC controls and Android pairing are included.

BUILD
1. Put the extracted repository contents into your GitHub repository.
2. Keep .github/workflows/build-athena.yml.
3. Open Actions -> Build Athena - FULL RELEASE.
4. Run workflow.
5. Download Athena-Android-APK and Athena-Windows-EXE.

NOTE
The Windows EXE is large because it contains the local AI model and llama.cpp runtime. The Android APK is also large because it contains the on-device model.
