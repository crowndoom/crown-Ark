package app.xodos2.ui.drawer.pages

import android.content.Context
import java.io.File

object DesktopDetector {

    // Known session binaries and their display names
    internal val knownBinaries = mapOf(
        "xfce4-session"      to "XFCE Desktop",
        "lxqt-session"       to "LXQt Desktop",
        "gnome-shell"        to "GNOME",
        "startplasma-x11"    to "KDE Plasma (X11)",
        "startplasma-wayland" to "KDE Plasma (Wayland)",
        "mate-session"       to "MATE",
        "cinnamon-session"   to "Cinnamon",
        "budgie-desktop"     to "Budgie",
        "startlxde"          to "LXDE",
        "enlightenment_start" to "Enlightenment"
    )

    /**
     * Returns a list of [displayName, binaryName] for every desktop
     * environment whose starting binary exists in the container’s /usr/bin.
     */
    fun detectInstalled(context: Context, containerId: Int): List<Pair<String, String>> {
        val rootfs = app.xodos2.ui.runtime.NativeInstallCoordinator
            .containerPath(context, containerId)
        val usrBin = File(rootfs, "usr/bin")
        if (!usrBin.isDirectory) return emptyList()

        return knownBinaries.mapNotNull { (binary, name) ->
            if (File(usrBin, binary).exists()) name to binary else null
        }
    }

    /**
     * Returns a default shell script that sets up the environment
     * and launches the given session binary.
     * Includes common PRoot / container workarounds.
     */
    fun defaultLaunchScript(binary: String): String {
        // Common preamble for most desktops
        val commonSetup = """
        # Common environment
        #export WAYLAND_DISPLAY=wayland-xodos2
        export DISPLAY=:0
        export PULSE_SERVER=127.0.0.1
        # Ensure DBus is running
        killall -9 dbus-daemon dbus-launch dbus*
        rm -f /run/dbus/pid && mkdir -p /run/dbus 
         dbus-daemon --system --fork
        if [ -z "${'$'}DBUS_SESSION_BUS_ADDRESS" ]; then
            eval $(dbus-launch --sh-syntax --exit-with-session)
        fi
        """.trimIndent()

        // Per‑binary tweaks
        val specific = when (binary) {
            "gnome-shell" -> """
                # GNOME workarounds
                killall -9 gnome-session-binary metacity gnome-panel 2>/dev/null              
                export XDG_CURRENT_DESKTOP=GNOME
                export DESKTOP_SESSION=gnome
                export XDG_SESSION_DESKTOP=gnome
                dbus-run-session gnome-shell --x11 &
                """.trimIndent()

            "startplasma-x11" -> """
            killall -9 startplasma-x11 startplasma-wayland startplasma* 2>/dev/null
                export DESKTOP_SESSION=plasma
                export XDG_CURRENT_DESKTOP=KDE
                startplasma-x11 &
                """.trimIndent()

            "startplasma-wayland" -> """
            killall -9 startplasma-x11 startplasma-wayland startplasma* 2>/dev/null
                export DESKTOP_SESSION=plasma
                export XDG_CURRENT_DESKTOP=KDE
                startplasma-wayland &
                """.trimIndent()

            "xfce4-session" -> """
            killall -9 xfce4-session xfce4* 2>/dev/null
                export XDG_CURRENT_DESKTOP=XFCE
                xfce4-session &
                """.trimIndent()

            "lxqt-session" -> """
            killall -9 lxqt-session lxqt* 2>/dev/null
                export XDG_CURRENT_DESKTOP=LXQt
                lxqt-session &
                """.trimIndent()

            "mate-session" -> """
            killall -9 mate-session mate* 2>/dev/null
                export XDG_CURRENT_DESKTOP=MATE
                mate-session &
                """.trimIndent()

            "cinnamon-session" -> """
            killall -9 cinnamon-session cinnamon* 2>/dev/null
                export XDG_CURRENT_DESKTOP=Cinnamon
                cinnamon-session &
                """.trimIndent()

            else -> "dbus-launch --exit-with-session $binary"
        }

        return "$commonSetup\n$specific"
    }
}