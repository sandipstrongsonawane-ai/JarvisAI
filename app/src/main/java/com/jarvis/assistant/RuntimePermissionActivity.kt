package com.jarvis.assistant

import android.content.Intent
import android.content.ComponentName
import android.provider.Settings
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.service.AssistantForegroundService

/**
 * Requests only the dangerous permissions needed by one pending voice action.
 * On approval, the exact structured command is sent back to the foreground service.
 */
class RuntimePermissionActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ACTION = "jarvis.permission.action"
        const val EXTRA_TARGET = "jarvis.permission.target"
        const val EXTRA_MESSAGE = "jarvis.permission.message"
        const val EXTRA_PERMISSIONS = "jarvis.permission.permissions"
        const val EXTRA_COMMAND_JSON = "jarvis.permission.command_json"
        const val EXTRA_SPECIAL_ACCESS = "jarvis.permission.special_access"
        private const val REQUEST_SPECIAL_ACCESS = 9201
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val requested = intent.getStringArrayListExtra(EXTRA_PERMISSIONS).orEmpty()
        val granted = requested.all { permission ->
            result[permission] == true ||
                checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            resumePendingCommand()
        } else {
            Toast.makeText(
                this,
                "Permission denied. Jarvis did not perform the action.",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val specialAccess = intent.getStringExtra(EXTRA_SPECIAL_ACCESS)
        if (!specialAccess.isNullOrBlank()) {
            val settingsIntent = when (specialAccess) {
                "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                "notification_listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                else -> null
            }
            if (settingsIntent == null) {
                Toast.makeText(this, "Unsupported special permission request.", Toast.LENGTH_LONG).show()
                finish()
            } else {
                startActivityForResult(settingsIntent, REQUEST_SPECIAL_ACCESS)
            }
            return
        }
        val requested = intent.getStringArrayListExtra(EXTRA_PERMISSIONS).orEmpty()
        val missing = requested.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            resumePendingCommand()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    @Deprecated("Use Activity Result APIs; retained for system Settings return flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SPECIAL_ACCESS) return
        val kind = intent.getStringExtra(EXTRA_SPECIAL_ACCESS).orEmpty()
        val granted = when (kind) {
            "accessibility" -> {
                val enabled = Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                val component = ComponentName(
                    this,
                    com.jarvis.assistant.accessibility.JarvisAccessibilityService::class.java
                ).flattenToString()
                enabled.split(':').any { it.equals(component, ignoreCase = true) } ||
                    com.jarvis.assistant.accessibility.JarvisAccessibilityService.instance != null
            }
            "notification_listener" -> {
                val enabled = Settings.Secure.getString(
                    contentResolver,
                    "enabled_notification_listeners"
                ).orEmpty()
                val component = ComponentName(
                    this,
                    com.jarvis.assistant.notifications.JarvisNotificationListener::class.java
                ).flattenToString()
                enabled.split(':').any { it.equals(component, ignoreCase = true) }
            }
            else -> false
        }
        if (granted) {
            resumePendingCommand()
        } else {
            Toast.makeText(this, "Access was not enabled. Jarvis did not run the action.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun resumePendingCommand() {
        val actionKey = intent.getStringExtra(EXTRA_ACTION)
        if (actionKey.isNullOrBlank()) {
            Toast.makeText(this, "Pending Jarvis action was missing.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val rawCommand = intent.getStringExtra(EXTRA_COMMAND_JSON)
        val command = rawCommand?.let {
            try { commandFromJson(org.json.JSONObject(it)) } catch (_: Exception) { null }
        } ?: com.jarvis.assistant.model.AssistantCommand(
            actionKey,
            intent.getStringExtra(EXTRA_TARGET),
            intent.getStringExtra(EXTRA_MESSAGE)
        )

        // A text-originated action may not have microphone permission. Do not start a
        // microphone foreground service just to execute an already-authorized action.
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            val result = com.jarvis.assistant.executor.CommandExecutor(this).executeConfirmed(command)
            Toast.makeText(this, result, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val resume = Intent(this, AssistantForegroundService::class.java).apply {
            action = AssistantForegroundService.ACTION_EXECUTE_DEFERRED_COMMAND
            putExtra(EXTRA_ACTION, command.action)
            putExtra(EXTRA_TARGET, command.target)
            putExtra(EXTRA_MESSAGE, command.message)
            putExtra(EXTRA_COMMAND_JSON, rawCommand)
        }
        ContextCompat.startForegroundService(this, resume)
        finish()
    }

    private fun commandFromJson(json: org.json.JSONObject): com.jarvis.assistant.model.AssistantCommand {
        val extra = linkedMapOf<String, String>()
        json.optJSONObject("extra")?.let { values ->
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                extra[key] = values.optString(key)
            }
        }
        val steps = json.optJSONArray("steps")?.let { array ->
            (0 until array.length()).map { commandFromJson(array.getJSONObject(it)) }
        }
        return com.jarvis.assistant.model.AssistantCommand(
            action = json.optString("action"),
            target = json.optString("target").takeUnless { it.isBlank() || it == "null" },
            message = json.optString("message").takeUnless { it.isBlank() || it == "null" },
            extra = extra,
            steps = steps
        )
    }
}
