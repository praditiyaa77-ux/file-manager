package com.example.filemanagerpro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import rikka.shizuku.Shizuku
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

private const val SHIZUKU_REQUEST = 7301
private val io = Executors.newSingleThreadExecutor()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FileManagerApp() }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SHIZUKU_REQUEST) {
            Toast.makeText(this, if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED)
                "Izin Shizuku diberikan" else "Izin Shizuku belum diberikan", Toast.LENGTH_SHORT).show()
        }
    }
}

private data class ShellEntry(val name: String, val directory: Boolean)

private object ShizukuFiles {
    fun available(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    fun granted(): Boolean = try {
        available() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun run(command: String): String {
        check(available()) { "Shizuku belum berjalan. Jalankan Shizuku terlebih dahulu." }
        check(granted()) { "Izin Shizuku belum diberikan." }
        @Suppress("DEPRECATION")
        val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val error = process.errorStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        if (code != 0) throw IllegalStateException(error.ifBlank { "Perintah gagal (exit $code)" }.trim())
        return output
    }

    fun list(path: String): List<ShellEntry> {
        val raw = run("ls -1Ap -- ${q(path)}")
        return raw.lineSequence().filter { it.isNotBlank() && it != "./" && it != "../" }
            .map { line ->
                val dir = line.endsWith("/")
                ShellEntry(line.removeSuffix("/"), dir)
            }.toList()
    }
    fun mkdir(path: String, name: String) { run("mkdir -- ${q("$path/$name")}") }
    fun rename(path: String, old: String, new: String) { run("mv -- ${q("$path/$old")} ${q("$path/$new")}") }
    fun delete(path: String, name: String, recursive: Boolean) {
        val target = "$path/$name"
        run(if (recursive) "rm -rf -- ${q(target)}" else "rm -f -- ${q(target)}")
    }
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("fmpro", 0) }
    var normalRoot by remember { mutableStateOf(prefs.getString("normal_root", null)?.let(Uri::parse)) }
    var normalUri by remember { mutableStateOf(normalRoot) }
    var shizukuMode by remember { mutableStateOf(false) }
    var protectedPath by remember { mutableStateOf("/storage/emulated/0/Android/data") }
    var protectedItems by remember { mutableStateOf<List<ShellEntry>>(emptyList()) }
    var normalRefresh by remember { mutableIntStateOf(0) }
    var shellRefresh by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var actionTarget by remember { mutableStateOf<ShellEntry?>(null) }
    var shizukuStatus by remember { mutableStateOf("Memeriksa Shizuku…") }
    var shellError by remember { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                normalRoot = uri; normalUri = uri
                prefs.edit().putString("normal_root", uri.toString()).apply()
                normalRefresh++
            } catch (e: Exception) { Toast.makeText(context, e.message ?: "Gagal memberi izin", Toast.LENGTH_LONG).show() }
        }
    }

    LaunchedEffect(Unit) {
        shizukuStatus = when {
            !ShizukuFiles.available() -> "Shizuku belum aktif"
            !ShizukuFiles.granted() -> "Shizuku aktif, izin belum diberikan"
            else -> "Shizuku terhubung • UID ${Shizuku.getUid()}"
        }
    }

    val normalFolder = remember(normalUri, normalRefresh) { normalUri?.let { DocumentFile.fromTreeUri(context, it) } }
    val normalEntries = remember(normalFolder, normalRefresh, search) {
        normalFolder?.listFiles()?.filter { it.name.orEmpty().contains(search, true) }
            ?.sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase() }).orEmpty()
    }

    fun loadShell(path: String) {
        shellError = null
        io.execute {
            try {
                val list = ShizukuFiles.list(path)
                android.os.Handler(mainLooper).post {
                    protectedItems = list; protectedPath = path; shellRefresh++
                }
            } catch (e: Exception) {
                android.os.Handler(mainLooper).post { shellError = e.message ?: "Tidak dapat membaca folder"; protectedItems = emptyList() }
            }
        }
    }
    fun shellAction(block: () -> Unit) {
        io.execute {
            try { block(); loadShell(protectedPath) }
            catch (e: Exception) { android.os.Handler(mainLooper).post { shellError = e.message ?: "Operasi gagal" } }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Column {
                    Text("File Manager Pro", fontWeight = FontWeight.Bold)
                    Text(if (shizukuMode) "Advanced • $protectedPath" else "Penyimpanan biasa",
                        style = MaterialTheme.typography.labelSmall, maxLines = 1)
                } },
                navigationIcon = {
                    IconButton(onClick = {
                        if (shizukuMode) {
                            val parent = protectedPath.substringBeforeLast('/', "")
                            if (parent.startsWith("/storage/emulated/0/Android")) loadShell(parent.ifBlank { "/storage/emulated/0/Android" })
                        } else if (normalUri != null && normalRoot != null && normalUri != normalRoot) {
                            normalUri = normalFolder?.parentFile?.uri ?: normalRoot; normalRefresh++
                        }
                    }) { Icon(Icons.Default.ArrowBack, "Kembali") }
                },
                actions = {
                    IconButton(onClick = { if (shizukuMode) loadShell(protectedPath) else normalRefresh++ }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                    IconButton(onClick = { folderPicker.launch(normalRoot) }) { Icon(Icons.Default.FolderOpen, "Pilih folder") }
                }
            )
        }, floatingActionButton = {
            if (shizukuMode && ShizukuFiles.granted()) {
                FloatingActionButton(onClick = { nameInput = ""; dialog = "mkdir" }) {
                    Icon(Icons.Default.CreateNewFolder, "Folder baru")
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
                    SegmentedButton(selected = !shizukuMode, onClick = { shizukuMode = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Storage") }
                    SegmentedButton(selected = shizukuMode, onClick = {
                        shizukuMode = true
                        if (ShizukuFiles.granted()) loadShell(protectedPath)
                    }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Android/data") }
                }
                if (shizukuMode) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(shizukuStatus, style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    try {
                                        if (!ShizukuFiles.available()) {
                                            shizukuStatus = "Shizuku belum berjalan. Buka aplikasi Shizuku dan mulai layanan."
                                        } else if (!ShizukuFiles.granted()) {
                                            Shizuku.requestPermission(SHIZUKU_REQUEST)
                                            shizukuStatus = "Menunggu izin Shizuku…"
                                        } else {
                                            shizukuStatus = "Shizuku terhubung • UID ${Shizuku.getUid()}"
                                            loadShell(protectedPath)
                                        }
                                    } catch (e: Exception) { shizukuStatus = e.message ?: "Shizuku error" }
                                }) { Text(if (ShizukuFiles.granted()) "Hubungkan" else "Minta izin") }
                                OutlinedButton(onClick = { protectedPath = "/storage/emulated/0/Android/data"; loadShell(protectedPath) }) { Text("Android/data") }
                                OutlinedButton(onClick = { protectedPath = "/storage/emulated/0/Android/obb"; loadShell(protectedPath) }) { Text("Android/obb") }
                            }
                            Text("Memerlukan aplikasi Shizuku aktif dan izin diberikan. Akses ADB (UID 2000), bukan root; kemampuan tergantung ROM XOS/Android.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (shellError != null) Text(shellError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
                    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        placeholder = { Text("Filter nama") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
                    val filtered = protectedItems.filter { it.name.contains(search, true) }
                    Text("${filtered.size} item", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 4.dp))
                    LazyColumn {
                        items(filtered, key = { protectedPath + "/" + it.name }) { entry ->
                            ListItem(
                                headlineContent = { Text(entry.name, maxLines = 1) },
                                supportingContent = { Text(if (entry.directory) "Folder" else "File") },
                                leadingContent = { Icon(if (entry.directory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null) },
                                trailingContent = { IconButton(onClick = { actionTarget = entry }) { Icon(Icons.Default.MoreVert, "Aksi") } },
                                modifier = Modifier.clickable {
                                    if (entry.directory) loadShell("$protectedPath/${entry.name}")
                                    else Toast.makeText(context, "Pratinjau file shell belum tersedia; gunakan menu aksi.", Toast.LENGTH_SHORT).show()
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                } else {
                    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        placeholder = { Text("Cari file atau folder") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
                    if (normalFolder == null) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(52.dp))
                                Text("Pilih folder penyimpanan", style = MaterialTheme.typography.titleMedium)
                                Button(onClick = { folderPicker.launch(null) }) { Text("Pilih folder") }
                            }
                        }
                    } else {
                        Text("${normalEntries.size} item", style = MaterialTheme.typography.labelMedium)
                        LazyColumn {
                            items(normalEntries, key = { it.uri.toString() }) { file ->
                                ListItem(
                                    headlineContent = { Text(file.name ?: "Tanpa nama", maxLines = 1) },
                                    supportingContent = { Text(if (file.isDirectory) "Folder" else formatSize(file.length())) },
                                    leadingContent = { Icon(if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null) },
                                    trailingContent = { IconButton(onClick = { actionTarget = ShellEntry(file.uri.toString(), file.isDirectory) }) { Icon(Icons.Default.MoreVert, "Info") } },
                                    modifier = Modifier.clickable {
                                        if (file.isDirectory) { normalUri = file.uri; normalRefresh++ }
                                        else openFile(context, file)
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (dialog == "mkdir") AlertDialog(
        onDismissRequest = { dialog = "" }, title = { Text("Buat folder") },
        text = { OutlinedTextField(nameInput, { nameInput = it }, label = { Text("Nama folder") }) },
        confirmButton = { TextButton(onClick = {
            val n = nameInput.trim()
            if (n.isNotBlank() && !n.contains("/") && n != "." && n != "..") shellAction { ShizukuFiles.mkdir(protectedPath, n) }
            dialog = ""
        }) { Text("Buat") } },
        dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Batal") } }
    )

    actionTarget?.let { target ->
        if (shizukuMode) {
            AlertDialog(
                onDismissRequest = { actionTarget = null },
                title = { Text(target.name) }, text = { Text(if (target.directory) "Folder" else "File") },
                confirmButton = { TextButton(onClick = {
                    nameInput = target.name; renameTarget = target.name; actionTarget = null; dialog = "rename"
                }) { Text("Rename") } },
                dismissButton = { Row {
                    TextButton(onClick = {
                        val t = target
                        shellAction { ShizukuFiles.delete(protectedPath, t.name, t.directory) }
                        actionTarget = null
                    }) { Text("Hapus") }
                    TextButton(onClick = { actionTarget = null }) { Text("Tutup") }
                } }
            )
        } else {
            val doc = remember(target.name) { runCatching { DocumentFile.fromSingleUri(context, Uri.parse(target.name)) }.getOrNull() }
            AlertDialog(onDismissRequest = { actionTarget = null }, title = { Text("Aksi file") },
                text = { Text("Untuk operasi lanjutan, gunakan menu versi berikutnya.") },
                confirmButton = { TextButton(onClick = { actionTarget = null }) { Text("Tutup") } })
        }
    }
    if (dialog == "rename") AlertDialog(
        onDismissRequest = { dialog = "" }, title = { Text("Ganti nama") },
        text = { OutlinedTextField(nameInput, { nameInput = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val old = actionTarget?.name
            val n = nameInput.trim()
            if (old != null && n.isNotBlank() && !n.contains("/") && n != "." && n != "..")
                shellAction { ShizukuFiles.rename(protectedPath, old, n) }
            dialog = ""; actionTarget = null
        }) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = { dialog = ""; actionTarget = null; renameTarget = null }) { Text("Batal") } }
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
private fun openFile(context: android.content.Context, file: DocumentFile) {
    try {
        val mime = context.contentResolver.getType(file.uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(file.uri, mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        context.startActivity(Intent.createChooser(intent, "Buka dengan"))
    } catch (_: Exception) { Toast.makeText(context, "Tidak dapat membuka file", Toast.LENGTH_SHORT).show() }
}
