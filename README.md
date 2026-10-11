🕌 Islamic Companion

An Offline-First Islamic Companion for Android

A modern, reliable, and extensible Islamic utility app designed to bring prayer times, prohibited prayer periods, Islamic calendars, Ramadan schedules, and essential Islamic tools into one place.

Built with Kotlin, Jetpack Compose, and a modular offline-first architecture, the app aims to provide accurate, accessible, and privacy-conscious Islamic utilities that continue working without an internet connection.

«Our vision: Build more than a prayer time app—a complete Islamic companion that users can rely on every day.»

---

📌 Table of Contents

- "Overview" (#-overview)
- "Core Principles" (#-core-principles)
- "Features" (#-features)
- "Prayer Times Engine" (#-prayer-times-engine)
- "Prohibited Prayer Times" (#-prohibited-prayer-times)
- "Islamic Calendar" (#-islamic-calendar)
- "Ramadan Companion" (#-ramadan-companion)
- "Notifications and Reminders" (#-notifications-and-reminders)
- "Offline-First Architecture" (#-offline-first-architecture)
- "Technology Stack" (#-technology-stack)
- "Architecture" (#-architecture)
- "Project Structure" (#-project-structure)
- "Data and API Strategy" (#-data-and-api-strategy)
- "Privacy and Security" (#-privacy-and-security)
- "Testing and Reliability" (#-testing-and-reliability)
- "Development Roadmap" (#-development-roadmap)
- "Religious Accuracy" (#-religious-accuracy)
- "Contributing" (#-contributing)
- "License" (#-license)

---

🌟 Overview

Islamic Companion is a planned Android application that combines essential Islamic utilities with a modern, user-friendly experience.

The initial release will focus on prayer times, prohibited prayer periods, calendar conversion, Ramadan schedules, and reliable notifications.

The architecture will support additional features such as Qibla direction, Quran, Duas, Azkar, Tasbih, mosque discovery, and other Islamic utilities without requiring a complete architectural redesign.

Our Goals

- Provide dependable daily prayer schedules.
- Calculate prayer times locally whenever possible.
- Explain the source and calculation method behind prayer schedules.
- Display relevant prohibited prayer periods using verified rules.
- Support Gregorian, Bangla, and Hijri calendars.
- Provide Ramadan schedules and configurable Sehri and Iftar reminders.
- Keep essential features available offline.
- Respect user privacy and minimize unnecessary permissions.
- Maintain clear boundaries between features to simplify future development.
- Support multiple calculation methods, calendar providers, and regional configurations.

---

🧭 Core Principles

1. Accuracy First

Prayer times and religious information must be handled carefully.

Calculation methods, scholarly differences, local timetables, and confirmed calendar dates must not be treated as interchangeable sources.

2. Offline First

The application should not depend on an API to perform its essential calculations.

Prayer times, solar events, date conversion, saved schedules, and settings should remain accessible offline wherever the required information is available.

3. Modular by Design

Prayer calculation, calendar conversion, Ramadan scheduling, notifications, and other features should maintain separate responsibilities.

Adding a new feature should not require rewriting unrelated components.

4. Privacy by Default

Core features should work without requiring an account.

Location access should be optional when manual location selection can provide the required functionality.

5. Transparent Data Sources

Users should be able to understand whether information comes from a local calculation, an external API, a selected timetable, or a confirmed local calendar date.

6. Reliable Notifications

The application should account for Android permissions, device restarts, time-zone changes, and battery restrictions when scheduling reminders.

---

✨ Features

Features are organized by development phase. The list describes the intended product scope, not a claim that every feature is already implemented.

🏠 Home Dashboard

The home screen will provide an overview of essential daily information.

Planned capabilities:

- Current Gregorian date.
- Bangladesh Bangla calendar date.
- Hijri date.
- Weekday in the selected language.
- Upcoming Islamic events.
- Current prayer period.
- Next prayer and countdown.
- Daily prayer timetable.
- Sunrise and sunset.
- Solar noon.
- Daylight and nighttime duration.
- Estimated Tahajjud window.
- Last third of the night.
- Current location and calculation profile.
- Data source and synchronization status.
- Quick access to frequently used Islamic tools.

The dashboard will be designed to display useful cached or locally calculated information without waiting for a network response.

🕋 Prayer Times

Planned capabilities:

- Fajr, Dhuhr, Asr, Maghrib, and Isha.
- Sunrise, sunset, and solar noon.
- Multiple prayer calculation methods.
- Hanafi and Shafi'i Asr calculation options where supported.
- High-latitude adjustment options.
- Individual prayer-time adjustments.
- GPS-based location selection.
- Manual city and coordinate selection.
- Saved locations.
- Optional local mosque timetable integration.
- Daily and monthly prayer schedules.
- Next-prayer countdown.
- Local calculation and API-based schedule comparison.
- Schedule source and calculation profile information.

⛔ Prohibited Prayer Periods

A dedicated module will calculate and display relevant prohibited prayer periods.

Planned capabilities:

- Sunrise-related prohibited period.
- Solar-noon-related prohibited period.
- Sunset-related prohibited period.
- Start and end times.
- Current period status.
- Countdown to the next period.
- Explanations and references for the displayed rules.
- Configuration for relevant scholarly differences.

The application will not rely on arbitrary, universally hardcoded minute intervals. Religious rules and calculation parameters must be reviewed against appropriate sources.

📅 Three-Calendar System

The calendar system will support three calendar representations.

Gregorian Calendar

- Date, month, and year.
- Leap-year handling.
- Weekday information.
- Date navigation and conversion.

Bangla Calendar

- Bangladesh civil calendar.
- Bangla month and year.
- Bangla and English numeral formats.
- Regional calendar configuration for future expansion.
- Bangla New Year and configurable events.

Hijri Calendar

- Hijri date, month, and year.
- Islamic event calendar.
- Ramadan and Eid date estimates.
- Hijri date adjustment.
- Confirmed local date overrides.
- Calculated, expected, and confirmed date states.

Users will be able to view and convert dates across the supported calendar systems.

🌙 Ramadan Companion

Planned capabilities:

- Daily Ramadan dashboard.
- Ramadan day number.
- Sehri ending time.
- Iftar time.
- Fasting duration.
- Sehri and Iftar countdowns.
- Monthly Ramadan timetable.
- Configurable Sehri reminders.
- Configurable Iftar reminders.
- Optional Imsak display.
- Tarawih and Tahajjud reminders.
- Last ten nights reminders.
- Eid date information.

Sehri ending will be linked to the selected Fajr timetable. Imsak, when enabled, will be displayed separately with its configured meaning.

Iftar will follow the selected Maghrib schedule.

🔔 Notifications and Reminders

Planned capabilities:

- Individual prayer notifications.
- Optional Azan sounds.
- Silent reminders.
- Vibration settings.
- Custom pre-prayer reminders.
- Sehri and Iftar reminders.
- Prohibited-period reminders.
- Jummah reminders.
- Islamic event reminders.
- Notification channels.
- Permission-aware scheduling.
- Automatic rescheduling after relevant system or configuration changes.

Exact alarm availability and Android background restrictions will be handled explicitly rather than assumed.

🧭 Qibla Compass — Planned

- Location-based Qibla direction.
- Compass interface.
- Direction in degrees.
- Device sensor support.
- Compass calibration guidance.
- Alternative interface when sensors are unavailable.
- Optional map-based direction display.

📿 Tasbih, Dua, and Azkar — Planned

- Digital Tasbih counter.
- Custom counting targets.
- Saved counting sessions.
- Morning and evening Azkar.
- Daily Dua collection.
- Arabic text.
- Transliteration and translation.
- Favorites.
- Offline access.

📖 Quran — Planned

- Surah and Ayah navigation.
- Arabic Quran text.
- Translation.
- Tafsir integration.
- Audio recitation.
- Bookmarks.
- Reading progress.
- Downloaded audio.
- Daily reading goals.

Quranic text, translations, tafsir, and audio will require appropriate source verification and licensing review.

🕌 Mosque Finder — Planned

- Nearby mosque discovery.
- Distance and map information.
- Navigation.
- Saved mosques.
- Mosque-specific prayer schedules.
- Jummah time information.
- Verified local timetable support.

Online discovery may require a map or places provider. Saved locations and locally stored timetable information should remain available offline.

🧮 Additional Future Features

- Zakat calculator.
- Fasting tracker.
- Optional prayer tracker.
- Islamic date converter.
- Hajj and Umrah guide.
- Travel mode.
- Multiple saved locations.
- Home-screen widgets.
- Android shortcuts.
- Optional cloud backup and synchronization.
- Additional languages.
- Wear OS support.

---

🕋 Prayer Times Engine

The prayer calculation engine is one of the most important components of the application.

It will be designed as an independent component rather than embedding calculation logic inside the user interface.

Calculation Configuration

Supported configuration options will include:

- Calculation method.
- Asr juristic method.
- High-latitude adjustment.
- Individual prayer-time offsets.
- Location coordinates.
- Time zone.
- Time rounding rules.
- Selected timetable source.

The exact configuration options will depend on the capabilities of the selected calculation engine and data providers.

Local Calculation

The application will evaluate the Adhan Kotlin library for local astronomical prayer-time calculations.

The intended processing flow is:

Location and Date
        |
        v
Prayer Calculation Profile
        |
        v
Local Prayer Calculation Engine
        |
        v
Prayer and Solar Events
        |
        v
Validation and Source Metadata
        |
        v
Room Database
        |
        v
Home Screen and Notifications

Local calculations will reduce network dependency and allow essential functionality to remain available offline.

Provider-Based Design

Prayer schedules may come from different sources:

- Local astronomical calculations.
- AlAdhan API.
- Verified local or mosque timetables.
- Future official timetable integrations.
- User-configured timetable adjustments.

Each source will have a defined integration interface.

Switching a provider should not require rewriting the dashboard or notification system.

Bangladesh Configuration

Karachi calculation settings with Hanafi Asr may be evaluated as an initial configurable baseline for Bangladesh.

This configuration will not automatically be represented as an official Islamic Foundation Bangladesh timetable.

Where possible, calculated results will be compared with verified local timetables. Users should be able to identify and select their preferred source.

---

⛔ Prohibited Prayer Times

Prohibited prayer periods will have their own calculation and presentation layer.

The system will distinguish astronomical events from religious interpretations associated with those events.

For example:

- Sunrise is a solar event.
- Sunset is a solar event.
- Solar noon is an astronomical event.
- The associated prohibited periods require the applicable religious rules and calculation parameters.

The implementation will avoid treating all scholarly interpretations as identical.

Where different rulings apply, the application will identify the selected rule set and provide relevant references.

Religious rules, interval boundaries, and explanatory content must undergo appropriate review before being presented as authoritative guidance.

---

📅 Islamic Calendar

Calendar Architecture

The calendar engine will keep date conversion separate from calendar presentation.

Conceptually, the system will support:

Calendar Engine
      |
      +-- Gregorian Calendar
      |
      +-- Bangladesh Bangla Calendar
      |
      +-- Hijri Calendar
      |
      +-- Hijri API Provider
      |
      +-- Confirmed Date Overrides

Hijri Date Policy

Hijri dates can differ according to calculation methods and local moon-sighting announcements.

The application will therefore distinguish between:

- Calculated dates.
- Expected dates.
- Confirmed local dates.

A confirmed date override should take precedence according to the configured calendar policy.

The original calculated value and its source should remain available for troubleshooting and transparency.

Bangla Calendar Policy

The initial Bangla calendar implementation will target the Bangladesh civil calendar.

Other regional calendar conventions may be added through separate implementations without changing the entire calendar module.

---

🌙 Ramadan Companion

Ramadan schedules will use the same underlying prayer schedule and location configuration as the core prayer system.

This avoids maintaining conflicting copies of Fajr and Maghrib times.

Schedule Data

Each Ramadan day may contain:

- Gregorian date.
- Bangla date.
- Hijri date.
- Fajr time.
- Sehri ending time.
- Optional Imsak time.
- Maghrib time.
- Iftar time.
- Data source.
- Confirmation status.

Reliability

- Ramadan schedules will be cached locally.
- Available local calculations will work offline.
- External data will be validated before being stored.
- Changes to the selected location or prayer profile will trigger schedule refreshes.
- Hijri dates will support confirmed local overrides.
- The interface will identify estimated or unverified dates where appropriate.

---

🔔 Notifications and Reminders

Notification scheduling will be implemented independently from the user interface.

Scheduling Components

- Android AlarmManager for appropriate scheduled alarms.
- Broadcast receivers for alarm handling.
- NotificationManager for notification delivery.
- WorkManager for suitable background synchronization tasks.
- A centralized scheduler for rebuilding scheduled events.

WorkManager will not be treated as a replacement for precise prayer-time alarms.

Rescheduling Triggers

The scheduler will reconsider alarms when:

- The device restarts.
- The date changes.
- The time zone changes.
- The selected location changes.
- Prayer calculation settings change.
- Manual offsets change.
- Notification preferences change.
- Exact alarm permissions change.

Android Restrictions

The application will account for:

- Exact alarm permissions.
- Notification permissions.
- Battery optimization.
- Doze mode.
- Background execution restrictions.
- Android version differences.
- Manufacturer-specific power management.

When exact scheduling is unavailable, the application will communicate the limitation and use an appropriate supported fallback.

The application will not promise perfect notification delivery on every Android device.

---

📴 Offline-First Architecture

Offline support is a core architectural requirement, not an optional enhancement.

Data Flow

             Application Starts
                     |
                     v
             Read Local Database
                     |
                     v
              Display Available Data
                     |
                     v
             Check Data Freshness
                     |
              +------+------+
              |             |
              v             v
          Data Stale     Data Current
              |
              v
       Refresh When Possible
              |
              v
       Validate Remote Data
              |
              v
        Update Local Database
              |
              v
       UI Observes Updated Data

Local Data Sources

Room Database

- Prayer schedules.
- Saved locations.
- Calendar data.
- Ramadan schedules.
- Timetable source metadata.
- Confirmed date overrides.
- Other structured offline content.

DataStore

- Language preference.
- Theme.
- Time format.
- Numeral format.
- Selected calculation method.
- Asr method.
- Notification preferences.
- Calendar configuration.

Offline Guarantees

The intended offline behavior includes:

- Local prayer-time calculation.
- Prohibited-period calculation when the required local inputs are available.
- Solar-event calculation.
- Gregorian and Bangla date conversion.
- Available Hijri date conversion and cached date access.
- Cached Ramadan schedules.
- Saved locations.
- User settings.
- Previously downloaded content.

Features requiring live maps, remote content, or online search may have reduced functionality without a network connection.

---

🛠 Technology Stack

The following technologies are planned for evaluation and implementation.

Component| Technology| Purpose
Language| Kotlin| Native Android development
UI| Jetpack Compose| Declarative user interface
Design System| Material 3| Consistent visual design
Architecture| MVVM + Clean Architecture principles| Separation of responsibilities
Database| Room| Structured local storage
Preferences| DataStore| Persistent settings
Asynchronous Operations| Coroutines + Flow| Reactive data handling
Networking| Retrofit or Ktor| Remote API integration
Serialization| Kotlin Serialization| Data serialization
Prayer Calculation| Adhan Kotlin library, subject to evaluation| Local prayer-time calculation
Background Sync| WorkManager| Persistent synchronization
Scheduled Alerts| AlarmManager| Appropriate time-based alarms
Notifications| NotificationManager| Android notification delivery
Dependency Injection| Hilt or Koin| Dependency management
Navigation| Navigation Compose| Screen navigation
Prayer Data API| AlAdhan API as an optional provider| Prayer and calendar data
Bangla Calendar| Local calendar engine| Offline Bangla date conversion
Optional Backend| Supabase or another suitable backend| Remote content and optional synchronization

Only technologies required for the current development phase will be introduced.

Dependency versions will be selected from compatible stable releases when implementation begins.

---

🏗 Architecture

The application will follow modular design principles and an offline-first repository pattern.

High-Level Architecture

+---------------------------------------+
|             PRESENTATION              |
|                                       |
| Home | Prayer | Calendar | Ramadan    |
| Qibla | Quran  | Tasbih   | Settings  |
+-------------------+-------------------+
                    |
                    v
+---------------------------------------+
|              VIEWMODELS               |
|                                       |
| UI State, User Actions, Screen State  |
+-------------------+-------------------+
                    |
                    v
+---------------------------------------+
|                DOMAIN                 |
|                                       |
| CalculatePrayerTimes                  |
| GetNextPrayer                         |
| GetProhibitedPeriods                  |
| ConvertCalendarDate                   |
| GetRamadanSchedule                    |
| ScheduleNotifications                 |
+-------------------+-------------------+
                    |
                    v
+---------------------------------------+
|              REPOSITORIES             |
|                                       |
| Prayer | Calendar | Ramadan | Location|
+-------------------+-------------------+
                    |
           +--------+--------+
           |                 |
           v                 v
+--------------------+ +--------------------+
|    LOCAL DATA      | |    REMOTE DATA     |
|                    | |                    |
| Room Database      | | Prayer API         |
| DataStore          | | Calendar API       |
| Local Calculators  | | Official Timetables|
| Bundled Content    | | Optional Backend   |
+--------------------+ +--------------------+

Architectural Rules

1. UI components must not call external APIs directly.
2. Business rules must not be embedded in screen composables.
3. Repositories will coordinate local and remote data sources.
4. Local data will remain the primary read source for offline-first features.
5. External providers will be accessed through defined interfaces.
6. Notification scheduling will remain independent of screen lifecycle.
7. Database changes will use versioned migrations.
8. Feature modules will depend on stable shared interfaces.
9. Religious calculations and calendar conversions will be testable independently of the UI.
10. Unrelated features should not require unnecessary modifications when a module changes.

---

📂 Project Structure

The following is a proposed modular structure. Actual Gradle modules will be introduced incrementally as the project develops.

islamic-companion/
│
├── app/
│
├── core/
│   ├── common/
│   ├── model/
│   ├── domain/
│   ├── database/
│   ├── network/
│   ├── location/
│   ├── notifications/
│   ├── designsystem/
│   └── testing/
│
├── data/
│   ├── prayer/
│   ├── calendar/
│   ├── ramadan/
│   ├── content/
│   └── location/
│
├── feature/
│   ├── home/
│   ├── prayer/
│   ├── forbidden-times/
│   ├── calendar/
│   ├── ramadan/
│   ├── qibla/
│   ├── mosque-finder/
│   ├── quran/
│   ├── tasbih/
│   ├── zakat/
│   └── settings/
│
├── gradle/
│
├── .github/
│   └── workflows/
│
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── README.md
└── LICENSE

Module Responsibilities

Module| Responsibility
"core:common"| Shared utilities and result types
"core:model"| Shared domain models
"core:domain"| Business rules and repository interfaces
"core:database"| Room entities, DAOs, and migrations
"core:network"| Shared networking configuration
"core:location"| Location acquisition and management
"core:notifications"| Alarm scheduling and notification delivery
"core:designsystem"| Reusable UI components and theme
"core:testing"| Shared testing utilities
"data:prayer"| Prayer calculations, providers, and persistence
"data:calendar"| Calendar conversion and synchronization
"data:ramadan"| Ramadan schedule integration
"feature:*"| Individual feature presentation and interaction

The project will avoid unnecessary modularization during the initial stages. Module boundaries should reflect real responsibilities rather than simply increasing the number of Gradle modules.

---

🔌 Data and API Strategy

External APIs will be optional providers rather than mandatory dependencies for core functionality.

Prayer Providers

The application may support:

- Local calculation provider.
- AlAdhan API provider.
- Verified timetable provider.
- Custom timetable provider.

Calendar Providers

The calendar system may support:

- Local Gregorian calendar.
- Local Bangladesh Bangla calendar.
- Local Hijri calculation.
- Hijri calendar API.
- Confirmed date override provider.

Data Validation

Before external data is stored, the application should validate:

- Response structure.
- Required fields.
- Date and location association.
- Time-zone consistency.
- Time values.
- Provider metadata.
- Calculation profile compatibility.

Invalid responses must not silently overwrite valid local data.

Suggested Database Entities

Entity| Purpose
"saved_locations"| Saved coordinates and location metadata
"prayer_profiles"| Calculation settings and offsets
"prayer_schedules"| Daily prayer and solar-event schedules
"prayer_time_sources"| Provider and source information
"calendar_days"| Gregorian, Bangla, and Hijri date mappings
"calendar_events"| Islamic observances and configurable events
"ramadan_schedules"| Daily Sehri and Iftar schedules
"date_overrides"| Confirmed dates and manual corrections
"notification_settings"| Per-event notification preferences
"saved_content"| Favorites and saved Islamic content

These entities describe the intended data model. Tables will be added when the corresponding functionality is implemented.

Prayer Schedule Metadata

A stored schedule should identify its:

- Location.
- Local date.
- Time zone.
- Prayer calculation profile.
- Source provider.
- Calculation version, where applicable.
- Calculation timestamp.
- Synchronization timestamp, where applicable.

This information helps prevent schedules from being reused incorrectly after location, date, or calculation settings change.

---

🔐 Privacy and Security

Privacy is a foundational requirement.

Privacy Principles

- No mandatory account for core features.
- Optional location permission.
- Manual location selection when GPS is unavailable or denied.
- Minimal collection of personal information.
- Local storage for personal settings and optional activity tracking.
- Transparent handling of remote requests.
- No unnecessary background location tracking.
- No embedded private API secrets.
- Optional backup or synchronization only when implemented and clearly disclosed.

If a backend is introduced, it should serve a defined purpose such as remote content management, verified timetable distribution, or optional synchronization.

Core local calculations and previously saved data must not become unavailable merely because a backend is down.

---

🧪 Testing and Reliability

Testing will cover calculations, date boundaries, offline behavior, notifications, and religious-content verification.

Prayer Calculation Tests

- Multiple locations in Bangladesh.
- Different latitudes.
- Different calculation methods.
- Hanafi and Shafi'i Asr settings.
- High-latitude cases.
- Manual time adjustments.
- Date and time-zone boundaries.

Where verified local timetables are available, results will be compared against them and differences documented.

Calendar Tests

- Gregorian leap years.
- Year boundaries.
- Bangla New Year transitions.
- Hijri month transitions.
- Ramadan boundaries.
- Confirmed date overrides.
- Manual date adjustments.
- Calendar conversion consistency.

Offline Tests

- Internet unavailable.
- API unavailable.
- Slow network.
- Empty database.
- Existing cached data.
- Invalid API responses.
- Duplicate synchronization.
- Database migration.

Notification Tests

- Device restart.
- App process termination.
- Exact alarm permission denied.
- Notification permission denied.
- Permission changes.
- Time-zone changes.
- Location changes.
- Calculation settings changes.
- Battery optimization and Doze mode.
- Duplicate notification prevention.

Religious Content Review

Qualified review should be sought for:

- Prohibited prayer periods.
- Calculation parameters.
- Ramadan timetable rules.
- Sehri and Iftar labeling.
- Quranic text and references.
- Dua and Azkar content.
- Islamic event information.

Passing automated tests alone does not establish the religious correctness of a calculation or ruling.

---

🗺 Development Roadmap

Development will proceed incrementally, prioritizing reliability before feature expansion.

Phase 1 — Project Foundation

- [ ] Initialize the Android project.
- [ ] Establish the design system.
- [ ] Set up navigation.
- [ ] Configure dependency injection.
- [ ] Define core domain models.
- [ ] Establish repository interfaces.
- [ ] Integrate Room and DataStore.
- [ ] Implement the offline-first data flow.
- [ ] Configure automated build and test workflows.

Phase 2 — Core Prayer System

- [ ] Implement location management.
- [ ] Integrate local prayer calculations.
- [ ] Add calculation settings.
- [ ] Display daily prayer schedules.
- [ ] Display solar events.
- [ ] Implement prohibited-period calculations.
- [ ] Add next-prayer countdown.
- [ ] Implement notification scheduling.
- [ ] Test core functionality offline.

Phase 3 — Calendar and Ramadan

- [ ] Implement Bangladesh Bangla calendar conversion.
- [ ] Integrate Hijri date calculation or a selected provider.
- [ ] Add Gregorian, Bangla, and Hijri date conversion.
- [ ] Build monthly calendar views.
- [ ] Add Ramadan schedules.
- [ ] Add Sehri and Iftar reminders.
- [ ] Implement confirmed-date overrides.

Phase 4 — API Integration and Synchronization

- [ ] Add the AlAdhan API adapter.
- [ ] Implement remote timetable integration where available.
- [ ] Validate remote data.
- [ ] Implement local caching.
- [ ] Configure WorkManager synchronization.
- [ ] Add retry and fallback handling.
- [ ] Display data source and freshness information.

Phase 5 — Additional Islamic Features

- [ ] Qibla compass.
- [ ] Tasbih.
- [ ] Dua and Azkar.
- [ ] Mosque finder.
- [ ] Optional prayer and fasting tracker.
- [ ] Zakat calculator.

Phase 6 — Advanced Experience

- [ ] Home-screen widgets.
- [ ] Advanced notification customization.
- [ ] Quran module.
- [ ] Audio support.
- [ ] Optional backup and synchronization.
- [ ] Additional languages.
- [ ] Travel mode.
- [ ] Wear OS support.

The roadmap may change as technical validation, testing, and user feedback reveal new requirements.

---

📖 Religious Accuracy

Islamic Companion aims to present useful Islamic information responsibly.

Prayer calculations, prohibited periods, Hijri dates, and religious content may vary according to calculation methods, local practices, or scholarly interpretation.

Therefore:

- Calculation methods will be identifiable.
- Timetable sources will be transparent.
- Regional differences will be considered.
- Confirmed local calendar dates will be distinguishable from estimates.
- Religious explanations will use reviewed references.
- The application will not invent religious rulings.
- Unverified information will not be presented as universally authoritative.

The application is intended to assist users with Islamic utilities, not replace qualified religious guidance or verified local timetables.

---

🤝 Contributing

Contributions are welcome once the repository's contribution guidelines and licensing details are established.

Potential contribution areas include:

- Prayer calculation verification.
- Calendar conversion.
- Offline-first data handling.
- Notification reliability.
- Accessibility and localization.
- Automated testing.
- UI/UX improvements.
- Documentation.
- Additional data providers.

Contributions involving prayer calculations, prohibited periods, or religious content should include appropriate references and tests.

All contributions should preserve the project's architectural boundaries, privacy principles, and offline-first requirements.

---

📄 License

The project license will be specified in the repository's "LICENSE" file.

Until that file is established, users should not assume that the source code is available under a particular open-source license.

---

🌙 Our Long-Term Vision

Islamic Companion aims to become a dependable, privacy-conscious, and extensible Islamic companion for everyday life.

By combining local calculations, reliable data storage, transparent sources, and modular architecture, the application will begin with essential prayer and calendar utilities while maintaining a foundation for future Islamic features.

Built around reliability. Designed for everyday use. Ready to grow.
