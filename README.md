# OsmNav (Android, osmdroid + GraphHopper API)

Features:
- OpenStreetMap rendering via osmdroid
- Turn-by-turn bottom panel (instruction + ETA/distance)
- Voice prompts with TextToSpeech
- Auto-advance instructions based on proximity to next maneuver
- Simple off-route detection and auto re-routing
- Geocoding (Nominatim) helper

## Setup
1. Open in Android Studio (JDK 17, AGP latest).
2. Put your GraphHopper Directions API key into `local.properties` as:

   `GRAPHOPPER_API_KEY=your_key_here`

   Or set an env var `GRAPHOPPER_API_KEY` during build/run.

3. Run on a device with GPS.

## Notes
- OSRM demo server was replaced by GraphHopper Directions API.
- Rerouting triggers if you're ~40m off the planned line.
- Geocoding uses Nominatim; respect its usage policy for production.
