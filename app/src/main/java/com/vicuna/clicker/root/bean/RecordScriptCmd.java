package com.vicuna.clicker.root.bean;

import java.util.ArrayList;
import java.util.List;

public class RecordScriptCmd {

    public Type type;
    public List<Bean> path;
    public int delayed;
    public int duration;
    /** 单条手势重复次数。旧脚本 JSON 没有该字段时 Gson 会得到 0，执行器会按 1 处理。 */
    public int repeatCount = 1;
    public String time = "";
    /** 可选自定义名称；为空时显示默认类型名（点击 / 滑动 / 轨迹手势）。 */
    public String name;
    /**
     * true 表示手动插入的普通指令（允许编辑坐标、时长等详细内容）；
     * false（旧 JSON 默认值）表示录制生成的手势（只允许改重复次数与名称）。
     */
    public boolean manual = false;

    public enum Type {
        Gesture, Delay
    }

    public RecordScriptCmd() {
    }

    public static RecordScriptCmd createGestureCMD(List<Bean> path, int duration, String time) {
        RecordScriptCmd recordScriptCmd = new RecordScriptCmd();
        recordScriptCmd.type = Type.Gesture;
        recordScriptCmd.path = path;
        recordScriptCmd.duration = duration;
        recordScriptCmd.repeatCount = 1;
        recordScriptCmd.time = time;
        recordScriptCmd.manual = false;
        return recordScriptCmd;
    }

    public static RecordScriptCmd createManualClickCMD(
            int x,
            int y,
            int duration,
            int delayed,
            int repeatCount,
            String name,
            String time
    ) {
        List<Bean> points = new ArrayList<>(1);
        points.add(new Bean(x, y));
        RecordScriptCmd cmd = new RecordScriptCmd();
        cmd.type = Type.Gesture;
        cmd.path = points;
        cmd.duration = Math.max(0, duration);
        cmd.delayed = Math.max(0, delayed);
        cmd.repeatCount = Math.max(1, repeatCount);
        cmd.setName(name);
        cmd.time = time;
        cmd.manual = true;
        return cmd;
    }

    public static RecordScriptCmd createManualSwipeCMD(
            int x0,
            int y0,
            int x1,
            int y1,
            int duration,
            int delayed,
            int repeatCount,
            String name,
            String time
    ) {
        List<Bean> points = new ArrayList<>(2);
        points.add(new Bean(x0, y0));
        points.add(new Bean(x1, y1));
        RecordScriptCmd cmd = new RecordScriptCmd();
        cmd.type = Type.Gesture;
        cmd.path = points;
        cmd.duration = Math.max(0, duration);
        cmd.delayed = Math.max(0, delayed);
        cmd.repeatCount = Math.max(1, repeatCount);
        cmd.setName(name);
        cmd.time = time;
        cmd.manual = true;
        return cmd;
    }

    public static RecordScriptCmd createDelayCMD(int Delay, String time) {
        RecordScriptCmd recordScriptCmd = new RecordScriptCmd();
        recordScriptCmd.type = Type.Delay;
        recordScriptCmd.delayed = Delay;
        recordScriptCmd.time = time;
        return recordScriptCmd;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = (name == null || name.trim().isEmpty()) ? null : name.trim();
    }

    public boolean hasCustomName() {
        return name != null && !name.trim().isEmpty();
    }

    public boolean isManualGesture() {
        return type == Type.Gesture && manual;
    }

    public boolean isManualClick() {
        return isManualGesture() && (path == null || path.size() <= 1);
    }

    public boolean isManualSwipe() {
        return isManualGesture() && path != null && path.size() >= 2;
    }

    /**
     * 录制手势的默认名称：
     * - 单点或所有点坐标相同 -> "点击"
     * - 两个点 -> "滑动"
     * - 多于两个点 -> "轨迹手势"
     */
    public String getDefaultGestureTypeName() {
        if (path == null || path.isEmpty()) {
            return "点击";
        }
        Bean first = path.get(0);
        boolean allSame = true;
        for (int i = 1; i < path.size(); i++) {
            Bean b = path.get(i);
            if (b.getX() != first.getX() || b.getY() != first.getY()) {
                allSame = false;
                break;
            }
        }
        if (allSame) {
            return "点击";
        }
        if (path.size() == 2) {
            return "滑动";
        }
        return "轨迹手势";
    }

    public String getDisplayName() {
        if (hasCustomName()) {
            return name.trim();
        }
        return getDefaultGestureTypeName();
    }

    @Override
    public String toString() {
        return "RecordScriptCmd{" +
                "type=" + type +
                ", path=" + path +
                ", delayed=" + delayed +
                ", duration=" + duration +
                ", name='" + name + '\'' +
                ", manual=" + manual +
                '}';
    }
}
