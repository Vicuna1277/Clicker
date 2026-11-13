package com.vicuna.clicker.root;

import android.app.Application;
import android.content.Context;

import com.vicuna.clicker.root.db.AppDatabase;

public class MyApp extends Application {
    public static final String TAG="MyApp";
    private AppDatabase appDatabase;
    private Context context;

    @Override
    public void onCreate() {
        super.onCreate();
        context=this;
        appDatabase=AppDatabase.getInstance(this);

        // 这里只初始化，不在 Application.onCreate 阻塞等待 su 授权。
        // 主页启动及绑定接口执行时在工作线程调用 MyService.ensureRoot(...)
        MyService.init(this);
    }

    public AppDatabase getAppDatabase(){
        return appDatabase;
    }
}
