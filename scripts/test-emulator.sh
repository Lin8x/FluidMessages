#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
avd="${FLUID_AVD:-FluidMessages_API_35}"
port="${FLUID_EMULATOR_PORT:-5556}"
serial="emulator-$port"
apk=""
open_only=false
expect_onboarding=false
booted=false
installed=false
emulator_args=(-no-snapshot -partition-size 4096)
boot_timeout="${FLUID_BOOT_TIMEOUT:-240}"

usage() {
    cat <<'HELP'
Usage: ./scripts/test-emulator.sh [--open] [--headless] [--software] [--expect-onboarding] [--apk PATH]

Builds the debug APK, boots a dedicated API 35 emulator, installs the app,
and checks the setup guide, appearance editor, and practice messaging. The emulator stays running after the test.
Run as your regular user. Set ANDROID_HOME if your SDK is outside ~/Android/Sdk.
Use --open to launch the app without running UI automation.
Use --apk to test an already-built APK.
Use --expect-onboarding to assert the first-run screen on a fresh AVD.
If the installed app uses a different signing key, retry on a separate test AVD.
HELP
}
fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }

while (( $# )); do
    case "$1" in
        --open) open_only=true ;;
        --expect-onboarding) expect_onboarding=true ;;
        --headless) emulator_args+=(-no-window -no-audio -gpu swiftshader_indirect) ;;
        --software) emulator_args+=(-accel off) ;;
        --apk) shift; (( $# )) || fail "--apk needs a file"; apk="$(realpath "$1")" ;;
        --help|-h) usage; exit 0 ;;
        *) usage >&2; fail "Unknown argument $1" ;;
    esac
    shift
done

(( EUID != 0 )) || fail "Run ./scripts/test-emulator.sh without sudo. The SDK and virtual devices belong to your regular user."

# Local SDK locations are optional and kept out of source control.
if [[ -f "$root/.env.emulator.local" ]]; then
    source "$root/.env.emulator.local"
fi
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"

case "$(uname -m)" in
    x86_64|amd64) abi=x86_64 ;;
    aarch64|arm64) abi=arm64-v8a ;;
    *) fail "Unsupported emulator host architecture" ;;
esac

export ANDROID_HOME="$sdk"
image="system-images;android-35;default;$abi"
emulator="$sdk/emulator/emulator"
adb="$sdk/platform-tools/adb"
tools="$sdk/cmdline-tools/latest/bin"

packages=()
[[ -x "$emulator" ]] || packages+=(emulator)
[[ -x "$adb" ]] || packages+=(platform-tools)
[[ -f "$sdk/system-images/android-35/default/$abi/system.img" ]] || packages+=("$image")
if [[ -z "$apk" ]]; then
    [[ -f "$sdk/platforms/android-35/android.jar" ]] || packages+=("platforms;android-35")
    [[ -x "$sdk/build-tools/35.0.0/aapt2" ]] || packages+=("build-tools;35.0.0")
