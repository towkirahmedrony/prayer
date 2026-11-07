# Salah — ProGuard/R8 rules.
#
# The release build enables R8 code shrinking and resource shrinking (see
# app/build.gradle.kts and docs/TECH_STACK.md §3.11). No additional keep rules
# are needed: the app uses no reflection-based or annotation-generated entry
# points beyond what each library's consumer rules already cover — Koin DSL
# modules (not annotations), Compose, DataStore, coroutines and the Adhan
# engine are all reached by direct calls. Keep this file as the explicit
# placeholder for any future keep rule the app genuinely requires.
