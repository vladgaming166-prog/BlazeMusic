package com.blazemuzix.app.ui

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blazemuzix.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Startup regression test: boots the real [com.blazemuzix.app.BlazeApp], inflates
 * MainActivity with the production theme/layouts and moves it to RESUMED on the
 * oldest and newest Android versions Robolectric can emulate.
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AppLaunchTest {

    @Test
    @Config(sdk = [Build.VERSION_CODES.LOLLIPOP])
    fun launchesOnApi21() = launch()

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun launchesOnApi33() = launch()

    @Test
    @Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
    fun launchesOnApi35() = launch()

    private fun launch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById(R.id.bottom_nav))
                assertNotNull(activity.findViewById(R.id.fragment_container))
                assertEquals(1, activity.supportFragmentManager.fragments.count { it.tag == "home" })
            }
        }
    }
}
