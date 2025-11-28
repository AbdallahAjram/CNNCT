# CNNCT – Cross-Platform Chat (Mobile & Web)

CNNCT is a real-time chat application for **Android** and **web**, built as a capstone project to explore modern communication systems with a focus on **privacy**, **cross-device sync**, and **AI-assisted messaging**.

The project combines:

- 📱 A native **Android app** (Kotlin + Jetpack Compose)
- 🌐 A **web app** (Django)
- ☁️ A shared backend using **Firebase** (Firestore, Auth, Storage, FCM)
- 🔊 **Agora** for voice calls
- 🤖 **AI-powered reply suggestions**

---

## ✨ Features

### 🔐 Authentication & User Accounts

- Email/password registration and login
- Google Sign-In 
- User profiles:
  - Display name, avatar, phone, about
  - Profile editing and settings pages
- Account privacy options and blocked users list

### 💬 Chat & Messaging

- 1:1 private chats
- Group chats
- Real-time updates via Firestore / WebSockets
- Message states:
  - Sent, delivered, read (where supported)
- Message types:
  - Text
  - Media / attachments (e.g., images, files, locations)
- Message actions:
  - Edit (where supported)
  - Delete / soft delete
  - Hide / archive conversations

> All core messaging behavior is **implemented and validated on Android**.  
> The web client follows the same model and offers real-time chat but may receive more features over time.

### 👥 Groups & User Directory

- Create and manage group chats
- Add or remove members (with metadata)
- Group info screens:
  - Group name, participants, settings
- Basic user search / directory for finding people

### 📞 Voice Calls

- 1:1 voice calls with **Agora**
- Call flow:
  - Outgoing calls
  - Incoming call screen
  - In-call UI
- Call logs / history
- Push-based incoming call notifications (FCM → call screen)
  

### 🤖 AI Reply Suggestions

- AI-assisted reply suggestions using OpenAI
- Context selection inside a chat to generate smart replies
- Dialog UI for previewing and inserting suggestions
- Designed to avoid unnecessary long-term storage of chat content

### 🔔 Notifications & Mute Controls

- Mobile push notifications for:
  - New messages
  - Incoming calls
- Per-chat mute / notification settings
- Notification settings screen in the Android app

### 🔒 Security & Privacy

- All network communication via **HTTPS (TLS)**
- Firebase-managed encryption at rest
- Firebase Security Rules to restrict access based on authenticated `uid`
- Minimal exposure of user data; only required fields are stored
- Server-side paths for admin operations (Django + Firebase Admin)

---

## 🧱 Tech Stack

### Mobile (Android)

- **Language:** Kotlin
- **UI:** Jetpack Compose, Material 3
- **Architecture:** MVVM-style with controllers/repositories
- **Realtime:** Firebase Firestore listeners
- **Auth:** Firebase Authentication
- **Storage:** Firebase Cloud Storage
- **Push & Calls:**
  - Firebase Cloud Messaging (FCM)
  - Agora Voice SDK
- **AI:** OpenAI API (through a repository/helper layer)

### Web

- **Backend:** Django
- **Templates / UI:** Django templates (with partials for chat, profile, etc.)
- **Realtime chat:** Django Channels/WebSockets (via consumers in `a_rtchat`)
- **Auth & data sync:** Firebase Admin SDK + Firestore
- **Static assets:** Custom CSS and images served via Django static files


### Shared / Backend

- **Database:** Firebase Firestore
- **File storage:** Firebase Cloud Storage
- **Authentication:** Firebase Authentication (client-side + Admin)
- **Notifications:** Firebase Cloud Messaging
- **AI Services:** OpenAI API

---

## 📁 Project Structure (High-Level Overview)

This tree shows only the **main modules**, not every single file.

```text
.
├── a_core/           # Django project core (settings, URLs, ASGI/WSGI, Firebase admin)
├── a_home/           # Web app home views and templates
├── a_rtchat/         # Web chat app (models, consumers, routing, templates)
├── a_users/          # User profiles, auth helpers, Firebase sync, management commands
│
├── app/              # Android app module
│   ├── src/main/java/com/example/cnnct/
│   │   ├── auth/         # Login, signup, complete profile flows
│   │   ├── chat/         # Chat MVC: controllers, repositories, views (screens)
│   │   ├── calls/        # Call logic, Agora integration, call UIs
│   │   ├── groups/       # Group list and group details screens
│   │   ├── homepage/     # Home screen, chat list, preloaded chats
│   │   ├── notifications # FCM handling, mute and notification settings
│   │   ├── settings/     # Account & privacy settings, app configuration
│   │   └── common/       # Shared UI components (avatars, widgets, utilities)
│   └── src/main/res/     # Android resources (layouts, drawables, themes, strings)
│
├── static/           # Static assets for the web (CSS, images, favicon)
├── templates/        # Django templates (base layout, home, auth, profile, chat)
├── scripts/          # Helper scripts (e.g., Firestore data maintenance)
│
├── manage.py         # Django entry point
├── requirements.txt  # Python dependencies
├── build.gradle.kts  # Android build (project-level)
├── settings.gradle.kts
├── package.json      # JS tooling/config (if used)
└── Procfile          # Process definitions for deployment (e.g., Heroku)
```

---

## 🚧 Future Improvements

- Video calls & Voice recordings (Agora or WebRTC)
- Rich media previews and improved file sharing
- End-to-end encryption layer for chats and calls
- Refined and expanded web UI/UX
- AI-powered chat summaries, message rewriting, and smart search
- Cross-platform theme unification and UI consistency
- Optional iOS client using the same backend architecture

---

## 📌 Project Status

CNNCT is an active and evolving capstone project.  
Future updates will expand features, improve consistency between platforms, and enhance the overall user-experience.
