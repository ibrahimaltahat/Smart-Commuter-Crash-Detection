package com.smartcommuter.presentation.map

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import com.smartcommuter.presentation.countdown.EmergencyCountdownActivity
import java.net.URL

@Composable
fun TripMapScreen(
    viewModel: TripMapViewModel,
    googleMapsApiKey: String
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(uiState.originLocation, 14f)
    }

    // مزود خرائط Carto Voyager مفتوح لتطبيقات الأندرويد بتصميم مطابق لـ Google Maps
    val freeStreetTileProvider = remember {
        object : UrlTileProvider(256, 256) {
            override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? {
                return try {
                    URL("https://a.basemaps.cartocdn.com/rastertiles/voyager/$zoom/$x/$y.png")
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    LaunchedEffect(uiState.decodedRoutePoints) {
        if (uiState.decodedRoutePoints.isNotEmpty()) {
            try {
                val boundsBuilder = LatLngBounds.builder()
                uiState.decodedRoutePoints.forEach { boundsBuilder.include(it) }
                cameraPositionState.animate(
                    update = CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 140),
                    durationMs = 1000
                )
            } catch (_: Exception) {
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = MapType.NORMAL),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, compassEnabled = true),
            onMapLongClick = { clickedLatLng ->
                if (!uiState.isTripActive) {
                    viewModel.selectDestinationAndFetchRoute(clickedLatLng, googleMapsApiKey)
                }
            }
        ) {
            TileOverlay(tileProvider = freeStreetTileProvider, transparency = 0f)

            Marker(
                state = MarkerState(position = uiState.originLocation),
                title = "نقطة الانطلاق"
            )

            uiState.destinationLocation?.let { dest ->
                Marker(
                    state = MarkerState(position = dest),
                    title = "الوجهة المحددة",
                    snippet = "${uiState.distanceText} (${uiState.durationText})"
                )
            }

            if (uiState.decodedRoutePoints.isNotEmpty()) {
                Polyline(
                    points = uiState.decodedRoutePoints,
                    color = if (uiState.isTripActive) Color(0x3300E676) else Color(0x332979FF),
                    width = 95f,
                    startCap = RoundCap(),
                    endCap = RoundCap(),
                    jointType = JointType.ROUND,
                    geodesic = true
                )

                Polyline(
                    points = uiState.decodedRoutePoints,
                    color = if (uiState.isTripActive) Color(0xFF00C853) else Color(0xFF1565C0),
                    width = 14f,
                    startCap = RoundCap(),
                    endCap = RoundCap(),
                    jointType = JointType.ROUND,
                    geodesic = true
                )
            }
        }

        if (!uiState.isTripActive) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .align(Alignment.Center)
                    .background(Color(0xFFD50000), shape = CircleShape)
            )
        }

        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (uiState.decodedRoutePoints.isEmpty()) {
                    Text(
                        text = "📍 حرّك الخريطة أو اضغط على الزر بالأسفل لإنشاء السياج الجغرافي الآمن",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val targetCenter = cameraPositionState.position.target
                            val dest = if (
                                targetCenter.latitude == uiState.originLocation.latitude &&
                                targetCenter.longitude == uiState.originLocation.longitude
                            ) {
                                LatLng(uiState.originLocation.latitude + 0.025, uiState.originLocation.longitude + 0.020)
                            } else {
                                targetCenter
                            }
                            viewModel.selectDestinationAndFetchRoute(dest, googleMapsApiKey)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2979FF)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "🎯 تحديد نقطة الوسط كوجهة ورسم المسار",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "المسافة: ${uiState.distanceText}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "الوقت: ${uiState.durationText}",
                            color = Color(0xFF64B5F6),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "نطاق السياج الجغرافي المسموح: ${uiState.corridorRadiusMeters.toInt()} متر",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                    Slider(
                        value = uiState.corridorRadiusMeters.toFloat(),
                        onValueChange = { viewModel.updateCorridorRadius(it.toDouble()) },
                        valueRange = 100f..1500f,
                        enabled = !uiState.isTripActive
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            if (uiState.isTripActive) {
                                viewModel.stopMonitoredTrip(context)
                            } else {
                                viewModel.startMonitoredTrip(context)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isTripActive) Color(0xFFD50000) else Color(0xFF00C853)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = if (uiState.isTripActive) "⏹️ إيقاف مراقبة الرحلة الآمنة" else "🛡️ بدء مراقبة الرحلة وكشف الحوادث",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    // زر محاكاة الحادث الفوري لتجربة شاشة العد التنازلي والـ PIN أمام اللجنة
                    if (uiState.isTripActive) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                val alertIntent = Intent(context, EmergencyCountdownActivity::class.java)
                                context.startActivity(alertIntent)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "🚨 محاكاة حادث الآن (اختبار عداد 30 ثانية والـ PIN)",
                                color = Color(0xFFFF5252),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }

        if (uiState.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0xFF00E676)
            )
        }
    }
}