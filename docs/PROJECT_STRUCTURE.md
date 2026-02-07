# Project Structure

Updated: 2026-02-07

## Top-level Layout
```text
.
- .gitignore
- .history
- .kotlin
- .vscode
- LICENSE
- MuMuChat-v2.0-beta.apk
- README.md
- README_EN.md
- app
- build.gradle
- docs
- gradle
- gradle.properties
- gradlew
- gradlew.bat
- settings.gradle
- src
```

## Conventions
- Keep executable/business code under src/ as the long-term target.
- Keep docs under docs/ (or doc/ for Cangjie projects).
- Keep local runtime artifacts and secrets out of version control.
