# Nafl prayers: Duha, Awwabin, Tahajjud

Nafl prayers whose times Salah states **besides** the five. They are not in the five-prayer list.
Like the prohibited windows, these are Salah's **documented conventions, not rulings** — nothing here
has been reviewed by a qualified scholar. Calculation: `core/domain/nafl/NaflPrayerCalculator`.

| Prayer | From | To | Derived from |
|---|---|---|---|
| Duha | the end of the sunrise prohibition | the start of the zenith prohibition (or Dhuhr, if earlier) | `ProhibitedPeriodCalculator` windows |
| Duha, preferred part | sunrise + a quarter of the day (sunset − sunrise) | end of Duha | schedule's sunrise and sunset |
| Awwabin | displayed Maghrib | displayed Isha | the day's schedule |
| Tahajjud (valid window) | displayed Isha | following Fajr | the day's Isha and the following day's Fajr |
| Tahajjud (recommended) | start of the last third of the night | following Fajr | `NightPeriodCalculator` |

Because Duha is bounded by the prohibited windows themselves, Duha and a prohibited window can never
overlap or contradict each other, and a change to the prohibited policy moves Duha with it.

## What the sources say (Hanafi, read November 2026)

- Duha begins once the post-sunrise disliked time has passed (about 15-20 minutes) and ends just
  before the sun reaches its zenith (Fatawa Hindiyya, as quoted by SeekersGuidance and Daruliftaa).
- Many Hanafi scholars separate Ishraq (right after sunrise) from Duha proper; the classical books
  treat them as one prayer. Salah shows one Duha period.
- The preferred time is later in the forenoon, after a quarter of the day (Qibla.com, ShariahBoard).
- Awwabin: six rak'at after Maghrib is a subcontinental Hanafi tradition. Some scholars say the
  narrations for it are weak and that the hadith called "Awwabin" refers to Duha. Salah states the
  tradition and says so.
- After Fajr until sunrise and after Asr until sunset, Nafl prayer is disliked in the Hanafi
  school. That is a different category from the three prohibited windows and is **not** shown as
  "prohibited time".

## Verification (Dhaka, 8 November 2026)

Anchors calculated independently (NOAA): sunrise 06:08:32, solar transit 11:42:06, sunset 17:15:39.

| Value | Expected from the rules | Shown by the app / reference |
|---|---|---|
| Sunrise window | 06:08-06:28 | Fajr ends 06:08 |
| Zenith window | 11:37-11:46 | reference Duha ends 11:37 |
| Sunset window | 16:56-17:16 | Maghrib 17:20 is sunset + 4 min correction |
| Duha | 06:28-11:37 (309 min) | reference 06:24-11:37 |
| Last third | 01:01:20 | reference 01:00 (its Fajr is 04:51) |

`NaflPrayerCalculatorTest` pins these numbers.
