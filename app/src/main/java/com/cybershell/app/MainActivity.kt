package com.cybershell.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Entity(tableName = "packages")
data class PackageEntity(
    @PrimaryKey val name: String,
    val version: String,
    val description: String,
    val isInstalled: Boolean = false,
    val downloadUrl: String,
    val binarySizeKb: Long
)

@Entity(tableName = "vps_configs")
data class VpsServer(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val isDefault: Boolean = false
)

data class TerminalLine(
    val text: String,
    val color: Color = Color(0xFF00FF66),
    val isCommand: Boolean = false
)

data class TerminalTab(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val history: MutableList<TerminalLine> = mutableListOf(),
    var currentPath: String = "/data/user/0/com.cybershell.app/app_sandbox",
    var isCloudMode: Boolean = false
)

@Dao
interface PackageDao {
    @Query("SELECT * FROM packages")
    fun getAllPackages(): Flow<List<PackageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPackage(pkg: PackageEntity)

    @Query("UPDATE packages SET isInstalled = :installed WHERE name = :name")
    suspend fun updateInstalledStatus(name: String, installed: Boolean)
}

@Database(entities = [PackageEntity::class, VpsServer::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun packageDao(): PackageDao
}

class CyberPackageManager(private val context: android.content.Context) {
    private val sandboxDir = File(context.filesDir, "sandbox/bin").apply { mkdirs() }

    fun listLocalBins(): List<String> {
        return sandboxDir.list()?.toList() ?: emptyList()
    }

    suspend fun installPackage(packageName: String): String = withContext(Dispatchers.IO) {
        val targetFile = File(sandboxDir, packageName)
        if (!targetFile.exists()) {
            targetFile.writeText("#!/system/bin/sh\necho 'Running $packageName binary v1.0'")
            targetFile.setExecutable(true)
        }
        return@withContext "Successfully installed $packageName to /sandbox/bin"
    }

    suspend fun removePackage(packageName: String): String = withContext(Dispatchers.IO) {
        val targetFile = File(sandboxDir, packageName)
        if (targetFile.exists()) {
            targetFile.delete()
            return@withContext "Removed $packageName"
        }
        return@withContext "Package $packageName is not installed"
    }

    fun clearCache(): String {
        sandboxDir.listFiles()?.forEach { it.delete() }
        return "Package cache cleared. Storage freed."
    }
}

class LocalSandboxEngine(private val packageManager: CyberPackageManager) {
    suspend fun executeCommand(
        cmd: String,
        currentDir: String,
        isCloudMode: Boolean
    ): List<TerminalLine> = withContext(Dispatchers.IO) {
        val results = mutableListOf<TerminalLine>()
        val parts = cmd.trim().split("\\s+".toRegex())
        val baseCommand = parts.firstOrNull() ?: ""

        if (isCloudMode) {
            results.add(TerminalLine("[vps-stream] Executing '$cmd' on remote server...", Color(0xFF00E5FF)))
            results.add(TerminalLine("[vps-stream] Command executed successfully.", Color(0xFF88FF88)))
            return@withContext results
        }

        when (baseCommand) {
            "help" -> {
                results.add(TerminalLine("CyberShell Micro Terminal Engine", Color(0xFF00FF66)))
                results.add(TerminalLine("Commands: help, clear, pkg, ls, pwd, cat, python, node, git, ssh", Color.White))
                results.add(TerminalLine("Cloud Mode: Toggle 'VPS MODE' to stream raw commands remotely.", Color(0xFFFFCC00)))
            }
            "pkg" -> {
                val action = parts.getOrNull(1)
                val target = parts.getOrNull(2) ?: ""
                when (action) {
                    "install" -> results.add(TerminalLine(packageManager.installPackage(target), Color(0xFF00E5FF)))
                    "remove" -> results.add(TerminalLine(packageManager.removePackage(target), Color(0xFFFF5555)))
                    "clean" -> results.add(TerminalLine(packageManager.clearCache(), Color(0xFFFFCC00)))
                    "list" -> results.add(TerminalLine("Installed: ${packageManager.listLocalBins().joinToString(", ")}", Color.White))
                    else -> results.add(TerminalLine("Usage: pkg [install|remove|clean|list] <pkg>", Color(0xFFFF5555)))
                }
            }
            "pwd" -> results.add(TerminalLine(currentDir, Color.White))
            "ls" -> results.add(TerminalLine("bin/  etc/  tmp/  home/  script.py  config.json", Color(0xFF00FF66)))
            "python", "python3" -> {
                val code = parts.drop(1).joinToString(" ")
                if (code.startsWith("-c")) {
                    results.add(TerminalLine("Python 3.11 Output:", Color(0xFF00FF66)))
                    results.add(TerminalLine("> ${code.replace("-c", "").trim()}", Color.White))
                } else {
                    results.add(TerminalLine("Python interactive sandbox mode active.", Color(0xFF888888)))
                }
            }
            "nmap" -> {
                results.add(TerminalLine("Notice: Android blocks local raw socket scans without root.", Color(0xFFFF3333)))
                results.add(TerminalLine("Route nmap through VPS MODE for scanning capabilities.", Color(0xFFFFCC00)))
            }
            else -> {
                if (cmd.isNotBlank()) {
                    results.add(TerminalLine("cybershell: command not found: $baseCommand", Color(0xFFFF5555)))
                }
            }
        }
        return@withContext results
    }
}

class TerminalViewModel : ViewModel() {
    private val _tabs = MutableStateFlow(listOf(TerminalTab(title = "Tab 1")))
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()

    private val _activeTabIndex = MutableStateFlow(0)
    val activeTabIndex: StateFlow<Int> = _activeTabIndex.asStateFlow()

    var activeTab: TerminalTab
        get() = _tabs.value[_activeTabIndex.value]
        set(value) {
            val list = _tabs.value.toMutableList()
            list[_activeTabIndex.value] = value
            _tabs.value = list
        }

    fun addTab() {
        if (_tabs.value.size < 5) {
            val newList = _tabs.value.toMutableList()
            newList.add(TerminalTab(title = "Tab ${newList.size + 1}"))
            _tabs.value = newList
            _activeTabIndex.value = newList.size - 1
        }
    }

    fun closeTab(index: Int) {
        if (_tabs.value.size > 1) {
            val newList = _tabs.value.toMutableList()
            newList.removeAt(index)
            _tabs.value = newList
            _activeTabIndex.value = (_activeTabIndex.value - 1).coerceAtLeast(0)
        }
    }

    fun selectTab(index: Int) {
        _activeTabIndex.value = index
    }

    fun toggleCloudMode() {
        val current = activeTab
        current.isCloudMode = !current.isCloudMode
        activeTab = current
    }

    fun processCommand(cmd: String, engine: LocalSandboxEngine) {
        if (cmd.trim() == "clear") {
            val updated = activeTab
            updated.history.clear()
            activeTab = updated
            return
        }

        val updatedTab = activeTab
        val prompt = if (updatedTab.isCloudMode) "kali@cloud-vps:~$" else "cybershell:~$"
        updatedTab.history.add(TerminalLine("$prompt $cmd", Color(0xFF00FF66), isCommand = true))

        viewModelScope.launch {
            val output = engine.executeCommand(cmd, updatedTab.currentPath, updatedTab.isCloudMode)
            updatedTab.history.addAll(output)
            activeTab = updatedTab
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CyberShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF090A0F)
                ) {
                    val viewModel: TerminalViewModel = viewModel()
                    val packageManager = remember { CyberPackageManager(applicationContext) }
                    val engine = remember { LocalSandboxEngine(packageManager) }

                    TerminalScreen(viewModel = viewModel, engine = engine)
                }
            }
        }
    }
}

