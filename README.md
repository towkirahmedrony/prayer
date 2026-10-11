# 🌙 Islamic Companion App

A comprehensive, offline-first Islamic companion app built for Android. Designed with reliability and extensibility in mind, this app goes beyond fetching API data by utilizing local astronomical calculations to ensure prayer times, calendars, and notifications work flawlessly without an active internet connection.

## 🎯 Overall Vision

The application is heavily modularized into 6 core engines to ensure separation of concerns and future scalability:

| Engine | Responsibility |
|---|---|
| **Prayer & Solar Engine** | Prayer times, sunrise, sunset, solar noon, and prohibited time calculation. |
| **Calendar Engine** | English (Gregorian), Bangla (Civil), and Hijri calendar conversions and events. |
| **Ramadan Engine** | Sehri, Iftar, fasting calendars, and specific Ramadan reminders. |
| **Offline Data Engine** | Local database (Room), cached API responses, and background data synchronization. |
| **Notification Engine** | Reliable scheduling for Azan, reminders, Sehri/Iftar alarms, and Doze-mode handling. |
| **Islamic Features** | Qibla compass, Quran, Daily Azkar, Tasbih, Mosque finder, and future modules. |

---

## ✨ Core Features

### 🕌 Advanced Prayer Times Engine
* **Local Calculation:** Powered by the astronomical calculation libraries (e.g., Adhan Kotlin) combined with verified local offsets.
* **Extensive Configurations:** Supports multiple calculation methods (MWL, ISNA, Karachi, Umm Al-Qura, etc.), Asr Madhhab settings (Hanafi/Shafi'i), and high-latitude adjustments.
* **Prohibited Times:** Dedicated tracking for Sunrise, Zawal (solar noon), and Sunset windows.

### 📅 Triple Calendar System
* **Seamless Integration:** View English, Bangla, and Hijri dates simultaneously.
* **Bangla Calendar:** Built-in algorithm for accurate local Bangla dates.
* **Hijri Override:** Support for locally sighted moon announcements and manual date adjustments.

### 🌙 Ramadan Dashboard
* Daily tracking of Sehri ending and Iftar times.
* Intelligent countdowns, monthly timetables, and specialized alarms (Tahajjud, last 10 nights, etc.).

### 🔔 Bulletproof Notifications
* Per-prayer customization (Azan sound, vibration, silent, pre-prayer reminders).
* Resilient scheduling system that automatically recalibrates upon device reboot, timezone shifts, or location changes.

### 🧭 Additional Tools
* Qibla compass (sensor & map-based).
* Digital Tasbih, Morning/Evening Azkar, and modular support for Quran and Zakat calculators in the future.

---

## 🏗️ Architecture

This project strictly follows **Clean Architecture** principles, the **Repository Pattern**, and an **Offline-First** strategy. The UI never interacts directly with network calls or raw databases; everything is mediated through domain use cases.

### Data Flow Diagram

```text
APP START ➔ Read Local Database ➔ Show Cached Data
                    │
            Is Data Missing/Stale?
            /                    \
          Yes                     No ➔ Continue
           │
   Local Calculation ➔ Show Available Data ➔ Background API Sync ➔ Update Local DB
```

### Technology Stack

* **Language:** Kotlin
* **UI Toolkit:** Jetpack Compose (Material 3)
* **Architecture:** MVVM + Clean Architecture
* **Local Database:** Room
* **Preferences:** DataStore
* **Asynchronous Operations:** Coroutines & Flow
* **Networking:** Retrofit / Ktor + Kotlin Serialization
* **Background Tasks:** WorkManager
* **Dependency Injection:** Hilt / Koin
* **Navigation:** Navigation Compose

---

## 📂 Module Structure

The project is structured by feature to allow scalable development without monolithic tightly-coupled code:

```text
app/
├── core/
│   ├── common/        # Shared utilities, date/time logic
│   ├── model/         # Domain models (PrayerTime, LocationInfo)
│   ├── domain/        # Use cases and repository interfaces
│   ├── database/      # Room entities, DAOs
│   ├── network/       # API clients
│   ├── notifications/ # Alarm scheduling and receivers
│   └── designsystem/  # Compose theme, typography, components
├── data/
│   ├── prayer/        # Local calculators, API adapters
│   ├── calendar/      # Calendar conversion algorithms
│   └── ramadan/       # Fasting schedules
└── feature/
    ├── home/          # Home dashboard
    ├── prayer/        # Prayer timetable UI
    ├── calendar/      # Multi-calendar views
    ├── ramadan/       # Ramadan specifics
    ├── qibla/         # Compass UI
    └── settings/      # User preferences
```

---

## 🔒 Key Architectural Decisions & Policies

1. **Source of Truth:** The local database (Room) and local calculation engines are the primary source of truth. APIs act as providers for synchronization and authoritative cross-checking, not as direct dependencies for the UI.
2. **Date Resolution:** Hijri dates support manual overrides based on local confirmed moon sightings, which take priority over API/astronomical estimates.
3. **Offline Guarantee:** Core prayer times, prohibited times, calendar conversions, and cached Ramadan schedules are guaranteed to work 100% offline.
4. **Resilient Notifications:** If Android's `SCHEDULE_EXACT_ALARM` permission is unavailable/revoked, the app gracefully falls back to supported periodic notification strategies while informing the user.
5. **Provider-based APIs:** External APIs (like AlAdhan) are integrated using adapter patterns. Swapping API providers in the future requires zero changes to the UI or core domain logic.

---

## 🗺️ Development Roadmap

* [ ] **Phase 1: Foundation** - Project structure, DI, Room, DataStore, and Design System.
* [ ] **Phase 2: Core Prayer System** - Local calculation engine, location services, daily timetable, and prohibited times.
* [ ] **Phase 3: Calendar & Ramadan** - Bangla/Hijri conversion engine, multi-calendar UI, and fasting schedules.
* [ ] **Phase 4: API & Synchronization** - API adapters, WorkManager background sync, and cache validation.
* [ ] **Phase 5: Advanced Islamic Features** - Qibla, Tasbih, Azkar, and Mosque finder.
* [ ] **Phase 6: Premium Experience** - Home screen widgets, Wear OS support, Quran module, and data export.

---

## 🛡️ Privacy & Security

* **No Mandatory Account:** All core features function without user registration.
* **Local-First Analytics:** Personal prayer/fasting tracking is stored completely on-device.
* **Location Privacy:** Coarse/precise location is used strictly for calculating prayer times and Qibla direction, and is not transmitted to arbitrary third-party servers. 
