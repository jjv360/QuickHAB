import java.awt.SystemTray

/**
 * App entry point.
 */
fun main(args : Array<String>) {

    // Check if supported
    if (!SystemTray.isSupported())
        return println("System tray is not supported! Exiting.");

    // Create menu
    OpenHAB()

}