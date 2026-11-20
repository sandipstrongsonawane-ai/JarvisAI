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
        val resume = Intent(this, AssistantForegroundService::class.java).apply {
            action = AssistantForegroundService.ACTION_EXECUTE_DEFERRED_COMMAND
            putExtra(EXTRA_ACTION, action)
            putExtra(EXTRA_TARGET, intent.getStringExtra(EXTRA_TARGET))
            putExtra(EXTRA_MESSAGE, intent.getStringExtra(EXTRA_MESSAGE))
        }
        ContextCompat.startForegroundService(this, resume)
        finish()
    }
}
