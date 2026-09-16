package com.example.securevault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import java.io.File
import java.text.DecimalFormat

class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        AppContext.context=applicationContext
        setContent{SecureVaultApp()}
    }
}
object AppContext{lateinit var context:Context}

@Composable fun SecureVaultApp(){
    val c=LocalContext.current
    var unlocked by remember{mutableStateOf(false)}
    var setup by remember{mutableStateOf(!AuthStore.hasPin(c))}
    MaterialTheme{
        when {
            setup -> SetupScreen{AuthStore.setPin(c,it);setup=false;unlocked=true}
            !unlocked -> LoginScreen{if(AuthStore.verify(c,it))unlocked=true}
            else -> VaultHome{unlocked=false}
        }
    }
}

@Composable fun PinField(value:String,onChange:(String)->Unit,label:String)=
    OutlinedTextField(value,{if(it.length<=12)onChange(it.filter(Char::isDigit))},
        label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth())

@Composable fun SetupScreen(done:(String)->Unit){
    var p by remember{mutableStateOf("")};var q by remember{mutableStateOf("")};var error by remember{mutableStateOf("")}
    AuthLayout("Создать Vault","PIN минимум 6 цифр."){
        PinField(p,{p=it},"PIN");Spacer(Modifier.height(10.dp));PinField(q,{q=it},"Повтор PIN")
        Spacer(Modifier.height(12.dp));if(error.isNotEmpty())Text(error)
        Button(onClick={when{p.length<6->error="Минимум 6 цифр";p!=q->error="PIN не совпадает";else->done(p)}},
            modifier=Modifier.fillMaxWidth()){Text("Создать")}
    }
}

@Composable fun LoginScreen(ok:(String)->Unit){
    var p by remember{mutableStateOf("")};var error by remember{mutableStateOf("")}
    AuthLayout("Secure Vault","Введите PIN для доступа."){
        PinField(p,{p=it},"PIN");Spacer(Modifier.height(12.dp))
        Button(onClick={if(AuthStore.verify(AppContext.context,p))ok(p)else error="Неверный PIN"},
            modifier=Modifier.fillMaxWidth()){Text("Открыть")}
        Spacer(Modifier.height(8.dp))
        if(canBiometric(LocalContext.current)){
            OutlinedButton(onClick={biometric(LocalContext.current,ok)},modifier=Modifier.fillMaxWidth()){
                Icon(Icons.Default.Fingerprint,null);Spacer(Modifier.width(8.dp));Text("Использовать биометрию")
            }
        }
        if(error.isNotEmpty())Text(error)
    }
}

fun canBiometric(c:Context)=BiometricManager.from(c).canAuthenticate(
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
)==BiometricManager.BIOMETRIC_SUCCESS

