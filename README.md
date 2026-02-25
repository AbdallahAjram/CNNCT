# 🌐 CNNCT: Mobile-first, AI-integrated, real-time communication

[![Production Access](https://img.shields.io/badge/Google%20Play-Production%20Access-brightgreen?logo=google-play)](https://play.google.com/store/apps/details?id=com.abdallah.cnnct)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple?logo=kotlin)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-2025.06-blue?logo=android)](https://developer.android.com/jetpack/compose)

**CNNCT** is a cutting-edge communication platform designed for the modern era. It combines the power of **Generative AI** with ultra-low latency **VoIP** and highly reactive **real-time messaging** to deliver a seamless user experience.

---

## 🏗️ System Architecture

CNNCT is built on a robust, scalable architecture that ensures high availability and performance.

### 📱 Frontend: Android (Kotlin & Jetpack Compose)
The frontend is a purely native Android application following the **MVVM (Model-View-ViewModel)** architectural pattern.
- **UI Architecture**: Built entirely with **Jetpack Compose** for a modern, declarative UI.
- **Dependency Injection**: Powered by **Hilt** to maintain a decoupled and testable codebase.
- **Asynchrony**: Utilizes **Kotlin Coroutines and Flows** for non-blocking operations and reactive state management.

### ☁️ Backend: Firebase Ecosystem
The "brain" of CNNCT resides in the Firebase cloud, providing a serverless and auto-scaling infrastructure.
- **Firestore**: A NoSQL document database that powers real-time data synchronization.
- **Cloud Functions**: Node.js-based serverless functions that handle complex logic and AI integrations.
- **Cloud Storage**: Securely handles media uploads (images, videos, documents).
- **Firebase Messaging (FCM)**: Delivers reliable push notifications for calls and messages.

### 🎙️ VoIP Infrastructure: Agora SDK
Voice communications are handled via the **Agora RTC SDK**, ensuring crystal-clear, low-latency audio even in low-bandwidth environments.

---

## 🚀 Key Production Features

### ✨ AI Smart Replies
CNNCT leverages **Generative AI** (OpenAI/Gemini) to provide context-aware response suggestions.
- **Implementation**: When a message is received, the frontend triggers a secure Firebase Cloud Function (`generateSmartReplies`).
- **Context Awareness**: The AI analyzes the recent conversation history to generate three relevant, natural-sounding suggestions, allowing users to reply instantly with a single tap.

### 💬 Real-Time Messaging
Experience zero-latency communication with our reactive data pipeline.
- **Reactive Flow**: The application uses `StateFlow` and `collectLatest` in the `ChatViewModel` to observe Firestore snapshots.
- **Optimistic UI**: Messages are immediately visible in the UI with a "Sending" status, providing an instant feedback loop for the user.
- **Advanced Features**: Full support for read receipts, "typing" indicators, and message editing/deletion.

### 📞 Low-Latency VoIP
High-fidelity voice calls are a core pillar of CNNCT.
- **Agora Integration**: Seamlessly integrated into the app's lifecycle, managing audio routing (speakerphone/earpiece) and system-level call notifications.
- **Push Service Integration**: Custom `Service` implementation ensures calls are received even when the app is in the background or the device is locked.

---

## 🛡️ Production Readiness

CNNCT is built for release and has undergone rigorous validation:
- ✅ **Google Play Closed Testing**: Successfully cleared the 14-day closed testing period with 20+ testers.
- ⚡ **Optimized Performance**: Fully optimized with **ProGuard/R8** to reduce APK size and obfuscate code for security.
- 📉 **Asset Optimization**: High-efficiency image and video compression during uploads to minimize data usage.
- 🐞 **Stability**: Detailed logging and crash reporting integrated to ensure a 99.9% crash-free rate.

---

## 🛠️ Tech Stack

| Category | Technology |
| :--- | :--- |
| **Language** | Kotlin |
| **UI Framework** | Jetpack Compose |
| **Architecture** | MVVM |
| **Dependency Injection** | Hilt |
| **Networking** | Firebase SDK / OkHttp |
| **Database** | Firebase Firestore |
| **Real-Time Data** | Kotlin Coroutines & Flows |
| **VoIP / Audio** | Agora SDK |
| **AI Integration** | Firebase Cloud Functions / OpenAI |
| **Image Loading** | Coil |

---

## 👨‍💻 Setup for Developers

To build and run CNNCT locally, follow these steps:

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/AbdallahAjram/CNNCT/tree/main/CNNCT_Android.git
   ```

2. **Firebase Setup**:
   - Create a project in the [Firebase Console](https://console.firebase.google.com/).
   - Add an Android app with the package name `com.abdallah.cnnct`.
   - Download the `google-services.json` file and place it in the `app/` directory.

3. **Agora Setup**:
   - Create an account on [Agora.io](https://www.agora.io/).
   - Obtain your `App ID`.
   - Add `agora.appId=YOUR_APP_ID` to your `local.properties` file.

4. **Build the Project**:
   - Open the project in **Android Studio (Hedgehog or newer)**.
   - Sync Project with Gradle Files.
   - Run the application on an emulator or physical device.

---

## 📄 License
*Copyright © 2026 Abdallah Ajram. All rights reserved.*
