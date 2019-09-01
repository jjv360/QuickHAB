import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.awt.event.KeyEvent
import java.awt.event.MouseListener
import javax.imageio.ImageIO
import javax.swing.JPopupMenu
import com.sun.webkit.Invoker.setInvoker
import dorkbox.systemTray.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.lang.Exception
import java.net.HttpURLConnection
import java.net.URL
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.UIManager
import java.util.logging.Level.SEVERE
import sun.util.logging.PlatformLogger
import java.awt.Image
import java.awt.event.ActionListener
import java.io.InputStream
import java.net.URLDecoder
import java.util.*
import java.util.jar.Manifest


/**
 * Manages integration with OpenHAB.
 */
class OpenHAB {

    class Item {
        var id = ""
        var name = ""
        var tags : List<String> = listOf()
    }

    /** Preferences */
    private val prefs = Prefs("QuickHAB")

    /** Tray icon */
    private val tray : SystemTray

    /** Server base address */
    private var serverAddress = prefs.props.getProperty("server", "http://localhost:8080")

    /** Last fetch error */
    private var error = ""

    /** List of items with our tag */
    private var items : List<Item> = listOf()

    /** The tag that an Item must have in order to show up in our list */
    private var queryTag = prefs.props.getProperty("queryTag", "QuickHAB")

    init {

        // Make Swing components look native
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())

        // Create tray icon
        tray = SystemTray.get()
        tray.setTooltip("QuickHAB")
        val image = ImageIO.read(OpenHAB::class.java.getResourceAsStream("icon.png"))
        val image2 = image.getScaledInstance(tray.trayImageSize, -1, Image.SCALE_SMOOTH)
        tray.setImage(image2)

        // Create menu
        createMenu()