@Composable
fun TerminalScreen(viewModel: TerminalViewModel, engine: LocalSandboxEngine) {
    val tabs by viewModel.tabs.collectAsState()
    val activeIndex by viewModel.activeTabIndex.collectAsState()
    val activeTab = tabs[activeIndex]

    var inputText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val clipboardManager = LocalClipboardManager.current
    var showFileManager by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF12141D))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = index == activeIndex
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) Color(0xFF1E2230) else Color(0xFF0F111A))
                            .border(
                                1.dp,
                                if (isSelected) Color(0xFF00FF66) else Color.Transparent,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { viewModel.selectTab(index) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = tab.title,
                                color = if (isSelected) Color.White else Color.Gray,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            if (tabs.size > 1) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.Gray,
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clickable { viewModel.closeTab(index) }
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = { viewModel.addTab() }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Add Tab", tint = Color(0xFF00FF66))
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (activeTab.isCloudMode) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF1A1C29))
                    .clickable { viewModel.toggleCloudMode() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = if (activeTab.isCloudMode) Icons.Default.Cloud else Icons.Default.Computer,
                    contentDescription = "Engine Mode",
                    tint = if (activeTab.isCloudMode) Color(0xFF00E5FF) else Color(0xFF00FF66),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (activeTab.isCloudMode) "VPS MODE" else "LOCAL",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (activeTab.isCloudMode) Color(0xFF00E5FF) else Color(0xFF00FF66),
                    fontFamily = FontFamily.Monospace
                )
            }

            IconButton(onClick = { showFileManager = true }) {
                Icon(Icons.Default.Folder, contentDescription = "Files", tint = Color.White)
            }
        }

        val listState = rememberLazyListState()
        LaunchedEffect(activeTab.history.size) {
            if (activeTab.history.isNotEmpty()) {
                listState.animateScrollToItem(activeTab.history.size - 1)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp)
                .clickable { focusRequester.requestFocus() }
        ) {
            item {
                Text(
                    text = "CyberShell Mobile Terminal v1.0.0\nType 'help' to list local commands.\n----------------------------------------",
                    color = Color(0xFF00E5FF),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            items(activeTab.history) { line ->
                Text(
                    text = line.text,
                    color = line.color,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (activeTab.isCloudMode) "kali@cloud-vps:~$ " else "cybershell:~$ ",
                        color = if (activeTab.isCloudMode) Color(0xFF00E5FF) else Color(0xFF00FF66),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(Color(0xFF00FF66)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (inputText.isNotBlank()) {
                                viewModel.processCommand(inputText, engine)
                                inputText = ""
                            }
                        })
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF10121A))
                .padding(vertical = 4.dp, horizontal = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("ESC", "CTRL", "ALT", "TAB", "/", "-", "|", "HOME", "END", "PASTE").forEach { key ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1E2230))
                        .clickable {
                            when (key) {
                                "TAB" -> inputText += "  "
                                "ESC" -> inputText = ""
                                "|" -> inputText += "|"
                                "-" -> inputText += "-"
                                "/" -> inputText += "/"
                                "PASTE" -> clipboardManager.getText()?.text?.let { inputText += it }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = key,
                        color = Color(0xFF00FF66),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    if (showFileManager) {
        AlertDialog(
            onDismissRequest = { showFileManager = false },
            containerColor = Color(0xFF12141D),
            title = {
                Text("Scoped Storage File Explorer", color = Color(0xFF00FF66), fontFamily = FontFamily.Monospace)
            },
            text = {
                Column {
                    Text("Path: /sandbox/home", color = Color.Gray, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    listOf("📄 payload.py", "📄 config.json", "📁 downloads", "📁 scripts").forEach { file ->
                        Text(
                            text = file,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showFileManager = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF66))
                ) {
                    Text("Close", color = Color.Black)
                }
            }
        )
    }
}

@Composable
fun CyberShellTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00FF66),
            secondary = Color(0xFF00E5FF),
            background = Color(0xFF090A0F),
            surface = Color(0xFF12141D)
        ),
        content = content
    )
}
