package com.vicuna.clicker.root.vm

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import com.vicuna.clicker.root.RecordScriptExecutor
import com.vicuna.clicker.root.bean.RecordScriptCmd
import com.vicuna.clicker.root.db.AppDatabase
import com.vicuna.clicker.root.db.RecordScriptBean
import com.vicuna.clicker.root.helper.toDate
import com.google.gson.Gson
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RecordScriptViewModel : ViewModel() {


    var data = SnapshotStateList<RecordScriptCmd>()

    val singleThreadExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    val recordScriptExecutor = RecordScriptExecutor()

    var recordScriptBean: RecordScriptBean? = null

    @Volatile
    var isRecord = false

    var lastTime = 0L


    fun playScript() {
        recordScriptExecutor.run(data)
    }


    fun addRecordScriptCmd(recordScriptCmd: RecordScriptCmd) {
        if (isRecord) {
            data.add(recordScriptCmd)
        }
    }

    fun removeRecordScriptCmd(recordScriptCmd: RecordScriptCmd) {
        data.remove(recordScriptCmd)
    }

    fun removeRecordScriptCmdAt(index: Int) {
        if (index in data.indices) {
            data.removeAt(index)
        }
    }

    fun insertRecordScriptCmd(index: Int, cmd: RecordScriptCmd) {
        if (index in 0..data.size) {
            data.add(index, cmd)
        } else {
            data.add(cmd)
        }
    }

    fun replaceRecordScriptCmd(index: Int, cmd: RecordScriptCmd) {
        if (index in data.indices) {
            data.removeAt(index)
            data.add(index, cmd)
        }
    }

    fun moveRecordScriptCmd(fromIndex: Int, toIndex: Int) {
        if (fromIndex in data.indices && toIndex in data.indices && fromIndex != toIndex) {
            val item = data.removeAt(fromIndex)
            data.add(toIndex, item)
        }
    }

    /** 校验自定义指令名称在当前脚本内是否重名（空名称表示使用默认名，不参与查重）。 */
    fun isCmdNameDuplicate(name: String?, excludeIndex: Int = -1): Boolean {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        for (i in data.indices) {
            if (i == excludeIndex) continue
            val existing = data[i].name?.trim().orEmpty()
            if (existing.isNotEmpty() && existing == trimmed) {
                return true
            }
        }
        return false
    }

    /** 校验录制脚本名称在数据库内是否已存在（排除当前正在编辑的脚本自身 id）。 */
    fun isScriptNameDuplicate(context: Context, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val currentId = recordScriptBean?.id ?: 0
        val db = AppDatabase.getInstance(context)
        val recordDuplicate = db.recordScriptDao.loadAllRecordScriptBean().any {
            it.id != currentId && it.name?.trim() == trimmed
        }
        val scriptDuplicate = db.scriptDao.loadAllScriptDataBean().any {
            it.name?.trim() == trimmed
        }
        val groupDuplicate = db.scriptGroupDao.loadAllScriptGroupBean().any {
            it.name?.trim() == trimmed
        }
        return recordDuplicate || scriptDuplicate || groupDuplicate
    }

    /** 轻量编辑：只开放延迟毫秒，避免修改手势路径/持续时间。 */
    fun updateDelay(index: Int, delayMs: Int) {
        val cmd = data.getOrNull(index) ?: return
        if (cmd.type != RecordScriptCmd.Type.Delay) return
        cmd.delayed = delayMs.coerceIn(0, 3_600_000)
        // SnapshotStateList 不观察 Java Bean 内部字段，remove/add 强制触发 Compose 刷新。
        data.removeAt(index)
        data.add(index, cmd)
    }

    /** 录制手势编辑：只允许改重复次数和自定义名称，不改动路径/坐标/执行时长。 */
    fun updateRecordedGesture(index: Int, repeatCount: Int, name: String?) {
        val cmd = data.getOrNull(index) ?: return
        if (cmd.type != RecordScriptCmd.Type.Gesture) return
        cmd.repeatCount = repeatCount.coerceIn(1, 9999)
        cmd.setName(name)
        data.removeAt(index)
        data.add(index, cmd)
    }

    /** 轻量编辑：手势只开放重复次数。旧脚本 repeatCount=0 时按 1 处理。 */
    fun updateRepeatCount(index: Int, repeatCount: Int) {
        val cmd = data.getOrNull(index) ?: return
        if (cmd.type != RecordScriptCmd.Type.Gesture) return
        cmd.repeatCount = repeatCount.coerceIn(1, 9999)
        data.removeAt(index)
        data.add(index, cmd)
    }

    fun postLastTime() {
        lastTime = SystemClock.uptimeMillis()
    }

    /**
     * 使用底层录制器根据内核事件时间戳算出的精确间隔毫秒数添加 Delay 指令。
     */
    fun addDelayTime(delayMs: Int) {
        if (isRecord) {
            val createDelayCMD = RecordScriptCmd.createDelayCMD(
                delayMs.coerceAtLeast(0),
                System.currentTimeMillis().toDate()
            )
            addRecordScriptCmd(createDelayCMD)
            lastTime = SystemClock.uptimeMillis()
        }
    }

    fun addDelayTime() {
        if (isRecord) {
            if (lastTime > 0) {
                val time = SystemClock.uptimeMillis() - lastTime
                val createDelayCMD = RecordScriptCmd.createDelayCMD(
                    time.toInt(),
                    System.currentTimeMillis().toDate()
                )
                addRecordScriptCmd(createDelayCMD)
            }

            lastTime = SystemClock.uptimeMillis()
        }
    }

    fun startRecord() {
        isRecord = true
        lastTime = SystemClock.uptimeMillis()
    }

    fun stopRecord() {
        isRecord = false
    }

    suspend fun saveScript(
        context: Context,
        name: String,
        xCoefficient: Float = 1.0f,
        yCoefficient: Float = 1.0f
    ) {
        if (recordScriptBean == null) {
            recordScriptBean = createRecordScriptCmd(name)
        } else {
            var currentTimeMillis = System.currentTimeMillis()
            recordScriptBean!!.name = name
            recordScriptBean!!.updateTime = currentTimeMillis.toDate()
            var gson = Gson()
            var toJson = gson.toJson(data)
            recordScriptBean!!.scriptJson = toJson
        }
        recordScriptBean!!.xCoefficient = xCoefficient
        recordScriptBean!!.yCoefficient = yCoefficient
        if (recordScriptBean!!.id > 0) {
            update(context, recordScriptBean!!)
        } else {
            insert(context, recordScriptBean!!)
        }
    }

    fun saveScriptBlocking(
        context: Context,
        name: String,
        xCoefficient: Float = 1.0f,
        yCoefficient: Float = 1.0f
    ) {
        if (recordScriptBean == null) {
            recordScriptBean = createRecordScriptCmd(name)
        } else {
            val currentTimeMillis = System.currentTimeMillis()
            recordScriptBean!!.name = name
            recordScriptBean!!.updateTime = currentTimeMillis.toDate()
            recordScriptBean!!.scriptJson = Gson().toJson(data)
        }
        recordScriptBean!!.xCoefficient = xCoefficient
        recordScriptBean!!.yCoefficient = yCoefficient
        if (recordScriptBean!!.id > 0) {
            update(context, recordScriptBean!!)
        } else {
            insert(context, recordScriptBean!!)
        }
    }

    private fun update(context: Context, recordScript: RecordScriptBean) {
        AppDatabase.getInstance(context).recordScriptDao.updateRecordScriptBean(recordScript)
    }

    private fun insert(context: Context, recordScript: RecordScriptBean) {
        AppDatabase.getInstance(context).recordScriptDao.insertRecordScriptBean(recordScript)
    }


    private fun createRecordScriptCmd(name: String): RecordScriptBean {
        var recordScriptBean1 = RecordScriptBean()
        var currentTimeMillis = System.currentTimeMillis()
        recordScriptBean1.name = name
        recordScriptBean1.createTime = currentTimeMillis.toDate()
        recordScriptBean1.updateTime = currentTimeMillis.toDate()
        var gson = Gson()
        var toJson = gson.toJson(data)
        recordScriptBean1.scriptJson = toJson
        return recordScriptBean1
    }


}