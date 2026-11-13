package com.vicuna.clicker.root.input;

/**
 * 这个类不是 Android Service。
 * 它由 ROOT shell 通过 app_process 在独立的 uid=0 进程里启动：
 *
 * CLASSPATH=<base.apk> app_process /system/bin com.vicuna.clicker.root.input.RootNativeDaemon ...
 *
 * Java 层只负责从 APK dex 进入 native 代码；真正的 /dev/uinput 打开和事件写入
 * 都发生在 uid=0 的 native 方法中。
 */
public final class RootNativeDaemon {
    private RootNativeDaemon() {}

    private static native int nativeRun(int width, int height);

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("ERROR java args: width height nativeLibPath");
            System.out.flush();
            System.exit(2);
            return;
        }

        try {
            final int width = Integer.parseInt(args[0]);
            final int height = Integer.parseInt(args[1]);
            final String nativeLibPath = args[2];

            System.load(nativeLibPath);
            System.out.println("JAVA_READY");
            System.out.flush();

            final int exitCode = nativeRun(width, height);
            System.exit(exitCode);
        } catch (Throwable t) {
            System.out.println("ERROR java " + t.getClass().getSimpleName() + ": " + t.getMessage());
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(3);
        }
    }
}
