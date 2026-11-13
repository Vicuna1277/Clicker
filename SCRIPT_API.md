# Clicker 脚本调用接口

对应源码版本：**3.3.19-slim**。

Clicker 根据调用方提供的脚本名称执行一次任务；执行何种脚本、何时捕捉或击败等判断由调用方完成。接口为 Android **显式绑定 Service + Messenger**，不需要打开 Clicker 页面。

## 准备

- 安装 Clicker，建立名称唯一的脚本。
- 在 Clicker“授权”页添加调用方真实包名，例如 `ink.roco.nearby`；调试包可能是 `ink.roco.nearby.debug`，须以实际包名为准。
- 完成 ROOT 或 Shizuku 授权。执行前会初始化输入后端，默认 ROOT 优先；不会通过接口弹出 Shizuku 授权请求。
- 如需显示执行状态点，事先授予 Clicker 悬浮窗权限。接口执行期间有前台通知，通知展示受系统设置影响。

普通脚本、录制脚本可以调用。组合脚本必须恰好包含一条动作流程，否则返回 `AMBIGUOUS_GROUP`。跨三类记录中存在同名脚本时返回 `AMBIGUOUS_NAME`，不会自行选择。

## 组件与协议

显式组件：

```text
包名：com.vicuna.clicker.root
服务：com.vicuna.clicker.root.api.ScriptApiService
```

调用应用可在 Manifest 添加包可见性声明：

```xml
<queries>
    <package android:name="com.vicuna.clicker.root" />
</queries>
```

使用 `bindService`，至少指定 `Context.BIND_AUTO_CREATE`，可按调用方需要附加 `Context.BIND_IMPORTANT`。这不是广播接口，也不是启动 Activity 的入口。

请求为 `Message.what = 1`，必须设置 `replyTo`，Bundle 字段如下：

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| request_id | String | 非空白，1～128字符；建议 UUID |
| script_name | String | 去除两端空白后1～256字符，按完整名称精确匹配，区分大小写 |

回复为 `Message.what = 100`，Bundle 包含 `request_id` 和 `status`。未授权回复的 `request_id` 为空字符串。没有有效 `replyTo` 时无法收到错误回复。

## Kotlin 调用片段

以下片段展示绑定及发送方式，需放入调用方自己的生命周期管理代码中。保持 `ServiceConnection`、回复 Messenger 和绑定状态引用，处理断连并在终态后解除绑定。

```kotlin
val requestId = java.util.UUID.randomUUID().toString()
val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
    if (message.what == 100) {
        val id = message.data.getString("request_id")
        val status = message.data.getString("status")
        // UNAUTHORIZED 的 id 为空；其余回复按 requestId 关联。
        // ACCEPTED / STARTED 不是终态，不能在此时解绑。
        // 终态后处理结果，并通过保存的 connection 解除绑定。
    }
    true
})

val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        try {
            Messenger(service).send(Message.obtain(null, 1).apply {
                replyTo = replies
                data = Bundle().apply {
                    putString("request_id", requestId)
                    putString("script_name", "捕捉")
                }
            })
        } catch (e: RemoteException) {
            // 结果未知；清理绑定，不自动重试点击任务。
        }
    }
    override fun onServiceDisconnected(name: ComponentName) {
        // 不代表执行成功；结果未知，按调用方策略停止流程。
    }
    override fun onBindingDied(name: ComponentName) {
        // 清理绑定；需要时重新建立连接，不盲目重发执行任务。
    }
    override fun onNullBinding(name: ComponentName) {
        // 绑定失败，清理绑定。
    }
}

val intent = Intent().setComponent(ComponentName(
    "com.vicuna.clicker.root",
    "com.vicuna.clicker.root.api.ScriptApiService"
))
val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
// 检查 bound；对已建立的绑定，在结束或取消时调用：
// context.unbindService(connection)
```

所需类型来自 `android.content`、`android.os`。发送后不要立即解绑。调用者可由自己的 Service 持有连接，避免页面退出时过早取消任务。Android 或厂商后台限制仍可能影响双方进程。

