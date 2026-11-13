package com.vicuna.clicker.root.input;

import android.os.Binder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SystemClock;
import java.lang.Process;
import android.view.*;
import java.lang.reflect.Method;
import java.io.*;
import java.util.concurrent.*;
import java.nio.*;
import java.util.Locale;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

/** Loaded by Shizuku as shell/root, never exported as an Android service. */
public final class ShizukuTouchService extends Binder {
    public static final String TOKEN="clicker.shizuku.touch.v1";
    public ShizukuTouchService() { }
    private Object manager;
    private Method inject;
    private volatile boolean recording;
    private volatile boolean stoppingRecording;
    private volatile int recordingExit;
    private String recordingId;
    private String devices() throws Exception {
        Process p=new ProcessBuilder("/system/bin/getevent","-pl").redirectErrorStream(true).start();
        FutureTask<String> read=new FutureTask<>(()->{
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int n;
            try(InputStream input=p.getInputStream()){
                while((n=input.read(buffer))!=-1){if(bytes.size()+n>131072)throw new IOException("Device output too large");bytes.write(buffer,0,n);}
            }
            return bytes.toString("UTF-8");
        });
        new Thread(read,"shizuku-device-probe").start();
        try {String output=read.get(6,TimeUnit.SECONDS);if(p.waitFor()!=0)throw new IOException(output);return output;}
        finally {p.destroy();read.cancel(true);}
    }
    private synchronized ParcelFileDescriptor record(String device,String id) throws Exception {
        if(device==null||!device.matches("/dev/input/event[0-9]+")||id==null||id.length()>80)throw new IllegalArgumentException("Invalid recording request");
        if(recording)throw new IllegalStateException("Recording already running");
        final FileInputStream input=new FileInputStream(device);
        final ParcelFileDescriptor[] pipe;
        try {pipe=ParcelFileDescriptor.createPipe();}catch(Exception e){input.close();throw e;}
        recording=true;stoppingRecording=false;recordingExit=0;recordingId=id;
        new Thread(()->{
            try(InputStream source=input;OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){
                int size=android.os.Process.is64Bit()?24:16;
                byte[] raw=new byte[size];int used=0;
                StructPollfd poll=new StructPollfd();poll.fd=input.getFD();poll.events=(short)OsConstants.POLLIN;
                // Drain already queued events when stopped, with a bounded drain window.
                long drainUntil=0;
                while(true){
                    if(stoppingRecording&&drainUntil==0)drainUntil=SystemClock.uptimeMillis()+200;
                    if(drainUntil!=0&&SystemClock.uptimeMillis()>=drainUntil)break;
                    if(Os.poll(new StructPollfd[]{poll},100)==0){if(stoppingRecording)break;continue;}
                    if((poll.revents&(OsConstants.POLLERR|OsConstants.POLLHUP|OsConstants.POLLNVAL))!=0)throw new IOException("Input device disconnected");
                    int n=source.read(raw,used,size-used);if(n<0)break;used+=n;if(used<size)continue;used=0;
                    ByteBuffer b=ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder());
                    long sec=size==24?b.getLong():(b.getInt()&0xffffffffL);
                    long usec=size==24?b.getLong():(b.getInt()&0xffffffffL);
                    int type=b.getShort()&65535,code=b.getShort()&65535,value=b.getInt();
                    String label=null;
                    if(type==0){if(code==0)label="SYN_REPORT";else if(code==3)throw new IOException("SYN_DROPPED: input events lost; retry recording");}
                    else if(type==1&&code==330)label="BTN_TOUCH";
                    else if(type==3)switch(code){case 47:label="ABS_MT_SLOT";break;case 53:label="ABS_MT_POSITION_X";break;case 54:label="ABS_MT_POSITION_Y";break;case 57:label="ABS_MT_TRACKING_ID";break;}
                    if(label!=null){String line=String.format(Locale.US,"[ %d.%06d ] %s %08x\n",sec,usec,label,value);output.write(line.getBytes("UTF-8"));output.flush();}
                }
            }catch(Exception e){
                recordingExit=1;
                android.util.Log.w("ShizukuInput","Recording failed",e);
            }finally{recording=false;}
        },"shizuku-recording-pipe").start();
        return pipe[0];
    }
    private synchronized void stopRecord(String id){
        if(recording&&(id==null||id.equals(recordingId)))stoppingRecording=true;
    }
    private synchronized int recordStatus(String id){
        if(!id.equals(recordingId))return 0;
        return recording?Integer.MIN_VALUE:recordingExit;
    }
    private void prepare() throws Exception {
        if(manager!=null)return;
        Class<?> type;
        try { type=Class.forName("android.hardware.input.InputManagerGlobal"); }
        catch(ClassNotFoundException e){type=Class.forName("android.hardware.input.InputManager");}
        Object instance=type.getMethod("getInstance").invoke(null);
        Method method=type.getMethod("injectInputEvent",InputEvent.class,int.class);
        manager=instance;inject=method;
    }
    private void event(long down,int action,float x,float y) throws Exception {
        MotionEvent ev=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { if(!Boolean.TRUE.equals(inject.invoke(manager,ev,2)))throw new SecurityException("Touch injection rejected"); }
        finally {ev.recycle();}
    }
    private synchronized void gesture(float[] xs,float[] ys,int duration) throws Exception {
        if(xs==null||ys==null||xs.length!=ys.length||xs.length<2||xs.length>4096||duration<1||duration>30000)
            throw new IllegalArgumentException("Invalid gesture");
        for(int i=0;i<xs.length;i++)if(Float.isNaN(xs[i])||Float.isInfinite(xs[i])||Float.isNaN(ys[i])||Float.isInfinite(ys[i]))throw new IllegalArgumentException("Invalid coordinate");
        prepare();long down=SystemClock.uptimeMillis();int last=0;
        try {
            event(down,MotionEvent.ACTION_DOWN,xs[0],ys[0]);
            for(int i=1;i<xs.length;i++){
                long target=down+(long)duration*i/(xs.length-1),remaining;
                while((remaining=target-SystemClock.uptimeMillis())>0)Thread.sleep(remaining);
                event(down,MotionEvent.ACTION_MOVE,xs[i],ys[i]);last=i;
            }
        } finally {event(down,MotionEvent.ACTION_UP,xs[last],ys[last]);}
    }
    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
        if(code==16777115){synchronized(this){stopRecord(null);System.exit(0);}return true;}
        if(code<1||code>6)return super.onTransact(code,data,reply,flags);
        data.enforceInterface(TOKEN);
        try {
            if(code==3){String output=devices();reply.writeNoException();reply.writeString(output);return true;}
            if(code==4){ParcelFileDescriptor pipe=record(data.readString(),data.readString());reply.writeNoException();pipe.writeToParcel(reply,Parcelable.PARCELABLE_WRITE_RETURN_VALUE);return true;}
            if(code==5){stopRecord(data.readString());reply.writeNoException();return true;}
            if(code==6){int status=recordStatus(data.readString());reply.writeNoException();reply.writeInt(status);return true;}
            if(code==2)prepare();else gesture(data.createFloatArray(),data.createFloatArray(),data.readInt());
            reply.writeNoException();
        } catch(Exception e){reply.writeException(new IllegalStateException("Shizuku input failed: "+e,e));}
        return true;
    }
}
