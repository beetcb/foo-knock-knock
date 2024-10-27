package com.example.myapplication

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

enum class NetworkAction(val value: String) {
    RECEIVE("receive"),
    HEARTBEAT("heartbeat");

    init {
        require(value == value.lowercase()) {
            "Value must be in lowercase"
        }
    }
}

object NetworkManager {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val BASE_URL = BuildConfig.SERVER_BASE_URL
    private const val TAG = "NetworkManager"

    fun sendContent(
        content: String,
        action: NetworkAction,
        onSuccess: ((String) -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encodedContent = URLEncoder.encode(content, StandardCharsets.UTF_8.toString())
                val url = "$BASE_URL?action=${action.value}&content=$encodedContent"

                val request = Request.Builder()
                    .url(url)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string() ?: ""
                        Log.d(TAG, "Success: $responseBody")
                        onSuccess?.let {
                            withContext(Dispatchers.Main) {
                                it(responseBody)
                            }
                        }
                    } else {
                        val exception = IOException("Request failed with code: ${response.code}")
                        Log.e(TAG, "Failed: ${response.code}")
                        onError?.let {
                            withContext(Dispatchers.Main) {
                                it(exception)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error", e)
                onError?.let {
                    withContext(Dispatchers.Main) {
                        it(e)
                    }
                }
            }
        }
    }
}


class MyYanYanService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val heartbeatInterval: Long = 1 * 60 * 1000

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, "DOUYIN_CHANNEL")
            .setContentTitle("[鄢鄢敲门] ✊✊✊")
            .setContentText("电话/通知 守护中")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("MyNotificationListener", "Listener connected")
        startHeartbeat()
    }


    private fun startHeartbeat() {
        serviceScope.launch {
            while (isActive) {
                try {
                    // Send heartbeat to server
                    NetworkManager.sendContent(NetworkAction.HEARTBEAT.value, NetworkAction.HEARTBEAT,
                        onSuccess = { Log.d("MyNotificationListener", "Heartbeat successful") },
                        onError = { e -> Log.e("MyNotificationListener", "Heartbeat failed", e) }
                    )
                } catch (e: Exception) {
                    Log.e("MyNotificationListener", "Heartbeat error", e)
                }
                delay(heartbeatInterval)
            }
        }
    }


    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val title = sbn.notification.extras.getString("android.title") ?: "No Title"
        val content = sbn.notification.extras.getString("android.text") ?: "No Content"
        val notificationText = "# $title $content"

        if (sbn.packageName != BuildConfig.NOTIFICATION_TARGET_APP_1) return
        if (BuildConfig.NOTIFICATION_TARGET_APP_1_USRENAMES.split(",")
                .none { title.contains(it) }
        ) return

        Log.d("MyNotificationListener", "Notification posted: $notificationText")

        val intent = Intent("com.example.myapplication.NOTIFICATION_POSTED")
        intent.putExtra("notification_text", notificationText)
        sendBroadcast(intent)

        NetworkManager.sendContent(notificationText, NetworkAction.RECEIVE)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel() // Cancel the coroutine
    }
}

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationText = intent.getStringExtra("notification_text") ?: return
        if (context is MainActivity) {
            context.viewModel.addNotification(notificationText)
        }
    }
}