## 状态

| status | 含义 |
| --- | --- |
| ACCEPTED | 已接受，正在查找脚本和准备输入后端；不是完成 |
| STARTED | 已准备开始执行；不是完成 |
| COMPLETED | Clicker 执行流程正常返回 |
| CANCELLED | 检测到停止、服务结束或调用授权被撤销 |
| BUSY | 手动或接口任务正在执行，不排队、不抢占 |
| NOT_FOUND | 没有对应名称的脚本 |
| AMBIGUOUS_NAME | 普通、录制或组合记录中有多个同名脚本 |
| AMBIGUOUS_GROUP | 组合脚本不恰好包含一条动作流程 |
| ROOT_UNAVAILABLE | 历史状态名：当前 ROOT / Shizuku 输入后端未就绪 |
| INVALID_REQUEST | 消息类型、请求编号或回复通道不符合要求 |
| INVALID_NAME | 脚本名称为空或超长 |
| UNAUTHORIZED | 发送方未通过包名授权校验，回复编号为空 |
| FAILED | 前台服务提升、数据解析、指令执行或输入通道等发生异常 |

只有 `ACCEPTED`、`STARTED` 是非终态。`COMPLETED` 不证明目标应用已收到每次点击，也不证明游戏捕捉成功。服务死亡、解绑、进程冻结或回调发送失败时，调用方不一定能收到终态。

## 执行与取消

- 每次请求只执行一次，使用执行器默认1倍速度，不传入手动执行页的次数、间隔和速度配置。
- 任务被接受后，全局执行互斥锁阻止新的手动/接口任务。不会抢占已有任务，也不提供任务队列。
- 普通及组合流程的指令按顺序执行；录制脚本等待每个手势返回，再进入后续指令。
- 当前没有独立的远程停止消息。单一调用者可通过解除绑定请求停止；Android 在所有调用方都解绑时调用 `onUnbind`，Clicker 才设置停止状态。一个调用者解绑、其他调用者仍绑定时，不构成可靠的单任务取消。
- 授权撤销在下一次停止检查时生效，不保证瞬间终止正在注入的手势。
- 已开始的手势通常继续到抬起，最长受当前手势约束；强杀或权限服务突然终止时不能保证释放。
- Clicker 不启动 Activity，不抢游戏焦点，不新增最近任务卡片；不会删除此前已有的任务卡片。执行期间显示前台通知和8dp状态点，有关权限缺失时状态点可能不显示。ROOT管理器提示不受接口控制。

## 授权校验

服务使用 `Message.sendingUid` 查询真实包名，不信任请求中的自报包名。默认授权列表为空，不按签名白名单校验，也没有调用方自定义签名权限要求；用户必须在 Clicker 中显式允许包名。共享 UID 时，该 UID 下的所有包名都必须被允许。

接口只接收脚本名称与请求编号，不接收脚本代码、JSON 导入或任意 shell 命令。调用方不能通过这个接口读取或编辑脚本内容。

## 请求去重与重试

以“调用 UID + request_id”查找当前 Service 实例中的历史记录，重复编号只回复已记录状态，不再次执行。完成时将历史记录限制在最多256条；不保证跨进程、服务重建或历史淘汰后的去重。

`BUSY` 没有加入历史，可稍后用同一编号重试。已记录失败的请求需要新的编号才能重新执行，但应先确定重试不会造成重复操作。断连、回调丢失或超时表示结果可能未知，不应自动换编号重试。

## 日志

- `ClickerTrace`：按 request 编号关联收发、执行位置、停止检查和状态点。
- `ScriptApi`：脚本执行异常。
- `RootInput` / `ShizukuInput`：输入初始化、注入和录制相关错误。

接口实现见 `app/src/main/java/com/vicuna/clicker/root/api/ScriptApiService.kt`，授权逻辑见同目录 `AllowedCallers.kt`。当前说明基于源码核对，不等同于完整构建或设备兼容验证。
