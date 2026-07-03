package com.dajiraj.steps_count

import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.mockito.Mockito
import kotlin.test.Test

/**
 * Unit tests for the [StepsCountPlugin] method-channel contract that can run without an Android
 * runtime (no started service, so the static manager is null and these paths take their default
 * branches).
 */
internal class StepsCountPluginTest {

    private fun call(method: String): MethodChannel.Result {
        val plugin = StepsCountPlugin()
        val result = Mockito.mock(MethodChannel.Result::class.java)
        plugin.onMethodCall(MethodCall(method, null), result)
        return result
    }

    @Test
    fun unknownMethod_reportsNotImplemented() {
        val result = call("thisMethodDoesNotExist")
        Mockito.verify(result).notImplemented()
    }

    @Test
    fun getStepSources_isEmptyOnAndroid() {
        // Step sources are an iOS/HealthKit concept; Android returns an empty list.
        val result = call("getStepSources")
        Mockito.verify(result).success(emptyList<Any>())
    }

    @Test
    fun startStepObserver_isNoOpTrueOnAndroid() {
        // iOS-only observer; Android treats it as a successful no-op.
        val result = call("startStepObserver")
        Mockito.verify(result).success(true)
    }

    @Test
    fun isServiceRunning_isFalseByDefault() {
        val result = call("isServiceRunning")
        Mockito.verify(result).success(false)
    }
}
