package io.github.kiroha.dashcast.satellite.pairing

import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import io.github.kiroha.dashcast.satellite.MainActivity
import io.github.kiroha.dashcast.satellite.R
import io.github.kiroha.dashcast.satellite.SatelliteSettings
import io.github.kiroha.dashcast.satellite.navigation.NavigationObservationBus
import io.github.kiroha.dashcast.satellite.navigation.NavigationSource
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CodePairingActivityTest {
    @After fun clear() { PairingOperation.forget {} }

    @Test fun `code stays only in the live activity while switching screens and is never restored`() {
        val controller = Robolectric.buildActivity(CodePairingActivity::class.java).setup()
        val activity = controller.get()
        val input = activity.findViewById<EditText>(R.id.pairing_code)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertFalse(input.isSaveEnabled)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, input.importantForAutofill)
        assertTrue(input.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        input.setText("123-456")
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop()
        assertEquals("123-456", input.text.toString())
        controller.restart().start().resume().visible()
        assertEquals("123-456", input.text.toString())
        controller.pause().stop()
        controller.destroy()
        val replacement = Robolectric.buildActivity(CodePairingActivity::class.java)
            .create(state).start().restoreInstanceState(state).resume().visible()
        assertEquals("", replacement.get().findViewById<EditText>(R.id.pairing_code).text.toString())
        replacement.pause().stop().destroy()
    }

    @Test fun `invalid code keeps screen editable and does not begin pairing`() {
        val controller = Robolectric.buildActivity(CodePairingActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<EditText>(R.id.pairing_code).setText("1234")
        activity.findViewById<Button>(R.id.code_pair).performClick()
        assertFalse(PairingOperation.busy)
        assertNotNull(activity.findViewById<EditText>(R.id.pairing_code).error)
        assertTrue(activity.findViewById<Button>(R.id.code_pair).isEnabled)
        controller.pause().stop().destroy()
    }

    @Test fun `starting code pairing stops guidance before opening the screen`() {
        val app = RuntimeEnvironment.getApplication()
        SatelliteSettings(app).enabled = true
        NavigationObservationBus.configure(NavigationSource.MAPS, transmittingEnabled = true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.get().findViewById<Button>(R.id.code_pairing).performClick()
        assertFalse(SatelliteSettings(app).enabled)
        assertFalse(NavigationObservationBus.configuration.transmittingEnabled)
        assertEquals(CodePairingActivity::class.java.name,
            shadowOf(controller.get()).nextStartedActivity.component?.className)
        controller.pause().stop().destroy()
    }
}
