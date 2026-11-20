package com.jarvis.assistant

import android.content.Intent
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

    private fun resumePendingCommand() {
        val action = intent.getStringExtra(EXTRA_ACTION)
        if (action.isNullOrBlank()) {
            Toast.makeText(this, "Pending Jarvis action was missing.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val rawCommand = intent.getStringExtra(EXTRA_COMMAND_JSON)
        val command = rawCommand?.let {
            try { commandFromJson(org.json.JSONObject(it)) } catch (_: Exception) { null }
        } ?: com.jarvis.assistant.model.AssistantCommand(
            action,
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