fun biometric(c:Context,ok:(String)->Unit){
    val a=c as ComponentActivity
    BiometricPrompt(a,object:BiometricPrompt.AuthenticationCallback(){
        override fun onAuthenticationSucceeded(r:BiometricPrompt.AuthenticationResult){ok("")}
    }).authenticate(BiometricPrompt.PromptInfo.Builder()
        .setTitle("Secure Vault").setSubtitle("Подтвердите личность").setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
}

@Composable fun AuthLayout(title:String,subtitle:String,content:@Composable ColumnScope.()->Unit){
    Column(Modifier.fillMaxSize().padding(28.dp),verticalArrangement=Arrangement.Center){
        Icon(Icons.Default.Lock,null,Modifier.size(56.dp))
        Spacer(Modifier.height(18.dp));Text(title,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
        Text(subtitle);Spacer(Modifier.height(24.dp));content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun VaultHome(lock:()->Unit){
    val c=LocalContext.current
    val repo=remember{VaultRepository(c,CryptoManager())}
    var files by remember{mutableStateOf(repo.list())}
    var search by remember{mutableStateOf("")}
    var sort by remember{mutableStateOf(0)}
    var selected by remember{mutableStateOf<VaultFile?>(null)}
    var snackbar by remember{mutableStateOf<String?>(null)}

    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->
        uris.forEach{uri->
            runCatching{
                val name=queryName(c,uri);val mime=c.contentResolver.getType(uri)?:guessMime(name)
                repo.importUri(uri,name,mime)
            }.onFailure{snackbar="Не удалось импортировать файл"}
        }
        files=repo.list()
    }
    val shown=files.filter{it.name.contains(search,true)}.let{
        when(sort){1->it.sortedBy{f->f.name.lowercase()};2->it.sortedByDescending{f->f.size};else->it.sortedBy{f->f.name.lowercase()}}
    }

    Scaffold(topBar={TopAppBar(title={Text("Secure Vault")},actions={
        IconButton(onClick=lock){Icon(Icons.Default.Lock,"Заблокировать")}
    })},floatingActionButton={
        FloatingActionButton(onClick={picker.launch(arrayOf("*/*"))}){Icon(Icons.Default.Add,"Добавить файл")}
    },snackbarHost={SnackbarHost(remember{SnackbarHostState()})}){pad->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)){
            Text("Защищённое хранилище",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text("${shown.size} файлов",style=MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(search,{search=it},Modifier.fillMaxWidth(),singleLine=true,
                placeholder={Text("Поиск…")},leadingIcon={Icon(Icons.Default.Search,null)})
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text("Сортировка",Modifier.weight(1f))
                TextButton(onClick={sort=(sort+1)%3}){Text(when(sort){0->"Имя";1->"Имя";else->"Размер"})}
            }
            LazyColumn{items(shown,key={it.id}){f->
                FileRow(f,{selected=f},{repo.delete(f);files=repo.list();snackbar="Файл удалён"})
            }}
        }
    }
    selected?.let{f->PreviewDialog(f,repo){selected=null}}
    snackbar?.let{msg->LaunchedEffect(msg){snackbar=null}}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun FileRow(f:VaultFile,open:()->Unit,delete:()->Unit){
    Card(Modifier.fillMaxWidth().padding(vertical=5.dp).combinedClickable(onClick=open,onLongClick=delete),
        shape=RoundedCornerShape(18.dp)){
        Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){
            Icon(iconFor(f.mime),null,Modifier.size(38.dp));Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)){Text(f.name,maxLines=1,fontWeight=FontWeight.SemiBold);Text(sizeText(f.size))}
            IconButton(onClick=open){Icon(Icons.Default.Visibility,"Открыть")}
            IconButton(onClick=delete){Icon(Icons.Default.Delete,"Удалить")}
        }
    }
}
fun iconFor(m:String)=when{
    m.startsWith("image/")->Icons.Default.Image
    m.startsWith("video/")->Icons.Default.PlayArrow
    m=="application/pdf"->Icons.Default.Description
    m.startsWith("audio/")->Icons.Default.MusicNote
    else->Icons.Default.InsertDriveFile
}
fun queryName(c:Context,u:Uri)=c.contentResolver.query(u,arrayOf("_display_name"),null,null,null)?.use{
    if(it.moveToFirst())it.getString(0) else "file"
}?:u.lastPathSegment?.substringAfterLast('/')?:"file"
fun guessMime(n:String)=when(n.substringAfterLast('.', "").lowercase()){
    "jpg","jpeg"->"image/jpeg";"png"->"image/png";"gif"->"image/gif";"mp4"->"video/mp4";"pdf"->"application/pdf";"mp3"->"audio/mpeg";else->"application/octet-stream"
}
fun sizeText(n:Long):String{val d=DecimalFormat("#.##");return when{n<1024->"$n B";n<1024*1024->"${d.format(n/1024.0)} KB";n<1024L*1024*1024->"${d.format(n/1024.0/1024)} MB";else->"${d.format(n/1024.0/1024/1024)} GB"}}

@Composable fun PreviewDialog(f:VaultFile,repo:VaultRepository,close:()->Unit){
    val c=LocalContext.current;var temp by remember{mutableStateOf<File?>(null)};var error by remember{mutableStateOf("")}
    DisposableEffect(Unit){onDispose{temp?.delete()}}
    AlertDialog(onDismissRequest=close,title={Text(f.name)},text={Column{
        Text("Размер: ${sizeText(f.size)}");Text("Тип: ${f.mime}")
        Spacer(Modifier.height(14.dp));if(error.isNotEmpty())Text(error)
        Button(onClick={runCatching{
            val x=File.createTempFile("sv_preview_",suffix(f.name),c.cacheDir)
            repo.decryptTo(f,x);temp=x
            val uri=FileProvider.getUriForFile(c,"${c.packageName}.files",x)
            c.startActivity(Intent(Intent.ACTION_VIEW).apply{setDataAndType(uri,f.mime);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)})
        }.onFailure{error=it.message?:"Ошибка"}){Text("Открыть")}
    }},confirmButton={TextButton(onClick=close){Text("Закрыть")}})
}
fun suffix(n:String)=n.substringAfterLast('.',".tmp").let{if(it.startsWith("."))it else ".$it"}
