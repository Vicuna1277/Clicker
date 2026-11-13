package com.vicuna.clicker.root.input

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference

/**
 * 只用于少量控制命令：ROOT 探测、appops、shell input fallback。
 * 高频触摸不要走这里，高频触摸由 RootUInputTouch 的长驻进程处理。
 */
object RootShell {
    private const val TAG = "RootShell"

    data class Result(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean = false
    ) {
        val ok: Boolean get() = !timedOut && exitCode == 0
    }

    fun isRootAvailable(timeoutMs: Long = 8000): Boolean {
        val result = exec("id -u", timeoutMs)
        return result.ok && result.output.lineSequence().any { it.trim() == "0" }
    }

    fun hasUInputCommand(): Boolean {
        return exec("test -x /system/bin/uinput", 3000).ok
    }

    fun exec(command: String, timeoutMs: Long = 8000): Result {
        var process: Process? = null
        val outputRef = AtomicReference("")
        return try {
            val p = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process = p

            val readerThread = Thread({
                val text = StringBuilder()
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            text.append(line).append('\n')
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "read output failed", t)
                }
                outputRef.set(text.toString())
            }, "root-shell-reader").apply {
                isDaemon = true
                start()
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            var exitCode: Int? = null
            while (System.currentTimeMillis() < deadline) {
                try {
                    exitCode = p.exitValue()
                    break
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(20)
                }
            }

            if (exitCode == null) {
                p.destroy()
                try { readerThread.join(300) } catch (_: InterruptedException) {}
                Result(-1, outputRef.get(), timedOut = true)
            } else {
                try { readerThread.join(500) } catch (_: InterruptedException) {}
                Result(exitCode, outputRef.get(), timedOut = false)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "exec failed: $command", t)
            Result(-1, t.message.orEmpty(), timedOut = false)
        } finally {
            try { process?.inputStream?.close() } catch (_: Throwable) {}
            try { process?.errorStream?.close() } catch (_: Throwable) {}
            try { process?.outputStream?.close() } catch (_: Throwable) {}
        }
    }
}
