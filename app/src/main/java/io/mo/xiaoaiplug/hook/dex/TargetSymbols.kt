package io.mo.xiaoaiplug.hook.dex

import org.json.JSONObject

/**
 * 小爱同学中被 Hook 的目标类名与方法名符号映射表。
 * 空值表示尚未验证或未命中；只有本次扫描确认的符号才能用于 Hook。
 */
data class TargetSymbols(
    val operationManagerClass: String = "",
    val rnCardClass: String = "",
    val bridgeClass: String = "",
    val audioTrackManagerClass: String = "",
    val toastStreamPlayerClass: String = "",
    val ttsBridgeClass: String = "",
    val asrProcessorClass: String = "",
    val agentActionClass: String = "",
    val toastOperationClass: String = "",
    val uiNavOperationClass: String = "",
    val uiNavMethodName: String = "",
    val speakContentClass: String = "",
    val intentUtilsWrapperClass: String = "",
    val intentUtilsClass: String = "",
    val chatDbManagerClass: String = "",
    val flowToastCardClass: String = "",
    val flowControllerClass: String = "",
    val floatManagerClass: String = ""
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
        put("uiNavMethodName", uiNavMethodName)
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
        SymbolDetail("uiNavMethodName", "后台应用导航方法", "无参 void 方法，调用 setSimulateKeyEvent(187)", uiNavMethodName),
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
                uiNavMethodName = json.optString("uiNavMethodName", defaults.uiNavMethodName),
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
