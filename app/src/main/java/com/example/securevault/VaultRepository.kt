package com.example.securevault

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

data class VaultFile(
    val id:String,
    val name:String,
    val size:Long,
    val mime:String,
    val file:File
)

class VaultRepository(private val context:Context,private val crypto:CryptoManager) {
    private val dir=File(context.filesDir,"vault").apply{mkdirs()}

    private fun meta(f:File)=File(f.absolutePath+".meta")

    fun list():List<VaultFile> =
        dir.listFiles()?.filter{it.isFile && it.extension=="svault"}?.mapNotNull{f->
            val m=meta(f)
            if(!m.exists()) null else {
                val lines=m.readLines()
                VaultFile(f.nameWithoutExtension,lines.getOrElse(0){"file"},
                    lines.getOrElse(2){"0"}.toLongOrNull()?:0,
                    lines.getOrElse(1){"application/octet-stream"},f)
            }
        }?.sortedBy{it.name.lowercase()} ?: emptyList()

    fun importUri(uri:Uri,name:String,mime:String) {
        val id=UUID.randomUUID().toString()
        val target=File(dir,"$id.svault")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                target.outputStream().use { out -> crypto.encrypt(input,out) }
            }
            val originalSize=context.contentResolver.openAssetFileDescriptor(uri,"r")?.use{it.length} ?: 0L
            meta(target).writeText("$name\n$mime\n$originalSize")
        } catch(t:Throwable) {
            target.delete();meta(target).delete();throw t
        }
    }

    fun decryptTo(v:VaultFile,target:File) =
        v.file.inputStream().use{input->target.outputStream().use{out->crypto.decrypt(input,out)}}

    fun delete(v:VaultFile){v.file.delete();meta(v.file).delete()}
}
