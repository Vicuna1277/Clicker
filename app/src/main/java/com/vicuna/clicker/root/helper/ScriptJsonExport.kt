package com.vicuna.clicker.root.helper

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** One launcher per list screen; only the temporary path goes into saved state. */
@Composable
fun rememberScriptJsonExporter(): (String,String) -> Unit {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val path=pendingPath
        if(uri==null){path?.let { File(it).delete() };pendingPath=null}
        else if(path==null)Toast.makeText(context,"导出数据已失效，请重试",Toast.LENGTH_LONG).show()
        else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val file=File(path)
                    context.contentResolver.openOutputStream(uri,"wt").use { output ->
                        if(output==null)throw IOException("无法打开保存位置")
                        file.inputStream().use { input -> input.copyTo(output) }
                    }
                }
                Toast.makeText(context,"JSON 已导出",Toast.LENGTH_SHORT).show()
            } catch(e:CancellationException){throw e}
            catch(e:Exception){Toast.makeText(context,"导出失败：${e.message}",Toast.LENGTH_LONG).show()}
            finally {File(path).delete();pendingPath=null}
        }
    }
    return { name,json ->
        if(preparing||pendingPath!=null)Toast.makeText(context,"请先完成当前导出",Toast.LENGTH_SHORT).show()
        else {
            preparing=true
            scope.launch {
                var temporary:File?=null
                try {
                    withContext(Dispatchers.IO) {
                        require(json.isNotBlank()) { "脚本数据为空" }
                        val parsed=JsonParser.parseString(json)
                        require(parsed.isJsonArray||parsed.isJsonObject) { "脚本 JSON 格式无效" }
                        temporary=File.createTempFile("script-export-",".json",context.cacheDir)
                        temporary!!.writeText(GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(parsed),Charsets.UTF_8)
                    }
                    pendingPath=temporary!!.absolutePath
                    val safeName=name.map { if(it<' '||it in "\\/:*?\"<>|") '_' else it }.joinToString("").trim().take(100).ifEmpty { "脚本" }
                    launcher.launch("$safeName.json")
                } catch(e:CancellationException){temporary?.delete();pendingPath=null;throw e}
                catch(e:Exception){temporary?.delete();pendingPath=null;Toast.makeText(context,"无法导出：${e.message}",Toast.LENGTH_LONG).show()}
                finally {preparing=false}
            }
        }
    }
}
