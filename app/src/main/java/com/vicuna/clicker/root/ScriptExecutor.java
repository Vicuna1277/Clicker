package com.vicuna.clicker.root;

import com.vicuna.clicker.root.bean.ScriptCmdBean;

import java.util.List;
import java.util.Stack;

public class ScriptExecutor {
    public interface ProgressListener { void onProgress(int index, ScriptCmdBean command, boolean starting); }
    private ProgressListener progressListener;
    public void setProgressListener(ProgressListener listener) { progressListener = listener; }
    private ScriptInterFace scriptInterFace;


    public ScriptExecutor(ScriptInterFace scriptInterFace) {
        this.scriptInterFace = scriptInterFace;
    }

    public ScriptInterFace getScriptInterFace() {
        return scriptInterFace;
    }

    public void setScriptInterFace(ScriptInterFace scriptInterFace) {
        this.scriptInterFace = scriptInterFace;
    }


    public void run(List<ScriptCmdBean> list) {
        if (list == null || scriptInterFace == null) return;
        Stack<ForStart> stack = new Stack<>();
        int[] ends = new int[list.size()];
        Stack<Integer> starts = new Stack<>();
        // Validate all loop pairs before injecting any input.
        for (int i = 0; i < list.size(); i++) {
            ScriptCmdBean cmd = list.get(i);
            if (cmd == null) { android.util.Log.w("ScriptExecutor", "Empty command at " + i); return; }
            if (cmd.getAction() == ScriptCmdBean.ACTION_FOR) starts.push(i);
            if (cmd.getAction() == ScriptCmdBean.ACTION_FOR_END) {
                if (starts.empty()) { android.util.Log.w("ScriptExecutor", "Unmatched loop end at " + i); return; }
                ends[starts.pop()] = i;
            }
        }
        if (!starts.empty()) { android.util.Log.w("ScriptExecutor", "Unmatched loop start"); return; }
        try {
            for (int i = 0; i < list.size() && scriptInterFace.isRun(); i++) {
                ScriptCmdBean cmd = list.get(i);
                int commandIndex = i;
                if (progressListener != null) progressListener.onProgress(commandIndex, cmd, true);
                try {
                if (cmd.getAction() == ScriptCmdBean.ACTION_FOR) {
                    int count = cmd.getFrequency();
                    if (count <= 0) { i = ends[i]; continue; }
                    stack.push(new ForStart(i, count - 1));
                } else if (cmd.getAction() == ScriptCmdBean.ACTION_FOR_END) {
                    ForStart loop = stack.pop();
                    if (loop.num > 0) {
                        loop.num--;
                        stack.push(loop);
                        i = loop.index;
                    }
                } else {
                    run(cmd);
                }
                } finally {
                    if (progressListener != null) progressListener.onProgress(commandIndex, cmd, false);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void run(ScriptCmdBean scriptCmdBean) throws InterruptedException {
        if (scriptCmdBean == null || scriptInterFace == null) {
            return;
        }
        if (scriptCmdBean.getAction() == ScriptCmdBean.ACTION_DELAYED) {
            scriptInterFace.delayedCmd(scriptCmdBean.getDelayed());
        } else if (scriptCmdBean.getAction() == ScriptCmdBean.ACTION_CLICK) {
            scriptInterFace.delayedCmd(scriptCmdBean.getDelayed());
            scriptInterFace.clickCMD(scriptCmdBean.getX0(), scriptCmdBean.getY0(), scriptCmdBean.getDuration());
        } else if (scriptCmdBean.getAction() == ScriptCmdBean.ACTION_GESTURE) {
            scriptInterFace.delayedCmd(scriptCmdBean.getDelayed());
            scriptInterFace.gestureCMD(scriptCmdBean.getX0(), scriptCmdBean.getY0()
                    , scriptCmdBean.getX1(), scriptCmdBean.getY1()
                    , scriptCmdBean.getDuration());
        } else if (scriptCmdBean.getAction() == ScriptCmdBean.ACTION_RANDOM_CLICK) {
            scriptInterFace.delayedCmd(scriptCmdBean.getDelayed());
            int x = Util.randomInt(scriptCmdBean.getX1(), scriptCmdBean.getX0());
            int y = Util.randomInt(scriptCmdBean.getY1(), scriptCmdBean.getY0());
            scriptInterFace.clickCMD(x, y, scriptCmdBean.getDuration());
        }
    }

    private class ForStart {
        private int index;
        private int num;

        public ForStart(int index, int num) {
            this.index = index;
            this.num = num;
        }

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public int getNum() {
            return num;
        }

        public void setNum(int num) {
            this.num = num;
        }
    }

    public interface ScriptInterFace {

        boolean isRun();

        void delayedCmd(int delayed) throws InterruptedException;

        void clickCMD(int x0, int y0, int duration) throws InterruptedException;

        void gestureCMD(int x0, int y0, int x1, int y1, int duration) throws InterruptedException;

    }

}
