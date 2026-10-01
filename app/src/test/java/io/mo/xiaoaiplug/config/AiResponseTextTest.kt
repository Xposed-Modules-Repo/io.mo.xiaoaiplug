package io.mo.xiaoaiplug.config

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiResponseTextTest {
    @Test fun openAiThinkingAndToolArgumentsSurviveRoundTripWithoutJsonNull() {
        val frames = ArrayList<AiResponseText>()
        val stream = OpenAiResponseStream(frames::add)
        stream.accept(JSONObject("""{"content":null,"reasoning_content":"先检查", "tool_calls":[{"index":0,"id":"call_1","function":{"name":"device_status","arguments":null}}]}"""))
        stream.accept(JSONObject("""{"reasoning_content":"电量", "tool_calls":[{"index":0,"function":{"name":null,"arguments":"{"}}]}"""))
        stream.accept(JSONObject("""{"reasoning_content":null,"tool_calls":[{"index":0,"function":{"arguments":"}"}}]}"""))
        val message = stream.toMessage()
        assertEquals("先检查电量", message.getString("reasoning_content"))
        assertEquals("", message.getString("content"))
        val call = message.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1", call.getString("id"))
        assertEquals("device_status", call.getJSONObject("function").getString("name"))
        assertEquals("{}", call.getJSONObject("function").getString("arguments"))
        assertEquals(AiResponseText("", "先检查电量"), frames.last())
    }

    @Test fun reasoningAliasIsDisplayedAndRetainedForTheNextToolRequest() {
        val frames = ArrayList<AiResponseText>()
        val stream = OpenAiResponseStream(frames::add)
        stream.accept(JSONObject("""{"reasoning":"计算中"}"""))
        stream.accept(JSONObject("""{"content":"结果是 4"}"""))
        assertEquals(AiResponseText("结果是 4", "计算中"), frames.last())
        assertEquals("计算中", stream.toMessage().getString("reasoning"))
    }

    @Test fun splitTagsAcrossEveryTokenNeverLeakThinkingIntoTheAnswer() {
        for (tag in listOf("think", "thinking")) {
            val frames = ArrayList<AiResponseText>()
            val stream = OpenAiResponseStream(frames::add)
            val raw = "<$tag>先查询电量</$tag>电量是 80%"
            for (character in raw) stream.accept(JSONObject().put("content", character.toString()))
            frames.forEach { frame ->
                assertFalse(frame.answer.contains("查询"))
                assertFalse(frame.answer.contains("<"))
                assertFalse(frame.thinking.contains("</"))
            }
            assertEquals(AiResponseText("电量是 80%", "先查询电量"), frames.last())
            assertEquals(raw, stream.toMessage().getString("content"))
        }
    }

    @Test fun unclosedThinkingDoesNotBecomeASpokenAnswerOrAToolCall() {
        val parts = AiResponseText.fromMessage(JSONObject().put("content",
            "<think>考虑 <tool_call>{\"name\":\"run_shell\"}</tool_call>"))
        assertEquals("", parts.answer)
        assertTrue(parts.thinking.contains("run_shell"))
    }

    @Test fun ordinaryTextAndTagExamplesInCodeRemainAnswers() {
        val raw = "示例：\n```xml\n<think>演示</think>\n```"
        assertEquals(AiResponseText(raw, ""), AiResponseText.split(raw))
        assertEquals(AiResponseText("答案", "第一轮\n\n第二轮"),
            AiResponseText.split("<think>第一轮</think>\n<thinking>第二轮</thinking>答案"))
        assertEquals(AiResponseText("", ""), AiResponseText.fromMessage(
            JSONObject("""{"content":null,"reasoning_content":null,"reasoning":null}""")))
    }

    @Test fun anthropicThinkingSignatureRedactionAndToolInputKeepTheirOriginalOrder() {
        val frames = ArrayList<AiResponseText>()
        val stream = AnthropicResponseStream(frames::add)
        stream.accept(JSONObject("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"先"}}"""))
        stream.accept(JSONObject("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"查询"}}"""))
        stream.accept(JSONObject("""{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed-"}}"""))
        stream.accept(JSONObject("""{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"block"}}"""))
        stream.accept(JSONObject("""{"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"opaque"}}"""))
        stream.accept(JSONObject("""{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"tool_1","name":"device_status","input":{}}}"""))
        stream.accept(JSONObject("""{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{"}}"""))
        stream.accept(JSONObject("""{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":null}}"""))
        stream.accept(JSONObject("""{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"}"}}"""))
        stream.accept(JSONObject("""{"type":"content_block_stop","index":2}"""))
        val message = stream.toMessage()
        val blocks = message.getJSONArray("anthropic_content")
        assertEquals("signed-block", blocks.getJSONObject(0).getString("signature"))
        assertEquals("redacted_thinking", blocks.getJSONObject(1).getString("type"))
        assertEquals("opaque", blocks.getJSONObject(1).getString("data"))
        assertEquals("{}", blocks.getJSONObject(2).getJSONObject("input").toString())
        assertEquals("tool_1", message.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals(AiResponseText("", "先查询"), AiResponseText.fromMessage(message))
        assertEquals(AiResponseText("", "先查询"), frames.last())
    }

    @Test fun anthropicTextAtBlockStartAndMultipleThinkingBlocksAreDisplayed() {
        val frames = ArrayList<AiResponseText>()
        val stream = AnthropicResponseStream(frames::add)
        stream.accept(JSONObject("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"检查完毕","signature":"sig"}}"""))
        stream.accept(JSONObject("""{"type":"content_block_start","index":1,"content_block":{"type":"thinking","thinking":"整理结果","signature":"sig2"}}"""))
        stream.accept(JSONObject("""{"type":"content_block_start","index":2,"content_block":{"type":"text","text":"电量"}}"""))
        stream.accept(JSONObject("""{"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":" 80%"}}"""))
        assertEquals(AiResponseText("电量 80%", "检查完毕\n\n整理结果"), frames.last())
        assertEquals(frames.last(), AiResponseText.fromMessage(stream.toMessage()))
    }
}
