package adris.altoclef.util.helpers;

import adris.altoclef.Debug;
import adris.altoclef.util.serialization.*;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Objects;
import java.util.Scanner;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Helps load settings/configuration files
 */
public class ConfigHelper {

    private static final String ALTO_FOLDER = "altoclef";

    private static File getConfigFile(String path) {
        return Paths.get(ALTO_FOLDER, path).toFile();
    }

    // For reloading
    private static final HashMap<String, Runnable> _loadedConfigs = new HashMap<>();

    public static void reloadAllConfigs() {
        for (Runnable reload : _loadedConfigs.values()) {
            reload.run();
        }
    }

    private static <T> T getConfig(String path, Supplier<T> getDefault, Class<T> classToLoad) {
        T result = getDefault.get();
        File loadFrom = getConfigFile(path);
        if (!loadFrom.exists()) {
            // (2026-09-26) 首建也走省略式保存: 全默认 → 落盘空 JSON。旧机制把
            // 当时的编译默认值整份写进文件, 源码日后调默认值时旧值残留继续覆盖
            // (beat_minecraft.json targetEyes=14 压 16、settings expedition=300
            // 压 40, 同根因两起)。
            saveConfigPruned(path, result, getDefault.get());
            return result;
        }

        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(Vec3d.class, new Vec3dDeserializer());
        module.addDeserializer(ChunkPos.class, new ChunkPosDeserializer());
        module.addDeserializer(BlockPos.class, new BlockPosDeserializer());
        mapper.registerModule(module);

        boolean failed = false;
        try {
            result = mapper.readValue(loadFrom, classToLoad);
        } catch (JsonMappingException ex) {
            Debug.logError("Failed to parse Config file of type " + classToLoad.getSimpleName() + "at " + path + ". JSON Error Message: " + ex.getMessage() + ".\n JSON Error STACK TRACE:\n\n");
            ex.printStackTrace();
            if (result instanceof IFailableConfigFile failable)
                failable.failedToLoad();
            failed = true;
        } catch (IOException e) {
            Debug.logError("Failed to read Config at " + path + ".");
            e.printStackTrace();
            if (result instanceof IFailableConfigFile failable)
                failable.failedToLoad();
            failed = true;
        }

        // Save over to include NEW settings
        // but only if a load was successful. Don't want to override user settings!
        if (!failed) {
            // (2026-09-19 23:15 覆盖可见化) 文件值会【静默】覆盖源码默认值 ——
            // 实测 beat_minecraft.json 的 targetEyes=14 把源码 16 压了数周,
            // 用户"为什么还是只收 7 根棒"排查半天, 全程无任何日志痕迹。
            // 纯播报、零行为: 不改写文件、不改判据, 只把差异打出来。
            logOverriddenFields(path, getDefault.get(), result, classToLoad);
            // (2026-09-26 根治残留覆盖) 回写时省略"值==源码默认"的字段: JSON
            // 只存用户真正改过的值, 缺失字段自然跟源码默认走。旧机制把编译默认
            // 值也整份落盘, 源码日后调默认值时旧值残留继续覆盖 (targetEyes=14
            // 压 16、expedition=300 压 40)。值==默认 ⇒ 写不写加载结果一致, 省
            // 略无损。
            saveConfigPruned(path, result, getDefault.get());
        }

        return result;
    }

