package io.mo.xiaoaiplug.config

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.CancellationSignal
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 工具注册表。
 *
 * 设计要点(都是被真机延迟教出来的):
 *
 * 1. **组合式工具优先于通用 shell**。"当前 WiFi 密码是多少"用 run_shell 要跑四轮
 *    (列网卡 → 找配置文件 → 读文件 → 解析),每轮一次模型往返,实测 14 秒。
 *    `wifi_info` 一轮出结果。凡是能预见的问法都应该有专用工具。
 * 2. **一个工具内部只起一次 shell**。多条命令用 `;` 拼进同一次 `su -c`,
 *    进程创建在真机上单次约 80~150ms,起五次就是半秒白给。
 * 3. **描述面向模型而非人**。description 里要写清"什么时候该用我",
 *    否则模型会退回 run_shell 自己拼命令,又变成多轮。
 */
object Tools {

    private const val TAG = "XiaoAiProbe"
    private const val SHELL_TIMEOUT_SEC = 15L
    private const val MAX_TOOL_OUTPUT = 6000

    /** 工具参数声明,用于生成 JSON Schema。 */
    data class Param(
        val name: String,
        val type: String,          // string / integer / boolean
        val description: String,
        val required: Boolean = false,
        val enum: List<String>? = null
    )

    /**
     * 一个工具:名字 + 给模型看的说明 + 参数 + 实现。
     *
     * `mutating` = 会改变设备状态(启动应用、改设置、动音量…)。
     * 这类工具**只在本轮确实由我们接管时**才允许执行 —— 真机事故:
     * "打开微信帮我给X发信息"命中放行词、本该由小爱自己处理,我们没接管,
     * 但模型照样跑了一遍并真的 launch_app 打开了微信。小爱那边同时在报
     * "暂不支持微信双开",两边各干各的。读类工具跑了无所谓,动手类不行。
     */
    data class Spec(
        val name: String,
        /** 一句话说明。工具页拿它当开关的 summary,所以要短、是人话。 */
        val description: String,
        /**
         * 只给模型看的补充:适用场景、默认值、坑。不进界面 ——
         * 揉进 [description] 的话工具页那 16 个开关会被撑成一堵墙。
         */
        val modelHint: String = "",
        val params: List<Param> = emptyList(),
        val mutating: Boolean = false,
        /**
         * 按**本次参数**判定算不算动作类;返回 null 之外的值时覆盖 [mutating]。
         *
         * 为 run_shell 而存在:它是唯一一个"同一个工具既可能只读也可能毁灭性"的工具
         * (`getprop ro.build.version.release` vs `rm -rf /data`),一个静态布尔值表达不了。
         * 早先它 mutating=false,于是完全绕开了下面那道闸 —— 真机日志:未接管的轮次里
         * 模型用 run_shell 连着 `am start` 了三次,真的把 Activity 拉了起来。
         */
        val mutatingWhen: ((JSONObject) -> Boolean)? = null,
        val handler: (JSONObject, Context?) -> String
    )

    // ---------------------------------------------------------------- 注册表

    val ALL: List<Spec> by lazy {
        listOf(
            runShell, deviceStatus, wifiInfo, networkInfo, topMemoryApps, topStorageApps,
            listApps, launchApp, sendMessage, queryContacts, readFile,
            getSetting, setSetting,
            mediaControl, setVolume, bluetoothControl, quickToggle,
            currentTime, recentNotifications, clipboard, getLocation, weather,
            readSmsCode, getScreenContent, getLogcat, appStateControl,
            saveMemory
        )
    }

    private val byName: Map<String, Spec> by lazy { ALL.associateBy { it.name } }

    /**
     * 「记忆个性化录入」开关背后就是这个工具的启停 —— 记忆页那个开关和工具页里
     * save_memory 那个开关读写的是**同一份状态**,不是两个各自为政的配置项。
     */
    const val SAVE_MEMORY = "save_memory"

    /** 用户把工具全关掉时存这个,以区别于"老存档里压根没有这个字段"(空串 = 全开)。 */
    const val NONE = "none"

    /** run_shell 的工具名。安全策略闸要按名字单独认出它(它是唯一接受任意命令串的工具)。 */
    const val RUN_SHELL = "run_shell"

    /**
     * 动作类工具被 allowMutating 闸拦下时,返回串的固定前缀。AiClient 靠它认出"被拦了":
     * 模型被拦后常换 set_setting → run_shell → launch_app 挨个试,每试一次烧一轮,
     * 认出来之后下一轮直接要求作答。
     */
    const val MUTATING_BLOCKED = "error: 本轮对话由小爱自己处理"

    /**
     * run_shell 执行策略。它是唯一接受**任意命令串**、又以 root 执行的工具,风险面最大 ——
     * 模型自己误判、或被 read_file / 短信 / 屏幕文本里夹带的注入指令诱导,都可能跑出破坏性命令。
     * 这道闸让用户能收窄它。空串 / 旧存档 = [FULL],保持原行为。
     */
    enum class ShellPolicy {
        /** run_shell 整体不可用(工具页也能整体关,这档是配置里的便捷冗余)。 */
        DISABLED,
        /** 只放行只读命令([isReadOnlyShell]),会改设备状态的一律拒 —— 保留查询、禁掉动手。 */
        READONLY,
        /** 当前行为:写命令仍受"仅接管轮执行"的 allowMutating 闸约束,但不再额外限制。 */
        FULL;

        companion object {
            fun fromKey(s: String): ShellPolicy = when (s.trim().lowercase()) {
                "disabled", "off" -> DISABLED
                "readonly", "read_only" -> READONLY
                else -> FULL
            }
        }
    }

    /** 配置里勾选的工具;`csv` 为空表示全开(老存档没这个字段),`none` 表示全关。 */
    fun enabled(csv: String): List<Spec> {
        if (csv.isBlank()) return ALL
        if (csv.trim() == NONE) return emptyList()
        val want = csv.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return ALL.filter { it.name in want }
    }

    fun isEnabled(csv: String, name: String): Boolean = enabled(csv).any { it.name == name }

    /** 将 MCP 远程工具转换为本模块的 Spec */
    fun mcpToolToSpec(tool: McpTool, server: McpServerConfig): Spec {
        val sanitizedServer = server.name.replace(Regex("[^a-zA-Z0-9_]"), "_").lowercase()
        val sanitizedTool = tool.name.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val specName = "mcp_${sanitizedServer}_$sanitizedTool".take(64)

        val params = mutableListOf<Param>()
        val props = tool.inputSchema.optJSONObject("properties")
        val requiredArr = tool.inputSchema.optJSONArray("required")
        val requiredSet = mutableSetOf<String>()
        if (requiredArr != null) {
            for (i in 0 until requiredArr.length()) {
                requiredSet.add(requiredArr.getString(i))
            }
        }
        if (props != null) {
            for (k in props.keys()) {
                val pObj = props.getJSONObject(k)
                val type = pObj.optString("type", "string")
                val desc = pObj.optString("description", "")
                val enumArr = pObj.optJSONArray("enum")
                val enumList = if (enumArr != null) {
                    List(enumArr.length()) { enumArr.getString(it) }
                } else null
                params.add(Param(k, type, desc, k in requiredSet, enumList))
            }
        }

        return Spec(
            name = specName,
            description = "[MCP: ${server.name}] ${tool.description.ifEmpty { tool.name }}",
            modelHint = "来源于 MCP 服务 ${server.name}",
            params = params,
            mutating = false,
            handler = { args, _ ->
                McpClient.callTool(server, tool.name, args)
            }
        )
    }

    /** 获取所有生效的内置工具和 MCP 工具 */
    fun allSpecs(csv: String, mcpServers: List<McpServerConfig>): List<Spec> {
        val builtIn = enabled(csv)
        val mcpSpecs = mutableListOf<Spec>()
        for (server in mcpServers) {
            if (!server.enabled) continue
            val tools = McpClient.getToolsCached(server)
            for (tool in tools) {
                mcpSpecs.add(mcpToolToSpec(tool, server))
            }
        }
        return builtIn + mcpSpecs
    }

    fun findSpec(name: String, mcpServers: List<McpServerConfig> = emptyList()): Spec? {
        byName[name]?.let { return it }
        for (server in mcpServers) {
            if (!server.enabled) continue
            val tools = McpClient.getToolsCached(server)
            for (tool in tools) {
                val spec = mcpToolToSpec(tool, server)
                if (spec.name == name) return spec
            }
        }
        return null
    }

    /**
     * 开/关单个工具,返回新的 csv。
     *
     * 全勾选时也存显式列表、不存空串:存空串的话以后新增的工具会被自动打开,
     * 用户以为自己关掉的东西又冒出来。(这条语义原先只写在工具页里,提上来共用。)
     */
    fun withEnabled(csv: String, name: String, on: Boolean): String {
        val cur = enabled(csv).map { it.name }.toSet()
        val next = if (on) cur + name else cur - name
        return next.joinToString(",").ifEmpty { NONE }
    }

    /**
     * 给模型看的工具说明。三条出口(OpenAI schema / Anthropic schema / 文本约定)都用它,
     * 免得"动作类"这种信息只在其中一条路上有。
     *
     * [Spec.mutating] 原先只在执行时被 allowMutating 拦,模型那边一无所知 ——
     * 它会以为自己随时能开应用,答"好的已经帮你打开了",实际被拦下什么也没发生。
     */
    private fun modelDescription(s: Spec): String = buildString {
        append(s.description)
        if (s.modelHint.isNotEmpty()) append(' ').append(s.modelHint)
        if (s.mutating) append(" ⚡动作类：会改变设备状态，只在本轮确实由本模块接管时才会真正执行。")
    }

    /** 单个工具的 JSON Schema。OpenAI 叫 `parameters`,Anthropic 叫 `input_schema`,内容一样。 */
    private fun paramsSchema(s: Spec): JSONObject {
        val props = JSONObject()
        val required = JSONArray()
        for (p in s.params) {
            val po = JSONObject().put("type", p.type).put("description", p.description)
            p.enum?.let { po.put("enum", JSONArray(it)) }
            props.put(p.name, po)
            if (p.required) required.put(p.name)
        }
        return JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", required)
    }

