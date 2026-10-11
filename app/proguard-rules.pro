# Salah — ProGuard/R8 rules.
#
# Shrinking is disabled for both build types in this phase (see docs/TECH_STACK.md),
# so these rules are only a placeholder for the eventual release configuration.
# Keep the Koin DSL metadata readable by reflection-free consumers is not required:
# Salah uses Koin's DSL module declarations, not annotations.
