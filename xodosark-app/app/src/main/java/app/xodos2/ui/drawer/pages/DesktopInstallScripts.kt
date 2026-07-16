package app.xodos2.ui.drawer.pages

object DesktopInstallScripts {

    fun buildDesktopInstallScript(distro: String, envName: String): String {
        val cleanDistro = distro.lowercase().trim()

        // Nix environment
        if (cleanDistro.contains("nix")) {
            return "source /nix/var/nix/profiles/default/etc/profile.d/nix.sh 2>/dev/null || true\n" +
                   "nix-channel --update && nix-env -u\n" +
                   "export PULSE_SERVER=127.0.0.1\n" +
                   "echo 'Nix profile packages updated successfully!'\n"
        }

        // Package manager & base deps – aggressive, force‑overwrite versions
        val (managerCmd, baseDeps) = when {
            cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") ||
            cleanDistro.contains("kali") || cleanDistro.contains("trisquel") ->
                Pair(
                    "apt-get update -y && apt-get upgrade -y --allow-downgrades --allow-remove-essential || true\n" +
                    "apt-get install -y --allow-downgrades --allow-remove-essential --option Dpkg::Options::=\"--force-confdef\" --option Dpkg::Options::=\"--force-confold\" --option Dpkg::Options::=\"--force-overwrite\"",
                    "mesa-utils xwayland libvulkan-dev mesa-vulkan-drivers libgl1-mesa-dri libglx-mesa0 libegl-mesa0 vulkan-tools dbus-x11 zip unzip"
                )
            cleanDistro.contains("arch") || cleanDistro.contains("manjaro") || cleanDistro.contains("artix") ->
                Pair(
                    "pacman -Syu --noconfirm --needed --overwrite \"*\" || true\n" +
                    "pacman -S --noconfirm --needed --overwrite \"*\"",
                    "mesa-utils xorg-server xorg-xwayland vulkan-devel mesa vulkan-tools dbus"
                )
            cleanDistro.contains("fedora") || cleanDistro.contains("almalinux") || cleanDistro.contains("rocky") ->
                Pair(
                    "dnf upgrade -y || true\n" +
                    "dnf install -y --allowerasing --skip-broken",
                    "glx-utils xorg-x11-server-Xwayland vulkan-loader-devel mesa-dri-drivers vulkan-tools dbus-x11 zip unzip"
                )
            cleanDistro.contains("alpine") ->
                Pair(
                    "apk update || true\n" +
                    "apk add --force-broken-world --force-non-repository",
                    "mesa-utils xwayland vulkan-loader mesa-dri-gallium vulkan-tools dbus"
                )
            cleanDistro.contains("void") ->
                Pair(
                    "xbps-install -Syu || true\n" +
                    "xbps-install -y -f",
                    "mesa-utils xwayland vulkan-loader mesa-dri vulkan-tools dbus"
                )
            cleanDistro.contains("opensuse") ->
                Pair(
                    "zypper --non-interactive refresh || true\n" +
                    "zypper --non-interactive install --force-resolution --allow-downgrade",
                    "mesa-utils xorg-x11-server-Xwayland vulkan-devel mesa-dri-drivers vulkan-tools dbus-1-x11 zip unzip"
                )
            else ->
                Pair(
                    "apt-get update -y && apt-get upgrade -y --allow-downgrades --allow-remove-essential || true\n" +
                    "apt-get install -y --allow-downgrades --allow-remove-essential --option Dpkg::Options::=\"--force-confdef\" --option Dpkg::Options::=\"--force-confold\" --option Dpkg::Options::=\"--force-overwrite\"",
                    "mesa-utils xwayland libvulkan-dev mesa-vulkan-drivers libgl1-mesa-dri libglx-mesa0 libegl-mesa0 vulkan-tools dbus-x11 zip unzip pulseaudio"
                )
        }

        // ─── Kali‑specific setup (improved) ───
        val gpgSetup = if (cleanDistro.contains("kali")) {
            """

# ==========================================
# ADVANCED PROOT SYSTEMD BYPASSES
# ==========================================

echo "Creating container compatibility stubs..."

# 1. Provide an empty machine-id file if missing
mkdir -p /etc
touch /etc/machine-id

# 2. HARD-STUB the failing configuration engines 
# This handles systemd 260+ sysusers and tmpfiles verification errors
for engine in /usr/bin/systemd-sysusers /usr/bin/systemd-tmpfiles /usr/sbin/dpkg-preconfigure /usr/sbin/pam-auth-update /usr/bin/linux-version /usr/sbin/update-initramfs /usr/share/debconf/frontend; do
    rm -f "${'$'}engine"
    echo '#!/bin/sh' > "${'$'}engine"
    echo 'exit 0' >> "${'$'}engine"
    chmod 777 "${'$'}engine"
done

# 3. Purge existing post-install files causing dependency log jams
echo "Neutralizing hardware package maintainer scripts..."
rm -f /var/lib/dpkg/info/systemd*.postinst
rm -f /var/lib/dpkg/info/udev*.postinst
rm -f /var/lib/dpkg/info/initramfs-tools*.postinst
rm -f /var/lib/dpkg/info/libpam*.postinst
rm -f /var/lib/dpkg/info/sudo*.postinst
rm -f /var/lib/dpkg/info/dbus*.postinst
rm -f /var/lib/dpkg/info/cron*.postinst
rm -f /var/lib/dpkg/info/dhcpcd*.postinst
rm -f /var/lib/dpkg/info/openssh*.postinst

# Force dpkg to mark the previously failing packages as configured
export DEBIAN_FRONTEND=noninteractive
export DEBCONF_NONINTERACTIVE_SEEN=true
dpkg --configure -a --force-all

# 4. Set clean software repository targets
echo "deb http://kali.download/kali kali-rolling main non-free contrib non-free-firmware" > /etc/apt/sources.list
rm -rf /etc/apt/sources.list.d/
mkdir -p /etc/apt/sources.list.d/

apt-get update -y
apt-get install -y --no-install-recommends gnupg curl || true
curl -fsSL --connect-timeout 30 https://archive.kali.org/archive-key.asc | gpg --yes --dearmor -o /etc/apt/trusted.gpg.d/kali-archive-keyring.gpg || true

# Force dependency fixes clear before pulling down target desktop environments
apt-get install -f -y --allow-downgrades
echo "Kali base environment aligned."
            """.trimIndent() + "\n"
        } else ""

        val libc6Setup = if (cleanDistro.contains("kali")) {
            """
                
                
                dpkg --configure -a --force-all
                apt-get install -f -y --allow-downgrades
                echo "Kali libc6 fixed."
            """.trimIndent() + "\n"
        } else ""

        // Audio dependencies
        val isModernDE = envName == "GNOME" || envName == "KDE Plasma"
        val audioDeps = if (isModernDE) {
            when {
                cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") || cleanDistro.contains("kali") ->
                    "pipewire pipewire-pulse wireplumber pavucontrol"
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") ->
                    "pipewire-pulse wireplumber pavucontrol"
                cleanDistro.contains("fedora") || cleanDistro.contains("almalinux") ->
                    "pipewire-pulseaudio wireplumber pavucontrol"
                cleanDistro.contains("alpine") ->
                    "pipewire pipewire-pulse wireplumber pavucontrol"
                cleanDistro.contains("void") ->
                    "pipewire wireplumber pavucontrol"
                cleanDistro.contains("opensuse") ->
                    "pipewire-pulseaudio wireplumber pavucontrol"
                else -> "pipewire pipewire-pulse pavucontrol"
            }
        } else {
            "pulseaudio pavucontrol"
        }

        // Desktop environment packages
        val desktopPackages = when (envName) {
            "XFCE Desktop" -> when {
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "xfce4 xfce4-goodies"
                cleanDistro.contains("alpine") -> "xfce4*"
                else -> "xfce4*"
            }
            "LXQt Desktop" -> when {
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "lxqt* lxqt-themes featherpad"
                cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") -> "lxqt* openbox"
                cleanDistro.contains("fedora") -> "@lxqt-desktop-environment qt5-qtbase-gui qt6-qtbase-gui"
                else -> "lxqt*"
            }
            "KDE Plasma" -> when {
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "plasma-meta dolphin konsole plasma-x11-session kwin-x11"
                cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") -> "kde-plasma-desktop"
                cleanDistro.contains("fedora") -> "@kde-desktop"
                else -> "plasma-desktop"
            }
            "GNOME" -> when {
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "gnome* gnome-tweaks"
                cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") -> "gnome-core"
                cleanDistro.contains("fedora") -> "@gnome-desktop"
                else -> "gnome*"
            }
            "MATE" -> when {
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "mate mate-extra"
                else -> "mate-desktop-environment"
            }
            "Cinnamon" -> when {
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "cinnamon* nemo"
                else -> "cinnamon-desktop-environment"
            }
            else -> ""
        }

        // KDE kwin PRoot downgrade fix for Arch
        val kdeArchProotFix = if (envName == "KDE Plasma" && (cleanDistro.contains("arch") || cleanDistro.contains("manjaro"))) {
            """
                echo "Downgrading kwin to fix PRoot Wayland crashing bug..."
                curl -L -o ~/kwin-6.6.5-4-aarch64.pkg.tar.xz https://github.com/xodiosx/XoDos-Ark/releases/download/4.18.1-1fix/kwin-6.6.5-4-aarch64.pkg.tar.xz
                pacman -U --noconfirm ~/kwin-6.6.5-4-aarch64.pkg.tar.xz
                balooctl6 status
                balooctl6 purge
                balooctl6 suspend
                balooctl6 disable
                echo "kwin successfully downgraded."
            """.trimIndent() + "\n"
        } else ""

        // Build script body
        var scriptBody = gpgSetup +
                "$managerCmd $desktopPackages $baseDeps $audioDeps\n" +
                kdeArchProotFix +
                "export PULSE_SERVER=127.0.0.1\n" +
                libc6Setup +
                "echo 'Installation completed!'\n"

        // gnome fix for Debian‑based
        if (envName == "GNOME" &&
            (cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") ||
             cleanDistro.contains("kali") || cleanDistro.contains("trisquel"))) {
            scriptBody += """
                # Apply GNOME
               rm -f /usr/share/dbus-1/system-services/org.freedesktop.login1.service
                echo 'fixed try using those commands to start desktop'
                rm -f /usr/share/dbus-1/system-services/org.freedesktop.login1.service

killall -9 gnome-session-binary dbus-daemon dbus-launch metacity gnome-panel 2>/dev/null
rm -f /run/dbus/pid && mkdir -p /run/dbus

export XDG_CURRENT_DESKTOP=GNOME
export DESKTOP_SESSION=gnome
export XDG_SESSION_DESKTOP=gnome


            """.trimIndent() + "\n"
        }
        if (envName == "LXQt Desktop" &&
            (cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") ||
             cleanDistro.contains("kali") || cleanDistro.contains("trisquel"))) {
            scriptBody += """
echo "Applying PRoot optimizations..."
            for file in /etc/xdg/autostart/*.desktop; do
                if [ -f "${'$'}file" ] && grep -qi "polkit" "${"$"}{file}"; then
                    rm -f "${'$'}file"
                fi
            done

            """.trimIndent() + "\n"
        }

        // Always wrap in a heredoc
        return "sh <<'EOF'\n${scriptBody}\nEOF"
    }
}