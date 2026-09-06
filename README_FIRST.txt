ATHENA - FREE ONE-CLICK BUILD PACKAGE
======================================

This package builds BOTH:
  - Athena-Android.apk
  - Athena.exe for Windows

The build is designed for GitHub Actions. GitHub Actions is free for public repositories.

ONE-TIME SETUP
--------------
1. Create a NEW PUBLIC repository on GitHub, e.g. Athena-Assistant.
2. Upload ALL contents of this folder to the repository, including the hidden:
      .github/workflows/build-athena.yml
   IMPORTANT: do not upload the ZIP itself. Upload the extracted files/folders.

3. In the GitHub repository, open:
      Actions -> Build Athena - APK + EXE

4. Click:
      Run workflow -> Run workflow

5. Wait for both jobs to finish.

6. Open the completed workflow run. Under Artifacts you will see:
      Athena-Android-APK
      Athena-Windows-EXE

7. Download the artifact you want.

ANDROID
-------
The Android workflow builds a signed DEBUG APK, so it can be installed on an Android phone without you supplying a keystore.
For a production Play Store release, a permanent release keystore should be added later.

WINDOWS
-------
The Windows workflow builds Athena.exe with PyInstaller on a Windows runner.

NOTES
-----
- You do NOT need Android Studio on your PC.
- You do NOT need Python installed on your PC for the cloud build.
- The project does not need a Gradle wrapper because the workflow installs Gradle 8.11.1 directly.
- Keep the repository PUBLIC if you want to use GitHub Actions without paid private-repository minutes.
