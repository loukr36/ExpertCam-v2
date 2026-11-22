# ExpertCam (Galaxy S22+ — Mode Caméra + Diagnostic)

Application caméra Android (Kotlin, Jetpack Compose, **API Camera2**) conçue pour contourner la panne matérielle du capteur ultra grand-angle (`0.6x` / `UW`) sur **Samsung Galaxy S22+**, sans jamais utiliser `setPhysicalCameraId` (erreur `-38`).

## Fonctionnalités
- **Mode Caméra principale simplifié** : Photo JPEG, Vidéo MP4 (`1080p` / `4K`), `1x`, `3x`, `Selfie`, Flash (`Off` / `Auto` / `On`), autofocus continu + tap-to-focus, aucun quadrillage.
- **Stratégie anti-crash en cascade** : scanne `cameraIdList` + IDs cachés `"0"`..`"100"`, bannit le `0.6x` du mode principal, tente les IDs cachés dédiés puis bascule automatiquement sur `"0"` en flux unique minimaliste (OIS/EIS off, zoom verrouillé `>= 1.0x`).
- **Mode Diagnostic (`DIAG`)** : teste chaque capteur individuellement (y compris `0.6x`), `ID 0 [0.6x / 1.0x / 3.0x]`, mode `TOUT TESTER (Auto)`, watchdog écran noir 3s et copie complète du rapport.
- **Caméra Système & Partage rapide** : `IMAGE_CAPTURE` / `VIDEO_CAPTURE` + partage 1-clic vers Snapchat, Instagram, WhatsApp et toutes les applis Android.

## Installer l'APK depuis GitHub Actions
1. Poussez ce dépôt sur GitHub (`main` ou `master`).
2. Ouvrez l'onglet **Actions** -> **Build ExpertCam Debug APK**.
3. Téléchargez l'artifact **`ExpertCam-S22Plus-debug-apk`**, extrayez le ZIP et installez **`app-debug.apk`**.
