# Privacy Policy for Dock

**Effective date:** November 1, 2026

Dock is an open-source document scanner for Android (package name `com.solo.dock`), developed by
[confusedmulga](https://github.com/confusedmulga). This policy explains what happens to your
information when you use the app.

## The short version

Dock does not collect, store, share or sell any personal information. Everything you scan and
every PDF you make stays on your device. Dock has no accounts, ads, analytics, crash reporting
or tracking. It does not even have the `INTERNET` permission, so it cannot send anything off
your phone.

## Information Dock does not collect

Dock does not collect any of the following, and has no way to send them anywhere:

- Your name, email address, phone number or any other contact details
- Your location
- Device identifiers, advertising IDs or usage statistics
- Your scans, photos, PDFs or their contents
- Crash logs or diagnostics

The app has no servers and no third-party SDKs for analytics, advertising or crash reporting.

## Permissions and what they are used for

**Camera.** Used to show the live preview, find the edges of a page and take the photo of it.
Preview frames are analysed in memory on your device, by OpenCV and a small corner-finding model
built into the app, and thrown away. Only the pages you choose
to capture are kept. You can refuse or revoke this permission at any time and still import
photos instead.

**Photos you pick.** When you import images, Dock uses the Android photo picker. The app only
gets access to the specific images you select, never your whole gallery. Dock does not request
the storage or media permissions.

**The folder you choose.** When you first open Dock, you pick a folder for your documents
(Documents/Dock by default) through the Android system folder picker. Dock keeps access to that
folder so it can save new PDFs there and list, open, rename, move, merge, split, compress and
delete the files in it when you ask it to. Dock does not read anything outside that folder
except images you pick yourself.

## Where your data is kept

- **Your documents** are saved as ordinary PDF files in the folder you chose. They belong to you
  and stay there until you delete them, including after Dock is uninstalled.
- **Settings.** Dock stores the location of your chosen folder in its private app storage.
- **Drafts and temporary files.** Pages you are still working on, and intermediate files created
  while a tool runs, are kept in Dock's private app storage and cache. They are removed when you
  save or discard the scan, when the tool finishes, or when the app is uninstalled.
- **PDF metadata.** PDFs that Dock creates record "Dock" as the producing app and the time they
  were created. Dock does not add your name, device details or location to them.
- **Recognized text.** When searchable text is on (the default for new scans, and switchable for
  each PDF), Dock reads the text on your pages with models built into the app, entirely on your
  device, and writes it into the PDF as an invisible layer, along with whether you turned it on
  or off. Anyone you share such a PDF with can search and copy that text. Turning it off for a
  PDF removes the text again.

Dock opts out of Android's automatic app backup, so its private data is not uploaded to your
backup account. The PDFs in your chosen folder are regular files, so if you use a backup or sync
service for that folder, that service's own privacy policy applies.

## Sharing

Dock only shares a file when you tap Share or Open with. The file is handed to the app you pick
from the Android share sheet. What happens to it after that is governed by that app's privacy
policy, not this one.

## Google Play

If you install Dock from Google Play, Google may collect information about the installation and,
if you have allowed it in your device settings, crash and performance data. This is handled by
Google under the [Google Privacy Policy](https://policies.google.com/privacy). Dock's developer
may see aggregated, anonymous statistics from this in the Google Play Console, such as install
counts and crash rates. These never include your documents or personal details.

## Children's privacy

Dock does not collect personal information from anyone, including children under 13.

## Deleting your data

- Delete individual documents from inside Dock, or with any file manager.
- Clear Dock's settings, drafts and temporary files from **Settings › Apps › Dock › Storage &
  cache › Clear storage**, or by uninstalling the app.

## Open source

Dock's source code is public at
[github.com/confusedmulga/Dock](https://github.com/confusedmulga/Dock), so anyone can check that
the app does what this policy says.

## Changes to this policy

If this policy changes, the updated version will be published at this address with a new
effective date. The full history of changes is available in the repository's commit history.

## Contact

Questions about this policy or about Dock can be raised by opening an issue at
[github.com/confusedmulga/Dock/issues](https://github.com/confusedmulga/Dock/issues).
