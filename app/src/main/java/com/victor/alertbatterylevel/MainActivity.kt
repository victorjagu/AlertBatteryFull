package com.victor.alertbatterylevel

// Importaciones oficiales requeridas para la interfaz visual y la gestión de permisos nativos
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

    // Almacenamiento local persistente para guardar los estados de los switches y textos
    private lateinit var prefs: SharedPreferences

    // Lista fija con todos los permisos que la aplicación requiere para poder operar correctamente
    private val listaPermisos = mutableListOf<String>().apply {
        // El envío de notificaciones push requiere aprobación del usuario a partir de Android 13
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Permisos de localización requeridos de forma conjunta para escanear y leer el nombre (SSID) de la red Wi-Fi
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    // Contrato asíncrono nativo para gestionar la vuelta del usuario desde la pantalla de Ajustes del Sistema
    private val ajustarConfiguracionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Al regresar de ajustes, evaluamos el ahorro de batería de forma inmediata
        verificarOptimizacionBateria()
    }

    // Contrato asíncrono nativo de Android que recibe el resultado de la ventana de permisos
    private val pedirPermisosLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultados -> 
        // Evaluamos si el usuario aceptó los permisos base de ubicación en primer plano antes de proceder con el segundo plano
        val fineGranted = resultados[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = resultados[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if ((fineGranted || coarseGranted) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Si la ubicación base está autorizada, procedemos a requerir de forma escalonada el permiso en segundo plano
            solicitarUbicacionSegundoPlano()
        } else {
            // Inmediatamente después de que el usuario interactúa con la ventana, evaluamos el ahorro de batería
            verificarOptimizacionBateria()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Inicializamos las SharedPreferences asociadas al nombre del archivo de la aplicación
        prefs = getSharedPreferences("BatteryAlertPrefs", Context.MODE_PRIVATE)
    
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // BLINDAJE ANTI-CIERRES: Delegamos el inicio de la petición a la cola de dibujo (post).
                    // Esto asegura que la app ya se cargó por completo en pantalla antes de gatillar los permisos.
                    LaunchedEffect(Unit) {
                        window.decorView.post {
                            ejecutarPeticionPermisos()
                        }
                    }
                    
                    // Inicializamos los componentes visuales principales
                    ScreenPrincipal()
                }
            }
        }
    }

    /**
     * Filtra los permisos declarados y lanza el cuadro del sistema únicamente para aquellos que no estén concedidos.
     */
    private fun ejecutarPeticionPermisos() {
        val noConcedidos = listaPermisos.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        // Si falta algún permiso en el dispositivo, desplegamos la solicitud nativa de golpe
        if (noConcedidos.isNotEmpty()) {
            pedirPermisosLauncher.launch(noConcedidos.toTypedArray())
        } else {
            // Si ya todo estaba previamente autorizado, comprobamos las restricciones del segundo plano (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                solicitarUbicacionSegundoPlano()
            } else {
                // Si ya todo estaba previamente autorizado, comprobamos las restricciones de batería
                verificarOptimizacionBateria()
            }
        }
    }

    /**
     * Muestra una explicación clara obligatoria antes de redirigir al usuario a activar "Permitir todo el tiempo".
     */
    private fun solicitarUbicacionSegundoPlano() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgPermission = Manifest.permission.ACCESS_BACKGROUND_LOCATION
            val hasBg = ContextCompat.checkSelfPermission(this, bgPermission) == PackageManager.PERMISSION_GRANTED

            if (!hasBg) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Acceso completo a la Ubicación")
                    .setMessage(
                        "Para que las alertas funcionen correctamente con tu red Wi-Fi, la aplicación necesita acceso permanente al GPS.\n\n" +
                        "En la siguiente pantalla, sigue estos pasos:\n" +
                        "1. Pulsa en 'Permisos'.\n" +
                        "2. Selecciona 'Ubicación'.\n" +
                        "3. Cambia la opción a 'Permitir todo el tiempo'."
                    )
                    .setPositiveButton("Ir a Ajustes") { _, _ ->
                        try {
                            // Redirigimos al usuario a la pantalla de Información de la App (máximo nivel permitido por Android)
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            }
                            ajustarConfiguracionLauncher.launch(intent)
                        } catch (e: Exception) {
                            verificarOptimizacionBateria()
                        }
                    }
                    .setNegativeButton("Cancelar") { dialog, _ ->
                        dialog.dismiss()
                        verificarOptimizacionBateria()
                    }
                    .setCancelable(false)
                    .show()
            } else {
                verificarOptimizacionBateria()
            }
        }
    }

    /**
     * Solicita exclusión del modo Doze de Android para prevenir que el sistema apague nuestro servicio en segundo plano.
     */
    private fun verificarOptimizacionBateria() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            // Si la aplicación no está en la lista blanca de ignorados, abrimos el menú correspondiente
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback: Si el fabricante bloquea la solicitud directa, abrimos la configuración general de batería
                    try {
                        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(fallback)
                    } catch (ex: Exception) { ex.printStackTrace() }
                }
            }
        }
    }

    /**
     * Gestiona el ciclo de vida del Foreground Service (arranque o detención) según el estado del Switch global.
     */
    private fun gestionarServicio(enable: Boolean) {
        // Almacenamos de forma persistente la elección del usuario
        prefs.edit().putBoolean("app_enabled", enable).apply()
        val serviceIntent = Intent(this, BatteryMonitorService::class.java)
        
        if (enable) {
            // Android 8.0 o superior exige el uso estricto de startForegroundService
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            // Detiene y destruye el hilo del servicio si el interruptor se apaga
            stopService(serviceIntent)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ScreenPrincipal() {
        // Variable de estado que monitoriza la activación o desactivación general del servicio
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
                        // Interruptor maestro de la aplicación ubicado en la barra superior
                        Switch(
                            checked = appEnabled,
                            onCheckedChange = { appEnabled = it; gestionarServicio(it) }
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
                    .verticalScroll(rememberScrollState()), // Habilita el desplazamiento en dispositivos compactos
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // --- SECCIÓN 1: ALERTA DE BATERÍA LLENA (MÁXIMO DE CARGA) ---
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
                                Text("Sonar siempre (ignorar silencio del móvil)")
                            }
                        }
                    }
                }

                // --- SECCIÓN 2: ALERTA DE BATERÍA BAJA (MÍNIMO DE DESCARGA) ---
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
                                Text("Sonar siempre (ignorar silencio del móvil)")
                            }
                        }
                    }
                }
                
                // --- SECCIÓN 3: CONDICIONES INTELIGENTES (FILTRADO POR WI-FI) ---
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
