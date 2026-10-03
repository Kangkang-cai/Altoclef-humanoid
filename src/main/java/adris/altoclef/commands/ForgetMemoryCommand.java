package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.commandsystem.CommandFeedback;
import adris.altoclef.trackers.KnowledgeMemory;

/**
 * 作废【自动感知的结构记忆】（2026-10-03），{@link KnowledgeMemory} 的命令入口。
 *
 * <p>用法：{@code @forgetmem <type>}，type ∈
 * {@code stronghold | end_portal | nether_fortress | blaze_spawner | village |
 * ruined_portal | warped_forest | lava_lake}，{@code all} = 清空本存档全部类型。
 * 大小写不敏感。
 *
 * <p>为什么与 {@code @forget} 分开：{@code @forget} 只删<b>命名地标</b>
 * （{@code NamedLandmarks}），且原定稿（mod-bridge-landmark.md §7.2）明确写着
 * "KnowledgeMemory 永不可经命令删除"，理由是防止 AI 起名撞自动中心时误删。
 * 2026-10-03 用户拍板推翻该条：bot 能自动记住结构却没有任何手段作废 ——
 * STRONGHOLD 记忆一旦记错（投掷异常/地形噪声），跨会话一直信、每次重启白跑。
 * 故单开一个 token，两个作用域互不干扰。
 *
 * <p>本命令是<b>状态写入</b>：无任务生命期、<b>不打断主线</b>；生效范围 = 当前
 * 存档:维度，并同步重写 knowledge.log（否则重启复活）。
 */
public class ForgetMemoryCommand extends Command {

    public ForgetMemoryCommand() throws CommandException {
        super("forgetmem", "作废自动结构记忆(KnowledgeMemory)。type: " + allTypes() + "|all",
                new Arg(String.class, "type"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String token = parser.get(String.class);
        if (token == null || token.isEmpty()) {
            CommandFeedback.fail("缺 type 参数。用法 @forgetmem <type|all>");
            log("forgetmem：缺 type 参数。用法 @forgetmem <type|all>");
            finish();
            return;
        }
        KnowledgeMemory km = mod.getKnowledgeMemory();
        if (km == null) {
            CommandFeedback.fail("KnowledgeMemory 未就绪（还没进世界？）");
            log("forgetmem：KnowledgeMemory 未就绪。");
            finish();
            return;
        }
        if ("all".equalsIgnoreCase(token) || "*".equals(token)) {
            int n = km.forgetCenters(null);
            String m = "已作废本存档全部自动结构记忆（" + (n == 0 ? "本来就没有，未改动" : n + " 条") + "）。";
            CommandFeedback.ok(m);
            log("forgetmem：" + m);
            finish();
            return;
        }
        KnowledgeMemory.StructureType type = parseType(token);
        if (type == null) {
            String m = "认不出类型 —— " + token + "（可选：" + allTypes() + " / all）";
            CommandFeedback.fail(m);
            log("forgetmem：" + m);
            finish();
            return;
        }
        int n = km.forgetCenters(type);
        String m = "已作废本存档的 " + type.name() + " 记忆（" + (n == 0 ? "本来就没有，未改动" : n + " 条") + "）。";
        CommandFeedback.ok(m);
        log("forgetmem：" + m);
        finish();
    }

    private static KnowledgeMemory.StructureType parseType(String token) {
        for (KnowledgeMemory.StructureType t : KnowledgeMemory.StructureType.values()) {
            if (t.name().equalsIgnoreCase(token)) return t;
        }
        return null;
    }

    /** 全部类型名（小写，`|` 分隔），供 help 与报错提示。 */
    private static String allTypes() {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeMemory.StructureType t : KnowledgeMemory.StructureType.values()) {
            if (sb.length() > 0) sb.append('|');
            sb.append(t.name().toLowerCase());
        }
        return sb.toString();
    }
}
