# BiOracle-66-Master.
# BioOracle v14 – Pure Kotlin Android rPPG App

**Real‑time heart rate & HRV from your smartphone camera – no wearables, no cloud.**

This repository contains a complete, working Android app that uses the front camera to perform remote photoplethysmography (rPPG) and display:

- **Heart Rate (HR)** – BPM
- **Heart Rate Variability (RMSSD)** – ms
- **Vascular Stiffness Index** – derived from HR
- **Circadian Meridian** – automatic mapping to traditional organ clock windows (liver, lung, stomach, etc.)

All processing runs **on‑device** (no data leaves your phone). The app requests camera permission once and starts scanning with a single button tap.

---

## How to Build

### Using GitHub Actions (recommended)
1. Push this repository to GitHub.
2. Go to the **Actions** tab – a workflow will automatically build the APK.
3. When finished, download the APK from the **Artifacts** section.

### Locally (Android Studio)
1. Clone the repo.
2. Open the project in Android Studio.
3. Connect your device (or start an emulator with a camera).
4. Click **Run** (green triangle).

---

## How to Use

1. Install the APK on your Android phone (min SDK 24 / Android 7+).
2. Open the app – you will see a reticle and a button.
3. Tap **"INITIATE FULL SPECTRUM ANALYSIS"**.
4. Grant **camera permission** when prompted.
5. Look at the front camera (hold the phone steady, face well‑lit).
6. Within 5‑10 seconds, HR, HRV, and the active meridian will update on screen.

---

## Technical Details

- **Camera API:** Android Camera2 – front camera at 640x480, 30‑60 fps
- **rPPG algorithm:** CHROM (de Haan & Jeanne, 2010) – pure Kotlin implementation
- **Filtering:** 6th order Butterworth bandpass (0.75 – 3.0 Hz)
- **HRV metric:** RMSSD (time‑domain)
- **Circadian mapping:** 12 meridian windows (traditional Chinese medicine hours)

---

## Repository Structure


