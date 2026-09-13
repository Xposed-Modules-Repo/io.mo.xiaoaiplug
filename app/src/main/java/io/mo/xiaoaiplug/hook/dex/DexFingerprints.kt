package io.mo.xiaoaiplug.hook.dex

import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Modifier

private const val TAG = "XiaoAiProbe.Dex"

/**
 * 针对小爱同学混淆类与方法的特征指纹库。
 * 每个指纹通过字符串引用、方法签名、调用关系等强语义特征进行匹配。
 */
object DexFingerprints {

    fun scan(bridge: DexKitBridge, defaultSymbols: TargetSymbols): TargetSymbols {
        val resolved = defaultSymbols.copy()

        // 1. RN 卡片类 (原 TemplateReactNativeCard, 新版 com.xiaomi.voiceassistant.instruction.card.b)
        // 特征: 包含字符串 "TemplateReactNativeCard" 或拥有 rnStartReceiveInstruction 方法,且不是内部类
        runCatching {
            val rnCardClass = bridge.findClass(
                FindClass.create().matcher(
                    ClassMatcher.create().addUsingString("TemplateReactNativeCard")
                )
            ).firstOrNull { it.name.startsWith("com.xiaomi.voiceassistant.instruction.card.") && !it.name.contains("$") }
                ?: bridge.findClass(
                    FindClass.create().matcher(
                        ClassMatcher.create().addMethod(
                            MethodMatcher.create().name("rnStartReceiveInstruction")
                        )
                    )
                ).firstOrNull { it.name.startsWith("com.xiaomi.voiceassistant.instruction.card.") && !it.name.contains("$") }
            if (rnCardClass != null) {
                resolved.rnCardClass = rnCardClass.name
                Log.i(TAG, "Fingerprint matched rnCardClass: ${resolved.rnCardClass}")
            }
        }.onFailure { Log.w(TAG, "Fingerprint scan rnCardClass failed", it) }

        // 2. ASR 处理器 (原 z10.a, 新版 q41.c)
        // 特征: 拥有 processed(Instruction) 方法且内部引用 "SpeechRecognizer.RecognizeResult"
        runCatching {
            val asrMethod = bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create()
                        .name("processed")
                        .addUsingString("SpeechRecognizer.RecognizeResult")
                )
            ).firstOrNull() ?: bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create().addUsingString("SpeechRecognizer.RecognizeResult")
                )
            ).firstOrNull { it.name == "processed" }
            if (asrMethod != null) {
                resolved.asrProcessorClass = asrMethod.className
                Log.i(TAG, "Fingerprint matched asrProcessorClass: ${resolved.asrProcessorClass}")
            }
        }.onFailure { Log.w(TAG, "Fingerprint scan asrProcessorClass failed", it) }

        // 3. RN Bridge (原 r70.a, 新版 ic1.a)
        // 特征: 拥有 sendStreamData(String, String) 方法
        runCatching {
            val bridgeMethod = bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create()
                        .name("sendStreamData")
                        .addParamType("java.lang.String")
                        .addParamType("java.lang.String")
                )
            ).firstOrNull()
            if (bridgeMethod != null) {
                resolved.bridgeClass = bridgeMethod.className
                Log.i(TAG, "Fingerprint matched bridgeClass: ${resolved.bridgeClass}")
            }
        }.onFailure { Log.w(TAG, "Fingerprint scan bridgeClass failed", it) }

        // 4. 音频音轨管理器 (原 v20.e, 新版 s51.f)
        // 特征: 包含静态方法 getMainAudioTrack 或引用特定音轨名 "toastStreamTts"
        runCatching {
            val trackMethod = bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create()
                        .name("getMainAudioTrack")
                        .modifiers(Modifier.STATIC or Modifier.PUBLIC)
                )
            ).firstOrNull()
            if (trackMethod != null) {
                resolved.audioTrackManagerClass = trackMethod.className
                Log.i(TAG, "Fingerprint matched audioTrackManagerClass: ${resolved.audioTrackManagerClass}")
            } else {
                val trackClass = bridge.findClass(
                    FindClass.create().matcher(
                        ClassMatcher.create().addUsingString("toastStreamTts")
                    )
                ).firstOrNull()
                if (trackClass != null) {
                    resolved.audioTrackManagerClass = trackClass.name
                    Log.i(TAG, "Fingerprint (by string) matched audioTrackManagerClass: ${resolved.audioTrackManagerClass}")
                }
            }
        }.onFailure { Log.w(TAG, "Fingerprint scan audioTrackManagerClass failed", it) }

        // 5. ToastStreamPlayer 播放器 (原 la0.n1, 新版 wf1.v1)
        // 特征: 拥有 speakTts(String)Ljava/lang/String; 方法或拥有 getToastStreamAudioTrackTask
        runCatching {
            val playerMethod = bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create()
                        .name("speakTts")
                        .addParamType("java.lang.String")
                        .returnType("java.lang.String")
                )
            ).firstOrNull { !it.className.contains("$") }
                ?: bridge.findClass(
                    FindClass.create().matcher(
                        ClassMatcher.create().addMethod(
                            MethodMatcher.create().name("getToastStreamAudioTrackTask")
                        )
                    )
                ).firstOrNull { !it.name.contains("$") }?.let { cls ->
                    bridge.findMethod(
                        FindMethod.create().matcher(
                            MethodMatcher.create().name("speakTts")
                        )
                    ).firstOrNull { it.className == cls.name }
                }
            if (playerMethod != null) {
                resolved.toastStreamPlayerClass = playerMethod.className
                Log.i(TAG, "Fingerprint matched toastStreamPlayerClass: ${resolved.toastStreamPlayerClass}")
            }
        }.onFailure { Log.w(TAG, "Fingerprint scan toastStreamPlayerClass failed", it) }

        // 6. AgentActionManager (原 kh0.s0, 新版 eo1.w0)
        // 特征: 拥有 executeActionsAsync 方法
        runCatching {
            val actionMethod = bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create().name("executeActionsAsync")
                )
            ).firstOrNull()
            if (actionMethod != null) {
                resolved.agentActionClass = actionMethod.className
                Log.i(TAG, "Fingerprint matched agentActionClass: ${resolved.agentActionClass}")
            }
        }.onFailure { Log.w(TAG, "Fingerprint scan agentActionClass failed", it) }

        // fakeDialogId 在很多不相关类中出现，必须用操作器自身的日志标识。
        runCatching {
            val toastOpClass = bridge.findClass(
                FindClass.create().matcher(
                    ClassMatcher.create().addUsingString("TemplateToastOperation")
                )
            ).singleOrNull { !it.name.contains("$") }
            if (toastOpClass != null) {
                resolved.toastOperationClass = toastOpClass.name
                Log.i(TAG, "Fingerprint matched toastOperationClass: ${resolved.toastOperationClass}")
            }
        }.onFailure { Log.w(TAG, "Fingerprint scan toastOperationClass failed", it) }

        // 8. UI Nav 杀后台操作 (原 jb0.ue)
        // 特征: 包含 OPEN_BACKGROUND_APPS 字符引用
        runCatching {
            val navClass = bridge.findClass(
                FindClass.create().matcher(
                    ClassMatcher.create().addUsingString("OPEN_BACKGROUND_APPS")
                )
            ).firstOrNull()
            if (navClass != null) {
                resolved.uiNavOperationClass = navClass.name
                Log.i(TAG, "Fingerprint matched uiNavOperationClass: ${resolved.uiNavOperationClass}")
            }
        }.onFailure { Log.w(TAG, "Fingerprint scan uiNavOperationClass failed", it) }

        // 9. SpeakContentManager (原 b2, 新版 com.xiaomi.voiceassistant.instruction.utils.x2)
        // 特征: 包含 addFragment(String, String) 和 clean()
        runCatching {
            val speakClass = bridge.findClass(
                FindClass.create().matcher(
                    ClassMatcher.create()
                        .addMethod(
                            MethodMatcher.create()
                                .name("addFragment")
                                .addParamType("java.lang.String")
                                .addParamType("java.lang.String")
                        )
                        .addMethod(
                            MethodMatcher.create()
                                .name("clean")
                        )
                )
            ).firstOrNull()
            if (speakClass != null) {
                resolved.speakContentClass = speakClass.name
                Log.i(TAG, "Fingerprint matched speakContentClass: ${resolved.speakContentClass}")
            }
        }.onFailure { Log.w(TAG, "Fingerprint scan speakContentClass failed", it) }

        // 10. IntentUtils (原 m2, 新版 t2)
        // 特征: 位于 com.xiaomi.voiceassistant.utils 包下且拥有 startActivitySafely(Intent, String)
        runCatching {
            val intentUtilsClass = bridge.findClass(
                FindClass.create().matcher(
                    ClassMatcher.create()
                        .addMethod(
                            MethodMatcher.create()
                                .name("startActivitySafely")
                                .addParamType("android.content.Intent")
                                .addParamType("java.lang.String")
                        )
                )
            ).firstOrNull { it.name.startsWith("com.xiaomi.voiceassistant.utils.") }
            if (intentUtilsClass != null) {
                resolved.intentUtilsClass = intentUtilsClass.name
                Log.i(TAG, "Fingerprint matched intentUtilsClass: ${resolved.intentUtilsClass}")
            }
        }.onFailure { Log.w(TAG, "Fingerprint scan intentUtilsClass failed", it) }

        // 11. ChatDbManager (原 com.xiaomi.voiceassistant.skills.model.chat.a)
        // 特征: 包含数据库表名 "CHAT_MESSAGE_BEAN" 且不是 DAO 实体类
        runCatching {
            val chatDbClass = bridge.findClass(
                FindClass.create().matcher(
                    ClassMatcher.create()
                        .addUsingString("CHAT_MESSAGE_BEAN")
                        .addMethod(MethodMatcher.create().name("recordToSpeak"))
                )
            ).firstOrNull { !it.name.contains("$") }
            if (chatDbClass != null) {
                resolved.chatDbManagerClass = chatDbClass.name
                Log.i(TAG, "Fingerprint matched chatDbManagerClass: ${resolved.chatDbManagerClass}")
            }
        }.onFailure { Log.w(TAG, "Fingerprint scan chatDbManagerClass failed", it) }

        // 12. FlowTemplateToastCard (原 FlowTemplateToastCard, 新版 com.xiaomi.voiceassistant.instruction.card.stream.b)
        // 特征: 拥有 updateCardText(String) 方法
        runCatching {
            val flowCardClass = bridge.findClass(
                FindClass.create().matcher(
                    ClassMatcher.create()
                        .addUsingString("FlowTemplateToastCard")
                        .addMethod(
                            MethodMatcher.create()
                                .name("updateCardText")
                                .addParamType("java.lang.String")
                        )
                )
            ).firstOrNull { it.name.contains("card") }
            if (flowCardClass != null) {
                resolved.flowToastCardClass = flowCardClass.name
                Log.i(TAG, "Fingerprint matched flowToastCardClass: ${resolved.flowToastCardClass}")
            }
        }.onFailure { Log.w(TAG, "Fingerprint scan flowToastCardClass failed", it) }

        // 13. 新版结果分发器与浮窗容器，不能沿用旧版本的混淆类名。
        runCatching {
            bridge.findClass(FindClass.create().matcher(
                ClassMatcher.create().addUsingString("ResultCardManagerController")
                    .addMethod(MethodMatcher.create().name("addCard")
                        .addParamType("com.xiaomi.voiceassistant.card.a"))
            )).singleOrNull { !it.name.contains("$") }?.let {
                resolved.flowControllerClass = it.name
            }
        }.onFailure { Log.w(TAG, "Fingerprint scan flowControllerClass failed", it) }

        return resolved
    }
}