@Suppress("DEPRECATION")
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            if (state == TelephonyManager.EXTRA_STATE_RINGING &&
                phoneNumber != null &&
                BuildConfig.CALL_FROM_TARGETS.split(",").contains(phoneNumber)
            ) {
                val message = "$phoneNumber 来电"
                NetworkManager.sendContent(message, NetworkAction.RECEIVE)

                context.sendBroadcast(
                    Intent("com.example.myapplication.NOTIFICATION_POSTED")
                        .putExtra("notification_text", "# $message")
                )
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    class NotificationViewModel : ViewModel() {
        private val _notifications = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
        val notifications: StateFlow<List<Pair<Long, String>>> = _notifications
        private val maxNotifications = 1000

        fun addNotification(notification: String) {
            val timestampMillis = System.currentTimeMillis()
            viewModelScope.launch {
                val currentList = _notifications.value.toMutableList()
                currentList.add(0, Pair(timestampMillis, notification))
                if (currentList.size > maxNotifications) {
                    currentList.removeAt(currentList.size - 1)
                }
                _notifications.emit(currentList)
            }
        }
    }


    // PermissionHelper类定义
    private inner class PermissionHelper(private val context: Context) {
        @RequiresApi(Build.VERSION_CODES.M)
        fun checkAndRequestPermissions() {
            checkNotificationListenerPermission()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                checkNotificationPermission()
            }
            checkBatteryOptimization()
            checkPhoneAndCallPermissions()
        }

        @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
        private fun checkNotificationListenerPermission() {
            if (!isNotificationListenerEnabled()) {
                showNotificationListenerSettings()
            }
        }

        private fun isNotificationListenerEnabled(): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            return flat?.contains(context.packageName) == true
        }

        @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
        private fun showNotificationListenerSettings() {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        @RequiresApi(Build.VERSION_CODES.O)
        private fun checkNotificationPermission() {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    showNotificationSettings()
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.O)
        private fun showNotificationSettings() {
            try {
                val intent = Intent().apply {
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, context.applicationInfo.uid)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent().apply {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }

        @RequiresApi(Build.VERSION_CODES.M)
        private fun checkBatteryOptimization() {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                showBatterySettings()
            }
        }

        @RequiresApi(Build.VERSION_CODES.M)
        fun showBatterySettings() {
            try {
                val intent = Intent().apply {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // Fallback to general settings if specific settings not available
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }

        // 检查并请求电话和通话记录权限
        private fun checkPhoneAndCallPermissions() {
            val permissions = arrayOf(
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.READ_CALL_LOG
            )

            val permissionsToRequest = permissions.filter {
                ActivityCompat.checkSelfPermission(context, it) !=
                        PackageManager.PERMISSION_GRANTED
            }.toTypedArray()

            if (permissionsToRequest.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    permissionsToRequest,
                    100
                )
            }
        }
    }

    // MainActivity的成员变量
    lateinit var viewModel: NotificationViewModel
    private lateinit var notificationReceiver: NotificationReceiver
    private lateinit var callReceiver: CallReceiver
    private var showPermissionDialog by mutableStateOf(false)
    private lateinit var permissionHelper: PermissionHelper

    // MainActivity的方法
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        permissionHelper = PermissionHelper(this)
        viewModel = ViewModelProvider(this)[NotificationViewModel::class.java]

        permissionHelper.checkAndRequestPermissions()
        createNotificationChannel()

        notificationReceiver = NotificationReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                notificationReceiver,
                IntentFilter("com.example.myapplication.NOTIFICATION_POSTED"),
                RECEIVER_EXPORTED
            )
        }

        callReceiver = CallReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                callReceiver,
                IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
                RECEIVER_EXPORTED
            )
        }

        setContent {
            MaterialTheme {
                MainScreen(viewModel) {
                    if (showPermissionDialog) {
                        NotificationPermissionDialog {
                            showPermissionDialog = false
                            permissionHelper.checkAndRequestPermissions()
                        }
                    }
                }
            }
        }

        Handler(Looper.getMainLooper()).post {
            val serviceIntent = Intent(this, MyYanYanService::class.java)
            startService(serviceIntent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "DOUYIN_CHANNEL",
            "Douyin Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Channel for Douyin notifications"
        }

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    @Composable
    private fun NotificationPermissionDialog(onConfirm: () -> Unit) {
        AlertDialog(
            onDismissRequest = { onConfirm() },
            title = { Text("需要通知权限") },
            text = { Text("此应用需要通知权限才能正常运行。请在设置中启用。") },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text("去设置")
                }
            },
            dismissButton = {
                TextButton(onClick = { onConfirm() }) {
                    Text("取消")
                }
            }
        )
    }

    @Composable
    private fun MainScreen(viewModel: NotificationViewModel, content: @Composable () -> Unit) {
        val notifications by viewModel.notifications.collectAsState()

        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {
                NotificationList(
                    notifications = notifications,
                    modifier = Modifier.fillMaxSize()
                )
                content()
            }
        }
    }

    @Composable
    private fun NotificationList(
        notifications: List<Pair<Long, String>>,
        modifier: Modifier = Modifier
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            if (notifications.isEmpty()) {
                Text(
                    text = "暂无消息",
                    modifier = Modifier.align(Alignment.Center),
                    textAlign = TextAlign.Center
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 36.dp)
                ) {
                    items(notifications) { (timestamp, notification) ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(6.dp)
                        ) {
                            Text(text = notification)
                            val formattedTime = SimpleDateFormat(
                                "HH:mm:ss",
                                Locale.getDefault()
                            ).format(Date(timestamp))
                            Text(
                                text = formattedTime,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                textAlign = TextAlign.End
                            )
                        }
                        HorizontalDivider(thickness = 1.dp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(notificationReceiver)
        unregisterReceiver(callReceiver)
    }
}
