# OsmNav (Android, osmdroid + CI ready)

This repo builds on GitHub Actions — no Android Studio required to compile.

## Build on GitHub
1. Push this project to a new GitHub repo.
2. Go to **Actions** → `Android CI` → run (or push a commit).
3. Download the artifact `OsmNav-debug-apk` → `app-debug.apk`.

## API Key (optional, runtime)
Create a repo secret `GRAPHOPPER_API_KEY` (GraphHopper Directions API). The build writes it into `local.properties` so the app can use it at runtime.

## Notes
- compileSdk/targetSdk = 34
- AGP 8.6.1, Kotlin 2.0.20, Gradle 8.9 (managed by Actions)
