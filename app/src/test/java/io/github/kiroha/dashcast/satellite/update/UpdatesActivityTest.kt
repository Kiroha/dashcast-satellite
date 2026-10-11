package io.github.kiroha.dashcast.satellite.update

import android.app.Application
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import io.github.kiroha.dashcast.satellite.R
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdatesActivityTest {
    private class QueueExecutor : AbstractExecutorService() {
        val tasks = mutableListOf<Runnable>()
        private var stopped = false
        override fun execute(task: Runnable) { tasks.add(task) }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped=true;return mutableListOf() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
        fun run() { tasks.removeAt(0).run(); shadowOf(Looper.getMainLooper()).idle() }
    }
    private class Backend : UpdateBackend {
        var fail = false
        var cancelled = false
        var validations = 0
        val staged = File(RuntimeEnvironment.getApplication().cacheDir,"updates/fake-update.apk")
        val release = UpdateRelease("v0.7.0-dev",ReleaseVersion(0,7,0,true),"Release notes",UpdateAsset("update.apk","url",3,"hash"),null)
        override fun latest(): UpdateRelease { if(fail) error("private_error");return release }
        override fun download(release: UpdateRelease, progress: (Int)->Unit): File {
            staged.parentFile!!.mkdirs();staged.writeText("apk");progress(50);return staged
        }
        override fun validate(file: File, release: UpdateRelease) { validations++;if(fail) error("bad") }
        override fun cancel() { cancelled=true }
    }
    @Before fun permissions() { shadowOf(RuntimeEnvironment.getApplication().packageManager).setCanRequestPackageInstalls(false) }
    @After fun reset() { UpdatesActivity.createSession = { UpdateSession(it) } }
    @Test fun `actual check action survives preparation failure and explicit retry then download`() {
        val backend=Backend();val executor=QueueExecutor();val session=UpdateSession(backend,executor)
        UpdatesActivity.createSession={session}
        val controller=Robolectric.buildActivity(UpdatesActivity::class.java).setup()
        try {
            val activity=controller.get()
            backend.fail=true
            activity.findViewById<View>(R.id.update_check).performClick()
            assertEquals("checking",session.state)
            executor.run()
            assertEquals(activity.getString(R.string.update_error),activity.findViewById<TextView>(R.id.update_status).text)
            backend.fail=false
            activity.findViewById<View>(R.id.update_check).performClick();executor.run()
            assertTrue(activity.findViewById<TextView>(R.id.update_notes).text.contains("v0.7.0-dev"))
            activity.findViewById<View>(R.id.update_download).performClick();executor.run()
            assertEquals("ready",session.state)
            activity.findViewById<View>(R.id.update_install).performClick()
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,shadowOf(activity).nextStartedActivity.action)
            controller.pause().resume()
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals(0,backend.validations)
        } finally {controller.pause().stop().destroy()}
        assertTrue(backend.cancelled)
        assertFalse(backend.staged.exists())
    }
    @Test fun `activity recreation retains pending check and shows completion on recreated screen`() {
        val backend=Backend();val executor=QueueExecutor();val session=UpdateSession(backend,executor)
        var creations=0
        UpdatesActivity.createSession={creations++;session}
        val controller=Robolectric.buildActivity(UpdatesActivity::class.java).setup()
        try {
            controller.get().findViewById<View>(R.id.update_check).performClick()
            controller.recreate()
            executor.run()
            assertEquals(1,creations)
            assertFalse(backend.cancelled)
            assertTrue(controller.get().findViewById<TextView>(R.id.update_notes).text.contains("v0.7.0-dev"))
        } finally {controller.pause().stop().destroy()}
    }
    @Test @Config(sdk=[26,35])
    fun `settings return requires explicit click then verified apk is read shared only once`() {
        val backend=Backend();val executor=QueueExecutor();val session=UpdateSession(backend,executor)
        UpdatesActivity.createSession={session}
        val controller=Robolectric.buildActivity(UpdatesActivity::class.java).setup()
        try {
            val activity=controller.get()
            activity.findViewById<View>(R.id.update_check).performClick();executor.run()
            activity.findViewById<View>(R.id.update_download).performClick();executor.run()
            activity.findViewById<View>(R.id.update_install).performClick()
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,shadowOf(activity).nextStartedActivity.action)
            controller.pause()
            shadowOf(activity.packageManager).setCanRequestPackageInstalls(true)
            controller.resume()
            assertNull(shadowOf(activity).nextStartedActivity)
            activity.findViewById<View>(R.id.update_install).performClick();executor.run()
            val intent=shadowOf(activity).nextStartedActivity
            assertEquals(android.content.Intent.ACTION_VIEW,intent.action)
            assertEquals("content",intent.data!!.scheme)
            assertEquals("${activity.packageName}.updates",intent.data!!.authority)
            assertEquals(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertEquals(0,intent.flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertEquals(1,backend.validations)
            controller.pause().resume()
            assertNull(shadowOf(activity).nextStartedActivity)
            assertThrows(IllegalArgumentException::class.java) {
                androidx.core.content.FileProvider.getUriForFile(activity,"${activity.packageName}.updates",
                    File(activity.noBackupFilesDir,"pairing.json"))
            }
        } finally {controller.pause().stop().destroy()}
    }
    @Test fun `closed session discards a staged download whose main callback is pending`() {
        val backend=Backend();val executor=QueueExecutor();val session=UpdateSession(backend,executor)
        session.check();executor.run();session.download()
        executor.tasks.removeAt(0).run()
        assertTrue(backend.staged.exists())
        session.close();shadowOf(Looper.getMainLooper()).idle()
        assertFalse(backend.staged.exists())
        assertNull(session.file)
    }
    @Test fun `observer replacement during rotation receives completion without retaining old activity`() {
        val backend=Backend();val executor=QueueExecutor();val session=UpdateSession(backend,executor)
        var old=0;var current=0
        session.observer={old++};session.check();session.observer=null
        session.observer={current++};executor.run()
        assertEquals(1,old);assertEquals(1,current);assertEquals("available",session.state)
        session.close()
    }
    @Test fun `verification failure removes staged apk and supports retry`() {
        val backend=Backend();val executor=QueueExecutor();val session=UpdateSession(backend,executor)
        session.check();executor.run();session.download();executor.run()
        backend.fail=true;session.verifyForInstall();executor.run()
        assertEquals("error",session.state);assertNull(session.file);assertFalse(backend.staged.exists())
        session.close()
    }
}
