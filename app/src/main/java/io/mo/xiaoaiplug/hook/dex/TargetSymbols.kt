package io.mo.xiaoaiplug.hook.dex

import org.json.JSONObject

/**
 * 小爱同学中被 Hook 的目标类名与方法名符号映射表。
 * 默认值为当前已知版本的硬编码符号，当 DexKit 动态扫描成功时会被新版符号覆盖。
 */
data class TargetSymbols(
    var operationManagerClass: String = "com.xiaomi.voiceassistant.instruction.base.OperationManager",
    var rnCardClass: String = "com.xiaomi.voiceassistant.instruction.card.b",
    var bridgeClass: String = "fc1.a",
    var audioTrackManagerClass: String = "p51.f",
    var toastStreamPlayerClass: String = "tf1.v1",
    var ttsBridgeClass: String = "com.xiaomi.voiceassistant.l2",
    var asrProcessorClass: String = "n41.c",
    var agentActionClass: String = "bo1.w0",
    var toastOperationClass: String = "ug1.jf",
    var uiNavOperationClass: String = "jb0.ue",
    var speakContentClass: String = "com.xiaomi.voiceassistant.instruction.utils.x2",
    var intentUtilsWrapperClass: String = "com.xiaomi.voiceassistant.instruction.utils.IntentUtilsWrapper",
    var intentUtilsClass: String = "com.xiaomi.voiceassistant.utils.t2",
    var chatDbManagerClass: String = "com.xiaomi.voiceassistant.skills.model.chat.a",
    var flowToastCardClass: String = "com.xiaomi.voiceassistant.instruction.card.stream.b",
    var flowControllerClass: String = "pl1.t0",
    var floatManagerClass: String = "com.xiaomi.voiceassistant.widget.r1"
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("operationManagerClass", operationManagerClass)
        put("rnCardClass", rnCardClass)
        put("bridgeClass", bridgeClass)
        put("audioTrackManagerClass", audioTrackManagerClass)
        put("toastStreamPlayerClass", toastStreamPlayerClass)
        put("ttsBridgeClass", ttsBridgeClass)
        put("asrProcessorClass", asrProcessorClass)
        put("agentActionClass", agentActionClass)
        put("toastOperationClass", toastOperationClass)
        put("uiNavOperationClass", uiNavOperationClass)
        put("speakContentClass", speakContentClass)
        put("intentUtilsWrapperClass", intentUtilsWrapperClass)
        put("intentUtilsClass", intentUtilsClass)
        put("chatDbManagerClass", chatDbManagerClass)
        put("flowToastCardClass", flowToastCardClass)
        put("flowControllerClass", flowControllerClass)
        put("floatManagerClass", floatManagerClass)
    }

    fun getDetailedList(): List<SymbolDetail> = listOf(
        SymbolDetail("asrProcessorClass", "ASR 终态处理器", "接收并分发语音识别终态指令 (RecognizeResult)", asrProcessorClass),
        SymbolDetail("bridgeClass", "RN 通信桥接器", "向 React Native 界面发送 sendStreamData 流式数据", bridgeClass),
        SymbolDetail("rnCardClass", "RN 模板卡片", "大模型问答卡片容器与生命周期管理 (TemplateReactNativeCard)", rnCardClass),
        SymbolDetail("operationManagerClass", "操作管理器", "接收 setQueryInfo 问话并调度下游 Operation", operationManagerClass),
        SymbolDetail("audioTrackManagerClass", "多通道音轨管理器", "维护 main / tts / toastStreamTts 音轨注册表", audioTrackManagerClass),
        SymbolDetail("toastStreamPlayerClass", "Toast 语音播放器", "在 toastStreamTts 独立音轨上播放大模型 TTS", toastStreamPlayerClass),
        SymbolDetail("ttsBridgeClass", "TTS 引擎桥接", "备用主音轨 TTS 语音播放器", ttsBridgeClass),
        SymbolDetail("speakContentClass", "播报内容管理器", "卡片右下角喇叭重播与内容同步管理器", speakContentClass),
        SymbolDetail("toastOperationClass", "Toast 话术卡片操作器", "固定话术卡片构建与接管 (FlowTemplateToastCard)", toastOperationClass),
        SymbolDetail("uiNavOperationClass", "后台应用导航拦截", "拦截杀后台时触发的 OPEN_BACKGROUND_APPS 模拟按键", uiNavOperationClass),
        SymbolDetail("agentActionClass", "Agent 动作执行器", "拦截小爱执行的系统设置跳转 Agent.Action", agentActionClass),
        SymbolDetail("intentUtilsWrapperClass", "设置跳转包装器", "拦截查看类指令跳转系统设置页", intentUtilsWrapperClass),
        SymbolDetail("intentUtilsClass", "Intent 启动工具", "拦截兜底全局搜索与底层 Activity 启动", intentUtilsClass),
        SymbolDetail("chatDbManagerClass", "历史对话数据库", "压制兜底文案并将 AI 答案写回历史对话库", chatDbManagerClass),
        SymbolDetail("flowToastCardClass", "流式结果卡片", "自建或接管的答案上屏 Toast 卡片", flowToastCardClass),
        SymbolDetail("flowControllerClass", "全屏结果流控制器", "全屏主界面卡片渲染容器 (addCard)", flowControllerClass),
        SymbolDetail("floatManagerClass", "悬浮窗管理器", "悬浮窗模式卡片渲染容器 (addCard)", floatManagerClass)
    )

    companion object {
        fun fromJson(json: JSONObject): TargetSymbols {
            val defaults = TargetSymbols()
            return TargetSymbols(
                operationManagerClass = json.optString("operationManagerClass", defaults.operationManagerClass),
                rnCardClass = json.optString("rnCardClass", defaults.rnCardClass),
                bridgeClass = json.optString("bridgeClass", defaults.bridgeClass),
                audioTrackManagerClass = json.optString("audioTrackManagerClass", defaults.audioTrackManagerClass),
                toastStreamPlayerClass = json.optString("toastStreamPlayerClass", defaults.toastStreamPlayerClass),
                ttsBridgeClass = json.optString("ttsBridgeClass", defaults.ttsBridgeClass),
                asrProcessorClass = json.optString("asrProcessorClass", defaults.asrProcessorClass),
                agentActionClass = json.optString("agentActionClass", defaults.agentActionClass),
                toastOperationClass = json.optString("toastOperationClass", defaults.toastOperationClass),
                uiNavOperationClass = json.optString("uiNavOperationClass", defaults.uiNavOperationClass),
                speakContentClass = json.optString("speakContentClass", defaults.speakContentClass),
                intentUtilsWrapperClass = json.optString("intentUtilsWrapperClass", defaults.intentUtilsWrapperClass),
                intentUtilsClass = json.optString("intentUtilsClass", defaults.intentUtilsClass),
                chatDbManagerClass = json.optString("chatDbManagerClass", defaults.chatDbManagerClass),
                flowToastCardClass = json.optString("flowToastCardClass", defaults.flowToastCardClass),
                flowControllerClass = json.optString("flowControllerClass", defaults.flowControllerClass),
                floatManagerClass = json.optString("floatManagerClass", defaults.floatManagerClass)
            )
        }
    }
}

data class SymbolDetail(
    val key: String,
    val name: String,
    val description: String,
    val resolvedClass: String
)
