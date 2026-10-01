package io.mo.xiaoaiplug.config

import org.json.JSONArray
import org.json.JSONObject

/** 显示和正文分开；只有 answer 能进入工具解析、TTS 和跨轮上下文。 */
internal data class AiResponseText(val answer: String, val thinking: String) {
    companion object {
        fun fromMessage(message: JSONObject): AiResponseText = split(
            message.opt("content") as? String ?: "",
            listOf("reasoning_content", "reasoning")
                .mapNotNull { message.opt(it) as? String }
                .filter { it.isNotBlank() }.distinct().joinToString("\n\n")
        )

        /** 只识别正文开头的思考块，正文/代码示例中的标签保持原样。 */
        fun split(content: String, reasoning: String = ""): AiResponseText {
            var rest = content.trimStart()
            val thoughts = ArrayList<String>()
            if (reasoning.isNotBlank()) thoughts.add(reasoning)
            while (rest.isNotEmpty()) {
                val tag = listOf("<think>", "<thinking>").firstOrNull { rest.startsWith(it) }
                if (tag == null) {
                    // 流式开标签可能跨 token；确认不是标签前不把碎片当答案。
                    if (listOf("<think>", "<thinking>").any { it.startsWith(rest) }) rest = ""
                    break
                }
                val close = "</${tag.substring(1)}"
                val end = rest.indexOf(close, tag.length)
                if (end < 0) {
                    var thought = rest.substring(tag.length)
                    // 闭标签跨 token 时，隐藏尚未完整到达的标签后缀。
                    val suffix = (1 until close.length).lastOrNull { thought.endsWith(close.take(it)) }
                    if (suffix != null) thought = thought.dropLast(suffix)
                    if (thought.isNotBlank()) thoughts.add(thought)
                    rest = ""
                    break
                }
                rest.substring(tag.length, end).takeIf { it.isNotBlank() }?.let(thoughts::add)
                rest = rest.substring(end + close.length).trimStart()
            }
            return AiResponseText(rest, thoughts.joinToString("\n\n"))
        }

        /** 保留完整内容块以便工具循环原样回传 thinking/signature/redacted_thinking。 */
        fun fromAnthropic(response: JSONObject): JSONObject {
            val blocks = response.optJSONArray("content") ?: JSONArray()
            val answer = StringBuilder()
            val thinking = ArrayList<String>()
            val calls = JSONArray()
            for (i in 0 until blocks.length()) {
                val block = blocks.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> answer.append(block.opt("text") as? String ?: "")
                    "thinking" -> (block.opt("thinking") as? String)
                        ?.takeIf { it.isNotBlank() }?.let(thinking::add)
                    "tool_use" -> calls.put(JSONObject()
                        .put("id", block.optString("id", "call_$i"))
                        .put("type", "function")
                        .put("function", JSONObject().put("name", block.optString("name"))
                            .put("arguments", block.optJSONObject("input") ?: JSONObject())))
                }
            }
            return JSONObject().put("role", "assistant").put("content", answer.toString())
                .put("anthropic_content", blocks).apply {
                    if (thinking.isNotEmpty()) put("reasoning_content", thinking.joinToString("\n\n"))
                    if (calls.length() > 0) put("tool_calls", calls)
                }
        }
    }
}

/** OpenAI SSE 的正文、思考字段和工具参数分别累积，JSON null 不进入文本。 */
internal class OpenAiResponseStream(private val onText: (AiResponseText) -> Unit) {
    private val content = StringBuilder()
    private val reasoning = LinkedHashMap<String, StringBuilder>()
    private val tools = LinkedHashMap<Int, JSONObject>()

    fun accept(delta: JSONObject) {
        var changed = false
        (delta.opt("content") as? String)?.takeIf { it.isNotEmpty() }?.let {
            content.append(it)
            changed = true
        }
        for (field in listOf("reasoning_content", "reasoning")) {
            (delta.opt(field) as? String)?.takeIf { it.isNotEmpty() }?.let {
                reasoning.getOrPut(field) { StringBuilder() }.append(it)
                changed = true
            }
        }
        val calls = delta.optJSONArray("tool_calls")
        if (calls != null) for (i in 0 until calls.length()) {
            val call = calls.optJSONObject(i) ?: continue
            val acc = tools.getOrPut(call.optInt("index", i)) {
                JSONObject().put("type", "function")
                    .put("function", JSONObject().put("arguments", ""))
            }
            (call.opt("id") as? String)?.takeIf { it.isNotEmpty() }?.let { acc.put("id", it) }
            val fn = call.optJSONObject("function") ?: continue
            val target = acc.getJSONObject("function")
            (fn.opt("name") as? String)?.takeIf { it.isNotEmpty() }?.let { target.put("name", it) }
            (fn.opt("arguments") as? String)?.let {
                target.put("arguments", target.optString("arguments") + it)
            }
        }
        if (changed) onText(AiResponseText.split(content.toString(),
            reasoning.values.map { it.toString() }.distinct().joinToString("\n\n")))
    }

    fun toMessage(): JSONObject = JSONObject().put("role", "assistant")
        .put("content", content.toString()).apply {
            reasoning.forEach { (field, text) -> put(field, text.toString()) }
            if (tools.isNotEmpty()) put("tool_calls", JSONArray().apply {
                tools.values.forEachIndexed { index, call ->
                    if (!call.has("id")) call.put("id", "call_$index")
                    put(call)
                }
            })
        }
}

/** Anthropic SSE 保留块顺序和签名；签名及隐去的思考不会当成文本显示。 */
internal class AnthropicResponseStream(private val onText: (AiResponseText) -> Unit) {
    private val blocks = sortedMapOf<Int, JSONObject>()
    private val toolArgs = HashMap<Int, StringBuilder>()

    fun accept(event: JSONObject) {
        val index = event.optInt("index", 0)
        var changed = false
        when (event.optString("type")) {
            "content_block_start" -> {
                val block = event.optJSONObject("content_block") ?: return
                blocks[index] = JSONObject(block.toString())
                changed = block.optString("type") in listOf("text", "thinking")
            }
            "content_block_delta" -> {
                val delta = event.optJSONObject("delta") ?: return
                val block = blocks[index] ?: return
                val field = when (delta.optString("type")) {
                    "text_delta" -> "text"
                    "thinking_delta" -> "thinking"
                    "signature_delta" -> "signature"
                    "input_json_delta" -> {
                        (delta.opt("partial_json") as? String)?.let {
                            toolArgs.getOrPut(index) { StringBuilder() }.append(it)
                        }
                        null
                    }
                    else -> null
                }
                if (field != null) (delta.opt(field) as? String)?.let {
                    block.put(field, (block.opt(field) as? String ?: "") + it)
                    changed = field != "signature"
                }
            }
            "content_block_stop" -> toolArgs.remove(index)?.let { args ->
                blocks[index]?.put("input", if (args.isBlank()) JSONObject() else JSONObject(args.toString()))
            }
        }
        if (changed) onText(AiResponseText.fromMessage(toMessage()))
    }

    fun toMessage(): JSONObject = AiResponseText.fromAnthropic(JSONObject().put("content",
        JSONArray().apply { blocks.values.forEach { put(it) } }))
}