    /** OpenAI `tools` 数组。原生 function calling 走这个,比文本约定可靠得多。 */
    fun toOpenAiSchema(specs: List<Spec>): JSONArray {
        val arr = JSONArray()
        for (s in specs) {
            arr.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject()
                        .put("name", s.name)
                        .put("description", modelDescription(s))
                        .put("parameters", paramsSchema(s))
                )
            )
        }
        return arr
    }

    /** Anthropic `tools` 数组。比 OpenAI 少一层包装:名字和 schema 直接摊在顶层。 */
    fun toAnthropicSchema(specs: List<Spec>): JSONArray {
        val arr = JSONArray()
        for (s in specs) {
            arr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("description", modelDescription(s))
                    .put("input_schema", paramsSchema(s))
            )
        }
        return arr
    }

    /**
     * 给不支持原生 function calling 的端点用:把工具表写进 system prompt。
     * 顺带**明确指定** JSON 方言 —— 之前模型自由发挥出三种 XML 变体,
     * 解析漏一种就把整段标记念了出去(见 AiClient.stripToolTags 的注释)。
     */
    fun toPromptSpec(specs: List<Spec>): String {
        if (specs.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("你可以调用以下工具获取设备真实信息。需要调用时，输出且仅输出：\n")
        sb.append("<tool_call>{\"name\":\"工具名\",\"arguments\":{...}}</tool_call>\n")
        sb.append("必须使用上面的 JSON 格式，不要用 XML 标签形式。可以一次输出多个 tool_call。\n")
        sb.append("拿到 <tool_response> 后，用简洁口语回答用户，不要复述原始数据。\n\n")
        sb.append("可用工具：\n")
        for (s in specs) {
            sb.append("- ").append(s.name).append(": ").append(modelDescription(s))
            if (s.params.isNotEmpty()) {
                sb.append(" 参数: ")
                sb.append(s.params.joinToString(", ") {
                    "${it.name}(${it.type}${if (it.required) ", 必填" else ""})"
                })
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * 执行一个工具。未知工具名返回错误串而不是抛异常 —— 让模型有机会自己纠正。
     *
     * `allowMutating` 在**执行时**求值,不是调用开始时:接管信号(takeOver / 静音泵)
     * 往往比模型调用晚到,开始时判定会误杀。
     */
    fun execute(
        name: String,
        args: JSONObject,
        ctx: Context?,
        allowMutating: Boolean = true,
        shellPolicy: ShellPolicy = ShellPolicy.FULL,
        mcpServers: List<McpServerConfig> = emptyList()
    ): String {
        val spec = findSpec(name, mcpServers)
            ?: return "error: unknown tool \"$name\""
        // run_shell 单独过一道策略闸(它是唯一接受任意命令串的工具)。这道闸在 allowMutating
        // 之前:毁灭性命令即使在接管轮、即使策略全开,也一律硬拦。
        if (name == RUN_SHELL) {
            shellDenial(args.optString("command", args.optString("cmd", "")).trim(), shellPolicy)
                ?.let { Log.w(TAG, "shell policy blocked run_shell ($shellPolicy)"); return it }
        }
        val mutating = spec.mutatingWhen?.invoke(args) ?: spec.mutating
        if (mutating && !allowMutating) {
            Log.i(TAG, "blocked mutating tool $name (round not ours)")
            return MUTATING_BLOCKED + "，未被本模块接管，动作类工具已禁用。" +
                    "请只作答，不要尝试执行任何会改变设备状态的操作，" +
                    "也不要换别的工具（包括 run_shell）重试同一个动作。"
        }
        return try {
            spec.handler(args, ctx).take(MAX_TOOL_OUTPUT)
        } catch (t: Throwable) {
            Log.w(TAG, "tool $name failed: $t")
            "error: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /**
     * run_shell 策略闸。返回拒绝理由(直接回给模型,让它换只读工具或如实告知用户)或 null(放行)。
     *
     * 毁灭性黑名单对**所有档**生效,包括 FULL —— 那类命令没有任何正常用途值得为它冒险。
     */
    private fun shellDenial(cmd: String, policy: ShellPolicy): String? {
        if (cmd.isEmpty()) return null   // 空命令留给 handler 自己回 "empty command"
        if (isDestructiveShell(cmd))
            return "error: 该命令被安全策略硬拦：疑似毁灭性操作（格式化 / 删除系统或数据分区 / 写裸块设备 / 刷机 / 重启 / fork bomb）。" +
                    "任何策略档下都不执行。如确有必要，请让用户手动在终端里做。"
        return when (policy) {
            ShellPolicy.DISABLED ->
                "error: run_shell 已被「shell 安全策略」禁用。请改用专用工具；" +
                        "确需执行命令时，让用户在「工具」页把策略改为「仅只读」或「全开」。"
            ShellPolicy.READONLY ->
                if (isReadOnlyShell(cmd)) null
                else "error: 当前「shell 安全策略」为「仅只读」，会改变设备状态的命令已禁用。" +
                        "只能执行查询类命令（getprop / cat / ls / dumpsys / settings get / pm list 等）。"
            ShellPolicy.FULL -> null
        }
    }

    /**
     * 毁灭性命令判定。**internal 是为了给 ShellGateTest 直接测** —— 和 [isReadOnlyShell] 一样,
     * 这是安全闸,光靠推演会漏(rm 的 flag 顺序、路径写法变体太多),必须真跑测试钉住。
     *
     * 认的是"任何档都不放"那一类:格式化、刷机、写裸块设备、fork bomb、重启关机、
     * 以及递归删除系统 / 数据 / 存储根。目的是防模型抽风或被注入诱导,**不是**防持 root 的用户
     * (那没意义),所以不追求防绕过,宁可偶尔误拦一条边缘命令,也不放过一次 rm -rf /data。
     */
    internal fun isDestructiveShell(cmd: String): Boolean {
        val c = cmd.lowercase()
        if (c.replace(Regex("\\s+"), "").contains(":(){:|:&};:")) return true      // fork bomb
        if (Regex("\\b(mkfs\\w*|mke2fs|make_ext4fs)\\b").containsMatchIn(c)) return true  // 格式化
        if (Regex("\\b(fastboot|blockdev)\\b").containsMatchIn(c)) return true       // 刷机 / 块设备操作
        if (Regex("\\breboot\\b").containsMatchIn(c)) return true                    // 重启
        if (Regex("\\bsvc\\s+power\\s+(shutdown|reboot)\\b").containsMatchIn(c)) return true
        if (Regex("\\bdd\\b[^|;&\\n]*\\bof=\\s*/dev/").containsMatchIn(c)) return true // dd 写块设备
        if (Regex(">\\s*/dev/block/").containsMatchIn(c)) return true                 // 覆写块设备
        return isRecursiveRmOnRoot(c)
    }

    /** 是否存在"递归删除 + 目标是根级危险路径"的 rm 命令段。 */
    private fun isRecursiveRmOnRoot(lower: String): Boolean {
        for (seg in lower.split(Regex("[|;&\\n]"))) {
            val s = seg.trim()
            if (s != "rm" && !s.startsWith("rm ")) continue
            val tokens = s.split(Regex("\\s+"))
            val recursive = tokens.contains("--recursive") ||
                    tokens.any { it.length >= 2 && it[0] == '-' && it.getOrNull(1) != '-' && it.contains('r') }
            if (!recursive) continue
            val targets = tokens.drop(1).filter { !it.startsWith("-") }
            if (targets.any { isDangerousPath(it) }) return true
        }
        return false
    }

    /** 根级危险路径:删了这些等于抹系统 / 数据。 */
    private fun isDangerousPath(p: String): Boolean {
        val t = p.trim().trim('\'', '"').trimEnd('/')
        if (t.isEmpty()) return true    // rm -rf /  (trimEnd 后成空)
        return t in DANGEROUS_ROOTS || Regex("^/[a-z_]+/?\\*$").matches(t)
    }

    private val DANGEROUS_ROOTS = setOf(
        "/system", "/vendor", "/data", "/storage", "/sdcard", "/dev", "/proc",
        "/sys", "/mnt", "/product", "/odm", "/*", "/data/data", "/data/*", "*", "/."
    )

    // ---------------------------------------------------------------- 通用 shell

    private val runShell = Spec(
        name = RUN_SHELL,
        description = "以 root 执行任意 shell 命令。",
        modelHint = "用于没有专用工具覆盖的操作（安装/卸载应用、查看进程详情、改文件权限等）。" +
                "能用专用工具就用专用工具——那些已经把输出解析好了，run_shell 的原始输出往往很长且要多轮往返。" +
                "注意：只读命令（getprop/cat/ls/dumpsys 等）任何时候都能执行；" +
                "会改变设备状态的命令只在本模块确实接管本轮对话时才会执行。",
        params = listOf(Param("command", "string", "要执行的 shell 命令", required = true)),
        mutatingWhen = { args ->
            !isReadOnlyShell(args.optString("command", args.optString("cmd", "")).trim())
        },
        handler = { args, _ ->
            val cmd = args.optString("command", args.optString("cmd", "")).trim()
            if (cmd.isEmpty()) "error: empty command" else sh(cmd)
        }
    )

    /**
     * 纯读命令的可执行文件名。**白名单而不是黑名单** —— 这是道安全闸,
     * 认不出的一律当成会动手。黑名单漏一个就等于放行,白名单漏一个只是少个能力。
     *
     * 不含 `pm` / `settings` / `wm` / `ip` 这类读写混合的:它们要看子命令,单独判(见下)。
     * 也不含 `am` —— 它就没有只读用法。
     */
    private val READ_ONLY_SHELL_BINS = setOf(
        "getprop", "cat", "ls", "df", "du", "ps", "grep", "egrep", "head", "tail",
        "wc", "stat", "uptime", "date", "id", "whoami", "free", "netstat",
        "printenv", "env", "echo", "find", "md5sum", "sha1sum", "dumpsys",
        "top", "basename", "dirname", "readlink", "file", "which", "awk", "sed", "cut", "sort", "uniq"
    )

    /**
     * 这条 shell 命令是不是纯只读。判**假**即视为动作类,在未接管的轮次里会被拒绝执行。
     *
     * 三条都必须成立,少一条就当动作类:
     *  1. 不含重定向(`>` 会写文件)、命令替换(`` ` ``/`$()` 里能藏任意东西,拆不干净)
     *  2. 拆开后**每一段**都在白名单里 —— 只看第一个词是不够的:
     *     `getprop ro.build.version.release && rm -rf /data` 第一段完全无害。
     *     实测模型确实会用 `&&` 串命令(查版本那次串了四条 getprop)。
     *  3. 读写混合的命令要看子命令(`settings get` 可以,`settings put` 不行)
     */
    // internal 而不是 private:要给 src/test 下的 ShellGateTest 直接测。
    // 这段是"模型能不能以 root 执行任意命令"的闸,推演过一次就漏了引号的情况
    // (`grep -E 'level|status'` 里的 | 被当成管道),所以必须真跑测试。
    internal fun isReadOnlyShell(cmd: String): Boolean {
        if (cmd.isBlank()) return false
        // 丢弃 stderr 是只读命令里的常见写法(`pm list packages 2>/dev/null`),
        // 它不写任何文件。先摘掉这两种固定形式,再按"含 > 即写文件"判。
        val stripped = cmd.replace("2>/dev/null", " ").replace("2>&1", " ")
        val segments = splitShellSegments(stripped) ?: return false
        if (segments.isEmpty()) return false
        return segments.all { seg ->
            val tokens = seg.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val bin = tokens.firstOrNull()?.trim('\'', '"')?.substringAfterLast('/')
                ?: return@all false
            val sub = tokens.getOrNull(1)?.trim('\'', '"').orEmpty()
            when {
                bin in READ_ONLY_SHELL_BINS -> true
                bin == "settings" -> sub == "get" || sub == "list"
                bin == "pm" -> sub == "list" || sub == "path" || sub == "dump"
                else -> false
            }
        }
    }

    /**
     * 按 `; && || | &` 把命令拆成一段一段,**引号内的不算分隔符**。
     * 遇到重定向 / 命令替换 / 引号没闭合,返回 null 表示"别放行"。
     *
     * 必须认引号:`dumpsys battery | grep -E 'level|status'` 里那个 `|` 是正则的一部分,
     * 按字面切会得到 `grep -E 'level` 和 `status'` 两段,后者的"命令名"是 `status'`,
     * 不在白名单里 → 整条被误判成动作类。这是只读命令里极常见的写法
     * (device_status 自己就这么写),漏判会让模型在未接管的轮次里连查询都做不了。
     *
     * 单双引号要区别对待:`$(...)` 和反引号在**双引号内仍然会执行**,只有单引号才是字面量。
     */
    private fun splitShellSegments(cmd: String): List<String>? {
        val segments = ArrayList<String>()
        val cur = StringBuilder()
        var quote = ' '
        var i = 0
        while (i < cmd.length) {
            val c = cmd[i]
            if (quote != ' ') {
                // 双引号内命令替换照样生效,不能放行
                if (quote == '"' && (c == '`' || (c == '$' && cmd.getOrNull(i + 1) == '('))) return null
                if (c == quote) quote = ' '
                cur.append(c); i++; continue
            }
            when {
                c == '\'' || c == '"' -> { quote = c; cur.append(c); i++ }
                c == '>' -> return null                                   // 写文件
                c == '`' -> return null                                   // 命令替换
                c == '$' && cmd.getOrNull(i + 1) == '(' -> return null    // 命令替换
                c == ';' || c == '\n' -> { segments.add(cur.toString()); cur.clear(); i++ }
                c == '&' || c == '|' -> {
                    segments.add(cur.toString()); cur.clear()
                    i += if (cmd.getOrNull(i + 1) == c) 2 else 1          // && / || 吃两个字符
                }
                else -> { cur.append(c); i++ }
            }
        }
        if (quote != ' ') return null      // 引号没闭合,解析不可信
        segments.add(cur.toString())
        return segments.map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- 设备状态

    private val deviceStatus = Spec(
        name = "device_status",
        description = "一次性返回设备状态：电量与充电状态、剩余存储、内存、运行时长、机型、系统版本。",
        modelHint = "用户问「还有多少电」「内存够不够」「手机是什么型号」" +
                "「安卓/系统版本是多少」「安全补丁到哪天」时用。" +
                "版本类问题必须用它,别用 run_shell 拼 getprop——那样拿到的是没有标签的裸值,容易对错行。",
        handler = { _, _ ->
            // 一次 su 调用跑完所有采集。分五次起进程要多花近半秒。
            val raw = sh(
                "echo '--BATTERY--'; dumpsys battery | grep -E 'level|status|powered|temperature';" +
                // 不能写 `df -h /data` —— 小米的 pass_through 挂载会让它解析到
                // /mnt/pass_through/.../com.xiaomi.market/... 那个 bind mount 上,
                // 报出来的是别人的挂载点。按挂载点精确匹配 " /data" 结尾那行。
                "echo '--STORAGE--'; df -h | grep -E ' /data$';" +
                "echo '--MEM--'; cat /proc/meminfo | grep -E 'MemTotal|MemAvailable';" +
                "echo '--UPTIME--'; uptime;" +
                // 每个值都**带标签**输出,不能只 getprop 出一串裸值。
                // 真机事故:"查看系统版本"这轮 getprop 返回的四行是
                //   BP2A.250605.031.A3 / 16 / 2026-06-01 / Xiaomi/xuanyuan:16/...
                // 全无标签,要靠位置对号。模型没对上,改从 build 号 BP2A.**250605** 里
                // 反推出"安全补丁 2025年6月5日"(实际 2026-06-01),Android 版本则直接
                // 用先验知识写成 15(实际 16)。数据是对的,是递过去的形式让它没法信。
                // marketname 尤其坑——"Xiaomi 15 Ultra"里那个 15 就挨着真版本号。
                "echo '--MODEL--';" +
                "echo -n 'marketname='; getprop ro.product.marketname;" +
                "echo -n 'model='; getprop ro.product.model;" +
                "echo -n 'android_version='; getprop ro.build.version.release;" +
                "echo -n 'security_patch='; getprop ro.build.version.security_patch;" +
                "echo -n 'hyperos_version='; getprop ro.miui.ui.version.name"
            )
            raw
        }
    )

    // ---------------------------------------------------------------- WiFi

    private val wifiInfo = Spec(
        name = "wifi_info",
        description = "返回当前连接的 WiFi状态信息",
        modelHint = "包含信号强度、协商速率、IP 地址，以及已保存网络的密码。" +
                "能查询任意已保存 WiFi 的密码（root 读 WifiConfigStore.xml），返回里会直接给出【答案】，可据此直接作答、无需再自己找。" +
                "用户问「WiFi 密码是多少」「当前连的什么网」「信号怎么样」时用。",
        params = listOf(
            Param("ssid", "string", "只查这个 SSID 的密码；留空表示当前连接的网络")
        ),
        handler = { args, _ ->
            val want = args.optString("ssid", "").trim()
            // 当前连接信息:小,随便截。
            val raw = sh(
                "echo '--CURRENT--'; dumpsys wifi | grep -m1 -E 'mWifiInfo|current SSID' ;" +
                "echo '--IP--'; ip -f inet addr show wlan0 | grep inet"
            )
            // 已保存网络单独读,而且**只 grep 出需要的三种行**。
            // 真机事故:早先这里 `cat` 整个 184KB 的 XML,被 MAX_TOOL_OUTPUT(6000)截断,
            // 当前连接的网络排在 42 个里的靠后位置,压根没进解析器 —— 工具返回一堆
            // 不相干的网络,模型只好再调一次、再退回 run_shell,一共烧了 3 轮 11 秒。
            // 截断本来只该管**返回给模型**的内容,不该管中间读取。
            // 保留 <Network> 行是为了分块:开放网络没有 PreSharedKey,
            // 只 grep SSID/PSK 两种行会串位。
            val store = sh(
                "grep -E '<Network>|name=\"SSID\"|name=\"PreSharedKey\"' " +
                "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml 2>/dev/null " +
                "|| grep -E '<Network>|name=\"SSID\"|name=\"PreSharedKey\"' " +
                "/data/misc/wifi/WifiConfigStore.xml 2>/dev/null",
                limit = 256 * 1024
            )
            val nets = parseWifiStore(store)
            val sb = StringBuilder()
            // `mWifiInfo SSID: "Xiaomi_XXXX", BSSID: …, RSSI: -68, Link speed: 576Mbps, …`
            // 整行原样给模型 —— 信号强度、协商速率、Supplicant 状态都在里面,
            // 用户问"信号怎么样""网速慢"时用得上,只抠个 SSID 就把它们丢了。
            val currentLine = raw.lineSequence().firstOrNull { it.contains("SSID:") }?.trim()
            val current = currentLine?.let {
                Regex("SSID:\\s*\"?([^\",]+)\"?").find(it)?.groupValues?.get(1)?.trim()
            }
            if (currentLine != null) sb.append("当前连接: ").append(currentLine).append('\n')
            Regex("inet\\s+(\\S+)").find(raw)?.let { sb.append("IP: ").append(it.groupValues[1]).append('\n') }

            // 目标网络:显式指定 > 当前连接。命中就把答案挑明,别让模型自己去列表里找 ——
            // 含糊的返回会让它再调一次工具,一轮就是好几秒。
            val target = want.ifEmpty { current.orEmpty() }
            val hit = if (target.isEmpty()) null
                      else nets.firstOrNull { it.first.equals(target, true) }
                        ?: nets.firstOrNull { it.first.contains(target, true) }

            when {
                nets.isEmpty() ->
                    sb.append("未能读取到已保存的 WiFi 配置(可能没有 root 权限)")
                hit != null ->
                    sb.append("【答案】网络 ").append(hit.first).append(" 的密码是: ")
                        .append(if (hit.second.isBlank()) "(开放网络，无密码)" else hit.second)
                        .append("\n(共保存了 ").append(nets.size).append(" 个网络，此条即用户所问，可直接作答)")
                target.isNotEmpty() -> {
                    sb.append("没有找到 \"").append(target).append("\" 的保存记录。")
                        .append("已保存的 ").append(nets.size).append(" 个网络:\n")
                    for ((ssid, psk) in nets.take(30)) {
                        sb.append("  ").append(ssid).append(" : ")
                            .append(if (psk.isBlank()) "(无密码)" else psk).append('\n')
                    }
                }
                else -> {
                    sb.append("已保存 ").append(nets.size).append(" 个网络:\n")
                    for ((ssid, psk) in nets.take(30)) {
                        sb.append("  ").append(ssid).append(" : ")
                            .append(if (psk.isBlank()) "(无密码)" else psk).append('\n')
                    }
                }
            }
            sb.toString()
        }
    )

    /**
     * 从 WifiConfigStore.xml 里抠出 (SSID, 密码) 对。
     * 实测这台机器上 42 个网络的 SSID **全部**是 `&quot;` 包起来的:
     *   `<string name="SSID">&quot;Xiaomi_8B79&quot;</string>`
     * 所以先无差别取标签内容,再单独剥引号 —— 不要把剥引号写进正则,
     * 那样要靠 `&quot;?` 的贪婪/惰性配合才对,读的人和写的人都容易想错。
     */
    private fun parseWifiStore(xml: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        // 每个 <Network> 块里各有一个 SSID 和(可能)一个 PreSharedKey
        for (block in xml.split("<Network>").drop(1)) {
            val ssid = tagValue(block, "SSID") ?: continue
            val psk = tagValue(block, "PreSharedKey").orEmpty()
            out.add(ssid to psk)
        }
        return out
    }

    private fun tagValue(block: String, name: String): String? {
        val raw = Regex("name=\"$name\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(block)?.groupValues?.get(1)?.trim() ?: return null
        return unquote(raw)
    }

    /** 剥掉 XML 转义的引号或真引号。开放网络会存 `null` 字面量。 */
    private fun unquote(s: String): String {
        var v = s.trim()
        if (v == "null") return ""
        if (v.startsWith("&quot;") && v.endsWith("&quot;") && v.length >= 12) {
            v = v.substring(6, v.length - 6)
        } else if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length - 1)
        }
        return v.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    }

    // ---------------------------------------------------------------- 网络

    private val networkInfo = Spec(
        name = "network_info",
        description = "当前网络连接情况",
        modelHint = "包含连接类型（WiFi/移动数据）、IP 地址、运营商、网络制式、飞行模式状态、连通性测试。" +
                "用户问「现在用的什么网络」「有没有联网」「运营商是谁」时用。",
        handler = { _, _ ->
            sh(
                "echo '--TYPE--'; dumpsys connectivity | grep -m3 -E 'NetworkAgentInfo|Active default';" +
                "echo '--IP--'; ip -f inet addr | grep -E 'inet .*(wlan|rmnet)';" +
                "echo '--CARRIER--'; getprop gsm.operator.alpha; getprop gsm.network.type;" +
                "echo '--AIRPLANE--'; settings get global airplane_mode_on;" +
                "echo '--PING--'; ping -c 1 -W 2 223.5.5.5 | tail -n 2"
            )
        }
    )

    // ---------------------------------------------------------------- 内存占用

    /**
     * "哪些应用最占内存"。没有这个工具时模型只能用 run_shell 去啃 `dumpsys meminfo` ——
     * 那份输出在这台机器上是几万字符,一返回就撞 MAX_TOOL_OUTPUT(6000)被拦腰截断,
     * 排序表的头部(也就是唯一有用的部分)恰好在最前面还好,但 `-a`、`ps` 之类的变体
     * 一律截在半路,模型换着命令重试直到轮数烧光,用户看到的就是
     * "工具调用超过上限,未得到最终答案"。和 wifi_info 那次是同一个坑:
     * **截断只该管返回给模型的内容,不该管工具内部的读取**,所以这里 sh(limit=…) 显式放大,
     * 在 Kotlin 里排完序再把前 N 条交出去。
     */
    private val topMemoryApps = Spec(
        name = "top_memory_apps",
        description = "获取进程内存占用状态",
        modelHint = "用户问「哪些应用最占内存」「内存被谁吃了」「手机卡是不是内存不够」时用。" +
                "默认不含系统进程（system_server/systemui 等），用户要看的话传 include_system=true。",
        params = listOf(
            Param("count", "integer", "返回前几名，默认 8"),
            Param("include_system", "boolean", "是否包含系统进程(system_server/systemui 等)，默认 false")
        ),
        handler = { args, ctx ->
            val count = args.optInt("count", 8).coerceIn(1, 30)
            val includeSystem = args.optBoolean("include_system", false)
            val raw = sh(MEM_CMD, limit = 512 * 1024)
            var procs = parseProcMem(raw)
            // ps 的 -o 选项在别的 ROM 上不一定支持;真解析不出来再退回慢但通用的 dumpsys
            if (procs.size < MIN_PLAUSIBLE_PROCS) {
                Log.i(TAG, "ps/smaps path yielded only ${procs.size} procs, " +
                        "falling back to dumpsys meminfo")
                procs = parseMeminfo(sh(MEM_CMD_FALLBACK, limit = 512 * 1024))
            }
            if (procs.isEmpty()) return@Spec "error: 没能解析出内存占用(可能没有 root 权限)"

            val pm = ctx?.packageManager
            val sb = StringBuilder()
            Regex("MemTotal:\\s+(\\d+) kB").find(raw)?.let { m ->
                sb.append("总内存 ").append(mb(m.groupValues[1].toLong()))
                Regex("MemAvailable:\\s+(\\d+) kB").find(raw)?.let {
                    sb.append("，可用 ").append(mb(it.groupValues[1].toLong()))
                }
                sb.append('\n')
            }
            sb.append("内存占用前 ").append(count).append(" 名:\n")
            var n = 0
            for ((pkg, kb) in procs) {
                // 进程名可能是 com.foo:remote / com.foo:push,归到主包名下看应用名
                val base = pkg.substringBefore(':')
                val info = pm?.let { runCatching { it.getApplicationInfo(base, 0) }.getOrNull() }
                val isSystem = info == null ||
                        (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem && !includeSystem) continue
                val label = info?.let { pm.getApplicationLabel(it).toString() }
                sb.append("  ").append(++n).append(". ")
                    .append(if (label != null && label != pkg) "$label ($pkg)" else pkg)
                    .append(" — ").append(mb(kb)).append('\n')
                if (n >= count) break
            }
            if (n == 0) {
                sb.append("  (前 ").append(procs.size)
                    .append(" 个进程全是系统进程；想看它们请用 include_system=true)")
            }
            sb.toString()
        }
    )

    /**
     * 两段式取内存占用,一次 su 跑完。
     *
     * 为什么不直接 `dumpsys meminfo`:它挨个进程发 binder、让每个进程自己遍历 smaps 算 PSS,
     * 这台机器 730 个进程实测 **5.42 秒**(工具整体 8 秒,用户能明显感到卡)。
     *
     * 也不能只用 `ps` 的 RSS(0.04 秒但不准):RSS 把共享页在每个进程里重复计,
     * 实测微信 RSS 760MB / PSS 551MB,虚高 27%,排名也会被 system_server 这类
     * 大量共享框架内存的进程带偏。
     *
     * 所以:**先用便宜的 RSS 排个序选出前 40 名候选,再只对这 40 个读
     * `/proc/<pid>/smaps_rollup`**(内核直接给算好的 Pss,不用逐页遍历)。
     * 实测 0.50 秒,PSS 精度不变。落选的进程 RSS 都比第 40 名小,
     * PSS ≤ RSS,所以不可能挤进前几名 —— 这个截断是安全的。
     */
    // `${'$'}` 是 Kotlin 转义:这些 $ 要原样交给 shell,不能被字符串模板吃掉。
    private val MEM_CMD = buildString {
        val d = "$"
        append("echo '--PS--'; PS=$d(ps -A -o PID,RSS,NAME);")
        append(" echo \"${d}PS\" | sort -k2 -rn | head -40;")
        append("echo '--PSS--';")
        append(" grep -s '^Pss:' $d(echo \"${d}PS\" | sort -k2 -rn | head -40 |")
        append(" while read p r n; do echo /proc/${d}p/smaps_rollup; done);")
        append("echo '--MEM--'; grep -E 'MemTotal|MemAvailable' /proc/meminfo")
    }

    /**
     * ps 这条路少于这么多个进程就判定为"没采到",退回 dumpsys。
     *
     * MEM_CMD 里是 `head -40`,任何一台正常开机的机器都必然凑满 40 条,所以这个阈值
     * 不会误伤。它防的是**采到了几条、但全是垃圾**的情况 —— 早先这里只判 `isEmpty()`,
     * 于是真机上翻车:HyperOS 把应用的 /proc 挂成 `hidepid=invisible`(见
     * `/proc/<pid>/mountinfo`),而我们 su 出来的 root **没有 READPROC(gid 3009)
     * 补充组、也没有 CAP_SYS_PTRACE**(KernelSU 按 App Profile 授权,不一定是完整 root),
     * 于是 `ps -A` 只看得见 su 自己拉起来的 ps/sh 两三个进程。
     * 非空 → 不触发 fallback → 工具一本正经地回"内存占用前 10 名:1. ps — 5MB"。
     * 模型不信这个结果,换 include_system 再调、再改用 run_shell 手搓 ps,
     * 六轮 MAX_TOOL_ITERATIONS 烧光,用户看到"工具调用超过上限,未得到最终答案"。
     *
     * dumpsys 走 binder 问 AMS,不读 /proc,所以完全不受 hidepid 影响 —— 这也是为什么
     * 判据要落在"结果像不像真的"上,而不是"命令有没有报错":那条 ps 是**成功返回**的。
     */
    private const val MIN_PLAUSIBLE_PROCS = 20

    /**
     * su 自己这一串管道进程。正常情况下它们 RSS 只有几 MB、排不进前 40,
     * 但 hidepid 挡住别人时它们就是全部,列出来纯属噪音。
     */
    private val SHELL_NOISE = setOf("ps", "sh", "sort", "head", "grep", "wc", "toybox", "awk")

    /** ps -o 不被支持时的退路:慢(5 秒+)但哪儿都有。 */
    private const val MEM_CMD_FALLBACK =
        "dumpsys meminfo | awk '/by process:/{p=1} /by OOM adjustment:/{p=0} p';" +
        "echo '--MEM--'; grep -E 'MemTotal|MemAvailable' /proc/meminfo"

    /**
     * 解析 MEM_CMD 的输出,返回按 KB 降序的 (进程名, KB)。
     *
     * `--PS--` 段:`22538 760084 com.tencent.mm`(pid / RSS / 进程名)
     * `--PSS--` 段:`/proc/22538/smaps_rollup:Pss:   551174 kB`
     *
     * 用 pid 把两段拼起来,**有 PSS 用 PSS,没有才退回 RSS** ——
     * 内核线程和刚退出的进程没有 smaps_rollup(或读不到),不能因此把它们丢了。
     */
    private fun parseProcMem(raw: String): List<Pair<String, Long>> {
        val psLine = Regex("^\\s*(\\d+)\\s+(\\d+)\\s+(\\S+)\\s*$")
        val pssLine = Regex("/proc/(\\d+)/smaps_rollup:Pss:\\s+(\\d+)")
        val names = LinkedHashMap<String, String>()   // pid -> 进程名
        val rss = HashMap<String, Long>()
        val pss = HashMap<String, Long>()
        var section = ""
        for (l in raw.lineSequence()) {
            when {
                l.startsWith("--") -> { section = l; continue }
                section.startsWith("--PS--") -> psLine.find(l)?.let { m ->
                    names[m.groupValues[1]] = m.groupValues[3]
                    rss[m.groupValues[1]] = m.groupValues[2].toLong()
                }
                section.startsWith("--PSS--") -> pssLine.find(l)?.let { m ->
                    pss[m.groupValues[1]] = m.groupValues[2].toLong()
                }
            }
        }
        return names.entries
            .mapNotNull { (pid, name) ->
                if (name.substringAfterLast('/') in SHELL_NOISE) return@mapNotNull null
                val kb = pss[pid] ?: rss[pid] ?: return@mapNotNull null
                name to kb
            }
            .sortedByDescending { it.second }
    }

    /**
     * 从 meminfo 的排序表里抠出 (进程名, KB)。表里的行长这样:
     *   `   350,123K: com.tencent.mm (pid 1234 / activities)`
     * 数字带千位逗号,得先去掉再转 Long。
     *
     * 输出里 PSS 和 RSS 两张表都在,**只能收一张** —— 两段都收会让同一个应用出现两次、
     * 数字还对不上。按表头挑 PSS,理由和 ps 那条路一样:RSS 把共享页在每个进程里重复计,
     * 虚高且会带偏排名。
     *
     * 注意这张 PSS 表的数字会**大于**同一次 dumpsys 里的 RSS(实测微信 RSS 304MB /
     * PSS 617MB)。纯 PSS 不可能超过 RSS,多出来的是换出到 ZRAM 的那部分 —— MIUI 压缩
     * 内存用得凶,这些页不驻留所以不计进 RSS,但确实还是这个应用占着的。问"内存被谁吃了"
     * 的时候算上它更贴题,所以不做修正。
     *
     * 不能按出现顺序挑:老版本 Android 是 PSS 在前,Android 14 起默认不算 PSS,
     * 这台 HyperOS 上是 `Total RSS by process:` 在前、PSS 在后。原先"取第一张表"的写法
     * 在这里恰好取到了 RSS。PSS 表缺席时(有些 ROM 真的只给 RSS)退回第一张有内容的表。
     */
    private fun parseMeminfo(raw: String): List<Pair<String, Long>> {
        val line = Regex("^\\s*([\\d,]+)K:\\s+(\\S+)")
        val tables = LinkedHashMap<String, MutableList<Pair<String, Long>>>()
        var cur: MutableList<Pair<String, Long>>? = null
        for (l in raw.lineSequence()) {
            if (l.contains("by process:")) {
                cur = tables.getOrPut(l.trim()) { ArrayList() }
                continue
            }
            val m = line.find(l)
            if (m == null) {
                // 表和表之间夹着别的段落(OOM adjustment、category…),空行不算结束,
                // 但一碰到不认识的正文就收手,免得把隔壁段的数字吃进来。
                if (l.isNotBlank()) cur = null
                continue
            }
            cur?.add(m.groupValues[2] to m.groupValues[1].replace(",", "").toLong())
        }
        val pick = tables.entries.firstOrNull { it.key.contains("PSS", true) && it.value.isNotEmpty() }
            ?: tables.entries.firstOrNull { it.value.isNotEmpty() }
        return pick?.value?.sortedByDescending { it.second }.orEmpty()
    }

    private fun mb(kb: Long): String =
        if (kb >= 1024L * 1024) String.format(Locale.US, "%.1fGB", kb / 1024.0 / 1024.0)
        else "${kb / 1024}MB"

    // ---------------------------------------------------------------- 存储占用

    /**
     * "哪些应用最占存储"。和 top_memory_apps 是同一个坑的另一半:没有专用工具时
     * 模型只能拿 run_shell 硬凑,而这件事在 shell 里**没有一条便宜的路** ——
     * 拿 `du` 递归 /data/data 要遍历几十万个文件,实测直接撞穿 SHELL_TIMEOUT_SEC(15s)
     * 只回半截;`pm list packages` 只有包名没有体积;`dumpsys package` 几十万字符,
     * 一返回就被 MAX_TOOL_OUTPUT(6000)拦腰截断。模型于是换着命令重试,
     * 六轮 MAX_TOOL_ITERATIONS 烧光,用户看到的就是"工具调用超过上限,未得到最终答案"。
     *
     * 正路是 StorageStatsManager:它读的是**内核 quota 记账**(和"设置-存储"里那个
     * 数字同源),完全不遍历文件,一个包一次 binder 就同时拿到 apk / 数据 / 缓存三个数。
     */
    private val topStorageApps = Spec(
        name = "top_storage_apps",
        description = "获取各应用占用的存储空间",
        modelHint = "统计应用大小 + 数据 + 缓存。" +
                "用户问「哪些应用最占存储」「存储空间被谁用了」「手机空间不够了」时用。" +
                "返回会附带机身存储总量和剩余。默认不含系统应用。",
        params = listOf(
            Param("count", "integer", "返回前几名，默认 8"),
            Param("include_system", "boolean", "是否包含系统应用，默认 false")
        ),
        handler = { args, ctx -> topStorage(args, ctx) }
    )

    /** 单个应用的体积。`data` 含 `cache`(两条数据源都是这个语义),所以 total = app + data。 */
    private data class AppSize(val pkg: String, val app: Long, val data: Long, val cache: Long)

    /** 扫描结果。`partial` = 预算烧完提前收工,排名可能不全,得对用户讲明。 */
    private class StorageScan(val apps: List<AppSize>, val partial: Boolean)

    /** quota 没开时 queryStatsForPackage 会退化成真的遍历文件,几百个包能跑几十秒。 */
    private const val STORAGE_SCAN_BUDGET_MS = 6000L

    private fun topStorage(args: JSONObject, ctx: Context?): String {
        val count = args.optInt("count", 8).coerceIn(1, 30)
        val includeSystem = args.optBoolean("include_system", false)
        val pm = ctx?.packageManager

        var scan = ctx?.let { queryStorageStats(it) } ?: StorageScan(emptyList(), false)
        if (scan.apps.isEmpty()) {
            // 退路:每日任务 DiskStatsLoggingService 落在 /data/system/diskstats_cache.json
            // 里的快照,可能是昨天的,但总比没有强。
            Log.i(TAG, "StorageStatsManager yielded nothing, falling back to dumpsys diskstats")
            scan = StorageScan(parseDiskStats(sh("dumpsys diskstats", limit = 512 * 1024)), false)
        }
        if (scan.apps.isEmpty()) {
            // 这条错误串是写给模型看的:必须明说"别重试",否则它会退回 run_shell
            // 自己拼命令,又把六轮烧光 —— 那正是本工具要解决的问题。
            return "error: 取不到各应用的存储占用（没有 PACKAGE_USAGE_STATS 权限，" +
                    "dumpsys diskstats 也没有缓存数据）。请直接如实告知用户这项查不到，" +
                    "不要改用 run_shell 或其它工具重试。"
        }

        val sb = StringBuilder()
        // 总量/剩余单独取:拿不到就不打印,别让整个工具因此失败
        storageTotals(ctx)?.let { sb.append(it).append('\n') }
        sb.append("占用存储前 ").append(count).append(" 名")
        if (!includeSystem) sb.append("（不含系统应用）")
        sb.append(":\n")

        var n = 0
        for (a in scan.apps.sortedByDescending { it.app + it.data }) {
            val info = pm?.let { runCatching { it.getApplicationInfo(a.pkg, 0) }.getOrNull() }
            val isSystem = when {
                pm == null -> false        // 没有 PackageManager 就没法判断，全给出去
                info == null -> true       // 已卸载 / 属于别的用户，当系统项滤掉
                else -> (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            }
            if (isSystem && !includeSystem) continue
            val label = info?.let { pm.getApplicationLabel(it).toString() }
            sb.append("  ").append(++n).append(". ")
                .append(if (label != null && label != a.pkg) "$label (${a.pkg})" else a.pkg)
                .append(" — ").append(humanBytes(a.app + a.data))
                .append("（应用 ").append(humanBytes(a.app))
                .append("，数据 ").append(humanBytes((a.data - a.cache).coerceAtLeast(0)))
                .append("，缓存 ").append(humanBytes(a.cache)).append("）\n")
            if (n >= count) break
        }
        if (n == 0) {
            sb.append("  （统计到的 ").append(scan.apps.size)
                .append(" 项全是系统应用；想看它们请用 include_system=true）")
        }
        if (scan.partial) {
            sb.append("（本机未启用存储配额，只来得及统计 ").append(scan.apps.size)
                .append(" 个应用，排名可能不完整）\n")
        }
        return sb.toString()
    }

    /**
     * StorageStatsManager 路线。需要 PACKAGE_USAGE_STATS ——
     * 没有的话**第一个包**就抛 SecurityException,整体放弃换下一条路;
     * 单个包失败(正在卸载、属于别的用户)只跳过它自己。
     */
    private fun queryStorageStats(ctx: Context): StorageScan {
        val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE)
                as? android.app.usage.StorageStatsManager ?: return StorageScan(emptyList(), false)
        val uuid = android.os.storage.StorageManager.UUID_DEFAULT
        val user = android.os.Process.myUserHandle()
        val out = ArrayList<AppSize>()
        val deadline = System.currentTimeMillis() + STORAGE_SCAN_BUDGET_MS
        for (app in ctx.packageManager.getInstalledApplications(0)) {
            val st = try {
                ssm.queryStatsForPackage(uuid, app.packageName, user)
            } catch (e: SecurityException) {
                Log.i(TAG, "no PACKAGE_USAGE_STATS in this process: $e")
                return StorageScan(emptyList(), false)
            } catch (t: Throwable) {
                continue
            }
            out.add(AppSize(app.packageName, st.appBytes, st.dataBytes, st.cacheBytes))
            if (System.currentTimeMillis() > deadline) {
                Log.i(TAG, "storage scan budget exceeded after ${out.size} packages")
                return StorageScan(out, true)
            }
        }
        return StorageScan(out, false)
    }

    /**
     * `dumpsys diskstats` 里的四行平行数组(单位是字节):
     *   Package Names: ["com.foo","com.bar"]
     *   App Sizes: [1234,5678]
     *   App Data Sizes: [...]
     *   App Cache Sizes: [...]
     * 按下标对齐。缺哪条就当 0,不能因为少一条把整份数据丢了。
     */
    private fun parseDiskStats(raw: String): List<AppSize> {
        fun arr(label: String): List<String>? =
            Regex("^$label:\\s*\\[(.*)\\]\\s*$", RegexOption.MULTILINE)
                .find(raw)?.groupValues?.get(1)
                ?.split(',')
                ?.map { it.trim().trim('"') }
                ?.filter { it.isNotEmpty() }
        val names = arr("Package Names") ?: return emptyList()
        val app = arr("App Sizes").orEmpty()
        val data = arr("App Data Sizes").orEmpty()
        val cache = arr("App Cache Sizes").orEmpty()
        return names.mapIndexed { i, pkg ->
            AppSize(
                pkg,
                app.getOrNull(i)?.toLongOrNull() ?: 0L,
                data.getOrNull(i)?.toLongOrNull() ?: 0L,
                cache.getOrNull(i)?.toLongOrNull() ?: 0L
            )
        }
    }

    /** 机身存储总量/剩余。StorageStatsManager 的这两个方法不需要任何权限。 */
    private fun storageTotals(ctx: Context?): String? {
        if (ctx == null) return null
        runCatching {
            val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE)
                    as android.app.usage.StorageStatsManager
            val uuid = android.os.storage.StorageManager.UUID_DEFAULT
            val total = ssm.getTotalBytes(uuid)
            val free = ssm.getFreeBytes(uuid)
            return "机身存储共 ${humanBytes(total)}，已用 ${humanBytes(total - free)}，" +
                    "剩余 ${humanBytes(free)}"
        }
        return runCatching {
            val fs = android.os.StatFs("/data")
            "/data 共 ${humanBytes(fs.blockCountLong * fs.blockSizeLong)}，" +
                    "剩余 ${humanBytes(fs.availableBlocksLong * fs.blockSizeLong)}"
        }.getOrNull()
    }

    private fun humanBytes(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2fGB", b / 1024.0 / 1024 / 1024)
        b >= 1024L * 1024 -> String.format(Locale.US, "%.0fMB", b / 1024.0 / 1024)
        else -> "${b / 1024}KB"
    }

    // ---------------------------------------------------------------- 应用

    private val listApps = Spec(
        name = "list_apps",
        description = "获取已安装的应用信息(包名 + 显示名)",
        modelHint = "用户问「我装了哪些应用」「有没有装 XXX」「找一下 XX 的包名」时用。默认只列第三方应用。",
        params = listOf(
            Param("filter", "string", "按名称/包名过滤的关键词，留空返回全部第三方应用"),
            Param("include_system", "boolean", "是否包含系统应用，默认 false")
        ),
        handler = { args, ctx ->
            val filter = args.optString("filter", "").trim()
            val includeSystem = args.optBoolean("include_system", false)
            val pm = ctx?.packageManager ?: return@Spec "error: no context available"
            val sb = StringBuilder()
            var n = 0
            for (app in pm.getInstalledApplications(0)) {
                val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem && !includeSystem) continue
                val label = pm.getApplicationLabel(app).toString()
                if (filter.isNotEmpty() &&
                    !label.contains(filter, true) && !app.packageName.contains(filter, true)
                ) continue
                sb.append(label).append(" (").append(app.packageName).append(")\n")
                if (++n >= 200) break
            }
            if (n == 0) "没有匹配的应用" else sb.toString()
        }
    )

    private val launchApp = Spec(
        name = "launch_app",
        description = "启动应用",
        modelHint = "支持按显示名或包名模糊匹配。用户说「打开微信」「帮我启动抖音」时用。",
        params = listOf(Param("name", "string", "应用显示名或包名", required = true)),
        mutating = true,
        handler = { args, ctx ->
            val name = args.optString("name", "").trim()
            if (name.isEmpty()) return@Spec "error: empty name"
            val pm = ctx?.packageManager ?: return@Spec "error: no context available"
            // 先当包名试,不中再按显示名找
            var pkg = pm.getInstalledApplications(0).firstOrNull { it.packageName == name }?.packageName
            if (pkg == null) {
                pkg = pm.getInstalledApplications(0).firstOrNull {
                    pm.getApplicationLabel(it).toString().equals(name, true)
                }?.packageName
            }
            if (pkg == null) {
                pkg = pm.getInstalledApplications(0).firstOrNull {
                    pm.getApplicationLabel(it).toString().contains(name, true)
                }?.packageName
            }
            if (pkg == null) return@Spec "error: 没找到应用 \"$name\""
            val intent = pm.getLaunchIntentForPackage(pkg)
                ?: return@Spec "error: $pkg 没有可启动的入口"
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            "已启动 $pkg"
        }
    )

    /**
     * 给微信联系人发消息。走无障碍服务真的去点微信界面 ——
     * 小爱原生这条路在双开微信上是死的,它只会回"暂不支持微信双开功能"。
     *
     * 跨进程:本工具跑在小爱进程里,服务在模块自己进程,靠 ConfigProvider 过桥。
     * 整个 UI 流程要好几秒,这里同步等,所以调用方必须在后台线程(AiClient 已经是)。
     */
    private val sendMessage = Spec(
        name = "send_message",
        description = "微信指定给某个联系人发消息。",
        modelHint = "通过无障碍服务发送，解决小爱原生不支持微信双开的问题。" +
                "名字对不上就找不到人。整个流程需要数秒。用户说「给张三发微信说晚上不回家吃饭」时用。",
        params = listOf(
            Param("contact", "string", "联系人在微信里的显示名，必须完全一致", required = true),
            Param("text", "string", "要发送的消息正文", required = true),
            Param("send", "boolean", "true=直接发出；false=只打开会话并填好正文，由用户自己按发送。默认 true")
        ),
        mutating = true,
        handler = { args, ctx ->
            val contact = args.optString("contact", "").trim()
            val text = args.optString("text", "").trim()
            val send = if (args.has("send")) args.optBoolean("send", true) else true
            when {
                ctx == null -> "error: no context available"
                contact.isEmpty() || text.isEmpty() -> "error: contact 或 text 为空"
                else -> {
                    // 微信必须在前台,无障碍服务才拿得到它的控件树
                    // 只管拉起来,不在这里 sleep 等 —— 等多久合适这里判断不了。
                    // 服务那边会轮询等微信真到前台(小爱的窗口常压在上面)。
                    ctx.packageManager.getLaunchIntentForPackage(UI_AUTO_WECHAT)?.let {
                        it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(it)
                    }
                    val extras = android.os.Bundle().apply {
                        putString("contact", contact)
                        putString("text", text)
                        putString("send", send.toString())
                    }
                    val out = ctx.contentResolver.call(
                        android.net.Uri.parse("content://io.mo.xiaoaiplug.config"),
                        "send_message", null, extras
                    )
                    out?.getString("result") ?: "error: 模块进程无响应（无障碍服务可能没开）"
                }
            }
        }
    )

    private const val UI_AUTO_WECHAT = "com.tencent.mm"

    // ---------------------------------------------------------------- 通讯录

    /**
     * 查通讯录号码。和 [readSmsCode] 一样走 `content query` 而不是直接读 contacts2.db ——
     * 那个库是 WAL、还常被锁,content provider 自己处理并发,root 下直接查最稳。
     * 只读,不改任何东西。
     */
    private val queryContacts = Spec(
        name = "query_contacts",
        description = "查询通讯录联系人的电话号码",
        modelHint = "用户问「张三的电话是多少」「给我李四的号码」「通讯录里有没有姓王的」时用。" +
                "按姓名模糊匹配，返回姓名+号码；一个人有多个号码会一并列出。留空 name 返回前若干条。",
        params = listOf(
            Param("name", "string", "按联系人姓名过滤(模糊匹配)，留空返回前若干条"),
            Param("limit", "integer", "最多返回几个联系人，默认 20")
        ),
        handler = { args, _ ->
            queryContactsImpl(
                args.optString("name", "").trim(),
                args.optInt("limit", 20).coerceIn(1, 100)
            )
        }
    )

    private fun queryContactsImpl(name: String, limit: Int): String {
        val raw = sh(
            "content query --uri content://com.android.contacts/data/phones " +
                "--projection display_name:data1",
            limit = 256 * 1024
        )
        if (raw.contains("Permission Denial", true) || raw.startsWith("shell error"))
            return "error: 读不到通讯录(可能没有 root 或联系人库不可访问): ${raw.take(160)}"
        // content query 每行一条:`Row: 0 display_name=张三, data1=13800138000`
        val rows = raw.split(Regex("(?m)^Row: \\d+ ")).map { it.trim() }.filter { it.isNotEmpty() }
        val nameRe = Regex("display_name=(.*?), data1=", RegexOption.DOT_MATCHES_ALL)
        val numRe = Regex("data1=(.*)", RegexOption.DOT_MATCHES_ALL)
        // 一个人常有多个号码(手机/座机),按 姓名→号码集合 归并去重,顺序保持首次出现
        val map = LinkedHashMap<String, LinkedHashSet<String>>()
        for (row in rows) {
            val dn = nameRe.find(row)?.groupValues?.get(1)?.trim() ?: continue
            val num = numRe.find(row)?.groupValues?.get(1)?.trim()?.replace(" ", "") ?: continue
            if (dn.isEmpty() || num.isEmpty() || num == "NULL") continue
            if (name.isNotEmpty() && !dn.contains(name, true)) continue
            // 姓名筛完再判上限:留空时才靠这个截断,带 name 时要把同名多号都收齐
            if (name.isEmpty() && dn !in map && map.size >= limit) break
            map.getOrPut(dn) { LinkedHashSet() }.add(num)
        }
        if (map.isEmpty())
            return if (name.isEmpty()) "通讯录是空的" else "通讯录里没有匹配「$name」的联系人"
        return map.entries.joinToString("\n") { (dn, nums) ->
            "$dn：${nums.joinToString("、")}"
        }
    }

    // ---------------------------------------------------------------- 文件

    private val readFile = Spec(
        name = "read_file",
        description = "读取文本文件内容(root)。用于查看日志、配置等。",
        modelHint = "支持 /data、/sdcard、/system 等任意路径。仅适合文本文件，二进制文件读了没有意义。",
        params = listOf(
            Param("path", "string", "绝对路径", required = true),
            Param("max_lines", "integer", "最多读多少行，默认 200")
        ),
        handler = { args, _ ->
            val path = args.optString("path", "").trim()
            if (path.isEmpty()) return@Spec "error: empty path"
            val maxLines = args.optInt("max_lines", 200).coerceIn(1, 2000)
            sh("head -n $maxLines ${shellQuote(path)}")
        }
    )

    // ---------------------------------------------------------------- 系统设置

    private val getSetting = Spec(
        name = "get_setting",
        description = "读取系统设置项的值(Settings.System/Secure/Global)。",
        modelHint = "用户问「屏幕亮度多少」「自动锁屏时间是多少」时用。" +
                "常用 key: screen_brightness(system)、screen_off_timeout(system)。",
        params = listOf(
            Param("namespace", "string", "system / secure / global", required = true,
                enum = listOf("system", "secure", "global")),
            Param("key", "string", "设置项名，如 screen_brightness", required = true)
        ),
        handler = { args, _ ->
            val ns = args.optString("namespace", "system").trim()
            val key = args.optString("key", "").trim()
            if (key.isEmpty()) return@Spec "error: empty key"
            sh("settings get ${shellQuote(ns)} ${shellQuote(key)}")
        }
    )

    private val setSetting = Spec(
        name = "set_setting",
        description = "修改系统设置项",
        modelHint = "用户说「把自动锁屏改成 1 分钟」「打开飞行模式」时用。" +
                "常用 key: screen_off_timeout(毫秒, system)、airplane_mode_on(0/1, global)。",
        params = listOf(
            Param("namespace", "string", "system / secure / global", required = true,
                enum = listOf("system", "secure", "global")),
            Param("key", "string", "设置项名", required = true),
            Param("value", "string", "要设置的值", required = true)
        ),
        mutating = true,
        handler = { args, _ ->
            val ns = args.optString("namespace", "system").trim()
            val key = args.optString("key", "").trim()
            val value = args.optString("value", "").trim()
            if (key.isEmpty()) return@Spec "error: empty key"
            sh("settings put ${shellQuote(ns)} ${shellQuote(key)} ${shellQuote(value)} && " +
                    "settings get ${shellQuote(ns)} ${shellQuote(key)}")
        }
    )

    // ---------------------------------------------------------------- 媒体 / 音量

    private val mediaControl = Spec(
        name = "media_control",
        description = "控制正在播放的媒体",
        modelHint = "用户说「暂停音乐」「下一首」「停止播放」时用。",
        params = listOf(
            Param("action", "string", "动作", required = true,
                enum = listOf("play", "pause", "toggle", "next", "previous", "stop"))
        ),
        mutating = true,
        handler = { args, _ ->
            val key = when (args.optString("action", "toggle").trim().lowercase()) {
                "play" -> 126
                "pause" -> 127
                "next" -> 87
                "previous", "prev" -> 88
                "stop" -> 86
                else -> 85  // toggle
            }
            sh("input keyevent $key")
            "已发送媒体按键 $key"
        }
    )

    private val setVolume = Spec(
        name = "set_volume",
        description = "设置、查询媒体音量",
        modelHint = "用户问「现在音量多大」「把音量调到 50%」「静音」时用。" +
                "不传 percent 则只返回当前音量、不做修改。",
        params = listOf(
            Param("percent", "integer", "目标音量百分比 0-100；不传则只返回当前音量")
        ),
        mutating = true,
        handler = { args, ctx ->
            val am = ctx?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                ?: return@Spec "error: no audio service"
            val stream = android.media.AudioManager.STREAM_MUSIC
            val max = am.getStreamMaxVolume(stream)
            if (!args.has("percent")) {
                val cur = am.getStreamVolume(stream)
                return@Spec "当前媒体音量 ${cur * 100 / max}% ($cur/$max)"
            }
            val pct = args.optInt("percent", 50).coerceIn(0, 100)
            val target = (pct * max + 50) / 100
            am.setStreamVolume(stream, target, 0)
            "已设为 $pct% ($target/$max)"
        }
    )

    // ---------------------------------------------------------------- 蓝牙

    /**
     * 蓝牙开关 + 状态查询 + 列配对 + 电量 + 连接/断开指定耳机。
     *
     * connect/disconnect 曾**故意不做** —— stock 上 `svc bluetooth` 只有总开关。这里走
     * 框架标准隐藏 API [BluetoothDevice.connect]/[disconnect](Android 13+ @SystemApi,一次
     * 连/断该设备所有已启用 profile,需 BLUETOOTH_PRIVILEGED),而工具跑在的 com.miui.voiceassist
     * 实测就持有这个权限(dumpsys 确认 granted=true),所以能真断音频。
     *
     * 走了弯路才定这条:先试过小米私有 AIDL(绑 com.xiaomi.bluetooth 的 BluetoothHeadsetService、
     * transact IMiuiHeadsetService.disconnect),真机验证 transact 不报错但**耳机根本不断** ——
     * 它只动自己那套 MMA/插件会话,不碰 A2DP/HFP。别再回头走那条。
     *
     * **不做 ANC/降噪切换**:实测那条路不走这个干净 binder,而是走动态下载的插件/云配置,
     * mode 的 int 值按机型漂移、连运行时都抓不稳,硬做就是"报成功实际没动"。
     *
     * connect 在蓝牙层是**异步**的,发起后不代表已连上,返回话术只说"已发起"、让用户确认,
     * 不许诺"已连接"。battery 走小米私有 ContentProvider `com.android.bluetooth.deviceinfo_provider`
     * (`content query` 稳定接口),非小米 ROM 上查不到时如实回"读不到"而不是编。
     */
    private val bluetoothControl = Spec(
        name = "bluetooth_control",
        description = "开关蓝牙、查询蓝牙状态、列出已配对设备、查蓝牙设备电量、连接/断开指定耳机",
        modelHint = "「打开/关闭蓝牙」→ enable/disable；「蓝牙开着吗」→ status；" +
                "「配对过哪些蓝牙设备」→ list_paired；" +
                "「耳机/蓝牙设备还有多少电」「左右耳电量」→ battery；" +
                "「连接/断开我的耳机」→ connect/disconnect，device 传设备名关键词或 MAC" +
                "（如「连接 Redmi Buds」→ connect device=Redmi Buds）。" +
                "connect 是异步发起，返回「已发起」不等于已连上，别向用户打包票说「已连接」。" +
                "**不支持**切降噪/通透（ANC）——如实说做不到、让用户自己在耳机设置里切。",
        params = listOf(
            Param("action", "string", "动作", required = true,
                enum = listOf("enable", "disable", "status", "list_paired", "battery", "connect", "disconnect")),
            Param("device", "string", "要连接/断开的设备：MAC 或名字关键词，仅 connect/disconnect 用",
                required = false)
        ),
        // 会改变设备状态的才算动作类;status/list_paired/battery 只读,未接管的轮次也该能查
        mutatingWhen = {
            it.optString("action", "status").trim().lowercase() in
                    setOf("enable", "disable", "connect", "disconnect")
        },
        handler = { args, ctx ->
            bluetoothControlImpl(
                args.optString("action", "status").trim().lowercase(),
                args.optString("device").trim(),
                ctx
            )
        }
    )

    private fun bluetoothControlImpl(action: String, device: String, ctx: Context?): String = when (action) {
        "enable" -> { sh("svc bluetooth enable"); "已打开蓝牙" }
        "disable" -> { sh("svc bluetooth disable"); "已关闭蓝牙" }
        "status" -> {
            when (sh("settings get global bluetooth_on").trim()) {
                "1" -> "蓝牙：开启"
                "0" -> "蓝牙：关闭"
                else -> "蓝牙状态未知"
            }
        }
        "list_paired" -> {
            // dumpsys 里带 MAC 的行就是已知/配对设备,格式各版本不一,统一抓「MAC + 其后的名字」。
            // 单引号包住正则交给设备端 shell 的 grep,{}() 在单引号里都是字面量。
            val raw = sh(
                "dumpsys bluetooth_manager | grep -iE '([0-9a-f]{2}:){5}[0-9a-f]{2}'",
                limit = 64 * 1024
            )
            val macRe = Regex("(([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2})\\s*(.*)")
            val seen = LinkedHashMap<String, String>()
            for (line in raw.lines()) {
                val m = macRe.find(line.trim()) ?: continue
                val mac = m.groupValues[1].uppercase()
                if (mac in seen) continue
                seen[mac] = m.groupValues[3].trim().trim('[', ']').trim()
            }
            if (seen.isEmpty()) "没有找到已配对的蓝牙设备(蓝牙可能关着，读不到列表)"
            else seen.entries.joinToString("\n") { (mac, n) -> if (n.isEmpty()) mac else "$n（$mac）" }
        }
        "battery" -> bluetoothBattery()
        "connect", "disconnect" -> {
            if (ctx == null) "error: 没有 Context，连不了设备"
            else if (device.isEmpty()) "error: 要连/断哪个设备？给个名字关键词或 MAC（先用 list_paired 看看有哪些）"
            else {
                val (dev, err) = resolveBondedDevice(device)
                if (dev == null) err
                else {
                    val r = btConnectDisconnect(dev, action == "connect")
                    val label = (dev.name?.takeIf { it.isNotBlank() } ?: dev.address)
                    when {
                        r.isNotEmpty() -> r
                        action == "connect" -> "已向 $label 发起连接，请留意耳机是否连上（异步，可能要一两秒；没连上多半是耳机没在待连状态）"
                        else -> "已向 $label 发起断开"
                    }
                }
            }
        }
        else -> "error: 未知操作 \"$action\""
    }

    // 走框架标准 API 连/断:BluetoothDevice.connect()/disconnect() 是 Android 13+ 的 @SystemApi
    // 隐藏方法,一次连/断该设备所有已启用 profile,需 BLUETOOTH_PRIVILEGED —— 而工具跑在的
    // com.miui.voiceassist 实测 granted=true(dumpsys package 确认)。
    // **为什么不用小米私有的 IMiuiHeadsetService.connect/disconnect**:真机验证过那条路
    // transact 不报错但耳机根本不断(它只管自己那套 MMA/插件会话,不动 A2DP/HFP)。
    // 返回值是 BluetoothStatusCodes,0=SUCCESS。

    /**
     * 在已配对设备里按 MAC(精确)或名字(子串,忽略大小写)解析出一个 BluetoothDevice。
     * 返回 (设备, 错误话术);设备为 null 时第二个是给用户看的原因。
     */
    private fun resolveBondedDevice(query: String): Pair<BluetoothDevice?, String> {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return null to "error: 取不到蓝牙适配器"
        if (!adapter.isEnabled) return null to "蓝牙没开，先打开蓝牙再连"
        val bonded = try {
            adapter.bondedDevices
        } catch (e: SecurityException) {
            return null to "error: 读不到已配对设备（缺 BLUETOOTH_CONNECT 权限）"
        } ?: return null to "error: 读不到已配对设备列表"

        val q = query.trim()
        if (Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(q)) {
            val d = bonded.firstOrNull { it.address.equals(q, ignoreCase = true) }
            return if (d != null) d to "" else null to "没找到已配对设备 $q"
        }
        val matches = bonded.filter { (it.name ?: "").contains(q, ignoreCase = true) }
        return when {
            matches.isEmpty() ->
                null to "没找到名字含「$q」的已配对设备，用 list_paired 看看有哪些。"
            matches.size > 1 ->
                null to ("「$q」匹配到多个设备：" +
                        matches.joinToString("、") { it.name ?: it.address } +
                        "，说得更具体点或直接给 MAC。")
            else -> matches[0] to ""
        }
    }

    /**
     * 反射调 BluetoothDevice.connect()/disconnect()(隐藏 @SystemApi)。返回空串=成功;
     * 非空=错误话术。connect 是**异步**的:返回 SUCCESS 只代表命令受理,真连上还要一两秒。
     */
    private fun btConnectDisconnect(device: BluetoothDevice, connect: Boolean): String {
        val method = if (connect) "connect" else "disconnect"
        return try {
            val ret = BluetoothDevice::class.java.getMethod(method).invoke(device)
            // Android 13+ 返回 int(BluetoothStatusCodes),0=SUCCESS;老版本可能是 void/boolean
            when (ret) {
                null -> ""                      // void:没抛异常即视为受理
                is Int -> if (ret == 0) "" else "error: 系统拒绝了${if (connect) "连接" else "断开"}（状态码 $ret）"
                is Boolean -> if (ret) "" else "error: 系统未受理${if (connect) "连接" else "断开"}请求"
                else -> ""
            }
        } catch (e: NoSuchMethodException) {
            "error: 这台系统没有 BluetoothDevice.$method（版本太老），连/断指定设备做不了"
        } catch (e: Exception) {
            val c = (e.cause ?: e)
            "error: ${if (connect) "连接" else "断开"}失败：${c.javaClass.simpleName}: ${c.message}"
        }
    }

    /**
     * 读小米私有 Provider 的设备电量。`content query` 输出是
     * `Row: N key=value, key=value, ...`,逐行拆成 map 再格式化。
     * battery/left/right/box 里 -1(或空)表示该项无效,过滤掉只留真实数值。
     */
    private fun bluetoothBattery(): String {
        val raw = sh(
            "content query --uri content://com.android.bluetooth.deviceinfo_provider/deviceinfo",
            limit = 32 * 1024
        )
        // Provider 不存在 / 无权限时 content 会打印 Error 或 No result found
        if (raw.contains("No result found", ignoreCase = true))
            return "没有已连接的蓝牙设备,或系统未记录电量"
        if (raw.contains("Error", ignoreCase = true) || raw.contains("Exception"))
            return "读不到蓝牙设备电量(这台 ROM 可能没有小米的电量 Provider)"

        val lines = raw.lines().filter { it.trimStart().startsWith("Row:") }
        if (lines.isEmpty()) return "没有已连接的蓝牙设备,或系统未记录电量"

        val out = StringBuilder()
        for (line in lines) {
            val body = line.substringAfter("Row:").trim().substringAfter(' ', "").ifEmpty {
                line.substringAfter("Row:").trim()
            }
            // content query 用 ", " 分隔各 key=value
            val kv = HashMap<String, String>()
            for (pair in body.split(", ")) {
                val i = pair.indexOf('=')
                if (i > 0) kv[pair.substring(0, i).trim()] = pair.substring(i + 1).trim()
            }
            val name = kv["name"]?.takeIf { it.isNotBlank() && it != "NULL" }
            val addr = kv["address"]?.takeIf { it.isNotBlank() && it != "NULL" }
            val title = name ?: addr ?: continue

            fun pct(key: String): Int? =
                kv[key]?.trim()?.toIntOrNull()?.takeIf { it in 0..100 }

            val parts = buildList {
                pct("left")?.let { add("左耳 $it%") }
                pct("right")?.let { add("右耳 $it%") }
                pct("box")?.let { add("充电盒 $it%") }
                // 单体设备(非 TWS)用 battery;已经有左右耳时它一般是无效值,不重复
                if (none { it.startsWith("左耳") || it.startsWith("右耳") })
                    pct("battery")?.let { add("电量 $it%") }
            }
            out.append(if (name != null && addr != null) "$name（$addr）" else title)
            out.append("：").append(if (parts.isEmpty()) "电量未知" else parts.joinToString("  "))
            out.append('\n')
        }
        val text = out.toString().trim()
        return text.ifEmpty { "没有已连接的蓝牙设备,或系统未记录电量" }
    }

    // ---------------------------------------------------------------- 快捷开关

    /**
     * 常用系统开关。命令表和解析在 [QuickToggle](纯逻辑,有单测),这里只管执行。
     *
     * 为什么要专门做:文本接管很宽,"打开手电筒"这类话也进我们的模型,而小爱原生的动作
     * 在接管轮会被拦下(见 NativeActionPolicy)。没有这个工具时模型只能拿 set_setting 猜 key、
     * 或 run_shell 自己拼命令 —— 好几轮,还常猜错(只写 airplane_mode_on 不会真进飞行模式)。
     */
    private val quickToggle = Spec(
        name = "quick_toggle",
        description = "开关常用系统功能：Wi-Fi、移动数据、飞行模式、NFC、手电筒、勿扰、深色模式、自动旋转、亮度、自动亮度、省电、定位",
        modelHint = "「打开手电筒」→ target=flashlight action=on；「关掉 Wi-Fi」→ wifi off；" +
                "「开勿扰」→ dnd on；「亮度调到三成」→ target=brightness action=set percent=30；" +
                "「定位开着吗」→ action=status。一句话要动几个开关，就在同一轮里并行调几次。" +
                "这些开关一律用本工具，不要用 set_setting 或 run_shell 代替。" +
                "蓝牙用 bluetooth_control，音量用 set_volume。" +
                "Wi-Fi/移动数据/飞行模式/NFC 是异步切换，返回「已发起」时别打包票说已经连上网。",
        params = listOf(
            Param("target", "string", "要操作的开关", required = true, enum = QuickToggle.TARGETS),
            Param(
                "action", "string",
                "on 打开 / off 关闭 / toggle 切换 / status 查询 / set 设定亮度(仅 brightness)",
                required = true, enum = listOf("on", "off", "toggle", "status", "set")
            ),
            Param("percent", "integer", "亮度百分比 0-100，仅 target=brightness、action=set 时用")
        ),
        // 查状态是只读的,未接管的轮次也该能查
        mutatingWhen = { it.optString("action", "status").trim().lowercase() != "status" },
        handler = { args, ctx -> quickToggleImpl(args, ctx) }
    )

    private fun quickToggleImpl(args: JSONObject, ctx: Context?): String {
        val target = args.optString("target").trim().lowercase()
        val action = args.optString("action", "status").trim().lowercase()
        if (target == QuickToggle.FLASHLIGHT) return torchImpl(action, ctx)
        if (target == QuickToggle.BRIGHTNESS) return brightnessImpl(action, args)
        val sw = QuickToggle.SWITCHES[target]
            ?: return "error: 不认识的开关 \"$target\"，可选：${QuickToggle.TARGETS.joinToString("、")}"

        val cur = sw.parse(sh(sw.statusCmd))
        val want = when (action) {
            "status" -> return "${sw.label}：" + onOff(cur)
            "on" -> true
            "off" -> false
            "toggle" -> cur?.not()
                ?: return "error: 读不到${sw.label}当前状态，没法切换。请让用户明确说打开还是关闭。"
            else -> return "error: ${sw.label}只支持 on / off / toggle / status"
        }
        val verb = if (want) "打开" else "关闭"
        if (cur == want) return "${sw.label}本来就是${onOff(want)}状态，没有改动"

        val (rc, out) = shRc(sw.setCmd(want))
        if (rc != 0 || QuickToggle.looksFailed(out)) {
            return "error: ${verb}${sw.label}失败(exit=$rc)：${out.take(300).ifBlank { "无输出" }}"
        }
        if (sw.async) return "已发起${verb}${sw.label}，几秒内生效"
        // 同步类回读一次:命令正常退出但系统没认(ROM 改过实现)时,不能对用户报成功
        return when (sw.parse(sh(sw.statusCmd))) {
            want -> "已${verb}${sw.label}"
            null -> "已执行${verb}${sw.label}，但读不到新状态，请让用户看一眼确认"
            else -> "error: 命令执行了，但${sw.label}仍是${onOff(!want)}，这台设备可能不支持这样切换"
        }
    }

    private fun onOff(state: Boolean?): String = when (state) {
        true -> "开启"
        false -> "关闭"
        null -> "状态未知"
    }

    /**
     * 手电筒没有 shell 命令,走 CameraManager。setTorchMode 不需要 CAMERA 权限,
     * 但相机正被别的应用占用时会抛 CameraAccessException(由 execute 统一转成 error)。
     */
    private fun torchImpl(action: String, ctx: Context?): String {
        val cm = ctx?.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
            ?: return "error: no camera service"
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "error: 这台设备没有可用的闪光灯"
        val cur = torchState(cm, id)
        val want = when (action) {
            "status" -> return "手电筒：" + onOff(cur)
            "on" -> true
            "off" -> false
            "toggle" -> cur?.not() ?: return "error: 读不到手电筒当前状态，请让用户明确说打开还是关闭。"
            else -> return "error: 手电筒只支持 on / off / toggle / status"
        }
        cm.setTorchMode(id, want)
        return if (want) "已打开手电筒" else "已关闭手电筒"
    }

    /** 手电筒没有同步查询接口,只能注册回调 —— 注册时系统会立刻回报一次当前状态。 */
    private fun torchState(cm: android.hardware.camera2.CameraManager, id: String): Boolean? {
        val result = AtomicReference<Boolean?>(null)
        val latch = CountDownLatch(1)
        val cb = object : android.hardware.camera2.CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == id) { result.set(enabled); latch.countDown() }
            }
            override fun onTorchModeUnavailable(cameraId: String) {
                // 相机被占用时手电筒不可用,当关着处理
                if (cameraId == id) { result.set(false); latch.countDown() }
            }
        }
        cm.registerTorchCallback({ it.run() }, cb)
        try {
            latch.await(800, TimeUnit.MILLISECONDS)
        } finally {
            cm.unregisterTorchCallback(cb)
        }
        return result.get()
    }

    private fun brightnessImpl(action: String, args: JSONObject): String {
        when (action) {
            "status" -> return QuickToggle.describeBrightness(
                sh("settings get system screen_brightness_mode; " +
                        "settings get system screen_brightness_float; settings get system screen_brightness")
            )
            "set" -> {}
            else -> return "error: 调亮度要用 action=set 加 percent；开关自动亮度用 target=auto_brightness"
        }
        if (!args.has("percent")) return "error: 设定亮度需要 percent(0-100)"
        val pct = args.optInt("percent").coerceIn(0, 100)
        val linear = String.format(Locale.US, "%.4f", QuickToggle.percentToLinear(pct))
        // 自动亮度开着时手动设的值会被环境光立刻盖掉,先关
        val (rc, out) = shRc("settings put system screen_brightness_mode 0; cmd display set-brightness $linear")
        if (rc != 0 || QuickToggle.looksFailed(out)) {
            return "error: 设定亮度失败(exit=$rc)：${out.take(300).ifBlank { "无输出" }}"
        }
        return "已把亮度调到 $pct%（同时关闭了自动亮度）"
    }

    /**
     * 跑命令并带回退出码。开关类命令失败时常常只回一行 usage 或干脆不出声,
     * 光看输出分不清成败。退出码取的是最后一条命令的。
     */
    private fun shRc(command: String): Pair<Int?, String> {
        val out = sh("$command; echo \"__rc=\$?\"").trimEnd()
        val m = Regex("__rc=(\\d+)$").find(out) ?: return null to out
        return m.groupValues[1].toIntOrNull() to out.substring(0, m.range.first).trim()
    }

    // ---------------------------------------------------------------- 杂项

    private val currentTime = Spec(
        name = "current_time",
        description = "获取当前系统时间",
        modelHint = "精确到秒，含星期几。用户问「现在几点了」「今天星期几」「今天几号」时用。",
        handler = { _, _ ->
            val fmt = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm:ss", Locale.CHINA)
            fmt.format(Date())
        }
    )

    private val recentNotifications = Spec(
        name = "recent_notifications",
        description = "获取最近的通知",
        modelHint = "含包名、标题、正文。用户问「刚才来了什么通知」「有没有新消息」时用。",
        handler = { _, _ ->
            sh("dumpsys notification --noredact | grep -E 'pkg=|android.title=|android.text=' | head -n 60")
        }
    )

    // ---------------------------------------------------------------- 剪贴板

    /**
     * 剪贴板读写。写入任何时候都稳;**读取**在 Android 10+ 被限成「前台应用/默认输入法」
     * 才准读 —— 我们跑在小爱进程里,正常语音交互时小爱本来就在前台,所以通常读得到;
     * 偶尔被系统按后台拦下时如实回「读不到」,不硬来(root 也绕不开这个焦点判定,
     * 只有 service call clipboard 那种 parcel 解析,版本相关又脆,不值得)。
     */
    private val clipboard = Spec(
        name = "clipboard",
        description = "读取或写入系统剪贴板",
        modelHint = "「我刚复制了什么」「读一下剪贴板」→ action=get；" +
                "「把这段复制到剪贴板」「帮我复制XX」→ action=set 并给 text。" +
                "读取偶尔会因系统后台限制失败，失败就如实说读不到，别编内容。",
        params = listOf(
            Param("action", "string", "get=读取；set=写入", required = true, enum = listOf("get", "set")),
            Param("text", "string", "action=set 时要写入的文本")
        ),
        mutatingWhen = { it.optString("action", "get").trim().lowercase() == "set" },
        handler = { args, ctx ->
            if (ctx == null) return@Spec "error: no context available"
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                ?: return@Spec "error: 拿不到剪贴板服务"
            when (args.optString("action", "get").trim().lowercase()) {
                "set" -> {
                    val text = args.optString("text", "")
                    if (text.isEmpty()) return@Spec "error: set 需要 text"
                    onMainThread(ctx) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("xiaoai", text))
                        "已写入剪贴板：$text"
                    }
                }
                else -> onMainThread(ctx) {
                    if (!cm.hasPrimaryClip()) return@onMainThread "剪贴板是空的"
                    val txt = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                    if (txt.isNullOrEmpty())
                        "剪贴板里没有可读文本(可能是图片，或被系统按后台读取限制拦下)"
                    else "剪贴板内容：$txt"
                }
            }
        }
    )

    /**
     * 把一段操作 marshal 到主线程同步执行。ClipboardManager 要求调用线程有 Looper,
     * 而工具跑在 AiClient 的线程池里没有 —— 直接调会抛。超时/异常都收成错误串返回。
     */
    private fun onMainThread(ctx: Context, block: () -> String): String {
        val latch = CountDownLatch(1)
        val holder = AtomicReference("error: 剪贴板操作超时")
        ctx.mainExecutor.execute {
            holder.set(runCatching(block).getOrElse { "error: ${it.javaClass.simpleName}: ${it.message}" })
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return holder.get()
    }

    // ---------------------------------------------------------------- 定位

    /**
     * "我在哪"、以及所有需要"这里"的问题(天气、附近、路况)。
     *
     * 真机事故:没这个工具时模型只能拿 run_shell 去 curl IP 定位服务,
     * 而这台机器挂着代理(clash),出口 IP 在美国 —— "看看现在天气"被定位到
     * **Santa Clara, California**,然后一路 find /data 翻天气应用的缓存,
     * 六轮烧光回"工具调用超过上限"。IP 定位在有代理/VPN 时是**系统性错误**,
     * 不是精度差一点的问题,再怎么换服务商都救不回来。
     *
     * 走系统定位就没这个问题:工具跑在被 hook 的 com.miui.voiceassist 进程里,
     * 而语音助手本来就要查天气/导航,**它自己就持有 ACCESS_FINE_LOCATION**
     * (实测 granted=true),所以这里直接问 LocationManager 就行,
     * 不需要我们自己的应用去申请权限、也不用弹框。
     *
     * 默认只读 getLastKnownLocation:系统里一直有别的应用在定位,这份缓存通常是
     * 分钟级的,而且**瞬时返回**(实测 dumpsys location 里就有 hAcc=30m 的网络定位)。
     * 只有缓存太旧或压根没有时才去要一次新定位 —— 那要等 GPS/基站,可能好几秒。
     */
    private val getLocation = Spec(
        name = "get_location",
        description = "获取设备当前位置（经纬度，尽量附带地名）",
        modelHint = "用户问天气、附近有什么、我在哪、路况时，先用这个拿位置。" +
                "**不要**用 run_shell 去 curl IP 定位服务：本机可能挂代理，IP 会定到国外，" +
                "拿到的城市是错的。返回的经纬度可以直接用来查天气" +
                "（如 wttr.in 接受「纬度,经度」格式）。",
        params = listOf(
            Param("fresh", "boolean", "强制重新定位（要等几秒），默认 false，用系统缓存")
        ),
        handler = { args, ctx -> locate(ctx, args.optBoolean("fresh", false)) }
    )

    /** 缓存比这个还旧就去要一次新定位。 */
    private const val LOCATION_FRESH_MS = 5 * 60 * 1000L

    /** 等新定位的上限。超了就用手上的缓存,**宁可给个旧位置也别让这一轮空手而归** —— 空手回去模型就该去翻 IP 了。 */
    private const val LOCATION_WAIT_SEC = 8L

    /**
     * 拿设备位置。[weather] 也用它 —— 天气工具默认查"这里",没有位置就只能回头
     * 去猜 IP,那正是这个工具要根治的毛病。
     */
    private fun deviceLocation(ctx: Context, fresh: Boolean): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        // 各家 provider 的缓存互相独立,挨个问一遍取最新的那份。
        // FUSED 通常最准(系统融合过),但不保证有,所以不能只问它。
        var best: Location? = null
        for (p in listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )) {
            val l = try {
                lm.getLastKnownLocation(p)
            } catch (t: Throwable) {
                null      // 没权限或 provider 不存在,换下一个
            } ?: continue
            if (best == null || l.time > best.time) best = l
        }

        val stale = best == null || System.currentTimeMillis() - best.time > LOCATION_FRESH_MS
        if (fresh || stale) currentLocation(lm, ctx)?.let { best = it }
        return best
    }

    private fun locate(ctx: Context?, fresh: Boolean): String {
        if (ctx == null) return "error: 没有 Context，取不到系统定位服务"
        val loc = deviceLocation(ctx, fresh)
            ?: return "error: 系统没有可用位置（定位服务可能关着，或宿主没有定位权限）"

        val sb = StringBuilder()
        placeName(ctx, loc)?.let { sb.append(it).append('\n') }
        sb.append(String.format(Locale.US, "经纬度: %.6f, %.6f\n", loc.latitude, loc.longitude))
        if (loc.hasAccuracy()) sb.append(String.format(Locale.US, "精度: ±%.0f 米\n", loc.accuracy))
        // 新鲜度要如实说:模型据此决定"这个位置够不够用"、要不要 fresh=true 再来一次
        val ageMin = (System.currentTimeMillis() - loc.time) / 60000
        sb.append("来源: ").append(loc.provider ?: "未知")
            .append("，").append(if (ageMin <= 0) "刚刚更新" else "${ageMin} 分钟前更新")
        return sb.toString()
    }

    /**
     * 要一次新定位。用 API 30+ 的 getCurrentLocation:它自带超时/取消,
     * 比 requestLocationUpdates 再自己拆监听省事,而且**接受 Executor**,
     * 不需要调用线程有 Looper —— 工具跑在线程池里,没有 Looper。
     */
    private fun currentLocation(lm: LocationManager, ctx: Context): Location? {
        val provider = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER
        ).firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null
        return try {
            val latch = CountDownLatch(1)
            val holder = AtomicReference<Location?>(null)
            val signal = CancellationSignal()
            lm.getCurrentLocation(provider, signal, ctx.mainExecutor) { l ->
                holder.set(l)
                latch.countDown()
            }
            if (!latch.await(LOCATION_WAIT_SEC, TimeUnit.SECONDS)) {
                signal.cancel()
                Log.i(TAG, "getCurrentLocation timed out on $provider")
            }
            holder.get()
        } catch (t: Throwable) {
            Log.w(TAG, "getCurrentLocation failed: $t")
            null
        }
    }

    // ---------------------------------------------------------------- 天气

    /**
     * 天气。数据来自 wttr.in（免费、不用 key）。
     *
     * 用 `format=j1` 的 JSON 而不是那个好看的单行格式:单行格式的**单位由服务端按
     * 客户端 IP 猜**,而这台机器挂着代理,实测回的是 `+88°F ↑12mph` —— 华氏度和英里。
     * j1 里 `temp_C`/`temp_F` 两套都给,单位由我们自己挑,代理再怎么绕都不影响。
     *
     * 不填 location 就用 [deviceLocation] 拿到的经纬度。这是这个工具存在的主要理由:
     * 模型自己拼 curl 时会去问 IP 定位服务,而代理出口在美国,"看看现在天气"
     * 一路答成加州的天气(真机事故,见 [getLocation] 上面那段)。
     */
    private val weather = Spec(
        name = "weather",
        description = "查询天气（当前实况 + 未来几天）",
        modelHint = "用户问天气、要不要带伞、明天冷不冷时用，一次就够，不用再调别的工具。" +
                "**不填 location 就是查用户当前所在地**（内部走系统定位，不是 IP 定位）；" +
                "只有用户明确问别的城市时才填 location。",
        params = listOf(
            Param("location", "string", "地点，如「北京」「Shenzhen」或「纬度,经度」。留空 = 当前位置"),
            Param("days", "integer", "要几天预报，1~3，默认 2（今天和明天）")
        ),
        handler = { args, ctx ->
            weatherReport(
                ctx,
                args.optString("location", "").trim(),
                args.optInt("days", 2).coerceIn(1, 3)
            )
        }
    )

    private fun weatherReport(ctx: Context?, location: String, days: Int): String {
        if (ctx == null) return "error: 没有 Context，无法查询系统天气。"
        return xiaomiWeatherReport(ctx, days)
            ?: "error: 查询小米系统天气失败，未能获取到天气数据。"
    }

    /**
     * 从小米系统原生天气 ContentProvider (content://weather/actualWeatherData/1) 查询天气
     * 文档参考: https://zhuti.designer.xiaomi.com/docs/blog/weatherApi.html
     */
    private fun xiaomiWeatherReport(ctx: Context, days: Int): String? {
        return try {
            val uri = Uri.parse("content://weather/actualWeatherData/1")
            val cursor = ctx.contentResolver.query(uri, null, null, null, null) ?: return null
            cursor.use { c ->
                if (!c.moveToFirst()) return null

                val cityNameIdx = c.getColumnIndex("city_name")
                val tempIdx = c.getColumnIndex("temperature")
                val descIdx = c.getColumnIndex("description")
                val humidityIdx = c.getColumnIndex("humidity")
                val windIdx = c.getColumnIndex("wind")
                val aqiIdx = c.getColumnIndex("aqilevel")
                val sunriseIdx = c.getColumnIndex("sunrise")
                val sunsetIdx = c.getColumnIndex("sunset")

                val cityName = if (cityNameIdx != -1) c.getString(cityNameIdx) else null
                val temp = if (tempIdx != -1) c.getString(tempIdx) else null
                val desc = if (descIdx != -1) c.getString(descIdx) else null

                if (cityName.isNullOrBlank() && temp.isNullOrBlank()) return null

                val sb = StringBuilder()
                sb.append(cityName ?: "当前位置").append('\n')

                sb.append("现在 ").append(temp?.let { if (it.endsWith("℃")) it else "${it}°C" } ?: "")
                if (!desc.isNullOrBlank()) sb.append("，").append(desc)

                if (humidityIdx != -1) {
                    val h = c.getString(humidityIdx)
                    if (!h.isNullOrBlank()) sb.append("，湿度 ").append(if (h.endsWith("%")) h else "${h}%")
                }
                if (windIdx != -1) {
                    val w = c.getString(windIdx)
                    if (!w.isNullOrBlank()) sb.append("，").append(w)
                }
                if (aqiIdx != -1) {
                    val aqi = c.getString(aqiIdx)
                    if (!aqi.isNullOrBlank() && aqi != "0") sb.append("，AQI ").append(aqi)
                }
                sb.append('\n')

                val dayIdx = c.getColumnIndex("day")
                val tmphighsIdx = c.getColumnIndex("tmphighs")
                val tmplowsIdx = c.getColumnIndex("tmplows")
                val weatherFromIdx = c.getColumnIndex("weathernamesfrom")

                var dayCount = 0
                do {
                    val dayOffset = if (dayIdx != -1) c.getInt(dayIdx) else (dayCount + 1)
                    if (dayOffset in 1..days) {
                        val dayLabel = when (dayOffset) {
                            1 -> "今天"
                            2 -> "明天"
                            3 -> "后天"
                            else -> "第${dayOffset}天"
                        }
                        val high = if (tmphighsIdx != -1) c.getString(tmphighsIdx) else null
                        val low = if (tmplowsIdx != -1) c.getString(tmplowsIdx) else null
                        val wName = if (weatherFromIdx != -1) c.getString(weatherFromIdx) else null

                        if (!high.isNullOrBlank() || !low.isNullOrBlank()) {
                            sb.append(dayLabel)
                            if (!low.isNullOrBlank() && !high.isNullOrBlank()) {
                                val cleanLow = low.replace("℃", "")
                                val cleanHigh = high.replace("℃", "")
                                sb.append(" ").append(cleanLow).append("~").append(cleanHigh).append("°C")
                            } else if (!high.isNullOrBlank()) {
                                sb.append(" 最高 ").append(high)
                            }
                            if (!wName.isNullOrBlank()) {
                                sb.append("，").append(wName)
                            }
                            sb.append('\n')
                            dayCount++
                        }
                    }
                } while (c.moveToNext() && dayCount < days)

                if (sunriseIdx != -1 && sunsetIdx != -1) {
                    val srMs = c.getLong(sunriseIdx)
                    val ssMs = c.getLong(sunsetIdx)
                    if (srMs > 0 && ssMs > 0) {
                        val formatTime = { ms: Long ->
                            val totalSec = ms / 1000
                            val hours = (totalSec / 3600) % 24
                            val mins = (totalSec % 3600) / 60
                            String.format(Locale.US, "%02d:%02d", hours, mins)
                        }
                        sb.append("日出 ").append(formatTime(srMs)).append(" 日落 ").append(formatTime(ssMs))
                    }
                }

                sb.toString().trimEnd()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "xiaomiWeatherReport failed", t)
            null
        }
    }

    /** 反查地名的等待上限。 */
    private const val GEOCODE_WAIT_SEC = 3L

    /**
     * 经纬度反查地名。拿不到就返回 null —— 光有经纬度也够查天气了,
     * 不值得为了个地名让整个工具失败。国行设备上 Geocoder 的后端不一定在。
     *
     * 用 API 33 的回调版而不是那个同名的阻塞版:反查要走网络,而这台机器的网络
     * 可能正绕着代理走,阻塞版没有超时参数,后端一卡就把整个工具拖住 ——
     * 定位本身明明已经拿到了,不该为了个锦上添花的地名赔上这一轮。
     */
    private fun placeName(ctx: Context, loc: Location): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            val latch = CountDownLatch(1)
            val holder = AtomicReference<String?>(null)
            Geocoder(ctx, Locale.CHINA).getFromLocation(
                loc.latitude, loc.longitude, 1,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        holder.set(addresses.firstOrNull()?.let { a ->
                            listOfNotNull(a.adminArea, a.locality, a.subLocality, a.thoroughfare)
                                .distinct()
                                .joinToString("")
                                .ifBlank { a.getAddressLine(0) }
                        })
                        latch.countDown()
                    }

                    override fun onError(errorMessage: String?) {
                        Log.i(TAG, "geocode failed: $errorMessage")
                        latch.countDown()
                    }
                }
            )
            if (!latch.await(GEOCODE_WAIT_SEC, TimeUnit.SECONDS)) Log.i(TAG, "geocode timed out")
            holder.get()?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.w(TAG, "geocode failed: $t")
            null
        }
    }

    /**
     * 注意 `mutating = false`。
     *
     * [Spec.mutating] 的语义是"会改变**设备**状态,本轮不归我们就必须拦下"
     * (见上面那段事故注释)。写一条记忆不碰设备,而且用户提到自己信息的那句话
     * 常常正好是小爱自己接管的轮次 —— 标成 mutating 的话恰恰在最该记的时候记不下来。
     */
    private val saveMemory = Spec(
        name = SAVE_MEMORY,
        description = "记住关于用户的长期信息(口味、家庭成员、习惯、称呼等)",
        modelHint = "用户在闲聊中透露出以后还用得上的个人信息时**主动**调用，不用先征求同意，" +
                "也不用在回答里提起「我记住了」。一次一条，写成自包含的陈述句" +
                "（「用户对花生过敏」而不是「对，是的」）。" +
                "只记稳定的事实，不要记一次性的东西（今天几点开会、刚才问的天气）。" +
                "已经记过的会自动去重，不用担心重复。",
        params = listOf(
            Param("content", "string", "要记住的一句话，自包含的陈述句", required = true)
        ),
        handler = { args, ctx ->
            val content = args.optString("content", "").trim()
            if (content.isEmpty()) "error: content 为空"
            else MemoryClient.save(ctx, content)
        }
    )

    // ---------------------------------------------------------------- 短信验证码

    private val readSmsCode = Spec(
        name = "read_sms_code",
        description = "读取最近收到的验证码 / 快递取件码",
        modelHint = "用户问「验证码是多少」「取件码是多少」「刚发来的那个码」时用。" +
                "只回最近几分钟内的数字码,默认 5 分钟。返回里最新的一条排在最前。",
        params = listOf(
            Param("minutes", "integer", "只看最近多少分钟内的短信，默认 5"),
            Param("keyword", "string", "按短信内容关键词过滤(如「取件」「验证码」)，留空不过滤")
        ),
        handler = { args, _ ->
            val minutes = args.optInt("minutes", 5).coerceIn(1, 180)
            val keyword = args.optString("keyword", "").trim()
            readSmsCodes(minutes, keyword)
        }
    )

    /**
     * 走 `content query` 而不是直接读 mmssms.db:那个库常是 WAL、还可能被锁,
     * 直接读文件要 copy+解 WAL 才稳。content provider 自己处理并发,root 下能查。
     * 只抽「码」(4~8 位数字)+ 发信方 + 时间,**不回整条短信正文**(隐私)。
     */
    private fun readSmsCodes(minutes: Int, keyword: String): String {
        val raw = sh(
            "content query --uri content://sms/inbox " +
            "--projection address:body:date --sort \"date DESC\"",
            limit = 64 * 1024
        )
        if (raw.contains("Permission Denial", true) || raw.startsWith("shell error"))
            return "error: 读不到短信(可能没有 root 或短信库不可访问): ${raw.take(160)}"
        val cutoff = System.currentTimeMillis() - minutes * 60_000L
        // content query 每行一条:`Row: 0 address=..., body=..., date=1699...`
        val rows = raw.split(Regex("(?m)^Row: \\d+ ")).map { it.trim() }.filter { it.isNotEmpty() }
        val dateRe = Regex("date=(\\d{10,13})")
        val bodyRe = Regex("body=(.*?), date=", RegexOption.DOT_MATCHES_ALL)
        val addrRe = Regex("address=(.*?), body=", RegexOption.DOT_MATCHES_ALL)
        val codeRe = Regex("(?<![0-9])[0-9]{4,8}(?![0-9])")
        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        val sb = StringBuilder()
        var n = 0
        for (row in rows) {
            val ts = dateRe.find(row)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val tsMs = if (ts < 1_000_000_000_000L) ts * 1000 else ts
            if (tsMs < cutoff) break   // 已按 date 倒序,更旧的不用再看
            val body = bodyRe.find(row)?.groupValues?.get(1) ?: continue
            if (keyword.isNotEmpty() && !body.contains(keyword)) continue
            val code = codeRe.find(body)?.value ?: continue
            val addr = addrRe.find(row)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { "未知号码" }
            sb.append("【").append(code).append("】 来自 ").append(addr)
                .append("（").append(fmt.format(Date(tsMs))).append("）")
                .append(if (n == 0) "  ← 最新" else "").append('\n')
            if (++n >= 5) break
        }
        return if (n == 0) "最近 $minutes 分钟内没有找到含数字码的短信" else sb.toString()
    }

    // ---------------------------------------------------------------- 屏幕文本

    private val getScreenContent = Spec(
        name = "get_screen_content",
        description = "读取当前屏幕上的文字内容",
        modelHint = "用户问「屏幕上这个报错什么意思」「帮我翻译屏幕上这段」「当前页面这个价格多少」时用。" +
                "通过无障碍服务提取**前台应用**界面的文本(自动跳过小爱自己的悬浮窗)。" +
                "纯 Canvas / 游戏画面可能取不到文字。需要无障碍服务已开启。",
        handler = { _, ctx ->
            if (ctx == null) return@Spec "error: no context available"
            // 跨进程:提取动作在模块自己的无障碍服务里,经 ConfigProvider 过桥(同 send_message)
            val extras = android.os.Bundle().apply {
                putBoolean("foreground", true)   // 取前台应用窗口,不是小爱悬浮窗
                putBoolean("reveal", true)        // 要真实文本(默认桥接是脱敏的)
            }
            val out = ctx.contentResolver.call(
                android.net.Uri.parse("content://io.mo.xiaoaiplug.config"),
                "ui_dump", null, extras
            )
            out?.getString("result") ?: "error: 模块进程无响应（无障碍服务可能没开）"
        }
    )

    // ---------------------------------------------------------------- 系统日志

    private val getLogcat = Spec(
        name = "get_logcat",
        description = "抓取系统日志(logcat)，用于排查崩溃/报错",
        modelHint = "用户问「刚才这个应用怎么闪退的」「看下报错日志」「抓一下 XX 的日志」时用。" +
                "默认只取最近 200 行 Error 级。可按 tag 或应用包名过滤。",
        params = listOf(
            Param("lines", "integer", "取最近多少行，默认 200"),
            Param("priority", "string", "最低日志级别，默认 E", enum = listOf("V", "D", "I", "W", "E")),
            Param("tag", "string", "只看某个 TAG，留空不限"),
            Param("package", "string", "只看某个应用(按其运行进程过滤)，留空不限")
        ),
        handler = { args, _ -> runLogcat(args) }
    )

    private fun runLogcat(args: JSONObject): String {
        val lines = args.optInt("lines", 200).coerceIn(1, 2000)
        val pri = args.optString("priority", "E").trim().uppercase()
            .let { if (it in setOf("V", "D", "I", "W", "E")) it else "E" }
        val tag = args.optString("tag", "").trim()
        val pkg = args.optString("package", "").trim()
        val filter = if (tag.isNotEmpty())
            "${shellQuote("$tag:$pri")} ${shellQuote("*:S")}"
        else shellQuote("*:$pri")
        val base = "logcat -d -v time -t $lines"
        val cmd = if (pkg.isNotEmpty())
            "P=\$(pgrep -f ${shellQuote(pkg)} | head -1); " +
                "if [ -z \"\$P\" ]; then echo '(该应用当前没有运行的进程，无法按它过滤)'; " +
                "else $base --pid=\$P $filter; fi"
        else "$base $filter"
        return sh(cmd)
    }

    // ---------------------------------------------------------------- 应用状态控制

    /**
     * 冻结/强杀的硬黑名单:这些一旦被停用或强杀会导致系统异常、甚至把本模块或小爱自己弄死。
     * force_stop 对它们也一律拒绝 —— 强杀 systemui/home 同样会黑屏。
     */
    private val PROTECTED_PKGS = setOf(
        "android", "com.miui.voiceassist", "io.mo.xiaoaiplug",
        "com.miui.home", "com.android.systemui", "com.android.settings",
        "com.lbe.security.miui", "com.miui.securitycenter"
    )

    private val appStateControl = Spec(
        name = "app_state_control",
        description = "强制停止 / 冻结 / 解冻应用，或清理缓存",
        modelHint = "「强行关掉抖音」「杀掉后台的微信」→ action=force_stop；" +
                "「把XX冻结起来」→ freeze；「解冻XX」→ unfreeze；" +
                "「清理缓存/清理垃圾」→ trim_cache(不需要 package)。" +
                "冻结只对第三方应用生效，系统关键应用会被拒绝。",
        params = listOf(
            Param("action", "string", "要执行的操作", required = true,
                enum = listOf("force_stop", "freeze", "unfreeze", "trim_cache")),
            Param("package", "string", "目标应用显示名或包名(trim_cache 不需要)")
        ),
        mutating = true,
        handler = { args, ctx -> runAppStateControl(args, ctx) }
    )

    private fun runAppStateControl(args: JSONObject, ctx: Context?): String {
        val action = args.optString("action", "").trim()
        if (action == "trim_cache") {
            val r = sh("pm trim-caches 100G")
            return "已尝试清理各应用缓存。\n$r"
        }
        val name = args.optString("package", "").trim()
        if (name.isEmpty()) return "error: 需要指定应用(package)"
        val pm = ctx?.packageManager
        val pkg = resolvePackage(pm, name) ?: return "error: 没找到应用 \"$name\""
        if (pkg in PROTECTED_PKGS)
            return "error: $pkg 是系统关键应用，不能对它做强杀/冻结(会导致系统或本功能异常)，已拒绝。"
        return when (action) {
            "force_stop" -> {
                val r = sh("am force-stop ${shellQuote(pkg)}")
                if (r.trimStart().startsWith("error", true)) "error: $r" else "已强制停止 $pkg"
            }
            "freeze" -> {
                val info = pm?.let { runCatching { it.getApplicationInfo(pkg, 0) }.getOrNull() }
                val isSystem = info == null ||
                        (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem)
                    return "error: $pkg 是系统应用，冻结可能导致系统异常，已拒绝。冻结只对第三方应用开放。"
                sh("pm disable-user --user 0 ${shellQuote(pkg)}")
                "已冻结 $pkg（应用被停用、图标消失；说\"解冻$name\"可恢复）"
            }
            "unfreeze" -> {
                sh("pm enable ${shellQuote(pkg)}")
                "已解冻 $pkg"
            }
            else -> "error: 未知操作 \"$action\""
        }
    }

    /** 按显示名或包名把用户说的应用解析成包名:先精确包名 → 精确显示名 → 模糊显示名。 */
    private fun resolvePackage(pm: android.content.pm.PackageManager?, name: String): String? {
        if (pm == null) return null
        val apps = pm.getInstalledApplications(0)
        return apps.firstOrNull { it.packageName == name }?.packageName
            ?: apps.firstOrNull { pm.getApplicationLabel(it).toString().equals(name, true) }?.packageName
            ?: apps.firstOrNull { pm.getApplicationLabel(it).toString().contains(name, true) }?.packageName
    }

    // ---------------------------------------------------------------- shell 实现

    /** 单引号包裹,内部单引号转义,避免路径/值里的空格和元字符把命令拆了。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 优先 root;su 不可用退回普通 shell(很多工具会因此失效,输出里会标注)。
     *
     * `limit` 默认是"直接返回给模型"的上限。工具内部要**先读后解析**时必须显式放大 ——
     * 否则会在解析之前就把数据截掉(见 wifi_info 里那段事故注释)。
     */
    private fun sh(command: String, limit: Int = MAX_TOOL_OUTPUT): String {
        runCatching { return exec(arrayOf("su", "-c", command), limit) }
        return runCatching { "(无 root，结果可能不完整) " + exec(arrayOf("sh", "-c", command), limit) }
            .getOrElse { "shell error: ${it.message}" }
    }

    private fun exec(argv: Array<String>, limit: Int = MAX_TOOL_OUTPUT): String {
        val proc = ProcessBuilder(*argv)
            .redirectErrorStream(true)
            .directory(File("/"))
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(SHELL_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return (output + "\n(命令超时)").take(limit)
        }
        return output.ifBlank { "(无输出, exit=${proc.exitValue()})" }.take(limit)
    }
}