        // Update UI
        refresh()

    }

    /** Get app version */
    val version : String get() {

        try {

            // Fetch value from manifest
            return Manifest(this.javaClass.getResourceAsStream("META-INF/MANIFEST.MF")).mainAttributes.getValue("Implementation-Version")

        } catch (err : Exception) {

            // No manifest
            return "(unpackaged)"

        }

    }

    /** Generates the right-click menu */
    fun createMenu() {

        // Reset menu
        while (tray.menu.first != null)
            tray.menu.remove(tray.menu.first)

        // Add each item
        items.filter { it.tags.contains(queryTag) }.forEach { item ->

            val menuItem = MenuItem(item.name)
            menuItem.callback = ActionListener {
                executeAction(item)
            }
            tray.menu.add(menuItem)

        }

        // No items found?
        if (items.filter { it.tags.contains(queryTag) }.isEmpty()) {

            // Add no items found indicator
            val noItem = MenuItem(if (error.isBlank()) "No Items found" else "Unable to fetch Items")
            noItem.enabled = error.isNotBlank()
            noItem.callback = ActionListener {
                JOptionPane.showMessageDialog(null, error, "Unable to fetch Items", JOptionPane.WARNING_MESSAGE)
            }
            tray.menu.add(noItem)

        }

        // Create divider
        tray.menu.add(Separator())

        // Create refresh button
        val refresh = MenuItem("Refresh")
        refresh.callback = ActionListener { refresh() }
        tray.menu.add(refresh)

        // Create settings button
        val settings = Menu("Settings")
        tray.menu.add(settings)

        // Settings menu - server address
        val server = MenuItem("Server: $serverAddress")
        server.callback = ActionListener { onServer() }
        settings.add(server)

        // Settings menu - tag name
        val tag = MenuItem("Tag: $queryTag")
        tag.callback = ActionListener { onTagChange() }
        settings.add(tag)

        if (items.isNotEmpty()) {

            // Settings menu - divider
            settings.add(Separator())

            // Settings menu - text
            val desc = MenuItem("Items with the '${queryTag}' tag:")
            desc.enabled = false
            settings.add(desc)

            // Settings menu - all items
            items.forEach { item ->

                val menuItem = Checkbox(item.name)
                menuItem.checked = item.tags.contains(queryTag)
                menuItem.callback = ActionListener {
                    menuItem.checked = item.tags.contains(queryTag)
                    toggleTag(item)
                }
                settings.add(menuItem)

            }

        }

        // Create about button
        val about = MenuItem("About")
        about.callback = ActionListener {
            JOptionPane.showMessageDialog(null, "This app allows you to quickly send ON actions to your OpenHAB items. You can select which items appear in the Settings menu. All items with a '$queryTag' tag attached will appear in the menu.", "QuickHAB $version", JOptionPane.INFORMATION_MESSAGE)
        }
        tray.menu.add(about)

        // Create quit button
        val quit = MenuItem("Quit")
        quit.callback = ActionListener { onQuit() }
        tray.menu.add(quit)

    }

    /** Fetches the list of Items from the OpenHAB instance */
    fun refresh() = GlobalScope.launch {

        // Catch errors
        try {

            // Fetch list of items
            val stream = api("GET", "/items")
            val json = Parser.default().parse(stream) as JsonArray<JsonObject>
            items = json.map {

                // Create an Item for each one
                val item = Item()
                val metadata = it.obj("metadata")
                item.id = it.string("name") ?: "UnnamedItem"
                item.name = metadata?.string("quickhab_name") ?: it.string("label") ?: item.id
                item.tags = it.array<String>("tags") ?: listOf()

                // Done
                return@map item

            }

            // Recreate menu
            error = ""
            createMenu()

        } catch (err : Exception) {

            // Show error
            System.err.println("Unable to fetch Items: " + err.localizedMessage)
            error = err.localizedMessage
            createMenu()

        }

    }

    /** Called when the user presses the Quit menu item */
    fun onQuit() {

        // Remove tray icon
        tray.shutdown()

        // Done, the JVM should exit now that there's no UI stuff going on

    }

    /** Called when the user wants to change the server address */
    fun onServer() = GlobalScope.launch {

        // Ask for new server address
        val address = JOptionPane.showInputDialog(null, "Enter the address to your OpenHAB2 server.\n\nFor remote servers with login, enter in the format: https://user:pass@server.com", serverAddress)
        if (address == null || address.isBlank())
            return@launch

        // Save server
        serverAddress = address
        prefs.props.setProperty("server", serverAddress)
        prefs.save()

        // Refresh
        refresh()

    }

    /** Called when the user wants to change the tag */
    fun onTagChange() = GlobalScope.launch {

        // Ask for new tag
        val value = JOptionPane.showInputDialog(null, "Enter the tag to filter by. Only Items with this tag attached will appear in the menu.", queryTag)
        if (value == null || value.isBlank())
            return@launch

        // Save it
        queryTag = value
        prefs.props.setProperty("queryTag", queryTag)
        prefs.save()

        // Refresh
        refresh()

    }

    /** Toggles the tag on the specified item */
    fun toggleTag(item : Item) = exec {

        // Check if currently set
        if (item.tags.contains(queryTag)) {

            // Remove tag
            api("DELETE", "/items/${item.id}/tags/$queryTag")
            item.tags -= queryTag
            createMenu()

        } else {

            // Add tag
            api("PUT", "/items/${item.id}/tags/$queryTag")
            item.tags += queryTag
            createMenu()

        }

    }

    /** Called when the user wants to execute the action on an item */
    fun executeAction(item : Item) = exec {

        // Run action
        api("POST", "/items/${item.id}", "ON")

    }

    /** Helper function to execute an action and show an error if there is one */
    private fun exec(code : () -> Unit) {

        // Do on background
        GlobalScope.launch {

            // Catch errors
            try {

                // Run code
                code()

            } catch (err : Exception) {

                // Show errors
                System.err.println(err.localizedMessage)
                JOptionPane.showMessageDialog(null, err.localizedMessage, "There was a problem", JOptionPane.WARNING_MESSAGE)

            }

        }

    }

    /** Helper function to execute an API call */
    private fun api(method : String, endpoint : String, body : String = "") : InputStream {

        // Send request
        val url = URL("$serverAddress/rest$endpoint")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method

        // Set authentication if needed
        if (url.userInfo != null) {
            val decodedAuth = URLDecoder.decode(url.userInfo, "UTF8")
            val basicAuth = "Basic " + String(Base64.getEncoder().encode(decodedAuth.toByteArray()))
            conn.setRequestProperty("Authorization", basicAuth)
        }

        // Apply body if it exists
        if (body.isNotBlank()) {
            conn.addRequestProperty("Content-Type", "text/plain")
            conn.doOutput = true
            conn.outputStream.write(body.toByteArray())
        }

        // Check response
        println("[API] $method $endpoint -> ${conn.responseCode}")
        if (conn.responseCode == 200) {
            // Success
        } else if (conn.responseCode == 404) {
            throw Exception("Item not found.")
        } else if (conn.responseCode == 405) {
            throw Exception("Item is not editable.")
        } else {
            throw Exception("Server returned code ${conn.responseCode}.")
        }

        // Return input stream
        return conn.inputStream

    }

}