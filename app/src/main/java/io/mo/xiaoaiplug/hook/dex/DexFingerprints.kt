package io.mo.xiaoaiplug.hook.dex

import android.os.SystemClock
import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Modifier

private const val TAG = "XiaoAiProbe.Dex"

/** Each rule records its own outcome; ambiguous candidates never silently become a match. */
object DexFingerprints {
    fun scan(bridge: DexKitBridge, defaultSymbols: TargetSymbols): DexScanResult {
        val values = defaultSymbols.toJson()
        val states = defaultSymbols.getDetailedList().associate {
            it.key to SymbolScan(SymbolState.DEFAULT)
        }.toMutableMap()

        fun classes(matcher: ClassMatcher) = bridge.findClass(FindClass.create().matcher(matcher)).map { it.name }
        fun methods(matcher: MethodMatcher) = bridge.findMethod(FindMethod.create().matcher(matcher))
        fun owners(matcher: MethodMatcher) = methods(matcher).map { it.className }.distinct()
        fun resolve(key: String, query: () -> List<String>) {
            val start = SystemClock.elapsedRealtime()
            var detail = ""
            val state = try {
                val candidates = query().distinct().sorted()
                val (name, status) = uniqueCandidate(candidates)
                detail = if (candidates.isEmpty()) "未找到符合指纹条件的候选" else "候选: ${candidates.joinToString()}"
                if (name != null) values.put(key, name)
                status
            } catch (t: Throwable) {
                Log.w(TAG, "Fingerprint $key failed", t)
                detail = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
                SymbolState.ERROR
            }
            val duration = SystemClock.elapsedRealtime() - start
            states[key] = SymbolScan(state, duration, detail)
            Log.i(TAG, "Fingerprint $key: $state (${duration}ms) $detail")
        }

        resolve("operationManagerClass") {
            classes(ClassMatcher.create().className("com.xiaomi.voiceassistant.instruction.base.OperationManager")
                .addMethod(MethodMatcher.create().name("setQueryInfo")))
        }
        resolve("intentUtilsWrapperClass") {
            classes(ClassMatcher.create().className("com.xiaomi.voiceassistant.instruction.utils.IntentUtilsWrapper")
                .addMethod(MethodMatcher.create().name("startActivitySafely")))
        }

        resolve("rnCardClass") {
            fun List<String>.cards() = filter {
                it.startsWith("com.xiaomi.voiceassistant.instruction.card.") && !it.contains('$')
            }
            classes(ClassMatcher.create().addUsingString("TemplateReactNativeCard")).cards().ifEmpty {
                classes(ClassMatcher.create().addMethod(
                    MethodMatcher.create().name("rnStartReceiveInstruction"))).cards()
            }
        }
        resolve("asrProcessorClass") {
            // The old fallback repeated this same predicate in Kotlin after a second full query.
            owners(MethodMatcher.create().name("processed")
                .addParamType("com.xiaomi.ai.api.common.Instruction")
                .addUsingString("SpeechRecognizer.RecognizeResult"))
        }
        resolve("bridgeClass") {
            owners(MethodMatcher.create().name("sendStreamData")
                .addParamType("java.lang.String").addParamType("java.lang.String"))
        }
        resolve("audioTrackManagerClass") {
            owners(MethodMatcher.create().name("getMainAudioTrack")
                .modifiers(Modifier.STATIC or Modifier.PUBLIC)).ifEmpty {
                classes(ClassMatcher.create().addUsingString("toastStreamTts"))
            }
        }
        resolve("toastStreamPlayerClass") {
            owners(MethodMatcher.create().name("speakTts").addParamType("java.lang.String")
                .returnType("java.lang.String")).filter { !it.contains('$') }.ifEmpty {
                // Both methods must belong to the same class; no second global method search.
                classes(ClassMatcher.create()
                    .addMethod(MethodMatcher.create().name("getToastStreamAudioTrackTask"))
                    .addMethod(MethodMatcher.create().name("speakTts")))
                    .filter { !it.contains('$') }
            }
        }
        resolve("agentActionClass") {
            // Binder stubs with JSONArray arguments are not the action implementation.
            owners(MethodMatcher.create().name("executeActionsAsync")
                .addParamType("com.xiaomi.ai.api.Agent\$Action").addParamType("java.lang.String"))
        }
        resolve("ttsBridgeClass") {
            val prefix = "com.xiaomi.voiceassistant."
            fun scope() = ClassMatcher.create().className(prefix, StringMatchType.StartsWith)
            val singletonOwners = methods(MethodMatcher.create().name("getInstance").paramCount(0)
                .modifiers(Modifier.PUBLIC or Modifier.STATIC).declaredClass(scope()))
                .filter { it.returnType?.name == it.className }
                .map { it.className }
                .filter { !it.removePrefix(prefix).contains('.') && !it.contains('$') }.toSet()
            fun stopOwners(name: String) = owners(MethodMatcher.create().name(name).paramCount(0)
                .modifiers(Modifier.PUBLIC).declaredClass(scope())).filter { it in singletonOwners }
            // Prefer the specific modern API. Older versions used several different stop names.
            stopOwners("stopTTS").ifEmpty {
                listOf("stop", "stopPlay", "stopSpeak").flatMap { stopOwners(it) }
            }
        }
        resolve("toastOperationClass") {
            classes(ClassMatcher.create().addUsingString("TemplateToastOperation")
                .addMethod(MethodMatcher.create().name("setRedefinedToastText").addParamType("java.lang.String"))
                .addMethod(MethodMatcher.create().name("setNeedChangeToastText").addParamType("boolean")))
                .filter { !it.contains('$') }
        }
        fun navMethod() = MethodMatcher.create().paramCount(0).returnType("void")
            .addUsingNumber(187).addInvoke(MethodMatcher.create().name("setSimulateKeyEvent")
                .addParamType("int"))
        resolve("uiNavOperationClass") {
            // OPEN_BACKGROUND_APPS is an enum field reference, not a string in this class.
            classes(ClassMatcher.create().addUsingString("UIControllerNavigate").addMethod(navMethod()))
        }
        resolve("uiNavMethodName") {
            if (states["uiNavOperationClass"]?.state != SymbolState.MATCHED) emptyList()
            else methods(navMethod().declaredClass(values.getString("uiNavOperationClass"))).map { it.name }
        }
        resolve("speakContentClass") {
            classes(ClassMatcher.create()
                .addMethod(MethodMatcher.create().name("addFragment")
                    .addParamType("java.lang.String").addParamType("java.lang.String"))
                .addMethod(MethodMatcher.create().name("clean")))
        }
        resolve("intentUtilsClass") {
            classes(ClassMatcher.create().addMethod(MethodMatcher.create().name("startActivitySafely")
                .addParamType("android.content.Intent").addParamType("java.lang.String")))
                .filter { it.startsWith("com.xiaomi.voiceassistant.utils.") }
        }
        resolve("chatDbManagerClass") {
            // The table-name literal belongs to the DAO, not the manager that records speech.
            classes(ClassMatcher.create().addUsingString("ChatDbManager")
                .addMethod(MethodMatcher.create().name("recordToSpeak").addParamType("java.lang.String")))
                .filter { !it.contains('$') }
        }
        resolve("flowToastCardClass") {
            classes(ClassMatcher.create().addUsingString("FlowTemplateToastCard")
                .addMethod(MethodMatcher.create().name("updateCardText").addParamType("java.lang.String")))
                .filter { it.contains("card") }
        }
        resolve("flowControllerClass") {
            // Several containers log this name and expose addCard; use the typed accessor.
            methods(MethodMatcher.create().name("getResultCardManagerController").paramCount(0)
                .declaredClass("com.xiaomi.voiceassistant.UiManager"))
                .mapNotNull { it.returnType?.name }
        }
        resolve("floatManagerClass") {
            methods(MethodMatcher.create().name("getFloatManager")
                .declaredClass("com.xiaomi.voiceassistant.UiManager"))
                .ifEmpty { methods(MethodMatcher.create().name("getFloatManager")) }
                .mapNotNull { it.returnType?.name }.filter { it.startsWith("com.xiaomi.voiceassistant.") }
        }
        return DexScanResult(TargetSymbols.fromJson(values), states.toMap())
    }
}
