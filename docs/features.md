# FluidMessages features

Everything the Android app does, in detail. For installing it, see the [README](../README.md).

## Getting started

Target device: Pixel 7 with GrapheneOS and dual SIM. The app targets Android 15 and requires Android 13 or newer. It uses the Android SMS role and does not require Google Play Services.

Open **Settings → About and updates** for the installed version, manual checks, and automatic release checks. Direct installs check GitHub about once a day while the app is open. A newer stable release can be downloaded after confirmation. The app verifies the APK’s package, signing certificate, and version code before opening Android’s installer. App store installs use their store for updates. Publish a signed APK as a GitHub Release asset named `FluidMessages-<version>.apk` on the matching `v<version>` tag, using the same signing key and an increasing version code.

After installation, choose **Set as default SMS app** to load the system inbox and send or receive SMS. **SIM** in the composer selects a subscription. Long-press a conversation for pinning, archiving, or hiding it from the regular inbox. Hiding changes the local inbox and does not block carrier delivery. Notification previews omit message text. Open **New message** to find contacts by name, pick a recent number, or enter a new number. Select several people to use a single SMS composer. Each person receives their own text and replies in their individual chat. Android’s `smsto:` links also accept comma-separated numbers.

Open the appearance editor from **Settings → Themes and appearance**, including before SMS setup. The Fluid palette uses blue for outgoing SMS and green for the automated outgoing color. Fluid and Midnight blend Apple Indigo and Purple in equal parts for the RCS preview. Incoming bubbles use system gray in light and dark mode. You can override the incoming color separately. **Color theme** lists the built-in themes and saved palettes. Individual message colors are in **Customize colors**, with Apple Indigo, Pink, Cyan and Mint featured in the RCS picker. Colored message bubbles use white text. Bright chromatic fills are shaded for contrast. Neutral bubbles choose black or white based on brightness. The swatches use the light, dark, and increased-contrast RGB values from the [Apple Human Interface Guidelines color table](https://developer.apple.com/design/human-interface-guidelines/color#Specifications). Choose a coordinated theme such as Midnight, Lavender, Evergreen, Tideline, Ember, Love, or Rosewater. Each theme has a light and dark palette. Visual color controls, custom hex, and named swatches are available. Switching themes applies the selected palette, wallpaper and default layout. **Reset to theme** reapplies the current one. Mode, sound, haptic and motion preferences remain separate. Selecting Love applies its palette and matching wallpaper. The appearance page keeps presets and the live preview at the top. Expand **Customize colors**, **Wallpaper**, **Inbox and toolbar**, **Message bubbles**, or **Motion and sound** as needed. Use **Save custom palette** to keep a named version of a preset and your color overrides. Search can sit at the top or in a bottom dock. Toolbar finish and opacity, inbox density, and bubble tails are also adjustable. Each theme has an automated outgoing color. The optional green SMS and blue RCS setup preset uses Apple system Green for SMS and Apple Purple for automated messages. In every theme, the automated color is a separate hue from that theme's SMS and RCS colors. First launch opens setup for appearance, motion, chimes, backups, privacy, and the LAN companion. Replay it or open the permission-free sample inbox from Settings. Under **Motion and sound**, set the outgoing sound and receive chime to Silent, a Fluid chime, the Android default, or a tone from the device picker. Fluid is the default: a soft swish when you send and a short three-note chime when a message arrives. Choose a haptic pattern and preview the feedback. Device tones may need reselection after restoring on another phone. The animation setting controls the sample preview, conversation entrance, and new outgoing bubble animation. App lock uses your device credential or strong biometric and hides the app from screenshots while enabled.

## Replies, reactions, and conversation appearance

Enable **Settings → Messaging → Show exact message times** for inbox date-and-time labels and a time under each bubble. Received SMS use the sender timestamp when Android supplies it. Other messages use the stored timestamp. You can always tap an individual bubble to reveal its time.

Swipe a bubble right to quote it. Long-press for a reaction strip, Reply, Forward, Copy, Select text, Share, Message details, and Delete for me. Forward opens an editable draft for the recipient you choose. It sends readable SMS such as `Forwarded “Coffee at ten?” by “Maya Chen”`; FluidMessages renders that text as a forwarded card. Replies send the quote and reply body as readable SMS. FluidMessages shows compatible reply text as a quote card, and tapping a matched quote jumps to the original message. A thumbs-up sends text such as `Liked “Coffee at ten?”`. The **+** in the reaction strip accepts an emoji from your keyboard or clipboard. FluidMessages folds compatible standard and custom emoji reactions onto the matching earlier message. Unmatched reactions remain visible as text. React to incoming messages or your own outgoing messages. Each person keeps one reaction per message: choosing your current reaction again removes it and sends `Removed a like from “Coffee at ten?”`, the wording iPhones use, and choosing a different one sends `Changed reaction from 👍 to ❤️ on “Coffee at ten?”`, so people on other apps know what changed. FluidMessages folds both, in one-to-one and group chats, on the phone and in the browser companion. Matrix and XMPP remove and change reactions natively: Matrix redacts your earlier reaction event, and XMPP sends your updated reaction set (XEP-0444). When someone else takes back a Matrix or XMPP reaction, it disappears from the bubble too. SMS, MMS, IRC, and email send the readable text, since IRC has no way to take a reaction back and RFC 9078 doesn't define one for email. Update the companion as well, so it sends removals natively instead of treating them as new reactions. A reaction to your own message adds `(my message)` to the SMS so FluidMessages can distinguish the two cases. Matching uses the quote and sender, so repeated identical texts may match the latest one. Every protocol sends through the same message layer, so reply, react, and forward work the same way in each chat. Matrix replies and reactions use native `m.in_reply_to` and `m.annotation` events, and Matrix reactions from other clients fold onto the matching message. Incoming email replies drop the quoted earlier conversation (the “On … wrote:” line, Outlook’s header block, and trailing `>` lines), since the bubble already shows when it was sent. When someone answers an older email than the latest one, a reply card shows which. Emails received before this are cleaned once on the phone and the companion. Email replies thread onto the exact message with `In-Reply-To`, and email reactions follow [RFC 9078](https://www.rfc-editor.org/rfc/rfc9078), which leaves the reaction email without the FluidMessages footer. SMS, MMS, and IRC use the readable text form above. Forwarding sends the readable forwarded text on every protocol. Use **Send as** in a sample chat to choose yourself or a fictional group member. Try the mixed **Weekend Plans**, all-RCS **Photo Walk**, and automated **Trail Crew** samples. The outgoing preview uses Automated first, then SMS, and RCS when all participants support it. Attach a photo in any sample composer. Sample photos stay local.

Tap an inbox avatar or a conversation’s name to open contact details. The inbox refreshes saved contact names and photos after edits. Search accepts partial words, accent-insensitive spelling, one-letter typos, and formatted numbers. Short codes and alphanumeric sender IDs use the automated outgoing color and conversation label. The classification follows the sender address. Incoming messages use the separate incoming color. **Edit contact** opens the Android Contacts editor. Calling, muting, pinning, and archiving are available for real SMS chats. **Theme and wallpaper** opens a live preview with built-in wallpapers, photo selection, and visual color controls. Selections save immediately for the conversation. Global wallpapers and visual colors are available in **Themes and appearance**. Selecting Love applies its palette and wallpaper together.

## Message protocols

**Settings → Message protocols** groups text messages (RCS preview, SMS, and **Allow MMS**) and internet protocols (Matrix, XMPP, Email, IRC). New messages use the first available protocol in this order: RCS, SMS or MMS, Matrix, XMPP, Email, IRC. RCS, SMS, and MMS share one conversation per person. SMS switches to MMS for photos and group chats. When RCS is offered, **via RCS ▾** above the composer, or touching and holding Send, sends a message as SMS instead. A contact with several protocols shows **via Matrix ▾** to pick how to send, and tapping the protocol under the conversation name switches which history you see. Choose **Always use … for** a contact to save a default. **Inbox protocol tags** and **Encryption status** default to **Automatic**. Tags appear once your inbox holds more than one kind of conversation, and a 🔒 line appears under the name only for end-to-end encrypted chats. Every chat's details list how it's protected. Choose Always or Off in Message protocols. **New message** accepts phone numbers and, for protocols that are on and have an account, a Matrix ID, an email or XMPP address (you choose which), an IRC #channel, or `irc:nick`. A protocol that's on but has no account offers to add one, and turned-off protocols aren't offered. An account connected from this phone pauses on the companion, so each message arrives once. With **Combine linked conversations** on, every protocol for a contact appears in one chat, and messages sent another way are labeled. Internet protocols connect automatically once the companion is paired and has accounts, and it checks every 15 minutes when it has none. A turned-off protocol stays readable, and the composer explains why it can't send. **Settings → Messaging → Privacy** removes metadata from files you send on every protocol: EXIF, XMP, and GPS from photos (by re-encoding them), container tags and location from MP4, 3GP, and WebM video and audio (by remuxing), ID3 tags from MP3, document information and XMP from PDFs ([PDFBox-Android](https://github.com/TomRoush/PdfBox-Android)), and document properties from Office and OpenDocument files. Other formats are sent unchanged.

**Settings → Battery and sync** sets how often FluidMessages checks the companion while open (10 seconds to every minute, or only when you refresh or send) and in the background (15 minutes to 6 hours, or off), whether automatic checks wait for Wi-Fi, whether they pause during Battery Saver, and when received attachments download. Carrier SMS and MMS arrive by push and are unaffected.

### Accounts and linked addresses

Open **Settings → Accounts** to add Matrix, XMPP, Email, or IRC accounts to your paired companion, or turn protocols on in setup and choose **Set up accounts**. Matrix asks for your password once to sign in, and the companion keeps only the session token. Passwords are stored in a private file (mode 600) in the companion's data volume and are never shown again or included in backups. Leave a password blank when editing to keep the saved one. You can also set accounts in Docker Compose with `FLUID_EMAIL_*`, `FLUID_MATRIX_*`, `FLUID_XMPP_*`, and `FLUID_IRC_*` variables (see `compose.yaml`). Those appear as **Set in Docker Compose** and are read-only on the phone. An incomplete Compose account is reported instead of stopping the companion.

Open a contact's details and choose **Link an address** to reach them by Matrix ID, XMPP address or group, email address, or IRC nick or #channel. A Matrix ID opens your existing direct room with that person, or creates one. Only linked conversations appear in FluidMessages; other email, rooms, and direct messages stay out. Channels listed on your IRC account appear in the inbox automatically.

The companion keeps one connection per account open, so your phone doesn't. **Matrix encryption:** with `matrix-nio[e2e]` (included in the Docker image), the companion becomes one of your Matrix devices. It decrypts and encrypts messages and files in encrypted rooms, and keeps its keys in the data volume, protected by a random pickle key. The phone shows those rooms as *End-to-end encrypted · decrypted on your companion*. Other people's new devices are trusted on first use, the way bots and bridges behave. To clear "unverified device" warnings for others, verify the companion's device from another Matrix client. Messages whose keys haven't arrived yet show a placeholder, and the companion requests the keys. **Connect from this phone:** in an account, choose **Connect through → This phone** instead of the companion. First accept the battery notice, which keeps **Continue** off until you turn on *I understand this uses more battery*. The phone then connects to that protocol itself: JavaMail for email, Smack for XMPP, the client-server API for Matrix, and a built-in TLS IRC client. Account details are encrypted with an Android Keystore key, stay on the phone, and are never backed up, and links you add are stored on the phone. Email and Matrix check on your Battery and sync schedule, plus a long poll for Matrix while the app is open. IRC and XMPP connect while FluidMessages is on screen. **Stay connected** keeps them connected in a foreground service with an ongoing notification, which uses the most battery. Encrypted Matrix rooms need the companion, so from the phone they show a placeholder and sending to them is declined with that reason. Without a companion, phone connections are the only option. XMPP is native (slixmpp) with stream management, message carbons, delivery receipts, group chats, replies (XEP-0461), reactions (XEP-0444), and files through HTTP File Upload. IRC is native, TLS only, with SASL sign-in and IRCv3 replies and reactions when the server supports message tags. Unsupported features fall back to readable text. A Matrix room ID for an XMPP or IRC link still routes through a Matrix bridge. IRC conversations keep the normal bubbles, with each speaker's nickname in its own color and the time beside it. `/me` actions appear in italics, and joins, leaves, and topic changes are small outlined notes in the IRC color. The channel's details show its topic and current nicknames.

If a message can't be sent, a red **Not delivered** notice appears under it, with the reason when one is known. Tap it to try again, edit and resend, or delete.

### Attachments

Tap **+** in the composer for Photos and videos, Camera, Document, Audio, Location, or Contact. With nothing typed, the microphone records a voice message (AAC, up to 3 minutes). A location is sent as an OpenStreetMap link and appears as a location card that opens your map app. A contact is sent as a vCard. Each protocol carries files in its standard way:

- **SMS** becomes **MMS** for a file, within your carrier’s MMS size limit (often 300 KB–1 MB). Photos are resized to fit, and other files that are too large are refused with the limit shown. Locations stay SMS.
- **Matrix** uploads to your homeserver’s media repository and sends `m.image`, `m.video`, `m.audio`, or `m.file`. Voice messages are marked as voice notes (MSC3245). Received media downloads through authenticated media.
- **Email** sends a normal MIME attachment, up to 25 MB, and receives the first attachment of messages from linked correspondents.
- **XMPP** and **IRC** currently go through Matrix bridges, which deliver files the way each network allows. IRC receives them as links.

Files go to the companion first and are stored in its data volume. Received attachments download according to **Battery and sync**, or when you tap them.

New groups use one protocol that everyone shares. That is MMS for phone numbers. Matrix, XMPP, and IRC groups are set up on the companion for now. If MMS is off, the same text goes to each number separately.

### Money

**+ → Money** requests or sends money through standard payment links, so paying always happens in the other person's own app. It works in two steps: pick how (your usual method sits on top as one big button, then crypto, payment apps and banks, and your own), then fill in only what that method needs. **Monero (XMR)** is the default; the last method you use becomes the new default, and **Settings → Payments** changes it.

- **Crypto:** Monero, Bitcoin, Bitcoin over Lightning (Lightning address or LNURL), USDC and SOL on Solana, USDC and ETH on Ethereum, USDC on Base.
- **Payment apps and banks:** PayPal.me, Venmo, Cash App, Revolut, UPI, and bank transfers by IBAN using the open `payto:` standard. Pick the currency where the service supports several.
- **Your own:** **Add your own…** takes a name and a payment link with `{address}`, `{amount}`, and `{note}` in it, such as `dogecoin:{address}?amount={amount}`. Cards for custom methods show a clear warning, on both sides, because FluidMessages can't check where those links send money.

Google Pay and Apple Pay don't offer payment links that other apps can use, so they aren't listed. FluidMessages never holds money, keys, seed phrases, or card numbers and never signs a transaction. The message is readable text, so people without FluidMessages still see the amount and link. Crypto payments can't be reversed.

### GIFs, stickers, polls, events, games, and link previews

Every one of these is sent as readable text or an ordinary picture, so people on other apps get something that makes sense, and FluidMessages shows it as a card.

- **GIFs and stickers:** **+ → GIF** or **Sticker**, or insert them straight from your keyboard (Gboard and others). Browse Tenor, GIPHY, Imgur, or Reddit r/gifs right in the picker with no account: long-press a GIF, then **Send**. Turn each site off or add your own (with `{query}` where search words go) under **GIF sites to browse** in **Settings → Media, GIFs, and links**. For search results inside the picker, choose Tenor or GIPHY with your own free API key, and optionally your own compatible API address, in **Settings → Media, GIFs, and links**. Searched GIFs are sent as a link (light on data, and other apps preview it); GIFs from your phone are sent as a file where the service carries files. **Make a sticker** turns any photo into a sticker. Stickers show without a bubble. GIFs and stickers can each be turned off.
- **Polls:** **+ → Poll** with 2 to 10 options. Everyone in the chat taps to vote and sees live counts. People on other apps see a numbered list and can reply with a number, which is counted too.
- **Events:** **+ → Event** shares a title, time (or all day), place, and notes. Everyone can answer **Going**, **Maybe**, or **Can't go** on the card, and it shows the counts and who said what. People on other apps can just reply `yes`, `maybe`, or `no` to the newest invite before it happens. **Add to calendar** saves it straight into a calendar on your phone (Google, CalDAV/DAVx⁵, Exchange, or local; it asks for calendar access once), or opens another calendar app, Google Calendar on the web, or saves an `.ics` file (the open iCalendar standard). It never depends on one calendar app working. `.ics` invites that others send show as the same card.
- **Games:** **+ → Games** offers chess, tic-tac-toe, and four in a row. Pick one, then who goes first (white or black for chess). With FluidMessages, tap the board to play, then **Send**; resign and play again are on the board. Chess has the full rules: castling, en passant, promotion, check, checkmate, and stalemate.
  - **With people on other apps:** they get each move as text with the board drawn in it and a line saying how to answer. They just reply with their move: `e5`, `Nf6`, or `O-O` in chess, a square number in tic-tac-toe, or a column number in four in a row. Their reply counts as their move, and your next move carries it on.
  - **Fair play:** a game message that changes earlier moves, plays for the other side, or keeps going after the game ended is marked as skipped and changes nothing. A number sent after a newer poll counts as a vote, not a move.
  - **On a website instead:** after picking a game, choose where to play. **Lichess** (chess, free, no account) makes a real game link for you: your side opens in your browser and the other link is sent. **Chess.com** (needs free accounts), **Foony**, and **Papergames** (tic-tac-toe and four in a row) open the site; start a private game there and paste its invite link. The chat gets a card with **Join the game**, and other apps get the link.
- **Rock paper scissors:** **+ → Games → Rock paper scissors**. Your pick is sealed (a SHA-256 hash of the game, your pick, and a secret), so you can't change it after seeing theirs. They pick on the card, or reply `rock`, `paper`, or `scissors` from any app. Then tap **Reveal my pick**; FluidMessages checks the reveal against the seal and warns if it doesn't match. Reveal from the device you started on, which keeps the secret. People on other apps can't check the seal themselves, and the message tells them their pick comes first.
- **Dice and random:** **+ → Dice and random** rolls up to six dice (d4 to d100), flips a coin, or picks one of your choices. Your device picks with a secure random generator, and everyone, on any app, sees the same result.
- **Link previews:** the first link in a message shows a card with the page's title, summary, and picture, with a play button for videos (YouTube, Vimeo, Twitch) and the same for songs and articles. Turn previews, or pictures from links, off in **Settings → Media, GIFs, and links**.

### Formatting, spoilers, edits, and effects

- **Formatting:** write `*bold*`, `_italic_`, `~strikethrough~`, `` `code` ``, or a ```` ``` ```` code block, the same way as WhatsApp. Other apps show the marks as typed. Marks inside web addresses are left alone.
- **Spoilers:** wrap words in `||double bars||`. FluidMessages covers them until you tap; the inbox and notifications never show them. Other apps show the words with the bars.
- **Edit a message:** touch and hold one of your sent text messages, then **Edit**. FluidMessages replaces the bubble and marks it **Edited**; **Message details** lists earlier versions. Other apps get `✏️ Edited “old text” to: new text`. Only the person who sent a message can edit it.
- **Effects:** type a message, then **+ → Send with effect**. Bubble effects are Slam, Loud, Gentle, and Invisible ink (hidden under sparkles until tapped). Screen effects are Confetti, Balloons, Hearts, and Fireworks. Each plays once when it arrives; touch and hold the message to replay it. Other apps get your words with a note like `(sent with Confetti 🎉)`. With motion turned off in appearance settings (or reduced motion in your browser), effects don't animate.

### Voice message transcripts

Under any voice message, tap **Show transcript**. The phone's own on-device speech recognizer writes it out, so the recording never leaves your phone; nothing is sent to a cloud service. Android asks for microphone permission the first time, because its speech recognizer requires it, but nothing is recorded. Transcripts are kept privately in the app's cache. Phones without on-device speech recognition (including most emulators) say so instead. FluidMessages Web doesn't transcribe, because browsers have no private way to do it.

### Contact cards

**+ → Contact** shares a contact as a vCard, the standard every phone understands. In FluidMessages it shows as a card with the name, numbers, and emails, plus **Add to contacts** and **Call**. FluidMessages Web shows the same card with **Save contact (.vcf)**.

### Editing photos

Tap **Edit** on a photo waiting to send, **Edit** in the full-screen viewer on any photo in the chat, or **+ → Edit a photo**. The editor has:

- **Crop:** free or square, 4:3, 3:4, 16:9, and 9:16, plus rotate and flip.
- **Adjust:** brightness, contrast, saturation, warmth, tint, and fade.
- **Filters:** Vivid, Mono, Noir, Sepia, Warm, Cool, Fade, Drama, Intense, and Invert. Adjustments apply on top of the filter.
- **Draw:** pen, pencil, marker, highlighter, and eraser, in 12 colors and any size.
- **Text:** eight fonts (Bold, Classic, Heavy, Serif, Typewriter, Handwritten, Casual, Small caps), outlined, boxed, or plain, in any color. Tap the picture to add words, drag to move, pinch to resize or turn, and double-tap to change the words. Layouts add top and bottom text, a title bar above, a caption bar below, or a label.
- **Images:** add another photo or one of your stickers, then move, resize, and turn it.
- **Size:** stretch wider or taller, and add space on any side in a color you pick.

Undo and redo cover every step, sliders included. **Send** sends the edit straight to the chat; **Done** puts it in the message box so you can add words first. Edits of a GIF are sent as a still picture.

### Viewing photos and videos

Tap a photo, GIF, or video in a chat to open it full screen. Pinch or double-tap to zoom, drag to look around, swipe down to close, and use **Share** or **Open** to hand it to another app. Videos play right in the viewer.

### Weak signal and photo quality

Messages to internet services that can't send because the connection drops are kept as **Queued · sends when you're back online** and go out automatically when the phone is online again. Texts the carrier holds show **Queued · sends when you have signal**. **Settings → Media, GIFs, and links → Photo quality** chooses **Automatic** (smaller photos on a slow connection), **Data saver**, **Standard**, or **High quality** (up to 4096 pixels). MMS still shrinks photos to fit the carrier's limit.

### Contact info and resync

Conversation details and a contact's page in the Phone tab list every saved phone number, email, SIP address, website, address, and birthday, with Android's labels (mobile, work, home…). Tap one to message, call, email, open, or copy it. If messages ever look out of date, **Resync** in the inbox's filter menu reloads texts, contacts, and internet accounts.

## Calls

The bottom tabs are **Phone** on the left and **Messages** on the right. The app reopens to whichever one you used last. **Phone** follows the iPhone Phone app's layout in your current theme:

- **Favorites:** people you've starred.
- **Recents:** calls, with **All** and **Missed** views.
- **Contacts:** a searchable list, with tags for each way you can reach someone (Phone, Matrix, Email, and so on).
- **Keypad:** opens from the round green button on every list, as in Google's Phone app. A typed number that belongs to a contact shows their name.

The sample inbox has its own Phone tab, with made-up contacts and Phone and Matrix calls.

Tap someone's photo or ⓘ to open their page. It shows their phone numbers and accounts, every chat with them and its latest message, and recent calls. It also has a star to add them to Favorites, and **Message**, **Call**, and **Video** buttons (video only for Matrix). Conversation details have the same buttons. When there's more than one way to do something, the button asks which one and can remember your choice; hold the button to choose again. Phone-network calls go through Android's call screen. A Matrix chat's details also offer **Voice call** and **Video call**.

Matrix calls appear in Recents next to phone calls, and **Settings → Chats and calls → Calls → Show regular phone calls** can leave Android's phone calls out. **Meeting links**, turned on in the same page, call anyone on any service. FluidMessages sends a link to a Jitsi Meet meeting in the chat and joins it. Jitsi Meet is free and open source, and you can use your own server. These calls show a yellow lock, because the meeting server can see who joins. The call screen uses the chat's wallpaper and shows the call's lock status at the top. In the sample inbox, calls open the same screen as a pretend call. The keypad switches between **Number** and **Username**, so you can call a Matrix ID directly. **Settings → Chats and calls → Calls** has a switch for each way of calling (Phone, Matrix, XMPP, SIP, and meeting links), separate from messaging. For example, Matrix can be off for messages but on for calls.

- **XMPP calls** use Jingle (XEP-0166/0167, with call ringing from XEP-0353), the same as Conversations and Dino. They need XMPP connected from this phone, and are end-to-end encrypted with DTLS-SRTP.
- **SIP calls** use [JsSIP](https://jssip.net) (MIT, about 234 KB) over a secure WebSocket. Add the account details from your SIP provider, which must accept `wss://` connections. SIP can also call regular phone numbers through your provider. These calls show a yellow lock, because the provider can access them. **Receive SIP calls** controls when the phone stays signed in to your provider so SIP calls can ring it: **Off**, **While FluidMessages is open**, or **Always**. Always shows Android's ongoing notification and uses a little more battery. One SIP call rings at a time; other callers hear busy.
- **Meeting links** are on by default. When you start one, you choose which chat gets the link (any way you can message that person), copy it instead, or don't send it. In FluidMessages, on the phone and the web, the link shows as a call card with a **Join** button. Other apps see a plain link ("📹 Join my video call: https://…").
- **During a call** you get mute, speaker, camera on/off and flip, a keypad for touch tones (it replaces the other buttons, and **Hide** brings them back) and hold (SIP), and **Record** on Matrix, XMPP, and SIP calls. The screen also shows a timer and the call's lock status, over the chat's wallpaper. Recordings save to your phone's Recordings folder (voice) or Movies folder (video), under FluidMessages, only inside the app, or any folder you pick, including an SD card or a folder a storage app offers. Choose separately for voice and video calls in **Settings → Calls → Call recording**, which can also send "I started recording this call" in the chat. Android doesn't let apps record regular phone calls.

Internet calls use WebRTC, with Matrix VoIP (version 1) events for signaling, so the other person can answer in FluidMessages, Element Web or desktop, or another Matrix app that supports one-to-one calls. Media goes directly between devices. The homeserver's TURN relay is used when both sides are behind strict networks. Chats linked on the phone ring while FluidMessages is open, or when background sync runs. Companion-linked chats ring while the app is open. FluidMessages doesn't replace the default phone app, and SIP and XMPP calls aren't supported yet.

## Privacy and security

**Settings → Privacy and security**:

- **App lock** asks for your fingerprint, face, or screen lock and keeps FluidMessages out of screenshots and recents.
- **Stored messages are encrypted.** Internet and picture messages, saved accounts, and the pairing token are sealed with AES-256-GCM. The key is wrapped by Android Keystore.
- **Private folder** moves conversations, or whole services, out of the inbox into the **Private folder**. Every inbox's filter menu lists Private folder, set up or not. A wrong passcode opens an empty folder, so nobody can tell whether anything is hidden. Open it with your fingerprint or screen lock when that's on, or type the passcode into search. Switching to another folder asks for the passcode again before it closes (you can turn that off in its settings), and it always closes when you leave the app. Texts in the private folder move out of Android's shared SMS store into an encrypted vault, which uses an EC P-256 public key, so new texts are stored while it's closed. Their alerts name no one. They never sync to FluidMessages Web, which also hides its own copies. You can leave the section out of Settings, so it only appears after you enter the passcode. A forgotten passcode can't be recovered.
- **Emergency erase passcode** is a second passcode. Typed instead of your real one (in search, Private folder, or a chat lock), it erases everything in FluidMessages at once.
- **Decoy mode** is for when someone else is using your phone. It swaps the inbox, the Phone tab, and the contacts you can message for pretend chats plus only the real people you allow. Turn it on from the inbox filter menu, the app icon's **Quick hide** shortcut, or the **Quick hide** tile in your phone's quick settings, which works even outside the app. To end it, long-press the **Messages** title, tap the tile again, or type your private folder passcode into search; each asks for the passcode. Pretend chats are never sent.
- **Chat and protocol codes.** A chat's details can add a code, which hides its previews and asks for the code before opening. **Protocol codes** do the same for every chat on a protocol.
- **FluidMessages encryption** works when both people use FluidMessages and enter the same key, made in the app or agreed in person. Turn it on by default for each protocol: SMS, MMS, Matrix, XMPP, Email, or IRC. A chat's own choice in its **Security** section always overrides the default. Keys are hidden until you tap the eye, and can be copied. Messages sent from the browser companion aren't encrypted this way; the companion says so in those chats. Other apps and the carrier see `FM1:` followed by Base64 of a key id, a nonce, and AES-256-GCM ciphertext. The key is PBKDF2-HMAC-SHA256 with 100,000 rounds and the salt `FluidMessages SMS 1`. Encrypted texts are limited to 1,000 characters.
- **Disappearing messages** remove messages after a timer you set, from 30 seconds to a year, per chat or for every chat. They're removed on the phone and the companion. Your own Matrix messages are redacted, and your own XMPP messages are retracted (XEP-0424). Encrypted texts, Matrix messages, and XMPP messages carry the timer, so FluidMessages on the other side removes them too. Other apps keep their copies.

Each chat's header and **Security** section show a colored lock:

- **Green:** end-to-end or FluidMessages encryption.
- **Yellow:** encrypted in transit only.
- **Red, unlocked:** not encrypted.

A chat's details group encryption, disappearing messages, hiding, and its chat code under **Security**. Rows that only give information show an ⓘ instead of an arrow.

**Settings → Reset** has two options:

- **Reset settings to defaults** keeps your messages, accounts, companion, the private folder, codes, and keys.
- **Erase everything** removes all of FluidMessages' data from the phone. Texts in Android's message store stay.

The companion's settings have their own reset.

### Group members

A group's details can **Add people** or **Remove someone**:

- **Matrix:** invite and kick. Removing someone needs permission in the room.
- **XMPP:** invite an address, or kick a nickname as a moderator.
- **IRC:** `INVITE` and `KICK`, which may need operator status.
- **Group texts:** carriers treat every set of people as its own group, so adding or removing someone starts a new group text.

## Read-only mode

Turn off every way to message in **Settings → Ways to message** and FluidMessages becomes read-only: you can still read every chat, but the compose button is gone, nothing can be sent, and the inbox says why. Turn off every way to call in **Settings → Calls** and the Phone tab still shows your calls and contacts, but the keypad and call buttons go away and no call can be placed. Incoming messages and calls still arrive. Either one can be on without the other.

## Backups

Open **Settings → Backups**. It has three groups: **On this phone** (export, share, restore, or schedule a folder), **Your own server** (SFTP, WebDAV, FTPS, or FTP), and **Cloud services** (OneDrive, Google Drive, S3). Select one or more categories in each backup or schedule: SMS, global theme and layout, sounds and effects, per-chat themes, conversation settings, wallpaper photos, and MMS, internet messages, and protocol settings. That last category embeds attachments up to 2 MB each (24 MB in all) and never includes account passwords. The wallpaper photo category embeds images for a restore on another device. Message attachments and group chats require MMS or RCS support. Backups combine the selected categories in one compact archive. Encryption is enabled by default and uses AES-256-GCM with a salted PBKDF2-HMAC-SHA256 key. Restore previews the archive and merges SMS into the Android provider with duplicate checking. Local and cloud schedules are independent, use a 15-minute to 7-day interval set with separate Hours and Minutes fields, and run when Android allows background work. To update a local schedule’s contents or interval, open **Local folder schedule**. The current folder stays selected, and an existing password can be kept. Scheduled archive passwords are stored using Android Keystore.

### Back up through an Android storage app

### Your own file server

Choose **Add a file server** to back up straight from the phone to a server you control, with nothing in between. Pick the type, then enter the address, port, username, password, and folder:

- **SFTP** (port 22): any server you can reach over SSH, including most NAS boxes and hosting accounts. The first connection remembers the server's key; if it ever changes, backups stop with a warning, the way SSH does.
- **WebDAV** (port 443, HTTPS only): Nextcloud, ownCloud, Synology, QNAP, and similar. For Nextcloud, use an address like `cloud.example.com/remote.php/dav/files/you`.
- **FTPS** (port 21): FTP with explicit TLS.
- **FTP** (port 21): not encrypted in transit, so use it only on your own network, with an encrypted backup.

Every port can be changed. **Save** tests the connection first; **Test connection** only checks it. The folder is created if it's missing. Each server has **Back up now**, its own **Backup schedule**, and **Restore newest backup**, which downloads the most recent `.fmb` archive and shows what's in it before restoring. Server passwords and scheduled archive passwords are stored encrypted with Android Keystore and are never included in backups.

**Backup file name** in Backups defaults to a date-stamped filename. A fixed filename replaces the same archive in scheduled folders and companion cloud destinations. For manual exports and sharing, Android and the chosen storage app handle existing files. Update the companion alongside Android to use fixed cloud filenames.

**Export a file** and **Restore from a file** open Android's file picker. You can use a cloud app that makes its files available through the picker. **Share to a storage app** hands an archive to an app you select in the share menu. **Local folder schedule** also works with a storage app that offers a writable folder via the picker. These options work without pairing a companion. [Android storage access documentation](https://developer.android.com/training/data-storage/shared/documents-files)

### Phone or companion per cloud destination

Open a provider in **Settings → Backups**, then choose **Phone** or **Companion** in **Backup method**. Phone lets you save or restore through Android’s file picker, hand off a backup to a storage app, or schedule backups to a writable folder. Select a cloud folder if the provider exposes one, or choose a device folder synced by a storage app. Provider credentials and sync for this route are handled by the selected Android app. Phone and companion schedules are separate and stay configured when you switch the selector.

### OneDrive, Google Drive, and S3 through the companion

Cloud destinations use your paired HTTPS companion. The companion runs rclone and stores the provider credentials in its private data volume. Configure each provider you want from your companion host:

```sh
docker compose up -d --build
docker compose exec fluidmessages rclone config
```

Create rclone remotes for [OneDrive](https://rclone.org/onedrive/), [Google Drive](https://rclone.org/drive/), and [S3](https://rclone.org/s3/) as needed. Enter the remote names and backup locations in your Compose environment. For example:

```dotenv
FLUID_BACKUP_ONEDRIVE=onedrive:FluidMessages
FLUID_BACKUP_DRIVE=drive:FluidMessages
FLUID_BACKUP_S3=s3:your-bucket/FluidMessages
```

Run `docker compose up -d` again. If you use a reverse proxy, allow roughly 65 MiB request bodies and 200-second upstream reads for backup transfers. On Android, open **Settings → Backups** and choose a provider. **Back up now** lets you select categories and upload a one-time backup with no schedule. **Backup schedule** sets a recurring interval and categories for that provider, and **Run saved schedule now** uses those saved settings. Cloud schedules are tied to the paired companion address. Update them explicitly after changing servers. **Back up all cloud schedules** runs the configured phone folder and companion schedules. **Restore newest backup** finds and downloads the most recent FluidMessages archive for that provider and asks for confirmation before importing it. Re-pair a new phone with the same HTTPS companion and enter the archive password to restore there.


FluidMessages downloads MMS, including group MMS, over mobile data. Replies in a group join the group you started. Android often doesn't know the phone's own number, so FluidMessages learns it from the first group or picture message addressed to you, or reads it when you allow phone number access. Group messages that arrived before that move into the right group once it's learned.
