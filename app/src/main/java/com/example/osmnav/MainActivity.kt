package com.example.osmnav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlinx.coroutines.*
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import kotlin.math.*

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
  private lateinit var map: MapView
  private var myLocationOverlay: MyLocationNewOverlay? = null
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private lateinit var ttbPrimary: TextView
  private lateinit var ttbSecondary: TextView
  private lateinit var nextStepFab: FloatingActionButton

  private var tts: TextToSpeech? = null
  private lateinit var fused: FusedLocationProviderClient
  private lateinit var req: LocationRequest
  private var navigating = false

  data class Step(
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val endLat: Double,
    val endLon: Double
  )

  private var steps: List<Step> = emptyList()
  private var currentStepIndex: Int = 0
  private var routePolyline: Polyline? = null
  private var routePoints: List<GeoPoint> = emptyList()
  private var destination: GeoPoint? = null

  private val requestPermissions = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { perms ->
    val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                  perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    if (granted) enableMyLocation()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val ctx = applicationContext
    Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", MODE_PRIVATE))
    Configuration.getInstance().userAgentValue = applicationContext.packageName

    setContentView(R.layout.activity_main)
    map = findViewById(R.id.map)
    ttbPrimary = findViewById(R.id.ttbPrimary)
    ttbSecondary = findViewById(R.id.ttbSecondary)
    nextStepFab = findViewById(R.id.nextStepFab)

    map.setTileSource(TileSourceFactory.MAPNIK)
    map.setMultiTouchControls(true)

    map.overlays.add(RotationGestureOverlay(map).apply { isEnabled = true })
    val compass = CompassOverlay(this, InternalCompassOrientationProvider(this), map)
    compass.enableCompass()
    map.overlays.add(compass)
    map.overlays.add(ScaleBarOverlay(map))

    map.controller.setZoom(14.5)
    val athens = GeoPoint(37.9838, 23.7275)
    map.controller.setCenter(athens)

    ensureLocationPermissions()
    fused = LocationServices.getFusedLocationProviderClient(this)
    req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()

    tts = TextToSpeech(this, this)

    val origin = athens
    val dest = GeoPoint(37.9715, 23.7267)
    destination = dest
    addMarker(origin, "Origin")
    addMarker(dest, "Destination")

    nextStepFab.setOnClickListener { advanceStep(manual = true) }

    scope.launch { routeWithGraphHopper(origin, dest) }
  }

  override fun onDestroy() {
    super.onDestroy()
    scope.cancel()
    tts?.shutdown()
    fused.removeLocationUpdates(cb)
  }

  override fun onInit(status: Int) {
    tts?.language = Locale.getDefault()
  }

  private fun ensureLocationPermissions() {
    val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
      requestPermissions.launch(arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
      ))
    } else enableMyLocation()
  }

  private fun enableMyLocation() {
    if (myLocationOverlay != null) return
    myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map).apply {
      enableMyLocation()
      enableFollowLocation()
    }
    map.overlays.add(myLocationOverlay)

    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
      fused.requestLocationUpdates(req, cb, mainLooper)
    }
  }

  private val cb = object : LocationCallback() {
    override fun onLocationResult(res: LocationResult) {
      val loc = res.lastLocation ?: return
      val p = GeoPoint(loc.latitude, loc.longitude)

      val (distToRoute, snap) = nearestPointOnPolyline(p, routePoints)
      val displayPoint = if (distToRoute < 20.0) snap else p

      val curr = steps.getOrNull(currentStepIndex)
      curr?.let {
        val d = distanceMeters(
          displayPoint.latitude, displayPoint.longitude,
          it.endLat, it.endLon
        )
        if (d < 25.0) advanceStep()
      }

      if (navigating && routePoints.isNotEmpty() && distToRoute > 40.0) {
        destination?.let { dst ->
          scope.launch { routeWithGraphHopper(displayPoint, dst) }
        }
      }
    }
  }

  private fun addMarker(point: GeoPoint, title: String) {
    val m = Marker(map)
    m.position = point
    m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    m.title = title
    map.overlays.add(m)
  }

  private suspend fun routeWithGraphHopper(start: GeoPoint, end: GeoPoint) {
    try {
      val key = graphHopperKey()
      val url = "https://graphhopper.com/api/1/route?profile=car&locale=" + Locale.getDefault().language +
                "&points_encoded=false&ch.disable=true" +
                "&point=" + start.latitude + "," + start.longitude +
                "&point=" + end.latitude + "," + end.longitude +
                "&instructions=true&calc_points=true&points_encoded=false&key=" + URLEncoder.encode(key, "UTF-8")
      val json = URL(url).readText()
      val root = JSONObject(json)
      val paths = root.getJSONArray("paths")
      if (paths.length() == 0) throw Exception("No route")
      val path = paths.getJSONObject(0)

      val pts = mutableListOf<GeoPoint>()
      val points = path.getJSONObject("points")
      val arr = points.getJSONArray("coordinates")
      for (i in 0 until arr.length()) {
        val c = arr.getJSONArray(i)
        val lon = c.getDouble(0)
        val lat = c.getDouble(1)
        pts.add(GeoPoint(lat, lon))
      }

      // GH: 'instructions' is an array
      val instrsContainer = path.getJSONArray("instructions")
      val parsed = mutableListOf<Step>()
      for (i in 0 until instrsContainer.length()) {
        val ins = instrsContainer.getJSONObject(i)
        val text = ins.getString("text")
        val dist = ins.optDouble("distance", 0.0)
        val timeMs = ins.optDouble("time", 0.0)
        val interval = ins.getJSONArray("interval")
        val endIdx = minOf(interval.getInt(1), pts.size - 1)
        val endPt = pts[endIdx]
        parsed.add(Step(text, dist, timeMs / 1000.0, endPt.latitude, endPt.longitude))
      }

      val totalDist = path.optDouble("distance", 0.0)
      val totalTime = path.optDouble("time", 0.0) / 1000.0

      withContext(Dispatchers.Main) {
        routePolyline?.let { map.overlays.remove(it) }
        routePolyline = Polyline().apply { setPoints(pts); outlinePaint.strokeWidth = 8f }
        map.overlays.add(routePolyline)
        map.invalidate()

        routePoints = pts
        steps = parsed
        currentStepIndex = 0
        navigating = true
        updateTtb(totalDist, totalTime)
        speakCurrent()
      }
    } catch (e: Exception) {
      Log.e("OsmNav", "Routing failed", e)
      withContext(Dispatchers.Main) {
        ttbPrimary.text = "Routing failed"
        ttbSecondary.text = e.message ?: "Unknown error"
      }
    }
  }

  private fun graphHopperKey(): String {
    val fromEnv = System.getenv("GRAPHOPPER_API_KEY")
    if (fromEnv != null && fromEnv.isNotBlank()) return fromEnv
    return "PUT_YOUR_GRAPHOPPER_API_KEY_HERE"
  }

  private fun updateTtb(totalDist: Double, totalDurSec: Double) {
    val curr = steps.getOrNull(currentStepIndex)
    if (curr == null) {
      ttbPrimary.text = "No steps"
      ttbSecondary.text = ""
      return
    }
    val km = (totalDist / 1000.0)
    val mins = (totalDurSec / 60.0).roundToInt()
    ttbPrimary.text = curr.instruction
    ttbSecondary.text = "ETA ~ " + mins + " min  •  Total " + String.format("%.1f", km) + " km"
  }

  private fun speakCurrent() {
    val txt = steps.getOrNull(currentStepIndex)?.instruction ?: return
    tts?.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "instr_" + currentStepIndex)
  }

  private fun advanceStep(manual: Boolean = false) {
    if (currentStepIndex + 1 < steps.size) {
      currentStepIndex++
      val remainingDist = steps.drop(currentStepIndex).sumOf { it.distanceMeters }
      val remainingDur = steps.drop(currentStepIndex).sumOf { it.durationSeconds }
      updateTtb(remainingDist, remainingDur)
      speakCurrent()
    } else {
      ttbPrimary.text = "Arrived"
      ttbSecondary.text = ""
      tts?.speak("You have arrived", TextToSpeech.QUEUE_FLUSH, null, "arrived")
      navigating = false
    }
  }

  private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat/2)*sin(dLat/2) + cos(Math.toRadians(lat1))*cos(Math.toRadians(lat2))*sin(dLon/2)*sin(dLon/2)
    val c = 2 * atan2(sqrt(a), sqrt(1-a))
    return R * c
  }

  private fun nearestPointOnPolyline(p: GeoPoint, poly: List<GeoPoint>): Pair<Double, GeoPoint> {
    if (poly.isEmpty()) return Pair(Double.POSITIVE_INFINITY, p)
    var bestDist = Double.POSITIVE_INFINITY
    var bestPoint = poly.first()
    for (i in 0 until poly.size - 1) {
      val a = poly[i]
      val b = poly[i+1]
      val cand = nearestPointOnSegment(p, a, b)
      val d = distanceMeters(p.latitude, p.longitude, cand.latitude, cand.longitude)
      if (d < bestDist) {
        bestDist = d
        bestPoint = cand
      }
    }
    return Pair(bestDist, bestPoint)
  }

  private fun nearestPointOnSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): GeoPoint {
    val latRad = Math.toRadians(p.latitude)
    val mPerDegLat = 111132.954
    val mPerDegLon = 111132.954 * cos(latRad)
    val ax = (a.longitude - p.longitude) * mPerDegLon
    val ay = (a.latitude - p.latitude) * mPerDegLat
    val bx = (b.longitude - p.longitude) * mPerDegLon
    val by = (b.latitude - p.latitude) * mPerDegLat
    val px = 0.0; val py = 0.0

    val abx = bx - ax; val aby = by - ay
    val apx = px - ax; val apy = py - ay
    val ab2 = abx*abx + aby*aby
    val t = if (ab2 == 0.0) 0.0 else max(0.0, min(1.0, (apx*abx + apy*aby)/ab2))
    val cx = ax + t * abx
    val cy = ay + t * aby
    val lon = p.longitude + cx / mPerDegLon
    val lat = p.latitude + cy / mPerDegLat
    return GeoPoint(lat, lon)
  }
}
