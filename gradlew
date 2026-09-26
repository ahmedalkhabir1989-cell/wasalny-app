#!/usr/bin/env sh
# Minimal wrapper - GitHub Actions uses gradle/actions/setup-gradle which downloads its own Gradle.
# For local builds prefer installing Gradle or generating full wrapper with: gradle wrapper
exec gradle "$@"
