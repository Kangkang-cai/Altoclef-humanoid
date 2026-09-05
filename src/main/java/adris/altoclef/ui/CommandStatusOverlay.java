package adris.altoclef.ui;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.speedrun.BeatMinecraft2Task;
import adris.altoclef.tasksystem.Task;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.lwjgl.glfw.GLFW;

import java.util.Collections;
import java.util.List;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.time.ZoneOffset;

public class CommandStatusOverlay {

    // [GamerKeybind] 速通 开始/停止 快捷键 (2026-08-23, 替代 UI 按钮):
    // Alt+Y = 开始 @gamer; Alt+U = 停止 (@stop)。UI 按钮在鼠标被游戏捕获时
    // 根本点不到, 改成键盘快捷键不受影响。onClientTick 里轮询 GLFW 按键
    // 上升沿触发 (防按住不放反复触发)。
    // 上一 tick Alt+Y / Alt+U 是否按下。
    private boolean _altYPrev = false;
    private boolean _altUPrev = false;

    //For the ingame timer
    private long _timeRunning;
    private long _lastTime = 0;

    public void render(AltoClef mod, MatrixStack matrixstack) {
        if (mod.getModSettings().shouldShowTaskChain()) {
            List<Task> tasks = Collections.emptyList();
            if (mod.getTaskRunner().getCurrentTaskChain() != null) {
                tasks = mod.getTaskRunner().getCurrentTaskChain().getTasks();
            }

            int color = 0xFFFFFFFF;
            drawTaskChain(MinecraftClient.getInstance().textRenderer, matrixstack, 0, 0, color, 10, tasks, mod);
        }
    }

    // [GamerKeybind] 每 tick 检测 Alt+Y (开始) / Alt+U (停止)。用 GLFW 直接
    // 轮询: 左/右 Alt 任一 + Y/U。只要在游戏内就响应 (不进聊天/界面判断 —
    // 键盘快捷键本来就该随时可用; 且 MC 聊天里 Alt+字母一般不会输出文本)。
    public void onClientTick(AltoClef mod) {
        if (!mod.inGame()) {
            _altYPrev = false;
            _altUPrev = false;
            return;
        }
        long handle = MinecraftClient.getInstance().getWindow().getHandle();
        boolean alt = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
        boolean altY = alt && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_Y) == GLFW.GLFW_PRESS;
        boolean altU = alt && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_U) == GLFW.GLFW_PRESS;

        if (altY && !_altYPrev) {
            startGamer(mod);
        }
        if (altU && !_altUPrev) {
            stopGamer(mod);
        }
        _altYPrev = altY;
        _altUPrev = altU;
    }

    // gamer 任务是否在跑: 用户任务链的顶层任务就是 BeatMinecraft2Task。
    private static boolean isGamerRunning(AltoClef mod) {
        Task cur = mod.getUserTaskChain().getCurrentTask();
        return cur instanceof BeatMinecraft2Task;
    }

    // Alt+Y: 开始 @gamer (已在跑则忽略, 避免重复派发)。
    private static void startGamer(AltoClef mod) {
        if (isGamerRunning(mod)) return;
        Debug.logMessage("Gamer keybind: starting gamer task");
        mod.runUserTask(new BeatMinecraft2Task(), () -> { });
    }

    // Alt+U: 停止当前用户任务 (等价于 @stop)。
    // (2026-09-05 复盘) 曾在此追加"清 LookBehavior.target / clearAllKeys /
    // onLostControl"三步清理, 用于治"终止任务后镜头慢动作"。实为误诊:
    // 真凶是用户把 MC 自带【电影模式】(平滑镜头) 绑在了 U 键上, Alt+U 顺手
    // 触发。且 force=true 的 lookAt target 在下一个 PlayerUpdateEvent PRE
    // 就被 LookBehavior 自己置空 (字节码 267-269), 根本不会残留。
    // 三步清理已全部移除; cancel() 内部 onTaskFinish 本就会 clearAllKeys。
    private static void stopGamer(AltoClef mod) {
        Debug.logMessage("Gamer keybind: stopping gamer task");
        mod.getUserTaskChain().cancel(mod);
    }

    private DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.from(ZoneOffset.of("+00:00"))); // The date formatter
    private void drawTaskChain(TextRenderer renderer, MatrixStack stack, float dx, float dy, int color, int maxLines, List<Task> tasks, AltoClef mod) {
        if (tasks.size() == 0) {
            renderer.draw(stack, " (no task running) ", dx, dy, color);
            if (_lastTime+10000 < Instant.now().toEpochMilli() && mod.getModSettings().shouldShowTimer()) {//if it doesn't run any task in 10 secs
                _timeRunning = Instant.now().toEpochMilli();//reset the timer
            }
        } else {
            float fontHeight = renderer.fontHeight;
            if (mod.getModSettings().shouldShowTimer()) { //If it's enabled
                _lastTime = Instant.now().toEpochMilli(); //keep the last time for the timer reset
                String _realTime = DATE_TIME_FORMATTER.format(Instant.now().minusMillis(_timeRunning)); //Format the running time to string
                renderer.draw(stack, "<"+_realTime+">", dx, dy, color);//Draw the timer before drawing tasks list
                dx += 8;//Do the same thing to list the tasks
                dy += fontHeight + 2;
            }
            if (tasks.size() > maxLines) {
                for (int i = 0; i < tasks.size(); ++i) {
                    // Skip over the next tasks
                    if (i == 0 || i > tasks.size() - maxLines) {
                        renderer.draw(stack, tasks.get(i).toString(), dx, dy, color);
                    } else if (i == 1) {
                        renderer.draw(stack, " ... ", dx, dy, color);
                    } else {
                        continue;
                    }
                    dx += 8;
                    dy += fontHeight + 2;
                }
            } else {
                for (Task task : tasks) {
                    renderer.draw(stack, task.toString(), dx, dy, color);
                    dx += 8;
                    dy += fontHeight + 2;
                }
            }

        }
    }
}
