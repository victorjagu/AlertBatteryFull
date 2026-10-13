package com.victor.alertbatterylevel

// Importaciones necesarias para el manejo de componentes de Android y Jetpack Compose
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    // Almacenamiento local persistente para las preferencias de configuración
    private lateinit var prefs: SharedPreferences

    // Lista ordenada de los permisos que se van a solicitar uno a uno al usuario
    private val listaPermisosPorPedir = mutableListOf<String>().apply {
        // Las notificaciones push requieren permiso explícito a partir de Android 13 (Tiramisu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Permisos de ubicación necesarios para poder escanear y leer el SSID (Nombre) de la red Wi-Fi
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    // Índice controlado para saber qué permiso de la lista se está evaluando actualmente
    private var permisoActualIndex = mutableStateOf(0)
    
    // Control de visibilidad para mostrar u ocultar el cuadro de diálogo explicativo (AlertDialog)
    private var mostrarDialogoExplicativo = mutableStateOf(false)
    
    // Variable dinámica para almacenar el texto que explicará la importancia del permiso denegado
    private var textoDialogo = mutableStateOf("")

    // Contrato asíncrono para gestionar la respuesta de la solicitud de un único permiso
    private val requestSinglePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { esConcedido ->
        if (esConcedido) {
            // Si el usuario acepta el permiso actual, llamamos al evaluador para comprobar el siguiente
            verificarSiguientePermiso()
        } else {
            // Si el usuario lo rechaza, analizamos la gravedad del rechazo y preparamos el diálogo de insistencia
            evaluarDenegacionPermiso(listaPermisosPorPedir[permisoActualIndex.value])
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
                    // LaunchedEffect asegura que el escaneo de permisos arranque de forma segura una vez montada la UI
                    LaunchedEffect(Unit) {
                        verificarSiguientePermiso()
                    }

                    // Renderizamos los elementos visuales de la aplicación
                    ScreenPrincipal()

                    // Interceptador visual: Si el usuario deniega un permiso obligatorio, se levanta esta ventana emergente
                    if (mostrarDialogoExplicativo.value) {
                        AlertDialog(
                            onDismissRequest = { /* Bloqueado: Impide cerrar el diálogo haciendo clic fuera de él */ },
                            title = { Text("Permiso Obligatorio") },
                            text = { Text(textoDialogo.value) },
                            confirmButton = {
                                Button(onClick = {
                                    // Ocultamos temporalmente el diálogo para relanzar la petición
                                    mostrarDialogoExplicativo.value = false
                                    val permiso = listaPermisosPorPedir[permisoActualIndex.value]
                                    
                                    // Si shouldShowRequestPermissionRationale devuelve false, el usuario marcó "No volver a preguntar"
                                    if (!ActivityCompat.shouldShowRequestPermissionRationale(this@MainActivity, permiso)) {
                                        // Obligamos al usuario a ir a la pantalla de Ajustes del sistema para corregirlo manualmente
                                        abrirAjustesAplicacion()
                                    } else {
                                        // Si aún se puede preguntar de forma nativa, volvemos a lanzar el cuadro del sistema
                                        requestSinglePermissionLauncher.launch(permiso)
                                    }
                                }) {
                                    Text("Entendido / Configurar")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * Recorre la lista de permisos en busca de la primera posición que no esté concedida.
     */
    private fun verificarSiguientePermiso() {
        while (permisoActualIndex.value < listaPermisosPorPedir.size) {
            val permiso = listaPermisosPorPedir[permisoActualIndex.value]
            // Comprobamos si el permiso actual ya fue otorgado previamente
            if (ContextCompat.checkSelfPermission(this, permiso) != PackageManager.PERMISSION_GRANTED) {
                // Al encontrar un permiso faltante, detenemos el bucle e invocamos la solicitud visual
                requestSinglePermissionLauncher.launch(permiso)
                return
            }
            // Si el permiso ya estaba listo, incrementamos el índice para inspeccionar el siguiente elemento
            permisoActualIndex.value++
        }
        
        // Si el flujo completa con éxito toda la lista de permisos, pasa a revisar el estado de la batería
        verificarOptimizacionBateria()
    }

    /**
     * Adapta dinámicamente el texto del cuadro explicativo dependiendo de cuál permiso fue rechazado.
     */
    private fun evaluarDenegacionPermiso(permiso: String) {
        textoDialogo.value = when (permiso) {
            Manifest.permission.POST_NOTIFICATIONS -> 
                "Para poder avisarte cuando tu batería llegue al límite configurado, es totalmente necesario que permitas las notificaciones."
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION -> 
                "Android requiere el permiso de ubicación para poder leer el nombre (SSID) de tu red Wi-Fi actual. Sin esto, los filtros de red no funcionarán."
            else -> "Este permiso es necesario para el correcto funcionamiento de la aplicación."
        }
        
        // Si el sistema operativo ha deshabilitado el diálogo nativo, añadimos una advertencia adicional
        if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permiso)) {
            textoDialogo.value += "\n\nDetectamos que el diálogo está bloqueado de forma permanente. Por favor, actívalo manualmente en los Ajustes de la app."
        }
        
        // Disparamos la visibilidad del diálogo en Compose
        mostrarDialogoExplicativo.value = true
    }

    /**
     * Solicita al sistema ignorar el modo de ahorro de energía agresivo (Doze Mode) para que el servicio no muera.
     */
    private fun verificarOptimizacionBateria() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            // Si la app no está en la lista blanca de exclusiones de batería, abrimos la solicitud
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback: Si el intento directo falla o está restringido por el fabricante, abre el menú general
                    try {
                        val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(fallbackIntent)
                    } catch (ex: Exception) { ex.printStackTrace() }
                }
            }
        }
    }

    /**
     * Abre de forma directa la configuración interna de la aplicación dentro de los Ajustes de Android.
     */
    private fun abrirAjustesAplicacion() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onResume() {
        super.onResume()
        // Cuando el usuario regresa de la pantalla de Ajustes del sistema, reiniciamos el contador a 0
        // para reevaluar desde el principio si otorgó los permisos requeridos o si se saltó alguno.
        permisoActualIndex.value = 0
        verificarSiguientePermiso()
    }

    /**
     * Enciende o apaga el Foreground Service de monitoreo dependiendo de las acciones del usuario.
     */
    private fun gestionarServicio(enable: Boolean) {
        // Guardamos el estado global de activación en el almacenamiento local
        prefs.edit().putBoolean("app_enabled", enable).apply()
        val serviceIntent = Intent(this, BatteryMonitorService::class.java)
        
        if (enable) {
            // Android 8.0 (Oreo) en adelante exige iniciar servicios en segundo plano a través de startForegroundService
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            // Si el switch general se apaga, destruimos el servicio en ejecución inmediatamente
            stopService(serviceIntent)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ScreenPrincipal() {
        // Estado reactivo que lee si el servicio general está operativo o apagado
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
                        // Switch principal en la barra superior para controlar el servicio
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
                    .verticalScroll(rememberScrollState()), // Permite hacer scroll vertical
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // --- SECCIÓN 1: CONFIGURACIÓN DE ALERTA DE BATERÍA LLENA ---
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

                // --- SECCIÓN 2: CONFIGURACIÓN DE ALERTA DE BATERÍA BAJA ---
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

                // --- SECCIÓN 3: FILTROS DE COBERTURA E INTELIGENCIA WI-FI ---
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
