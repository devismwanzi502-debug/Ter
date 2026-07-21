package com.cybershell.app

import android.os.Bundle
import android.os.PowerManager
import android.content.Context
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // KEEP SCREEN ON WHILE APP IS ACTIVE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ACQUIRE WAKE LOCK TO PREVENT PROCESS SLEEPING
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CyberShell::CommandWakeLock"
        ).apply { acquire(10 * 60 * 1000L) }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    TerminalScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }
}

@Composable
fun TerminalScreen() {
    val logs = remember { mutableStateListOf("Welcome to CyberShell v1.0.0", "Type a command below...") }
    var inputText by remember { mutableStateOf("") }
    
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        // TERMINAL OUTPUT DISPLAY
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(logs) { line ->
                Text(
                    text = line,
                    color = Color.Green,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // COMMAND INPUT FIELD
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(
                color = Color.Green,
                fontFamily = FontFamily.Monospace
            ),
            singleLine = true,
            placeholder = { Text("Enter command...", color = Color.Gray) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (inputText.isNotBlank()) {
                        val userCmd = inputText
                        logs.add("cybershell$ $userCmd")
                        inputText = ""

                        // EXECUTE SHELL COMMAND ASYNCHRONOUSLY
                        coroutineScope.launch {
                            val result = executeCommand(userCmd)
                            logs.addAll(result.trim().split("\n"))
                            listState.animateScrollToItem(logs.size - 1)
                        }
                    }
                }
            )
        )
    }
}

// EXECUTION HELPER FOR SYSTEM COMMANDS
suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
    return@withContext try {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = StringBuilder()
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            output.append(line).append("\n")
        }

        process.waitFor()
        output.toString().ifEmpty { "Command executed with no output." }
    } catch (e: Exception) {
        "Error executing command: ${e.localizedMessage}"
    }
}
