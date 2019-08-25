import java.io.File
import java.util.*
import java.util.prefs.Preferences

/**
 * Replacement for the super buggy Java Preferences API, which straight-up doesn't work on Windows 10. And yes, it's
 * a bug in Java, not in Windows 10. Java Preferences tries to access the SYSTEM registry entry even though we just want
 * USER access.
 */
class Prefs(name : String) {

    /** Path to file where preferences are stored */
    val file : File

    /** Properties */
    val props = Properties()

    init {

        // Set file location
        file = File(System.getProperty("user.home"), ".$name")

        // Load from file if needed
        if (file.exists()) {

            // Load from file
            props.load(file.inputStream())

        }

    }

    /** Save changes */
    fun save() {

        // Write changes to file
        props.store(file.outputStream(), "QuickHAB settings")

    }

}