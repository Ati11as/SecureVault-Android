package com.example.securevault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        AppContext.context = applicationContext
        cleanupPreviewCache(applicationContext)
        setContent { SecureVaultApp() }
    }
}

object AppContext { lateinit var context: Context }

@Composable
fun SecureVaultApp() {
    val c = LocalContext.current
    var unlocked by remember { mutableStateOf(false) }
    var setup by remember { mutableStateOf(!AuthStore.hasPin(c)) }
    MaterialTheme {
        when {
            setup -> SetupScreen { AuthStore.setPin(c, it); setup = false; unlocked = true }
            !unlocked -> LoginScreen { unlocked = true }
            else -> VaultHome { unlocked = false }
        }
    }
}

@Composable
fun PinField(value: String, onChange: (String) -> Unit, label: String) =
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 12) onChange(it.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

@Composable
fun SetupScreen(done: (String) -> Unit) {
    var p by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AuthLayout("Создать Vault", "PIN минимум 6 цифр.") {
        PinField(p, { p = it }, "PIN")
        Spacer(Modifier.height(10.dp))
        PinField(q, { q = it }, "Повтор PIN")
        Spacer(Modifier.height(12.dp))
        if (error.isNotEmpty()) Text(error)
        Button(
            onClick = {
                when {
                    p.length < 6 -> error = "Минимум 6 цифр"
                    p != q -> error = "PIN не совпадает"
                    else -> done(p)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Создать") }
    }
}

@Composable
fun LoginScreen(ok: (String) -> Unit) {
    val c = LocalContext.current
    var p by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AuthLayout("Secure Vault", "Введите PIN для доступа.") {
        PinField(p, { p = it }, "PIN")
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (AuthStore.verify(c, p)) ok(p) else error = "Неверный PIN"
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Открыть") }
        Spacer(Modifier.height(8.dp))
        if (canBiometric(c)) {
            OutlinedButton(
                onClick = { biometric(c, ok) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Fingerprint, null)
                Spacer(Modifier.width(8.dp))
                Text("Использовать биометрию")
            }
        }
        if (error.isNotEmpty()) Text(error)
    }
}

fun canBiometric(c: Context) = BiometricManager.from(c).canAuthenticate(
    BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
) == BiometricManager.BIOMETRIC_SUCCESS

fun biometric(c: Context, ok: (String) -> Unit) {
    val activity = c as FragmentActivity
    val prompt = BiometricPrompt(
        activity,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                ok("")
            }
        }
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Secure Vault")
        .setSubtitle("Подтвердите личность")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build()
    prompt.authenticate(info)
}

@Composable
fun AuthLayout(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lock, null, Modifier.size(56.dp))
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(subtitle)
        Spacer(Modifier.height(24.dp))
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultHome(lock: () -> Unit) {
    val c = LocalContext.current
    val repo = remember { VaultRepository(c, CryptoManager()) }
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(emptyList<VaultFile>()) }
    var search by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<VaultFile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            files = withContext(Dispatchers.IO) { repo.list() }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = "Импорт: 0/${uris.size}"
            var done = 0
            for (uri in uris) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val name = queryName(c, uri)
                        val mime = c.contentResolver.getType(uri) ?: guessMime(name)
                        repo.importUri(uri, name, mime)
                    }
                }.onFailure { status = "Ошибка: ${it.message ?: "импорт"}" }
                done++
                status = "Импорт: $done/${uris.size}"
            }
            files = withContext(Dispatchers.IO) { repo.list() }
            busy = false
            status = ""
        }
    }

    val shown = remember(files, search, sort) {
        files.filter { it.name.contains(search, true) }.let {
            when (sort) {
                1 -> it.sortedBy { f -> f.name.lowercase() }
                2 -> it.sortedByDescending { f -> f.size }
                else -> it.sortedBy { f -> f.name.lowercase() }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Secure Vault") },
                actions = {
                    IconButton(onClick = lock) { Icon(Icons.Default.Lock, "Заблокировать") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { if (!busy) picker.launch(arrayOf("*/*")) }) {
                Icon(Icons.Default.Add, "Добавить файл")
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            Text("Защищённое хранилище", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${shown.size} файлов", style = MaterialTheme.typography.bodyMedium)
            if (busy) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Поиск…") },
                leadingIcon = { Icon(Icons.Default.Search, null) }
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Сортировка", Modifier.weight(1f))
                TextButton(onClick = { sort = (sort + 1) % 3 }) {
                    Text(when (sort) { 0, 1 -> "Имя"; else -> "Размер" })
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { f ->
                    FileRow(
                        f,
                        open = { selected = f },
                        delete = {
                            scope.launch {
                                withContext(Dispatchers.IO) { repo.delete(f) }
                                invalidatePreview(c, f)
                                files = withContext(Dispatchers.IO) { repo.list() }
                            }
                        }
                    )
                }
            }
        }
    }

    selected?.let { f -> PreviewDialog(f, repo) { selected = null } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(f: VaultFile, open: () -> Unit, delete: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).combinedClickable(onClick = open, onLongClick = delete),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(iconFor(f.mime), null, Modifier.size(38.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, maxLines = 1, fontWeight = FontWeight.SemiBold)
                Text(sizeText(f.size))
            }
            IconButton(onClick = open) { Icon(Icons.Default.Visibility, "Открыть") }
            IconButton(onClick = delete) { Icon(Icons.Default.Delete, "Удалить") }
        }
    }
}

fun iconFor(m: String) = when {
    m.startsWith("image/") -> Icons.Default.Image
    m.startsWith("video/") -> Icons.Default.PlayArrow
    m == "application/pdf" -> Icons.Default.Description
    m.startsWith("audio/") -> Icons.Default.MusicNote
    else -> Icons.Default.InsertDriveFile
}

fun queryName(c: Context, u: Uri) = c.contentResolver.query(u, arrayOf("_display_name"), null, null, null)?.use {
    if (it.moveToFirst()) it.getString(0) else "file"
} ?: u.lastPathSegment?.substringAfterLast('/') ?: "file"

fun guessMime(n: String) = when (n.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "mp4" -> "video/mp4"
    "pdf" -> "application/pdf"
    "mp3" -> "audio/mpeg"
    else -> "application/octet-stream"
}

fun sizeText(n: Long): String {
    val d = DecimalFormat("#.##")
    return when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "${d.format(n / 1024.0)} KB"
        n < 1024L * 1024 * 1024 -> "${d.format(n / 1024.0 / 1024)} MB"
        else -> "${d.format(n / 1024.0 / 1024 / 1024)} GB"
    }
}

@Composable
fun PreviewDialog(f: VaultFile, repo: VaultRepository, close: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = close,
        title = { Text(f.name) },
        text = {
            Column {
                Text("Размер: ${sizeText(f.size)}")
                Text("Тип: ${f.mime}")
                Spacer(Modifier.height(14.dp))
                if (error.isNotEmpty()) Text(error)
                Button(
                    enabled = !opening,
                    onClick = {
                        scope.launch {
                            opening = true
                            error = ""
                            runCatching {
                                val x = withContext(Dispatchers.IO) {
                                    getOrCreatePreview(c, f, repo)
                                }
                                val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", x)
                                c.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, f.mime)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            }.onFailure { error = it.message ?: "Ошибка открытия файла" }
                            opening = false
                        }
                    }
                ) { Text(if (opening) "Подготовка…" else "Открыть") }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Закрыть") } }
    )
}

private fun previewDir(c: Context) = File(c.cacheDir, "vault_previews").apply { mkdirs() }

private fun getOrCreatePreview(c: Context, f: VaultFile, repo: VaultRepository): File {
    val target = File(previewDir(c), "${f.id}${suffix(f.name)}")
    if (target.exists() && target.length() > 0L) return target
    target.delete()
    return target.also { repo.decryptTo(f, it) }
}

private fun invalidatePreview(c: Context, f: VaultFile) {
    File(previewDir(c), "${f.id}${suffix(f.name)}").delete()
}

private fun cleanupPreviewCache(c: Context) {
    val dir = File(c.cacheDir, "vault_previews")
    dir.listFiles()?.forEach { it.delete() }
}

fun suffix(n: String) = n.substringAfterLast('.', ".tmp").let { if (it.startsWith(".")) it else ".${it}" }
