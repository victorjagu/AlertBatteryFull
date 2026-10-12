package com.victor.alertbatterylevel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    // Lanzador para pedir los permisos obligatorios de Android (Notificaciones y Ubicación Fina para el SSID)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("BatteryAlertPrefs", Context.MODE_PRIVATE)
    
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Ejecuta la petición de permisos de forma asíncrona una vez que la pantalla ya existe
                    LaunchedEffect(Unit) {
                        verificarYPedirPermisos()
                    }
                    ScreenPrincipal()
                }
            }
        }
    }

    private fun verificarYPedirPermisos() {
        val permisos = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permisos.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permisos.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val noConcedidos = permisos.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (noConcedidos.isNotEmpty()) {
            requestPermissionLauncher.launch(noConcedidos.toTypedArray())
        }

        // Petición específica para ignorar la optimización de batería agresiva en OnePlus
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback seguro: Si el cuadro directo falla, abre el menú general de batería en vez de crashear
                    try {
                        val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(fallbackIntent)
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                }
            }
        }
    }

    private fun gestionarServicio(enable: Boolean) {
        prefs.edit().putBoolean("app_enabled", enable).apply()
        val serviceIntent = Intent(this, BatteryMonitorService::class.java)
        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            stopService(serviceIntent)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ScreenPrincipal() {
        var appEnabled by remember { mutableStateOf(prefs.getBoolean("app_enabled", false)) }
        val subtitulo = if (appEnabled) "Alertas de nivel de batería ENCENDIDO" else "Alertas de nivel de batería APAGADO"
        
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("BatteryAlert", style = MaterialTheme.typography.titleLarge)
                            Text(subtitulo, style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    actions = {
                        Switch(
                            checked = appEnabled,
                            onCheckedChange = {
                                appEnabled = it
                                gestionarServicio(it)
                            }
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // AQUÍ SE ENGANCHARÁ LA PARTE 2 CON LAS TARJETAS DE CONFIGURACIÓN
              
                // --- SECCIÓN 1: ALERTA DE BATERÍA LLENA ---
                var highEnabled by remember { mutableStateOf(prefs.getBoolean("high_enabled", false)) }
                var highLevel by remember { mutableStateOf(prefs.getInt("high_level", 80).toFloat()) }
                var highMsgNotif by remember { mutableStateOf(prefs.getString("high_msg_notif", "¡Batería cargada! Desconéctame por favor") ?: "") }
                var highUseBeep by remember { mutableStateOf(prefs.getBoolean("high_use_beep", false)) }
                var highVolBeep by remember { mutableStateOf(prefs.getFloat("high_vol_beep", 0.5f)) }
                var highUseTts by remember { mutableStateOf(prefs.getBoolean("high_use_tts", false)) }
                var highMsgTts by remember { mutableStateOf(prefs.getString("high_msg_tts", "Batería cargada. Desconectame, por favor") ?: "") }
                var highVolTts by remember { mutableStateOf(prefs.getFloat("high_vol_tts", 0.5f)) }
                var highUseVibrate by remember { mutableStateOf(prefs.getBoolean("high_use_vibrate", false)) }
                var highAlwaysSound by remember { mutableStateOf(prefs.getBoolean("high_always_sound", true)) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Alerta Batería Llena", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Switch(checked = highEnabled, onCheckedChange = { highEnabled = it; prefs.edit().putBoolean("high_enabled", it).apply() })
                        }
                        if (highEnabled) {
                            Text("Nivel objetivo: ${highLevel.toInt()}%")
                            Slider(value = highLevel, onValueChange = { highLevel = it }, onValueChangeFinished = { prefs.edit().putInt("high_level", highLevel.toInt()).apply() }, valueRange = 50f..100f)
                            OutlinedTextField(value = highMsgNotif, onValueChange = { highMsgNotif = it; prefs.edit().putString("high_msg_notif", it).apply() }, label = { Text("Mensaje notificación") }, modifier = Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = highUseBeep, onCheckedChange = { highUseBeep = it; prefs.edit().putBoolean("high_use_beep", it).apply() })
                                Text("Usar Pitido (Beep)")
                            }
                            if (highUseBeep) {
                                Text("Volumen Pitido")
                                Slider(value = highVolBeep, onValueChange = { highVolBeep = it }, onValueChangeFinished = { prefs.edit().putFloat("high_vol_beep", highVolBeep).apply() })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = highUseTts, onCheckedChange = { highUseTts = it; prefs.edit().putBoolean("high_use_tts", it).apply() })
                                Text("Usar Mensaje de Voz (TTS)")
                            }
                            if (highUseTts) {
                                OutlinedTextField(value = highMsgTts, onValueChange = { highMsgTts = it; prefs.edit().putString("high_msg_tts", it).apply() }, label = { Text("Texto de voz") }, modifier = Modifier.fillMaxWidth())
                                Text("Volumen Voz")
                                Slider(value = highVolTts, onValueChange = { highVolTts = it }, onValueChangeFinished = { prefs.edit().putFloat("high_vol_tts", highVolTts).apply() })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = highUseVibrate, onCheckedChange = { highUseVibrate = it; prefs.edit().putBoolean("high_use_vibrate", it).apply() })
                                Text("Vibración")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = highAlwaysSound, onCheckedChange = { highAlwaysSound = it; prefs.edit().putBoolean("high_always_sound", it).apply() })
                                Text("Sonar siempre (ignorar silencio/vibración del móvil)")
                            }
                        }
                    }
                }

                // --- SECCIÓN 2: ALERTA DE BATERÍA BAJA ---
                var lowEnabled by remember { mutableStateOf(prefs.getBoolean("low_enabled", false)) }
                var lowLevel by remember { mutableStateOf(prefs.getInt("low_level", 20).toFloat()) }
                var lowMsgNotif by remember { mutableStateOf(prefs.getString("low_msg_notif", "¡Batería baja! Conecta el cargador") ?: "") }
                var lowUseBeep by remember { mutableStateOf(prefs.getBoolean("low_use_beep", false)) }
                var lowVolBeep by remember { mutableStateOf(prefs.getFloat("low_vol_beep", 0.5f)) }
                var lowUseTts by remember { mutableStateOf(prefs.getBoolean("low_use_tts", false)) }
                var lowMsgTts by remember { mutableStateOf(prefs.getString("low_msg_tts", "Batería baja. Conéctame al cargador") ?: "") }
                var lowVolTts by remember { mutableStateOf(prefs.getFloat("low_vol_tts", 0.5f)) }
                var lowUseVibrate by remember { mutableStateOf(prefs.getBoolean("low_use_vibrate", false)) }
                var lowAlwaysSound by remember { mutableStateOf(prefs.getBoolean("low_always_sound", true)) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Alerta Batería Baja", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Switch(checked = lowEnabled, onCheckedChange = { lowEnabled = it; prefs.edit().putBoolean("low_enabled", it).apply() })
                        }
                        if (lowEnabled) {
                            Text("Nivel objetivo: ${lowLevel.toInt()}%")
                            Slider(value = lowLevel, onValueChange = { lowLevel = it }, onValueChangeFinished = { prefs.edit().putInt("low_level", lowLevel.toInt()).apply() }, valueRange = 5f..40f)
                            OutlinedTextField(value = lowMsgNotif, onValueChange = { lowMsgNotif = it; prefs.edit().putString("low_msg_notif", it).apply() }, label = { Text("Mensaje notificación") }, modifier = Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = lowUseBeep, onCheckedChange = { lowUseBeep = it; prefs.edit().putBoolean("low_use_beep", it).apply() })
                                Text("Usar Pitido (Beep)")
                            }
                            if (lowUseBeep) {
                                Text("Volumen Pitido")
                                Slider(value = lowVolBeep, onValueChange = { lowVolBeep = it }, onValueChangeFinished = { prefs.edit().putFloat("low_vol_beep", lowVolBeep).apply() })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = lowUseTts, onCheckedChange = { lowUseTts = it; prefs.edit().putBoolean("low_use_tts", it).apply() })
                                Text("Usar Mensaje de Voz (TTS)")
                            }
                            if (lowUseTts) {
                                OutlinedTextField(value = lowMsgTts, onValueChange = { lowMsgTts = it; prefs.edit().putString("low_msg_tts", it).apply() }, label = { Text("Texto de voz") }, modifier = Modifier.fillMaxWidth())
                                Text("Volumen Voz")
                                Slider(value = lowVolTts, onValueChange = { lowVolTts = it }, onValueChangeFinished = { prefs.edit().putFloat("low_vol_tts", lowVolTts).apply() })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = lowUseVibrate, onCheckedChange = { lowUseVibrate = it; prefs.edit().putBoolean("low_use_vibrate", it).apply() })
                                Text("Vibración")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = lowAlwaysSound, onCheckedChange = { lowAlwaysSound = it; prefs.edit().putBoolean("low_always_sound", it).apply() })
                                Text("Sonar siempre (ignorar silencio/vibración del móvil)")
                            }
                        }
                    }
                }
                
                // --- SECCIÓN 3: CONDICIONES INTELIGENTES (WI-FI) ---
                var wifiSsids by remember { mutableStateOf(prefs.getString("wifi_ssids", "") ?: "") }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Filtros Inteligentes de Red", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = wifiSsids,
                            onValueChange = { wifiSsids = it; prefs.edit().putString("wifi_ssids", it).apply() },
                            label = { Text("SSIDs de Wi-Fi permitidos (separados por coma)") },
                            placeholder = { Text("Ej: MiCasa_WiFi, Trabajo_WiFi") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Si se deja vacío, las alertas sonarán en cualquier red.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
