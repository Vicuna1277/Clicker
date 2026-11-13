package com.vicuna.clicker.root.api

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Path
import android.os.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vicuna.clicker.root.MyService
import com.vicuna.clicker.root.RecordScriptExecutor
import com.vicuna.clicker.root.ScriptExecutor
import com.vicuna.clicker.root.bean.RecordScriptCmd
import com.vicuna.clicker.root.bean.ScriptCmdBean
import com.vicuna.clicker.root.bean.ScriptGroup
import com.vicuna.clicker.root.db.AppDatabase
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Bound API; foreground notification and a small overlay only while executing. */
class ScriptApiService : Service() {
    private val executionIndicator by lazy { ExecutionIndicator(this) }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r,"clicker-api-runner") }
    private val watchdog=Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"clicker-api-watchdog") }
    private var threadWatch:java.util.concurrent.ScheduledFuture<*>?=null
    private var executionForeground=false
    private fun beginForeground(name:String) {
        val channel="clicker-script-execution"
        val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notification=if(Build.VERSION.SDK_INT>=26) {
            manager.createNotificationChannel(NotificationChannel(channel,"脚本执行",NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this,channel)
        } else Notification.Builder(this)
        startForeground(9210,notification.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Clicker 正在执行脚本").setContentText(name)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).build())
        executionForeground=true
        android.util.Log.i("ClickerTrace","execution foreground started")
    }
    private fun endForeground() {
        if(executionForeground){stopForeground(STOP_FOREGROUND_REMOVE);executionForeground=false}
    }
    private val running = AtomicBoolean(false)
    private val occupied = AtomicBoolean(false)
    private val alive = AtomicBoolean(true)
    private val history = LinkedHashMap<String, String>() // Main thread only.
    private val main = Handler(Looper.getMainLooper())
    private val gson = Gson()
    @Volatile private var traceRequest = ""
    @Volatile private var lastStep = "idle"
    @Volatile private var lastStepAt = 0L
    private fun trace(step: String) {
        lastStep=step;lastStepAt=SystemClock.uptimeMillis()
        android.util.Log.i("ClickerTrace","$traceRequest $step")
    }
    private val heartbeat=object:Runnable {
        override fun run() {
            if(!occupied.get())return
            android.util.Log.i("ClickerTrace","$traceRequest WAIT last=$lastStep elapsed_ms=${SystemClock.uptimeMillis()-lastStepAt} running=${running.get()} alive=${alive.get()}")
            main.postDelayed(this,5000)
        }
    }
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        handle(message); true
    })
    override fun onBind(intent: Intent): IBinder {
        android.util.Log.i("ClickerTrace","service bound diag=fgs-overlay-1")
        return messenger.binder
    }
    override fun onUnbind(intent: Intent): Boolean {
        android.util.Log.i("ClickerTrace","$traceRequest UNBIND running=${running.get()} last=$lastStep")
        running.set(false)
        executionIndicator.hide()
        return false
    }
    override fun onDestroy() {
        android.util.Log.i("ClickerTrace","$traceRequest DESTROY running=${running.get()} last=$lastStep")
        main.removeCallbacks(heartbeat)
        threadWatch?.cancel(false);watchdog.shutdownNow()
        endForeground()
        executionIndicator.hide()
        alive.set(false); running.set(false); worker.shutdown()
        super.onDestroy()
    }
    private fun reply(target: Messenger?, id: String, status: String) {
        if (target == null) return
        try {
            target.send(Message.obtain(null, 100).apply {
                data = Bundle().apply { putString("request_id", id); putString("status", status) }
            })
            android.util.Log.i("ClickerTrace","reply_sent request=$id status=$status")
        } catch (e: RemoteException) {
            android.util.Log.w("ClickerTrace","reply_failed request=$id status=$status",e)
        }
    }
    private fun handle(message: Message) {
        val callerUid=message.sendingUid
        if (!AllowedCallers.permits(this,callerUid)) {
            reply(message.replyTo,"","UNAUTHORIZED")
            return
        }
        val id = message.data.getString("request_id").orEmpty()
        val target = message.replyTo
        if (message.what != 1 || id.isBlank() || id.length > 128 || target == null) {
            reply(target, id, "INVALID_REQUEST"); return
        }
        val key = "$callerUid:$id"
        history[key]?.let { reply(target, id, it); return }
        val name = message.data.getString("script_name").orEmpty().trim()
        if (name.isEmpty() || name.length > 256) { reply(target,id,"INVALID_NAME"); return }
        if (!occupied.compareAndSet(false,true)) { reply(target,id,"BUSY"); return }
        if (!ExecutionGate.acquire(this)) { occupied.set(false); reply(target,id,"BUSY"); return }
        try { beginForeground(name) } catch(e:Exception) {
            android.util.Log.e("ClickerTrace","Cannot promote execution service; request=$id",e)
            ExecutionGate.release(this);occupied.set(false);reply(target,id,"FAILED");return
        }
        running.set(true)
        traceRequest="request=$id script=$name"
        trace("ACCEPTED uid=$callerUid")
        executionIndicator.show()
        main.removeCallbacks(heartbeat);main.postDelayed(heartbeat,5000)
        threadWatch?.cancel(false)
        threadWatch=watchdog.scheduleWithFixedDelay({
            android.util.Log.i("ClickerTrace","$traceRequest BG_WATCH last=$lastStep elapsed_ms=${SystemClock.uptimeMillis()-lastStepAt} running=${running.get()}")
            if(SystemClock.uptimeMillis()-lastStepAt>2000)try{
                Thread.getAllStackTraces().forEach { (thread,stack) ->
                    if(thread.name in setOf("main","clicker-api-runner","root-input-injector"))
                        android.util.Log.i("ClickerTrace","$traceRequest THREAD name=${thread.name} state=${thread.state}\n${stack.take(10).joinToString("\n")}")
                }
            }catch(e:Exception){android.util.Log.w("ClickerTrace","Cannot inspect execution threads",e)}
        },5,5,java.util.concurrent.TimeUnit.SECONDS)
        history[key] = "ACCEPTED"
        reply(target,id,"ACCEPTED")
        val callerPackages=packageManager.getPackagesForUid(callerUid)?.toSet().orEmpty()
        worker.execute {
            var outcome = "FAILED"
            val cancellationLogged=AtomicBoolean(false)
            fun active(): Boolean {
                val live=alive.get();val run=running.get()
                val allowed=callerPackages.isNotEmpty() && AllowedCallers.packages(this).containsAll(callerPackages)
                if((!live||!run||!allowed)&&cancellationLogged.compareAndSet(false,true))
                    android.util.Log.i("ClickerTrace","$traceRequest CANCEL_CHECK alive=$live running=$run allowed=$allowed last=$lastStep")
                return live&&run&&allowed
            }
            fun ordinary(commands: List<ScriptCmdBean>) {
                require(commands.isNotEmpty())
                // A separate headless adapter propagates input failures to the API.
                val iface = object : ScriptExecutor.ScriptInterFace {
                    override fun isRun() = active()
                    override fun delayedCmd(ms: Int) {
                        val end = SystemClock.uptimeMillis() + ms.coerceAtLeast(0).toLong()
                        while (active() && SystemClock.uptimeMillis() < end) Thread.sleep(10)
                    }
                    override fun clickCMD(x: Int,y: Int,duration: Int) {
                        if (active() && !MyService.myService.dispatchGestureClickAndWait(x.toFloat(),y.toFloat(),duration) { active() }) error("Input failed")
                    }
                    override fun gestureCMD(x: Int,y: Int,x1: Int,y1: Int,duration: Int) {
                        if (active() && !MyService.myService.dispatchGestureAndWait(x.toFloat(),y.toFloat(),x1.toFloat(),y1.toFloat(),duration) { active() }) error("Input failed")
                    }
                }
                // Validate loop pairs here too: legacy executor logs invalid loops and returns.
                var depth=0
                for (cmd in commands) {
                    if (cmd.action == ScriptCmdBean.ACTION_FOR) depth++
                    if (cmd.action == ScriptCmdBean.ACTION_FOR_END) { depth--; require(depth>=0) }
                }
                require(depth==0)
                trace("ordinary commands=${commands.size}")
                val executor=ScriptExecutor(iface)
                executor.setProgressListener { index,command,starting ->
                    trace("command=${index+1} action=${command.action} phase=${if(starting) "begin" else "end"} delay_ms=${command.delayed} duration_ms=${command.duration} loops=${command.frequency}")
                }
                executor.run(commands)
            }
            try {
                val db = AppDatabase.getInstance(applicationContext)
                val normal = db.scriptDao.loadAllScriptDataBean().filter { it.name?.trim()==name }
                val recorded = db.recordScriptDao.loadAllRecordScriptBean().filter { it.name?.trim()==name }
                val groups = db.scriptGroupDao.loadAllScriptGroupBean().filter { it.name?.trim()==name }
                val count=normal.size+recorded.size+groups.size
                outcome = when {
                    count==0 -> "NOT_FOUND"
                    count!=1 -> "AMBIGUOUS_NAME"
                    !active() -> "CANCELLED"
                    !MyService.ensureRoot(applicationContext) -> "ROOT_UNAVAILABLE"
                    !active() -> "CANCELLED"
                    else -> {
                        main.post { if(active()) { history[key]="STARTED"; reply(target,id,"STARTED") } }
                        when {
                            normal.isNotEmpty() -> {
                                val commands: List<ScriptCmdBean> = gson.fromJson(normal.single().scriptJson,object:TypeToken<List<ScriptCmdBean>>(){}.type)
                                ordinary(commands)
                            }
                            recorded.isNotEmpty() -> {
                                val commands: List<RecordScriptCmd> = gson.fromJson(recorded.single().scriptJson,object:TypeToken<List<RecordScriptCmd>>(){}.type)
                                require(commands.isNotEmpty())
                                var failed=false
                                val executor=RecordScriptExecutor()
                                executor.propagateFailures=true
                                executor.onProgress={trace(it)}
                                trace("record commands=${commands.size}")
                                executor.recordScriptInterface=object:RecordScriptExecutor.RecordScriptInterface {
                                    override fun isRun()=active() && !failed
                                    override fun preDispatchGesture(x:Int,y:Int)=false
                                    override fun endDispatchGesture() { }
                                    override fun dispatchGesture(position:Int,path:Path,duration:Int) {
                                        if (!MyService.myService.dispatchGestureAndWait(path,duration) { active() }) failed=true
                                    }
                                }
                                require(commands.all { it.type!=null && (it.type!=RecordScriptCmd.Type.Gesture || !it.path.isNullOrEmpty()) })
                                executor.run(commands)
                                check(!failed)
                            }
                            else -> {
                                val group=gson.fromJson(groups.single().scriptJson,ScriptGroup::class.java)
                                // Never guess which workflow a multi-workflow group should run.
                                require(group.actionScript.size==1) { "AMBIGUOUS_GROUP" }
                                val action=group.actionScript.single()
                                ordinary(action.script.map { group.actionMap[it] ?: error("Missing group command") })
                            }
                        }
                        if(active()) "COMPLETED" else "CANCELLED"
                    }
                }
            } catch (e: Exception) {
                outcome=if(e.message=="AMBIGUOUS_GROUP") "AMBIGUOUS_GROUP" else if(active()) "FAILED" else "CANCELLED"
                android.util.Log.w("ScriptApi", "Headless execution failed",e)
            } finally {
                trace("worker_exit outcome=$outcome")
                threadWatch?.cancel(false)
                ExecutionGate.release(this)
                main.post {
                    main.removeCallbacks(heartbeat)
                    endForeground()
                    executionIndicator.hide()
                    running.set(false)
                    occupied.set(false)
                    history[key]=outcome
                    while(history.size>256) history.remove(history.keys.first())
                    reply(target,id,outcome)
                }
            }
        }
    }
}
