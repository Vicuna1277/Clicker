package com.vicuna.clicker.root.input

import android.content.*
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ShizukuInput {
    @Volatile private var binder: IBinder? = null
    @Volatile private var connection: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null
    fun authorized(): Boolean = runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun ready(): Boolean = authorized() && binder?.isBinderAlive==true
    @Synchronized fun connect(context: Context): Boolean {
        if(ready())return true
        if(!authorized() || Looper.myLooper()==Looper.getMainLooper())return false
        close()
        val latch=CountDownLatch(1)
        val options=Shizuku.UserServiceArgs(ComponentName(context,ShizukuTouchService::class.java))
            .tag("clicker-touch").daemon(false).processNameSuffix("shizuku_input").version(347)
        val callback=object: ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { binder=service;latch.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { binder=null }
        }
        return try {
            args=options;connection=callback
            Shizuku.bindUserService(options,callback)
            if(!latch.await(6000,TimeUnit.MILLISECONDS))error("Shizuku service connection timeout")
            transact(2);true
        } catch(e:Exception){android.util.Log.w("ShizukuInput","Cannot connect",e);close();false}
    }
    private fun transact(code: Int, xs: FloatArray?=null, ys: FloatArray?=null, duration: Int=0) {
        check(authorized()) { "Shizuku permission unavailable" }
        val remote=binder ?: error("Shizuku service disconnected")
        val data=Parcel.obtain();val result=Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuTouchService.TOKEN)
            if(code==1){data.writeFloatArray(xs);data.writeFloatArray(ys);data.writeInt(duration)}
            check(remote.transact(code,data,result,0)) { "Shizuku input transaction rejected" };result.readException()
        } finally {data.recycle();result.recycle()}
    }
    fun gesture(path: Path, duration: Int) {
        val measure=PathMeasure(path,false);val length=measure.length
        val count=(duration/8+1).coerceIn(2,4096)
        val xs=FloatArray(count);val ys=FloatArray(count);val pos=FloatArray(2)
        for(i in 0 until count){check(measure.getPosTan(length*i/(count-1),pos,null)) { "Empty gesture path" };xs[i]=pos[0];ys[i]=pos[1]}
        transact(1,xs,ys,duration)
    }
    fun line(x1:Int,y1:Int,x2:Int,y2:Int,duration:Int) {
        val count=(duration/8+1).coerceIn(2,4096)
        val xs=FloatArray(count){x1+(x2.toFloat()-x1)*it/(count-1)}
        val ys=FloatArray(count){y1+(y2.toFloat()-y1)*it/(count-1)}
        transact(1,xs,ys,duration)
    }
    private fun <T> recordingCall(code:Int, device:String?=null, id:String?=null, read:(Parcel)->T):T {
        check(ready()) { "Shizuku service or permission unavailable" }
        val remote=binder ?: error("Shizuku service disconnected")
        val data=Parcel.obtain();val reply=Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuTouchService.TOKEN)
            if(code==4)data.writeString(device)
            if(code in 4..6)data.writeString(id)
            check(remote.transact(code,data,reply,0)) { "Shizuku recording request rejected" }
            reply.readException();return read(reply)
        }finally{data.recycle();reply.recycle()}
    }
    fun devices():String=recordingCall(3){it.readString().orEmpty()}
    fun record(device:String):java.lang.Process {
        val id=java.util.UUID.randomUUID().toString()
        val descriptor=recordingCall(4,device,id){ParcelFileDescriptor.CREATOR.createFromParcel(it)}
        val input=ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        return object:java.lang.Process(){
            override fun getInputStream():java.io.InputStream=input
            override fun getErrorStream():java.io.InputStream=java.io.ByteArrayInputStream(byteArrayOf())
            override fun getOutputStream():java.io.OutputStream=java.io.ByteArrayOutputStream()
            override fun exitValue():Int {
                val status=runCatching{recordingCall(6,id=id){it.readInt()}}.getOrDefault(-1)
                if(status==Int.MIN_VALUE)throw IllegalThreadStateException("Recording active")
                return status
            }
            override fun waitFor():Int {
                while(true){try{return exitValue()}catch(_:IllegalThreadStateException){Thread.sleep(10)}}
            }
            override fun destroy(){runCatching{recordingCall(5,id=id){Unit}}}
            override fun destroyForcibly():java.lang.Process {destroy();runCatching{input.close()};return this}
        }
    }
    fun close() {
        val options=args;val callback=connection
        args=null;connection=null;binder=null
        if(options!=null&&callback!=null)runCatching { Shizuku.unbindUserService(options,callback,true) }
    }
}