fi
if (( ${#packages[@]} )); then
    [[ -x "$tools/sdkmanager" ]] || fail "Install Android SDK Command-line Tools (latest) under $sdk, or set ANDROID_HOME."
    "$tools/sdkmanager" --sdk_root="$sdk" "${packages[@]}" ||
        fail "SDK package installation failed. Run sdkmanager --licenses and retry."
fi
[[ -x "$emulator" && -x "$adb" && -f "$sdk/system-images/android-35/default/$abi/system.img" ]] ||
    fail "SDK packages are missing. Run sdkmanager --licenses and retry."

if [[ -z "$apk" ]]; then
    cd "$root"
    if [[ -x ./gradlew ]]; then
        ./gradlew assembleDebug
    elif command -v gradle >/dev/null; then
        gradle assembleDebug
    else
        fail "Install Gradle 8.11.1 and JDK 17, or pass --apk /path/to/a/current/debug.apk."
    fi
    apk="$root/app/build/outputs/apk/debug/app-debug.apk"
fi
[[ -f "$apk" ]] || fail "APK not found at $apk"
command -v python3 >/dev/null || fail "Python 3 is required for UI assertions."

"$adb" start-server >/dev/null
pid=""
log=""
report_emulator_failure() {
    local status=$?
    if ((status)); then
        if "$booted"; then
            if "$installed"; then
                printf 'The emulator is still running. Retry with --open to inspect the app manually.\n' >&2
            else
                printf 'The emulator %s is still running with its app data intact.\n' "$avd" >&2
            fi
        elif [[ -n "$pid" ]]; then
            printf 'Emulator log: %s\n' "$log" >&2
            tail -25 "$log" >&2 || true
            kill "$pid" 2>/dev/null || true
        fi
    fi
}
trap report_emulator_failure EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

running_avd() {
    "$adb" -s "$1" emu avd name | tr -d '\r' | sed -n '1p'
}

boot_emulator() {
    local running deadline
    serial="emulator-$port"
    device=("$adb" -s "$serial")
    booted=false
    pid=""
    if "$adb" devices | awk -v serial="$serial" '$1 == serial { found=1 } END { exit !found }'; then
        running="$(running_avd "$serial")"
        [[ "$running" == "$avd" ]] || fail "$serial is running $running. Set FLUID_EMULATOR_PORT to an unused even port."
    else
        if ! "$emulator" -list-avds | grep -Fxq "$avd"; then
            [[ -x "$tools/avdmanager" ]] || fail "Install Android SDK Command-line Tools to create $avd."
            "$tools/avdmanager" create avd --name "$avd" --package "$image" --device pixel_7 <<< no
        fi
        log="$(mktemp "${TMPDIR:-/tmp}/fluidmessages-emulator.XXXXXX")"
        nohup "$emulator" -avd "$avd" -port "$port" "${emulator_args[@]}" >"$log" 2>&1 </dev/null &
        pid=$!
    fi

    deadline=$((SECONDS + boot_timeout))
    until [[ "$("${device[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" == 1 ]]; do
        [[ -z "$pid" ]] || kill -0 "$pid" 2>/dev/null || fail "Emulator exited before booting."
        (( SECONDS < deadline )) || fail "Emulator did not finish booting within $boot_timeout seconds."
        sleep 2
    done
    booted=true

    "${device[@]}" shell input keyevent KEYCODE_WAKEUP
    "${device[@]}" shell wm dismiss-keyguard
}

recovery_port() {
    local candidate
    while read -r candidate; do
        if [[ "$(running_avd "$candidate" 2>/dev/null)" == "$avd" ]]; then
            printf '%s\n' "${candidate#emulator-}"
            return
        fi
    done < <("$adb" devices | awk '$1 ~ /^emulator-[0-9]+$/ { print $1 }')

    python3 - <<'PY'
import socket
import sys

for port in range(5554, 5683, 2):
    try:
        with socket.socket() as console, socket.socket() as adb:
            console.bind(("127.0.0.1", port))
            adb.bind(("127.0.0.1", port + 1))
    except OSError:
        continue
    print(port)
    sys.exit(0)
sys.exit(1)
PY
}

boot_emulator
if ! install_output="$("${device[@]}" install -r "$apk" 2>&1)"; then
    if [[ "$install_output" != *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* ]]; then
        printf '%s\n' "$install_output" >&2
        fail "Android rejected the APK. FluidMessages was not updated."
    fi
    old_avd="$avd"
    avd="${avd}_local"
    port="$(recovery_port)" || fail "No free emulator port is available for $avd."
    printf 'Signing key differs on %s. Keeping its data and testing this APK on %s.\n' "$old_avd" "$avd"
    boot_emulator
    if ! install_output="$("${device[@]}" install -r "$apk" 2>&1)"; then
        printf '%s\n' "$install_output" >&2
        fail "Could not install on $avd. If its signing key changed too, set FLUID_AVD to a new test AVD name and FLUID_EMULATOR_PORT to a free even port."
    fi
fi
installed=true
printf '%s\n' "$install_output"
printf 'Installed APK: %s\n' "$apk"
activity="org.fluidmessages/.MainActivity"
"${device[@]}" shell am force-stop org.fluidmessages
launch="$("${device[@]}" shell am start -W -n "$activity")"
printf '%s\n' "$launch"
[[ "$launch" == *"Status: ok"* ]] || fail "Activity launch failed."
if "$open_only"; then
    printf 'FluidMessages is open on %s.
' "$serial"
    exit 0
fi

ui() {
    "${device[@]}" shell rm -f /sdcard/fluid-test-ui.xml
    "${device[@]}" shell uiautomator dump /sdcard/fluid-test-ui.xml >/dev/null || return 1
    "${device[@]}" exec-out cat /sdcard/fluid-test-ui.xml
}

find_label() {
    python3 -c '
import re, sys, xml.etree.ElementTree as ET
wanted = sys.argv[1:]
tree = ET.parse(sys.stdin)
parents = {child: parent for parent in tree.iter("node") for child in parent}
candidates = []
for node in tree.iter("node"):
    labels = [node.get("text", "").strip(), node.get("content-desc", "").strip()]
    if not any(label in wanted for label in labels):
        continue
    target = node
    editable = node.get("class") == "android.widget.EditText"
    if not editable:
        while target.get("clickable") != "true" and target in parents:
            target = parents[target]
        if target.get("clickable") != "true":
            target = node
    bounds = re.findall(r"\d+", target.get("bounds", ""))
    if len(bounds) != 4 or target.get("enabled") == "false":
        continue
    left, top, right, bottom = map(int, bounds)
    if right > left and bottom > top:
        priority = 2 if editable else int(target.get("clickable") == "true")
        candidates.append((priority, (left + right) // 2, (top + bottom) // 2))
if candidates:
    _, x, y = max(candidates, key=lambda match: match[0])
    print(x, y)
else:
    sys.exit(1)
' "$@"
}

tap_label() {
    local coordinates x y
    for _ in 1 2 3; do
        if coordinates="$(ui | find_label "$@" 2>/dev/null)"; then
            read -r x y <<< "$coordinates"
            "${device[@]}" shell input tap "$x" "$y"
            return
        fi
        sleep 0.4
    done
    return 1
}

wait_visible() {
    local xml
    for _ in 1 2 3; do
        if xml="$(ui)" && [[ "$xml" == *"$1"* ]]; then
            return 0
        fi
        sleep 0.4
    done
    return 1
}

advance_setup() {
    local current="$1" next="$2"
    for _ in 1 2; do
        if wait_visible "$next"; then return 0; fi
        wait_visible "$current" || return 1
        tap_label "Continue" || return 1
    done
    wait_visible "$next"
}

if "$expect_onboarding"; then
    wait_visible "Make it yours" && wait_visible "Step 1 of 6" ||
        fail "A fresh install did not start on the first onboarding page."
fi
first_ui="$(ui)"
if [[ "$first_ui" == *"Skip setup"* ]]; then
    tap_label "Skip setup" || fail "Could not finish first-run setup."
fi
tap_label "Settings" || fail "Could not open Settings."
tap_label "Themes and appearance" || fail "Could not open the appearance editor."
xml="$(ui)"
[[ "$xml" == *"Sample conversation"* && "$xml" == *"Coffee at ten?"* && "$xml" == *"Automated preview"* ]] ||
    fail "The sample conversation did not render."
tap_label "Color theme" || fail "Could not open the primary theme dropdown."
theme_xml="$(ui)"
for theme in Fluid Midnight Lavender Evergreen Tideline Ember Love Rosewater; do
    [[ "$theme_xml" == *"text=\"$theme\""* ]] || fail "Theme missing from dropdown: $theme"
done
"${device[@]}" shell input keyevent KEYCODE_BACK
tap_label "Appearance mode" || fail "Could not open the appearance mode dropdown."
mode_xml="$(ui)"
[[ "$mode_xml" == *'text="Light"'* && "$mode_xml" == *'text="Dark"'* ]] ||
    fail "The appearance mode choices did not render."
"${device[@]}" shell input keyevent KEYCODE_BACK
appearance_xml="$(ui)"
[[ "$appearance_xml" == *"Customize colors"* && "$appearance_xml" != *"Buttons and unread indicators"* &&
   "$appearance_xml" != *"SMS/RCS color mapping"* ]] ||
    fail "Advanced color fields must start collapsed."
tap_label "Customize colors" || fail "Could not expand the custom color section."
wait_visible "SMS color" || fail "The expanded SMS color control did not render."
if ! wait_visible "Automated color"; then
    size="$("${device[@]}" shell wm size | tail -1 | tr -d '\r')"
    dimensions="${size##*: }"
    width="${dimensions%x*}"
    height="${dimensions#*x}"
    "${device[@]}" shell input swipe "$((width / 2))" "$((height * 4 / 5))" "$((width / 2))" "$((height * 3 / 5))" 440
fi
wait_visible "RCS color (preview)" && wait_visible "Automated color" ||
    fail "The expanded RCS and automated color controls did not render."
tap_label "RCS color (preview)" || fail "Could not open the RCS color choices."
rcs_colors="$(ui)"
for color in Indigo Pink Cyan Mint; do
    [[ "$rcs_colors" == *"Apple $color"* ]] || fail "Missing a featured RCS color."
done
"${device[@]}" shell input keyevent KEYCODE_BACK
tap_label "Customize colors" || fail "Could not collapse the custom color section."
if ! tap_label "Close appearance editor" "Done"; then
    "${device[@]}" shell input keyevent KEYCODE_BACK
fi
tap_label "Messaging" || fail "Could not open Messaging settings."
wait_visible "Show exact message times" || fail "The Messaging switch did not render."
tap_label "Show exact message times" || fail "Could not change the message timestamp switch."
tap_label "Show exact message times" || fail "Could not restore the message timestamp switch."
"${device[@]}" shell input keyevent KEYCODE_BACK
tap_label "Backups" || fail "Could not open the separate Backups page."
tap_label "Export a file" || fail "Could not open the backup export form."
wait_visible "Choose save location" || fail "The backup export form did not render."
wait_visible "Wallpaper photos" && wait_visible "Per-chat themes" || fail "The backup categories did not render."
tap_label "Clear all" && wait_visible "Select all" || fail "Could not deselect the backup categories."
tap_label "Global theme and layout" || fail "Could not choose an individual backup category."
"${device[@]}" shell input keyevent KEYCODE_BACK
tap_label "Share to a storage app" || fail "Could not open the companion-free backup form."
wait_visible "Choose storage app" || fail "The backup share action did not render."
"${device[@]}" shell input keyevent KEYCODE_BACK
tap_label "Restore from a file" || fail "Could not open the backup restore form."
wait_visible "Choose backup file" || fail "The backup restore form did not render."
"${device[@]}" shell input keyevent KEYCODE_BACK
tap_label "Local folder schedule" || fail "Could not open the local schedule form."
wait_visible "Hours" && wait_visible "Minutes" || fail "The schedule interval fields did not render."
tap_label "30 min" && wait_visible 'text="30"' || fail "The interval preset did not update the minutes field."
"${device[@]}" shell input keyevent KEYCODE_BACK
tap_label "Google Drive" || fail "Could not open the Google Drive destination."
tap_label "Backup method" || fail "The provider has no backup method dropdown."
method_xml="$(ui)"
[[ "$method_xml" == *'text="Phone"'* && "$method_xml" == *'text="Companion"'* ]] ||
    fail "The provider is missing a backup method."
"${device[@]}" shell input keyevent KEYCODE_BACK
provider_xml="$(ui)"
if [[ "$provider_xml" == *"Phone backup"* ]]; then
    [[ "$provider_xml" == *"Back up now"* && "$provider_xml" == *"Backup schedule"* &&
        "$provider_xml" == *"Restore from a file"* ]] || fail "The provider is missing phone backup actions."
elif [[ "$provider_xml" == *"Not set up"* ]]; then
    [[ "$provider_xml" == *"Set up companion"* && "$provider_xml" != *"Restore newest backup"* ]] ||
        fail "An unpaired provider should only offer setup."
else
    [[ "$provider_xml" == *"Back up now"* && "$provider_xml" == *"Backup schedule"* &&
        "$provider_xml" == *"Restore newest backup"* ]] || fail "The provider is missing companion backup actions."
fi
"${device[@]}" shell input keyevent KEYCODE_BACK
"${device[@]}" shell input keyevent KEYCODE_BACK
tap_label "Replay setup guide" || fail "Could not open the setup guide."
setup_pages=("Make it yours" "Pick your colors" "Motion, sound and haptics" "Keep a backup" "Privacy and the web" "Ready to text")
wait_visible "${setup_pages[0]}" || fail "The first setup page did not render."
for (( index = 1; index < ${#setup_pages[@]}; index++ )); do
    before="${setup_pages[index - 1]}"
    after="${setup_pages[index]}"
    advance_setup "$before" "$after" || fail "Could not advance from $before to $after."
    if [[ "$after" == "Pick your colors" ]]; then
        if ! wait_visible "SMS/RCS color mapping"; then
            dimensions="$("${device[@]}" shell wm size | tail -1 | tr -d '\r')"
            dimensions="${dimensions##*: }"
            width="${dimensions%x*}"
            height="${dimensions#*x}"
            "${device[@]}" shell input swipe "$((width / 2))" "$((height * 4 / 5))" "$((width / 2))" "$((height / 2))" 440
        fi
        tap_label "SMS/RCS color mapping" || fail "Setup is missing the optional transport palette."
        wait_visible "Green SMS · Blue RCS" || fail "Setup is missing the green SMS and blue RCS choice."
        "${device[@]}" shell input keyevent KEYCODE_BACK
    fi
done
tap_label "Open sample inbox" || fail "Could not open the sample inbox."
inbox_xml="$(ui)"
read -r filter_x filter_y <<< "$(find_label "Filters" <<< "$inbox_xml")"
read -r settings_x settings_y <<< "$(find_label "Settings" <<< "$inbox_xml")"
read -r inbox_x inbox_y <<< "$(find_label "My inbox" <<< "$inbox_xml")"
[[ -n "$filter_x" && -n "$settings_x" && -n "$inbox_x" ]] ||
    fail "The sample inbox toolbar buttons did not render."
(( filter_x > inbox_x && inbox_x > settings_x && filter_y == settings_y )) ||
    fail "Filters must be at the right end of the top toolbar."
tap_label "Filters" && tap_label "Archived" || fail "Could not open the sample archive."
wait_visible "Avery Brooks" || fail "The sample archive did not show its archived thread."
tap_label "Filters" && tap_label "Blocked" || fail "Could not open the sample blocked folder."
wait_visible "Sam Rivera" || fail "The sample blocked folder did not show its fixture thread."
tap_label "Filters" && tap_label "Inbox" || fail "Could not return to the sample inbox."
tap_label "Trail Alerts" || fail "Could not open the automated sender preview."
wait_visible "Reply STOP" && wait_visible 'text="STOP"' || fail "The sample opt-out pair did not render."
tap_label "Back to messages" || fail "Could not leave the automated sender preview."
tap_label "Jordan Ellis" || fail "Could not open the sample RCS thread."
wait_visible "Sample RCS" || fail "The RCS preview did not render."
tap_label "Jordan Ellis" || fail "Could not open conversation details."
tap_label "Theme and wallpaper" || fail "Could not open the per-chat appearance page."
wait_visible "Conversation wallpaper" || fail "Conversation wallpaper controls did not render."
tap_label "Back" || fail "Could not return to conversation details."
tap_label "Done" || fail "Could not go back to the conversation."
incoming="I sent the route for Saturday."
read -r bubble_x bubble_y <<< "$(ui | find_label "$incoming")"
[[ -n "$bubble_x" && -n "$bubble_y" ]] || fail "Could not find the message to quote."
"${device[@]}" shell input swipe "$bubble_x" "$bubble_y" "$((bubble_x + 210))" "$bubble_y" 320
wait_visible "Reply to" || fail "Swiping right did not start a reply."
tap_label "Practice message" || fail "Could not focus the practice composer."
"${device[@]}" shell input text 'Emulator%stest'
tap_label "Send message" || fail "Could not send a practice message."
wait_visible "Emulator test" || fail "The practice message did not appear."
wait_visible "Replying to" || fail "The reply did not render as a quote card."
if [[ "$("${device[@]}" shell dumpsys input_method)" == *"mInputShown=true"* ]]; then
    "${device[@]}" shell input keyevent KEYCODE_BACK
fi
read -r bubble_x bubble_y <<< "$(ui | find_label "$incoming")"
[[ -n "$bubble_x" && -n "$bubble_y" ]] || fail "Could not find the message to react to."
"${device[@]}" shell input swipe "$bubble_x" "$bubble_y" "$bubble_x" "$bubble_y" 750
tap_label "Thumbs up" || fail "Could not send a thumbs-up in the sample thread."
reaction_seen=false
for _ in 1 2 3; do
    if ui | find_label "👍" >/dev/null; then reaction_seen=true; break; fi
done
"$reaction_seen" || fail "The SMS reaction did not fold onto the matching bubble."
read -r bubble_x bubble_y <<< "$(ui | find_label "$incoming")"
"${device[@]}" shell input swipe "$bubble_x" "$bubble_y" "$bubble_x" "$bubble_y" 750
tap_label "Forward" || fail "Forward is missing from the message actions."
tap_label "Maya Chen" || fail "Could not choose a fictional recipient."
wait_visible "Maya Chen" || fail "The forwarded draft did not open its target conversation."
wait_visible "Forwarded" || fail "The forwarding attribution was missing from the draft."
tap_label "Send message" || fail "Could not send the practice forward."
wait_visible "Forwarded from Jordan Ellis" || fail "The forwarded text did not render as a card."
if [[ "$("${device[@]}" shell dumpsys input_method)" == *"mInputShown=true"* ]]; then
    "${device[@]}" shell input keyevent KEYCODE_BACK
fi
tap_label "Send as Maya Chen" || fail "Could not select the sample contact as sender."
tap_label "Message from Maya Chen" || fail "Could not switch the sample composer to the contact."
"${device[@]}" shell input text 'Incoming%sfixture'
tap_label "Send message" || fail "Could not add a sample incoming message."
wait_visible "Incoming fixture" || fail "The sample contact message did not appear."
tap_label "Back to messages" || fail "Could not return from the sample chat."
tap_label "Weekend Plans" || fail "Could not open the sample group."
wait_visible "Sample group · 4 members" || fail "The sample group did not show its participants."
own='I’ll bring dessert.'
read -r bubble_x bubble_y <<< "$(ui | find_label "$own")"
[[ -n "$bubble_x" && -n "$bubble_y" ]] || fail "Could not find the outgoing group message."
"${device[@]}" shell input swipe "$bubble_x" "$bubble_y" "$bubble_x" "$bubble_y" 750
tap_label "Heart" || fail "Could not react to your own sample message."
wait_visible "❤️" || fail "The reaction did not appear on your own message."
read -r bubble_x bubble_y <<< "$(ui | find_label "$own")"
"${device[@]}" shell input swipe "$bubble_x" "$bubble_y" "$bubble_x" "$bubble_y" 750
tap_label "More emoji" || fail "The reaction strip has no custom emoji action."
tap_label "Use 🎉" && tap_label "React" || fail "Could not pick and apply a custom emoji."
custom_seen=false
for _ in 1 2 3; do
    if ui | find_label "🎉" >/dev/null; then custom_seen=true; break; fi
done
"$custom_seen" || fail "The custom reaction did not appear on your own message."
tap_label "Send as Jordan Ellis" || fail "Could not choose a group member as sender."
tap_label "Message from Jordan Ellis" || fail "The group composer did not switch senders."
"${device[@]}" shell input text 'Group%sfixture'
tap_label "Send message" || fail "Could not send a practice group message."
wait_visible "Group fixture" || fail "The group message did not appear."
"${device[@]}" shell rm -f /sdcard/fluid-test-ui.xml
printf 'PASS  Installed FluidMessages and verified setup, appearance, and practice messaging on %s.\n' "$serial"