    /**
     * (2026-09-19 23:15 配置覆盖可见化, 纯诊断/零行为) 播报"文件值 != 源码默认值"
     * 的字段。ConfigHelper 的设计是"文件存在即以文件为准", 所以源码里调完默认值
     * 可能毫无效果 (beat_minecraft.json 的 targetEyes=14 压住源码 16, 直接导致
     * 烈焰棒目标 7 而非 8)。此处只打日志, 让覆盖行为可见。
     */
    private static <T> void logOverriddenFields(String path, T defaults, T loaded, Class<T> classToLoad) {
        if (defaults == null || loaded == null) return;
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Field field : classToLoad.getFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            try {
                Object dv = field.get(defaults);
                Object lv = field.get(loaded);
                if (Objects.deepEquals(dv, lv)) continue;
                if (count > 0) sb.append(", ");
                sb.append(field.getName()).append("=").append(dv).append("->").append(lv);
                count++;
            } catch (IllegalAccessException ignored) {
                // getFields() 只返回 public, 理论不会发生
            }
        }
        if (count == 0) return;
        String msg = "[Config-Override] " + path + ": " + count
                + " field(s) taken from FILE (default->file): " + sb;
        Debug.logMessage(msg);
        Debug.logToFile("altoclef_diag.log", msg);
    }

    public static <T> void loadConfig(String path, Supplier<T> getDefault, Class<T> classToLoad, Consumer<T> onReload) {
        T result = getConfig(path, getDefault, classToLoad);
        _loadedConfigs.put(path, () -> onReload.accept(getConfig(path, getDefault, classToLoad)));
        onReload.accept(result);
    }

    public static <T> void saveConfig(String path, T config) {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Vec3d.class, new Vec3dSerializer());
        module.addSerializer(BlockPos.class, new BlockPosSerializer());
        module.addSerializer(ChunkPos.class, new ChunkPosSerializer());
        mapper.registerModule(module);

        File toSave = getConfigFile(path);
        if (!toSave.getParentFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            toSave.getParentFile().mkdirs();
        }

        try {
            mapper.enable(SerializationFeature.INDENT_OUTPUT);

            // Pretty print and indent arrays too.
            DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter();
            prettyPrinter.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);

            mapper.writer(prettyPrinter).writeValue(toSave, config);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * (2026-09-26 省略式保存, 根治"残留默认值覆盖源码新默认") 序列化 loaded 后
     * 逐字段与【当前源码默认】对比, 值相等的字段直接从 JSON 中剔除再落盘。
     * 语义保证: 字段值==默认 ⇒ 写不写加载结果一致, 省略无损; 源码日后调默认值,
     * 文件不再残留旧默认压着 (0926 expedition=300 压 40 事故同根因)。
     * 失败兜底: 任何异常退回整份保存 (老行为), 不丢配置。
     */
    private static <T> void saveConfigPruned(String path, T loaded, T defaults) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            SimpleModule module = new SimpleModule();
            module.addSerializer(Vec3d.class, new Vec3dSerializer());
            module.addSerializer(BlockPos.class, new BlockPosSerializer());
            module.addSerializer(ChunkPos.class, new ChunkPosSerializer());
            mapper.registerModule(module);
            com.fasterxml.jackson.databind.JsonNode loadedTree = mapper.valueToTree(loaded);
            com.fasterxml.jackson.databind.JsonNode defaultTree = mapper.valueToTree(defaults);
            if (loadedTree instanceof com.fasterxml.jackson.databind.node.ObjectNode obj
                    && defaultTree instanceof com.fasterxml.jackson.databind.node.ObjectNode defObj) {
                java.util.Iterator<String> names = obj.fieldNames();
                java.util.List<String> toRemove = new java.util.ArrayList<>();
                while (names.hasNext()) {
                    String name = names.next();
                    com.fasterxml.jackson.databind.JsonNode dv = defObj.get(name);
                    // 默认树里没有的键保留 (新增字段, 老文件兼容), 相等的剔除。
                    if (dv != null && dv.equals(obj.get(name))) {
                        toRemove.add(name);
                    }
                }
                toRemove.forEach(obj::remove);
                File toSave = getConfigFile(path);
                if (!toSave.getParentFile().exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    toSave.getParentFile().mkdirs();
                }
                mapper.enable(SerializationFeature.INDENT_OUTPUT);
                DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter();
                prettyPrinter.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
                mapper.writer(prettyPrinter).writeValue(toSave, obj);
                return;
            }
            saveConfig(path, loaded);
        } catch (Exception e) {
            // 任何意外退回整份保存, 宁可残留也不丢用户配置。
            e.printStackTrace();
            saveConfig(path, loaded);
        }
    }

    private static <T extends IListConfigFile> T getListConfig(String path, Supplier<T> getDefault) {
        T result = getDefault.get();

        result.onLoadStart();

        File loadFrom = getConfigFile(path);
        if (!loadFrom.exists()) {
            // Empty
            Debug.logInternal("Lists file not found at " + path);
            return result;
        }

        try {
            FileInputStream fis = new FileInputStream(loadFrom);
            Scanner sc = new Scanner(fis);    //file to be scanned
            //returns true if there is another line to read
            while (sc.hasNextLine()) {
                String line = trimComment(sc.nextLine()).trim();
                if (line.length() == 0) continue;
                result.addLine(line);
            }
            sc.close();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        return result;
    }

    public static <T extends IListConfigFile> void loadListConfig(String path, Supplier<T> getDefault, Consumer<T> onReload) {
        T result = getListConfig(path, getDefault);
        _loadedConfigs.put(path, () -> onReload.accept(getListConfig(path, getDefault)));
        onReload.accept(result);
    }

    private static String trimComment(String line) {
        int pound = line.indexOf('#');
        if (pound == -1) {
            return line;
        }
        return line.substring(0, pound);
    }

    public static void ensureCommentedListFileExists(String path, String startingComment) {
        File loadFrom = getConfigFile(path);
        if (loadFrom.exists()) {
            // Already exists, don't make a new one.
            return;
        }
        StringBuilder result = new StringBuilder();
        for (String line : startingComment.split("\\r?\\n")) {
            if (line.length() != 0) {
                result.append("# ").append(line).append("\n");
            }
        }
        try {
            Files.write(loadFrom.toPath(), result.toString().getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
